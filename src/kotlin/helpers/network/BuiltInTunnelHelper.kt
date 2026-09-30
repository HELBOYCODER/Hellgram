package desu.inugram.helpers.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.fc.fcaevpn.NativeEngine
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.ConnectionsManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

// entiny: built-in MASQUE/WARP proxy tunnel; local SOCKS5/HTTP on 1819/1820, ported from FCAE VPN (GPLv3)
object BuiltInTunnelHelper {
    private const val TAG = "InuTunnel"

    const val PROTOCOL_MASQUE = 0
    const val PROTOCOL_WIREGUARD = 1
    const val PROTOCOL_GOOL = 2
    const val PROTOCOL_AUTO = 3
    const val PROTOCOL_MASQUE_IN_MASQUE = 5

    const val SOCKS_PORT = 1819
    const val HTTP_PORT = 1820

    @Volatile private var engineRunning = false
    @Volatile private var starting = false
    @Volatile private var lastStatus = ""
    @Volatile private var proxyPrefBefore = false

    @Volatile var rttMs = 0
        private set
    @Volatile var peer = ""
        private set

    private var appContext: Context? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdog: Thread? = null

    @Volatile private var restarting = false
    private var lastNetworkChange = 0L
    private var autoRetries = 0
    private val MAX_AUTO_RETRIES = 3

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
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
                // entiny: FCAE parity — the engine heals itself (state 6 / respawning gateway);
                // probing during that window double-fails and our old code killed a healthy
                // self-reconnecting engine with nativeStop(). Only treat probe failure as fatal
                // when the engine itself is terminal (0/5); while 1..4/6 give it time.
                if (probeThroughProxy(3500)) {
                    failures = 0
                    // entiny: tunnel healthy again — give the auto-retry budget back so a later
                    // drop still gets its 3 reconnect attempts.
                    if (autoRetries > 0) autoRetries = 0
                    continue
                }
                val engineState = try { NativeEngine.nativeGetState() } catch (_: Throwable) { -1 }
                if (engineState in 1..4 || engineState == 6) {
                    Log.i(TAG, "probe missed but engine state $engineState is self-healing; waiting")
                    failures = 0
                    continue
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
    @Synchronized
    private fun restart(reason: String) {
        if (restarting) return
        restarting = true
        lastStatus = "reconnecting ($reason)"
        postState()
        try {
            try { NativeEngine.nativeStop() } catch (_: Throwable) {}
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
    fun isStarting(): Boolean = starting

    @JvmStatic
    fun statusText(): String = lastStatus

    // entiny: engine state as its own list, used by diagnostics. 4 = the engine reports connected.
    @JvmStatic
    fun stateText(): String {
        if (!InuConfig.BUILT_IN_TUNNEL.value) return "off"
        if (engineRunning) return "connected"
        if (starting) return "connecting"
        return try {
            when (val s = NativeEngine.nativeGetState()) {
                1 -> "provisioning"; 2 -> "scanning"; 3 -> "connecting"
                4 -> "engine-says-connected"; 6 -> "reconnecting"
                else -> "state $s"
            }
        } catch (_: Throwable) { "unknown" }
    }

    @JvmStatic
    fun logsText(): String = try { NativeEngine.nativeGetLogs() } catch (_: Throwable) { "" }

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
        InuConfig.BUILT_IN_TUNNEL.value = enabled
        if (enabled) start() else stop()
    }

    @JvmStatic
    fun connect() {
        InuConfig.BUILT_IN_TUNNEL.value = true
        start()
    }

    @JvmStatic
    fun disconnect() {
        InuConfig.BUILT_IN_TUNNEL.value = false
        stop()
    }

    @JvmStatic
    fun restartIfNeeded() {
        if (isActive()) {
            stopEngine()
            start()
        }
    }

    private fun start() {
        val context = appContext ?: return
        if (starting || engineRunning) return
        starting = true
        proxyPrefBefore = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)
        Thread {
            if (!NativeEngine.loadLibrary()) {
                onFailed("native library missing")
                return@Thread
            }
            try {
                lastStatus = "starting"
                postState()
                val configDir = File(context.filesDir, "tunnel").apply { mkdirs() }
                try { NativeEngine.nativeStop() } catch (_: Throwable) {}
                try { NativeEngine.nativeSetNativeLibDir(context.applicationInfo.nativeLibraryDir) } catch (_: Throwable) {}
                val ok = NativeEngine.nativeStart(
                    protocol = tunnelProtocol(),
                    mode = 0,
                    lanSharing = false,
                    scanMode = InuConfig.BUILT_IN_TUNNEL_SCAN.value,
                    ipVersion = when (InuConfig.BUILT_IN_TUNNEL_IP.value) { 1 -> 6; 2 -> 10; else -> 4 },
                    quickReconnect = true,
                    noizeProfile = when (val n = InuConfig.BUILT_IN_TUNNEL_NOIZE.value) {
                        "none" -> "off"
                        else -> n
                    },
                    fragmentEnabled = InuConfig.BUILT_IN_TUNNEL_FRAGMENT.value,
                    fragMinSize = 16,
                    fragMaxSize = 32,
                    fragMinDelay = 2,
                    fragMaxDelay = 10,
                    socksPort = SOCKS_PORT,
                    httpPort = HTTP_PORT,
                    forcePeer = InuConfig.BUILT_IN_TUNNEL_PEER.value,
                    configPath = File(configDir, "aether.toml").absolutePath,
                    h2Enabled = InuConfig.BUILT_IN_TUNNEL_H2.value,
                    echEnabled = InuConfig.BUILT_IN_TUNNEL_ECH.value,
                    sni = "",
                    sysProfile = 0,
                    teamName = "",
                    accessToken = "",
                    accessEmail = "",
                    routesFile = "",
                    routesInline = "",
                    torMode = 0,
                    torBridges = 0,
                    torBridgeLines = "",
                    engineLog = 3,
                    backend = InuConfig.BUILT_IN_TUNNEL_BACKEND.value,
                    torSocksPort = 0,
                    torHttpPort = 0,
                    psiphonThroughTunnel = false,
                    psiphonConfig = """{"FCAETransport":0}""",
                    psiphonRegion = InuConfig.BUILT_IN_TUNNEL_PSIPHON_REGION.value,
                    psiphonSocksPort = 0,
                    psiphonHttpPort = 0,
                    tunTcpSndbuf = 256000,
                    tunTcpRcvbuf = 256000,
                    tunTcpAutoTuning = false,
                    t2sLog = 0,
                    tunEngine = 0,
                    tunMtu = 1500,
                    tunDnsServers = ""
                )
                if (!ok) {
                    onFailed(NativeEngine.nativeGetLastError().ifBlank { "engine start failed" })
                    return@Thread
                }
                // entiny: FCAE parity. The upstream app NEVER times out a dial — states 1..4 and 6
                // are all "still working" and only terminal 0/5 end the session. The engine's own
                // turbo gateway scan alone has a 45s budget, so our old 45s deadline killed the
                // engine mid-scan and the retry loop relived the scan forever: stuck "connecting".
                // Poll until terminal, keep the UI status live.
                while (true) {
                    if (!InuConfig.BUILT_IN_TUNNEL.value) {
                        starting = false
                        stopEngine()
                        return@Thread
                    }
                    val state = try { NativeEngine.nativeGetState() } catch (_: Throwable) { -1 }
                    if (state == 0 || state == 5) {
                        val err = NativeEngine.nativeGetLastError().ifBlank { NativeEngine.nativeGetStatusMsg() }
                        onFailed(err.ifBlank { "connection failed" })
                        return@Thread
                    }
                    if (state == 4) {
                        // entiny: THE fix. state==4 is the engine's own opinion and it turns true
                        // before the listener forwards anything, so the old code advertised
                        // "connected" while every request through the proxy failed. Require a real
                        // SOCKS5 CONNECT to succeed before we trust it.
                        lastStatus = "verifying proxy"
                        postState()
                        if (waitForProxyReady(20000)) {
                            onConnected("127.0.0.1:$SOCKS_PORT")
                        } else {
                            val tail = try {
                                NativeEngine.nativeGetLogs().lines().takeLast(6).joinToString(" | ")
                            } catch (_: Throwable) { "" }
                            onFailed("port not forwarding $tail".take(240))
                        }
                        return@Thread
                    }
                    val stateName = when (state) {
                        1 -> "provisioning"; 2 -> "scanning"; 3 -> "connecting"; 6 -> "reconnecting"
                        else -> "state $state"
                    }
                    val msg = try { NativeEngine.nativeGetStatusMsg() } catch (_: Throwable) { "" }
                    val shown = if (msg.isNotBlank()) "$stateName: $msg" else stateName
                    if (shown != lastStatus) {
                        lastStatus = shown
                        postState()
                    }
                    Thread.sleep(350)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "tunnel start error", e)
                onFailed(e.message ?: "error")
            }
        }.start()
    }

    private fun stop() {
        stopEngine()
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
        try { NativeEngine.nativeStop() } catch (_: Throwable) {}
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
        Log.e(TAG, "built-in tunnel failed: $error")
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

    private fun tunnelProtocol(): Int {
        return when (InuConfig.BUILT_IN_TUNNEL_PROTOCOL.value) {
            1 -> PROTOCOL_WIREGUARD
            2 -> PROTOCOL_GOOL
            3 -> PROTOCOL_AUTO
            4 -> PROTOCOL_MASQUE_IN_MASQUE
            else -> PROTOCOL_MASQUE
        }
    }

    // entiny: a TCP connect to the port is NOT proof the tunnel works. The listener can accept
    // and then fail to forward, which is exactly the "shows connected but nothing loads" case.
    // Drive a real SOCKS5 handshake + CONNECT and require the engine to accept it.
    @JvmStatic
    fun probeThroughProxy(timeoutMs: Int = 4000): Boolean {
        if (!isPortOpen(SOCKS_PORT)) return false
        return try {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), timeoutMs)
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                // greeting: version 5, one method, no-auth
                out.write(byteArrayOf(5, 1, 0)); out.flush()
                val greet = ByteArray(2)
                if (inp.read(greet) != 2 || greet[0].toInt() != 5 || greet[1].toInt() != 0) return false
                // CONNECT 1.1.1.1:443
                // entiny: 0xBB > Byte.MAX_VALUE, so it must be written as an explicit Byte —
                // byteArrayOf() cannot coerce an out-of-range int literal and the whole module
                // failed to compile (this is what broke the release APK build).
                out.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0x01, 0xBB.toByte())); out.flush()
                val resp = ByteArray(4)
                if (inp.read(resp) != 4) return false
                if (resp[0].toInt() != 5) return false
                if (resp[1].toInt() != 0) return false // 0x00 = succeeded
                val rest = when (resp[3].toInt()) {
                    1 -> 6      // IPv4 + port
                    4 -> 18     // IPv6 + port
                    3 -> {      // domain: len byte + name + port
                        val len = inp.read()
                        if (len < 0) 0 else len + 2
                    }
                    else -> 0
                }
                if (rest > 0) {
                    val sink = ByteArray(rest)
                    inp.read(sink)
                }
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    // entiny: state==4 only means the engine believes it is up; the port may not be forwarding
    // yet. Poll the real handshake before we point Telegram at the proxy.
    private fun waitForProxyReady(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!InuConfig.BUILT_IN_TUNNEL.value) return false
            if (probeThroughProxy()) return true
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    // entiny: media3/ExoPlayer route through the tunnel needs this probe too
    @JvmStatic
    fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 350) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
