@file:Suppress("MaxLineLength", "MagicNumber", "SwallowedException") // cursor column indexes and size units; expected platform exceptions become protocol replies

package dev.benfranklin.tether.v5

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.benfranklin.tether.R
import dev.benfranklin.tether.core.features.ContinueGate
import dev.benfranklin.tether.core.features.DndSync
import dev.benfranklin.tether.core.features.OtpDetector
import dev.benfranklin.tether.core.proto.Albums
import dev.benfranklin.tether.core.proto.AlbumsReq
import dev.benfranklin.tether.core.proto.Caps5
import dev.benfranklin.tether.core.proto.ContinueCard
import dev.benfranklin.tether.core.proto.ContinueKinds
import dev.benfranklin.tether.core.proto.DndState
import dev.benfranklin.tether.core.proto.FileKinds5
import dev.benfranklin.tether.core.proto.FsGet
import dev.benfranklin.tether.core.proto.FsGetResult
import dev.benfranklin.tether.core.proto.FsList
import dev.benfranklin.tether.core.proto.FsListReq
import dev.benfranklin.tether.core.proto.FsRoots
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.NowPlaying
import dev.benfranklin.tether.core.proto.OpenOnPhone
import dev.benfranklin.tether.core.proto.OpenResult
import dev.benfranklin.tether.core.proto.Otp
import dev.benfranklin.tether.core.proto.PhoneStatus
import dev.benfranklin.tether.core.proto.PhoneStatusReq
import dev.benfranklin.tether.core.proto.Photos
import dev.benfranklin.tether.core.proto.PhotosReq
import dev.benfranklin.tether.core.proto.Thumb
import dev.benfranklin.tether.core.proto.ThumbReq
import dev.benfranklin.tether.core.proto.TypeResult
import dev.benfranklin.tether.core.proto.TypeText
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.files.AndroidFiles
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.screen.ControlService
import dev.benfranklin.tether.service.Notifications
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Everything 0.5 adds on the phone, glued to the runtime: file and photo browsing for the Mac,
 * one-time codes, Continue on Mac cards, open on phone, typing from the Mac, DND sync, the
 * setup status and clipboard images. Protocol work runs on the runtime's [engine]; slow work
 * (listing folders, thumbnails, staging files) on an IO thread.
 */
@Suppress("TooManyFunctions", "LargeClass")
class Features5(
    private val app: Context,
    private val engine: ScheduledExecutorService,
    private val files: AndroidFiles,
    private val settings: () -> AppSettings,
    private val send: (Message) -> Boolean,
    private val sendFile: (TransferRecord) -> Unit,
    private val supports: (String) -> Boolean,
    private val connected: () -> Boolean,
) {
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "tether-fs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    val phoneFiles = PhoneFiles(app)
    private val otpGate = ContinueGate(OTP_DEDUPE_MS)
    private val cardGate = ContinueGate()
    private val dnd = DndSync()
    private var lastStatus: List<dev.benfranklin.tether.core.proto.PermInfo>? = null
    private var lastSong: String? = null
    /** Transfer ids of clipboard images the phone sent, so their rows stay out of the list. */
    private val hiddenTransfers = HashSet<String>()

    // ===================== incoming =====================

    /** True when [m] was a 0.5 message (handled here). */
    fun handle(m: Message): Boolean {
        when (m) {
            is FsListReq -> io.execute { onList(m) }
            is FsGet -> io.execute { onGet(m) }
            is ThumbReq -> io.execute { onThumb(m) }
            is PhotosReq -> io.execute { onPhotos(m) }
            is AlbumsReq -> io.execute { onAlbums(m) }
            is OpenOnPhone -> main.post { onOpen(m) }
            is TypeText -> main.post { onType(m) }
            is DndState -> onRemoteDnd(m)
            is PhoneStatusReq -> pushStatus(force = true)
            else -> return false
        }
        return true
    }

    private fun reply(m: Message) = engine.execute { send(m) }

    private fun onList(r: FsListReq) {
        val out = if (!settings().shareFiles) {
            FsList(r.ref, r.root, r.path, emptyList(), needs = "disabled")
        } else {
            runCatching { phoneFiles.list(r.ref, r.root, r.path, r.offset, r.limit) }
                .getOrElse { FsList(r.ref, r.root, r.path, emptyList(), needs = "error:" + (it.message ?: "list")) }
        }
        reply(fitList(out))
        Log.i("fs.list", "root" to r.root, "n" to out.entries.size, "needs" to out.needs)
    }

    /** Keep one listing under the record limit (very large folders page anyway). */
    private fun fitList(l: FsList): FsList {
        var cur = l
        while (cur.entries.size > 1 && MessageCodec.encode(cur).size > MAX_MESSAGE) {
            val keep = cur.entries.size * 2 / 3
            cur = cur.copy(entries = cur.entries.take(keep), more = true)
        }
        return cur
    }

    private fun onPhotos(r: PhotosReq) {
        val out = if (!settings().sharePhotoBrowser) {
            Photos(r.ref, emptyList(), needs = "disabled", before = r.before)
        } else {
            phoneFiles.photos(r.ref, r.limit, r.before, r.album, r.filter)
        }
        reply(out)
    }

    private fun onAlbums(r: AlbumsReq) {
        reply(if (!settings().sharePhotoBrowser) Albums(r.ref, emptyList(), needs = "disabled") else phoneFiles.albums(r.ref))
    }

    private fun onThumb(r: ThumbReq) {
        val allowed = if (r.root == FsRoots.PHOTOS) settings().sharePhotoBrowser else settings().shareFiles
        if (!allowed) return reply(Thumb(r.ref, false, ByteArray(0), "disabled"))
        val (data, reason) = phoneFiles.thumbnail(r.root, r.path, r.size, THUMB_BYTES)
        reply(Thumb(r.ref, data != null, data ?: ByteArray(0), reason))
    }

    private fun onGet(r: FsGet) {
        val allowed = if (r.root == FsRoots.PHOTOS) settings().sharePhotoBrowser else settings().shareFiles
        if (!allowed) return reply(FsGetResult(r.ref, false, reason = "disabled"))
        val (uri, needs) = phoneFiles.uriFor(r.root, r.path)
        if (uri == null) return reply(FsGetResult(r.ref, false, reason = needs ?: "missing"))
        try {
            val staged = files.stage(uri, System.currentTimeMillis())
            val name = if (uri.scheme == "file") TransferEngine.safeFileName(File(uri.path!!).name) else staged.name
            val rec = staged.copy(name = name, kind = FileKinds5.FS, mime = PhoneFiles.mimeOf(name) ?: staged.mime)
            engine.execute {
                sendFile(rec)
                send(FsGetResult(r.ref, true, rec.id, rec.name, rec.size))
            }
            Log.i("fs.get", "root" to r.root, "bytes" to rec.size)
        } catch (e: IOException) {
            reply(FsGetResult(r.ref, false, reason = "error:" + (e.message ?: "read")))
        } catch (e: SecurityException) {
            reply(FsGetResult(r.ref, false, reason = if (r.root == FsRoots.PHOTOS) "photos" else "allfiles"))
        }
    }

    // ===================== open on phone =====================

    private fun onOpen(o: OpenOnPhone) {
        if (!settings().allowOpenOnPhone) return reply(OpenResult(o.id, false, reason = "disabled"))
        val text = o.text
        val url = o.url?.trim()
        if (url.isNullOrEmpty()) {
            if (text.isNullOrEmpty()) return reply(OpenResult(o.id, false, reason = "invalid"))
            app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tether", text))
            Notifications.alert(app, "Copied from your Mac", text.take(PREVIEW))
            return reply(OpenResult(o.id, true, "clipboard"))
        }
        dev.benfranklin.tether.core.features.PhoneSetupLink.itemId(url)?.let { return openSetupFix(o, it) }
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in OPENABLE) return reply(OpenResult(o.id, false, reason = "invalid"))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(app.packageManager) == null && uri.scheme !in listOf("http", "https")) {
            return reply(OpenResult(o.id, false, reason = "no_app"))
        }
        // Android blocks activity starts from the background unless Tether may draw over apps.
        if (Settings.canDrawOverlays(app)) {
            try {
                app.startActivity(intent)
                return reply(OpenResult(o.id, true, "activity"))
            } catch (e: ActivityNotFoundException) {
                return reply(OpenResult(o.id, false, reason = "no_app"))
            } catch (e: SecurityException) {
                Log.w("open.blocked", "reason" to e.message)
            }
        }
        val pi = PendingIntent.getActivity(app, o.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, Notifications.CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle("Open on phone")
            .setContentText(url.take(PREVIEW))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        Notifications.postRaw(app, ID_OPEN_BASE + (o.id.hashCode() and ID_MASK), n)
        reply(OpenResult(o.id, true, "notification"))
    }

    /** The Mac's "Fix" for one checklist row (tether://setup/<id>): open its Settings screen, or Tether's Setup. */
    private fun openSetupFix(o: OpenOnPhone, id: String) {
        val fix = Setup.items(app).firstOrNull { it.id == id }?.fix
        val intent = ((fix as? Setup.Fix.Screen)?.intent ?: Intent(app, dev.benfranklin.tether.ui.MainActivity::class.java)
            .putExtra(EXTRA_SETUP_ITEM, id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.i("open.setup_fix", "item" to id, "overlay" to Settings.canDrawOverlays(app))
        if (Settings.canDrawOverlays(app)) {
            try {
                app.startActivity(intent)
                return reply(OpenResult(o.id, true, "activity"))
            } catch (e: ActivityNotFoundException) {
                Log.w("open.setup_fix_failed", "reason" to e.message)
            } catch (e: SecurityException) {
                Log.w("open.blocked", "reason" to e.message)
            }
        }
        val pi = PendingIntent.getActivity(app, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = Setup.items(app).firstOrNull { it.id == id }?.label ?: "Setup"
        val n = NotificationCompat.Builder(app, Notifications.CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle("Turn on $label")
            .setContentText("Your Mac asked. Tap to open the setting.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        Notifications.postRaw(app, ID_OPEN_BASE + (id.hashCode() and ID_MASK), n)
        reply(OpenResult(o.id, true, "notification"))
    }

    // ===================== typing =====================

    private fun onType(t: TypeText) {
        if (!settings().allowTyping) return reply(TypeResult(t.seq, false, "disabled"))
        val svc = ControlService.current
        if (svc == null) {
            // Enabled but not bound yet (just switched on, or the process restarted): say so.
            val reason = if (ControlService.enabledInSettings(app)) "binding" else "no_service"
            Log.i("type.no_service", "reason" to reason)
            return reply(TypeResult(t.seq, false, reason))
        }
        val r = svc.type(t)
        Log.i("type.result", "ok" to r.ok, "reason" to r.reason, "key" to t.key, "chars" to (t.text?.length ?: 0))
        reply(r)
    }

    // ===================== DND =====================

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            engine.execute { pushDnd() }
        }
    }

    private fun dndOn(): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun pushDnd() {
        if (!settings().dndSync || !supports(Caps5.DND) || !connected()) return
        val on = dndOn()
        if (dnd.local(on, System.currentTimeMillis())) send(DndState(on, "phone", System.currentTimeMillis(), Setup.dndAccess(app)))
    }

    private fun onRemoteDnd(d: DndState) {
        if (!settings().dndSync) return
        if (!dnd.remote(d.on, System.currentTimeMillis())) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.i("dnd.no_access")
            return
        }
        nm.setInterruptionFilter(if (d.on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        Log.i("dnd.applied", "on" to d.on)
    }

    // ===================== status =====================

    fun pushStatus(force: Boolean = false) = engine.execute {
        if (!supports(Caps5.STATUS) || !connected()) return@execute
        val perms = Setup.perms(app)
        if (!force && perms == lastStatus) return@execute
        lastStatus = perms
        send(PhoneStatus(perms, Build.VERSION.SDK_INT, System.currentTimeMillis()))
    }

    // ===================== one-time codes =====================

    /** A text arrived (SMS body or notification text): copy any one-time code to the Mac. */
    fun maybeOtp(appName: String, text: String?, src: String) {
        if (!settings().otpAutoCopy || text.isNullOrBlank()) return
        val code = OtpDetector.find(text) ?: return
        engine.execute {
            if (!supports(Caps5.OTP) || !connected()) return@execute
            if (!otpGate.offer(code, System.currentTimeMillis())) return@execute
            send(Otp(code, appName.take(APP_MAX), src, System.currentTimeMillis()))
            Log.i("otp.sent", "src" to src, "len" to code.length)
        }
    }

    // ===================== Continue on Mac =====================

    fun card(card: ContinueCard, dedupeKey: String = card.id + "|" + (card.url ?: card.path ?: card.title)) = engine.execute {
        if (!settings().continueCards || !supports(Caps5.CONTINUE) || !connected()) return@execute
        if (!cardGate.offer(dedupeKey, System.currentTimeMillis())) return@execute
        send(card)
        Log.i("continue.sent", "kind" to card.kind, "thumb" to card.thumb.size)
    }

    /** Text the phone sent to the Mac's clipboard: a single web link also becomes a card. */
    fun linkCopied(text: String) {
        if (!settings().continueLinks) return
        val t = text.trim()
        if (t.contains(' ') || t.contains('\n') || !(t.startsWith("http://") || t.startsWith("https://"))) return
        val host = runCatching { Uri.parse(t).host }.getOrNull() ?: return
        card(ContinueCard("link", ContinueKinds.LINK, host, t.take(TITLE_MAX), t, app = "Clipboard", ts = System.currentTimeMillis()))
    }

    fun shared(text: String?, subject: String?) {
        val t = text?.trim().orEmpty()
        val url = Regex("https?://\\S+").find(t)?.value
        val title = subject?.takeIf { it.isNotBlank() } ?: url?.let { Uri.parse(it).host } ?: t.take(TITLE_MAX)
        card(
            ContinueCard(
                "share-" + UUID.randomUUID(), ContinueKinds.SHARE, title.take(TITLE_MAX), (url ?: t).take(TITLE_MAX), url,
                ts = System.currentTimeMillis(), ttl = SHARE_TTL,
            ),
        )
        if (url == null && t.isNotEmpty()) {
            // Plain text: it goes to the Mac clipboard too.
            engine.execute { send(dev.benfranklin.tether.core.proto.Clip(UUID.randomUUID().toString(), System.currentTimeMillis(), t)) }
        }
    }

    /** A file shared to "Continue on Mac": it is sent, and the card opens it on arrival. */
    fun sharedFile(rec: TransferRecord, thumb: ByteArray) {
        card(
            ContinueCard(
                "file-" + rec.id, ContinueKinds.SHARE, rec.name, "Shared from your phone", root = "transfer", path = rec.id,
                size = rec.size, ts = System.currentTimeMillis(), ttl = SHARE_TTL, thumb = thumb,
            ),
        )
    }

    /** Files shared to "Continue on Mac": staged, sent, and announced with a card (thumbnail for images). */
    fun continueFiles(uris: List<Uri>) {
        io.execute {
            for (u in uris) {
                try {
                    val rec = files.stage(u, System.currentTimeMillis())
                    val thumb = if (rec.mime.startsWith("image/")) {
                        runCatching {
                            app.contentResolver.openInputStream(u)?.use { input ->
                                val bmp = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = SHARE_SAMPLE })
                                bmp?.let { PhoneFiles.jpeg(it, CARD_THUMB_BYTES) }
                            }
                        }.getOrNull() ?: ByteArray(0)
                    } else {
                        ByteArray(0)
                    }
                    engine.execute { sendFile(rec) }
                    sharedFile(rec, thumb)
                } catch (e: IOException) {
                    Log.w("continue.stage_failed", "reason" to e.message)
                } catch (e: SecurityException) {
                    Log.w("continue.stage_denied", "reason" to e.message)
                }
            }
        }
    }

    fun nowPlaying(np: NowPlaying) {
        if (!settings().continueSongs || !np.active || !np.playing || np.title.isBlank()) return
        val key = np.title + "|" + np.artist
        if (key == lastSong) return
        lastSong = key
        card(
            ContinueCard(
                "song", ContinueKinds.SONG, np.title.take(TITLE_MAX), np.artist.takeIf { it.isNotBlank() }, null, app = np.app,
                ts = System.currentTimeMillis(), ttl = SONG_TTL,
            ),
            dedupeKey = "song|$key",
        )
    }

    /** The browser tab the accessibility service saw (Chrome, Brave, Edge, Samsung Internet). */
    fun browserTab(url: String, title: String?, browser: String) {
        if (!settings().continueTabs) return
        val u = if (url.startsWith("http")) url else "https://$url"
        val host = runCatching { Uri.parse(u).host }.getOrNull() ?: return
        card(
            ContinueCard(
                "tab", ContinueKinds.TAB, (title?.takeIf { it.isNotBlank() } ?: host).take(TITLE_MAX), u.removePrefix("https://").take(TITLE_MAX),
                u, app = browser, ts = System.currentTimeMillis(), ttl = TAB_TTL,
            ),
            dedupeKey = "tab|$u",
        )
    }

    // ----- new photos and downloads (MediaStore observers) -----

    private var observing = false
    private var lastPhotoId = -1L
    private var lastDownloadId = -1L

    private val photoObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            io.execute { scanNewPhoto() }
        }
    }

    private val downloadObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            io.execute { scanNewDownload() }
        }
    }

    private fun newest(uri: Uri, extra: Array<String>): android.database.Cursor? = runCatching {
        app.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED) + extra,
            null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )
    }.getOrNull()

    private fun scanNewPhoto() {
        if (!settings().continuePhotos || !Setup.photosAccess(app)) return
        newest(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, emptyArray())?.use { c ->
            if (!c.moveToFirst()) return
            val id = c.getLong(0)
            val added = c.getLong(3) * MS
            if (lastPhotoId < 0) {
                lastPhotoId = id
                return
            }
            if (id == lastPhotoId || System.currentTimeMillis() - added > FRESH_MS) return
            lastPhotoId = id
            val name = c.getString(1) ?: "photo"
            val size = c.getLong(2)
            val shot = name.contains("screenshot", ignoreCase = true)
            val thumb = phoneFiles.thumbnail(FsRoots.PHOTOS, id.toString(), CARD_THUMB, CARD_THUMB_BYTES).first ?: ByteArray(0)
            card(
                ContinueCard(
                    "photo-$id", ContinueKinds.PHOTO, name, (if (shot) "Screenshot" else "Photo") + " · " + human(size),
                    root = FsRoots.PHOTOS, path = id.toString(), size = size, ts = System.currentTimeMillis(), thumb = thumb,
                ),
            )
        }
    }

    private fun scanNewDownload() {
        if (!settings().continueDownloads || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        newest(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING))
            ?.use { c ->
                if (!c.moveToFirst()) return
                val id = c.getLong(0)
                if (lastDownloadId < 0) {
                    lastDownloadId = id
                    return
                }
                if (id == lastDownloadId || c.getInt(5) == 1 || System.currentTimeMillis() - c.getLong(3) * MS > FRESH_MS) return
                lastDownloadId = id
                val name = c.getString(1) ?: return
                val rel = c.getString(4).orEmpty().removePrefix("Download/").removePrefix("Download").trim('/')
                val path = if (rel.isEmpty()) name else "$rel/$name"
                card(
                    ContinueCard(
                        "download-$id", ContinueKinds.DOWNLOAD, name, "Download · " + human(c.getLong(2)), root = FsRoots.DOWNLOADS,
                        path = path, size = c.getLong(2), ts = System.currentTimeMillis(),
                    ),
                )
            }
    }

    // ===================== clipboard images =====================

    /** The phone clipboard holds an image (read while Tether is in front): send it to the Mac clipboard. */
    fun sendClipImage(uri: Uri) {
        if (!settings().clipImages) return
        io.execute {
            try {
                val rec = files.stage(uri, System.currentTimeMillis())
                if (rec.size > CLIP_IMAGE_MAX) {
                    files.release(rec)
                    return@execute
                }
                val mime = app.contentResolver.getType(uri) ?: rec.mime
                engine.execute {
                    if (!supports(Caps5.CLIP_IMAGE)) {
                        files.release(rec)
                        return@execute
                    }
                    hiddenTransfers += rec.id
                    sendFile(rec.copy(kind = FileKinds5.CLIP, mime = mime, name = "clipboard." + mime.substringAfter('/', "png")))
                }
            } catch (e: IOException) {
                Log.w("clipimg.stage_failed", "reason" to e.message)
            } catch (e: SecurityException) {
                Log.w("clipimg.denied", "reason" to e.message)
            }
        }
    }

    /** Transfers 0.5 owns are not shown as downloads (clipboard images, browser fetches). */
    fun hides(r: TransferRecord): Boolean = r.kind == FileKinds5.CLIP || r.id in hiddenTransfers

    /** A transfer finished: an incoming clipboard image goes onto the phone clipboard. Returns true when consumed. */
    fun transferDone(r: TransferRecord): Boolean {
        if (r.kind != FileKinds5.CLIP) return false
        if (r.direction == Direction.OUT || r.status != TransferStatus.DONE) return true
        val src = r.ref?.let(::localFile) ?: return true
        io.execute { putImageOnClipboard(src, r.mime) }
        return true
    }

    private fun localFile(ref: String): File? = File(ref).takeIf { it.exists() }

    private fun putImageOnClipboard(src: File, mime: String) {
        val dir = File(app.filesDir, "clips").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("clipboard-") }?.forEach { it.delete() }
        val ext = if (mime.contains("jpeg") || mime.contains("jpg")) "jpg" else "png"
        val f = File(dir, "clipboard-${System.currentTimeMillis()}.$ext")
        src.copyTo(f, overwrite = true)
        src.delete()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        if (opts.outWidth <= 0) {
            Log.w("clipimg.not_an_image")
            return
        }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        main.post {
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return@post
            cm.setPrimaryClip(ClipData.newUri(app.contentResolver, "Image from Mac", uri))
            Log.i("clipimg.received", "bytes" to f.length())
        }
    }

    // ===================== lifecycle =====================

    fun apply(paired: Boolean, s: AppSettings) {
        val want = paired && s.continueCards && (s.continuePhotos || s.continueDownloads)
        if (want && !observing) {
            observing = true
            runCatching {
                app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, photoObserver)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    app.contentResolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, downloadObserver)
                }
            }
            io.execute {
                scanNewPhoto()
                scanNewDownload()
            }
        } else if (!want && observing) {
            observing = false
            app.contentResolver.unregisterContentObserver(photoObserver)
            app.contentResolver.unregisterContentObserver(downloadObserver)
        }
        ControlService.current?.configureTabs(paired && s.continueCards && s.continueTabs)
    }

    init {
        ContextCompat.registerReceiver(
            app, dndReceiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** The link came up: send what the Mac needs to know right away. */
    fun linkUp() = engine.execute {
        dnd.reset()
        lastStatus = null
        pushDnd()
        pushStatus()
    }

    companion object {
        private const val OTP_DEDUPE_MS = 120_000L
        private const val MAX_MESSAGE = 120 * 1024
        private const val THUMB_BYTES = 92 * 1024
        private const val CARD_THUMB = 192
        private const val CARD_THUMB_BYTES = 24 * 1024
        private const val SHARE_SAMPLE = 8
        private const val CLIP_IMAGE_MAX = 16L * 1024 * 1024
        private const val PREVIEW = 120
        private const val APP_MAX = 64
        private const val TITLE_MAX = 200
        private const val SONG_TTL = 10 * 60
        private const val TAB_TTL = 30 * 60
        private const val SHARE_TTL = 30 * 60
        private const val FRESH_MS = 120_000L
        private const val MS = 1000L
        private const val ID_OPEN_BASE = 90_000
        private const val ID_MASK = 0xFFF
        const val EXTRA_SETUP_ITEM = "tether.setup_item"
        private val OPENABLE = setOf("http", "https", "mailto", "tel", "geo", "sms", "smsto", "market", "spotify", "upi")

        fun human(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "${bytes shr 10} KB"
            else -> "$bytes B"
        }
    }
}
