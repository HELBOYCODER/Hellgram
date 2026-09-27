package desu.inugram.ui.helboy

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.VideoPlayer

// entiny: fullscreen channel host on Telegram's own media3 player (same VideoPlayer
// that powers WebPlayerView streams); url is an HLS/direct stream
class HelboyVideoActivity(
    private val streamUrl: String = "",
    private val titleText: CharSequence = "",
) : BaseFragment() {

    private var player: VideoPlayer? = null
    private var surfaceView: SurfaceView? = null

    override fun createView(context: Context): View {
        val container = FrameLayout(context)
        container.setBackgroundColor(Color.BLACK)
        actionBar.setTitle(titleText)
        actionBar.setActionBarMenuOnItemClick(object : org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val surface = SurfaceView(context)
        surfaceView = surface
        container.addView(surface, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        val tap = View(context)
        tap.isClickable = true
        tap.setOnClickListener {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
        }
        container.addView(tap, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        AndroidUtilities.runOnUIThread {
            if (streamUrl.isEmpty()) return@runOnUIThread
            val p = VideoPlayer(true, false)
            player = p
            p.setSurfaceView(surface)
            p.preparePlayer(Uri.parse(streamUrl), if (streamUrl.contains(".m3u8")) "hls" else "other")
            p.setPlayWhenReady(true)
        }
        return container
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        player?.apply {
            try { releasePlayer(false) } catch (_: Throwable) {}
        }
        player = null
    }
}
