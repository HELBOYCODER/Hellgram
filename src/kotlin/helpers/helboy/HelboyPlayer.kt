package desu.inugram.helpers.helboy

import android.net.Uri
import desu.inugram.ui.helboy.HelboyWebPlayerActivity
import org.telegram.ui.ActionBar.BaseFragment

// entiny: plays a Helboy channel in the bundled in-app web player (hls.js + Plyr),
// routed through the built-in tunnel's HTTP port by HelboyWebViewProxy.
object HelboyPlayer {

    @JvmStatic
    fun play(fragment: BaseFragment, ch: HelboyChannel) {
        val url = when {
            ch.hasStreams -> "file:///android_asset/helboy_player/index.html#u=" + Uri.encode(ch.primaryUrl)
            ch.youtubeId != null -> "file:///android_asset/helboy_player/index.html#y=" + ch.youtubeId
            else -> return
        }
        fragment.presentFragment(
            HelboyWebPlayerActivity("$url&n=${Uri.encode(ch.name)}", ch.name),
        )
    }
}
