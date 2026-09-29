package desu.inugram.ui.helboy

import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import desu.inugram.helpers.helboy.HelboyWebViewProxy
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.LayoutHelper

// entiny: fullscreen in-app web player (hls.js + Plyr assets); routed through the tunnel HTTP port
class HelboyWebPlayerActivity(
    private val startUrl: String = "",
    private val titleText: CharSequence = "",
) : BaseFragment() {

    private var webView: WebView? = null

    override fun createView(context: Context): View {
        val container = FrameLayout(context)
        container.setBackgroundColor(Color.BLACK)
        actionBar.setTitle(titleText)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val web = createPlayerWebView(context, startUrl)
        webView = web
        container.addView(web, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        web.loadUrl(startUrl)
        return container
    }

    companion object {
        @JvmStatic
        fun createPlayerWebView(context: Context, startUrl: String): WebView {
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
            }
            return web
        }
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
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
    }
}
