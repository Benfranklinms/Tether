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
import dev.benfranklin.tether.core.features.CapturePolicy
import dev.benfranklin.tether.core.features.FileKinds
import dev.benfranklin.tether.core.features.ShelfCache
import dev.benfranklin.tether.core.features.ShelfRename
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
    /** Tether is in front (an activity start needs no exemption then). */
    private val foreground: () -> Boolean = { false },
    /** Linked to the Mac, and whether over the Wi-Fi fast path (Shelf files are fetched in the background). */
    private val link: () -> Pair<Boolean, Boolean> = { false to false },
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
    /** What to do with a Shelf file once its bytes arrive (the user asked before it was here). */
    private enum class Want { OPEN, COPY, SHARE }
    private val wantedFiles = HashMap<String, Want>()
    val shelfFiles = ShelfFiles(app)
    /** Ids with a local (encrypted) copy, and ids being fetched right now (with when the request went out). */
    private val _localFiles = MutableStateFlow<Set<String>>(emptySet())
    val localFiles: StateFlow<Set<String>> = _localFiles.asStateFlow()
    private val inFlight = HashMap<String, Long>()
    private val _fetching = MutableStateFlow<Set<String>>(emptySet())
    val fetching: StateFlow<Set<String>> = _fetching.asStateFlow()
    private val launcher = CaptureLauncher(app, foreground)
    /** The open Insert from Phone request: its ref and when its screen actually opened (null = not yet). */
    private var captureOpenedAt: Long? = null
    private var hotspotRef: String? = null
    private var apReceiver: BroadcastReceiver? = null

    init {
        io.execute {
            shelfFiles.clearOpened()
            loadShelf()
            shelfFiles.migrate()
            shelfFiles.prune(_shelf.value.items.mapNotNull { i -> i.id.takeIf { i.file != null } }.toSet())
            refreshLocal()
        }
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

    /** Link up: send our whole shelf (the Mac does the same; both merge), then fetch missing files. */
    fun linkUp() {
        if (supports(Caps6.SHELF)) send(_shelf.value.snapshot())
        io.execute { syncFiles() }
    }

    /** The link changed (e.g. the Wi-Fi fast path came up): larger missing files can be fetched now. */
    fun linkChanged() {
        val (connected, _) = link()
        if (!connected) {
            synchronized(inFlight) { inFlight.clear() }
            _fetching.value = emptySet()
        }
        io.execute { syncFiles() }
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
        val now = System.currentTimeMillis()
        val current = _capture.value
        if (current != null && CapturePolicy.busy(captureOpenedAt, now)) {
            return reply(CaptureResult(r.ref, CaptureStates.FAILED, reason = CaptureReasons.BUSY))
        }
        // A request whose screen never opened (or is stale) is replaced, never "busy" forever.
        if (current != null) reply(CaptureResult(current.ref, CaptureStates.FAILED, reason = CaptureReasons.CANCELLED))
        _capture.value = CaptureUi(r.ref, r.kind)
        captureOpenedAt = null
        launcher.launch(r.ref, r.kind) {
            reply(CaptureResult(r.ref, CaptureStates.OPEN, reason = CaptureReasons.TAP_PHONE))
        }
    }

    /** CaptureActivity for [ref] is on screen (called from its onCreate / onNewIntent). */
    fun captureOpened(ref: String) {
        main.post {
            if (_capture.value?.ref != ref) return@post
            captureOpenedAt = System.currentTimeMillis()
            launcher.confirmed(ref)
            reply(CaptureResult(ref, CaptureStates.OPEN))
            Log.i("capture.opened")
        }
    }

    /** From CaptureActivity: the picture / scan ([file], [pages]) or null when cancelled. */
    fun captureDone(ref: String, kind: String, file: File?, pages: Int, error: String? = null) {
        if (_capture.value?.ref == ref) {
            _capture.value = null
            captureOpenedAt = null
            launcher.cancel()
        }
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
        val before = _shelf.value
        val pruned = s.pruned(System.currentTimeMillis())
        _shelf.value = pruned
        saveShelf(pruned)
        if (sync != null) engine.execute { if (supports(Caps6.SHELF)) send(sync) }
        // Items that went away take their file copies with them.
        val live = pruned.items.map { it.id }.toSet()
        before.items.filter { it.file != null && it.id !in live }.forEach { gone -> io.execute { shelfFiles.remove(gone.id) } }
        io.execute {
            refreshLocal()
            syncFiles()
        }
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
                val plain = File(staged.ref!!)
                // The local copy is encrypted at rest right away; the staged plain copy goes.
                shelfFiles.ingest(id, plain, move = true)
                val f = ShelfFile(staged.name, staged.size, staged.mime, staged.sha256, "phone")
                synchronized(this) {
                    val item = ShelfState.fileItem(f, id, "phone", System.currentTimeMillis())
                    val next = _shelf.value.add(item)
                    commit(next, ShelfSync(next.items.filter { it.id == id }))
                }
            } catch (e: IOException) {
                Log.w("shelf.add_file_failed", "error" to e.message)
            } catch (e: SecurityException) {
                Log.w("shelf.add_file_denied", "error" to e.message)
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

    /** Rename (0.5.3): the title, and for files the name used when opening / copying. Synced; false when nothing changed. */
    @Synchronized fun rename(id: String, title: String): Boolean {
        val item = _shelf.value.items.firstOrNull { it.id == id } ?: return false
        val renamed = ShelfRename.rename(item, title, System.currentTimeMillis()) ?: return false
        commit(_shelf.value.update(renamed), ShelfSync(listOf(renamed)))
        Log.i("shelf.renamed", "file" to (item.file != null))
        return true
    }

    /** The card's primary action. Returns a short confirmation ("Copied", "Opened"). */
    fun primary(item: ShelfItem): String {
        when (item.kind) {
            ShelfKinds.LINK -> open(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl(item.body))))
            ShelfKinds.ADDRESS -> open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("${item.title}, ${item.body}"))))
            ShelfKinds.FILE -> return withFile(item, Want.OPEN)
            else -> {
                copy(ShelfDetect.copyText(item))
                return "Copied"
            }
        }
        return "Opened"
    }

    /**
     * Copy (0.5.3): a FILE item puts the file itself on the clipboard (a FileProvider content URI,
     * so a photo pastes as a photo); everything else copies its text.
     */
    fun copyItem(item: ShelfItem): String {
        if (item.kind != ShelfKinds.FILE) {
            copy(ShelfDetect.copyText(item))
            return "Copied"
        }
        return withFile(item, Want.COPY)
    }

    fun share(item: ShelfItem): String = if (item.kind == ShelfKinds.FILE) {
        withFile(item, Want.SHARE)
    } else {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ShelfDetect.copyText(item))
        open(Intent.createChooser(send, null))
        "Shared"
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
            Notifications.alert(app, "No app can open this", "Install an app for this kind of file, then try again")
        }
    }

    /** Runs [want] on the local copy, or fetches it first. Returns the button's confirmation text. */
    private fun withFile(item: ShelfItem, want: Want): String {
        if (shelfFiles.has(item.id)) {
            io.execute { act(item, want) }
            return when (want) {
                Want.OPEN -> "Opened"
                Want.COPY -> "Copied"
                Want.SHARE -> "Shared"
            }
        }
        val (connected, _) = link()
        if (!connected || !supports(Caps6.SHELF)) {
            main.post {
                android.widget.Toast.makeText(app, "Not downloaded yet · connect your Mac", android.widget.Toast.LENGTH_SHORT).show()
            }
            return "Not downloaded"
        }
        synchronized(wantedFiles) { wantedFiles[item.id] = want }
        request(item, force = true)
        return "Getting…"
    }

    private fun act(item: ShelfItem, want: Want) {
        val f = shelfFiles.materialize(item) ?: return Log.w("shelf.materialize_failed")
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        val mime = item.file?.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: app.contentResolver.getType(uri) ?: "*/*"
        main.post {
            when (want) {
                Want.OPEN -> open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                Want.SHARE -> open(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        null,
                    ),
                )
                Want.COPY -> {
                    val clip = ClipData.newUri(app.contentResolver, f.name, uri)
                    app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                    Log.i("shelf.copied_file", "image" to FileKinds.isImage(item.file?.mime, item.file?.name))
                }
            }
        }
    }

    // ----- background copies of Shelf files (0.5.3) -----

    private fun refreshLocal() {
        _localFiles.value = _shelf.value.items.filter { it.file != null && shelfFiles.has(it.id) }.map { it.id }.toSet()
    }

    /** Ask the Mac for every Shelf file we don't have yet (≤100 MB; over Bluetooth only small ones). */
    private fun syncFiles() {
        val (connected, wifi) = link()
        if (!connected || !supports(Caps6.SHELF)) return
        val now = System.currentTimeMillis()
        synchronized(inFlight) { inFlight.entries.removeAll { now - it.value > FETCH_TIMEOUT_MS } }
        for (item in _shelf.value.items) {
            val f = item.file ?: continue
            val busy = synchronized(inFlight) { item.id in inFlight }
            if (ShelfCache.shouldFetch(f, shelfFiles.has(item.id), busy, connected, wifi)) request(item, force = false)
        }
    }

    private fun request(item: ShelfItem, force: Boolean) {
        synchronized(inFlight) {
            if (!force && item.id in inFlight) return
            inFlight[item.id] = System.currentTimeMillis()
            _fetching.value = inFlight.keys.toSet()
        }
        engine.execute { if (supports(Caps6.SHELF)) send(ShelfFileReq(item.id)) }
        Log.i("shelf.fetch", "bytes" to (item.file?.size ?: 0), "wanted" to force)
    }

    /** The Mac asked for a file: any device holding a copy serves it (not just the one that added it). */
    private fun serveShelfFile(r: ShelfFileReq) {
        val item = _shelf.value.items.firstOrNull { it.id == r.id } ?: return
        val plain = shelfFiles.materialize(item) ?: return Log.i("shelf.serve_missing")
        try {
            val staged = files.stage(Uri.fromFile(plain), System.currentTimeMillis())
            engine.execute { sendFile(staged.copy(name = item.file?.name ?: staged.name, kind = FileKinds6.SHELF)) }
        } catch (e: IOException) {
            Log.w("shelf.serve_failed", "error" to e.message)
        } finally {
            plain.parentFile?.deleteRecursively()
        }
    }

    private fun shelfFileArrived(r: TransferRecord) {
        val src = r.ref?.let(::File)?.takeIf { it.exists() } ?: return
        val item = _shelf.value.items.firstOrNull { it.file?.sha256.equals(r.sha256, true) }
            ?: _shelf.value.items.firstOrNull { it.file?.name == r.name }
        if (item == null) {
            src.delete()
            return
        }
        try {
            shelfFiles.ingest(item.id, src, move = true)
        } catch (e: IOException) {
            Log.w("shelf.store_failed", "error" to e.message)
            src.delete()
            return
        }
        synchronized(inFlight) {
            inFlight.remove(item.id)
            _fetching.value = inFlight.keys.toSet()
        }
        refreshLocal()
        val want = synchronized(wantedFiles) { wantedFiles.remove(item.id) }
        if (want != null) act(item, want)
    }

    companion object {
        private const val FETCH_TIMEOUT_MS = 10 * 60_000L
        private const val ID_HOTSPOT = 7_101
        private const val AP_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val AP_ENABLED = 13
    }
}
