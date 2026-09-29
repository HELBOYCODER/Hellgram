package desu.inugram.helpers.network

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// helboy: local HTTP proxy sitting in front of the tunnel's SOCKS port.
//
// Why this exists: Android's HttpURLConnection - used by media3/ExoPlayer for every HLS playlist and
// segment, and by WebView/Chromium - only understands HTTP proxies. java.net.Proxy.Type.SOCKS is
// not honoured there, so pointing media3 straight at the tunnel's SOCKS port silently does nothing.
// The engine can also expose an HTTP port, but when that listener is not up every stream request
// falls back to direct and dies on a filtered network - which is exactly the "channel plays for two
// seconds then never loads again" symptom. This bridge terminates HTTP locally and forwards through
// SOCKS, so playback works whenever the tunnel itself works.
//
// Ported from the Suni TV player, where it is the piece that makes SOCKS-only engines usable for
// video; logic kept deliberately close to that proven implementation.
object TunnelHttpBridge {
    private const val TAG = "InuHttp2Socks"
    private const val MAX_CONNECTIONS = 64
    private const val COPY_BUFFER = 64 * 1024
    private const val CLIENT_TIMEOUT_MS = 30_000

    @Volatile private var server: ServerSocket? = null
    @Volatile private var boundSocksPort = -1

    @Volatile var port = -1
        private set

    private val live = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "inu-http2socks").apply { isDaemon = true }
    }

    @Synchronized
    fun ensureStarted(socksPort: Int): Int {
        server?.let { if (!it.isClosed && boundSocksPort == socksPort) return port }
        stop()
        if (!isTcpOpen(socksPort)) return -1
        return try {
            val s = ServerSocket()
            s.bind(InetSocketAddress("127.0.0.1", 0))
            server = s
            boundSocksPort = socksPort
            port = s.localPort
            pool.execute {
                while (!s.isClosed) {
                    try {
                        val client = s.accept()
                        if (live.get() >= MAX_CONNECTIONS) {
                            runCatching { client.close() }
                            continue
                        }
                        live.incrementAndGet()
                        pool.execute {
                            try {
                                handle(client, socksPort)
                            } finally {
                                live.decrementAndGet()
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            Log.i(TAG, "bridge on :$port -> socks 127.0.0.1:$socksPort")
            port
        } catch (e: Exception) {
            Log.e(TAG, "bridge start failed", e)
            -1
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        port = -1
        boundSocksPort = -1
    }

    fun isTcpOpen(p: Int, timeoutMs: Int = 350): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), timeoutMs) }
            true
        } catch (_: Exception) {
            false
        }

    private fun socksSocket(socksPort: Int): Socket =
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))

    private fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c == -1) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString("ISO-8859-1")
    }

    // Two threads, one per direction: video is long-lived and must not be buffered to completion.
    private fun pump(client: Socket, upstream: Socket) {
        val up = Thread {
            runCatching { upstream.getInputStream().copyTo(client.getOutputStream(), COPY_BUFFER) }
            runCatching { client.close() }
        }
        val down = Thread {
            runCatching { client.getInputStream().copyTo(upstream.getOutputStream(), COPY_BUFFER) }
            runCatching { upstream.close() }
        }
        up.isDaemon = true
        down.isDaemon = true
        up.start()
        down.start()
    }

    private fun handle(client: Socket, socksPort: Int) {
        try {
            client.soTimeout = CLIENT_TIMEOUT_MS
            val reqLine = readLine(client.getInputStream()) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return

            if (parts[0].equals("CONNECT", true)) {
                // TLS tunnel (https://...): relay raw bytes both ways.
                val hp = parts[1]
                val host = hp.substringBefore(":")
                val p = hp.substringAfter(":", "443").toIntOrNull() ?: 443
                val upstream = socksSocket(socksPort)
                upstream.connect(InetSocketAddress(host, p), 15_000)
                client.soTimeout = 0
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                pump(client, upstream)
                return
            }

            // Plain HTTP: read headers, re-issue through SOCKS.
            var line = readLine(client.getInputStream())
            var hostHeader = ""
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Host:", true)) hostHeader = line.substringAfter(":").trim()
                line = readLine(client.getInputStream())
            }
            val raw = parts[1]
            val target = if (raw.startsWith("http://")) raw.substring(7) else "$hostHeader$raw"
            val host = target.substringBefore(":").substringBefore("/")
            if (host.isBlank()) return
            val p = target.substringAfter(":", "").takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 80
            val rest = target.removePrefix(host).removePrefix(":$p")
            val path = if (rest.startsWith("/")) rest else "/$rest"
            val upstream = socksSocket(socksPort)
            upstream.connect(InetSocketAddress(host, p), 15_000)
            client.soTimeout = 0
            upstream.getOutputStream().write(
                "${parts[0]} $path HTTP/1.1\r\nHost: $host\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n".toByteArray()
            )
            upstream.getOutputStream().flush()
            upstream.getInputStream().copyTo(client.getOutputStream(), COPY_BUFFER)
            upstream.close()
        } catch (e: Exception) {
            Log.d(TAG, "bridge request failed: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }
}
