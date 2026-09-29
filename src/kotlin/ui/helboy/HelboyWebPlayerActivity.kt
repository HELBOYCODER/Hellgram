package desu.inugram.ui.helboy

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
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
import org.telegram.ui.ActionBar.Theme

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

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        // Force landscape for video viewing
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Keep screen on during playback
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Hide system bars for immersive experience
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity?.window?.attributes?.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return true
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        // Restore portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView?.destroy()
        webView = null
    }

    override fun createView(context: android.content.Context): View {
        actionBar?.setBackgroundColor(Color.parseColor("#FF1A1A2E"))
        actionBar?.setTitleColor(Color.WHITE)
        actionBar?.setTitle(title)
        actionBar?.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar?.castMenuActionBar()?.let {
            it.background = ColorDrawable(Color.parseColor("#FF1A1A2E"))
        }

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            setIndicatorColor(Color.parseColor("#FF4CAF50"))
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
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
                    // Hide action bar and progress
                    actionBar?.visibility = View.GONE
                    progressBar?.visibility = View.GONE
                    // Hide system UI
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
        activity?.window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun showSystemUI() {
        activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
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
