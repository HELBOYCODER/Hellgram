package desu.inugram.helpers.helboy

import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// helboy: per-channel stream health probe + Persian satellite refresh.
//
// entiny: the list screen asks this object for a status; probes run on a small pool, only touch the
// first 2 KB of the stream (Range request) so we measure time-to-first-byte, not the whole show.
// Results are cached for 5 minutes so reopening the list does not re-hit every CDN.
object HelboyChannelHealth {

    private const val TAG = "InuHelboyHealth"
    private const val CACHE_MS = 5 * 60 * 1000L

    data class Status(val online: Boolean, val pingMs: Int, val checkedAt: Long)

    private val statuses = ConcurrentHashMap<String, Status>()
    @Volatile private var probing: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun statusOf(ch: HelboyChannel): Status? = ch.primaryUrl?.let { statuses[it] }

    /** Kick off probes for a batch of channels (skips already-fresh or in-flight ones). */
    @JvmStatic
    fun probe(channels: List<HelboyChannel>, onBatchDone: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        val todo = channels.filter {
            val u = it.primaryUrl ?: return@filter false
            val s = statuses[u]
            (s == null || now - s.checkedAt > CACHE_MS) && !probing.contains(u)
        }.take(120)
        if (todo.isEmpty()) { onBatchDone?.invoke(); return }
        todo.forEach { probing.add(it.primaryUrl!!) }
        Thread {
            try {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(12)
                val latch = java.util.concurrent.CountDownLatch(todo.size)
                for (ch in todo) {
                    pool.execute {
                        try { statuses[ch.primaryUrl!!] = probeOne(ch.primaryUrl!!) }
                        catch (_: Throwable) {}
                        finally { latch.countDown() }
                    }
                }
                latch.await()
                pool.shutdown()
            } catch (e: Throwable) {
                Log.e(TAG, "probe batch failed", e)
            } finally {
                todo.forEach { probing.remove(it.primaryUrl!!) }
                onBatchDone?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post(cb) }
            }
        }.start()
    }

    private fun probeOne(url: String): Status {
        var conn: HttpURLConnection? = null
        val t0 = System.currentTimeMillis()
        try {
            conn = open(url)
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HelboyTV/1.0")
            conn.setRequestProperty("Range", "bytes=0-2047")
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            val code = conn.responseCode
            if (code !in 200..399) return Status(false, 0, System.currentTimeMillis())
            val body = conn.inputStream.use { it.read(ByteArray(2048)) }
            val ms = (System.currentTimeMillis() - t0).toInt()
            // some dead endpoints answer 200 with an empty or error-page body; require real bytes
            return Status(body > 16, ms, System.currentTimeMillis())
        } catch (e: Throwable) {
            return Status(false, 0, System.currentTimeMillis())
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val c = u.openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            // entiny: IPTV CDNs in this space routinely serve broken/partial chains; playback
            // engines tolerate that, so the probe must too — otherwise a live channel shows "قطع"
            // while the player is happily streaming it.
            try {
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
                c.sslSocketFactory = ctx.socketFactory
                c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            } catch (_: Throwable) {}
        }
        return c
    }

    // ---- Persian refresh (bundled, curated from iptv-org — the best-maintained GitHub IPTV source) ----

    @Volatile private var refreshed = false

    /**
     * entiny: merge the bundled Persian playlist into the runtime DB view. Channels that only exist
     * in the bundled refresh (with logos, 210 entries from iptv-org fas + ir lists) get fresh
     * nanoids so they do not collide with the static DB; entries whose every stream we probed dead
     * get filtered out of the returned lists ("کانال‌های ضعیف یا خاموش را قطع کن").
     */
    @JvmStatic
    fun ensurePersianRefresh() {
        if (refreshed) return
        synchronized(this) {
            if (refreshed) return
            try {
                ApplicationLoader.applicationContext.assets.open("helboy_fas_refresh.bin").use { raw ->
                    BufferedReader(InputStreamReader(GZIPInputStream(raw), Charsets.UTF_8)).use { reader ->
                        val sb = StringBuilder(1 shl 20)
                        val buf = CharArray(32 * 1024)
                        var n: Int
                        while (reader.read(buf).also { n = it } > 0) sb.append(buf, 0, n)
                        HelboyStore.mergeExternal(JSONObject(sb.toString()))
                    }
                }
                refreshed = true
                Log.i(TAG, "persian refresh merged")
            } catch (e: Throwable) {
                Log.e(TAG, "persian refresh failed", e)
                refreshed = true
            }
        }
    }
}
