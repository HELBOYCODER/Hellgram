package desu.inugram.helpers.helboy

import android.net.Uri
import desu.inugram.ui.helboy.HelboyNativePlayerActivity
import desu.inugram.ui.helboy.HelboyWebPlayerActivity
import org.telegram.ui.ActionBar.BaseFragment

// entiny: the bundled Plyr web player (YouTube-style controls: quality, speed, captions, PiP-style
// fullscreen) is now the PRIMARY player for every channel — user request "بجای استفاده از پلیر پیش
// فرض از این پلیر استفاده کن". Telegram's native player stays as an automatic fallback when the
// web player reports it cannot play the stream.
object HelboyPlayer {

    @JvmStatic
    fun play(fragment: BaseFragment, ch: HelboyChannel) {
        val webPage = "file:///android_asset/helboy_player/index.html"
        // helboy: pass logo + favorite state so the YouTube-style header can render the avatar
        val fav = if (HelboyStore.isFavorite(ch.id)) "1" else "0"
        val logo = Uri.encode(ch.logo ?: "")
        val webTarget: String = when {
            ch.hasStreams -> "$webPage#u=${Uri.encode(ch.primaryUrl ?: return)}&n=${Uri.encode(ch.name)}&l=$logo&f=$fav"
            else -> ch.youtubeId?.let { "$webPage#y=$it&n=${Uri.encode(ch.name)}&l=$logo&f=$fav" } ?: return
        }
        fragment.presentFragment(HelboyWebPlayerActivity(webTarget, ch.name, ch.id))
    }

    /** entiny: manual escape hatch — opens the same stream in the native player. */
    @JvmStatic
    fun playNative(fragment: BaseFragment, ch: HelboyChannel) {
        if (!ch.hasStreams) return
        val url = ch.primaryUrl ?: return
        val webPage = "file:///android_asset/helboy_player/index.html"
        fragment.presentFragment(
            HelboyNativePlayerActivity(
                url,
                ch.name,
                "$webPage#u=${Uri.encode(url)}&n=${Uri.encode(ch.name)}",
            ),
        )
    }
}
