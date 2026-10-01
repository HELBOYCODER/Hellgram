package desu.inugram.ui.helboy

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenu
import org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick
import org.telegram.ui.ActionBar.BackDrawable
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.HeaderCell
import org.telegram.ui.Cells.TextSettingsCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.utils.proxy.ProxySettings
import java.net.HttpURLConnection
import java.net.URL

// helboy: visible "Fresh Proxies" screen — a real GUI for the community proxy list.
// Fetches MTProto proxies from public sources (SoliSpirit/mtproto primary + CDN mirrors so
// at least one resolves when the tunnel is down), shows them in a tappable list, and
// connects on tap. Caches the last list so it renders offline / pre-login.
class HelboyProxyListActivity : BaseFragment() {

    private class Entry(val info: SharedConfig.ProxyInfo, val raw: String)

    private var freshProxies = ArrayList<Entry>()
    private var statusText = ""
    private var adapter: FreshAdapter? = null

    companion object {
        private val SOURCES = listOf(
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            "https://cdn.jsdelivr.net/gh/SoliSpirit/mtproto@master/all_proxies.txt",
            "https://gh-proxy.com/https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            "https://ghproxy.net/https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"
        )
        private const val PREFS = "helboy_fresh_proxies"
        private const val KEY_CACHE = "cached_list"
        private const val MENU_REFRESH = 2101
    }

    override fun createView(context: Context): View {
        actionBar.setBackButtonDrawable(BackDrawable(false))
        actionBar.setTitle("Fresh Proxies")
        actionBar.setAllowOverlayTitle(true)
        val menu: ActionBarMenu = actionBar.createMenu()
        menu.addItemWithWidth(MENU_REFRESH, R.drawable.inu_proxy_refresh, AndroidUtilities.dp(54))

        actionBar.setActionBarMenuOnItemClick(object : ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_REFRESH -> refresh(manual = true)
                }
            }
        })

        loadCache(context)

        val frameLayout = FrameLayout(context)
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))

        val listView = RecyclerListView(context)
        listView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = FreshAdapter(context)
        listView.adapter = adapter
        listView.setOnItemClickListener { view, position ->
            if (position >= 2 && position - 2 < freshProxies.size) {
                connect(freshProxies[position - 2])
            }
        }
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.TOP or Gravity.LEFT))

        val status = TextView(context)
        status.id = View.generateViewId()
        status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4))
        status.textSize = 13f
        status.gravity = Gravity.CENTER
        status.text = statusText
        frameLayout.addView(status, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM, 16f, 0f, 16f, 12f))
        statusView = status

        AndroidUtilities.runOnUIThread({ refresh(manual = false) }, 400)
        return frameLayout
    }

    private var statusView: TextView? = null

    private fun connect(entry: Entry) {
        try {
            SharedConfig.currentProxy = SharedConfig.addProxy(entry.info)
            MessagesController.getGlobalMainSettings().edit().putBoolean("proxy_enabled", true).commit()
            ConnectionsManager.setProxySettings(true, entry.info.settings)
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString("ConnectingConnectProxy", R.string.ConnectingConnectProxy)).show()
        } catch (t: Throwable) {
            org.telegram.messenger.FileLog.e(t)
        }
    }

    private fun refresh(manual: Boolean) {
        statusText = if (manual) "fetching fresh proxies…" else statusText.ifBlank { "fetching…" }
        statusView?.text = statusText
        adapter?.notifyDataSetChanged()
        Thread {
            var fetched: List<String>? = null
            for (url in SOURCES) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 12000
                    conn.setRequestProperty("User-Agent", "Helboy/1.0")
                    val lines = conn.inputStream.bufferedReader().readLines().filter { it.contains("/proxy?") }
                    if (lines.isNotEmpty()) { fetched = lines; break }
                } catch (t: Throwable) {
                    org.telegram.messenger.FileLog.e(t)
                }
            }
            val result = fetched ?: emptyList()
            AndroidUtilities.runOnUIThread {
                freshProxies.clear()
                val seen = HashSet<String>()
                for (line in result) {
                    val info = parse(line) ?: continue
                    val k = key(info)
                    if (!seen.add(k)) continue
                    freshProxies.add(Entry(info, line))
                }
                statusText = when {
                    freshProxies.isNotEmpty() -> "${freshProxies.size} fresh proxies — tap to connect"
                    manual -> "fetch failed — no source reachable"
                    else -> statusText
                }
                if (freshProxies.isNotEmpty()) saveCache(context, result)
                if (manual && freshProxies.isEmpty()) {
                    BulletinFactory.of(this@HelboyProxyListActivity).createSimpleBulletin(R.raw.error, "Fetch failed").show()
                }
                statusView?.text = statusText
                adapter?.notifyDataSetChanged()
            }
        }.start()
    }

    private fun parse(line: String): SharedConfig.ProxyInfo? {
        return try {
            val q = line.indexOf('?')
            if (q < 0) return null
            val params = line.substring(q + 1).split('&')
                .mapNotNull { i ->
                    val p = i.indexOf('=')
                    if (p <= 0) null else i.substring(0, p) to i.substring(p + 1)
                }.toMap()
            val server = params["server"]?.trimEnd('.') ?: return null
            val port = params["port"]?.toIntOrNull() ?: return null
            val secret = params["secret"] ?: return null
            if (server.isBlank() || port !in 1..65535 || secret.isBlank()) return null
            SharedConfig.ProxyInfo(
                ProxySettings.builder()
                    .setType(ProxySettings.Type.MTPROTO)
                    .setAddress(server).setPort(port).setSecret(secret).build()
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun key(info: SharedConfig.ProxyInfo): String =
        "${info.settings.address}:${info.settings.port}:${info.settings.secret}"

    private fun saveCache(context: Context?, lines: List<String>) {
        try {
            (context ?: parentActivity)?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_CACHE, lines.joinToString("\n"))?.apply()
        } catch (t: Throwable) {
        }
    }

    private fun loadCache(context: Context) {
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CACHE, null) ?: return
            val seen = HashSet<String>()
            for (line in raw.split("\n")) {
                val info = parse(line) ?: continue
                if (!seen.add(key(info))) continue
                freshProxies.add(Entry(info, line))
            }
            if (freshProxies.isNotEmpty()) statusText = "${freshProxies.size} fresh proxies (cached)"
        } catch (t: Throwable) {
        }
    }

    private inner class FreshAdapter(val ctx: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = 2 + freshProxies.size
        override fun getItemViewType(position: Int): Int = if (position < 2) position else 2
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v: View = when (viewType) {
                0 -> HeaderCell(ctx)
                1 -> TextSettingsCell(ctx)
                else -> ProxyCell(ctx)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when {
                position == 0 -> (holder.itemView as HeaderCell).setText("FRESH PROXIES — TAP TO CONNECT")
                position == 1 -> (holder.itemView as TextSettingsCell).setTextAndValue(statusText, null, false)
                else -> {
                    val e = freshProxies[position - 2]
                    val selected = SharedConfig.currentProxy != null && key(SharedConfig.currentProxy) == key(e.info)
                    (holder.itemView as ProxyCell).bind("${e.info.settings.address}:${e.info.settings.port}", selected)
                }
            }
        }
    }

    private class ProxyCell(ctx: Context) : LinearLayout(ctx) {
        private val title = TextView(ctx)
        private val check = TextView(ctx)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), 0)
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            title.textSize = 16f
            title.setSingleLine(true)
            title.ellipsize = TextUtils.TruncateAt.MIDDLE
            addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            check.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4))
            check.textSize = 16f
            addView(check, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            minimumHeight = AndroidUtilities.dp(50)
        }

        fun bind(text: String, selected: Boolean) {
            title.text = text
            check.text = if (selected) "✓" else ""
        }
    }
}
