package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.KeyPair
import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.link.FragmentException
import dev.benfranklin.tether.core.link.Fragmentation
import dev.benfranklin.tether.core.link.Fragmenter
import dev.benfranklin.tether.core.link.Reassembler
import dev.benfranklin.tether.core.proto.Albums
import dev.benfranklin.tether.core.proto.CallLog
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.Contacts
import dev.benfranklin.tether.core.proto.ContinueCard
import dev.benfranklin.tether.core.proto.FsList
import dev.benfranklin.tether.core.proto.Hello
import dev.benfranklin.tether.core.proto.KeyRotate
import dev.benfranklin.tether.core.proto.KeyRotateAck
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.NowPlayingArt
import dev.benfranklin.tether.core.proto.PhoneStatus
import dev.benfranklin.tether.core.proto.Photos
import dev.benfranklin.tether.core.proto.Ping
import dev.benfranklin.tether.core.proto.Pong
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.proto.RecordKind
import dev.benfranklin.tether.core.proto.ScreenInput
import dev.benfranklin.tether.core.proto.ScreenStart
import dev.benfranklin.tether.core.proto.ScreenState
import dev.benfranklin.tether.core.proto.ScreenStop
import dev.benfranklin.tether.core.proto.ShelfSync
import dev.benfranklin.tether.core.proto.SmsMessages
import dev.benfranklin.tether.core.proto.SmsThreads
import dev.benfranklin.tether.core.proto.Thumb
import dev.benfranklin.tether.core.proto.TypeText
import dev.benfranklin.tether.core.proto.Unpair
import dev.benfranklin.tether.core.proto.VideoFeedback
import dev.benfranklin.tether.core.proto.VideoFrame
import dev.benfranklin.tether.core.proto.WifiBind
import dev.benfranklin.tether.core.proto.WifiOffer
import dev.benfranklin.tether.core.proto.WifiRevoke
import dev.benfranklin.tether.core.session.PairingContext
import dev.benfranklin.tether.core.session.PeerRecord
import dev.benfranklin.tether.core.session.Session
import dev.benfranklin.tether.core.session.SessionListener
import dev.benfranklin.tether.core.session.SessionState
import dev.benfranklin.tether.core.transfer.Channel
import dev.benfranklin.tether.core.transfer.IncomingFiles
import dev.benfranklin.tether.core.transfer.OutgoingFiles
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.core.transfer.TransferListener
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.Heartbeat
import dev.benfranklin.tether.core.wifi.WifiBindGate
import java.security.SecureRandom

/** Durable identity and pairing state. Implementations must write atomically. */
interface IdentityStore {
    /** This install's static key. Created on first launch. */
    var localKey: KeyPair

    /** A rotated key waiting for the peer's acknowledgement. */
    var pendingLocalKey: KeyPair?

    /** The paired device, or null when unpaired. */
    var peer: PeerRecord?
}

/** What the UI shows. */
sealed interface LinkState {
    data object Offline : LinkState
    data object Handshaking : LinkState
    data class Pairing(val sas: String, val peerName: String, val auto: Boolean) : LinkState
    data class Connected(val peerName: String, val wifi: Boolean) : LinkState
}

interface ControllerListener {
    fun onState(state: LinkState)

    /** Clip, Notif, NotifRemoved, NotifDismiss and other app-level messages. */
    fun onMessage(message: Message)

    fun onTransfer(record: TransferRecord, important: Boolean)

    fun onTransferRemoved(id: String) {}

    /** Newly paired with [peer]. */
    fun onPaired(peer: PeerRecord) {}

    /** The other device unpaired us (or we unpaired); trust is already cleared. */
    fun onUnpaired(byRemote: Boolean) {}

    /** The link closed for [reason] (for the UI's error line and the log). */
    fun onClosed(reason: CloseReason, byRemote: Boolean, wifi: Boolean) {}

    /** Mac side: the phone offered a Wi-Fi session. Connect and call [TetherController.wifiConnected]. */
    fun onWifiOffer(offer: WifiOffer) {}

    /** The Wi-Fi offer was withdrawn. */
    fun onWifiRevoked() {}

    /** An authenticated message of [type] couldn't be decoded and was dropped (see SessionListener.onUndecodable). */
    fun onUndecodable(type: Int, reason: String) {}

    /** The rotated key was acknowledged; the app should reconnect so it takes effect. */
    fun onKeyRotated() {}
}

/**
 * Glue between the transports (BLE fragments, TCP records) and the protocol: owns the
 * Bluetooth session, the optional Wi-Fi session, the transfer engine, keepalive and key
 * rotation. Single-threaded; both apps call it from one serial queue.
 */
@Suppress("TooManyFunctions")
class TetherController(
    private val role: Role,
    private val identity: IdentityStore,
    private val localHello: () -> Hello,
    outgoing: OutgoingFiles,
    incoming: IncomingFiles,
    private val listener: ControllerListener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val engine = TransferEngine(outgoing, incoming, object : TransferListener {
        override fun onTransferUpdated(record: TransferRecord, important: Boolean) =
            listener.onTransfer(record, important)

        override fun onTransferRemoved(id: String) = listener.onTransferRemoved(id)
    }, clock)

    // ---- Bluetooth ----
    private var ble: Session? = null
    private var bleWriter: ((ByteArray) -> Unit)? = null
    private var maxFragment = DEFAULT_FRAGMENT
    private val fragmenter = Fragmenter()
    private val reassembler = Reassembler()
    private val heartbeat = Heartbeat()
    private var bleStartedAt = 0L
    private var blePairing: PairingContext = PairingContext.CLOSED
    private var bleLinkUp = false
    private var bleHeard = false
    private var bleFirstSeen = false
    private var bleRetries = 0
    private var blePairingChanged = false

    // ---- Wi-Fi ----
    private var wifi: Session? = null
    private var wifiWriter: ((ByteArray) -> Unit)? = null
    private var wifiBound = false
    private var wifiStartedAt = 0L
    private var wifiToken: ByteArray? = null
    private val gate = WifiBindGate(random)
    private val wifiHeartbeat = Heartbeat()

    var state: LinkState = LinkState.Offline
        private set

    val transfers: TransferEngine get() = engine

    val isConnected: Boolean get() = ble?.state == SessionState.ESTABLISHED || wifiReady

    val wifiReady: Boolean get() = wifi?.state == SessionState.ESTABLISHED && wifiBound

    val bleReady: Boolean get() = ble?.state == SessionState.ESTABLISHED

    fun restoreTransfers(saved: List<TransferRecord>) = engine.restore(saved)

    /**
     * Features the connected peer announced in its Hello (`caps`), empty while offline. A
     * 0.1.x peer announces only clip/notif/file, so new features stay hidden until it updates.
     */
    val peerCaps: Set<String>
        get() {
            val s = ble?.takeIf { it.state == SessionState.ESTABLISHED } ?: wifi?.takeIf { wifiReady }
            return s?.remoteHello?.caps?.toSet().orEmpty()
        }

    /** The peer's app version string from its Hello (for diagnostics), null while offline. */
    val peerApp: String?
        get() = (ble?.takeIf { it.state == SessionState.ESTABLISHED } ?: wifi?.takeIf { wifiReady })?.remoteHello?.app

    fun peerSupports(cap: String): Boolean = cap in peerCaps

    // ================= Bluetooth link =================

    /**
     * A BLE connection is up (GATT connected and subscribed). [writer] puts one fragment on
     * the air; [maxFragment] is ATT MTU minus 3, capped at [Fragmentation.MAX_BLE_FRAGMENT].
     */
    fun bleConnected(maxFragment: Int, writer: (ByteArray) -> Unit, pairing: PairingContext = PairingContext.CLOSED) {
        ble?.linkLost()
        this.maxFragment = clampFragment(maxFragment)
        bleWriter = writer
        blePairing = pairing
        bleLinkUp = true
        bleRetries = 0
        startBleSession()
    }

    /**
     * Pairing mode opened or closed while a link may already be up. Applies to the next
     * handshake on this link (the responder restarts its session when the phone retries).
     */
    fun setPairing(pairing: PairingContext) {
        blePairing = pairing
        blePairingChanged = true
    }

    /** Handshake attempts on the current link beyond the first (for diagnostics). */
    val bleHandshakeRetries: Int get() = bleRetries

    private fun startBleSession() {
        // Detach the old session first so its close can't reach the app as a link loss.
        val old = ble
        ble = null
        old?.linkLost()
        reassembler.reset()
        bleStartedAt = clock()
        bleHeard = false
        bleFirstSeen = false
        blePairingChanged = false
        val listener = BleListener()
        val s = Session(role, identity.localKey, localHello(), ::trusts, blePairing, listener, random)
        listener.session = s
        ble = s
        // A Bluetooth reconnect while Wi-Fi carries the session is not a visible state change.
        if (wifiReady) refreshState() else setState(LinkState.Handshaking)
        s.start()
    }

    fun setMaxFragment(maxFragment: Int) {
        this.maxFragment = clampFragment(maxFragment)
    }

    fun bleFragment(fragment: ByteArray) {
        if (!bleLinkUp) return
        // Any fragment proves the radio path is alive, even mid-way through a long record.
        heartbeat.heard(clock())
        val record = reassembler.accept(fragment) ?: return
        bleHeard = true
        if (role == Role.RESPONDER && isFirstHandshakeMessage(record)) {
            // The phone (re)started the handshake: a lost message or a session that already
            // timed out here must not wedge the link, so begin a fresh session for it.
            val cur = ble
            val fresh = cur != null && cur.state == SessionState.HANDSHAKING && !bleFirstSeen && !blePairingChanged
            val restartable = cur == null || cur.state == SessionState.HANDSHAKING || cur.state == SessionState.CLOSED
            if (!fresh && restartable) {
                bleRetries++
                startBleSession()
            }
            bleFirstSeen = true
        }
        ble?.receive(record)
    }

    private fun isFirstHandshakeMessage(record: ByteArray): Boolean =
        record.size >= 2 && record[0].toInt() == RecordKind.HANDSHAKE && record[1].toInt() == 1

    fun bleDisconnected() {
        bleLinkUp = false
        ble?.linkLost()
        bleWriter = null
    }

    fun confirmPairing() = ble?.confirmPairing()

    fun rejectPairing() = ble?.rejectPairing()

    // ================= Wi-Fi link =================

    /**
     * Phone: our TCP listener is ready at [addrs]:[port]. Sends a WIFI_OFFER (fresh token)
     * over Bluetooth. Returns false when Bluetooth isn't connected.
     */
    fun offerWifi(addrs: List<String>, port: Int): Boolean {
        val s = ble ?: return false
        if (s.state != SessionState.ESTABLISHED || addrs.isEmpty()) return false
        s.send(WifiOffer(addrs, port, gate.mint()))
        return true
    }

    fun revokeWifi(reason: String = "network") {
        gate.revoke()
        if (bleReady) ble?.send(WifiRevoke(reason))
        wifiDisconnected()
    }

    /**
     * A TCP connection is up. The Noise roles match Bluetooth (phone initiates), whichever
     * side opened the socket. The Mac passes the offer's [token] and sends WIFI_BIND; the
     * phone passes null and admits the session only if WIFI_BIND carries its token.
     */
    fun wifiConnected(writer: (ByteArray) -> Unit, token: String? = null) {
        wifi?.linkLost()
        wifiWriter = writer
        wifiBound = false
        wifiStartedAt = clock()
        wifiToken = token?.let { B64.decode(it) }
        val s = Session(
            role, identity.localKey, localHello(), ::trustsCurrentPeer, PairingContext.CLOSED, WifiListener(), random,
            prologue = Protocol.PROLOGUE_WIFI,
        )
        wifi = s
        s.start()
    }

    fun wifiRecord(record: ByteArray) {
        wifiHeartbeat.heard(clock())
        wifi?.receive(record)
    }

    fun wifiDisconnected() {
        wifi?.linkLost()
    }

    // ================= App actions =================

    /**
     * Send [message] on the best link: large clips go over Wi-Fi when it is up.
     * Returns false if no link can carry it.
     */
    fun send(message: Message): Boolean {
        // Screen video never goes over Bluetooth; its control messages prefer Wi-Fi for latency.
        val wifiOnly = message is VideoFrame
        if (wifiOnly && !wifiReady) return false
        val useWifi = wifiReady && (wifiOnly || prefersWifi(message) || !bleReady)
        val s = (if (useWifi) wifi else ble)?.takeIf { it.state == SessionState.ESTABLISHED } ?: return false
        if (message is Clip && !useWifi && message.text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_CLIP_BYTES) {
            return false
        }
        s.send(message)
        return true
    }

    /** Latency-sensitive screen control and anything big (lists, artwork) goes over Wi-Fi when it is up. */
    /** Big or latency-sensitive records go over Wi-Fi when it is up. */
    private fun prefersWifi(m: Message): Boolean = WIFI_PREFERRED.any { it.isInstance(m) }

    fun sendFile(record: TransferRecord) = engine.enqueue(record)

    fun cancelTransfer(id: String) = engine.cancel(id)

    fun retryTransfer(id: String) = engine.retry(id)

    fun removeTransfer(id: String) = engine.remove(id)

    /** Forget the peer here and tell it to forget us. */
    fun unpair() {
        val s = ble?.takeIf { it.state == SessionState.ESTABLISHED }
        s?.send(Unpair("user"))
        identity.peer = null
        identity.pendingLocalKey = null
        s?.close(CloseReason.UNPAIRED)
        wifi?.close(CloseReason.UNPAIRED)
        listener.onUnpaired(byRemote = false)
    }

    /**
     * Start a key rotation. The new key is kept pending until the peer acknowledges it.
     * Returns false when not connected (rotation needs the peer).
     */
    fun rotateKey(): Boolean {
        val s = ble?.takeIf { it.state == SessionState.ESTABLISHED } ?: return false
        val next = identity.pendingLocalKey ?: KeyPair.generate(random).also { identity.pendingLocalKey = it }
        s.send(KeyRotate(B64.encode(next.publicKey)))
        return true
    }

    /** Call about once a second: keepalive and handshake/pairing timeouts. */
    fun tick() {
        val now = clock()
        ble?.let { s -> tickSession(s, heartbeat, bleStartedAt, now) }
        wifi?.let { s ->
            if (s.state == SessionState.ESTABLISHED && !wifiBound && now - wifiStartedAt > HANDSHAKE_TIMEOUT_MS) {
                s.close(CloseReason.TIMEOUT)
            } else {
                tickSession(s, wifiHeartbeat, wifiStartedAt, now)
            }
        }
    }

    private fun tickSession(s: Session, hb: Heartbeat, startedAt: Long, now: Long) {
        when (s.state) {
            SessionState.HANDSHAKING -> when {
                // Phone: nothing back from the Mac yet. A write may have been lost while the
                // Mac was still setting up the subscription, so send the first message again.
                s === ble && role == Role.INITIATOR && !bleHeard && bleRetries < MAX_HANDSHAKE_RETRIES &&
                    now - startedAt > HANDSHAKE_RETRY_MS -> {
                    bleRetries++
                    startBleSession()
                }
                now - startedAt > HANDSHAKE_TIMEOUT_MS -> s.close(CloseReason.TIMEOUT)
            }
            SessionState.VERIFYING -> if (now - startedAt > PAIRING_TIMEOUT_MS) s.close(CloseReason.TIMEOUT)
            SessionState.ESTABLISHED -> when (hb.tick(now)) {
                Heartbeat.Action.SEND_PING -> s.send(Ping(now))
                Heartbeat.Action.DEAD -> s.close(CloseReason.TIMEOUT)
                Heartbeat.Action.NONE -> Unit
            }
            else -> Unit
        }
    }

    // ================= internals =================

    private fun trusts(key: ByteArray): Boolean = identity.peer?.trusts(key) == true

    /** Wi-Fi only ever talks to the paired peer (never pairs). */
    private fun trustsCurrentPeer(key: ByteArray): Boolean = trusts(key)

    private val bleChannel = Channel(Transport.BLE) { m -> ble?.takeIf { it.state == SessionState.ESTABLISHED }?.send(m) }
    private val wifiChannel = Channel(Transport.WIFI) { m -> wifi?.takeIf { it.state == SessionState.ESTABLISHED }?.send(m) }

    private fun refreshState() {
        val peer = identity.peer
        val s = ble
        state = when {
            s?.state == SessionState.VERIFYING -> LinkState.Pairing(s.sas.orEmpty(), s.remoteHello?.name.orEmpty(), false)
            isConnected && peer != null -> LinkState.Connected(peer.name, wifiReady)
            s?.state == SessionState.HANDSHAKING -> LinkState.Handshaking
            else -> LinkState.Offline
        }
        listener.onState(state)
    }

    private fun setState(st: LinkState) {
        state = st
        listener.onState(st)
    }

    private fun updateEngineChannel() {
        when {
            wifiReady -> if (engine.transport != Transport.WIFI) engine.switchTo(wifiChannel)
            bleReady -> if (engine.transport != Transport.BLE) engine.switchTo(bleChannel)
            else -> if (engine.isConnected) engine.onDisconnected()
        }
    }

    private fun handleCommon(s: Session, message: Message, channel: Channel) {
        when (message) {
            is Ping -> s.send(Pong(message.t))
            is Pong -> Unit
            is Unpair -> {
                identity.peer = null
                identity.pendingLocalKey = null
                listener.onUnpaired(byRemote = true)
                s.close(CloseReason.UNPAIRED)
                wifi?.close(CloseReason.UNPAIRED)
            }
            is KeyRotate -> {
                val newKey = B64.decode(message.key)
                val peer = identity.peer
                if (newKey != null && newKey.size == KeyPair.KEY_LEN && peer != null) {
                    identity.peer = peer.withPendingKey(newKey)
                    s.send(KeyRotateAck(message.key))
                }
            }
            is KeyRotateAck -> {
                val pending = identity.pendingLocalKey
                if (pending != null && B64.encode(pending.publicKey) == message.key) {
                    identity.localKey = pending
                    identity.pendingLocalKey = null
                    listener.onKeyRotated()
                }
            }
            is WifiOffer -> if (channel.transport == Transport.BLE) listener.onWifiOffer(message)
            is WifiRevoke -> {
                listener.onWifiRevoked()
            }
            is WifiBind -> Unit // only meaningful as the first Wi-Fi message
            else -> if (!engine.handle(message, channel)) listener.onMessage(message)
        }
    }

    private inner class BleListener : SessionListener {
        /** The session this listener belongs to; callbacks from a replaced session are ignored. */
        var session: Session? = null

        private val current: Boolean get() = session != null && session === ble

        override fun onOutput(record: ByteArray) {
            if (!current) return
            val w = bleWriter ?: return
            try {
                fragmenter.fragment(record, maxFragment).forEach(w)
            } catch (_: FragmentException) {
                ble?.close(CloseReason.PROTOCOL_ERROR)
            }
        }

        override fun onPairingRequest(sas: String, remote: Hello, remoteKey: ByteArray, autoConfirmed: Boolean) {
            if (!current) return
            setState(LinkState.Pairing(sas, remote.name, autoConfirmed))
        }

        override fun onEstablished(remote: Hello, remoteKey: ByteArray, newlyPaired: Boolean) {
            if (!current) return
            val now = clock()
            heartbeat.reset(now)
            if (newlyPaired) {
                val record = PeerRecord.paired(remote.name, remote.platform, remoteKey, now)
                identity.peer = record
                identity.pendingLocalKey = null
                listener.onPaired(record)
            } else {
                identity.peer = identity.peer?.afterHandshake(remoteKey, remote.name)
            }
            updateEngineChannel()
            refreshState()
            // A rotation that was never acknowledged is retried on every connect.
            if (identity.pendingLocalKey != null) rotateKey()
        }

        override fun onMessage(message: Message) {
            if (!current) return
            val s = ble ?: return
            handleCommon(s, message, bleChannel)
        }

        override fun onUndecodable(type: Int, reason: String) {
            if (current) listener.onUndecodable(type, reason)
        }

        override fun onClosed(reason: CloseReason, byRemote: Boolean) {
            if (!current) return
            ble = null
            // bleWriter stays: the radio link may still be up and the phone may retry.
            reassembler.reset()
            gate.revoke()
            updateEngineChannel()
            refreshState()
            listener.onClosed(reason, byRemote, wifi = false)
        }
    }

    private inner class WifiListener : SessionListener {
        override fun onOutput(record: ByteArray) {
            wifiWriter?.invoke(record)
        }

        override fun onEstablished(remote: Hello, remoteKey: ByteArray, newlyPaired: Boolean) {
            wifiHeartbeat.reset(clock())
            val token = wifiToken
            // The side that received the WIFI_OFFER (the Mac) proves it saw the offer.
            if (token != null) {
                wifi?.send(WifiBind(B64.encode(token)))
                bind()
            }
        }

        override fun onMessage(message: Message) {
            val s = wifi ?: return
            if (!wifiBound) {
                if (wifiToken == null && gate.admit(message)) bind() else s.close(CloseReason.UNTRUSTED)
                return
            }
            handleCommon(s, message, wifiChannel)
        }

        override fun onUndecodable(type: Int, reason: String) = listener.onUndecodable(type, reason)

        override fun onClosed(reason: CloseReason, byRemote: Boolean) {
            val wasBound = wifiBound
            wifi = null
            wifiWriter = null
            wifiBound = false
            wifiToken = null
            updateEngineChannel()
            refreshState()
            if (wasBound || reason != CloseReason.LINK_LOST) listener.onClosed(reason, byRemote, wifi = true)
        }

        private fun bind() {
            wifiBound = true
            updateEngineChannel()
            refreshState()
        }
    }

    companion object {
        /** Record types that prefer the Wi-Fi session (big lists, thumbnails, screen control). */
        private val WIFI_PREFERRED = listOf(
            Clip::class, ScreenInput::class, VideoFeedback::class, ScreenStart::class, ScreenStop::class, ScreenState::class,
            SmsThreads::class, SmsMessages::class, NowPlayingArt::class, CallLog::class, Contacts::class,
            FsList::class, Photos::class, Thumb::class, ContinueCard::class, PhoneStatus::class, TypeText::class, Albums::class,
            ShelfSync::class,
        )
        /** Fragment size the link will actually use for a reported [maxFragment] (ATT MTU - 3). */
        fun clampFragment(maxFragment: Int): Int = maxFragment.coerceIn(DEFAULT_FRAGMENT, Fragmentation.MAX_BLE_FRAGMENT)

        const val DEFAULT_FRAGMENT = 20
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
        const val HANDSHAKE_RETRY_MS = 3_000L
        const val MAX_HANDSHAKE_RETRIES = 3
        const val PAIRING_TIMEOUT_MS = 120_000L
    }
}
