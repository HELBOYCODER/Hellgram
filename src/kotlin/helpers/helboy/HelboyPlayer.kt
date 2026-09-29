package desu.inugram.helpers.helboy

import android.net.Uri
import desu.inugram.ui.helboy.HelboyNativePlayerActivity
import desu.inugram.ui.helboy.HelboyWebPlayerActivity
import org.telegram.ui.ActionBar.BaseFragment

// entiny: stream channels use Telegram's native VideoPlayer (hls/mp4), with the
// bundled hls.js web page as automatic fallback; YouTube channels stay on the web player.
object HelboyPlayer {

    @JvmStatic
    fun play(fragment: BaseFragment, ch: HelboyChannel) {
        val webPage = "file:///android_asset/helboy_player/index.html"
        if (ch.hasStreams) {
            fragment.presentFragment(
                HelboyNativePlayerActivity(
                    ch.primaryUrl,
                    ch.name,
                    "$webPage#u=${Uri.encode(ch.primaryUrl)}&n=${Uri.encode(ch.name)}",
                ),
            )
            return
        }
        ch.youtubeId?.let {
            fragment.presentFragment(
                HelboyWebPlayerActivity("$webPage#y=$it&n=${Uri.encode(ch.name)}", ch.name),
            )
        }
    }
}
