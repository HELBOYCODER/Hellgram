package desu.inugram.ui.helboy

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.ThemeDescription

// helboy: fullscreen video player with orientation support
class HelboyWebPlayerActivity(
    private val loadUrl: String,
    private val title: String,
) : BaseFragment() {

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var isFullscreen = false
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customView: View? = null
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        val act = parentActivity ?: return true
        // Remember and force landscape
        previousOrientation = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Keep screen on during playback
        act.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Allow display cutout for immersive video
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            act.window?.attributes?.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return true
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        // Restore previous orientation
        val act = parentActivity
        if (act != null && previousOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            act.requestedOrientation = previousOrientation
        }
        act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView?.destroy()
        webView = null
    }

    override fun createView(context: android.content.Context): View {
        actionBar?.setBackgroundColor(Color.parseColor("#FF1A1A2E"))
        actionBar?.setTitleColor(Color.WHITE)
        actionBar?.setTitle(title)
        actionBar?.setBackButtonImage(R.drawable.ic_ab_back)

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF4CAF50"))
            }
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(progressBar)

        @SuppressLint("SetJavaScriptEnabled")
        val wv = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                // Hardware acceleration for smooth video
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar?.visibility = View.GONE
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    view?.loadUrl("about:blank")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    container.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    actionBar?.visibility = View.GONE
                    progressBar?.visibility = View.GONE
                    hideSystemUI()
                    isFullscreen = true
                }

                override fun onHideCustomView() {
                    if (customView == null) return
                    container.removeView(customView)
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    actionBar?.visibility = View.VISIBLE
                    showSystemUI()
                    isFullscreen = false
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress in 1..99) {
                        progressBar?.visibility = View.VISIBLE
                    } else {
                        progressBar?.visibility = View.GONE
                    }
                }

                override fun onReceivedTitle(view: WebView?, t: String?) {
                    // Don't update title during playback
                }
            }
        }
        webView = wv
        container.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return container
    }

    private fun hideSystemUI() {
        val act = parentActivity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            act.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun showSystemUI() {
        parentActivity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        if (isFullscreen) {
            webView?.evaluateJavascript("document.webkitExitFullscreen()") {}
            webView?.evaluateJavascript(
                "var v=document.querySelector('video');if(v&&v.webkitExitFullscreen)v.webkitExitFullscreen();",
                {}
            )
            return true
        }
        return super.onBackPressed(invoked)
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> {
        return ArrayList()
    }
}
