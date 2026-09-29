package desu.inugram.helpers.network

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

// helboy: route plain java.net.HttpURLConnection traffic (media3 DefaultHttpDataSource, used by
// Telegram's VideoPlayer/ExoPlayer for HLS + progressive streams) through the built-in tunnel's
// local HTTP port. WebView has its own override path in HelboyWebViewProxy; media3 does not read
// that one, which is why stream channels ignored the tunnel and failed on filtered networks.
//
// Android's HttpURLConnection consults ProxySelector.getDefault(), so installing a selector here
// covers every media3 request (playlist + each HLS segment) without touching ExoPlayer internals.
object TunnelSocketRoute {
    private const val TAG = "InuTunnelRoute"

    @Volatile private var installed = false
    @Volatile private var port = 0
    private var previous: ProxySelector? = null

    @JvmStatic
    @Synchronized
    fun install(httpPort: Int) {
        if (httpPort <= 0) return
        if (installed) {
            port = httpPort
            return
        }
        previous = try {
            ProxySelector.getDefault()
        } catch (_: Throwable) {
            null
        }
        port = httpPort
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    val p = socketProxy(port)
                    if (p != null) return mutableListOf(p)
                    // tunnel dropped between requests -> fall back to direct instead of hard-failing
                    return mutableListOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                    Log.w(TAG, "tunnel proxy refused ${uri?.host} (${ioe?.message})")
                }
            })
            installed = true
            Log.i(TAG, "media traffic routed through tunnel http://127.0.0.1:$httpPort")
        } catch (t: Throwable) {
            installed = false
            Log.e(TAG, "unable to install tunnel proxy selector", t)
        }
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        if (!installed) return
        installed = false
        port = 0
        try {
            ProxySelector.setDefault(previous)
        } catch (t: Throwable) {
            Log.w(TAG, "unable to restore proxy selector", t)
        }
        previous = null
        Log.i(TAG, "media traffic back to direct")
    }

    // Only hand out the tunnel when its port is actually listening; otherwise a stale tunnel
    // would break every HTTP request in the process.
    private fun socketProxy(httpPort: Int): Proxy? {
        if (httpPort <= 0) return null
        return if (BuiltInTunnelHelper.isPortOpen(httpPort)) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
        } else {
            null
        }
    }
}
