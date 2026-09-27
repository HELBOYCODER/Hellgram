package desu.inugram.helpers.network

import android.content.Context
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

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        if (InuConfig.BUILT_IN_TUNNEL.value) start()
    }

    @JvmStatic
    fun isActive(): Boolean = InuConfig.BUILT_IN_TUNNEL.value && engineRunning

    @JvmStatic
    fun isStarting(): Boolean = starting

    @JvmStatic
    fun statusText(): String = lastStatus

    @JvmStatic
    fun logsText(): String = try { NativeEngine.nativeGetLogs() } catch (_: Throwable) { "" }

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
            if (isPortOpen(SOCKS_PORT)) {
                onConnected("127.0.0.1:$SOCKS_PORT")
                return@Thread
            }
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
                    noizeProfile = InuConfig.BUILT_IN_TUNNEL_NOIZE.value,
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
                    psiphonConfig = "",
                    psiphonRegion = InuConfig.BUILT_IN_TUNNEL_PSIPHON_REGION.value,
                    psiphonSocksPort = 0,
                    psiphonHttpPort = 0,
                    tunTcpSndbuf = 0,
                    tunTcpRcvbuf = 0,
                    tunTcpAutoTuning = false,
                    t2sLog = 0,
                    tunEngine = 0,
                    tunMtu = 0,
                    tunDnsServers = ""
                )
                if (!ok) {
                    onFailed(NativeEngine.nativeGetLastError().ifBlank { "engine start failed" })
                    return@Thread
                }
                val deadline = SystemClock.elapsedRealtime() + 45000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!InuConfig.BUILT_IN_TUNNEL.value) {
                        starting = false
                        stopEngine()
                        return@Thread
                    }
                    val state = try { NativeEngine.nativeGetState() } catch (_: Throwable) { -1 }
                    if (state == 5) {
                        val err = NativeEngine.nativeGetLastError().ifBlank { NativeEngine.nativeGetStatusMsg() }
                        onFailed(err.ifBlank { "connection failed" })
                        return@Thread
                    }
                    if (state == 4 || isPortOpen(SOCKS_PORT)) {
                        onConnected("127.0.0.1:$SOCKS_PORT")
                        return@Thread
                    }
                    Thread.sleep(350)
                }
                onFailed("timeout — try Stealth or Ironclad scan, or set a custom endpoint")
            } catch (e: Throwable) {
                Log.e(TAG, "tunnel start error", e)
                onFailed(e.message ?: "error")
            }
        }.start()
    }

    private fun stop() {
        stopEngine()
        desu.inugram.helpers.helboy.HelboyWebViewProxy.clear()
        restoreProxyPref()
        CensorshipHelper.reapply()
    }

    private fun stopEngine() {
        try { NativeEngine.nativeStop() } catch (_: Throwable) {}
        engineRunning = false
        starting = false
        lastStatus = ""
    }

    private fun onConnected(status: String) {
        engineRunning = true
        starting = false
        lastStatus = status
        Log.i(TAG, "built-in tunnel connected: $status")
        AndroidUtilities.runOnUIThread {
            MessagesController.getGlobalMainSettings().edit { putBoolean("proxy_enabled", true) }
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
        AndroidUtilities.runOnUIThread {
            desu.inugram.helpers.helboy.HelboyWebViewProxy.clear()
            if (InuConfig.BUILT_IN_TUNNEL.value) {
                InuConfig.BUILT_IN_TUNNEL.value = false
                restoreProxyPref()
                CensorshipHelper.reapply()
            }
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

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 350) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
