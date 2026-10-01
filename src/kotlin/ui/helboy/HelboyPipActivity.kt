package desu.inugram.ui.helboy

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.Player
import android.app.RemoteAction
import android.graphics.drawable.Icon
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.LaunchActivity

// helboy: dedicated picture-in-picture playback window. Launched from the native player's PiP
// button; immediately shrinks into a floating 16:9 window that floats above Telegram chat screens.
// Lives in its own task so the main Telegram task stays interactive underneath.
//
// Fixes (test-21):
// - system PiP X (dismiss) now FULLY stops playback + releases audio (was leaving audio running)
// - expand/exit PiP returns to the main Helboy TV player via LaunchActivity.presentFragment
class HelboyPipActivity : Activity() {

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private var bar: LinearLayout? = null
    private var playBtn: ImageButton? = null
    private var closeBtn: ImageButton? = null
    private var expandedToPlayer = false
    private var lifecyclePaused = true
    private var streamUrl = ""
    private var titleText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamUrl = intent?.getStringExtra(EXTRA_URL) ?: ""
        titleText = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        if (Build.VERSION.SDK_INT >= 28) {
            setPictureInPictureParams(buildParams())
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
            setOnClickListener { closeEverything() }
        }
        bar?.addView(playBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(44f), AndroidUtilities.dp(44f)))
        bar?.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(44f), AndroidUtilities.dp(44f)))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        bar?.visibility = View.GONE
        root.setOnClickListener { bar?.visibility = if (bar?.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

        setContentView(root)
        if (streamUrl.isNotEmpty()) startPlayer(streamUrl)
    }

    private fun buildParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) {
            // expand action: returns to the full Helboy TV player
            val expandIntent = Intent(this, HelboyPipActivity::class.java).setAction(ACTION_EXPAND)
            val expand = RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_zoom),
                "Expand", "Expand",
                PendingIntent.getActivity(this, 1, expandIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            val closeIntent = Intent(this, HelboyPipActivity::class.java).setAction(ACTION_CLOSE)
            val close = RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Close", "Close",
                PendingIntent.getActivity(this, 2, closeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            b.setActions(listOf(expand, close))
        }
        return b.build()
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

    // hard-stop: release the player AND the audio session so nothing keeps playing
    private fun closeEverything() {
        stopAndReleasePlayer()
        expandedToPlayer = false
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) {
            // leaving pip explicitly so the window disappears before the task finishes
            try { moveTaskToBack(true) } catch (_: Throwable) {}
        }
        finishAndRemoveTask()
    }

    private fun stopAndReleasePlayer() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    private fun expandToMainPlayer() {
        if (expandedToPlayer) return
        expandedToPlayer = true
        val url = streamUrl
        val title = titleText
        // hand playback back to the main Helboy TV player inside the Telegram task
        AndroidUtilities.runOnUIThread {
            try {
                val act = LaunchActivity.instance
                if (act != null) {
                    act.presentFragment(HelboyPlayerActivity(url, title, ""), false, true)
                } else {
                    val i = packageManager.getLaunchIntentForPackage(packageName)
                    if (i != null) startActivity(i)
                }
            } catch (_: Throwable) {
            }
        }
        stopAndReleasePlayer()
        finishAndRemoveTask()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent?.action) {
            ACTION_EXPAND -> expandToMainPlayer()
            ACTION_CLOSE -> closeEverything()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecyclePaused = false
        // drop straight into the floating window
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && !expandedToPlayer) {
            try {
                enterPictureInPictureMode(buildParams())
            } catch (_: Throwable) {
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isFinishing && !isInPictureInPictureMode && !expandedToPlayer) {
            try {
                enterPictureInPictureMode(buildParams())
            } catch (_: Throwable) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        bar?.visibility = if (isInPictureInPictureMode) View.GONE else bar?.visibility ?: View.GONE
        if (!isInPictureInPictureMode && !isChangingConfigurations && !expandedToPlayer && !isFinishing) {
            // helboy: if pip exited while we are NOT the resumed/visible activity, the window
            // was DISMISSED (system X) → kill TV completely. Only when the window expands
            // in-place (still visible, resumed) do we return to the main player.
            if (lifecyclePaused) {
                closeEverything()
            } else {
                expandToMainPlayer()
            }
        }
    }

    override fun onStop() {
        // if the pip window was dismissed by the system X button while backgrounded,
        // the activity stops WITHOUT a destroy callback in some paths — make sure audio dies
        if (!isChangingConfigurations && !expandedToPlayer && !isFinishing) {
            val p = player
            // keep playing only if still actually in pip; if we were dismissed, window is gone
            if (p != null && Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode) {
                stopAndReleasePlayer()
                finishAndRemoveTask()
                return
            }
        }
        super.onStop()
    }

    override fun onPause() {
        lifecyclePaused = true
        if (!isInPictureInPictureMode && !expandedToPlayer) player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        stopAndReleasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val ACTION_EXPAND = "desu.inugram.helboy.PIP_EXPAND"
        const val ACTION_CLOSE = "desu.inugram.helboy.PIP_CLOSE"

        fun launch(activity: android.content.Context, url: String, title: String) {
            val i = Intent(activity, HelboyPipActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            i.putExtra(EXTRA_TITLE, title)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        }
    }
}
