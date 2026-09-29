package desu.inugram.ui.helboy

import android.content.Context
import android.graphics.Color
import android.net.http.SslError
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import desu.inugram.helpers.helboy.HelboyWebViewProxy
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.LayoutHelper

// entiny: fullscreen in-app web player (bundled hls.js + Plyr); used for YouTube channels and as
// the fallback when native playback keeps failing. Assets are loaded from android_asset so the page
// still starts on a filtered network — the previous version pulled hls.js/Plyr from jsdelivr and
// showed a blank black screen when those CDNs were blocked.
class HelboyWebPlayerActivity(
    private val startUrl: String = "",
    private val titleText: CharSequence = "",
) : BaseFragment() {

    private var webView: WebView? = null

    // entiny: the WebView proxy override is cleared whenever the tunnel drops, so re-assert it and
    // reload once the tunnel is back instead of leaving a dead player on screen.
    private val proxyObserver = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
        if (id != NotificationCenter.proxySettingsChanged) return@NotificationCenterDelegate
        val web = webView ?: return@NotificationCenterDelegate
        HelboyWebViewProxy.applyFromTunnel()
        AndroidUtilities.runOnUIThread { web.loadUrl(startUrl) }
    }

    override fun onFragmentCreate(): Boolean {
        NotificationCenter.getGlobalInstance().addObserver(proxyObserver, NotificationCenter.proxySettingsChanged)
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        val container = FrameLayout(context)
        container.setBackgroundColor(Color.BLACK)
        actionBar.setTitle(titleText)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        HelboyWebViewProxy.applyFromTunnel()
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = true
        web.settings.setAllowUniversalAccessFromFileURLs(true)
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.setBackgroundColor(Color.BLACK)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // entiny: keep sub-resource failures from killing playback started over the tunnel, and
            // surface a retry instead of a silent black frame.
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    AndroidUtilities.runOnUIThread({ view.loadUrl(startUrl) }, 1500)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 500) {
                    AndroidUtilities.runOnUIThread({ view.loadUrl(startUrl) }, 1500)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // entiny: tunneled/HLS hosts often present odd cert chains; proceed rather than fail hard
                handler.proceed()
            }
        }
        webView = web
        container.addView(web, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        web.loadUrl(startUrl)
        return container
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        NotificationCenter.getGlobalInstance().removeObserver(proxyObserver, NotificationCenter.proxySettingsChanged)
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
    }
}
