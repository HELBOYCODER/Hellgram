package desu.inugram.helpers.helboy

import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

// entiny: bundled Helboy TV channel database loader (asset helboy_data.bin, gzip JSON),
// ported from Suni TV (MIT)
object HelboyStore {
    private const val TAG = "InuHelboy"
    private var root: JSONObject? = null

    @Synchronized
    private fun ensureLoaded(): JSONObject? {
        root?.let { return it }
        val start = System.currentTimeMillis()
        try {
            ApplicationLoader.applicationContext.assets.open("helboy_data.bin").use { raw ->
                BufferedReader(InputStreamReader(GZIPInputStream(raw), Charsets.UTF_8)).use { reader ->
                    val sb = StringBuilder(1 shl 22)
                    val buf = CharArray(32 * 1024)
                    var n: Int
                    while (reader.read(buf).also { n = it } > 0) sb.append(buf, 0, n)
                    root = JSONObject(sb.toString())
                }
            }
            Log.i(TAG, "helboy data loaded in ${System.currentTimeMillis() - start} ms")
        } catch (e: Throwable) {
            Log.e(TAG, "failed loading helboy data", e)
        }
        return root
    }

    // entiny: parse the 5 MB db off the UI thread once at startup to avoid first-open jank
    @JvmStatic
    fun warmUp() {
        if (!desu.inugram.InuConfig.HELBOY_TV.value || root != null) return
        Thread { ensureLoaded() }.start()
    }

    fun ready(): Boolean = ensureLoaded() != null

    fun counts(kind: HelboyKind): Int = ensureLoaded()
        ?.optJSONObject(kind.slug)
        ?.optJSONObject("by_category")
        ?.optJSONArray("all")
        ?.length() ?: 0

    fun countries(kind: HelboyKind): List<Pair<String, Int>> {
        val r = ensureLoaded() ?: return emptyList()
        val meta = r.optJSONObject(kind.slug)?.optJSONObject("meta") ?: return emptyList()
        val out = ArrayList<Pair<String, Int>>()
        val keys = meta.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            val o = meta.optJSONObject(code) ?: continue
            if (!o.optBoolean("hasChannels", false)) continue
            out.add(code.uppercase() to o.optInt("channelCount", 0))
        }
        return out.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    fun channelsByCountry(kind: HelboyKind, code: String): List<HelboyChannel> {
        val r = ensureLoaded() ?: return emptyList()
        val byCountry = r.optJSONObject(kind.slug)?.optJSONObject("by_country") ?: return emptyList()
        val arr = byCountry.optJSONArray(code.lowercase())
            ?: byCountry.optJSONArray(code.uppercase())
            ?: return emptyList()
        val base = ArrayList<HelboyChannel>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { base.add(HelboyChannel.fromJson(it)) }
        }
        if (code.equals("IR", true) && kind != HelboyKind.WEBCAM) {
            val seen = base.mapNotNull { it.id }.toMutableSet()
            val allArr = r.optJSONObject(kind.slug)?.optJSONObject("by_category")?.optJSONArray("all")
            if (allArr != null) {
                for (i in 0 until allArr.length()) {
                    val obj = allArr.optJSONObject(i) ?: continue
                    val langs = obj.optJSONArray("languages") ?: continue
                    var fas = false
                    for (j in 0 until langs.length()) {
                        if (langs.optString(j).equals("fas", true)) { fas = true; break }
                    }
                    if (!fas) continue
                    val ch = HelboyChannel.fromJson(obj)
                    if (ch.id.isNotEmpty() && seen.add(ch.id)) base.add(ch)
                }
            }
        }
        base.sortBy { it.name.lowercase() }
        return base
    }

    fun search(kind: HelboyKind, query: String, limit: Int = 200): List<HelboyChannel> {
        if (query.isBlank()) return emptyList()
        val r = ensureLoaded() ?: return emptyList()
        val q = query.trim().lowercase()
        val allArr = r.optJSONObject(kind.slug)?.optJSONObject("by_category")?.optJSONArray("all")
            ?: return emptyList()
        val out = ArrayList<HelboyChannel>(64)
        for (i in 0 until allArr.length()) {
            val obj = allArr.optJSONObject(i) ?: continue
            if (obj.optString("name", "").lowercase().contains(q)) {
                out.add(HelboyChannel.fromJson(obj))
                if (out.size >= limit) break
            }
        }
        return out
    }

    private var favorites: HashSet<String>? = null

    fun favoriteIds(): MutableSet<String> {
        favorites?.let { return it }
        val set = HashSet(InuConfig.HELBOY_FAVORITES.value.split(",").filter { it.isNotBlank() })
        favorites = set
        return set
    }

    fun isFavorite(id: String): Boolean = favoriteIds().contains(id)

    fun toggleFavorite(ch: HelboyChannel): Boolean {
        val set = favoriteIds()
        val added = if (set.remove(ch.id)) false else set.add(ch.id)
        InuConfig.HELBOY_FAVORITES.value = set.joinToString(",")
        return added
    }

    fun favoriteChannels(): List<HelboyChannel> {
        val r = ensureLoaded() ?: return emptyList()
        val ids = favoriteIds()
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<HelboyChannel>(ids.size)
        for (slug in arrayOf("tv", "radio", "webcams")) {
            val arr = r.optJSONObject(slug)?.optJSONObject("by_category")?.optJSONArray("all") ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ch = HelboyChannel.fromJson(obj)
                if (ch.id in ids) out.add(ch)
            }
        }
        return out
    }
}
