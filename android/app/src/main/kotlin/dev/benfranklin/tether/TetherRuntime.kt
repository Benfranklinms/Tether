package dev.benfranklin.tether

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import dev.benfranklin.tether.ble.BleCentral
import dev.benfranklin.tether.core.ControllerListener
import dev.benfranklin.tether.core.LinkState
import dev.benfranklin.tether.core.TetherController
import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.Hello
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.Notif
import dev.benfranklin.tether.core.proto.NotifDismiss
import dev.benfranklin.tether.core.proto.NotifRemoved
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.proto.WifiOffer
import dev.benfranklin.tether.core.session.PairingContext
import dev.benfranklin.tether.core.session.PairingUri
import dev.benfranklin.tether.core.session.PeerRecord
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.fingerprint
import dev.benfranklin.tether.core.wifi.TcpLink
import dev.benfranklin.tether.data.FileIdentityStore
import dev.benfranklin.tether.data.SeenAppsRepository
import dev.benfranklin.tether.data.SettingsRepository
import dev.benfranklin.tether.data.TransferRepository
import dev.benfranklin.tether.files.AndroidFiles
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.notify.MirrorListenerService
import dev.benfranklin.tether.service.Notifications
import dev.benfranklin.tether.service.TetherService
import dev.benfranklin.tether.wifi.WifiServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
    val engine = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "tether-engine").apply { isDaemon = true } }
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
    private var offeredAddrs: List<String> = emptyList()
    private var ticker: ScheduledFuture<*>? = null
    private var tickPeriod = 0L
    private var pairing: PairingContext? = null
    private var pairingTimeout: ScheduledFuture<*>? = null
    private var lastClipHash: String? = null

    init {
        engine.execute {
            controller.restoreTransfers(transfers.saved)
            files.sweep(transfers.saved.map { it.id }.toSet())
        }
    }

    private fun hello() = Hello(name = deviceName(), platform = "android", app = BuildConfig.VERSION_NAME)

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

    fun setForeground(foreground: Boolean) = central.setForeground(foreground)

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
    }

    /** The app came to the foreground and could read the clipboard: send it if new. */
    fun clipboardSeen(text: String?) {
        if (text.isNullOrEmpty() || !settings.current.sendClipboardOnOpen) return
        val h = hash(text)
        engine.execute {
            if (h == lastClipHash || !controller.isConnected) return@execute
            lastClipHash = h
            controller.send(Clip(UUID.randomUUID().toString(), System.currentTimeMillis(), text))
            Log.i("clip.auto_sent")
        }
    }

    /** Stage and queue files from the share sheet or the picker. */
    fun sendFiles(uris: List<Uri>) {
        Thread({
            for (u in uris) {
                try {
                    val rec = files.stage(u, System.currentTimeMillis())
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

    fun mirror(n: Notif) = engine.execute { controller.send(n) }

    fun mirrorRemoved(key: String) = engine.execute {
        if (settings.current.notifications.dismissSync) controller.send(NotifRemoved(key))
    }

    // ===================== ControllerListener =====================

    override fun onState(state: LinkState) {
        this.state.update { it.copy(link = state, peer = identity.peer) }
        retick(state)
        if (state is LinkState.Connected) {
            this.state.update { it.copy(lastError = null) }
            maybeOfferWifi()
        }
        Notifications.updateOngoing(app, this.state.value)
    }

    override fun onMessage(message: Message) {
        when (message) {
            is Clip -> onClip(message)
            is NotifDismiss -> if (settings.current.notifications.dismissSync) MirrorListenerService.dismiss(message.key)
            else -> Log.i("msg.ignored", "type" to message.type)
        }
    }

    private fun onClip(c: Clip) {
        if (!settings.current.receiveClipboard) return
        lastClipHash = hash(c.text)
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
        Log.i("pair.done", "peer" to fingerprint(peer.keyBytes))
    }

    override fun onUnpaired(byRemote: Boolean) {
        if (byRemote) afterUnpair("Your Mac unpaired this phone")
    }

    private fun afterUnpair(msg: String) {
        stopWifi()
        state.update { it.copy(peer = null, link = LinkState.Offline, notice = msg) }
        central.stop()
        TetherService.stop(app)
    }

    override fun onClosed(reason: CloseReason, byRemote: Boolean, wifi: Boolean) {
        Log.i("link.closed", "reason" to reason.name, "remote" to byRemote, "wifi" to wifi)
        if (wifi) {
            tcp?.close()
            tcp = null
            offeredAddrs = emptyList()
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
        val addrs = WifiServer.lanAddresses()
        if (addrs.isEmpty()) {
            if (offeredAddrs.isNotEmpty()) controller.revokeWifi()
            offeredAddrs = emptyList()
            return
        }
        wifi.start(wifiServiceId())
        if (!wifi.isRunning) return
        if (controller.offerWifi(addrs, wifi.port)) {
            offeredAddrs = addrs
            Log.i("wifi.offered", "count" to addrs.size)
        }
    }

    private fun onNetworkChanged() {
        val addrs = WifiServer.lanAddresses()
        if (addrs == offeredAddrs) return
        if (controller.wifiReady && tcp != null && addrs.isNotEmpty()) return // live session survives
        offeredAddrs = emptyList()
        if (addrs.isEmpty()) controller.revokeWifi() else maybeOfferWifi()
    }

    private fun onWifiSocket(socket: Socket) {
        tcp?.close()
        val link = TcpLink(socket, engine, controller::wifiRecord) { _ -> controller.wifiDisconnected() }
        tcp = link
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
        private const val AVOID_MINUTES = 10L
        private const val PAIRING_TIMEOUT_S = 90L
        private const val WIFI_RETRY_S = 10L
        private const val WIFI_ID_BYTES = 8
        private const val FAST_TICK_S = 1L
        private const val IDLE_TICK_S = 5L
    }
}
