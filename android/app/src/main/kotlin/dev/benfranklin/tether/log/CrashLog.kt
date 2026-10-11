package dev.benfranklin.tether.log

import android.content.Context
import android.os.Build
import dev.benfranklin.tether.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept on disk (0.5.3 crash-safety net). The uncaught-exception handler writes
 * it synchronously before the process dies; Settings › About shows "Copy last crash" while one
 * is stored, so a stack trace can be sent without adb. Also records non-fatal errors that a
 * gesture guard swallowed (see [guarded]), so a bug that no longer crashes still leaves a trace.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val MAX_CHARS = 64_000
    private const val STAMP_CHARS = 16 // "yyyy-MM-dd HH:mm"

    private fun file(c: Context) = File(c.filesDir, FILE)

    fun install(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(app, "Crash", t.name, e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** A swallowed (non-fatal) error from a gesture or other guarded UI callback. */
    fun nonFatal(c: Context, where: String, e: Throwable) {
        runCatching { write(c.applicationContext, "Caught in $where", Thread.currentThread().name, e) }
    }

    fun read(c: Context): String? = runCatching { file(c).takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** When the stored crash happened ("2026-10-11 12:40"), or null when none is stored. */
    fun stamp(c: Context): String? =
        read(c)?.lineSequence()?.firstOrNull()?.substringAfter("· ", "")?.take(STAMP_CHARS)?.takeIf { it.isNotBlank() }

    fun clear(c: Context) {
        runCatching { file(c).delete() }
    }

    internal fun format(kind: String, thread: String, e: Throwable, at: Date = Date()): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(at)
        return buildString {
            append("Tether ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") · ")
            append(time).append('\n')
            append(kind).append(" on thread ").append(thread).append('\n')
            append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append('\n').append(sw.toString().take(MAX_CHARS))
        }
    }

    private fun write(c: Context, kind: String, thread: String, e: Throwable) {
        file(c).writeText(format(kind, thread, e))
    }
}

/**
 * Runs a UI callback (drag, long-press, reorder) so a bug in it can never take the app down:
 * the error is logged and stored as the last crash, and the gesture simply stops.
 */
inline fun guarded(c: Context, where: String, block: () -> Unit) {
    try {
        block()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.e("ui.guard", e, "where" to where)
        CrashLog.nonFatal(c, where, e)
    }
}
