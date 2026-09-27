package desu.inugram.helpers.helboy

import desu.inugram.helpers.WebAppHelper
import desu.inugram.ui.helboy.HelboyVideoActivity
import org.telegram.ui.ActionBar.BaseFragment

// entiny: plays a Helboy channel in Telegram's own media3 player; YouTube-only
// channels go through the internal WebView routed over the tunnel's HTTP proxy.
object HelboyPlayer {

    @JvmStatic
    fun play(fragment: BaseFragment, ch: HelboyChannel) {
        val url = ch.primaryUrl ?: return
        if (ch.isYoutubeOnly) {
            WebAppHelper.openHelboyWebApp(fragment, url)
            return
        }
        fragment.presentFragment(HelboyVideoActivity(url, ch.name))
    }
}
