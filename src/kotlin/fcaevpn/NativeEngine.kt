package com.fc.fcaevpn

import android.util.Log
import androidx.annotation.Keep

// entiny: JNI surface of the embedded MASQUE/WARP tunnel engine, mirroring the upstream
// wrapper at 1.3.5.7 exactly (signatures must match the prebuilt .so). Ported from
// FCAE VPN (GPLv3), https://github.com/FCFlenkchy/FCAE_VPN
@Keep
object NativeEngine {
    private var isLoaded = false

    fun loadLibrary(): Boolean {
        if (isLoaded) return true
        return try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("fcae_go_bridge")
            System.loadLibrary("fcaevpn_native")
            nativeInit()
            isLoaded = true
            Log.i("TunnelNative", "tunnel native libraries loaded")
            true
        } catch (e: Throwable) {
            Log.e("TunnelNative", "Failed to load tunnel native libraries", e)
            false
        }
    }

    @JvmStatic external fun nativeInit()
    @JvmStatic external fun nativeSetNativeLibDir(path: String)
    @JvmStatic external fun nativeStart(
        protocol: Int,
        mode: Int,
        lanSharing: Boolean,
        scanMode: Int,
        ipVersion: Int,
        quickReconnect: Boolean,
        noizeProfile: String,
        fragmentEnabled: Boolean,
        fragMinSize: Int,
        fragMaxSize: Int,
        fragMinDelay: Int,
        fragMaxDelay: Int,
        socksPort: Int,
        httpPort: Int,
        forcePeer: String,
        configPath: String,
        h2Enabled: Boolean,
        echEnabled: Boolean,
        sni: String,
        sysProfile: Int,
        teamName: String,
        accessToken: String,
        accessEmail: String,
        routesFile: String,
        routesInline: String,
        torMode: Int,
        torBridges: Int,
        torBridgeLines: String,
        engineLog: Int,
        backend: Int,
        torSocksPort: Int,
        torHttpPort: Int,
        psiphonThroughTunnel: Boolean,
        psiphonConfig: String,
        psiphonRegion: String,
        psiphonSocksPort: Int,
        psiphonHttpPort: Int,
        tunTcpSndbuf: Int,
        tunTcpRcvbuf: Int,
        tunTcpAutoTuning: Boolean,
        t2sLog: Int,
        tunEngine: Int,
        tunMtu: Int,
        tunDnsServers: String,
    ): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeStopBegin()
    @JvmStatic external fun nativeGetLogs(): String
    @JvmStatic external fun nativeClearLogs()

    @JvmStatic external fun nativeGetState(): Int
    @JvmStatic external fun nativeGetRxBps(): Long
    @JvmStatic external fun nativeGetTxBps(): Long
    @JvmStatic external fun nativeGetTotalRx(): Long
    @JvmStatic external fun nativeGetTotalTx(): Long
    @JvmStatic external fun nativeGetRttMs(): Int
    @JvmStatic external fun nativeGetPeer(): String
    @JvmStatic external fun nativeGetLanIp(): String
    @JvmStatic external fun nativeGetStatusMsg(): String
    @JvmStatic external fun nativeGetLastError(): String
}
