package dev.benfranklin.tether.core.proto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/** Protocol constants shared with the macOS implementation. See docs/PROTOCOL.md. */
object Protocol {
    const val VERSION: Int = 1
    const val PROLOGUE: String = "tether/1"
    /** Prologue for the Wi-Fi (TCP) session, so a BLE handshake can never be replayed on TCP. */
    const val PROLOGUE_WIFI: String = "tether/1/wifi"
    const val SERVICE_UUID: String = "7E7E0001-5A1D-4C6B-9C0E-7465746865B1"
    const val CHARACTERISTIC_UUID: String = "7E7E0002-5A1D-4C6B-9C0E-7465746865B1"
    const val BONJOUR_TYPE: String = "_tether._tcp"
    /** Largest clip sent over Bluetooth. */
    const val MAX_CLIP_BYTES: Int = 64 * 1024
    /** Largest clip sent over Wi-Fi (and accepted by the decoder). */
    const val MAX_CLIP_BYTES_WIFI: Int = 1024 * 1024
    const val MAX_TITLE_CHARS: Int = 256
    const val MAX_TEXT_CHARS: Int = 2048
    const val FILE_CHUNK: Int = 16 * 1024
    const val FILE_CHUNK_WIFI: Int = 64 * 1024
    /** Largest record on a TCP (Wi-Fi) link: a Wi-Fi chunk or clip plus headers. */
    const val MAX_TCP_RECORD: Int = 2 * 1024 * 1024
    const val FILE_WARN_BYTES: Long = 20L * 1024 * 1024
    /** Hard limit over Bluetooth only; bigger files wait for Wi-Fi. */
    const val FILE_MAX_BYTES: Long = 100L * 1024 * 1024
    const val FILE_MAX_BYTES_WIFI: Long = 4L * 1024 * 1024 * 1024
}

/** First byte of every record on the link. */
object RecordKind {
    const val HANDSHAKE: Int = 0x01
    const val TRANSPORT: Int = 0x02
    const val CLOSE: Int = 0x03
}

/** Advisory reason carried by a plaintext CLOSE record or a local close. */
enum class CloseReason(val code: Int) {
    NORMAL(0),
    UNTRUSTED(1),
    BUSY(2),
    PROTOCOL_ERROR(3),
    VERSION_MISMATCH(4),
    PAIRING_REJECTED(5),
    UNPAIRED(6),
    WRONG_DEVICE(7),
    TIMEOUT(8),
    LINK_LOST(9),
    ;

    companion object {
        fun of(code: Int): CloseReason = entries.firstOrNull { it.code == code } ?: PROTOCOL_ERROR
    }
}

@Serializable
data class Hello(
    val v: Int = Protocol.VERSION,
    val name: String,
    val platform: String,
    val app: String,
    val caps: List<String> = Caps.V1,
)

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface Message {
    val type: Int
}

object MsgType {
    const val PING = 0x10
    const val PONG = 0x11
    const val PAIR_CONFIRM = 0x20
    const val PAIR_REJECT = 0x21
    const val UNPAIR = 0x22
    const val KEY_ROTATE = 0x23
    const val KEY_ROTATE_ACK = 0x24
    const val CLIP = 0x30
    const val NOTIF = 0x40
    const val NOTIF_REMOVED = 0x41
    const val NOTIF_DISMISS = 0x42
    const val FILE_OFFER = 0x50
    const val FILE_ACCEPT = 0x51
    const val FILE_REJECT = 0x52
    const val FILE_CHUNK = 0x53
    const val FILE_ACK = 0x54
    const val FILE_DONE = 0x55
    const val FILE_COMPLETE = 0x56
    const val FILE_CANCEL = 0x57
    const val WIFI_OFFER = 0x60
    const val WIFI_REVOKE = 0x61
    const val WIFI_BIND = 0x62

    // ---- revision 2 (0.2.0), gated on Hello.caps ----
    const val NOTIF_ACTION = 0x43
    const val NOTIF_ACTION_RESULT = 0x44
    const val BATTERY = 0x70
    const val RING = 0x71
    const val RING_STOP = 0x72
    const val RING_STATE = 0x73
    const val SCREEN_START = 0x80
    const val SCREEN_STATE = 0x81
    const val SCREEN_STOP = 0x82
    const val VIDEO_FRAME = 0x83
    const val SCREEN_INPUT = 0x84
    const val VIDEO_FEEDBACK = 0x85
}

@Serializable data class Ping(val t: Long) : Message { override val type get() = MsgType.PING }
@Serializable data class Pong(val t: Long) : Message { override val type get() = MsgType.PONG }

/** Local user (or a valid QR token) confirmed the pairing. [token] is base64url, QR flow only. */
@Serializable data class PairConfirm(val token: String? = null) : Message {
    override val type get() = MsgType.PAIR_CONFIRM
}

@Serializable data class PairReject(val reason: String = "user") : Message {
    override val type get() = MsgType.PAIR_REJECT
}

@Serializable data class Unpair(val reason: String = "user") : Message {
    override val type get() = MsgType.UNPAIR
}

/** Sender will switch to static key [key] (base64url) once the peer acknowledges. */
@Serializable data class KeyRotate(val key: String) : Message { override val type get() = MsgType.KEY_ROTATE }
@Serializable data class KeyRotateAck(val key: String) : Message { override val type get() = MsgType.KEY_ROTATE_ACK }

/** Plain-text clipboard. The text travels in the binary body, not the JSON header. */
data class Clip(val id: String, val ts: Long, val text: String) : Message {
    override val type get() = MsgType.CLIP
}

@Serializable data class Notif(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String = "",
    val text: String = "",
    val ts: Long,
    /** Buttons the Mac may offer (0.2.0+; older Macs ignore the field). */
    val actions: List<NotifActionInfo> = emptyList(),
) : Message { override val type get() = MsgType.NOTIF }

@Serializable data class NotifRemoved(val key: String) : Message { override val type get() = MsgType.NOTIF_REMOVED }
@Serializable data class NotifDismiss(val key: String) : Message { override val type get() = MsgType.NOTIF_DISMISS }

@Serializable data class FileOffer(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val mime: String = "application/octet-stream",
    /** "screenshot" or "photo" for automatic media sends (0.2.0+); null for a user's file. */
    val kind: String? = null,
) : Message { override val type get() = MsgType.FILE_OFFER }

@Serializable data class FileAccept(val id: String, val offset: Long) : Message {
    override val type get() = MsgType.FILE_ACCEPT
}

@Serializable data class FileReject(val id: String, val reason: String) : Message {
    override val type get() = MsgType.FILE_REJECT
}

/** A slice of file data; [data] travels in the binary body. */
class FileChunk(val id: String, val offset: Long, val data: ByteArray) : Message {
    override val type get() = MsgType.FILE_CHUNK
    override fun equals(other: Any?) =
        other is FileChunk && other.id == id && other.offset == offset && other.data.contentEquals(data)
    override fun hashCode() = (id.hashCode() * HASH_PRIME + offset.hashCode()) * HASH_PRIME + data.contentHashCode()
    override fun toString() = "FileChunk(id=$id, offset=$offset, len=${data.size})"
    private companion object { const val HASH_PRIME = 31 }
}

@Serializable data class FileAck(val id: String, val offset: Long) : Message { override val type get() = MsgType.FILE_ACK }
@Serializable data class FileDone(val id: String) : Message { override val type get() = MsgType.FILE_DONE }

@Serializable data class FileComplete(val id: String, val ok: Boolean, val reason: String? = null) : Message {
    override val type get() = MsgType.FILE_COMPLETE
}

@Serializable data class FileCancel(val id: String, val reason: String = "user") : Message {
    override val type get() = MsgType.FILE_CANCEL
}

/**
 * Sent over Bluetooth by the phone: "I'm listening for a Tether TCP session at [addrs]:[port]".
 * [token] (base64url, 16 bytes) must be presented in WIFI_BIND; single use.
 */
@Serializable data class WifiOffer(val addrs: List<String>, val port: Int, val token: String) : Message {
    override val type get() = MsgType.WIFI_OFFER
}

/** The phone's listener went away (Wi-Fi off, network change). */
@Serializable data class WifiRevoke(val reason: String = "network") : Message {
    override val type get() = MsgType.WIFI_REVOKE
}

/** First message on a new TCP session, binding it to the offer made over Bluetooth. */
@Serializable data class WifiBind(val token: String) : Message { override val type get() = MsgType.WIFI_BIND }

/** A message type this build does not know. Ignored for forward compatibility. */
data class UnknownMessage(override val type: Int) : Message

@Serializable private data class ClipHeader(val id: String, val ts: Long)
@Serializable private data class ChunkHeader(val id: String, val offset: Long)

/**
 * Message wire format (inside a Noise transport record):
 * ```
 * u8  type
 * u32 headerLength (big-endian)
 * ..  header: UTF-8 JSON object
 * ..  body: raw bytes (CLIP text, FILE_CHUNK data; empty otherwise)
 * ```
 */
object MessageCodec {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private const val PREFIX = 5
    private val EMPTY = ByteArray(0)

    fun encode(m: Message): ByteArray {
        val (header, body) = when (m) {
            is Ping -> enc(Ping.serializer(), m) to EMPTY
            is Pong -> enc(Pong.serializer(), m) to EMPTY
            is PairConfirm -> enc(PairConfirm.serializer(), m) to EMPTY
            is PairReject -> enc(PairReject.serializer(), m) to EMPTY
            is Unpair -> enc(Unpair.serializer(), m) to EMPTY
            is KeyRotate -> enc(KeyRotate.serializer(), m) to EMPTY
            is KeyRotateAck -> enc(KeyRotateAck.serializer(), m) to EMPTY
            is Clip -> enc(ClipHeader.serializer(), ClipHeader(m.id, m.ts)) to m.text.toByteArray(Charsets.UTF_8)
            is Notif -> enc(Notif.serializer(), m) to EMPTY
            is NotifRemoved -> enc(NotifRemoved.serializer(), m) to EMPTY
            is NotifDismiss -> enc(NotifDismiss.serializer(), m) to EMPTY
            is FileOffer -> enc(FileOffer.serializer(), m) to EMPTY
            is FileAccept -> enc(FileAccept.serializer(), m) to EMPTY
            is FileReject -> enc(FileReject.serializer(), m) to EMPTY
            is FileChunk -> enc(ChunkHeader.serializer(), ChunkHeader(m.id, m.offset)) to m.data
            is FileAck -> enc(FileAck.serializer(), m) to EMPTY
            is FileDone -> enc(FileDone.serializer(), m) to EMPTY
            is FileComplete -> enc(FileComplete.serializer(), m) to EMPTY
            is FileCancel -> enc(FileCancel.serializer(), m) to EMPTY
            is WifiOffer -> enc(WifiOffer.serializer(), m) to EMPTY
            is WifiRevoke -> enc(WifiRevoke.serializer(), m) to EMPTY
            is WifiBind -> enc(WifiBind.serializer(), m) to EMPTY
            is NotifAction -> enc(NotifAction.serializer(), m) to EMPTY
            is NotifActionResult -> enc(NotifActionResult.serializer(), m) to EMPTY
            is Battery -> enc(Battery.serializer(), m) to EMPTY
            is Ring -> enc(Ring.serializer(), m) to EMPTY
            is RingStop -> enc(RingStop.serializer(), m) to EMPTY
            is RingState -> enc(RingState.serializer(), m) to EMPTY
            is ScreenStart -> enc(ScreenStart.serializer(), m) to EMPTY
            is ScreenState -> enc(ScreenState.serializer(), m) to EMPTY
            is ScreenStop -> enc(ScreenStop.serializer(), m) to EMPTY
            is VideoFrame -> enc(
                VideoHeader.serializer(), VideoHeader(m.src, m.seq, m.pts, m.key, m.config, m.part, m.parts),
            ) to m.data
            is ScreenInput -> enc(ScreenInput.serializer(), m) to EMPTY
            is VideoFeedback -> enc(VideoFeedback.serializer(), m) to EMPTY
            is UnknownMessage -> throw ProtocolException("cannot encode unknown message")
        }
        val buf = ByteBuffer.allocate(PREFIX + header.size + body.size)
        buf.put(m.type.toByte())
        buf.putInt(header.size)
        buf.put(header)
        buf.put(body)
        return buf.array()
    }

    fun decode(bytes: ByteArray): Message {
        if (bytes.size < PREFIX) throw ProtocolException("message too short")
        val buf = ByteBuffer.wrap(bytes)
        val type = buf.get().toInt() and 0xFF
        val headerLen = buf.int
        if (headerLen < 0 || headerLen > bytes.size - PREFIX) throw ProtocolException("bad header length")
        val header = String(bytes, PREFIX, headerLen, Charsets.UTF_8)
        val body = bytes.copyOfRange(PREFIX + headerLen, bytes.size)
        return try {
            decodeTyped(type, header, body)
        } catch (e: SerializationException) {
            throw ProtocolException("bad header for type $type", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("bad header for type $type", e)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun decodeTyped(type: Int, header: String, body: ByteArray): Message = when (type) {
        MsgType.PING -> dec(Ping.serializer(), header)
        MsgType.PONG -> dec(Pong.serializer(), header)
        MsgType.PAIR_CONFIRM -> dec(PairConfirm.serializer(), header)
        MsgType.PAIR_REJECT -> dec(PairReject.serializer(), header)
        MsgType.UNPAIR -> dec(Unpair.serializer(), header)
        MsgType.KEY_ROTATE -> dec(KeyRotate.serializer(), header)
        MsgType.KEY_ROTATE_ACK -> dec(KeyRotateAck.serializer(), header)
        MsgType.CLIP -> {
            if (body.size > Protocol.MAX_CLIP_BYTES_WIFI) throw ProtocolException("clip too large")
            val h = dec(ClipHeader.serializer(), header)
            Clip(h.id, h.ts, String(body, Charsets.UTF_8))
        }
        MsgType.NOTIF -> dec(Notif.serializer(), header)
        MsgType.NOTIF_REMOVED -> dec(NotifRemoved.serializer(), header)
        MsgType.NOTIF_DISMISS -> dec(NotifDismiss.serializer(), header)
        MsgType.FILE_OFFER -> dec(FileOffer.serializer(), header)
        MsgType.FILE_ACCEPT -> dec(FileAccept.serializer(), header)
        MsgType.FILE_REJECT -> dec(FileReject.serializer(), header)
        MsgType.FILE_CHUNK -> {
            val h = dec(ChunkHeader.serializer(), header)
            FileChunk(h.id, h.offset, body)
        }
        MsgType.FILE_ACK -> dec(FileAck.serializer(), header)
        MsgType.FILE_DONE -> dec(FileDone.serializer(), header)
        MsgType.FILE_COMPLETE -> dec(FileComplete.serializer(), header)
        MsgType.FILE_CANCEL -> dec(FileCancel.serializer(), header)
        MsgType.WIFI_OFFER -> dec(WifiOffer.serializer(), header)
        MsgType.WIFI_REVOKE -> dec(WifiRevoke.serializer(), header)
        MsgType.WIFI_BIND -> dec(WifiBind.serializer(), header)
        else -> decodeRevision2(type, header, body)
    }

    private fun decodeRevision2(type: Int, header: String, body: ByteArray): Message = when (type) {
        MsgType.NOTIF_ACTION -> dec(NotifAction.serializer(), header)
        MsgType.NOTIF_ACTION_RESULT -> dec(NotifActionResult.serializer(), header)
        MsgType.BATTERY -> dec(Battery.serializer(), header)
        MsgType.RING -> dec(Ring.serializer(), header)
        MsgType.RING_STOP -> dec(RingStop.serializer(), header)
        MsgType.RING_STATE -> dec(RingState.serializer(), header)
        MsgType.SCREEN_START -> dec(ScreenStart.serializer(), header)
        MsgType.SCREEN_STATE -> dec(ScreenState.serializer(), header)
        MsgType.SCREEN_STOP -> dec(ScreenStop.serializer(), header)
        MsgType.VIDEO_FRAME -> {
            val h = dec(VideoHeader.serializer(), header)
            if (h.parts < 1 || h.part !in 0 until h.parts) throw ProtocolException("bad frame part")
            VideoFrame(h.src, h.seq, h.pts, h.key, h.config, h.part, h.parts, body)
        }
        MsgType.SCREEN_INPUT -> dec(ScreenInput.serializer(), header)
        MsgType.VIDEO_FEEDBACK -> dec(VideoFeedback.serializer(), header)
        else -> UnknownMessage(type)
    }

    private fun <T> enc(s: KSerializer<T>, v: T): ByteArray = json.encodeToString(s, v).toByteArray(Charsets.UTF_8)
    private fun <T> dec(s: KSerializer<T>, h: String): T = json.decodeFromString(s, h)

    fun encodeHello(h: Hello): ByteArray = enc(Hello.serializer(), h)

    fun decodeHello(bytes: ByteArray): Hello = try {
        json.decodeFromString(Hello.serializer(), String(bytes, Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw ProtocolException("bad hello", e)
    } catch (e: IllegalArgumentException) {
        throw ProtocolException("bad hello", e)
    }
}

