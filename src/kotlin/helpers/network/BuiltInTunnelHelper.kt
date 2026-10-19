package desu.inugram.helpers.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.network.v2ray.V2RayLink
import desu.inugram.helpers.network.v2ray.V2RayScraper
import desu.inugram.helpers.network.v2ray.XrayEngine
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.ConnectionsManager
import java.net.InetSocketAddress
import java.net.Socket

// entiny: built-in Xray (V2Ray) tunnel; local SOCKS5/HTTP on 1819/1820, server picked from the scraped fastest list
object BuiltInTunnelHelper {
    private const val TAG = "InuTunnel"

    const val SOCKS_PORT = 1819
    const val HTTP_PORT = 1820

    @Volatile private var engineRunning = false
    @Volatile private var starting = false
    @Volatile private var lastStatus = ""
    @Volatile private var proxyPrefBefore = false
    @Volatile private var handle: XrayEngine.Handle? = null

    @Volatile var rttMs = 0
        private set
    @Volatile var peer = ""
        private set

    private var appContext: Context? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdog: Thread? = null

    @Volatile private var restarting = false
    // entiny: generation guard — a start/dial thread must exit as soon as a newer start()
    // replaces it (settings change mid-dial), otherwise the stale thread reports state of an
    // engine instance it no longer owns.
    @Volatile private var startGeneration = 0L
    private var lastNetworkChange = 0L
    private var autoRetries = 0
    private val MAX_AUTO_RETRIES = 5

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        XrayEngine.killOrphans()
        initNetworkCallback(context)
        startWatchdog()
        if (InuConfig.BUILT_IN_TUNNEL.value) start()
    }

    // entiny: the engine can die (or its port go stale) while engineRunning is still true — network
    // switches, doze, or an upstream drop. Previously that left Telegram proxying into a dead port
    // with no recovery until the user toggled the tunnel by hand. Poll the port and restart.
    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread {
            var failures = 0
            while (true) {
                try {
                    Thread.sleep(15000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!InuConfig.BUILT_IN_TUNNEL.value || starting || restarting) continue
                // entiny: the tunnel is enabled but the engine is down (all retries exhausted).
                // The watchdog is the only thing left that can bring it back without a manual
                // toggle, so treat that as a failure too.
                if (!engineRunning) {
                    failures++
                    if (failures < 2) continue
                    failures = 0
                    Log.w(TAG, "tunnel enabled but engine down -> restarting")
                    restart(reason = "engine down")
                    continue
                }
                // entiny: probe the real forwarding path — isPortOpen alone misses the
                // "listener accepts but forwards nothing" failure.
                if (probeThroughProxy(3500)) {
                    failures = 0
                    // entiny: tunnel healthy again — give the auto-retry budget back so a later
                    // drop still gets its 3 reconnect attempts.
                    if (autoRetries > 0) autoRetries = 0
                    continue
                }
                if (handle?.alive() != true) {
                    Log.w(TAG, "xray process is gone")
                    failures = 2
                }
                failures++
                Log.w(TAG, "tunnel port dead (check $failures)")
                if (failures < 2) continue
                failures = 0
                restart(reason = "tunnel unresponsive")
            }
        }.apply {
            name = "inu-tunnel-watchdog"
            isDaemon = true
            start()
        }
    }

    // entiny: restart without clearing the user's preference — a transient drop must not switch
    // the tunnel off permanently the way onFailed() does for a first-connect failure.
    @Volatile private var lastRestartAt = 0L

    @Synchronized
    private fun restart(reason: String) {
        if (restarting) return
        // entiny: rate-limit restarts — a flaky link used to bounce the engine every watchdog
        // cycle (15s), re-firing proxySettingsChanged and flashing every open screen white.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRestartAt < 90_000L) return
        lastRestartAt = now
        restarting = true
        lastStatus = "reconnecting ($reason)"
        postState()
        try {
            stopProcess()
            engineRunning = false
            starting = false
            clearMediaRoute()
            start()
        } finally {
            // entiny: clear the flag synchronously — start() spawns its own thread and the
            // engine start path checks nothing about `restarting`, but leaving it set until
            // the next main-loop pass raced with onFailed()'s auto-retry and swallowed it
            // (retry saw restarting==true and bailed → tunnel stuck "reconnecting" forever).
            restarting = false
        }
    }

    private fun initNetworkCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged("available")
            }
            override fun onLost(network: Network) {
                onNetworkChanged("lost")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (isActive() && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    // VPN took over - suppress our proxy
                }
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Throwable) {}
    }

    // entiny: onAvailable fires once per network (wifi + cell + vpn) in quick succession; without
    // debouncing, each event tore down and rebuilt the engine and they fought each other.
    private fun onNetworkChanged(what: String) {
        if (!InuConfig.BUILT_IN_TUNNEL.value || starting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkChange < 3000) return
        lastNetworkChange = now
        Log.i(TAG, "network $what -> reconnecting tunnel")
        restart(reason = "network $what")
    }

    @JvmStatic
    fun isActive(): Boolean = InuConfig.BUILT_IN_TUNNEL.value && engineRunning && !restarting

    @JvmStatic
    fun isStarting(): Boolean = starting || (V2RayScraper.isScanning() && V2RayScraper.connectAfterScan)

    @JvmStatic
    fun reportStatus(text: String) {
        lastStatus = text
        postState()
    }

    @JvmStatic
    fun statusText(): String = lastStatus

    // entiny: engine state as its own line, used by diagnostics
    @JvmStatic
    fun stateText(): String {
        if (!InuConfig.BUILT_IN_TUNNEL.value) return "off"
        if (engineRunning) return "connected"
        if (starting) return "connecting"
        return if (handle?.alive() == true) "engine-up" else "engine-down"
    }

    @JvmStatic
    fun logsText(): String = XrayEngine.logs()

    // entiny: "connected but nothing loads" needed sight into the real state, because the engine
    // log alone does not say whether the local port actually forwards. This probes every layer so
    // a shared log immediately shows which one is broken.
    @JvmStatic
    fun diagnosticsText(): String {
        val sb = StringBuilder()
        sb.append("=== Hellboy Tunnel diagnostics ===\n")
        sb.append("time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
        sb.append("enabled: ").append(InuConfig.BUILT_IN_TUNNEL.value).append('\n')
        sb.append("state: ").append(stateText()).append('\n')
        sb.append("engineRunning: ").append(engineRunning).append('\n')
        sb.append("starting: ").append(starting).append('\n')
        sb.append("restarting: ").append(restarting).append('\n')
        sb.append("lastStatus: ").append(lastStatus).append('\n')
        sb.append("autoRetries: ").append(autoRetries).append('/').append(MAX_AUTO_RETRIES).append('\n')
        sb.append("socksPort: ").append(SOCKS_PORT).append(" open=").append(isPortOpen(SOCKS_PORT)).append('\n')
        sb.append("httpPort: ").append(HTTP_PORT).append(" open=").append(isPortOpen(HTTP_PORT)).append('\n')
        sb.append("handshake (probeThroughProxy): ").append(probeThroughProxy(4000)).append('\n')
        val bp = TunnelHttpBridge.port
        sb.append("bridgePort: ").append(bp).append('\n')
        if (bp > 0) {
            sb.append("bridgeHandshake: ").append(
                try {
                    Socket().use { s ->
                        s.soTimeout = 3000
                        s.connect(InetSocketAddress("127.0.0.1", bp), 3000)
                        true
                    }
                } catch (_: Throwable) { false }
            ).append('\n')
        }
        sb.append("mediaRouteInstalled: ").append(TunnelSocketRoute.isInstalled())
            .append(" routePort=").append(TunnelSocketRoute.currentPort()).append('\n')
        try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val n = cm?.activeNetwork
            val caps = n?.let { cm.getNetworkCapabilities(it) }
            sb.append("network: ").append(
                when {
                    caps == null -> "unknown"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                }
            ).append('\n')
        } catch (_: Throwable) {}
        sb.append("\n=== engine log ===\n")
        sb.append(logsText().takeLast(6000))
        return sb.toString()
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        if (enabled) connect() else disconnect()
    }

    @JvmStatic
    fun connect(): Boolean {
        if (V2RayLink.parse(InuConfig.BUILT_IN_TUNNEL_SERVER.value) == null) {
            val ctx = appContext ?: return false
            lastStatus = "searching servers"
            postState()
            V2RayScraper.scanAsync(ctx, connectBest = true)
            return true
        }
        InuConfig.BUILT_IN_TUNNEL.value = true
        start()
        return true
    }

    @JvmStatic
    fun disconnect() {
        V2RayScraper.cancelConnect()
        InuConfig.BUILT_IN_TUNNEL.value = false
        stop()
    }

    @JvmStatic
    fun selectServer(link: String) {
        InuConfig.BUILT_IN_TUNNEL_SERVER.value = link
        InuConfig.BUILT_IN_TUNNEL.value = true
        restartIfNeeded()
    }

    @JvmStatic
    fun restartIfNeeded() {
        // entiny: settings must ALWAYS take effect while the user intends the
        // tunnel to be on. The old gate (isActive()) made every option a no-op while the
        // engine was still dialing or had failed: taps felt dead and the fix was a manual
        // disconnect/reconnect. Now: restart immediately when running; when a start attempt
        // is in flight, stop it and start fresh; when enabled-but-failed, retry with the
        // new values right away.
        if (!InuConfig.BUILT_IN_TUNNEL.value) return
        restarting = true
        try {
            if (starting || engineRunning) {
                stopEngine()
            }
        } finally {
            restarting = false
        }
        autoRetries = 0
        start()
    }

    private fun start() {
        val context = appContext ?: return
        if (starting || engineRunning) return
        val server = V2RayLink.parse(InuConfig.BUILT_IN_TUNNEL_SERVER.value)
        if (server == null) {
            lastStatus = "no server selected"
            postState()
            return
        }
        starting = true
        val myGeneration = ++startGeneration
        proxyPrefBefore = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)
        Thread {
            if (!XrayEngine.available(context)) {
                onFailed("tunnel engine missing in this build")
                return@Thread
            }
            try {
                lastStatus = "starting"
                postState()
                stopProcess()
                val started = XrayEngine.start(context, server, SOCKS_PORT, HTTP_PORT)
                handle = started
                peer = server.name
                val t0 = SystemClock.elapsedRealtime()
                if (!XrayEngine.waitForPort(started, SOCKS_PORT, 5000)) {
                    if (startGeneration != myGeneration) return@Thread
                    val tail = XrayEngine.logs().lines().takeLast(4).joinToString(" | ")
                    stopProcess()
                    onFailed("engine did not start $tail".take(240))
                    return@Thread
                }
                lastStatus = "verifying proxy"
                postState()
                val deadline = SystemClock.elapsedRealtime() + 20000
                var ready = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (startGeneration != myGeneration) return@Thread
                    if (!InuConfig.BUILT_IN_TUNNEL.value) {
                        starting = false
                        stopEngine()
                        return@Thread
                    }
                    if (!started.alive()) break
                    if (probeThroughProxy(4000)) {
                        ready = true
                        break
                    }
                    Thread.sleep(500)
                }
                if (startGeneration != myGeneration) return@Thread
                if (ready) {
                    rttMs = (SystemClock.elapsedRealtime() - t0).toInt()
                    onConnected("${server.name} (127.0.0.1:$SOCKS_PORT)")
                } else {
                    val tail = XrayEngine.logs().lines().takeLast(4).joinToString(" | ")
                    stopProcess()
                    onFailed("server not responding $tail".take(240))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "tunnel start error", e)
                onFailed(e.message ?: "error")
            }
        }.apply {
            name = "inu-tunnel-start"
            isDaemon = true
            start()
        }
    }

    private fun stopProcess() {
        val h = handle
        handle = null
        try { h?.stop() } catch (_: Throwable) {}
    }

    private fun stop() {
        stopEngine()
        appContext?.let { desu.inugram.helpers.network.TunnelForegroundService.refresh(it) }
        desu.inugram.helpers.helboy.HelboyWebViewProxy.clear()
        clearMediaRoute()
        TunnelHttpBridge.stop()
        restoreProxyPref()
        CensorshipHelper.reapply()
    }

    private fun clearMediaRoute() {
        TunnelSocketRoute.clear()
        TunnelHttpBridge.stop()
    }

    private fun stopEngine() {
        stopProcess()
        engineRunning = false
        starting = false
        lastStatus = ""
        // entiny: never leave the process-wide selector pointing at a dead tunnel
        clearMediaRoute()
    }

    // entiny: public reconnect hook for network changes
    @JvmStatic
    fun reconnect() {
        if (isActive()) {
            stopEngine()
            start()
        }
    }

    private fun onConnected(status: String) {
        engineRunning = true
        starting = false
        lastStatus = status
        autoRetries = 0
        appContext?.let { desu.inugram.helpers.network.TunnelForegroundService.refresh(it) }
        Log.i(TAG, "built-in tunnel connected: $status")
        // entiny: install off the UI thread — install() probes the port synchronously.
        // The bridge is what media3/WebView can actually use: they only speak HTTP proxies, and
        // both are started against the SOCKS port, which the engine always exposes.
        val bridgePort = TunnelHttpBridge.ensureStarted(SOCKS_PORT)
        TunnelSocketRoute.install(if (bridgePort > 0) bridgePort else HTTP_PORT)
        AndroidUtilities.runOnUIThread {
            MessagesController.getGlobalMainSettings().edit { putBoolean("proxy_enabled", true) }
            for (a in 0 until org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) {
                try {
                    ConnectionsManager.getInstance(a).resumeNetworkMaybe()
                } catch (_: Throwable) {}
            }
            ConnectionsManager.setProxySettings(true, "127.0.0.1", SOCKS_PORT, "", "", "")
            desu.inugram.helpers.helboy.HelboyWebViewProxy.applyFromTunnel()
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
            postState()
        }
    }

    private fun restoreProxyPref() {
        MessagesController.getGlobalMainSettings().edit { putBoolean("proxy_enabled", proxyPrefBefore) }
    }

    private fun onFailed(error: String) {
        starting = false
        engineRunning = false
        lastStatus = error
        appContext?.let { desu.inugram.helpers.network.TunnelForegroundService.refresh(it) }
        Log.e(TAG, "built-in tunnel failed: $error")
        // helboy: tunnel is down — pull fresh community proxies so the user can still connect
        appContext?.let { desu.inugram.helpers.network.HelboyProxyRefresher.onTunnelDown(it) }
        // entiny: tunnel failures land in the same copyable journal as everything else
        desu.inugram.helpers.diagnostics.ErrorLog.record("tunnel", error)
        // entiny: a failure right after a network transition is usually transient. Retry with
        // backoff instead of switching the tunnel off for the rest of the session (which is what
        // made the old build look like "reconnect is broken" after wifi<->mobile switches).
        // entiny: onFailed can fire for BOTH fresh starts and reconnects — retry on both, and
        // reset autoRetries whenever the tunnel stayed healthy for a while (watchdog resets it
        // via probe), so an unlucky streak doesn't permanently disable reconnection.
        if (InuConfig.BUILT_IN_TUNNEL.value && autoRetries < MAX_AUTO_RETRIES) {
            autoRetries++
            lastStatus = "retrying ($autoRetries/$MAX_AUTO_RETRIES): $error"
            postState()
            Thread {
                try {
                    Thread.sleep(2500L * autoRetries)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (InuConfig.BUILT_IN_TUNNEL.value && !engineRunning && !starting && !restarting) start()
            }.apply { isDaemon = true }.start()
            return
        }
        AndroidUtilities.runOnUIThread {
            desu.inugram.helpers.helboy.HelboyWebViewProxy.clear()
            clearMediaRoute()
            // entiny: do NOT clear the user's preference here. Doing that is what made the tunnel
            // need a manual toggle: one failed start switched the feature off, so init() on the
            // next launch would not auto-start it. Leave it on and let the watchdog bring it back.
            restoreProxyPref()
            CensorshipHelper.reapply()
            postState()
        }
    }

    private fun postState() {
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        }
    }

    // entiny: bounded history so a burst of identical state posts cannot pile up
    @Volatile private var lastPostedStatus: String? = null

    private fun postStateIfChanged(status: String) {
        if (status == lastPostedStatus) return
        lastPostedStatus = status
        postState()
    }

    // entiny: a TCP connect to the port is NOT proof the tunnel works. The listener can accept
    // and then fail to forward, which is exactly the "shows connected but nothing loads" case.
    // Drive a real SOCKS5 handshake + CONNECT and require the engine to accept it.
    @JvmStatic
    fun probeThroughProxy(timeoutMs: Int = 4000): Boolean {
        if (!isPortOpen(SOCKS_PORT)) return false
        return XrayEngine.httpProbe(SOCKS_PORT, timeoutMs)
    }

    // entiny: media3/ExoPlayer route through the tunnel needs this probe too
    @JvmStatic
    fun isPortOpen(port: Int): Boolean = XrayEngine.isPortOpen(port)
}
