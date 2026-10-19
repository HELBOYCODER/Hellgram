package desu.inugram.helpers.network.v2ray

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

// entiny: Xray-core runs as a child process (libxray.so in the native lib dir); Telegram talks to its local SOCKS5/HTTP inbounds
object XrayEngine {
    private const val TAG = "InuXray"
    private const val MAX_LOG_LINES = 300

    private val logLines = ArrayDeque<String>()
    private val reservedPorts = HashSet<Int>()
    private val instanceCounter = AtomicInteger(0)

    class Handle(val process: Process, val configFile: File, private val socksPort: Int) {
        fun alive(): Boolean = try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

        fun stop() {
            try { process.destroy() } catch (_: Throwable) {}
            val deadline = SystemClock.elapsedRealtime() + 1500
            while (alive() && SystemClock.elapsedRealtime() < deadline) {
                try { Thread.sleep(40) } catch (_: InterruptedException) { break }
            }
            try { configFile.delete() } catch (_: Throwable) {}
            Log.i(TAG, "xray on $socksPort stopped")
        }
    }

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libxray.so")

    fun available(context: Context): Boolean = binary(context).exists()

    fun logs(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private fun addLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
    }

    fun buildConfig(server: V2RayServer, socksPort: Int, httpPort: Int?, quiet: Boolean): String {
        val inbounds = JSONArray().put(
            JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", socksPort)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1")),
        )
        if (httpPort != null) {
            inbounds.put(
                JSONObject()
                    .put("tag", "http-in")
                    .put("listen", "127.0.0.1")
                    .put("port", httpPort)
                    .put("protocol", "http")
                    .put("settings", JSONObject()),
            )
        }
        return JSONObject()
            .put("log", JSONObject().put("access", "none").put("loglevel", if (quiet) "none" else "error"))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .put("inbounds", inbounds)
            .put(
                "outbounds",
                JSONArray().put(server.outboundJson()).put(JSONObject().put("tag", "direct").put("protocol", "freedom")),
            )
            .toString()
    }

    fun start(context: Context, server: V2RayServer, socksPort: Int, httpPort: Int?, quiet: Boolean = false): Handle {
        val bin = binary(context)
        check(bin.exists()) { "xray binary missing" }
        val dir = File(context.filesDir, "tunnel").apply { mkdirs() }
        val cfg = File(dir, "xray-$socksPort-${instanceCounter.incrementAndGet()}.json")
        cfg.writeText(buildConfig(server, socksPort, httpPort, quiet))
        val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
        pb.directory(dir)
        pb.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
        pb.redirectErrorStream(true)
        val process = pb.start()
        Thread {
            try {
                // entiny: scan probes only drain the pipe, so the shared log stays about the real tunnel
                process.inputStream.bufferedReader().forEachLine { if (!quiet) addLog(it) }
            } catch (_: Throwable) {
            }
        }.apply {
            name = "inu-xray-log"
            isDaemon = true
            start()
        }
        return Handle(process, cfg, socksPort)
    }

    // entiny: a previous app process can leave its xray child bound to the tunnel ports
    fun killOrphans() {
        val self = android.os.Process.myPid()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            try {
                if (File(dir, "cmdline").readText().contains("libxray.so")) android.os.Process.killProcess(pid)
            } catch (_: Throwable) {
            }
        }
    }

    // entiny: parallel probes used to get the same "free" port and overwrite each other's config
    fun reservePort(): Int {
        repeat(30) {
            val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            synchronized(reservedPorts) { if (reservedPorts.add(port)) return port }
        }
        error("no free port")
    }

    fun releasePort(port: Int) {
        synchronized(reservedPorts) { reservedPorts.remove(port) }
    }

    fun isPortOpen(port: Int, timeoutMs: Int = 350): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    } catch (_: Throwable) {
        false
    }

    // entiny: any HTTP answer through the SOCKS port proves the whole path (inbound -> server -> internet) works
    fun httpProbe(socksPort: Int, timeoutMs: Int): Boolean = try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = URL("http://1.1.1.1/cdn-cgi/trace").openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        try {
            conn.responseCode in 100..599
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        false
    }

    fun waitForPort(handle: Handle, port: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!handle.alive()) return false
            if (isPortOpen(port)) return true
            try { Thread.sleep(80) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    // entiny: real delay like v2rayNG: spin a throwaway instance on a free port and time one request through it
    fun realDelay(context: Context, server: V2RayServer, timeoutMs: Int = 5000): Int {
        var handle: Handle? = null
        var port = -1
        return try {
            port = reservePort()
            handle = start(context, server, port, null, quiet = true)
            if (!waitForPort(handle, port, 3000)) return -1
            // entiny: first request pays the cold TLS/REALITY handshake, the second shows the steady ping the user will feel
            val first = SystemClock.elapsedRealtime()
            if (!httpProbe(port, timeoutMs)) return -1
            val firstMs = (SystemClock.elapsedRealtime() - first).toInt().coerceAtLeast(1)
            val second = SystemClock.elapsedRealtime()
            if (httpProbe(port, timeoutMs)) (SystemClock.elapsedRealtime() - second).toInt().coerceAtLeast(1) else firstMs
        } catch (_: Throwable) {
            -1
        } finally {
            handle?.stop()
            if (port > 0) releasePort(port)
        }
    }
}
