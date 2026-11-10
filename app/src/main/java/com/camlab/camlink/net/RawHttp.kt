package com.camlab.camlink.net

import android.net.Network
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale

/**
 * Minimal HTTP GET used for old camera servers.
 *
 * OkHttp expects a well-behaved HTTP/1.1 server. The camera's Myriad-CS stack
 * sometimes answers `description.xml` without Content-Length and keeps the
 * connection open, or dislikes keep-alive; OkHttp then waits for a read timeout
 * and reports failure. This client sends HTTP/1.0 with `Connection: close` and
 * accepts the bytes it got when the server goes quiet.
 */
data class RawHttpResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val text: String get() = String(body, Charsets.UTF_8)

    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
}

object RawHttp {

    const val USER_AGENT = "CamLink/1.5 UPnP/1.1"

    /**
     * @param headers extra request headers. Any name already present replaces
     *   the default, so a caller can send a Samsung-style `User-Agent` or add
     *   `Access-Method`.
     */
    fun get(
        network: Network?,
        url: String,
        version: String = "HTTP/1.0",
        connectTimeoutMs: Int = 5000,
        headerTimeoutMs: Int = 12000,
        idleTimeoutMs: Int = 4000,
        maxBytes: Int = 512 * 1024,
        headers: Map<String, String> = emptyMap()
    ): RawHttpResponse {
        val uri = URI(url)
        val host = uri.host ?: throw IOException("No host in $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.ifBlank { "/" } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        val request = buildString {
            append("GET ").append(path).append(' ').append(version).append("\r\n")
            val headerLines = LinkedHashMap<String, String>()
            headerLines["Host"] = if (port != 80) "$host:$port" else host
            headerLines["User-Agent"] = USER_AGENT
            headerLines["Accept"] = "*/*"
            headerLines["Connection"] = "close"
            headers.forEach { (name, value) ->
                val existing = headerLines.keys.firstOrNull {
                    it.equals(name, ignoreCase = true)
                }
                if (existing != null) headerLines.remove(existing)
                headerLines[name] = value
            }
            headerLines.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }

        val socket = Socket()
        try {
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = headerTimeoutMs
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }

            val input = BufferedInputStream(socket.getInputStream())
            val headerBytes = readHeader(input, 64 * 1024)
                ?: throw IOException("Empty response")
            val headerText = String(headerBytes, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val statusLine = lines.firstOrNull().orEmpty()
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }

            socket.soTimeout = idleTimeoutMs
            val body = readBody(input, headers, maxBytes)
            return RawHttpResponse(status, statusLine, headers, body)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHeader(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val tail = ByteArray(4)
        while (out.size() < limit) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            tail[0] = tail[1]
            tail[1] = tail[2]
            tail[2] = tail[3]
            tail[3] = b.toByte()
            if (out.size() >= 4 &&
                tail[0] == '\r'.code.toByte() && tail[1] == '\n'.code.toByte() &&
                tail[2] == '\r'.code.toByte() && tail[3] == '\n'.code.toByte()
            ) {
                return out.toByteArray()
            }
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    private fun readBody(
        input: InputStream,
        headers: Map<String, String>,
        maxBytes: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val transfer = headers["transfer-encoding"].orEmpty().lowercase(Locale.US)
        val length = headers["content-length"]?.toLongOrNull()

        if (transfer.contains("chunked")) {
            readChunked(input, out, maxBytes)
            return out.toByteArray()
        }
        if (length != null && length >= 0) {
            readLimited(input, out, minOf(length, maxBytes.toLong()).toInt())
            return out.toByteArray()
        }

        // No Content-Length: read until close, or stop after the server idles.
        val buffer = ByteArray(8192)
        while (out.size() < maxBytes) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun readLimited(input: InputStream, out: ByteArrayOutputStream, count: Int) {
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val read = try {
                input.read(buffer, 0, minOf(buffer.size, remaining))
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readChunked(input: InputStream, out: ByteArrayOutputStream, maxBytes: Int) {
        while (out.size() < maxBytes) {
            val line = readAsciiLine(input) ?: return
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: return
            if (size == 0) return
            readLimited(input, out, minOf(size, maxBytes - out.size()))
            readAsciiLine(input)
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < 1024) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) {
                return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
            }
            out.write(b)
        }
        return if (out.size() == 0) null
        else String(out.toByteArray(), Charsets.ISO_8859_1)
    }
}
