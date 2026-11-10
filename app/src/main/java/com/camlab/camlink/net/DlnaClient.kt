package com.camlab.camlink.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Talks to the camera's DLNA / UPnP media server.
 *
 * Discovery is SSDP (M-SEARCH) and falls back to probing the Wi-Fi gateway and
 * the usual camera addresses. Browsing is UPnP ContentDirectory Browse over
 * SOAP; files are plain HTTP downloads from the URLs in the DIDL-Lite reply.
 */
class DlnaClient(private val context: Context) {

    private val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val baseProbeHttp: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(1800, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
        .build()

    /**
     * A client whose sockets are pinned to the camera's Wi-Fi network. Without
     * this, Android may route the request over mobile data while the camera's
     * access point has no internet.
     */
    private fun http(probe: Boolean): OkHttpClient {
        val base = if (probe) baseProbeHttp else baseHttp
        val network = findWifiLink(context)?.network ?: return base
        return base.newBuilder()
            .socketFactory(WifiBoundSocketFactory(network))
            .build()
    }

    suspend fun discover(
        timeoutMs: Long = 6000,
        onStatus: (String) -> Unit = {}
    ): List<CameraDevice> =
        withContext(Dispatchers.IO) {
            lastDescriptionStatus = null
            val locations = ssdpLocations(timeoutMs)
            lastSsdpLocations = locations
            if (locations.isNotEmpty()) {
                onStatus("Camera answered. Reading its description…")
            }
            // Bound the description work: an unreachable HTTP port should fail
            // the search in tens of seconds, not minutes.
            val deadline = System.currentTimeMillis() + DESCRIPTION_BUDGET_MS
            locations.mapNotNull { location ->
                runCatching {
                    fetchDescription(
                        location,
                        deadline - System.currentTimeMillis(),
                        onStatus
                    )
                }.getOrNull()
            }
        }

    /** Try a user supplied host such as `192.168.1.1` or `192.168.1.1:7676`. */
    suspend fun connect(
        input: String,
        onStatus: (String) -> Unit = {}
    ): CameraDevice? = withContext(Dispatchers.IO) {
        lastDescriptionStatus = null
        val deadline = System.currentTimeMillis() + MANUAL_BUDGET_MS
        for (url in manualCandidates(input)) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining < 500) break
            val device = runCatching {
                fetchDescription(url, remaining, onStatus)
            }.getOrNull()
            if (device?.contentDirectoryUrl != null) return@withContext device
        }
        null
    }

    /**
     * LOCATION values from the last SSDP run. When these are non-empty the
     * camera answered discovery, so the caller should not spend another minute
     * probing gateways after the description fetch failed.
     */
    @Volatile
    var lastSsdpLocations: Set<String> = emptySet()
        private set

    /**
     * Last HTTP status seen while fetching a device description. 401/403 means
     * the camera is up but refused this phone; that needs a different message
     * and a different fix than "no camera found".
     */
    @Volatile
    var lastDescriptionStatus: Int? = null
        private set

    /** Addresses worth trying when SSDP is blocked: gateway plus camera defaults. */
    fun likelyHosts(): List<String> {
        val out = LinkedHashSet<String>()
        findWifiLink(context)?.gateway?.let { out += it }
        out += listOf("192.168.1.1", "192.168.0.1", "10.0.0.1", "192.168.101.1")
        return out.toList()
    }

    suspend fun browseAll(
        device: CameraDevice,
        onStatus: (String) -> Unit = {}
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val control = device.contentDirectoryUrl
            ?: throw IOException("Camera has no ContentDirectory service")
        val client = http(probe = false)

        val items = ArrayList<MediaItem>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add("0" to 0)

        while (queue.isNotEmpty()) {
            val (objectId, depth) = queue.removeFirst()
            if (!visited.add(objectId)) continue

            var start = 0
            while (true) {
                val page = browse(control, objectId, start, PAGE_SIZE, client)
                items += page.content.items

                if (depth < MAX_DEPTH) {
                    page.content.containers.forEach { queue.add(it.id to depth + 1) }
                }

                val returned = page.numberReturned
                if (returned <= 0) break
                start += returned
                if (page.totalMatches <= 0 || start >= page.totalMatches) break
            }
            onStatus("Read ${items.size} files…")
        }

        items.distinctBy { it.id }
            .sortedWith(compareByDescending<MediaItem> { it.date ?: "" }.thenBy { it.title })
    }

    private fun browse(
        controlUrl: String,
        objectId: String,
        start: Int,
        count: Int,
        client: OkHttpClient
    ): BrowsePage {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
            append("<s:Body>")
            append("""<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">""")
            append("<ObjectID>").append(escape(objectId)).append("</ObjectID>")
            append("<BrowseFlag>BrowseDirectChildren</BrowseFlag>")
            append("<Filter>*</Filter>")
            append("<StartingIndex>").append(start).append("</StartingIndex>")
            append("<RequestedCount>").append(count).append("</RequestedCount>")
            append("<SortCriteria></SortCriteria>")
            append("</u:Browse></s:Body></s:Envelope>")
        }

        val request = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
            )
            .post(body.toRequestBody(SOAP_MEDIA_TYPE))
            .build()

        var response = client.newCall(request).execute()
        // MobileLink can refuse the first Browse even after the description
        // worked, until the phone is on the camera's allow list. One retry
        // with the Samsung identity is enough for that handshake window.
        if (response.code == 401 || response.code == 403) {
            response.close()
            val samsung = SamsungLink.dlnaHeaders(SamsungLink.phoneMac())
            response = client.newCall(
                request.newBuilder().apply {
                    samsung.forEach { (name, value) -> header(name, value) }
                }.build()
            ).execute()
        }
        response.use { reply ->
            if (!reply.isSuccessful) {
                throw IOException("Browse failed: HTTP ${reply.code}")
            }
            val xml = reply.body?.string().orEmpty()
            return DlnaXml.parseBrowseResponse(xml, controlUrl)
        }
    }

    private fun fetchDescription(
        url: String,
        budgetMs: Long,
        onStatus: (String) -> Unit = {}
    ): CameraDevice? {
        val xml = descriptionXml(url, budgetMs, onStatus) ?: return null
        return DlnaXml.parseDescription(xml, url)
    }

    /**
     * Fetch the device description with a raw HTTP request.
     *
     * HTTP/1.1 is tried first: this camera answers it immediately, while an
     * HTTP/1.0 request can hang until the timeout. `Connection: close` avoids
     * the server's broken keep-alive, and a response without Content-Length is
     * accepted when the server goes quiet (see RawHttp).
     *
     * A 401/403 means the camera refused this phone. In Samsung Link mode that
     * is the DLNA access-control list; in MobileLink mode the camera also wants
     * the Samsung app's introduction first (see [samsungPair]) and then shows
     * an "allow this device" prompt. Both are retried here while the prompt is
     * up.
     */
    private fun descriptionXml(
        url: String,
        budgetMs: Long,
        onStatus: (String) -> Unit
    ): String? {
        val network = findWifiLink(context)?.network
        val deadline = System.currentTimeMillis() + budgetMs
        val mac = SamsungLink.phoneMac()
        var denied = false

        fun attempt(version: String, headers: Map<String, String>): String? {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining < 600) return null
            val headerTimeout = minOf(HEADER_TIMEOUT_MS, remaining.toInt())
                .coerceAtLeast(1000)
            val response = runCatching {
                RawHttp.get(
                    network = network,
                    url = url,
                    version = version,
                    connectTimeoutMs = minOf(4000, headerTimeout),
                    headerTimeoutMs = headerTimeout,
                    idleTimeoutMs = 3000,
                    headers = headers
                )
            }.getOrNull() ?: return null

            lastDescriptionStatus = response.status
            denied = response.status == 401 || response.status == 403
            if (response.status in 200..299) {
                val xml = response.text
                if (looksLikeDescription(xml)) return xml
            }
            return null
        }

        attempt("HTTP/1.1", emptyMap())?.let { return it }
        if (!denied) {
            attempt("HTTP/1.0", emptyMap())?.let { return it }
        }
        if (!denied) return null

        // MobileLink: introduce CamLink the way the Samsung app does, which is
        // what makes the camera pop up its "allow this device" question.
        onStatus("Camera found. Asking it to allow CamLink…")
        samsungPair(url, deadline, onStatus)?.let { return it }

        // Give the user time to press Allow / OK on the camera.
        val retryHeaders = SamsungLink.dlnaHeaders(mac)
        while (denied && System.currentTimeMillis() + 3500 < deadline) {
            onStatus("On the camera, tap Allow / OK for this phone…")
            runCatching { Thread.sleep(2500) }
            attempt("HTTP/1.1", retryHeaders)?.let { return it }
        }
        return null
    }

    /**
     * Ask a MobileLink camera to pair, using only GET requests.
     *
     * The request paths come from the original Samsung app: `/smp_2_` is its
     * entry point and `/mode/control` is the newer mode handshake. Some replies
     * are the device description itself, so a successful one can be used
     * directly; otherwise the caller retries `description.xml` with the
     * Samsung `User-Agent`/`Access-Method` headers.
     */
    private fun samsungPair(
        descriptionUrl: String,
        deadline: Long,
        onStatus: (String) -> Unit
    ): String? {
        val link = findWifiLink(context)
        val network = link?.network
        val uri = runCatching { URI(descriptionUrl) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        val mac = SamsungLink.phoneMac()
        val headers = SamsungLink.handshakeHeaders(mac, link?.address)

        val targets = LinkedHashSet<String>()
        SamsungLink.HANDSHAKE_PATHS.forEach { targets += "http://$host:$port$it" }
        targets += "http://$host:$port/description.xml"
        SamsungLink.PORTS.filter { it != port }.forEach { other ->
            targets += "http://$host:$other/smp_2_"
        }

        for (target in targets) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining < 1500) break
            val response = runCatching {
                RawHttp.get(
                    network = network,
                    url = target,
                    version = "HTTP/1.0",
                    connectTimeoutMs = minOf(1000, remaining.toInt()).coerceAtLeast(600),
                    headerTimeoutMs = minOf(3500, remaining.toInt()).coerceAtLeast(1000),
                    idleTimeoutMs = 1500,
                    headers = headers
                )
            }.getOrNull() ?: continue

            if (response.status in 200..299) {
                val xml = response.text
                if (looksLikeDescription(xml)) return xml
            }
        }
        onStatus("Waiting for the camera to allow this phone…")
        return null
    }

    private fun looksLikeDescription(xml: String): Boolean {
        if (xml.isBlank()) return false
        val lower = xml.lowercase()
        return lower.contains("<device") ||
            lower.contains("servicelist") ||
            lower.contains("friendlyname")
    }

    private fun ssdpLocations(timeoutMs: Long): Set<String> {
        val locations = LinkedHashSet<String>()
        val link = findWifiLink(context)

        val wifiManager = context.applicationContext
            .getSystemService(WifiManager::class.java)
        val lock = wifiManager?.createMulticastLock("camlink-ssdp")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 350
                broadcast = true
            }
            link?.network?.let { network -> runCatching { network.bindSocket(socket) } }

            val address = InetAddress.getByName(SSDP_ADDRESS)
            val payloads = SEARCH_TARGETS.map { target -> mSearch(target).toByteArray(Charsets.US_ASCII) }
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L

            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextSend) {
                    payloads.forEach { payload ->
                        runCatching {
                            socket.send(DatagramPacket(payload, payload.size, address, SSDP_PORT))
                        }
                    }
                    nextSend = now + 1200
                }
                try {
                    val buffer = ByteArray(4096)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseLocation(String(buffer, 0, packet.length))?.let { locations.add(it) }
                } catch (_: SocketTimeoutException) {
                    // keep waiting until the deadline
                }
            }
        } catch (_: Exception) {
            // discovery is best-effort; the manual path still works
        } finally {
            runCatching { socket?.close() }
            runCatching { lock?.release() }
        }
        return locations
    }

    private fun parseLocation(response: String): String? {
        for (line in response.lineSequence()) {
            if (line.startsWith("LOCATION:", ignoreCase = true)) {
                return line.substringAfter(':').trim().takeIf { it.startsWith("http") }
            }
        }
        return null
    }

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val PAGE_SIZE = 200
        private const val MAX_DEPTH = 6
        private const val HEADER_TIMEOUT_MS = 10000
        private const val DESCRIPTION_BUDGET_MS = 30000L
        private const val MANUAL_BUDGET_MS = 20000L
        private const val USER_AGENT = "CamLink/1.5 UPnP/1.1"

        private val SOAP_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

        private val MSEARCH_TEMPLATE =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: %s\r\n" +
                "USER-AGENT: $USER_AGENT\r\n" +
                "\r\n"

        /** Ask for root devices as well as media servers; cameras vary in what they answer. */
        private val SEARCH_TARGETS = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "ssdp:all"
        )

        private fun mSearch(target: String): String = MSEARCH_TEMPLATE.format(target)

        /** Candidate description URLs for a host or host:port. */
        fun manualCandidates(input: String): List<String> {
            val host = input.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')
            if (host.isBlank()) return emptyList()

            val hasPort = host.substringAfterLast(':', "").toIntOrNull() != null
            val candidates = LinkedHashSet<String>()
            if (hasPort) {
                candidates += "http://$host/description.xml"
                candidates += "http://$host/rootDesc.xml"
                candidates += "http://$host/smp_2_"
                candidates += "http://$host/smp_5_"
                candidates += "http://$host/smp_6_"
                candidates += "http://$host/smp_7_"
                candidates += "http://$host/smp_8_"
            } else {
                candidates += "http://$host:7676/smp_2_"
                candidates += "http://$host:7676/smp_6_"
                candidates += "http://$host:7676/smp_5_"
                candidates += "http://$host:7676/smp_7_"
                candidates += "http://$host:7676/smp_8_"
                candidates += "http://$host:7676/description.xml"
                candidates += "http://$host/description.xml"
                candidates += "http://$host/rootDesc.xml"
                candidates += "http://$host:49152/description.xml"
                candidates += "http://$host:49153/description.xml"
                candidates += "http://$host:8080/description.xml"
            }
            return candidates.toList()
        }

        private fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
