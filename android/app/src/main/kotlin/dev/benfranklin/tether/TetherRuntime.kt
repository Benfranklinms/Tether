package dev.benfranklin.tether

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.ble.BleCentral
import dev.benfranklin.tether.call.CallBridge
import dev.benfranklin.tether.clip.ClipWatcher
import dev.benfranklin.tether.core.ControllerListener
import dev.benfranklin.tether.core.LinkState
import dev.benfranklin.tether.core.TetherController
import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.features.AdaptiveBitrate
import dev.benfranklin.tether.core.features.BatteryThrottle
import dev.benfranklin.tether.core.features.FrameSplitter
import dev.benfranklin.tether.core.proto.Battery
import dev.benfranklin.tether.core.proto.CallAction
import dev.benfranklin.tether.core.proto.CallDial
import dev.benfranklin.tether.core.proto.CallLogReq
import dev.benfranklin.tether.core.proto.ContactSearch
import dev.benfranklin.tether.core.proto.Caps4
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Caps3
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.Hello
import dev.benfranklin.tether.core.proto.MediaCmd
import dev.benfranklin.tether.core.proto.NotifAccess
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.Notif
import dev.benfranklin.tether.core.proto.NotifAction
import dev.benfranklin.tether.core.proto.NotifActionResult
import dev.benfranklin.tether.core.proto.NotifDismiss
import dev.benfranklin.tether.core.proto.NotifRemoved
import dev.benfranklin.tether.core.proto.NowPlaying
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.proto.ProximityCfg
import dev.benfranklin.tether.core.proto.Ring
import dev.benfranklin.tether.core.proto.RingState
import dev.benfranklin.tether.core.proto.RingStop
import dev.benfranklin.tether.core.proto.Rssi
import dev.benfranklin.tether.core.proto.ScreenInput
import dev.benfranklin.tether.core.proto.ScreenStart
import dev.benfranklin.tether.core.proto.ScreenState
import dev.benfranklin.tether.core.proto.ScreenStates
import dev.benfranklin.tether.core.proto.ScreenStop
import dev.benfranklin.tether.core.proto.Sims
import dev.benfranklin.tether.core.proto.SmsMessage
import dev.benfranklin.tether.core.proto.SmsMessages
import dev.benfranklin.tether.core.proto.SmsNew
import dev.benfranklin.tether.core.proto.SmsSearch
import dev.benfranklin.tether.core.proto.SmsSend
import dev.benfranklin.tether.core.proto.SmsSendResult
import dev.benfranklin.tether.core.proto.SmsThreadReq
import dev.benfranklin.tether.core.proto.SmsThreads
import dev.benfranklin.tether.core.proto.SmsThreadsReq
import dev.benfranklin.tether.core.proto.VideoFeedback
import dev.benfranklin.tether.core.proto.WifiOffer
import dev.benfranklin.tether.core.session.PairingContext
import dev.benfranklin.tether.core.session.PairingUri
import dev.benfranklin.tether.core.session.PeerRecord
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.ReconnectWatchdog
import dev.benfranklin.tether.core.util.fingerprint
import dev.benfranklin.tether.core.wifi.TcpLink
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.data.FileIdentityStore
import dev.benfranklin.tether.data.SeenAppsRepository
import dev.benfranklin.tether.data.SettingsRepository
import dev.benfranklin.tether.data.TransferRepository
import dev.benfranklin.tether.device.BatteryMonitor
import dev.benfranklin.tether.device.Ringer
import dev.benfranklin.tether.files.AndroidFiles
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.log.LoggedExecutor
import dev.benfranklin.tether.media.MediaWatcher
import dev.benfranklin.tether.media.NowPlayingMonitor
import dev.benfranklin.tether.notify.MirrorListenerService
import dev.benfranklin.tether.notify.NotificationActions
import dev.benfranklin.tether.notify.SensitiveWatch
import dev.benfranklin.tether.screen.ControlService
import dev.benfranklin.tether.screen.ScreenCaptureService
import dev.benfranklin.tether.screen.ScreenConsentActivity
import dev.benfranklin.tether.service.Notifications
import dev.benfranklin.tether.service.TetherService
import dev.benfranklin.tether.sms.SmsBridge
import dev.benfranklin.tether.sms.SmsStore
import dev.benfranklin.tether.sms.StoreException
import dev.benfranklin.tether.v5.Features5
import dev.benfranklin.tether.v5.Updater
import dev.benfranklin.tether.wifi.WifiServer
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the UI renders, as one immutable snapshot. */
data class UiState(
    val link: LinkState = LinkState.Offline,
    val radio: BleCentral.RadioState = BleCentral.RadioState.IDLE,
    val peer: PeerRecord? = null,
    val pairingMode: Boolean = false,
    val pairingQrName: String? = null,
    val lastError: String? = null,
    val fingerprint: String = "",
    val notice: String? = null,
    /** What the Bluetooth side is doing right now, for the pairing status line and failures. */
    val diag: BleCentral.Diag = BleCentral.Diag(),
    /** Features the connected Mac announced (empty offline); a 0.1.x Mac lacks the new ones. */
    val peerCaps: Set<String> = emptySet(),
    val peerApp: String? = null,
    /** The Mac is viewing this phone's screen right now. */
    val screenLive: Boolean = false,
    val ringing: Boolean = false,
)

/** One line saying what pairing is doing right now. Pure; unit-tested. */
fun pairStatus(s: UiState): String {
    val d = s.diag
    return when {
        s.link is LinkState.Handshaking -> "Connected to your Mac · securing the link…"
        s.radio == BleCentral.RadioState.OFF || d.stage == BleCentral.Stage.OFF -> "Bluetooth is off. Turn it on to pair."
        s.radio == BleCentral.RadioState.NO_PERMISSION -> "Allow Nearby devices for Tether to find your Mac."
        d.stage == BleCentral.Stage.CONNECTING ->
            "Found your Mac · connecting" + if (d.connectAttempts > 1) " (try ${d.connectAttempts})" else "…"
        d.stage == BleCentral.Stage.DISCOVERING || d.stage == BleCentral.Stage.SUBSCRIBING -> "Found your Mac · opening the link…"
        d.stage == BleCentral.Stage.CONNECTED -> "Connected to your Mac · securing the link…"
        d.stage == BleCentral.Stage.SCANNING && d.adsSeen == 0 && d.locationOff ->
            "Looking for your Mac… nothing heard yet. If this stays, turn on Location."
        d.stage == BleCentral.Stage.SCANNING -> "Looking for your Mac… ${d.adsSeen} Bluetooth signals heard, no Tether yet"
        else -> "Starting Bluetooth…"
    }
}

/** Why a pairing attempt ran out of time, in words that say what to try. Pure; unit-tested. */
fun pairFailure(d: BleCentral.Diag, link: LinkState): String = when {
    link is LinkState.Handshaking || d.linkUps > 0 ->
        "Your Mac answered but the secure handshake didn't finish. Close and reopen the Mac's pairing window, " +
            "then try again. If it repeats, export diagnostics from both apps."
    d.macSeen ->
        "Found your Mac but couldn't connect" + (d.lastError?.let { " ($it)" } ?: "") +
            ". Turn Bluetooth off and on on both devices, then try again."
    d.adsSeen == 0 ->
        "No Bluetooth signals at all reached this phone." +
            (if (d.locationOff) " Turn on Location (some phones need it to scan)." else "") +
            " Check Nearby devices permission for Tether, then try again."
    else ->
        "Couldn't find your Mac. Keep Tether's pairing window open on the Mac, check its Bluetooth is on and Tether is " +
            "allowed in System Settings › Privacy & Security › Bluetooth, then try again."
}

/**
 * The process-wide owner of the link. One single-threaded [engine] executor runs the
 * protocol controller, the BLE central's callbacks and the Wi-Fi sockets' callbacks, so the
 * protocol never sees concurrent calls. [TetherService] keeps the process in the foreground
 * while a Mac is paired.
 */
@Suppress("TooManyFunctions", "LargeClass")
class TetherRuntime(private val app: Context) : ControllerListener, BleCentral.Listener {
    val engine: ScheduledThreadPoolExecutor = LoggedExecutor("tether-engine")
    val settings = SettingsRepository(app)
    val transfers = TransferRepository(app)
    val seenApps = SeenAppsRepository(app)
    val files = AndroidFiles(app, settings)
    private val identity = FileIdentityStore(app)
    private val main = Handler(Looper.getMainLooper())

    private val state = MutableStateFlow(UiState(peer = identity.peer, fingerprint = fingerprint(identity.localKey.publicKey)))
    val ui: StateFlow<UiState> = state.asStateFlow()

    private val controller = TetherController(
        Role.INITIATOR, identity, ::hello, files, files, this,
    )
    private val central = BleCentral(app, engine, this)
    private val wifi = WifiServer(app, engine, ::onWifiSocket, ::onNetworkChanged)
    private var tcp: TcpLink? = null
    private var tcpAt = 0L
    private var offeredAddrs: List<String> = emptyList()
    /** 0.5.2: keep trying the Wi-Fi fast path while Bluetooth-only (backoff + on every network change). */
    private val fastPath = dev.benfranklin.tether.core.wifi.FastPathRetry()
    private var fastPathTicker: java.util.concurrent.ScheduledFuture<*>? = null
    private var ticker: ScheduledFuture<*>? = null
    private var tickPeriod = 0L
    private var pairing: PairingContext? = null
    private var pairingTimeout: ScheduledFuture<*>? = null
    @Volatile private var lastClipHash: String? = null

    // ---- 0.4.3 clipboard auto mode ----
    val clipWatcher = ClipWatcher(
        app, lastHash = { lastClipHash }, connected = { state.value.link is LinkState.Connected },
        send = { text -> sendClip(text, fromUser = false) },
        sendImage = { uri -> clipboardImageSeen(Uri.parse(uri), force = true) },
    )

    // ---- 0.2.0 features ----
    private val batteryThrottle = BatteryThrottle()
    private val battery = BatteryMonitor(app) { b -> engine.execute { pushBattery(b) } }
    private val ringer = Ringer(app) { on, reason -> engine.execute { ringChanged(on, reason) } }
    private val media = MediaWatcher(app) { rec -> engine.execute { queueMedia(rec) } }
    @Volatile var screenRequest: ScreenStart = ScreenStart()
        private set
    private var screenPending = false
    private var screenLive = false
    private var frameRotation = 0
    private var frameSeq = 0L
    private var abr = AdaptiveBitrate()
    private var lastFeedback: VideoFeedback? = null
    @Volatile private var foreground = false

    // ---- 0.3.0 features ----
    private val sms = SmsBridge(
        app,
        onNew = { m -> engine.execute { pushSms(m) } },
        onSent = { ref, ok, reason -> engine.execute { controller.send(SmsSendResult(ref, ok, reason)) } },
        onSimsChanged = { engine.execute { pushSims() } },
    )
    private val calls = CallBridge(
        app, { settings.current }, engine,
        send = { m -> controller.send(m) }, supports = { c -> controller.peerSupports(c) },
    )
    val sensitive = SensitiveWatch(app)
    private val nowPlaying = NowPlayingMonitor(
        app,
        onState = { np -> engine.execute { pushNowPlaying(np) } },
        onArt = { art -> engine.execute { if (nowPlayingAllowed()) controller.send(art) } },
    )
    private var rssiTask: ScheduledFuture<*>? = null

    // ---- 0.5.0 features ----
    val v5 = Features5(
        app, engine, files, { settings.current },
        send = { m -> controller.send(m) }, sendFile = { r -> controller.sendFile(r) },
        supports = { c -> controller.peerSupports(c) }, connected = { controller.isConnected },
    )
    val v6 = dev.benfranklin.tether.v6.Features6(
        app, engine, files, { settings.current },
        send = { m -> controller.send(m) }, sendFile = { r -> controller.sendFile(r) },
        supports = { c -> controller.peerSupports(c) },
        foreground = { foreground },
        link = { (state.value.link as? LinkState.Connected)?.let { true to it.wifi } ?: (false to false) },
    )
    val updater = Updater(app).also { Updater.instance = it }

    /** 0.5.3 Home panels: the Mac's battery / now playing (revision 7) and recent clipboard texts. */
    val feeds = dev.benfranklin.tether.v7.HomeFeeds(send = { m -> controller.send(m) }, supports = { c -> controller.peerSupports(c) })

    // ---- 0.3.1 reconnect safety net ----
    private val watchdog = ReconnectWatchdog()
    private var radioRestarts = 0
    private var lastLoggedState: String? = null

    /** Screen on / leaving Doze: radios may be fresh again, so allow an early restart. */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            engine.execute {
                Log.i("device.wake", "action" to i.action?.substringAfterLast('.'), "linked" to controller.bleReady)
                watchdog.poke()
                checkWatchdog()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        engine.scheduleWithFixedDelay({ checkWatchdog() }, WATCHDOG_S, WATCHDOG_S, TimeUnit.SECONDS)
        engine.execute {
            controller.restoreTransfers(transfers.saved)
            files.sweep(transfers.saved.map { it.id }.toSet())
        }
        settings.onChanged = { s -> engine.execute { applyFeatures(s) } }
        engine.execute { applyFeatures(settings.current) }
    }

    private fun hello() = Hello(name = deviceName(), platform = "android", app = BuildConfig.VERSION_NAME, caps = Caps.CURRENT)

    private fun deviceName(): String {
        val m = Build.MODEL.orEmpty()
        val brand = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        return if (m.startsWith(brand, ignoreCase = true)) m else "$brand $m".trim()
    }

    // ===================== lifecycle =====================

    /** Called by [TetherService] when it starts: run the radio if paired or pairing. */
    fun startLink() = engine.execute {
        if (identity.peer != null || pairing != null) central.start()
    }

    fun stopLink() = engine.execute {
        central.stop()
        stopWifi()
    }

    /** The Reconnect button: rebuild the Bluetooth side now (what quit + reopen used to fix). */
    fun reconnect() = engine.execute {
        Log.i("ui.reconnect")
        watchdog.reset()
        restartRadio("manual")
    }

    private fun checkWatchdog() {
        val eligible = identity.peer != null && pairing == null && central.radioUsable()
        if (watchdog.observe(SystemClock.elapsedRealtime(), controller.bleReady, eligible, interactive())) restartRadio("watchdog")
    }

    /** Screen on or Tether on screen: the watchdog backoff is capped at 60 s (0.5.4). */
    private fun interactive(): Boolean =
        foreground || (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

    /** Tear down Bluetooth (and a Wi-Fi path that isn't carrying the session) and start over. */
    private fun restartRadio(reason: String) {
        radioRestarts++
        Log.w(
            "radio.restart", "reason" to reason, "attempt" to watchdog.attempts, "total" to radioRestarts,
            "wifi" to controller.wifiReady, "stage" to state.value.diag.stage.name, "nextS" to watchdog.thresholdMs(interactive()) / MS_PER_S,
        )
        if (!controller.wifiReady) stopWifi()
        central.hardRestart(reason)
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        central.setForeground(foreground)
        clipWatcher.onForeground(foreground)
    }

    // ===================== pairing =====================

    /** Enter pairing mode; [qr] when the user scanned the Mac's code. Times out after 2 minutes. */
    fun startPairing(qr: PairingUri?) = engine.execute {
        val ctx = PairingContext(acceptNew = true, expectedRemoteKey = qr?.key, token = qr?.token)
        pairing = ctx
        controller.setPairing(ctx)
        state.update { it.copy(pairingMode = true, pairingQrName = qr?.name, lastError = null) }
        pairingTimeout?.cancel(false)
        pairingTimeout = engine.schedule({
            val s = state.value
            if (controller.state is LinkState.Pairing) return@schedule // codes on screen; the session times out itself
            Log.w("pair.timeout", "diag" to s.diag, "link" to s.link::class.simpleName)
            cancelPairingInternal(pairFailure(s.diag, s.link))
        }, PAIRING_TIMEOUT_S, TimeUnit.SECONDS)
        central.clearAvoided()
        central.resetDiag()
        TetherService.start(app)
        central.start()
        central.setForeground(true)
        // Already connected to something unpaired? Reconnect so the new context applies.
        central.disconnect("pairing")
        Log.i("pair.start", "qr" to (qr != null))
    }

    fun cancelPairing() = engine.execute { cancelPairingInternal(null) }

    private fun cancelPairingInternal(error: String?) {
        if (pairing == null) return
        pairing = null
        controller.setPairing(PairingContext.CLOSED)
        pairingTimeout?.cancel(false)
        if (controller.state is LinkState.Pairing) controller.rejectPairing()
        state.update { it.copy(pairingMode = false, pairingQrName = null, lastError = error) }
        if (identity.peer == null) {
            central.stop()
            TetherService.stop(app)
        }
    }

    fun confirmPairing() = engine.execute { controller.confirmPairing() }
    fun rejectPairing() = engine.execute { controller.rejectPairing() }

    // ===================== actions =====================

    /** Send text to the Mac's clipboard. Returns via [UiState.notice]. */
    fun sendClip(text: String, fromUser: Boolean) = engine.execute {
        if (text.isEmpty()) return@execute
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val ok = controller.send(Clip(UUID.randomUUID().toString(), System.currentTimeMillis(), text))
        lastClipHash = hash(text)
        val notice = when {
            ok -> if (fromUser) "Sent to your Mac" else null
            !controller.isConnected -> "Not connected to your Mac"
            bytes > Protocol.MAX_CLIP_BYTES -> "Too large for Bluetooth. Join the same Wi-Fi to send it."
            else -> "Couldn't send"
        }
        if (notice != null) note(notice)
        Log.i("clip.sent", "ok" to ok, "bytes" to bytes, "user" to fromUser)
        if (ok) {
            v5.linkCopied(text)
            feeds.clip(text, fromMac = false)
        }
    }

    /** The app came to the foreground and the clipboard holds an image: send it if new (0.5). */
    fun clipboardImageSeen(uri: Uri, force: Boolean = false) {
        if (!force && !settings.current.sendClipboardOnOpen) return
        val h = hash(uri.toString())
        engine.execute {
            if (h == lastClipHash || !controller.isConnected) return@execute
            lastClipHash = h
            v5.sendClipImage(uri)
        }
    }

    /** The app came to the foreground and could read the clipboard: send it if new. */
    fun clipboardSeen(text: String?) {
        if (text.isNullOrEmpty() || !settings.current.sendClipboardOnOpen) return
        val h = hash(text)
        engine.execute {
            if (h == lastClipHash || !controller.isConnected) return@execute
            lastClipHash = h
            if (controller.send(Clip(UUID.randomUUID().toString(), System.currentTimeMillis(), text))) feeds.clip(text, fromMac = false)
            Log.i("clip.auto_sent")
        }
    }

    /** Stage and queue files from the share sheet or the picker. */
    fun sendFiles(uris: List<Uri>) {
        Thread({
            for (u in uris) {
                try {
                    val rec = files.stage(u, System.currentTimeMillis())
                    transferSources.remember(rec.id, u)
                    engine.execute { controller.sendFile(rec) }
                } catch (e: IOException) {
                    Log.e("file.stage_failed", e)
                    note("Couldn't read that file")
                } catch (e: SecurityException) {
                    Log.e("file.stage_denied", e)
                    note("Tether wasn't allowed to read that file")
                }
            }
        }, "tether-stage").start()
    }

    fun cancelTransfer(id: String) = engine.execute { controller.cancelTransfer(id) }
    fun retryTransfer(id: String) = engine.execute { controller.retryTransfer(id) }
    fun removeTransfer(id: String) = engine.execute { controller.removeTransfer(id) }

    /** Where sent files came from, so the Transfers screen can open them (0.5.3). */
    val transferSources = dev.benfranklin.tether.files.TransferSources(app)

    /** Clear all (Transfers screen): every finished transfer leaves the list; running ones stay. */
    fun clearFinishedTransfers() = engine.execute {
        val done = transfers.transfers.value.filter { it.status.isFinal }.map { it.id }
        done.forEach { controller.removeTransfer(it) }
        transferSources.retain(transfers.transfers.value.map { it.id }.toSet() - done.toSet())
    }

    fun openTransfer(t: TransferRecord): String? = transferSources.open(t)

    fun unpair() = engine.execute {
        controller.unpair()
        afterUnpair("Unpaired")
    }

    fun rotateKey() = engine.execute {
        if (!controller.rotateKey()) note("Connect to your Mac first") else note("Rotating keys…")
    }

    /** Wi-Fi is usable for big files right now. */
    val wifiReady: Boolean get() = (state.value.link as? LinkState.Connected)?.wifi == true

    // ===================== notifications from the listener =====================

    fun mirror(n: Notif) = engine.execute {
        controller.send(n)
        v5.maybeOtp(n.app, n.title + "\n" + n.text, "notif")
    }

    // ===================== 0.2.0 features =====================

    /** Start/stop the watchers that depend on settings and permissions. Engine thread. */
    private fun applyFeatures(s: AppSettings) {
        if (s.shareBattery && identity.peer != null) battery.start() else battery.stop()
        val paired = identity.peer != null
        media.configure(MediaWatcher.Prefs(paired && s.sendScreenshots, paired && s.sendPhotos))
        if (!s.allowScreen && (screenLive || screenPending)) stopScreen("disabled")
        if (!s.allowRing && ringer.ringing) ringer.stop("disabled")
        // 0.3.0
        sms.watch(paired && s.shareSms)
        calls.apply(paired, s)
        if (paired && s.shareNowPlaying) nowPlaying.start() else nowPlaying.stop()
        if (!s.allowProximity) stopRssi()
        // 0.4.3
        clipWatcher.configure(paired && s.autoClip, s.autoClipSensitive)
        // 0.5.0
        v5.apply(paired, s)
    }

    /** Permissions changed (the app came back from a permission screen). */
    fun permissionsChanged() = engine.execute {
        applyFeatures(settings.current)
        v5.pushStatus()
        calls.permissionsChanged()
        sensitive.recheck()?.let(::pushNotifAccess)
    }

    /** MediaStore changed while the process was asleep (content-trigger job). */
    fun mediaChanged() {
        engine.execute { applyFeatures(settings.current) }
        media.poke()
    }

    private fun pushBattery(b: Battery) {
        if (!settings.current.shareBattery || !controller.peerSupports(Caps.BATTERY)) return
        if (batteryThrottle.offer(b, System.currentTimeMillis())) controller.send(b)
    }

    private fun queueMedia(rec: TransferRecord) {
        if (identity.peer == null || controller.transfers.hasOutgoing(rec.sha256)) {
            files.release(rec)
            return
        }
        controller.sendFile(rec)
    }

    private fun onNotifAction(a: NotifAction) {
        if (!settings.current.notificationActions) {
            controller.send(NotifActionResult(a.key, a.action, false, "disabled"))
            return
        }
        Thread({
            val r = NotificationActions.perform(app, a.key, a.action, a.text)
            engine.execute { controller.send(NotifActionResult(a.key, a.action, r.ok, r.reason)) }
        }, "tether-action").start()
    }

    private fun onRing(r: Ring) {
        if (!settings.current.allowRing) {
            controller.send(RingState(false, "disabled"))
            return
        }
        ringer.start(r.timeoutS)
    }

    private fun ringChanged(on: Boolean, reason: String?) {
        state.update { it.copy(ringing = on) }
        controller.send(RingState(on, reason))
    }

    val ringing: Boolean get() = ringer.ringing

    /** Stop button on the phone's home screen. */
    fun stopScreenSharing() = engine.execute { stopScreen("phone") }

    /** Stop pressed on the phone (notification or full-screen alert). */
    fun stopRingLocally() = ringer.stop("phone")

    private fun onScreenStart(req: ScreenStart) {
        val s = settings.current
        when {
            !s.allowScreen -> controller.send(ScreenState(ScreenStates.DENIED, reason = "disabled"))
            !controller.wifiReady -> controller.send(ScreenState(ScreenStates.NEEDS_WIFI, reason = "bluetooth_only"))
            screenLive -> {
                ScreenCaptureService.active?.requestKeyframe()
                controller.send(liveState())
            }
            else -> {
                screenRequest = req.copy(maxSize = minOf(req.maxSize, s.screenMaxSize).coerceAtLeast(MIN_SCREEN))
                screenPending = true
                abr = AdaptiveBitrate(maxBps = req.bitrate.coerceIn(MIN_BPS, MAX_BPS), startBps = START_BPS)
                controller.send(ScreenState(ScreenStates.CONSENT, control = ControlService.available))
                askScreenConsent()
                Log.i("screen.requested", "max" to screenRequest.maxSize, "fps" to req.fps)
            }
        }
    }

    private fun askScreenConsent() = main.post {
        if (foreground) {
            app.startActivity(ScreenConsentActivity.intent(app))
            return@post
        }
        val n = androidx.core.app.NotificationCompat.Builder(app, Notifications.CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(app.getString(R.string.screen_request_title))
            .setContentText(app.getString(R.string.screen_request_text))
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(ScreenConsentActivity.pending(app))
            .setAutoCancel(true)
            .setTimeoutAfter(SCREEN_REQUEST_TIMEOUT_MS)
            .build()
        Notifications.postRaw(app, Notifications.ID_SCREEN_REQUEST, n)
    }

    fun screenConsentResult(granted: Boolean) = engine.execute {
        if (!granted) {
            screenPending = false
            controller.send(ScreenState(ScreenStates.DENIED, reason = "user"))
            Log.i("screen.denied")
        }
    }

    fun screenBitrate(): Int = abr.bitrate

    private fun liveState() = ScreenState(
        ScreenStates.LIVE, lastW, lastH, frameRotation, control = ControlService.available,
    )

    private var lastW = 0
    private var lastH = 0

    fun screenLive(w: Int, h: Int, rotation: Int) = engine.execute {
        screenPending = false
        screenLive = true
        lastW = w
        lastH = h
        frameRotation = rotation
        state.update { it.copy(screenLive = true) }
        if (!controller.send(liveState())) stopScreen("link")
    }

    fun screenEnded(state: String, reason: String) = engine.execute {
        val was = screenLive || screenPending
        screenLive = false
        screenPending = false
        this.state.update { it.copy(screenLive = false) }
        if (was) controller.send(ScreenState(state, reason = reason))
    }

    private fun stopScreen(reason: String) {
        screenPending = false
        Notifications.cancelId(app, Notifications.ID_SCREEN_REQUEST)
        val svc = ScreenCaptureService.active
        if (svc != null) svc.stopSession(reason) else screenEnded(ScreenStates.STOPPED, reason)
    }

    /** One encoded access unit from the screen encoder (any thread). */
    fun sendVideo(data: ByteArray, key: Boolean, config: Boolean, ptsUs: Long) = engine.execute {
        if (!screenLive && !config) return@execute
        val queued = tcp?.queuedBytes ?: 0L
        val fb = lastFeedback
        abr.update(queued, fb?.lagMs ?: 0, fb?.dropped ?: 0, System.currentTimeMillis())?.let { bps ->
            ScreenCaptureService.active?.setBitrate(bps)
            Log.i("screen.bitrate", "bps" to bps, "queued" to queued)
        }
        lastFeedback = null
        if (abr.shouldDrop(queued, key) && !config) {
            ScreenCaptureService.active?.requestKeyframe()
            return@execute
        }
        val seq = frameSeq++
        for (part in FrameSplitter.split("screen", seq, ptsUs, key, config, data)) {
            if (!controller.send(part)) {
                stopScreen("wifi")
                return@execute
            }
        }
    }

    private fun onVideoFeedback(f: VideoFeedback) {
        lastFeedback = f
        if (f.keyframe) ScreenCaptureService.active?.requestKeyframe()
    }

    private fun onScreenInput(i: ScreenInput) {
        if (!screenLive) return
        val svc = ControlService.current ?: return
        main.post {
            if (!svc.perform(i, frameRotation)) Log.w("control.failed", "kind" to i.k)
        }
    }

    fun mirrorRemoved(key: String) = engine.execute {
        if (settings.current.notifications.dismissSync) controller.send(NotifRemoved(key))
    }

    // ===================== 0.3.0 features =====================

    private fun smsAllowed() = settings.current.shareSms && controller.peerSupports(Caps3.SMS)

    /** Why SMS can't be read right now ("disabled", "sms"), or null. */
    private fun smsBlocker(): String? = when {
        !settings.current.shareSms -> "disabled"
        !sms.store.canRead() -> "sms"
        else -> null
    }

    /**
     * Every SMS request gets exactly one answer, even when the provider throws, stalls or the
     * permission is gone: the Mac shows that reason instead of spinning. Timings and failures
     * land in Export diagnostics as sms.threads / sms.thread / sms.search lines.
     */
    private fun onSmsThreads(r: SmsThreadsReq) {
        val blocker = smsBlocker()
        if (blocker != null) {
            controller.send(SmsThreads(emptyList(), query = r.query, needs = blocker))
            Log.i("sms.threads", "needs" to blocker)
            return
        }
        sms.io.execute {
            val started = SystemClock.elapsedRealtime()
            val reply = try {
                val page = sms.store.threads(r.limit, r.before, r.query)
                val fitted = fit(page.threads) { SmsThreads(it, page.more || it.size < page.threads.size, r.query, page.error) }
                Log.i(
                    "sms.threads", "ms" to SystemClock.elapsedRealtime() - started, "count" to page.threads.size,
                    "path" to page.path, "more" to page.more, "err" to page.error, "paging" to (r.before != null),
                    "limitInOrder" to sms.store.limitInOrder,
                )
                fitted
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                Log.e("sms.threads_failed", t, "ms" to SystemClock.elapsedRealtime() - started)
                SmsThreads(emptyList(), query = r.query, needs = "error:" + t.javaClass.simpleName)
            }
            engine.execute { if (!controller.send(reply)) Log.w("sms.reply_offline", "type" to "threads") }
        }
    }

    private fun onSmsThread(r: SmsThreadReq) {
        val blocker = smsBlocker()
        if (blocker != null) {
            controller.send(SmsMessages(r.thread, emptyList(), error = blocker))
            return
        }
        sms.io.execute {
            val started = SystemClock.elapsedRealtime()
            val reply = try {
                val (msgs, more) = sms.store.messages(r.thread, r.limit, r.before)
                Log.i("sms.thread", "ms" to SystemClock.elapsedRealtime() - started, "count" to msgs.size)
                // Too big for one record: drop the oldest and let the Mac page for them.
                fit(msgs.reversed()) { SmsMessages(r.thread, it.reversed(), more || it.size < msgs.size) }
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                Log.e("sms.thread_failed", t, "ms" to SystemClock.elapsedRealtime() - started)
                SmsMessages(r.thread, emptyList(), error = storeError(t))
            }
            engine.execute { if (!controller.send(reply)) Log.w("sms.reply_offline", "type" to "thread") }
        }
    }

    private fun onSmsSearch(r: SmsSearch) {
        val blocker = smsBlocker()
        if (blocker != null) {
            controller.send(SmsMessages(-1, emptyList(), search = r.query, error = blocker))
            return
        }
        sms.io.execute {
            val started = SystemClock.elapsedRealtime()
            val reply = try {
                val found = sms.store.search(r.query, r.limit)
                Log.i("sms.search", "ms" to SystemClock.elapsedRealtime() - started, "count" to found.size)
                fit(found) { SmsMessages(-1, it, it.size < found.size, search = r.query) }
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                Log.e("sms.search_failed", t)
                SmsMessages(-1, emptyList(), search = r.query, error = storeError(t))
            }
            engine.execute { if (!controller.send(reply)) Log.w("sms.reply_offline", "type" to "search") }
        }
    }

    private fun storeError(t: Throwable): String = when {
        t is StoreException && (t.reason == "sms" || t.reason == SmsStore.DENIED) -> "sms"
        t is StoreException -> "error:" + t.reason
        else -> "error:" + t.javaClass.simpleName
    }

    private fun onSmsSend(r: SmsSend) {
        if (settings.current.shareSms) {
            sms.send(r.ref, r.to, r.body, r.sub)
        } else {
            controller.send(SmsSendResult(r.ref, false, "disabled"))
        }
    }

    private fun pushSms(m: SmsMessage) {
        if (smsAllowed()) controller.send(SmsNew(m))
        if (!m.out) v5.maybeOtp(m.name ?: m.addr, m.body, "sms")
    }

    private fun pushSims() {
        val forCalls = settings.current.allowCalling && controller.peerSupports(Caps4.DIAL)
        if (!smsAllowed() && !forCalls) return
        sms.io.execute {
            val list = sms.sims()
            engine.execute { controller.send(Sims(list)) }
        }
    }

    /** The Mac shows calls itself, so the dialer's call notifications aren't mirrored. */
    val callAlertsActive: Boolean
        get() = calls.alertsActive(state.value.link is LinkState.Connected, state.value.peerCaps)

    /** The dialer's incoming-call notification, for the caller's name when contacts can't be read. */
    fun dialerNotification(title: String?) = calls.dialerTitle(title)

    /** Every posted notification passes here so Android 15+ redaction is noticed (0.4.0). */
    fun sensitiveSeen(title: String?, text: String?) = engine.execute {
        sensitive.onNotification(title, text)?.let(::pushNotifAccess)
    }

    private fun pushNotifAccess(a: NotifAccess) {
        if (controller.peerSupports(Caps4.NOTIF_ACCESS)) controller.send(a)
    }

    private fun nowPlayingAllowed() = settings.current.shareNowPlaying && controller.peerSupports(Caps3.NOW_PLAYING)

    private fun pushNowPlaying(np: NowPlaying) {
        if (nowPlayingAllowed()) controller.send(np)
        v5.nowPlaying(np)
    }

    private fun onMediaCmd(c: MediaCmd) {
        if (!settings.current.shareNowPlaying) return
        main.post {
            val reason = nowPlaying.command(c)
            if (reason != null) Log.i("media.cmd_failed", "cmd" to c.cmd, "reason" to reason)
        }
    }

    private fun onProximityCfg(c: ProximityCfg) {
        stopRssi()
        if (!c.on || !settings.current.allowProximity) return
        val period = c.intervalMs.toLong().coerceIn(RSSI_MIN_MS, RSSI_MAX_MS)
        rssiTask = engine.scheduleWithFixedDelay({ central.readRssi() }, 0, period, TimeUnit.MILLISECONDS)
        Log.i("proximity.on", "ms" to period)
    }

    private fun stopRssi() {
        rssiTask?.cancel(false)
        rssiTask = null
    }

    /** Largest list prefix whose message fits comfortably in one record. */
    private fun <T> fit(items: List<T>, build: (List<T>) -> Message): Message {
        var n = items.size
        var m = build(items)
        while (n > 1 && MessageCodec.encode(m).size > MAX_LIST_BYTES) {
            n -= maxOf(1, n / FIT_STEP)
            m = build(items.take(n))
        }
        return m
    }

    // ===================== ControllerListener =====================

    override fun onState(state: LinkState) {
        val wasConnectedForShelf = this.state.value.link is LinkState.Connected
        val label = when (state) {
            is LinkState.Connected -> if (state.wifi) "connected-wifi" else "connected"
            is LinkState.Pairing -> "pairing"
            LinkState.Handshaking -> "handshaking"
            LinkState.Offline -> "offline"
        }
        if (label != lastLoggedState) {
            Log.i("link.state", "from" to lastLoggedState, "to" to label, "retries" to controller.bleHandshakeRetries)
            lastLoggedState = label
        }
        this.state.update {
            it.copy(link = state, peer = identity.peer, peerCaps = controller.peerCaps, peerApp = controller.peerApp)
        }
        if (state is LinkState.Connected) {
            batteryThrottle.reset()
            battery.snapshot()?.let(::pushBattery)
            media.poke()
            pushSims()
            nowPlaying.resend()
            calls.connected()
            v5.linkUp()
            if (SensitiveWatch.applies) {
                sensitive.recheck()
                pushNotifAccess(sensitive.status())
            }
        } else {
            stopRssi()
            feeds.linkDown()
            if (screenLive || screenPending) stopScreen("link")
            if (wasConnectedForShelf) v6.linkChanged()
        }
        retick(state)
        if (state is LinkState.Connected) {
            this.state.update { it.copy(lastError = null) }
            if (!wasConnectedForShelf) v6.linkUp() else v6.linkChanged()
            if (!state.wifi) {
                val now = SystemClock.elapsedRealtime()
                if (fastPath.nextAt == null) {
                    fastPath.linkUp(now)
                    fastPath.attempted(now)
                }
            } else {
                fastPath.connected()
            }
            maybeOfferWifi()
        }
        Notifications.updateOngoing(app, this.state.value)
    }

    override fun onUndecodable(type: Int, reason: String) {
        Log.w("msg.decode_failed", "type" to type, "reason" to reason.take(DECODE_REASON_MAX))
    }

    override fun onMessage(message: Message) {
        when (message) {
            is Clip -> onClip(message)
            is NotifDismiss -> if (settings.current.notifications.dismissSync) MirrorListenerService.dismiss(message.key)
            is NotifAction -> onNotifAction(message)
            is Ring -> onRing(message)
            is RingStop -> ringer.stop("mac")
            is ScreenStart -> onScreenStart(message)
            is ScreenStop -> stopScreen("mac")
            is ScreenInput -> onScreenInput(message)
            is VideoFeedback -> onVideoFeedback(message)
            is SmsThreadsReq -> onSmsThreads(message)
            is SmsThreadReq -> onSmsThread(message)
            is SmsSearch -> onSmsSearch(message)
            is SmsSend -> onSmsSend(message)
            is CallAction, is CallDial, is CallLogReq, is ContactSearch -> calls.handle(message)
            is MediaCmd -> onMediaCmd(message)
            is ProximityCfg -> onProximityCfg(message)
            else -> if (!v5.handle(message) && !v6.handle(message) && !feeds.handle(message)) {
                Log.i("msg.ignored", "type" to message.type)
            }
        }
    }

    private fun onClip(c: Clip) {
        if (!settings.current.receiveClipboard) return
        lastClipHash = hash(c.text)
        feeds.clip(c.text, fromMac = true)
        clipWatcher.onLocalWrite(c.text)
        main.post {
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return@post
            val clip = ClipData.newPlainText("Tether", c.text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
            }
            cm.setPrimaryClip(clip)
        }
        Log.i("clip.received", "bytes" to c.text.length)
    }

    override fun onTransfer(record: TransferRecord, important: Boolean) {
        if (v6.hides(record)) {
            if (important && record.status.isFinal) {
                v6.transferDone(record)
                engine.execute { controller.removeTransfer(record.id) }
            } else if (!record.status.isFinal) {
                v6.transferProgress(record)
            }
            return
        }
        if (v5.hides(record)) {
            // Clipboard images are not downloads: no rows, no notifications.
            if (important && record.status.isFinal) {
                v5.transferDone(record)
                engine.execute { controller.removeTransfer(record.id) }
            }
            return
        }
        transfers.upsert(record, important)
        if (important) {
            val activeBle = transfers.saved.any { it.status == TransferStatus.ACTIVE && it.via == Transport.BLE }
            central.setHighThroughput(activeBle)
            Notifications.transfer(app, record)
            if (record.direction == Direction.IN && record.status == TransferStatus.DONE && settings.current.notifyOnReceive) {
                Notifications.received(app, record)
            }
        }
    }

    override fun onTransferRemoved(id: String) {
        transfers.remove(id)
        Notifications.cancelTransfer(app, id)
    }

    override fun onPaired(peer: PeerRecord) {
        pairing = null
        controller.setPairing(PairingContext.CLOSED)
        pairingTimeout?.cancel(false)
        state.update { it.copy(peer = peer, pairingMode = false, pairingQrName = null) }
        settings.update { it.copy(onboardingDone = true) }
        applyFeatures(settings.current)
        Log.i("pair.done", "peer" to fingerprint(peer.keyBytes))
    }

    override fun onUnpaired(byRemote: Boolean) {
        if (byRemote) afterUnpair("Your Mac unpaired this phone")
    }

    private fun afterUnpair(msg: String) {
        stopWifi()
        applyFeatures(settings.current)
        state.update { it.copy(peer = null, link = LinkState.Offline, notice = msg) }
        central.stop()
        TetherService.stop(app)
    }

    override fun onClosed(reason: CloseReason, byRemote: Boolean, wifi: Boolean) {
        Log.i("link.closed", "reason" to reason.name, "remote" to byRemote, "wifi" to wifi)
        if (wifi) {
            if (screenLive || screenPending) stopScreen("wifi")
            tcp?.close()
            tcp = null
            offeredAddrs = emptyList()
            fastPath.wifiLost(SystemClock.elapsedRealtime())
            engine.schedule({ maybeOfferWifi() }, WIFI_RETRY_S, TimeUnit.SECONDS)
            return
        }
        val msg = when (reason) {
            CloseReason.UNTRUSTED -> if (pairing != null) {
                "Open Tether on your Mac and choose Pair a phone"
            } else {
                central.avoidCurrent(AVOID_MINUTES)
                "A Mac nearby isn't paired with this phone"
            }
            CloseReason.WRONG_DEVICE -> {
                central.avoidCurrent(AVOID_MINUTES)
                "That's a different Mac than the code you scanned"
            }
            CloseReason.PAIRING_REJECTED -> "Pairing was declined"
            CloseReason.VERSION_MISMATCH -> "Update Tether on both devices"
            CloseReason.TIMEOUT -> "Your Mac stopped responding"
            else -> null
        }
        if (msg != null) state.update { it.copy(lastError = msg) }
        central.disconnect(reason.name)
    }

    override fun onWifiOffer(offer: WifiOffer) = Unit // the phone makes offers, it never receives them

    override fun onKeyRotated() {
        note("New keys in use")
        central.disconnect("key rotated")
        state.update { it.copy(fingerprint = fingerprint(identity.localKey.publicKey)) }
    }

    // ===================== BLE =====================

    override fun onLinkUp(maxFragment: Int) {
        controller.bleConnected(maxFragment, central::write, pairing ?: PairingContext.CLOSED)
    }

    override fun onFragment(fragment: ByteArray) = controller.bleFragment(fragment)

    override fun onLinkDown(reason: String) {
        controller.bleDisconnected()
        Log.i("ble.link_down", "reason" to reason)
    }

    override fun onRssi(rssi: Int) {
        if (rssiTask != null) controller.send(Rssi(rssi, System.currentTimeMillis()))
    }

    override fun onDiag(diag: BleCentral.Diag) {
        state.update { it.copy(diag = diag) }
    }

    override fun onRadioState(state: BleCentral.RadioState) {
        this.state.update { it.copy(radio = state) }
        Notifications.updateOngoing(app, this.state.value)
    }

    // ===================== Wi-Fi =====================

    private fun maybeOfferWifi() {
        if (!controller.bleReady || controller.wifiReady) return
        if (!settings.current.wifiFastPath) return stopWifi()
        wifi.ensureWatching()
        ensureFastPathTicker()
        val addrs = WifiServer.lanAddresses()
        if (addrs.isEmpty()) {
            if (offeredAddrs.isNotEmpty()) controller.revokeWifi()
            offeredAddrs = emptyList()
            return
        }
        wifi.start(wifiServiceId())
        if (!wifi.isRunning) return
        // A fresh offer (new one-time token) every time: the Mac retries it right away, also
        // when it just joined this phone's hotspot and mDNS isn't relayed there.
        if (controller.offerWifi(addrs, wifi.port)) {
            offeredAddrs = addrs
            Log.i("wifi.offered", "count" to addrs.size, "attempt" to fastPath.attempts)
        }
    }

    private fun onNetworkChanged() {
        watchdog.poke()
        val addrs = WifiServer.lanAddresses()
        if (controller.wifiReady && tcp != null && addrs.isNotEmpty()) return // live session survives
        if (addrs == offeredAddrs && fastPath.nextAt != null) {
            fastPath.networkChanged(SystemClock.elapsedRealtime())
            return
        }
        Log.i("wifi.network_changed", "count" to addrs.size)
        offeredAddrs = emptyList()
        fastPath.networkChanged(SystemClock.elapsedRealtime())
        if (addrs.isEmpty()) controller.revokeWifi()
    }

    /** Every 2 s while connected: notice address changes nobody broadcast, and retry the upgrade when due. */
    private fun ensureFastPathTicker() {
        if (fastPathTicker != null) return
        fastPathTicker = engine.scheduleWithFixedDelay({ fastPathTick() }, FAST_PATH_TICK_S, FAST_PATH_TICK_S, TimeUnit.SECONDS)
    }

    private fun fastPathTick() {
        val now = SystemClock.elapsedRealtime()
        if (!controller.bleReady) {
            fastPath.linkDown()
            return
        }
        if (controller.wifiReady) {
            fastPath.connected()
            return
        }
        if (fastPath.nextAt == null) fastPath.linkUp(now)
        val addrs = WifiServer.lanAddresses()
        if (addrs != offeredAddrs && addrs.isNotEmpty() && offeredAddrs.isNotEmpty()) fastPath.networkChanged(now)
        // A TCP session is mid-handshake; a new token would void it.
        if (tcp != null && now - tcpAt < TCP_HANDSHAKE_MS) return
        if (fastPath.due(now, controller.bleReady, controller.wifiReady)) {
            fastPath.attempted(now)
            maybeOfferWifi()
        }
    }

    private fun onWifiSocket(socket: Socket) {
        tcp?.close()
        val link = TcpLink(socket, engine, controller::wifiRecord) { _ -> controller.wifiDisconnected() }
        tcp = link
        tcpAt = SystemClock.elapsedRealtime()
        controller.wifiConnected(link::write, null)
        link.start()
        Log.i("wifi.accepted")
    }

    private fun stopWifi() {
        controller.wifiDisconnected()
        tcp?.close()
        tcp = null
        wifi.stop()
        offeredAddrs = emptyList()
    }

    private fun wifiServiceId(): String =
        B64.encode(MessageDigest.getInstance("SHA-256").digest(identity.localKey.publicKey).copyOf(WIFI_ID_BYTES))

    // ===================== misc =====================

    private fun retick(s: LinkState) {
        val period = when (s) {
            is LinkState.Handshaking, is LinkState.Pairing -> FAST_TICK_S
            is LinkState.Connected -> IDLE_TICK_S
            LinkState.Offline -> 0L
        }
        if (period == tickPeriod) return
        tickPeriod = period
        ticker?.cancel(false)
        ticker = if (period == 0L) null else engine.scheduleWithFixedDelay({ controller.tick() }, period, period, TimeUnit.SECONDS)
    }

    fun note(msg: String) = state.update { it.copy(notice = msg) }

    fun clearNotice() = state.update { it.copy(notice = null) }

    fun clearError() = state.update { it.copy(lastError = null) }

    private fun hash(s: String): String =
        B64.encode(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    companion object {
        private const val MAX_LIST_BYTES = 60 * 1024
        private const val DECODE_REASON_MAX = 160
        private const val WATCHDOG_S = 10L
        private const val MS_PER_S = 1000L
        private const val FIT_STEP = 4
        private const val RSSI_MIN_MS = 1000L
        private const val RSSI_MAX_MS = 10_000L
        private const val AVOID_MINUTES = 10L
        private const val PAIRING_TIMEOUT_S = 90L
        private const val WIFI_RETRY_S = 10L
        private const val FAST_PATH_TICK_S = 2L
        private const val TCP_HANDSHAKE_MS = 10_000L
        private const val WIFI_ID_BYTES = 8
        private const val FAST_TICK_S = 1L
        private const val IDLE_TICK_S = 5L
        private const val MIN_SCREEN = 480
        private const val MIN_BPS = 1_000_000
        private const val MAX_BPS = 12_000_000
        private const val START_BPS = 4_000_000
        private const val SCREEN_REQUEST_TIMEOUT_MS = 60_000L
    }
}
