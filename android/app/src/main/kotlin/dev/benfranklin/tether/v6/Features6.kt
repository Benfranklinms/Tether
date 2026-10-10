@file:Suppress("TooManyFunctions", "MagicNumber")

package dev.benfranklin.tether.v6

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.benfranklin.tether.R
import dev.benfranklin.tether.core.features.CaptureNaming
import dev.benfranklin.tether.core.features.ShelfDetect
import dev.benfranklin.tether.core.features.ShelfState
import dev.benfranklin.tether.core.proto.CaptureReasons
import dev.benfranklin.tether.core.proto.CaptureReq
import dev.benfranklin.tether.core.proto.CaptureResult
import dev.benfranklin.tether.core.proto.CaptureStates
import dev.benfranklin.tether.core.proto.Caps6
import dev.benfranklin.tether.core.proto.FileKinds6
import dev.benfranklin.tether.core.proto.FindActions
import dev.benfranklin.tether.core.proto.FindMac
import dev.benfranklin.tether.core.proto.FindMacState
import dev.benfranklin.tether.core.proto.HotspotReasons
import dev.benfranklin.tether.core.proto.HotspotReq
import dev.benfranklin.tether.core.proto.HotspotState
import dev.benfranklin.tether.core.proto.HotspotStates
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfFileReq
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.proto.ShelfSync
import dev.benfranklin.tether.core.proto.ShelfTomb
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.files.AndroidFiles
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.screen.ControlService
import dev.benfranklin.tether.service.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/** Find my Mac as the phone UI shows it. */
data class FindUi(val ringing: Boolean = false, val pending: String? = null, val locked: Boolean = false, val error: String? = null)

/** Hotspot request as the phone UI shows it. */
data class HotspotUi(val state: String? = null, val reason: String? = null)

/** An Insert from Phone request waiting for the camera / scanner. */
data class CaptureUi(val ref: String, val kind: String)

@Serializable private data class ShelfDisk(val items: List<ShelfItem> = emptyList(), val tombs: List<ShelfTomb> = emptyList())

/**
 * 0.5.2 on the phone: Insert from Phone (camera / document scanner for the Mac), Find my Mac,
 * Instant Hotspot and Shelf. Protocol work on the runtime's [engine], slow work on [io].
 */
class Features6(
    private val app: Context,
    private val engine: ScheduledExecutorService,
    private val files: AndroidFiles,
    private val settings: () -> AppSettings,
    private val send: (Message) -> Boolean,
    private val sendFile: (TransferRecord) -> Unit,
    private val supports: (String) -> Boolean,
) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tether-v6").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val shelfFile = File(app.filesDir, "shelf.sealed")

    private val _shelf = MutableStateFlow(ShelfState())
    val shelf: StateFlow<ShelfState> = _shelf.asStateFlow()
    private val _find = MutableStateFlow(FindUi())
    val find: StateFlow<FindUi> = _find.asStateFlow()
    private val _hotspot = MutableStateFlow(HotspotUi())
    val hotspot: StateFlow<HotspotUi> = _hotspot.asStateFlow()
    private val _capture = MutableStateFlow<CaptureUi?>(null)
    val capture: StateFlow<CaptureUi?> = _capture.asStateFlow()
    /** Shelf file ids the user asked to open (opened when the bytes arrive). */
    private val wantedFiles = HashSet<String>()
    private var hotspotRef: String? = null
    private var apReceiver: BroadcastReceiver? = null

    init {
        io.execute { loadShelf() }
    }

    fun handle(m: Message): Boolean {
        when (m) {
            is CaptureReq -> main.post { onCapture(m) }
            is FindMacState -> onFindState(m)
            is HotspotReq -> main.post { onHotspot(m) }
            is ShelfSync -> io.execute { mergeShelf(m) }
            is ShelfFileReq -> io.execute { serveShelfFile(m) }
            else -> return false
        }
        return true
    }

    /** Link up: send our whole shelf (the Mac does the same; both merge). */
    fun linkUp() {
        if (supports(Caps6.SHELF)) send(_shelf.value.snapshot())
    }

    private fun reply(m: Message) = engine.execute { send(m) }

    /** Transfers 0.5.2 owns aren't shown as downloads. */
    fun hides(r: TransferRecord): Boolean = r.kind == FileKinds6.CAPTURE || r.kind == FileKinds6.SHELF

    fun transferDone(r: TransferRecord): Boolean {
        if (!hides(r)) return false
        if (r.kind == FileKinds6.SHELF && r.direction == Direction.IN && r.status == TransferStatus.DONE) {
            io.execute { shelfFileArrived(r) }
        }
        if (r.kind == FileKinds6.CAPTURE && r.direction == Direction.OUT && r.status.isFinal) {
            r.ref?.let { File(it).delete() }
        }
        return true
    }

    // ===================== Insert from Phone =====================

    private fun onCapture(r: CaptureReq) {
        if (!settings().allowCapture) return reply(CaptureResult(r.ref, CaptureStates.FAILED, reason = CaptureReasons.DISABLED))
        if (_capture.value != null) return reply(CaptureResult(r.ref, CaptureStates.FAILED, reason = CaptureReasons.BUSY))
        _capture.value = CaptureUi(r.ref, r.kind)
        val intent = CaptureActivity.intent(app, r.ref, r.kind)
        if (Settings.canDrawOverlays(app) || ControlService.available) {
            try {
                app.startActivity(intent)
                return reply(CaptureResult(r.ref, CaptureStates.OPEN))
            } catch (e: SecurityException) {
                Log.w("capture.blocked", "reason" to e.message)
            }
        }
        val pi = PendingIntent.getActivity(app, 7001, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, Notifications.CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(if (r.kind == "scan") "Scan a document for your Mac" else "Take a photo for your Mac")
            .setContentText("Tap to open the camera")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        Notifications.postRaw(app, ID_CAPTURE, n)
        reply(CaptureResult(r.ref, CaptureStates.OPEN, reason = CaptureReasons.TAP_PHONE))
    }

    /** From CaptureActivity: the picture / scan ([file], [pages]) or null when cancelled. */
    fun captureDone(ref: String, kind: String, file: File?, pages: Int, error: String? = null) {
        _capture.value = null
        Notifications.cancelId(app, ID_CAPTURE)
        if (file == null) {
            return reply(CaptureResult(ref, CaptureStates.FAILED, reason = error ?: CaptureReasons.CANCELLED))
        }
        io.execute {
            try {
                val staged = files.stage(Uri.fromFile(file), System.currentTimeMillis())
                val name = CaptureNaming.fileName(kind, pages, System.currentTimeMillis())
                val mime = if (name.endsWith(".pdf")) "application/pdf" else "image/jpeg"
                val rec = staged.copy(name = name, kind = FileKinds6.CAPTURE, mime = mime)
                file.delete()
                engine.execute {
                    sendFile(rec)
                    send(CaptureResult(ref, CaptureStates.SENDING, rec.id, rec.name, pages = pages))
                }
                Log.i("capture.sent", "kind" to kind, "pages" to pages, "bytes" to rec.size)
            } catch (e: IOException) {
                reply(CaptureResult(ref, CaptureStates.FAILED, reason = "error:" + (e.message ?: "read")))
            }
        }
    }

    // ===================== Find my Mac =====================

    fun findMac(action: String) {
        val id = UUID.randomUUID().toString().take(8)
        _find.value = _find.value.copy(pending = action, error = null)
        engine.execute {
            if (!supports(Caps6.FIND_MAC) || !send(FindMac(id, action))) {
                _find.value = FindUi(error = "Your Mac isn't connected")
            }
        }
        main.postDelayed({
            if (_find.value.pending == action) _find.value = _find.value.copy(pending = null, error = "No answer from your Mac")
        }, 8_000)
    }

    private fun onFindState(s: FindMacState) {
        _find.value = FindUi(
            ringing = s.ringing, pending = null, locked = s.locked,
            error = if (s.ok) null else (s.reason ?: "Couldn't do that on the Mac"),
        )
        if (s.by == "mac") Notifications.alert(app, "Found your Mac", "Someone pressed Found it on the Mac")
    }

    // ===================== Instant Hotspot =====================

    private fun onHotspot(r: HotspotReq) {
        hotspotRef = r.ref
        if (!settings().allowHotspot) return hotspotFailed(HotspotReasons.DISABLED)
        val ssid = HotspotStore.ssid(app)
        if (ssid.isNullOrBlank()) {
            Notifications.alert(app, "Instant Hotspot", "Save your hotspot name and password in Tether › Settings to let your Mac join")
            return hotspotFailed(HotspotReasons.NO_CREDENTIALS)
        }
        if (HotspotFlipper.apEnabled(app) == true) return hotspotOn()
        _hotspot.value = HotspotUi(HotspotStates.STARTING)
        reply(HotspotState(r.ref, HotspotStates.STARTING))
        watchAp()
        val svc = ControlService.current
        if (svc == null || !settings().hotspotAutomation) {
            manualHotspot(HotspotReasons.NO_SERVICE)
            return
        }
        HotspotFlipper.flip(svc) { clicked ->
            if (!clicked) {
                manualHotspot(HotspotReasons.NO_SWITCH)
            } else {
                // The switch was clicked: wait for the soft AP to report enabled (or assume on after 6 s).
                main.postDelayed({ if (hotspotRef == r.ref && _hotspot.value.state == HotspotStates.STARTING) hotspotOn() }, 6_000)
            }
        }
        main.postDelayed({
            if (hotspotRef == r.ref && _hotspot.value.state != HotspotStates.ON && _hotspot.value.state != HotspotStates.FAILED) {
                hotspotFailed(HotspotReasons.TIMEOUT)
            }
        }, 90_000)
    }

    private fun manualHotspot(why: String) {
        Log.i("hotspot.manual", "why" to why)
        _hotspot.value = HotspotUi(HotspotStates.MANUAL, why)
        reply(HotspotState(hotspotRef, HotspotStates.MANUAL, reason = why))
        val opened = Settings.canDrawOverlays(app) && HotspotFlipper.openSettings(app)
        if (!opened) {
            val i = Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(app, 7002, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, Notifications.CH_REQUESTS)
                .setSmallIcon(R.drawable.ic_stat_tether)
                .setContentTitle("Turn on Hotspot for your Mac")
                .setContentText("Tap, then switch on Hotspot. Your Mac joins by itself.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            Notifications.postRaw(app, ID_HOTSPOT, n)
        }
    }

    private fun watchAp() {
        if (apReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val state = i.getIntExtra("wifi_state", -1)
                if (state == AP_ENABLED && _hotspot.value.state != HotspotStates.ON && hotspotRef != null) hotspotOn()
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter(AP_ACTION), ContextCompat.RECEIVER_EXPORTED)
        apReceiver = r
    }

    private fun stopWatchingAp() {
        apReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        apReceiver = null
    }

    private fun hotspotOn() {
        stopWatchingAp()
        Notifications.cancelId(app, ID_HOTSPOT)
        _hotspot.value = HotspotUi(HotspotStates.ON)
        // Sent only inside the encrypted session; never logged.
        reply(HotspotState(hotspotRef, HotspotStates.ON, HotspotStore.ssid(app), HotspotStore.psk(app)?.ifEmpty { null }))
        Log.i("hotspot.on")
    }

    private fun hotspotFailed(reason: String) {
        stopWatchingAp()
        _hotspot.value = HotspotUi(HotspotStates.FAILED, reason)
        reply(HotspotState(hotspotRef, HotspotStates.FAILED, reason = reason))
        Log.i("hotspot.failed", "reason" to reason)
    }

    // ===================== Shelf =====================

    private fun loadShelf() {
        val bytes = Vault.readFile(shelfFile) ?: return
        runCatching { json.decodeFromString(ShelfDisk.serializer(), bytes.toString(Charsets.UTF_8)) }
            .onSuccess { _shelf.value = ShelfState(it.items, it.tombs) }
            .onFailure { Log.w("shelf.load_failed", "error" to it.javaClass.simpleName) }
    }

    private fun saveShelf(s: ShelfState) {
        io.execute {
            val text = json.encodeToString(ShelfDisk.serializer(), ShelfDisk(s.items, s.tombs))
            Vault.writeFile(shelfFile, text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun commit(s: ShelfState, sync: ShelfSync?) {
        val pruned = s.pruned(System.currentTimeMillis())
        _shelf.value = pruned
        saveShelf(pruned)
        if (sync != null) engine.execute { if (supports(Caps6.SHELF)) send(sync) }
    }

    @Synchronized private fun mergeShelf(m: ShelfSync) {
        commit(_shelf.value.merge(m), null)
    }

    @Synchronized fun addText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val item = ShelfState.itemFrom(t, UUID.randomUUID().toString(), "phone", System.currentTimeMillis())
        val next = _shelf.value.add(item)
        commit(next, ShelfSync(next.items.filter { it.id == item.id }))
    }

    fun addFile(uri: Uri) {
        io.execute {
            try {
                val id = UUID.randomUUID().toString()
                val staged = files.stage(uri, System.currentTimeMillis())
                val dir = File(app.filesDir, "shelf/$id").apply { mkdirs() }
                val dest = File(dir, staged.name)
                File(staged.ref!!).copyTo(dest, overwrite = true)
                File(staged.ref!!).delete()
                val f = ShelfFile(staged.name, staged.size, staged.mime, staged.sha256, "phone")
                synchronized(this) {
                    val item = ShelfState.fileItem(f, id, "phone", System.currentTimeMillis())
                    val next = _shelf.value.add(item)
                    commit(next, ShelfSync(next.items.filter { it.id == id }))
                }
            } catch (e: IOException) {
                Log.w("shelf.add_file_failed", "error" to e.message)
            }
        }
    }

    @Synchronized fun remove(id: String): ShelfItem? {
        val item = _shelf.value.items.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        commit(_shelf.value.remove(id, now), ShelfSync(removed = listOf(ShelfTomb(id, now))))
        return item
    }

    @Synchronized fun restore(item: ShelfItem) {
        val next = _shelf.value.restore(item, System.currentTimeMillis())
        commit(next, ShelfSync(next.items.filter { it.id == item.id }))
    }

    @Synchronized fun move(id: String, to: Int) {
        val next = _shelf.value.move(id, to, System.currentTimeMillis())
        commit(next, ShelfSync(next.items.filter { it.id == id }))
    }

    /** The card's primary action. Returns a short confirmation ("Copied", "Opened"). */
    fun primary(item: ShelfItem): String {
        when (item.kind) {
            ShelfKinds.LINK -> open(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl(item.body))))
            ShelfKinds.ADDRESS -> open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("${item.title}, ${item.body}"))))
            ShelfKinds.FILE -> openFile(item)
            else -> {
                copy(ShelfDetect.copyText(item))
                return "Copied"
            }
        }
        return "Opened"
    }

    fun copy(text: String) {
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tether Shelf", text))
    }

    private fun linkUrl(s: String): String = if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "https://$s"

    private fun open(i: Intent) {
        try {
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("shelf.open_failed", "reason" to e.message)
        }
    }

    private fun localFile(item: ShelfItem): File? =
        item.file?.let { File(app.filesDir, "shelf/${item.id}/${it.name}") }?.takeIf { it.exists() }

    private fun openFile(item: ShelfItem) {
        val f = localFile(item)
        if (f != null) {
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
            open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.file?.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        }
        wantedFiles += item.id
        engine.execute { if (supports(Caps6.SHELF)) send(ShelfFileReq(item.id)) }
    }

    private fun serveShelfFile(r: ShelfFileReq) {
        val item = _shelf.value.items.firstOrNull { it.id == r.id } ?: return
        val f = localFile(item) ?: return
        try {
            val staged = files.stage(Uri.fromFile(f), System.currentTimeMillis())
            engine.execute { sendFile(staged.copy(name = item.file?.name ?: staged.name, kind = FileKinds6.SHELF)) }
        } catch (e: IOException) {
            Log.w("shelf.serve_failed", "error" to e.message)
        }
    }

    private fun shelfFileArrived(r: TransferRecord) {
        val src = r.ref?.let(::File)?.takeIf { it.exists() } ?: return
        val item = _shelf.value.items.firstOrNull { it.file?.sha256.equals(r.sha256, true) }
            ?: _shelf.value.items.firstOrNull { it.file?.name == r.name } ?: return
        val dir = File(app.filesDir, "shelf/${item.id}").apply { mkdirs() }
        val dest = File(dir, item.file?.name ?: r.name)
        src.copyTo(dest, overwrite = true)
        src.delete()
        if (wantedFiles.remove(item.id)) main.post { openFile(item) }
    }

    companion object {
        private const val ID_CAPTURE = 7_100
        private const val ID_HOTSPOT = 7_101
        private const val AP_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val AP_ENABLED = 13
    }
}
