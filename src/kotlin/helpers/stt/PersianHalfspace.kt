package desu.inugram.helpers.stt

/**
 * ZWNJ (half-space) formatter for Persian text from Sokhan.
 */
object PersianHalfspace {
    private const val ZWNJ = '\u200C'

    private fun isPersian(c: Char): Boolean =
        c in '\u0621'..'\u064A' || c in '\u06F0'..'\u06F9' || c in '\u067E'..'\u06AF'

    fun apply(text: String): String {
        val words = text.split(" ")
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val w = words[i]
            if (i + 1 < words.size) {
                val next = words[i + 1]
                val lastFa = w.isNotEmpty() && isPersian(w.last())
                val firstFa = next.isNotEmpty() && isPersian(next.first())
                val merge: String? = when {
                    (w == "می" || w == "نمی") && firstFa -> "$w$ZWNJ$next"
                    next == "ها" && lastFa -> "$w$ZWNJ" + "ها"
                    (next == "تر" || next == "ترین") && lastFa && w.length > 2 -> "$w$ZWNJ$next"
                    else -> null
                }
                if (merge != null) {
                    out.add(merge)
                    i += 2
                    continue
                }
            }
            out.add(w)
            i += 1
        }
        return out.joinToString(" ")
    }
}
