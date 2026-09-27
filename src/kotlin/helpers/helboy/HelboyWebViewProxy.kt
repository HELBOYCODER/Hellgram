package desu.inugram.helpers.helboy

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import desu.inugram.helpers.network.BuiltInTunnelHelper

// entiny: routes all WebView traffic through the built-in tunnel's local HTTP port; ported from Suni TV (MIT)
object HelboyWebViewProxy {
    private const val TAG = "InuHelboyProxy"
    private var activeRule: String? = null

    @JvmStatic
    fun applyFromTunnel() {
        if (!BuiltInTunnelHelper.isActive()) {
            clear()
            return
        }
        setProxy("127.0.0.1", BuiltInTunnelHelper.HTTP_PORT)
    }

    private fun setProxy(host: String, port: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "PROXY_OVERRIDE unsupported; WebView stays direct")
            return
        }
        val rule = "$host:$port"
        if (activeRule == rule) return
        try {
            val config = ProxyConfig.Builder()
                .addProxyRule(rule)
                .addDirect()
                .build()
            ProxyController.getInstance().setProxyOverride(config, { it.run() }) {
                activeRule = rule
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to set WebView proxy", e)
        }
    }

    @JvmStatic
    fun clear() {
        if (activeRule == null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        try {
            ProxyController.getInstance().clearProxyOverride({ it.run() }) { activeRule = null }
        } catch (_: Throwable) {
        }
    }
}
