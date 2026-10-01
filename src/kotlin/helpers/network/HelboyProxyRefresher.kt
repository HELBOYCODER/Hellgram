package desu.inugram.helpers.network

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.tgnet.ConnectionsManager
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.utils.proxy.ProxySettings
import org.telegram.messenger.FileLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

// helboy: fresh MTProto proxy pool fetched from the top community list
// (SoliSpirit/mtproto on GitHub — most starred, auto-updated daily).
//
// Behaviour:
// - every 20 minutes, if the Hellboy Tunnel is NOT connected/healthy, fetch a fresh
//   proxy list and silently merge it into the Telegram proxy list (enabled list grows,
//   user sees them in the proxy screen).
// - if the tunnel failed, auto-pick the first unseen proxy and connect through it, so
//   the user gets online via "fresh proxies" without any interaction.
// - ProxyListActivity exposes a manual "refresh proxies" button that forces a fetch.
object HelboyProxyRefresher {

    const val SOURCE_URL = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"
    private const val PREFS = "helboy_proxy_refresh"
    private const val KEY_LAST_FETCH = "last_fetch_ms"
    private const val KEY_SEEN = "seen_proxies"
    private const val INTERVAL_MS = 20 * 60 * 1000L

    @Volatile private var fetching = AtomicBoolean(false)
    @Volatile var lastFetchResult: String = ""
        private set
    @Volatile var lastFetchTime: Long = 0
        private set

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun isDue(ctx: Context): Boolean =
        SystemClock.elapsedRealtime() - prefs(ctx).getLong(KEY_LAST_FETCH, 0L) > INTERVAL_MS

    /** Called from BuiltInTunnelHelper when the tunnel failed/lost connection. */
    @JvmStatic
    fun onTunnelDown(ctx: Context) {
        if (!isDue(ctx)) return
        refreshAsync(ctx, autoConnect = true)
    }

    /** Manual refresh (proxy screen button) — always fetches, never auto-connects. */
    @JvmStatic
    fun refreshAsync(ctx: Context, autoConnect: Boolean) {
        if (!fetching.compareAndSet(false, true)) return
        Thread {
            try {
                val proxies = fetchList()
                lastFetchTime = System.currentTimeMillis()
                if (proxies.isEmpty()) {
                    lastFetchResult = "no proxies in source"
                    return@Thread
                }
                val p = prefs(ctx)
                val seen = p.getStringSet(KEY_SEEN, HashSet()) ?: HashSet()
                var added = 0
                var firstNew: SharedConfig.ProxyInfo? = null
                // entiny: cap growth — the source pushes ~180 rows and this ran every 20 min,
                // so unbounded adds bloated SharedConfig and made every list re-render janky.
                val limit = 60
                for (line in proxies) {
                    if (added >= limit) break
                    val info = parseProxy(line) ?: continue
                    if (seen.contains(keyOf(info))) continue
                    SharedConfig.addProxy(info)
                    seen.add(keyOf(info))
                    added++
                    if (firstNew == null) firstNew = info
                }
                p.edit().putStringSet(KEY_SEEN, seen)
                    .putLong(KEY_LAST_FETCH, SystemClock.elapsedRealtime()).commit()
                lastFetchResult = "+$added fresh proxies (${proxies.size} in source)"

                if (autoConnect && firstNew != null) {
                    // connect through the freshest proxy so the user can reach Telegram
                    AndroidUtilities.runOnUIThread {
                        try {
                            SharedConfig.currentProxy = firstNew
                            val s = firstNew.settings
                            MessagesController.getGlobalMainSettings().edit()
                                .putBoolean("proxy_enabled", true).commit()
                            ConnectionsManager.setProxySettings(true, s)
                            NotificationCenter.getGlobalInstance()
                                .postNotificationName(NotificationCenter.proxySettingsChanged)
                        } catch (t: Throwable) {
                            FileLog.e(t)
                        }
                    }
                }
            } catch (t: Throwable) {
                lastFetchResult = "fetch failed: ${t.message}"
                FileLog.e(t)
            } finally {
                fetching.set(false)
            }
        }.start()
    }

    private fun fetchList(): List<String> {
        return try {
            val conn = URL(SOURCE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Helboy/1.0")
            conn.inputStream.bufferedReader().readLines().filter { it.contains("/proxy?") }
        } catch (t: Throwable) {
            FileLog.e(t)
            emptyList()
        }
    }

    // supports https://t.me/proxy?... and tg://proxy?... links
    fun parseProxy(line: String): SharedConfig.ProxyInfo? {
        val cleaned = line.trim()
        val q = cleaned.indexOf('?')
        if (q < 0) return null
        val params = cleaned.substring(q + 1).split('&')
            .mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
        val server = params["server"]?.trimEnd('.') ?: return null
        val port = params["port"]?.toIntOrNull() ?: return null
        val secret = params["secret"] ?: return null
        if (server.isBlank() || port !in 1..65535 || secret.isBlank()) return null
        return SharedConfig.ProxyInfo(
            ProxySettings.builder()
                .setType(ProxySettings.Type.MTPROTO)
                .setAddress(server)
                .setPort(port)
                .setSecret(secret)
                .build()
        )
    }

    private fun keyOf(info: SharedConfig.ProxyInfo): String =
        "${info.settings.address}:${info.settings.port}:${info.settings.secret}"
}
