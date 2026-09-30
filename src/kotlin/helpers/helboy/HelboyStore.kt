package desu.inugram.helpers.helboy

import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

// helboy: bundled Helboy TV channel database loader (asset helboy_data.bin, gzip JSON)
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

    // entiny: merge a bundled refresh playlist (iptv-org Persian/Iran m3u, curated) into the
    // in-memory view. Matching is by normalized name so we can also upgrade an existing channel
    // with a logo instead of duplicating it. IDs get an "rf-" prefix to avoid nanoid collisions.
    @Synchronized
    fun mergeExternal(refresh: JSONObject) {
        val r = ensureLoaded() ?: return
        val allArr = r.optJSONObject("tv")?.optJSONObject("by_category")?.optJSONArray("all") ?: return
        val byCountry = r.optJSONObject("tv")?.optJSONObject("by_country")
        val meta = r.optJSONObject("tv")?.optJSONObject("meta")
        val existing = HashMap<String, JSONObject>()
        for (i in 0 until allArr.length()) {
            val o = allArr.optJSONObject(i) ?: continue
            existing[normalizeName(o.optString("name"))] = o
        }
        val channels = refresh.optJSONArray("channels") ?: return
        for (i in 0 until channels.length()) {
            val e = channels.optJSONObject(i) ?: continue
            val name = e.optString("name")
            if (name.isBlank()) continue
            val url = e.optString("url")
            if (url.isBlank()) continue
            val key = normalizeName(name)
            val cur = existing[key]
            if (cur != null) {
                // upgrade: logo + extra stream source on the existing channel
                val sources = cur.optJSONObject("sources") ?: JSONObject().also { cur.put("sources", it) }
                val streams = sources.optJSONArray("streams")
                if (streams == null) sources.put("streams", org.json.JSONArray().put(url))
                else {
                    var have = false
                    for (j in 0 until streams.length()) if (streams.optString(j) == url) have = true
                    if (!have) streams.put(url)
                }
                if (cur.optString("logo").isBlank()) cur.put("logo", e.optString("logo"))
                continue
            }
            val country = e.optString("country", "ir").lowercase().ifBlank { "ir" }
            val obj = JSONObject()
                .put("nanoid", "rf-" + Integer.toHexString((key + url).hashCode()))
                .put("name", name)
                .put("logo", e.optString("logo", ""))
                .put("country", country)
                .put("languages", org.json.JSONArray().put("fas"))
                .put("isGeoBlocked", false)
                .put("sources", JSONObject().put("streams", org.json.JSONArray().put(url)))
            allArr.put(obj)
            existing[key] = obj
            byCountry?.optJSONArray(country)?.put(obj)
            meta?.optJSONObject(country)?.let {
                it.put("hasChannels", true)
                it.put("channelCount", it.optInt("channelCount", 0) + 1)
            }
        }
    }

    private fun normalizeName(n: String): String =
        n.lowercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^a-z0-9\\u0600-\\u06FF]+"), "")

    // helboy: resolve a channel by id across all sections (used by the player's JS bridge)
    fun findById(id: String): HelboyChannel? {
        if (id.isBlank()) return null
        val r = ensureLoaded() ?: return null
        for (slug in arrayOf("tv", "radio", "webcams")) {
            val arr = r.optJSONObject(slug)?.optJSONObject("by_category")?.optJSONArray("all") ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("nanoid") == id) return HelboyChannel.fromJson(obj)
            }
        }
        return null
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
