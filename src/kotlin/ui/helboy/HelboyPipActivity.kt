package desu.inugram.ui.helboy

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import android.widget.ImageButton
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme

// helboy: dedicated picture-in-picture playback window. Launched from the native player's PiP
// button; immediately shrinks into a floating 16:9 window that floats above Telegram chat screens.
// Lives in its own task so the main Telegram task stays interactive underneath.
class HelboyPipActivity : Activity() {

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private var bar: LinearLayout? = null
    private var playBtn: ImageButton? = null
    private var closeBtn: ImageButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        playBtn = ImageButton(this).apply {
            setBackgroundColor(0x66000000)
            setImageResource(android.R.drawable.ic_media_pause)
            setOnClickListener { togglePlay() }
        }
        closeBtn = ImageButton(this).apply {
            setBackgroundColor(0x66000000)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setOnClickListener { finishAndRemoveTask() }
        }
        bar?.addView(playBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(44)))
        bar?.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(44)))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        bar?.visibility = View.GONE
        root.setOnClickListener { bar?.visibility = if (bar?.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

        setContentView(root)
        startPlayer(intent?.getStringExtra(EXTRA_URL) ?: return)
    }

    private fun startPlayer(url: String) {
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("User-Agent" to "Helboy/1.0"))))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 60000, 1500, 3000).build())
            .build()
        player = p
        p.setVideoTextureView(textureView)
        p.volume = 1f
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn?.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            }
        })
        val item = MediaItem.Builder().setUri(Uri.parse(url)).build()
        if (url.contains(".m3u8")) {
            val src = HlsMediaSource.Factory(DefaultHttpDataSource.Factory()).createMediaSource(item)
            p.setMediaSource(src)
        } else {
            p.setMediaItem(item)
        }
        p.prepare()
        p.playWhenReady = true
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    override fun onResume() {
        super.onResume()
        // drop straight into the floating window
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Throwable) {
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isFinishing && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Throwable) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        bar?.visibility = if (isInPictureInPictureMode) View.GONE else bar?.visibility ?: View.GONE
        if (!isInPictureInPictureMode && player != null && !isChangingConfigurations) {
            // user expanded back to fullscreen: keep going here
        }
    }

    override fun onPause() {
        if (!isInPictureInPictureMode) player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun launch(activity: android.content.Context, url: String, title: String) {
            val i = Intent(activity, HelboyPipActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            i.putExtra(EXTRA_TITLE, title)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        }
    }
}
