package com.tonda.tether

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Minimal embedded HTTP proxy, plain java.net sockets, zero dependencies.
 *
 * What it does:
 *  - Plain HTTP: reads the request line + headers, forwards the request to
 *    the target host over the phone's mobile-data connection, relays the
 *    response back to the PC.
 *  - HTTPS (and anything else): handles CONNECT by answering "200
 *    Connection established", then blindly pipes bytes both ways until
 *    either side closes. The proxy never sees the encrypted content.
 *
 * What it does NOT do (v1 limits, on purpose):
 *  - No caching, no filtering, no authentication. Anyone on the Wi-Fi
 *    Direct group can use it — that is the same trust model as the
 *    hotspot itself.
 *  - No HTTP/2 or WebSocket special-casing; CONNECT tunnelling covers
 *    those well enough for a browser.
 *
 * TODO (real phone): measure throughput on ColorOS — expected to be fine
 * for browsing/YouTube, but confirm no per-connection overhead problem
 * when a page opens dozens of parallel connections.
 */
class ProxyServer(
    private val port: Int,
    private val onBytesTransferred: (Long) -> Unit,
    private val onClientConnected: () -> Unit,
    private val onClientDisconnected: () -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    val bytesTransferred = AtomicLong(0)

    /** True once [start] has bound the socket. False after [stop]. */
    val isAlive: Boolean
        get() = running.get() && serverSocket?.isBound == true && serverSocket?.isClosed == false

    fun start() {
        if (running.get()) return
        serverSocket = ServerSocket(port).apply { reuseAddress = true }
        running.set(true)
        thread(name = "proxy-accept", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    onClientConnected()
                    pool.execute { handleClient(client) }
                } catch (_: Exception) {
                    // Socket closed by stop() — loop ends via running flag.
                    break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]

            // Read headers until the blank line.
            val headers = mutableListOf<String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                headers += line
            }

            if (method == "CONNECT") {
                handleConnect(client, output, target)
            } else {
                handleHttp(client, input, output, method, target, headers)
            }
        } catch (_: Exception) {
            // Client went away mid-request; nothing to do.
        } finally {
            try { client.close() } catch (_: Exception) { }
            onClientDisconnected()
        }
    }

    private fun handleHttp(
        client: Socket,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        method: String,
        target: String,
        headers: List<String>,
    ) {
        // target is a full URL (http://host:port/path) for proxy requests.
        val url = try { java.net.URL(target) } catch (_: Exception) { return }
        val host = url.host
        val port = if (url.port != -1) url.port else 80
        val path = buildString {
            append(url.path.ifEmpty { "/" })
            if (url.query != null) append("?").append(url.query)
        }

        val upstream = Socket()
        upstream.connect(InetSocketAddress(host, port), 15_000)
        upstream.soTimeout = 60_000
        try {
            val upOut = BufferedOutputStream(upstream.getOutputStream())
            val upIn = BufferedInputStream(upstream.getInputStream())

            // Forward the request, rewriting the request line to origin form.
            upOut.write("$method $path HTTP/1.1\r\n".toByteArray())
            var hasConnectionClose = false
            for (h in headers) {
                val lower = h.lowercase()
                // Drop hop-by-hop headers that must not be forwarded.
                if (lower.startsWith("proxy-connection:") ||
                    lower.startsWith("proxy-authorization:")
                ) continue
                if (lower.startsWith("connection:")) {
                    hasConnectionClose = true
                    upOut.write("Connection: close\r\n".toByteArray())
                    continue
                }
                upOut.write("$h\r\n".toByteArray())
            }
            if (!hasConnectionClose) upOut.write("Connection: close\r\n".toByteArray())
            upOut.write("\r\n".toByteArray())

            // Forward a request body if Content-Length says there is one.
            val contentLength = headers.firstOrNull { it.lowercase().startsWith("content-length:") }
                ?.substringAfter(":")?.trim()?.toLongOrNull() ?: 0L
            if (contentLength > 0) {
                copyExact(clientInput, upOut, contentLength)
            }
            upOut.flush()

            // Relay the whole response back (headers + body). Content-Length
            // is unknown up front, so read until the server closes.
            copyAll(upIn, clientOutput)
            clientOutput.flush()
        } finally {
            try { upstream.close() } catch (_: Exception) { }
        }
    }

    private fun handleConnect(client: Socket, clientOutput: BufferedOutputStream, target: String) {
        val (host, port) = target.split(":").let {
            it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 443)
        }
        val upstream = Socket()
        upstream.connect(InetSocketAddress(host, port), 15_000)
        try {
            clientOutput.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            clientOutput.flush()
            val clientIn = BufferedInputStream(client.getInputStream())
            val upIn = BufferedInputStream(upstream.getInputStream())
            val upOut = BufferedOutputStream(upstream.getOutputStream())
            val clientOut = BufferedOutputStream(client.getOutputStream())

            // Pipe both directions until either side closes.
            val t1 = thread(name = "proxy-up", isDaemon = true) {
                try { copyAll(clientIn, upOut); upOut.flush() } catch (_: Exception) { }
                try { upstream.shutdownOutput() } catch (_: Exception) { }
            }
            val t2 = thread(name = "proxy-down", isDaemon = true) {
                try { copyAll(upIn, clientOut); clientOut.flush() } catch (_: Exception) { }
                try { client.shutdownOutput() } catch (_: Exception) { }
            }
            t1.join()
            t2.join()
        } finally {
            try { upstream.close() } catch (_: Exception) { }
        }
    }

    // ---- small byte helpers ------------------------------------------------

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) break
            if (prev != '\r'.code) buf.write(b)
            prev = b
        }
        return buf.toString(Charsets.UTF_8)
    }

    private fun copyAll(input: BufferedInputStream, output: BufferedOutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
            val total = bytesTransferred.addAndGet(n.toLong())
            onBytesTransferred(total)
        }
    }

    private fun copyExact(input: BufferedInputStream, output: BufferedOutputStream, bytes: Long) {
        val buf = ByteArray(8192)
        var remaining = bytes
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            output.write(buf, 0, n)
            remaining -= n
        }
    }
}
