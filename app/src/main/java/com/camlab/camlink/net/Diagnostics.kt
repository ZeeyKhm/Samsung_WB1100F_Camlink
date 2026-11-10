package com.camlab.camlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Read-only network probe used when automatic discovery fails.
 *
 * It never sends anything except SSDP M-SEARCH, TCP connects and HTTP GETs, so
 * the camera and its card are untouched. The report is plain text so it can be
 * copied out of the phone and sent for analysis.
 */
class Diagnostics(private val context: Context) {

    suspend fun run(onStatus: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        fun line(text: String = "") = out.append(text).append('\n')

        line("CamLink diagnostics")
        line(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        line()

        onStatus("Reading Wi-Fi details…")
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val link = findWifiLink(context)

        line("PHONE NETWORK")
        val active = cm?.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        line("Default route: ${describe(activeCaps)}")

        if (link == null) {
            line("Wi-Fi: none")
            line()
            line("The phone is not connected to a Wi-Fi network.")
            line("Join the camera's Wi-Fi, keep the connection, then run this again.")
            return@withContext out.toString()
        }

        line("Wi-Fi interface: ${link.interfaceName}")
        line(
            "Phone address: ${link.address ?: "—"}" +
                (link.prefixLength?.let { "/$it" } ?: "")
        )
        line("Gateway: ${link.gateway ?: "—"}")
        line("DNS: ${link.dns.joinToString().ifBlank { "—" }}")
        line("All link addresses: ${link.linkAddresses.joinToString().ifBlank { "—" }}")
        if (active != link.network) {
            line("Warning: Android's default route is not this Wi-Fi network.")
            line("CamLink still pins camera traffic to Wi-Fi, but other apps may not.")
        }
        val ssid = runCatching {
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        }.getOrNull()
        if (!ssid.isNullOrBlank() && !ssid.contains("unknown", ignoreCase = true)) {
            line("SSID: ${ssid.trim('"')}")
        }
        line()

        onStatus("Sending SSDP discovery…")
        line("SSDP (M-SEARCH $SSDP_ADDRESS:$SSDP_PORT)")
        val ssdp = ssdp(link, 4000)
        if (ssdp.isEmpty()) {
            line("No UPnP responses.")
        } else {
            ssdp.forEach { line(it.summary()) }
        }
        line()

        val locations = ssdp.mapNotNull { it.location }.distinct().take(4)
        if (locations.isNotEmpty()) {
            onStatus("Reading camera description…")
            line("DEVICE DESCRIPTION (advertised LOCATION)")
            locations.forEach { url ->
                line(probeDescription(link, url))
                line()
            }

            // MobileLink mode refuses plain DLNA until it has seen the Samsung
            // app's introduction, so show what each handshake request returns.
            onStatus("Trying the Samsung MobileLink handshake…")
            line("SAMSUNG MOBILINK HANDSHAKE")
            line(probeSamsung(link, locations.first()))
            line()
        } else {
            // No SSDP: still try the Samsung handshake on the gateway, which is
            // the camera in MobileLink mode.
            val gateway = link.gateway
            if (gateway != null) {
                onStatus("Trying the Samsung MobileLink handshake on the gateway…")
                line("SAMSUNG MOBILINK HANDSHAKE (gateway $gateway)")
                line(probeSamsung(link, "http://$gateway:49152/description.xml"))
                line()
            }
        }

        onStatus("Scanning ${link.subnetLabel} for open ports…")
        val open = scan(link)
        line("OPEN PORTS (${link.subnetLabel}, ${SCAN_PORTS.joinToString()})")
        if (open.isEmpty()) {
            line("No open TCP ports found.")
        } else {
            open.forEach { (host, ports) -> line("$host: ${ports.joinToString()}") }
        }
        line()

        if (open.isNotEmpty()) {
            onStatus("Checking camera web pages…")
            val urls = open.flatMap { (host, ports) -> urlsFor(host, ports) }
            val probes = probeAll(link, urls)
            line("HTTP PROBES")
            probes.forEach { line(it) }
            line()
        }

        line("END")
        out.toString()
    }

    // --- SSDP ---------------------------------------------------------------

    private data class SsdpResponse(
        val source: String,
        val headers: Map<String, String>
    ) {
        val location: String?
            get() = headers["location"]?.takeIf { it.startsWith("http", ignoreCase = true) }

        fun summary(): String = buildString {
            append(source)
            headers["st"]?.let { append("  ST: ").append(it) }
            location?.let { append("\n   LOCATION: ").append(it) }
            headers["server"]?.let { append("\n   SERVER: ").append(it) }
            headers["usn"]?.let { append("\n   USN: ").append(it) }
            headers["cache-control"]?.let { append("\n   CACHE-CONTROL: ").append(it) }
        }
    }

    private fun ssdp(link: WifiLink, timeoutMs: Long): List<SsdpResponse> {
        val responses = LinkedHashMap<String, SsdpResponse>()
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifiManager?.createMulticastLock("camlink-diagnostics")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 300
                broadcast = true
            }
            runCatching { link.network.bindSocket(socket) }

            val target = InetAddress.getByName(SSDP_ADDRESS)
            val payloads = SEARCH_TARGETS.map { st ->
                MSEARCH.format(st).toByteArray(Charsets.US_ASCII)
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L

            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextSend) {
                    payloads.forEach { payload ->
                        runCatching {
                            socket.send(DatagramPacket(payload, payload.size, target, SSDP_PORT))
                        }
                    }
                    nextSend = now + 1200
                }
                try {
                    val buffer = ByteArray(4096)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val source = packet.address?.hostAddress.orEmpty()
                    val text = String(buffer, 0, packet.length)
                    val headers = parseSsdpHeaders(text)
                    if (headers.isEmpty()) continue
                    val response = SsdpResponse(source, headers)
                    // The camera repeats the same advertisements for each M-SEARCH;
                    // key by USN/ST/LOCATION so the report stays readable.
                    val key = listOf("usn", "st", "location")
                        .joinToString("|") { headers[it].orEmpty() }
                        .ifBlank { source + text }
                    responses.putIfAbsent(key, response)
                } catch (_: SocketTimeoutException) {
                    // keep waiting until the deadline
                }
            }
        } catch (_: Exception) {
            // diagnostics are best effort
        } finally {
            runCatching { socket?.close() }
            runCatching { lock?.release() }
        }
        return responses.values.toList()
    }

    private fun parseSsdpHeaders(text: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim().sanitize()
            if (name.isNotBlank() && value.isNotBlank() && name !in headers) {
                headers[name] = value
            }
        }
        return headers
    }

    // --- device description --------------------------------------------------

    /**
     * Fetch the exact URL the camera advertised in SSDP, using raw HTTP/1.0
     * before HTTP/1.1. This is the request that decides whether discovery can
     * work, so print the status, headers, body and parse result.
     */
    private fun probeDescription(link: WifiLink, url: String): String {
        val out = StringBuilder()
        out.append("GET ").append(url).append('\n')

        for (version in listOf("HTTP/1.1", "HTTP/1.0")) {
            val result = runCatching {
                RawHttp.get(
                    network = link.network,
                    url = url,
                    version = version,
                    connectTimeoutMs = 4000,
                    headerTimeoutMs = 12000,
                    idleTimeoutMs = 3000
                )
            }
            val response = result.getOrNull()
            if (response == null) {
                val error = result.exceptionOrNull()
                out.append("   ").append(version).append(" -> ")
                    .append(error?.javaClass?.simpleName ?: "failed").append(": ")
                    .append(error?.message.orEmpty().sanitize().take(160)).append('\n')
                continue
            }

            out.append("   ").append(version).append(" -> ")
                .append(response.reason.ifBlank { "HTTP ${response.status}" })
                .append(" (").append(response.body.size).append(" bytes)")
            val info = response.headers.entries
                .joinToString(", ") { (name, value) -> "$name=$value" }
                .sanitize()
                .take(600)
            if (info.isNotBlank()) out.append(" [").append(info).append(']')
            out.append('\n')

            if (response.status == 401 || response.status == 403) {
                out.append("   note: camera refused this client — check DLNA Access Control ")
                    .append("on the camera\n")
            }

            val xml = response.text
            if (xml.isNotBlank()) {
                out.append("   body: ").append(xml.sanitize().take(1200)).append('\n')
                val device = DlnaXml.parseDescription(xml, url)
                if (device != null) {
                    out.append("   parse: name=").append(device.friendlyName.ifBlank { "—" })
                    out.append(", model=").append(device.modelName.ifBlank { "—" })
                    out.append(", manufacturer=").append(device.manufacturer.ifBlank { "—" })
                    out.append('\n')
                    out.append("   ContentDirectory: ")
                        .append(device.contentDirectoryUrl ?: "missing").append('\n')
                } else {
                    out.append("   parse: not a UPnP device description\n")
                }
            }
            return out.toString().trimEnd()
        }
        return out.toString().trimEnd()
    }

    // --- Samsung MobileLink handshake ---------------------------------------

    /**
     * Send the same GETs the Samsung SMART CAMERA app uses in MobileLink mode.
     * Read-only: the paths are fetched, never posted to.
     */
    private fun probeSamsung(link: WifiLink, descriptionUrl: String): String {
        val out = StringBuilder()
        val uri = runCatching { URI(descriptionUrl) }.getOrNull()
            ?: return "Could not parse $descriptionUrl"
        val host = uri.host ?: return "Could not parse $descriptionUrl"
        val port = if (uri.port > 0) uri.port else 80
        val mac = SamsungLink.phoneMac()

        out.append("Phone MAC sent: ").append(mac).append('\n')
        out.append("Headers: User-Agent: SEC_RVF_ML_<mac>, Access-Method: manual\n")

        val targets = LinkedHashSet<String>()
        SamsungLink.HANDSHAKE_PATHS.forEach { targets += "http://$host:$port$it" }
        targets += "http://$host:$port/description.xml"
        SamsungLink.PORTS.filter { it != port }.forEach {
            targets += "http://$host:$it/smp_2_"
        }

        var denied = false
        for (target in targets) {
            out.append(target).append('\n')
            val result = runCatching {
                RawHttp.get(
                    network = link.network,
                    url = target,
                    version = "HTTP/1.0",
                    connectTimeoutMs = 900,
                    headerTimeoutMs = 3500,
                    idleTimeoutMs = 1500,
                    headers = SamsungLink.handshakeHeaders(mac, link.address)
                )
            }
            val response = result.getOrNull()
            if (response == null) {
                val error = result.exceptionOrNull()
                out.append("   -> ").append(error?.javaClass?.simpleName ?: "failed")
                    .append(": ")
                    .append(error?.message.orEmpty().sanitize().take(120))
                    .append('\n')
                continue
            }

            out.append("   -> ")
                .append(response.reason.ifBlank { "HTTP ${response.status}" })
                .append(" (").append(response.body.size).append(" bytes)")
            val info = response.headers.entries
                .joinToString(", ") { (name, value) -> "$name=$value" }
                .sanitize()
                .take(400)
            if (info.isNotBlank()) out.append(" [").append(info).append(']')
            out.append('\n')

            if (response.status == 401 || response.status == 403) denied = true
            val body = response.text
            if (body.isNotBlank()) {
                out.append("   body: ").append(body.sanitize().take(600)).append('\n')
            }
        }

        if (denied) {
            out.append("note: still denied — on the camera screen choose Allow / OK ")
                .append("for this phone, then run Find camera\n")
        }
        return out.toString().trimEnd()
    }

    // --- port scan ----------------------------------------------------------

    private suspend fun scan(link: WifiLink): Map<String, List<Int>> = coroutineScope {
        val hosts = subnetHosts(link.address)
        if (hosts.isEmpty()) return@coroutineScope emptyMap()

        val semaphore = Semaphore(24)
        val found = hosts
            .flatMap { host -> SCAN_PORTS.map { port -> host to port } }
            .map { (host, port) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (tcpOpen(link.network, host, port)) host to port else null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()

        found
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ports) -> ports.sorted() }
            .toSortedMap(compareBy { it.substringAfterLast('.').toIntOrNull() ?: 0 })
    }

    private fun tcpOpen(network: Network, host: String, port: Int): Boolean {
        val socket = Socket()
        return try {
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun subnetHosts(address: String?): List<String> {
        val parts = address?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return emptyList()
        if (parts.size != 4) return emptyList()
        val base = "${parts[0]}.${parts[1]}.${parts[2]}"
        return (1..254)
            .map { "$base.$it" }
            .filter { it != address }
    }

    // --- HTTP probes --------------------------------------------------------

    private fun urlsFor(host: String, ports: List<Int>): List<String> =
        ports.flatMap { port ->
            HTTP_PATHS.map { path -> "http://$host:$port$path" }
        }

    private suspend fun probeAll(link: WifiLink, urls: List<String>): List<String> =
        coroutineScope {
            val client = OkHttpClient.Builder()
                .connectTimeout(1200, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .writeTimeout(1000, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .socketFactory(WifiBoundSocketFactory(link.network))
                .build()
            val semaphore = Semaphore(6)

            urls.mapIndexed { index, url ->
                async(Dispatchers.IO) {
                    index to semaphore.withPermit { probe(client, url) }
                }
            }.awaitAll()
                .sortedBy { it.first }
                .map { it.second }
        }

    private fun probe(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                val length = response.header("Content-Length").orEmpty()
                val headers = buildList {
                    response.header("Server")?.let { add("server=$it") }
                    response.header("Location")?.let { add("location=$it") }
                    response.header("WWW-Authenticate")?.let { add("auth=$it") }
                }.joinToString(", ")

                val snippet = if (
                    type.startsWith("image/", ignoreCase = true) ||
                    type.startsWith("video/", ignoreCase = true)
                ) {
                    ""
                } else {
                    runCatching { response.peekBody(1500).string() }.getOrDefault("")
                }.sanitize()

                buildString {
                    append("GET ").append(url)
                    append(" -> HTTP ").append(response.code)
                    if (type.isNotBlank()) append(", ").append(type)
                    if (length.isNotBlank()) append(", ").append(length).append(" bytes")
                    if (headers.isNotBlank()) append(" [").append(headers).append(']')
                    append('\n')
                    if (snippet.isNotBlank()) {
                        append("   ").append(snippet.take(400)).append('\n')
                    }
                    if (response.request.url.toString() != url) {
                        append("   final: ").append(response.request.url).append('\n')
                    }
                }
            }
        } catch (exc: Exception) {
            "GET $url -> ${exc.javaClass.simpleName}: " +
                exc.message.orEmpty().sanitize().take(120) + "\n"
        }
    }

    private fun describe(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("mobile")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val internet = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            "has internet"
        } else {
            "no internet"
        }
        return "${transports.joinToString("+").ifBlank { "unknown" }} ($internet)"
    }

    private fun String.sanitize(): String = replace(CONTROL_CHARS, " ")
        .replace(WHITESPACE, " ")
        .trim()

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val USER_AGENT = "CamLink/1.5 UPnP/1.1"
        private const val CONNECT_TIMEOUT_MS = 500

        private val SCAN_PORTS = listOf(80, 7676, 49152, 49153, 8080)
        private val HTTP_PATHS = listOf(
            "/",
            "/description.xml",
            "/rootDesc.xml",
            "/smp_2_",
            "/smp_3_",
            "/smp_4_",
            "/smp_6_",
            "/smp_5_",
            "/smp_7_",
            "/smp_8_"
        )
        private val SEARCH_TARGETS = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "ssdp:all"
        )
        private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        private val WHITESPACE = Regex("\\s+")

        private val MSEARCH =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: %s\r\n" +
                "USER-AGENT: $USER_AGENT\r\n" +
                "\r\n"
    }
}
