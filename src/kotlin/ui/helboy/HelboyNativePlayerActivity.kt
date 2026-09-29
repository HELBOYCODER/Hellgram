package desu.inugram.ui.helboy

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import desu.inugram.helpers.helboy.HelboyWebViewProxy
import desu.inugram.helpers.network.BuiltInTunnelHelper
import desu.inugram.helpers.network.TunnelSocketRoute
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.VideoPlayer

// helboy: native HLS/progressive playback for stream channels.
//
// entiny: the previous version gave up after a single retry and then handed off to the web player,
// which is why stream channels looked like they "lost connection" and never came back. Now the
// player owns its own recovery: exponential-backoff retries, a fresh ExoPlayer on each attempt
// (ExoPlayer keeps its error state on the same instance, so retrying in place cannot recover),
// and an immediate replay the moment the tunnel reports it is up again.
class HelboyNativePlayerActivity(
    private val streamUrl: String = "",
    private val titleText: CharSequence = "",
    private val webFallbackUrl: String = "",
) : BaseFragment() {

    private var container: FrameLayout? = null
    private var textureView: TextureView? = null
    private var videoPlayer: VideoPlayer? = null
    private var progress: ProgressBar? = null
    private var statusText: TextView? = null
    private var fallbackWeb: WebView? = null
    private var fellBack = false
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var attempts = 0
    private var lastPosition = 0L
    private var retryPending = false

    // entiny: replay as soon as the tunnel finishes reconnecting, instead of waiting out the backoff
    private val proxyObserver = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
        if (id != NotificationCenter.proxySettingsChanged) return@NotificationCenterDelegate
        if (BuiltInTunnelHelper.isActive() && attempts > 0) retryNow()
    }

    override fun onFragmentCreate(): Boolean {
        val act = parentActivity ?: return super.onFragmentCreate()
        previousOrientation = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        NotificationCenter.getGlobalInstance().addObserver(proxyObserver, NotificationCenter.proxySettingsChanged)
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context)
        root.setBackgroundColor(Color.BLACK)
        container = root
        actionBar.setTitle(titleText)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val texture = TextureView(context)
        textureView = texture
        root.addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER))
        texture.setOnClickListener {
            val player = videoPlayer ?: return@setOnClickListener
            if (player.isPlaying()) player.pause() else player.play()
        }

        val overlay = LinearLayout(context)
        overlay.orientation = LinearLayout.VERTICAL
        overlay.gravity = Gravity.CENTER
        root.addView(overlay, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        progress = ProgressBar(context)
        overlay.addView(progress, LayoutHelper.createLinear(42, 42, Gravity.CENTER_HORIZONTAL))

        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        overlay.addView(statusText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0))

        buildPlayer(context)
        return root
    }

    private fun streamType(): String = if (streamUrl.contains(".m3u8")) "hls" else "other"

    private fun buildPlayer(context: Context) {
        val texture = textureView ?: return
        retryPending = false
        showStatus(if (attempts > 0) "Reconnecting… (attempt $attempts)" else null)
        progress?.visibility = View.VISIBLE

        val player = VideoPlayer()
        videoPlayer = player
        player.setTextureView(texture)
        player.setDelegate(object : VideoPlayer.VideoPlayerDelegate {
            override fun onStateChanged(playWhenReady: Boolean, playbackState: Int) {
                progress?.visibility =
                    if (playbackState == ExoPlayer.STATE_READY || playbackState == ExoPlayer.STATE_ENDED) View.GONE else View.VISIBLE
                if (playbackState == ExoPlayer.STATE_READY) {
                    attempts = 0
                    showStatus(null)
                }
                try {
                    val window = parentActivity?.window ?: return
                    if (playWhenReady && playbackState == ExoPlayer.STATE_READY) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                } catch (_: Exception) {
                }
            }

            override fun onError(player: VideoPlayer, e: Exception) {
                scheduleRetry("Connection dropped")
            }

            override fun onVideoSizeChanged(width: Int, height: Int, unappliedRotationDegrees: Int, pixelWidthHeightRatio: Float) {
                val w = if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) height else width
                val h = if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) width else height
                val root = container ?: return
                val viewW = root.width
                val viewH = root.height
                if (w <= 0 || h <= 0 || viewW <= 0 || viewH <= 0) return
                val aspect = (w * pixelWidthHeightRatio) / h
                val fitW = if (viewW.toFloat() / viewH > aspect) (viewH * aspect).toInt() else viewW
                val fitH = if (viewW.toFloat() / viewH > aspect) viewH else (viewW / aspect).toInt()
                val lp = textureView?.layoutParams as? FrameLayout.LayoutParams ?: return
                lp.width = fitW
                lp.height = fitH
                lp.gravity = Gravity.CENTER
                textureView?.layoutParams = lp
            }

            override fun onRenderedFirstFrame() {
                progress?.visibility = View.GONE
            }
        })
        player.preparePlayer(Uri.parse(streamUrl), streamType())
        player.setPlayWhenReady(true)
        if (lastPosition > 2000) player.seekTo(lastPosition)
    }

    // entiny: ExoPlayer retains its fatal error on the same instance, so recovering means building a
    // new VideoPlayer and reusing the TextureView under it.
    private fun scheduleRetry(reason: String) {
        if (fellBack || retryPending) return
        lastPosition = try {
            videoPlayer?.currentPosition ?: 0L
        } catch (_: Throwable) {
            0L
        }
        retryPending = true
        val delay = RETRY_DELAYS[attempts.coerceAtMost(RETRY_DELAYS.lastIndex)]
        attempts++
        showStatus("$reason — retrying in ${delay}s")
        progress?.visibility = View.VISIBLE
        AndroidUtilities.runOnUIThread({ if (!fellBack) retryNow() }, delay * 1000L)
    }

    private fun retryNow() {
        retryPending = false
        val context = container?.context ?: return
        releasePlayer()
        // tunnel may have come back (or gone away) since the last attempt — resync routing
        HelboyWebViewProxy.applyFromTunnel()
        if (BuiltInTunnelHelper.isActive()) {
            TunnelSocketRoute.install(BuiltInTunnelHelper.HTTP_PORT)
        }
        if (attempts >= MAX_NATIVE_ATTEMPTS) {
            switchToWebFallback()
            return
        }
        buildPlayer(context)
    }

    private fun showStatus(text: String?) {
        statusText?.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        statusText?.text = text ?: ""
    }

    private fun releasePlayer() {
        try {
            videoPlayer?.releasePlayer(true)
        } catch (_: Throwable) {
        }
        videoPlayer = null
    }

    private fun switchToWebFallback() {
        if (webFallbackUrl.isEmpty()) {
            finishFragment()
            return
        }
        fellBack = true
        AndroidUtilities.runOnUIThread {
            releasePlayer()
            textureView?.let { container?.removeView(it) }
            textureView = null
            val context = container?.context ?: return@runOnUIThread
            HelboyWebViewProxy.applyFromTunnel()
            val web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.mediaPlaybackRequiresUserGesture = false
            web.settings.allowFileAccess = true
            web.settings.setAllowUniversalAccessFromFileURLs(true)
            web.setBackgroundColor(Color.BLACK)
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            fallbackWeb = web
            container?.addView(web, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
            web.loadUrl(webFallbackUrl)
        }
    }

    override fun onPause() {
        if (fallbackWeb != null) fallbackWeb?.onPause() else videoPlayer?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        fallbackWeb?.onResume()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        NotificationCenter.getGlobalInstance().removeObserver(proxyObserver, NotificationCenter.proxySettingsChanged)
        val act = parentActivity
        if (act != null && previousOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            act.requestedOrientation = previousOrientation
        }
        try {
            parentActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {
        }
        releasePlayer()
        fallbackWeb?.apply {
            stopLoading()
            destroy()
        }
        fallbackWeb = null
        textureView = null
        container = null
    }

    companion object {
        private val RETRY_DELAYS = longArrayOf(2, 4, 8, 15, 30)
        private const val MAX_NATIVE_ATTEMPTS = 6
    }
}
