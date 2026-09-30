package desu.inugram.helpers.helboy

import android.net.Uri
import desu.inugram.ui.helboy.HelboyPlayerActivity
import org.telegram.ui.ActionBar.BaseFragment

// helboy: the ONE player — our own fullscreen ExoPlayer activity (HelboyPlayerActivity) with an
// always-visible YouTube-style control bar (quality, speed, subtitles, mute, seek, live badge).
// All traffic rides the built-in tunnel via TunnelSocketRoute, so channels open on filtered
// networks without any WebView/proxy-override dependency.
object HelboyPlayer {

    @JvmStatic
    fun play(fragment: BaseFragment, ch: HelboyChannel) {
        // helboy: direct HLS/progressive streams go to OUR player; YouTube-only channels keep
        // the embedded web path (a watch URL is not a playable stream)
        val url = ch.streamUrls.firstOrNull()
        if (url != null) {
            fragment.presentFragment(HelboyPlayerActivity(url, ch.name, ch.id))
            return
        }
        val youtubeId = ch.youtubeId ?: return
        val webPage = "file:///android_asset/helboy_player/index.html"
        fragment.presentFragment(
            desu.inugram.ui.helboy.HelboyWebPlayerActivity(
                "$webPage#y=$youtubeId&n=${Uri.encode(ch.name)}",
                ch.name,
                ch.id,
            )
        )
    }

    /** helboy: kept for the bridge/legacy call sites — same player. */
    @JvmStatic
    fun playNative(fragment: BaseFragment, ch: HelboyChannel) {
        play(fragment, ch)
    }
}
