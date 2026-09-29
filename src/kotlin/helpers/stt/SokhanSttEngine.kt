package desu.inugram.helpers.stt

import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech-to-Text engine powered by Google's online speech API and Sokhan.
 * Keyless, online, free forever.
 */
object SokhanSttEngine {
    private const val SPEECH_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
    private const val BASE_URL = "https://www.google.com/speech-api/v2/recognize"

    fun transcribe(file: File): String {
        val pcm = AudioDecoder.decodeToPcm16(file)
            ?: throw IOException("Failed to decode audio file")
        if (pcm.isEmpty()) return ""

        val flacBytes = FlacEncoder.encodePcm16(pcm)
        val lang = resolveLanguage()

        val urlString = "$BASE_URL?output=json&lang=$lang&key=$SPEECH_KEY"
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 40000
            setRequestProperty("Content-Type", "audio/x-flac; rate=16000")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0")
        }

        connection.outputStream.use { it.write(flacBytes) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            FileLog.e("SokhanSttEngine error HTTP $code: $err")
            throw IOException("Google Speech error HTTP $code: $err")
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        var result = parseGoogleResponse(response)

        if (result.isNotEmpty() && (lang.startsWith("fa") || isPersian(result))) {
            result = PersianHalfspace.apply(result)
        }
        return result
    }

    private fun resolveLanguage(): String {
        val custom = InuConfig.AI_TRANSCRIBE_LANGUAGE.value.trim()
        if (custom.isNotEmpty()) return custom

        val cur = try {
            LocaleController.getInstance().currentLocale?.toLanguageTag() ?: "fa-IR"
        } catch (_: Throwable) {
            "fa-IR"
        }
        return when {
            cur.startsWith("fa", ignoreCase = true) -> "fa-IR"
            cur.startsWith("en", ignoreCase = true) -> "en-US"
            cur.startsWith("ru", ignoreCase = true) -> "ru-RU"
            cur.startsWith("ar", ignoreCase = true) -> "ar-SA"
            cur.startsWith("tr", ignoreCase = true) -> "tr-TR"
            cur.startsWith("uk", ignoreCase = true) -> "uk-UA"
            else -> cur
        }
    }

    private fun parseGoogleResponse(response: String): String {
        val sb = StringBuilder()
        for (line in response.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val json = JSONObject(trimmed)
                val resultArr = json.optJSONArray("result") ?: continue
                for (i in 0 until resultArr.length()) {
                    val resObj = resultArr.optJSONObject(i) ?: continue
                    val altArr = resObj.optJSONArray("alternative") ?: continue
                    val alt = altArr.optJSONObject(0) ?: continue
                    val transcript = alt.optString("transcript", "").trim()
                    if (transcript.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(transcript)
                    }
                }
            } catch (_: Exception) {}
        }
        return sb.toString().trim()
    }

    private fun isPersian(text: String): Boolean =
        text.any { it in '\u0600'..'\u06FF' }
}
