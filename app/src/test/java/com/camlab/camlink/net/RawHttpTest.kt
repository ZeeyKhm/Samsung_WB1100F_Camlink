package com.camlab.camlink.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RawHttpTest {

    @Test
    fun `sends HTTP 1_0 with connection close and reads content length body`() {
        val server = ServerSocket(0)
        val requestLine = arrayOfNulls<String>(1)
        val connectionHeader = arrayOfNulls<String>(1)

        val worker = thread {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                requestLine[0] = reader.readLine()
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    if (line.startsWith("Connection:", ignoreCase = true)) {
                        connectionHeader[0] = line
                    }
                    line = reader.readLine()
                }
                socket.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/xml\r\n" +
                        "Content-Length: 5\r\n" +
                        "\r\n" +
                        "hello").toByteArray(Charsets.US_ASCII)
                )
                socket.getOutputStream().flush()
            }
        }

        try {
            val response = RawHttp.get(
                network = null,
                url = "http://127.0.0.1:${server.localPort}/description.xml",
                connectTimeoutMs = 2000,
                headerTimeoutMs = 2000,
                idleTimeoutMs = 500
            )
            assertEquals(200, response.status)
            assertEquals("hello", response.text)
            assertEquals("GET /description.xml HTTP/1.0", requestLine[0])
            assertTrue(connectionHeader[0]?.contains("close", ignoreCase = true) == true)
        } finally {
            server.close()
            worker.join(2000)
        }
    }

    @Test
    fun `accepts a response without content length and stops when the server idles`() {
        val server = ServerSocket(0)
        val worker = thread {
            runCatching {
                server.accept().use { socket: Socket ->
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)
                    )
                    var line = reader.readLine()
                    while (line != null && line.isNotEmpty()) line = reader.readLine()
                    // Old camera style: body, no Content-Length, connection left open.
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\n\r\n<device/>"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    socket.getOutputStream().flush()
                    Thread.sleep(1500)
                }
            }
        }

        try {
            val response = RawHttp.get(
                network = null,
                url = "http://127.0.0.1:${server.localPort}/description.xml",
                connectTimeoutMs = 2000,
                headerTimeoutMs = 2000,
                idleTimeoutMs = 400
            )
            assertEquals(200, response.status)
            assertEquals("<device/>", response.text)
        } finally {
            server.close()
            worker.join(2000)
        }
    }
}
