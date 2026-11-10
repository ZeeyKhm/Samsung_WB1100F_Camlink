package com.camlab.camlink.net

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Saves camera files into the phone gallery through MediaStore.
 * Never touches the camera: downloads are read-only HTTP GETs.
 *
 * Embedded camera servers usually rate-limit each HTTP connection well below
 * the Wi-Fi link speed, so several files are fetched at once. The old build
 * stopped at two connections because too many at once can upset the WB1100F's
 * small server. This version starts with two and adds another stream only while
 * the measured aggregate throughput is still rising, up to six. On a camera
 * that cannot take more than two, it stays at two; on one that scales, it uses
 * the whole link. A failure stops the ramp so the transfer settles instead of
 * piling load onto a struggling server.
 */
class Downloader(private val context: Context) {

    data class Summary(
        val saved: Int,
        val skipped: Int,
        val failed: Int,
        val firstError: String?
    )

    private data class QueuedFile(
        val item: MediaItem,
        val name: String
    )

    private sealed interface SaveResult {
        data object Saved : SaveResult
        data class Failed(val message: String) : SaveResult
    }

    @Volatile
    private var cancelled = false

    private val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // A read timeout (rather than none) turns a wedged camera connection
        // into a retry instead of a download that never finishes.
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Enough idle sockets for the parallel streams, and a dispatcher that
        // does not cap the per-camera host at OkHttp's default of five.
        .connectionPool(ConnectionPool(MAX_CONCURRENCY, 2, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests = MAX_CONCURRENCY * 2
            maxRequestsPerHost = MAX_CONCURRENCY * 2
        })
        .build()

    /**
     * Downloads must go out over the camera's Wi-Fi. Without this, Android can
     * send them over mobile data while the MobileLink access point has no
     * internet, so the request never reaches the camera.
     */
    private fun http(): OkHttpClient {
        val network = findWifiLink(context)?.network ?: return baseHttp
        return baseHttp.newBuilder()
            .socketFactory(WifiBoundSocketFactory(network))
            .build()
    }

    fun cancel() {
        cancelled = true
    }

    suspend fun download(
        items: List<MediaItem>,
        onProgress: (done: Int, total: Int, item: MediaItem, bytesPerSecond: Long) -> Unit
    ): Summary = withContext(Dispatchers.IO) {
        cancelled = false
        val http = http()
        val resolver = context.contentResolver
        val known = existingNames()
        val total = items.size
        var saved = 0
        var skipped = 0
        var failed = 0
        var firstError: String? = null

        // Name everything once up front: this is also what skips files that are
        // already on the phone and duplicate names inside the selection.
        val queue = ArrayList<QueuedFile>(total)
        items.forEachIndexed { index, item ->
            val name = Naming.fileName(item, index)
            if (!known.add(name)) {
                skipped++
            } else {
                queue += QueuedFile(item, name)
            }
        }

        if (queue.isNotEmpty()) {
            val completed = AtomicInteger(0)
            val alreadySkipped = skipped
            val bytes = AtomicLong(0)
            val startedAt = System.nanoTime()
            val sawFailure = AtomicBoolean(false)
            val lock = Any()
            val wifiLock = acquireWifiLock()
            try {
                coroutineScope {
                    val channel = Channel<QueuedFile>(Channel.UNLIMITED)
                    queue.forEach { channel.trySend(it) }
                    channel.close()

                    fun worker(): Job = launch(Dispatchers.IO) {
                        for (file in channel) {
                            if (cancelled) break
                            val result = save(http, resolver, file, bytes)
                            synchronized(lock) {
                                when (result) {
                                    is SaveResult.Failed -> {
                                        failed++
                                        sawFailure.set(true)
                                        if (firstError == null) firstError = result.message
                                    }
                                    SaveResult.Saved -> saved++
                                }
                                val done = alreadySkipped + completed.incrementAndGet()
                                val elapsed = (System.nanoTime() - startedAt).coerceAtLeast(1)
                                val rate = bytes.get() * NANOS_PER_SECOND / elapsed
                                onProgress(done, total, file.item, rate)
                            }
                        }
                    }

                    val workers = ArrayList<Job>()
                    val useful = minOf(MAX_CONCURRENCY, queue.size)
                    repeat(minOf(INITIAL_CONCURRENCY, useful)) { workers += worker() }

                    // Keep adding a stream while the extra parallel connection
                    // actually buys throughput. Once the gain is under 5% the
                    // camera has saturated and the extra streams would only
                    // make its server work harder for nothing.
                    var lastRate = 0L
                    var lastSampleBytes = 0L
                    var lastSampleAt = System.nanoTime()
                    while (workers.any { it.isActive } &&
                        workers.size < useful &&
                        !sawFailure.get()
                    ) {
                        delay(RAMP_INTERVAL_MS)
                        if (!workers.any { it.isActive }) break
                        val nowBytes = bytes.get()
                        val now = System.nanoTime()
                        val rate = (nowBytes - lastSampleBytes) * NANOS_PER_SECOND /
                            (now - lastSampleAt).coerceAtLeast(1)
                        val improved = rate > lastRate + lastRate / 20
                        lastRate = rate
                        lastSampleBytes = nowBytes
                        lastSampleAt = now
                        if (!improved) break
                        workers += worker()
                    }
                    workers.forEach { it.join() }
                }
            } finally {
                releaseWifiLock(wifiLock)
            }
        }

        items.lastOrNull()?.let { last ->
            if (!cancelled) onProgress(total, total, last, 0L)
        }
        Summary(saved, skipped, failed, firstError)
    }

    /** Insert a pending row, stream the file into it, then publish it. */
    private fun save(
        http: OkHttpClient,
        resolver: ContentResolver,
        file: QueuedFile,
        bytes: AtomicLong
    ): SaveResult {
        val item = file.item
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (item.isVideo) {
            "${Environment.DIRECTORY_MOVIES}/CamLink"
        } else {
            "${Environment.DIRECTORY_PICTURES}/CamLink"
        }
        val mime = item.mime.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
        if (uri == null) return SaveResult.Failed("Could not create ${file.name}")

        var lastError: Exception? = null
        for (attempt in 0 until ATTEMPTS) {
            if (cancelled) {
                lastError = IOException("Cancelled")
                break
            }
            try {
                copy(http, resolver, item, uri, bytes)
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                return SaveResult.Saved
            } catch (exc: Exception) {
                lastError = exc
                if (cancelled) break
                runCatching { Thread.sleep(RETRY_DELAY_MS * (attempt + 1)) }
            }
        }

        runCatching { resolver.delete(uri, null, null) }
        return SaveResult.Failed(
            lastError?.message ?: lastError?.javaClass?.simpleName ?: "Download failed"
        )
    }

    private fun copy(
        http: OkHttpClient,
        resolver: ContentResolver,
        item: MediaItem,
        uri: Uri,
        bytes: AtomicLong
    ) {
        val request = Request.Builder()
            .url(item.url)
            .header("User-Agent", RawHttp.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .build()

        var response = http.newCall(request).execute()
        // In MobileLink mode the camera can refuse a request until it has let
        // this phone in. Retry once with the Samsung identity.
        if (response.code == 401 || response.code == 403) {
            response.close()
            val samsung = SamsungLink.dlnaHeaders(SamsungLink.phoneMac())
            response = http.newCall(
                request.newBuilder().apply {
                    samsung.forEach { (name, value) -> header(name, value) }
                }.build()
            ).execute()
        }

        response.use { reply ->
            if (!reply.isSuccessful) throw IOException("HTTP ${reply.code}")
            val body = reply.body ?: throw IOException("empty response")
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("cannot open destination")
            BufferedOutputStream(stream, BUFFER_SIZE).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled) throw IOException("Cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        bytes.addAndGet(read.toLong())
                    }
                }
            }
        }
    }

    /** Keep Wi-Fi out of power save for the length of the transfer. */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock(): WifiManager.WifiLock? = runCatching {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CamLink:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun releaseWifiLock(lock: WifiManager.WifiLock?) {
        runCatching { lock?.release() }
    }

    private fun existingNames(): MutableSet<String> {
        val names = mutableSetOf<String>()
        val targets = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to
                "${Environment.DIRECTORY_PICTURES}/CamLink",
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to
                "${Environment.DIRECTORY_MOVIES}/CamLink"
        )
        for ((uri, path) in targets) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$path%"),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { names += it }
                    }
                }
            }
        }
        return names
    }

    private companion object {
        /** Streams to start with, and the most the camera should ever see. */
        const val INITIAL_CONCURRENCY = 2
        const val MAX_CONCURRENCY = 6
        const val RAMP_INTERVAL_MS = 700L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BUFFER_SIZE = 256 * 1024
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 400L
        const val CONNECT_TIMEOUT_SECONDS = 6L
        const val READ_TIMEOUT_SECONDS = 45L
    }
}
