package dev.benfranklin.tether.log

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Structured logging: one JSON object per line, written on a background thread to
 * `files/logs/tether.log`, rotated at [MAX_BYTES] with [KEEP] old files. Logs carry events,
 * states and error reasons only, never clipboard text, notification content or file names.
 * The user exports them from Settings.
 */
object Log {
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP = 3
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tether-log").apply { isDaemon = true } }
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun i(event: String, vararg fields: Pair<String, Any?>) = write("info", event, null, fields)
    fun w(event: String, vararg fields: Pair<String, Any?>) = write("warn", event, null, fields)
    fun e(event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) = write("error", event, error, fields)

    private fun write(level: String, event: String, error: Throwable?, fields: Array<out Pair<String, Any?>>) {
        val line = buildJsonObject {
            put("ts", JsonPrimitive(Instant.now().toString()))
            put("level", JsonPrimitive(level))
            put("event", JsonPrimitive(event))
            for ((k, v) in fields) {
                when (v) {
                    null -> Unit
                    is Number -> put(k, JsonPrimitive(v))
                    is Boolean -> put(k, JsonPrimitive(v))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
            if (error != null) {
                put("error", JsonPrimitive(error.javaClass.simpleName))
                error.message?.let { put("message", JsonPrimitive(it.take(MAX_MESSAGE))) }
            }
        }.toString()
        android.util.Log.println(priority(level), "Tether", line)
        val d = dir ?: return
        io.execute { append(d, line) }
    }

    private fun priority(level: String) = when (level) {
        "error" -> android.util.Log.ERROR
        "warn" -> android.util.Log.WARN
        else -> android.util.Log.INFO
    }

    private fun append(d: File, line: String) {
        try {
            val f = File(d, "tether.log")
            if (f.length() > MAX_BYTES) rotate(d)
            FileWriter(f, true).use { it.write(line + "\n") }
        } catch (_: IOException) {
            // Logging must never take the app down.
        }
    }

    private fun rotate(d: File) {
        File(d, "tether.log.$KEEP").delete()
        for (i in KEEP - 1 downTo 1) File(d, "tether.log.$i").renameTo(File(d, "tether.log.${i + 1}"))
        File(d, "tether.log").renameTo(File(d, "tether.log.1"))
    }

    /** Concatenate all log files (oldest first) into one export file in cache/exports. */
    fun export(context: Context, header: String): File {
        val d = dir ?: File(context.filesDir, "logs")
        val out = File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, "tether-logs.txt") }
        val done = java.util.concurrent.CountDownLatch(1)
        io.execute { done.countDown() } // flush pending writes first
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        out.bufferedWriter().use { w ->
            w.write(header)
            w.write("\n")
            for (i in KEEP downTo 1) File(d, "tether.log.$i").takeIf { it.exists() }?.let { w.write(it.readText()) }
            File(d, "tether.log").takeIf { it.exists() }?.let { w.write(it.readText()) }
        }
        return out
    }

    private const val MAX_MESSAGE = 300
}
