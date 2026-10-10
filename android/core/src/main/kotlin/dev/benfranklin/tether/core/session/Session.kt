package dev.benfranklin.tether.core.session

import dev.benfranklin.tether.core.crypto.CipherState
import dev.benfranklin.tether.core.crypto.HandshakeState
import dev.benfranklin.tether.core.crypto.KeyPair
import dev.benfranklin.tether.core.crypto.NoiseException
import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.Hello
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.PairConfirm
import dev.benfranklin.tether.core.proto.PairReject
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.proto.ProtocolException
import dev.benfranklin.tether.core.proto.RecordKind
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.constantTimeEquals
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * How this session may treat a peer whose static key is not trusted yet.
 *
 * - Mac (responder): [acceptNew] while the "Pair a phone" window is open; [token] is the
 *   one-time secret shown in the QR code.
 * - Android (initiator): [acceptNew] while the user is in the pairing flow. After scanning a
 *   QR, [expectedRemoteKey] and [token] come from it.
 */
class PairingContext(
    val acceptNew: Boolean,
    val expectedRemoteKey: ByteArray? = null,
    val token: ByteArray? = null,
) {
    companion object {
        val CLOSED = PairingContext(acceptNew = false)
    }
}

enum class SessionState { IDLE, HANDSHAKING, VERIFYING, ESTABLISHED, CLOSED }

interface SessionListener {
    /** A record to put on the link. */
    fun onOutput(record: ByteArray)

    /** Unknown peer finished the handshake; show [sas] and ask the user to compare. */
    fun onPairingRequest(sas: String, remote: Hello, remoteKey: ByteArray, autoConfirmed: Boolean) {}

    fun onEstablished(remote: Hello, remoteKey: ByteArray, newlyPaired: Boolean)

    fun onMessage(message: Message)

    /**
     * An authenticated message of [type] whose header didn't parse (a peer bug, never an
     * attack: it decrypted). Dropped without closing the session so one odd field can't take
     * the link down; reported so the app can log it and fail the request it answered.
     */
    fun onUndecodable(type: Int, reason: String) {}

    fun onClosed(reason: CloseReason, byRemote: Boolean)
}

/**
 * One encrypted conversation over one link connection. Single-threaded: callers must
 * serialize calls (both apps drive it from one queue/dispatcher).
 */
class Session(
    private val role: Role,
    private val localStatic: KeyPair,
    private val localHello: Hello,
    private val isTrusted: (ByteArray) -> Boolean,
    private val pairing: PairingContext,
    private val listener: SessionListener,
    random: SecureRandom = SecureRandom(),
    fixedEphemeral: ByteArray? = null,
    prologue: String = Protocol.PROLOGUE,
) {
    var state: SessionState = SessionState.IDLE
        private set

    var remoteHello: Hello? = null
        private set
    var remoteKey: ByteArray? = null
        private set
    var sas: String? = null
        private set

    private val handshake = HandshakeState(
        role, localStatic, prologue.toByteArray(Charsets.US_ASCII), random, fixedEphemeral,
    )
    private var tx: CipherState? = null
    private var rx: CipherState? = null
    private var localConfirmed = false
    private var remoteConfirmed = false
    private var confirmSent = false

    fun start() {
        check(state == SessionState.IDLE) { "session already started" }
        state = SessionState.HANDSHAKING
        if (role == Role.INITIATOR) writeHandshake()
    }

    /** Feed one reassembled record from the link. */
    fun receive(record: ByteArray) {
        if (state == SessionState.CLOSED) return
        if (record.isEmpty()) return fail(CloseReason.PROTOCOL_ERROR)
        try {
            when (record[0].toInt()) {
                RecordKind.HANDSHAKE -> onHandshake(record)
                RecordKind.TRANSPORT -> onTransport(record)
                RecordKind.CLOSE -> {
                    val reason = if (record.size > 1) CloseReason.of(record[1].toInt()) else CloseReason.NORMAL
                    finish(reason, byRemote = true)
                }
                else -> fail(CloseReason.PROTOCOL_ERROR)
            }
        } catch (_: NoiseException) {
            fail(CloseReason.PROTOCOL_ERROR)
        } catch (_: ProtocolException) {
            fail(CloseReason.PROTOCOL_ERROR)
        }
    }

    /** Send an application message. Only valid once [SessionState.ESTABLISHED]. */
    fun send(message: Message) {
        check(state == SessionState.ESTABLISHED) { "session not established" }
        sendEncrypted(message)
    }

    /** The local user confirmed the six-digit code matches. */
    fun confirmPairing() {
        if (state != SessionState.VERIFYING) return
        localConfirmed = true
        sendConfirmOnce(null)
        maybeFinishPairing()
    }

    fun rejectPairing() {
        if (state != SessionState.VERIFYING) return
        sendEncrypted(PairReject("user"))
        close(CloseReason.PAIRING_REJECTED)
    }

    /** Close locally. Sends an advisory CLOSE record when the link is still up. */
    fun close(reason: CloseReason = CloseReason.NORMAL) {
        if (state == SessionState.CLOSED) return
        if (state != SessionState.IDLE) listener.onOutput(byteArrayOf(RecordKind.CLOSE.toByte(), reason.code.toByte()))
        finish(reason, byRemote = false)
    }

    /** The link dropped underneath us. No CLOSE record is sent. */
    fun linkLost() {
        if (state != SessionState.CLOSED) finish(CloseReason.LINK_LOST, byRemote = false)
    }

    private fun onHandshake(record: ByteArray) {
        if (state != SessionState.HANDSHAKING || record.size < 2) return fail(CloseReason.PROTOCOL_ERROR)
        val step = record[1].toInt()
        if (step != handshake.messageIndex + 1) return fail(CloseReason.PROTOCOL_ERROR)
        val payload = handshake.readMessage(record.copyOfRange(2, record.size))
        if (payload.isNotEmpty()) {
            val hello = MessageCodec.decodeHello(payload)
            if (hello.v != Protocol.VERSION) return fail(CloseReason.VERSION_MISMATCH)
            remoteHello = hello
        }
        if (handshake.isComplete) onHandshakeComplete() else writeHandshake()
    }

    private fun writeHandshake() {
        // Message 1 (-> e) is sent in the clear, so it carries no identity.
        val payload = if (handshake.messageIndex == 0) ByteArray(0) else MessageCodec.encodeHello(localHello)
        val step = handshake.messageIndex + 1
        val msg = handshake.writeMessage(payload)
        listener.onOutput(byteArrayOf(RecordKind.HANDSHAKE.toByte(), step.toByte()) + msg)
        if (handshake.isComplete) onHandshakeComplete()
    }

    private fun onHandshakeComplete() {
        val result = handshake.split()
        tx = result.send
        rx = result.receive
        remoteKey = result.remoteStatic
        val hello = remoteHello ?: return fail(CloseReason.PROTOCOL_ERROR)
        val key = result.remoteStatic
        if (isTrusted(key)) {
            state = SessionState.ESTABLISHED
            listener.onEstablished(hello, key, newlyPaired = false)
            return
        }
        if (!pairing.acceptNew) return close(CloseReason.UNTRUSTED)
        val expected = pairing.expectedRemoteKey
        if (expected != null && !constantTimeEquals(expected, key)) return close(CloseReason.WRONG_DEVICE)
        state = SessionState.VERIFYING
        val code = computeSas(result.handshakeHash)
        sas = code
        val auto = role == Role.INITIATOR && expected != null
        listener.onPairingRequest(code, hello, key, auto)
        if (auto) {
            localConfirmed = true
            sendConfirmOnce(pairing.token)
        }
    }

    private fun onTransport(record: ByteArray) {
        val cipher = rx
        if (cipher == null || (state != SessionState.ESTABLISHED && state != SessionState.VERIFYING)) {
            return fail(CloseReason.PROTOCOL_ERROR)
        }
        val plain = cipher.decrypt(record.copyOfRange(1, record.size))
        val message = try {
            MessageCodec.decode(plain)
        } catch (e: ProtocolException) {
            if (state != SessionState.ESTABLISHED) throw e
            listener.onUndecodable(if (plain.isEmpty()) -1 else plain[0].toInt() and TYPE_MASK, e.message ?: "undecodable")
            return
        }
        if (state == SessionState.VERIFYING) onVerifyingMessage(message) else listener.onMessage(message)
    }

    private fun onVerifyingMessage(message: Message) {
        when (message) {
            is PairConfirm -> {
                remoteConfirmed = true
                if (role == Role.RESPONDER && !localConfirmed && tokenMatches(message.token)) {
                    localConfirmed = true
                    sendConfirmOnce(null)
                }
                maybeFinishPairing()
            }
            is PairReject -> finish(CloseReason.PAIRING_REJECTED, byRemote = true)
            else -> fail(CloseReason.PROTOCOL_ERROR)
        }
    }

    private fun tokenMatches(token: String?): Boolean {
        val mine = pairing.token ?: return false
        val theirs = token?.let { B64.decode(it) } ?: return false
        return constantTimeEquals(mine, theirs)
    }

    private fun sendConfirmOnce(token: ByteArray?) {
        if (confirmSent) return
        confirmSent = true
        sendEncrypted(PairConfirm(token?.let { B64.encode(it) }))
    }

    private fun maybeFinishPairing() {
        if (state != SessionState.VERIFYING || !localConfirmed || !remoteConfirmed) return
        state = SessionState.ESTABLISHED
        listener.onEstablished(checkNotNull(remoteHello), checkNotNull(remoteKey), newlyPaired = true)
    }

    private fun sendEncrypted(message: Message) {
        val cipher = checkNotNull(tx) { "no transport cipher" }
        val ct = cipher.encrypt(MessageCodec.encode(message))
        listener.onOutput(byteArrayOf(RecordKind.TRANSPORT.toByte()) + ct)
    }

    private fun fail(reason: CloseReason) = close(reason)

    private fun finish(reason: CloseReason, byRemote: Boolean) {
        if (state == SessionState.CLOSED) return
        state = SessionState.CLOSED
        tx?.destroy()
        rx?.destroy()
        listener.onClosed(reason, byRemote)
    }

    companion object {
        private const val SAS_MOD = 1_000_000L
        private const val SAS_BYTES = 4
        private const val BYTE_MASK = 0xFFL
        private const val TYPE_MASK = 0xFF
        private const val BYTE_BITS = 8

        /** Six-digit short authentication string, identical on both sides of one handshake. */
        fun computeSas(handshakeHash: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").apply {
                update("tether-sas/1".toByteArray(Charsets.US_ASCII))
                update(handshakeHash)
            }.digest()
            var v = 0L
            for (i in 0 until SAS_BYTES) v = (v shl BYTE_BITS) or (d[i].toLong() and BYTE_MASK)
            return String.format(Locale.ROOT, "%06d", v % SAS_MOD)
        }
    }
}
