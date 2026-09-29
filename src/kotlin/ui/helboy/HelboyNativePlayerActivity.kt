package desu.inugram.ui.helboy

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.media3.exoplayer.ExoPlayer
import desu.inugram.helpers.helboy.HelboyWebViewProxy
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.VideoPlayer

class HelboyNativePlayerActivity(
    private val streamUrl: String = "",
    private val titleText: CharSequence = "",
    private val webFallbackUrl: String = "",
) : BaseFragment() {

    private var container: FrameLayout? = null
    private var textureView: TextureView? = null
    private var videoPlayer: VideoPlayer? = null
    private var progress: ProgressBar? = null
    private var fallbackWeb: WebView? = null
    private var errorCount = 0
    private var fellBack = false
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onFragmentCreate(): Boolean {
        val act = parentActivity ?: return super.onFragmentCreate()
        previousOrientation = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
        root.addView(texture, LayoutHelper.createFrame(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        texture.setOnClickListener {
            val player = videoPlayer ?: return@setOnClickListener
            if (player.isPlaying()) player.pause() else player.play()
        }

        progress = ProgressBar(context)
        root.addView(progress, LayoutHelper.createFrame(42f, 42f, Gravity.CENTER))

        val type = if (streamUrl.contains(".m3u8")) "hls" else "other"
        val player = VideoPlayer()
        videoPlayer = player
        player.setTextureView(texture)
        player.setDelegate(object : VideoPlayer.VideoPlayerDelegate {
            override fun onStateChanged(playWhenReady: Boolean, playbackState: Int) {
                progress?.visibility = if (playbackState == ExoPlayer.STATE_READY || playbackState == ExoPlayer.STATE_ENDED) View.GONE else View.VISIBLE
                val window = parentActivity?.window ?: return
                try {
                    if (playWhenReady && playbackState == ExoPlayer.STATE_READY) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                } catch (_: Exception) {
                }
            }

            override fun onError(player: VideoPlayer, e: Exception) {
                if (!fellBack && ++errorCount == 1) {
                    AndroidUtilities.runOnUIThread({
                        if (fellBack) return@runOnUIThread
                        videoPlayer?.preparePlayer(Uri.parse(streamUrl), type)
                        videoPlayer?.setPlayWhenReady(true)
                    }, 400)
                } else {
                    switchToWebFallback()
                }
            }

            override fun onVideoSizeChanged(width: Int, height: Int, unappliedRotationDegrees: Int, pixelWidthHeightRatio: Float) {
                val w = if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) height else width
                val h = if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) width else height
                val root2 = container ?: return
                val viewW = root2.width
                val viewH = root2.height
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
        player.preparePlayer(Uri.parse(streamUrl), type)
        player.setPlayWhenReady(true)
        return root
    }

    private fun switchToWebFallback() {
        if (webFallbackUrl.isEmpty()) {
            finishFragment()
            return
        }
        fellBack = true
        AndroidUtilities.runOnUIThread {
            try {
                videoPlayer?.releasePlayer(true)
            } catch (_: Throwable) {
            }
            videoPlayer = null
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
        val act = parentActivity
        if (act != null && previousOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            act.requestedOrientation = previousOrientation
        }
        try {
            parentActivity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {
        }
        videoPlayer?.releasePlayer(true)
        videoPlayer = null
        fallbackWeb?.apply {
            stopLoading()
            destroy()
        }
        fallbackWeb = null
        textureView = null
        container = null
    }
}
