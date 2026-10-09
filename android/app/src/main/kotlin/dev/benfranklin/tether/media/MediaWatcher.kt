package dev.benfranklin.tether.media

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.features.MediaLedger
import dev.benfranklin.tether.core.features.MediaLedgerState
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.data.JsonFile
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime
import java.io.File
import java.io.IOException

/** What kind of new image this is, from where MediaStore filed it. Pure; unit-tested. */
object MediaKinds {
    const val SCREENSHOT = "screenshot"
    const val PHOTO = "photo"

    /**
     * [relativePath] is MediaStore RELATIVE_PATH (Android 10+, e.g. "Pictures/Screenshots/" or
     * "DCIM/Camera/"); [dataPath] the legacy DATA column; [bucket] BUCKET_DISPLAY_NAME.
     */
    fun classify(relativePath: String?, dataPath: String?, bucket: String?): String? {
        val p = (relativePath ?: dataPath ?: "").replace('\\', '/').lowercase()
        val b = bucket.orEmpty().lowercase()
        return when {
            "screenshot" in p || b == "screenshots" -> SCREENSHOT
            p.startsWith("dcim/camera") || "/dcim/camera/" in p || b == "camera" -> PHOTO
            else -> null
        }
    }
}

/**
 * Watches MediaStore for new screenshots and camera photos and queues them for the Mac.
 * A ContentObserver catches changes while the link service keeps the process alive; a
 * content-trigger job catches them when Android killed the process. Each new item is hashed
 * and checked against the [MediaLedger] (never the old gallery, never the same bytes twice);
 * transfers themselves resume after disconnects like any other file.
 */
class MediaWatcher(private val context: Context, private val enqueue: (TransferRecord) -> Unit) {
    data class Prefs(val screenshots: Boolean, val photos: Boolean) {
        val any: Boolean get() = screenshots || photos
    }

    private val thread = HandlerThread("tether-media").apply { start() }
    private val handler = Handler(thread.looper)
    private val ledgerFile = JsonFile(File(context.filesDir, "media_ledger.json"), MediaLedgerState.serializer())
    private val ledger = MediaLedger(ledgerFile.read() ?: MediaLedgerState())
    private var prefs = Prefs(screenshots = false, photos = false)
    private var observer: ContentObserver? = null
    private val scan = Runnable { scanNow() }

    /** Apply the user's toggles; starts or stops watching. Call from any thread. */
    fun configure(p: Prefs) = handler.post {
        val was = prefs
        prefs = p
        if (p.any && hasPermission(context)) {
            if (!ledger.started) {
                ledger.startAt(System.currentTimeMillis() / MS)
                ledgerFile.write(ledger.state)
            }
            register()
            schedule(context)
            if (!was.any) Log.i("media.watch_on", "screenshots" to p.screenshots, "photos" to p.photos)
            handler.removeCallbacks(scan)
            handler.postDelayed(scan, DEBOUNCE_MS)
        } else {
            unregister()
            cancel(context)
            if (was.any) Log.i("media.watch_off", "permission" to hasPermission(context))
        }
    }

    /** Look for anything new now (after a reconnect or a job wake-up). */
    fun poke() {
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, DEBOUNCE_MS)
    }

    private fun register() {
        if (observer != null) return
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = poke()
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
        observer = o
    }

    private fun unregister() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private class Item(val id: Long, val added: Long, val name: String?, val mime: String?, val kind: String?, val pending: Boolean)

    private fun scanNow() {
        if (!prefs.any || !hasPermission(context) || !ledger.started) return
        var pendingSeen = false
        try {
            val fresh = query(ledger.state.watermark).filter { ledger.isNew(it.id, it.added) }
            // Stop at the first item still being written; we'll be notified when it's done.
            val ready = fresh.takeWhile { !it.pending }
            pendingSeen = ready.size < fresh.size
            for (item in ready) {
                val kind = item.kind
                if (kind != null && wanted(kind)) {
                    handle(item.id, item.added, kind, item.name, item.mime)
                } else {
                    ledger.advance(item.id, item.added)
                }
            }
        } catch (e: SecurityException) {
            Log.w("media.scan_denied", "reason" to e.message)
        } catch (e: IllegalArgumentException) {
            Log.e("media.scan_failed", e)
        }
        ledgerFile.write(ledger.state)
        if (pendingSeen) handler.postDelayed(scan, PENDING_RETRY_MS)
    }

    private fun wanted(kind: String): Boolean =
        (kind == MediaKinds.SCREENSHOT && prefs.screenshots) || (kind == MediaKinds.PHOTO && prefs.photos)

    private fun query(since: Long): List<Item> {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        @Suppress("DEPRECATION")
        val pathCol = if (q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val cols = listOfNotNull(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, pathCol,
            if (q) MediaStore.Images.Media.IS_PENDING else null,
        ).toTypedArray()
        val out = ArrayList<Item>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, "${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf(since.toString()), "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC",
        )?.use { c ->
            val iPath = c.getColumnIndex(pathCol)
            val iPending = if (q) c.getColumnIndex(MediaStore.Images.Media.IS_PENDING) else -1
            val iBucket = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val p = if (iPath >= 0) c.getString(iPath) else null
                val bucket = if (iBucket >= 0) c.getString(iBucket) else null
                out += Item(
                    id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)),
                    added = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)),
                    name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)),
                    mime = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)),
                    kind = MediaKinds.classify(if (q) p else null, if (q) null else p, bucket),
                    pending = iPending >= 0 && c.getInt(iPending) == 1,
                )
            }
        }
        return out
    }

    private fun handle(id: Long, added: Long, kind: String, name: String?, mime: String?) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val rec = try {
            context.runtime.files.stage(uri, System.currentTimeMillis())
        } catch (e: IOException) {
            Log.w("media.stage_failed", "reason" to e.message)
            ledger.advance(id, added)
            return
        } catch (e: SecurityException) {
            Log.w("media.stage_denied", "reason" to e.message)
            return
        }
        when (ledger.decide(id, added, rec.sha256)) {
            MediaLedger.Decision.SEND -> {
                val named = if (name.isNullOrBlank()) rec else rec.copy(name = TransferEngine.safeFileName(name))
                enqueue(named.copy(kind = kind, wifiOnly = kind == MediaKinds.PHOTO, mime = mime ?: rec.mime))
                Log.i("media.queued", "kind" to kind, "bytes" to rec.size)
            }
            else -> {
                context.runtime.files.release(rec)
                Log.i("media.skipped_duplicate", "kind" to kind)
            }
        }
    }

    companion object {
        private const val MS = 1000L
        private const val DEBOUNCE_MS = 1_500L
        private const val PENDING_RETRY_MS = 4_000L
        private const val JOB_ID = 4711

        /** Android 13+: READ_MEDIA_IMAGES; Android 14 partial access is reported separately. */
        fun permission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        fun hasPermission(c: Context): Boolean =
            ContextCompat.checkSelfPermission(c, permission()) == PackageManager.PERMISSION_GRANTED

        /** Android 14+: the user picked "Allow limited access" (only selected photos are visible). */
        fun limitedAccess(c: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !hasPermission(c) && ContextCompat.checkSelfPermission(
                c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED

        /** A content-trigger job wakes us for new images even if the process was killed. */
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, MediaJobService::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .setTriggerContentUpdateDelay(DEBOUNCE_MS)
                .build()
            js.schedule(job)
        }

        fun cancel(c: Context) {
            c.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}

/** Woken by MediaStore changes; content-trigger jobs are one-shot, so it re-schedules itself. */
class MediaJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val rt = applicationContext.runtime
        rt.mediaChanged()
        MediaWatcher.schedule(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
