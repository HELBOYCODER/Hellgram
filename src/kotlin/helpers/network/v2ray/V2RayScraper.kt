package desu.inugram.helpers.network.v2ray

import android.content.Context
import android.os.SystemClock
import desu.inugram.InuConfig
import desu.inugram.helpers.diagnostics.ErrorLog
import desu.inugram.helpers.network.BuiltInTunnelHelper
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// entiny: scrapes public V2Ray lists, TCP-pings a sample, then real-delay tests the fastest and keeps the best 5
object V2RayScraper {
    const val STAGE_IDLE = 0
    const val STAGE_LISTS = 1
    const val STAGE_PING = 2
    const val STAGE_TEST = 3

    const val ERROR_NONE = 0
    const val ERROR_ENGINE = 1
    const val ERROR_LISTS = 2
    const val ERROR_UNREACHABLE = 3

    const val TOP = 5
    private const val SAMPLE = 300
    private const val REAL_TEST = 70
    private const val PER_ENDPOINT = 2
    private const val GOOD_ENOUGH = 10
    private const val MAX_BODY = 6 * 1024 * 1024

    private class Source(val raw: String, val cdn: String)

    private val SOURCES = listOf(
        Source(
            "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt",
            "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Config@main/All_Configs_Sub.txt",
        ),
        Source(
            "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
            "https://cdn.jsdelivr.net/gh/Epodonios/v2ray-configs@main/All_Configs_Sub.txt",
        ),
        Source(
            "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
            "https://cdn.jsdelivr.net/gh/mahdibland/V2RayAggregator@master/sub/sub_merge.txt",
        ),
        Source(
            "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt",
            "https://cdn.jsdelivr.net/gh/MatinGhanbari/v2ray-configs@main/subscriptions/v2ray/super-sub.txt",
        ),
    )

    private val lock = Any()
    @Volatile private var running = false

    @Volatile var stage = STAGE_IDLE
        private set
    @Volatile var error = ERROR_NONE
        private set
    @Volatile var report = ""
        private set

    @Volatile var connectAfterScan = false
        private set

    fun isScanning(): Boolean = running

    fun cancelConnect() {
        synchronized(lock) { connectAfterScan = false }
    }

    fun servers(): List<V2RayServer> {
        val raw = InuConfig.BUILT_IN_TUNNEL_SERVERS.value
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                V2RayLink.parse(o.getString("link"))?.also { it.pingMs = o.optInt("ping", -1) }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun scanAsync(context: Context, connectBest: Boolean = false) {
        synchronized(lock) {
            if (running) {
                if (connectBest) connectAfterScan = true
                return
            }
            running = true
            connectAfterScan = connectBest
        }
        error = ERROR_NONE
        val app = context.applicationContext
        Thread {
            try {
                scan(app)
            } catch (t: Throwable) {
                error = ERROR_LISTS
                ErrorLog.record("v2ray-scan", t)
            } finally {
                stage = STAGE_IDLE
                val connect = synchronized(lock) {
                    val wanted = connectAfterScan
                    connectAfterScan = false
                    running = false
                    wanted
                }
                if (connect) {
                    val best = if (error == ERROR_NONE) servers().firstOrNull() else null
                    if (best != null) {
                        BuiltInTunnelHelper.selectServer(best.link)
                    } else {
                        BuiltInTunnelHelper.reportStatus("no working server found")
                    }
                }
                notifyChanged()
            }
        }.apply {
            name = "inu-v2ray-scan"
            isDaemon = true
            start()
        }
    }

    private fun notifyChanged() {
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        }
    }

    private fun setStage(next: Int) {
        stage = next
        notifyChanged()
    }

    private fun scan(context: Context) {
        report = ""
        if (!XrayEngine.available(context)) {
            error = ERROR_ENGINE
            return
        }
        setStage(STAGE_LISTS)
        val fetched = fetchAll()
        val links = fetched.first
        if (links.isEmpty()) {
            error = ERROR_LISTS
            report = "lists ${fetched.second}/${SOURCES.size}"
            return
        }
        val seen = HashSet<String>()
        val parsed = ArrayList<V2RayServer>()
        for (line in links) {
            val server = V2RayLink.parse(line) ?: continue
            if (seen.add(server.key())) parsed.add(server)
        }

        // entiny: public lists are full of hundreds of configs behind the same CDN IP; ping each endpoint once
        // and keep only a few configs per endpoint so the test round is not flooded with clones of one dead front
        val byEndpoint = parsed.groupBy { "${it.host}:${it.port}" }
        val endpoints = byEndpoint.keys.shuffled().take(SAMPLE)

        setStage(STAGE_PING)
        val pingPool = Executors.newFixedThreadPool(40)
        val pings = endpoints.map { ep ->
            val server = byEndpoint.getValue(ep).first()
            pingPool.submit<Int> { tcpPing(server.host, server.port) }
        }
        val pingDeadline = SystemClock.elapsedRealtime() + 25_000
        val endpointPing = HashMap<String, Int>()
        endpoints.forEachIndexed { i, ep ->
            val left = (pingDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
            val ms = try { pings[i].get(left, TimeUnit.MILLISECONDS) } catch (_: Throwable) { -1 }
            if (ms >= 0) endpointPing[ep] = ms
        }
        pingPool.shutdownNow()
        val reachable = endpointPing.entries.sortedBy { it.value }.flatMap { (ep, ms) ->
            byEndpoint.getValue(ep).shuffled().take(PER_ENDPOINT).onEach { it.pingMs = ms }
        }.take(REAL_TEST)
        report = "lists ${fetched.second}/${SOURCES.size}, links ${links.size}, parsed ${parsed.size}, " +
            "endpoints ${endpoints.size}, reachable ${endpointPing.size}, testing ${reachable.size}"
        if (reachable.isEmpty()) {
            error = ERROR_UNREACHABLE
            return
        }

        setStage(STAGE_TEST)
        val testPool = Executors.newFixedThreadPool(6)
        val completion = ExecutorCompletionService<Pair<V2RayServer, Int>>(testPool)
        for (server in reachable) completion.submit { server to XrayEngine.realDelay(context, server) }
        val tested = ArrayList<V2RayServer>()
        val testDeadline = SystemClock.elapsedRealtime() + 110_000
        var done = 0
        while (done < reachable.size && tested.size < GOOD_ENOUGH) {
            val left = testDeadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            val result = (try { completion.poll(left, TimeUnit.MILLISECONDS)?.get() } catch (_: Throwable) { null }) ?: break
            done++
            if (result.second > 0) {
                result.first.pingMs = result.second
                tested.add(result.first)
            }
        }
        testPool.shutdownNow()
        report += ", passed ${tested.size}/$done"
        val best = tested.sortedBy { it.pingMs }.take(TOP)
        if (best.isEmpty()) {
            error = ERROR_UNREACHABLE
            return
        }
        val out = JSONArray()
        for (server in best) out.put(JSONObject().put("link", server.link).put("ping", server.pingMs))
        InuConfig.BUILT_IN_TUNNEL_SERVERS.value = out.toString()
    }

    private fun tcpPing(host: String, port: Int): Int = try {
        var best = Int.MAX_VALUE
        repeat(2) {
            val t0 = SystemClock.elapsedRealtime()
            Socket().use { it.connect(InetSocketAddress(host, port), 2500) }
            best = minOf(best, (SystemClock.elapsedRealtime() - t0).toInt())
        }
        best.coerceAtLeast(1)
    } catch (_: Throwable) {
        -1
    }

    private fun fetchAll(): Pair<List<String>, Int> {
        val pool = Executors.newFixedThreadPool(SOURCES.size)
        val jobs = SOURCES.map { source -> pool.submit<List<String>> { fetchSource(source) } }
        val out = ArrayList<String>()
        var okSources = 0
        for (job in jobs) {
            try {
                val links = job.get(60, TimeUnit.SECONDS)
                if (links.isNotEmpty()) okSources++
                out.addAll(links)
            } catch (_: Throwable) {
            }
        }
        pool.shutdownNow()
        return out to okSources
    }

    private fun fetchSource(source: Source): List<String> {
        val proxy = if (BuiltInTunnelHelper.isActive()) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", BuiltInTunnelHelper.SOCKS_PORT))
        } else {
            null
        }
        for (url in listOf(source.raw, source.cdn)) {
            val body = download(url, proxy) ?: continue
            val links = extractLinks(body)
            if (links.isNotEmpty()) return links
        }
        return emptyList()
    }

    private fun download(url: String, proxy: Proxy?): String? = try {
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Helboy/1.0")
        try {
            if (conn.responseCode != 200) {
                null
            } else {
                val sb = StringBuilder()
                conn.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(16 * 1024)
                    while (sb.length < MAX_BODY) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        null
    }

    private fun extractLinks(body: String): List<String> {
        var text = body
        if (!text.contains("://")) {
            text = try {
                String(V2RayLink.decodeBase64(text.filter { !it.isWhitespace() }), Charsets.UTF_8)
            } catch (_: Throwable) {
                return emptyList()
            }
        }
        return text.lineSequence().map { it.trim() }.filter { V2RayLink.looksLikeLink(it) }.toList()
    }
}
