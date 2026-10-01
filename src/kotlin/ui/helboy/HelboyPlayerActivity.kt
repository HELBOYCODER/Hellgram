package desu.inugram.ui.helboy

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import desu.inugram.helpers.network.BuiltInTunnelHelper
import desu.inugram.helpers.network.TunnelSocketRoute
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.LayoutHelper
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl

// helboy: own fullscreen player built directly on media3 ExoPlayer — no WebView, no Plyr.
// Always-visible YouTube-style control bar: play/pause, seek bar with buffer %, quality menu
// (HLS levels), speed menu, volume slider, subtitle (CC) toggle, mute, fullscreen aspect toggle,
// and live "connection quality" readout. All media traffic goes through the built-in tunnel via
// TunnelSocketRoute (media3 honours java.net.ProxySelector) so channels open on filtered networks.
class HelboyPlayerActivity(
    private val streamUrl: String = "",
    private val titleText: CharSequence = "",
    private val channelId: String = "",
) : BaseFragment() {

    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private var controlsBar: LinearLayout? = null
    private var topBar: LinearLayout? = null
    private var playBtn: ImageButton? = null
    private var muteBtn: ImageButton? = null
    private var pipBtn: ImageButton? = null
    private var ccBtn: ImageButton? = null
    private var qualityBtn: TextView? = null
    private var speedBtn: TextView? = null
    private var seekBar: HelboySeekBar? = null
    private var timeText: TextView? = null
    private var statusText: TextView? = null
    private var qualityBadge: TextView? = null
    private var liveBadge: TextView? = null
    private var progress: ProgressBar? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var currentLevel = -1 // -1 = auto
    private var muted = false
    private var subtitlesOn = false
    private var speedIndex = 2 // 1.0x
    private var retryAttempts = 0
    private var retryPending = false
    private var lastErrorText: String? = null
    private var hideControlsRunnable: Runnable? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            AndroidUtilities.runOnUIThread(this, 500)
        }
    }

    private val proxyObserver = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
        if (id != NotificationCenter.proxySettingsChanged) return@NotificationCenterDelegate
        if (BuiltInTunnelHelper.isActive() && retryAttempts > 0) {
            retryPending = false
            buildPlayer()
        }
    }

    override fun onFragmentCreate(): Boolean {
        val act = parentActivity ?: return super.onFragmentCreate()
        previousOrientation = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        NotificationCenter.getGlobalInstance().addObserver(proxyObserver, NotificationCenter.proxySettingsChanged)
        return super.onFragmentCreate()
    }

    override fun createView(context: android.content.Context): View {
        val r = FrameLayout(context)
        r.setBackgroundColor(Color.BLACK)
        root = r
        actionBar.setTitle(titleText)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val texture = TextureView(context)
        textureView = texture
        r.addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.MATCH_PARENT.toFloat(), Gravity.CENTER))

        // live badge + connection readout
        liveBadge = TextView(context).apply {
            text = "LIVE"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(2f), AndroidUtilities.dp(8f), AndroidUtilities.dp(2f))
            background = org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(4f), 0xFFCC0000.toInt())
            visibility = View.GONE
        }
        r.addView(liveBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.END, 0f, 8f, 12f, 0f))

        qualityBadge = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(2f), AndroidUtilities.dp(8f), AndroidUtilities.dp(2f))
            background = org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(4f), 0x99000000.toInt())
            visibility = View.GONE
        }
        r.addView(qualityBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.START, 12f, 8f, 0f, 0f))

        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        r.addView(statusText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER))

        progress = ProgressBar(context)
        r.addView(progress, LayoutHelper.createFrame(44, 44, Gravity.CENTER))

        // ----- control bar (always visible) -----
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(6f), AndroidUtilities.dp(10f), AndroidUtilities.dp(6f))
        bar.background = org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(14f), 0xB3000000.toInt())
        controlsBar = bar
        r.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM, 10f, 0f, 10f, 10f))

        val iconBtn: (Int, String) -> ImageButton = { res, desc ->
            ImageButton(context).apply {
                setImageResource(res)
                contentDescription = desc
                background = null
                setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }

        playBtn = iconBtn(R.drawable.ic_action_pause, "play/pause")
        playBtn!!.setOnClickListener { togglePlayPause() }
        bar.addView(playBtn, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL))

        muteBtn = iconBtn(R.drawable.phosphor_speaker_high, "mute")
        muteBtn!!.setOnClickListener { toggleMute() }
        bar.addView(muteBtn, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL))

        pipBtn = iconBtn(R.drawable.inu_pip, "picture in picture")
        pipBtn!!.setOnClickListener { enterPip() }
        bar.addView(pipBtn, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL))

        seekBar = HelboySeekBar(context)
        seekBar!!.onSeek = { frac -> player?.seekTo((player!!.duration * frac).toLong()) }
        bar.addView(seekBar, LayoutHelper.createLinear(0, 26, 1f))

        timeText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        bar.addView(timeText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 4, 0))

        ccBtn = iconBtn(R.drawable.outline_caption_24, LocaleController.getString(R.string.InuHelboySubtitles))
        ccBtn!!.setOnClickListener { toggleSubtitles() }
        ccBtn!!.alpha = 0.35f
        bar.addView(ccBtn, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL))

        speedBtn = TextView(context).apply {
            text = "1x"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setOnClickListener { showSpeedMenu(it) }
        }
        bar.addView(speedBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 4, 0, 4, 0))

        qualityBtn = TextView(context).apply {
            text = LocaleController.getString(R.string.InuHelboyQualityAuto)
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setOnClickListener { showQualityMenu(it) }
        }
        bar.addView(qualityBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL))

        buildPlayer()
        AndroidUtilities.runOnUIThread(updateRunnable, 500)
        return r
    }

    private fun buildPlayer() {
        val context = root?.context ?: return
        retryPending = false
        showStatus(lastErrorText)
        progress?.visibility = View.VISIBLE

        TunnelSocketRoute.install(BuiltInTunnelHelper.HTTP_PORT)

        val cookieManager = CookieManager()
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(cookieManager)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android) HelboyTV/2.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 5_000)
            .build()

        trackSelector = DefaultTrackSelector(context)

        val p = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()

        val isHls = streamUrl.contains(".m3u8")
        val item = MediaItem.Builder().setUri(Uri.parse(streamUrl)).build()
        if (isHls) {
            val source = HlsMediaSource.Factory(httpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
            p.setMediaSource(source)
        } else {
            p.setMediaSource(DefaultMediaSourceFactory(httpFactory).createMediaSource(item))
        }

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                progress?.visibility = if (state == Player.STATE_READY) View.GONE else View.VISIBLE
                if (state == Player.STATE_READY) {
                    retryAttempts = 0
                    lastErrorText = null
                    showStatus(null)
                    liveBadge?.visibility = View.VISIBLE
                } else if (state == Player.STATE_BUFFERING) {
                    progress?.visibility = View.VISIBLE
                }
                updatePlayIcon()
            }

            override fun onPlayerError(error: PlaybackException) {
                lastErrorText = error.errorCodeName
                scheduleRetry()
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateQualityLabel()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                fitTexture(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }
        })

        p.setVideoTextureView(textureView!!)
        p.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(), true)
        p.volume = if (muted) 0f else 1f
        p.playWhenReady = true
        p.prepare()
        player = p
        updateQualityLabel()
    }

    private fun fitTexture(w: Int, h: Int, par: Float) {
        val r = root ?: return
        val viewW = r.width
        val viewH = r.height
        if (w <= 0 || h <= 0 || viewW <= 0 || viewH <= 0) return
        val aspect = (w * par) / h
        val fitW = if (viewW.toFloat() / viewH > aspect) (viewH * aspect).toInt() else viewW
        val fitH = if (viewW.toFloat() / viewH > aspect) viewH else (viewW / aspect).toInt()
        val lp = textureView?.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = fitW
        lp.height = fitH
        lp.gravity = Gravity.CENTER
        textureView?.layoutParams = lp
    }

    private fun togglePlayPause() {
        val p = player ?: return
        performHaptic()
        if (p.isPlaying) p.pause() else p.play()
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val playing = player?.isPlaying == true
        playBtn?.setImageResource(if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play)
    }

    private fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        muteBtn?.alpha = if (muted) 0.45f else 1f
        performHaptic()
    }

    private fun toggleSubtitles() {
        val ts = trackSelector ?: return
        subtitlesOn = !subtitlesOn
        ccBtn?.alpha = if (subtitlesOn) 1f else 0.35f
        performHaptic()
        val params = ts.buildUponParameters()
        if (subtitlesOn) params.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
        else params.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
        ts.setParameters(params)
    }

    private fun showSpeedMenu(anchor: View) {
        performHaptic()
        val popup = PopupMenu(getContext(), anchor)
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        speeds.forEachIndexed { i, s ->
            popup.menu.add(0, i, i, if (s == 1f) LocaleController.getString(R.string.InuHelboySpeed) + ": 1x" else "${s}x")
        }
        popup.setOnMenuItemClickListener { mi ->
            speedIndex = mi.itemId
            player?.setPlaybackSpeed(speeds[speedIndex])
            speedBtn?.text = if (speeds[speedIndex] == 1f) "1x" else "${speeds[speedIndex]}x"
            true
        }
        popup.show()
    }

    private fun showQualityMenu(anchor: View) {
        performHaptic()
        val p = player ?: return
        val tracks = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        val popup = PopupMenu(getContext(), anchor)
        popup.menu.add(0, -1, 0, LocaleController.getString(R.string.InuHelboyQuality) + ": " + LocaleController.getString(R.string.InuHelboyQualityAuto))
        var idx = 0
        val heights = ArrayList<Pair<Int, Int>>() // level index, height
        tracks.forEach { group ->
            for (fi in 0 until group.mediaTrackGroup.length) {
                val format = group.getTrackFormat(fi)
                if (format.height > 0) heights.add(Pair(idx, format.height))
                idx++
            }
        }
        heights.sortedByDescending { it.second }.forEachIndexed { mi, (lvl, h) ->
            popup.menu.add(0, lvl, mi + 1, "${h}p")
        }
        popup.setOnMenuItemClickListener { menuItem ->
            currentLevel = menuItem.itemId
            applyQuality()
            true
        }
        popup.show()
    }

    private fun applyQuality() {
        val ts = trackSelector ?: return
        val params = ts.buildUponParameters()
        if (currentLevel == -1) {
            params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            val p = player ?: return
            // helboy: map the chosen level index to its real height so quality actually changes
            var height = -1
            var idx = 0
            val groups = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            groups.forEach { g ->
                for (fi in 0 until g.mediaTrackGroup.length) {
                    if (idx == currentLevel) height = g.getTrackFormat(fi).height
                    idx++
                }
            }
            if (height > 0) params.setMaxVideoSize(Int.MAX_VALUE, height)
            else params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        ts.setParameters(params)
        updateQualityLabel()
    }

    private fun updateQualityLabel() {
        val p = player ?: return
        val tracks = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        var best = 0
        tracks.forEach { g ->
            for (fi in 0 until g.mediaTrackGroup.length) {
                val f = g.getTrackFormat(fi)
                if (f.height > best) best = f.height
            }
        }
        qualityBtn?.text = if (currentLevel == -1) {
            LocaleController.getString(R.string.InuHelboyQualityAuto)
        } else {
            "${best}p"
        }
        if (best > 0) {
            qualityBadge?.text = (if (currentLevel == -1) "AUTO " else "") + "${best}p"
            qualityBadge?.visibility = View.VISIBLE
            AndroidUtilities.runOnUIThread({ qualityBadge?.visibility = View.GONE }, 3000)
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        val buf = p.bufferedPercentage
        if (dur > 0) {
            seekBar?.setFraction((pos.toDouble() / dur).coerceIn(0.0, 1.0), buf / 100f)
            timeText?.text = formatTime(pos) + " / " + formatTime(dur)
        } else {
            // live: show buffer health instead of position
            seekBar?.setFraction(1.0, buf / 100f)
            timeText?.text = "LIVE"
        }
        updatePlayIcon()
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun showStatus(text: String?) {
        statusText?.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        statusText?.text = text ?: ""
    }

    private fun scheduleRetry() {
        if (retryPending) return
        retryPending = true
        retryAttempts++
        val delay = longArrayOf(2, 4, 8, 15, 30, 30)[(retryAttempts - 1).coerceAtMost(5)]
        showStatus("Reconnecting… ($retryAttempts) in ${delay}s")
        AndroidUtilities.runOnUIThread({
            if (retryPending) {
                releasePlayer()
                buildPlayer()
            }
        }, delay * 1000)
    }

    private fun performHaptic() {
        root?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun releasePlayer() {
        AndroidUtilities.cancelRunOnUIThread(updateRunnable)
        try { player?.setVideoTextureView(null) } catch (_: Throwable) {}
        val p = player
        player = null
        if (p != null) Thread {
            try { p.stop() } catch (_: Throwable) {}
            try { p.release() } catch (_: Throwable) {}
        }.start()
    }

    private fun enterPip() {
        performHaptic()
        try {
            desu.inugram.ui.helboy.HelboyPipActivity.launch(parentActivity ?: return, streamUrl, titleText?.toString() ?: "")
            // hand playback over to the floating window
            try { player?.stop(); player?.release() } catch (_: Throwable) {}
            player = null
            finishFragment()
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        AndroidUtilities.cancelRunOnUIThread(updateRunnable)
        player?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        AndroidUtilities.runOnUIThread(updateRunnable, 500)
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
        // entiny: detach the surface before releasing — TextureView.release() on the main
        // thread during fragment teardown blocks on the EGL mutex (measured ANR) and janks
        // the next screen to white on slower GPUs.
        try { player?.setVideoTextureView(null) } catch (_: Throwable) {}
        val p = player
        player = null
        textureView = null
        trackSelector = null
        root = null
        if (p != null) {
            AndroidUtilities.runOnUIThread({}) // ensure ordering with pending UI runnables
            Thread {
                try { p.stop() } catch (_: Throwable) {}
                try { p.release() } catch (_: Throwable) {}
            }.start()
        }
    }
}
