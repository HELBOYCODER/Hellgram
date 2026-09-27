package desu.inugram.helpers.helboy

import org.json.JSONArray
import org.json.JSONObject

// entiny: Helboy TV channel model; data pipeline ported from Suni TV (MIT), channels from iptv sources
enum class HelboyKind(val slug: String) {
    TV("tv"),
    RADIO("radio"),
    WEBCAM("webcams");

    companion object {
        fun fromSlug(slug: String): HelboyKind = entries.firstOrNull { it.slug == slug } ?: TV
    }
}

data class HelboyChannel(
    val id: String,
    val name: String,
    val country: String,
    val logo: String?,
    val streamUrls: List<String>,
    val youtubeId: String?,
) {
    val hasStreams: Boolean get() = streamUrls.isNotEmpty()
    val isYoutubeOnly: Boolean get() = streamUrls.isEmpty() && youtubeId != null
    val primaryUrl: String?
        get() = when {
            streamUrls.isNotEmpty() -> streamUrls.first()
            youtubeId != null -> "https://www.youtube.com/watch?v=$youtubeId"
            else -> null
        }

    companion object {
        fun fromJson(o: JSONObject): HelboyChannel {
            val sources = o.optJSONObject("sources")
            val streamsJson = sources?.optJSONArray("streams") ?: JSONArray()
            val streams = (0 until streamsJson.length()).mapNotNull { streamsJson.optString(it, null) }
            val ytJson = sources?.optJSONArray("youtube")
            var ytId: String? = null
            if (ytJson != null && ytJson.length() > 0) {
                ytId = extractYoutubeId(ytJson.optString(0))
            }
            return HelboyChannel(
                id = o.optString("nanoid"),
                name = o.optString("name"),
                country = o.optString("country"),
                logo = o.optString("logo", null),
                streamUrls = streams,
                youtubeId = ytId,
            )
        }

        private fun extractYoutubeId(url: String): String? {
            val patterns = listOf(
                Regex("""embed/([A-Za-z0-9_-]{6,})"""),
                Regex("""youtu\.be/([A-Za-z0-9_-]{6,})"""),
                Regex("""[?&]v=([A-Za-z0-9_-]{6,})"""),
            )
            for (p in patterns) {
                p.find(url)?.let { return it.groupValues[1] }
            }
            return null
        }
    }
}

fun helboyFlag(code: String): String {
    if (code.length != 2) return ""
    val c1 = code[0].uppercaseChar().code - 'A'.code + 0x1F1E6
    val c2 = code[1].uppercaseChar().code - 'A'.code + 0x1F1E6
    return String(Character.toChars(c1)) + String(Character.toChars(c2))
}
