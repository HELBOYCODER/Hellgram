package desu.inugram.helpers.diagnostics

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * entiny/Hellboy: process-wide error journal.
 *
 * Telegram logs plenty, but never in a form the user can hand back. Every sub-system that can fail
 * invisibly (voice transcription, the built-in tunnel, media proxy, TV) reports here with a full
 * cause chain, and the log is readable + copyable from Settings, so a bug report is one tap.
 */
object ErrorLog {

    private const val MAX_ENTRIES = 60
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var file: File? = null

    fun init(context: android.content.Context) {
        if (file != null) return
        file = File(context.filesDir, "hellgram-errors.log")
    }

    @JvmStatic
    fun record(tag: String, detail: String) {
        recordInternal(tag, detail)
    }

    @JvmStatic
    fun record(tag: String, t: Throwable?) {
        recordInternal(tag, stackOf(t))
    }

    @JvmStatic
    fun record(tag: String, detail: String, t: Throwable?) {
        recordInternal(tag, detail + "\n" + stackOf(t))
    }

    private fun recordInternal(tag: String, detail: String) {
        val line = "[${fmt.format(Date())}] [$tag] $detail"
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        try {
            file?.appendText(line + "\n\n")
        } catch (_: Throwable) {
        }
    }

    private fun stackOf(t: Throwable?): String {
        if (t == null) return "(no exception)"
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        // keep it short: message + first frames are what matters in a chat paste
        return sw.toString().lines().take(12).joinToString("\n")
    }

    @JvmStatic
    fun isEmpty(): Boolean = synchronized(entries) { entries.isEmpty() }

    @JvmStatic
    fun text(): String = synchronized(entries) {
        if (entries.isEmpty()) return "— no errors recorded —"
        entries.joinToString("\n\n----\n\n")
    }

    @JvmStatic
    fun clear() {
        synchronized(entries) { entries.clear() }
        try {
            file?.writeText("")
        } catch (_: Throwable) {
        }
    }

    /** Global, copyable report dialog. Safe to call from any thread. */
    @JvmStatic
    fun showReport() {
        AndroidUtilities.runOnUIThread {
            try {
                val activity = AndroidUtilities.findActivity(LaunchActivity.instance)
                    ?: throw IllegalStateException("no activity")
                val body = text()
                val tv = android.widget.TextView(activity).apply {
                    text = body
                    setTextIsSelectable(true)
                    textSize = 11f
                    setPadding(48, 32, 48, 16)
                }
                val scroll = android.widget.ScrollView(activity).apply { addView(tv) }
                AlertDialog.Builder(activity)
                    .setTitle("Hellgram error log")
                    .setView(scroll)
                    .setPositiveButton(LocaleController.getString(R.string.InuCopyLogs)) { _, _ ->
                        AndroidUtilities.addToClipboard(body)
                    }
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show()
            } catch (e: Throwable) {
                org.telegram.messenger.FileLog.e("ErrorLog showReport failed", e)
            }
        }
    }
}
