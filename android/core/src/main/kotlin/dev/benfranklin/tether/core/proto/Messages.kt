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

    // ---- revision 3 (0.3.0), gated on Hello.caps ----
    const val SMS_THREADS_REQ = 0x90
    const val SMS_THREADS = 0x91
    const val SMS_THREAD_REQ = 0x92
    const val SMS_MESSAGES = 0x93
    const val SMS_SEND = 0x94
    const val SMS_SEND_RESULT = 0x95
    const val SMS_NEW = 0x96
    const val SMS_SEARCH = 0x97
    const val SIMS = 0x98
    const val CALL_STATE = 0xA0
    const val CALL_ACTION = 0xA1
    const val NOW_PLAYING = 0xB0
    const val MEDIA_CMD = 0xB1
    const val NOW_PLAYING_ART = 0xB2
    const val RSSI = 0xD0
    const val PROXIMITY_CFG = 0xD1

    // ---- revision 4 (0.4.0), gated on Hello.caps ----
    const val NOTIF_ACCESS = 0x45
    const val CALL_DIAL = 0xA2
    const val CALL_DIAL_RESULT = 0xA3
    const val CALL_LIVE = 0xA4
    const val CALL_LOG_REQ = 0xA5
    const val CALL_LOG = 0xA6
    const val CONTACT_SEARCH = 0xA7
    const val CONTACTS = 0xA8

    // ---- revision 5 (0.5.0), gated on Hello.caps ----
    const val OTP = 0x32
    const val FS_LIST_REQ = 0xC0
    const val FS_LIST = 0xC1
    const val FS_GET = 0xC2
    const val FS_GET_RESULT = 0xC3
    const val THUMB_REQ = 0xC4
    const val THUMB = 0xC5
    const val PHOTOS_REQ = 0xC6
    const val PHOTOS = 0xC7
    const val CONTINUE = 0xC8
    const val OPEN_ON_PHONE = 0xC9
    const val OPEN_RESULT = 0xCA
    const val TYPE_TEXT = 0xCB
    const val TYPE_RESULT = 0xCC
    const val DND_STATE = 0xE0
    const val PHONE_STATUS = 0xE1
    const val PHONE_STATUS_REQ = 0xE2
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
@Serializable private data class ThumbHeader(val ref: String, val ok: Boolean, val reason: String? = null)

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

    @Suppress("LongMethod")
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
            is SmsThreadsReq -> enc(SmsThreadsReq.serializer(), m) to EMPTY
            is SmsThreads -> enc(SmsThreads.serializer(), m) to EMPTY
            is SmsThreadReq -> enc(SmsThreadReq.serializer(), m) to EMPTY
            is SmsMessages -> enc(SmsMessages.serializer(), m) to EMPTY
            is SmsSend -> enc(SmsSend.serializer(), m) to EMPTY
            is SmsSendResult -> enc(SmsSendResult.serializer(), m) to EMPTY
            is SmsNew -> enc(SmsNew.serializer(), m) to EMPTY
            is SmsSearch -> enc(SmsSearch.serializer(), m) to EMPTY
            is Sims -> enc(Sims.serializer(), m) to EMPTY
            is CallState -> enc(CallState.serializer(), m) to EMPTY
            is CallAction -> enc(CallAction.serializer(), m) to EMPTY
            is NowPlaying -> enc(NowPlaying.serializer(), m) to EMPTY
            is MediaCmd -> enc(MediaCmd.serializer(), m) to EMPTY
            is NowPlayingArt -> enc(ArtHeader.serializer(), ArtHeader(m.hash)) to m.data
            is Rssi -> enc(Rssi.serializer(), m) to EMPTY
            is ProximityCfg -> enc(ProximityCfg.serializer(), m) to EMPTY
            is NotifAccess -> enc(NotifAccess.serializer(), m) to EMPTY
            is CallDial -> enc(CallDial.serializer(), m) to EMPTY
            is CallDialResult -> enc(CallDialResult.serializer(), m) to EMPTY
            is CallLive -> enc(CallLive.serializer(), m) to EMPTY
            is CallLogReq -> enc(CallLogReq.serializer(), m) to EMPTY
            is CallLog -> enc(CallLog.serializer(), m) to EMPTY
            is ContactSearch -> enc(ContactSearch.serializer(), m) to EMPTY
            is Contacts -> enc(Contacts.serializer(), m) to EMPTY
            is Otp -> enc(Otp.serializer(), m) to EMPTY
            is FsListReq -> enc(FsListReq.serializer(), m) to EMPTY
            is FsList -> enc(FsList.serializer(), m) to EMPTY
            is FsGet -> enc(FsGet.serializer(), m) to EMPTY
            is FsGetResult -> enc(FsGetResult.serializer(), m) to EMPTY
            is ThumbReq -> enc(ThumbReq.serializer(), m) to EMPTY
            is Thumb -> enc(ThumbHeader.serializer(), ThumbHeader(m.ref, m.ok, m.reason)) to m.data
            is PhotosReq -> enc(PhotosReq.serializer(), m) to EMPTY
            is Photos -> enc(Photos.serializer(), m) to EMPTY
            is ContinueCard -> enc(ContinueHeader.serializer(), m.header()) to m.thumb
            is OpenOnPhone -> enc(OpenOnPhone.serializer(), m) to EMPTY
            is OpenResult -> enc(OpenResult.serializer(), m) to EMPTY
            is TypeText -> enc(TypeText.serializer(), m) to EMPTY
            is TypeResult -> enc(TypeResult.serializer(), m) to EMPTY
            is DndState -> enc(DndState.serializer(), m) to EMPTY
            is PhoneStatus -> enc(PhoneStatus.serializer(), m) to EMPTY
            is PhoneStatusReq -> enc(PhoneStatusReq.serializer(), m) to EMPTY
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
        else -> decodeRevision3(type, header, body)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun decodeRevision3(type: Int, header: String, body: ByteArray): Message = when (type) {
        MsgType.SMS_THREADS_REQ -> dec(SmsThreadsReq.serializer(), header)
        MsgType.SMS_THREADS -> dec(SmsThreads.serializer(), header)
        MsgType.SMS_THREAD_REQ -> dec(SmsThreadReq.serializer(), header)
        MsgType.SMS_MESSAGES -> dec(SmsMessages.serializer(), header)
        MsgType.SMS_SEND -> dec(SmsSend.serializer(), header)
        MsgType.SMS_SEND_RESULT -> dec(SmsSendResult.serializer(), header)
        MsgType.SMS_NEW -> dec(SmsNew.serializer(), header)
        MsgType.SMS_SEARCH -> dec(SmsSearch.serializer(), header)
        MsgType.SIMS -> dec(Sims.serializer(), header)
        MsgType.CALL_STATE -> dec(CallState.serializer(), header)
        MsgType.CALL_ACTION -> dec(CallAction.serializer(), header)
        MsgType.NOW_PLAYING -> dec(NowPlaying.serializer(), header)
        MsgType.MEDIA_CMD -> dec(MediaCmd.serializer(), header)
        MsgType.NOW_PLAYING_ART -> {
            if (body.size > MAX_ART_BYTES) throw ProtocolException("artwork too large")
            NowPlayingArt(dec(ArtHeader.serializer(), header).hash, body)
        }
        MsgType.RSSI -> dec(Rssi.serializer(), header)
        MsgType.PROXIMITY_CFG -> dec(ProximityCfg.serializer(), header)
        else -> decodeRevision4(type, header, body)
    }

    private fun decodeRevision4(type: Int, header: String, body: ByteArray): Message = when (type) {
        MsgType.NOTIF_ACCESS -> dec(NotifAccess.serializer(), header)
        MsgType.CALL_DIAL -> dec(CallDial.serializer(), header)
        MsgType.CALL_DIAL_RESULT -> dec(CallDialResult.serializer(), header)
        MsgType.CALL_LIVE -> dec(CallLive.serializer(), header)
        MsgType.CALL_LOG_REQ -> dec(CallLogReq.serializer(), header)
        MsgType.CALL_LOG -> dec(CallLog.serializer(), header)
        MsgType.CONTACT_SEARCH -> dec(ContactSearch.serializer(), header)
        MsgType.CONTACTS -> dec(Contacts.serializer(), header)
        else -> decodeRevision5(type, header, body)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun decodeRevision5(type: Int, header: String, body: ByteArray): Message = when (type) {
        MsgType.OTP -> dec(Otp.serializer(), header)
        MsgType.FS_LIST_REQ -> dec(FsListReq.serializer(), header)
        MsgType.FS_LIST -> dec(FsList.serializer(), header)
        MsgType.FS_GET -> dec(FsGet.serializer(), header)
        MsgType.FS_GET_RESULT -> dec(FsGetResult.serializer(), header)
        MsgType.THUMB_REQ -> dec(ThumbReq.serializer(), header)
        MsgType.THUMB -> {
            if (body.size > MAX_THUMB_BYTES) throw ProtocolException("thumbnail too large")
            val h = dec(ThumbHeader.serializer(), header)
            Thumb(h.ref, h.ok, body, h.reason)
        }
        MsgType.PHOTOS_REQ -> dec(PhotosReq.serializer(), header)
        MsgType.PHOTOS -> dec(Photos.serializer(), header)
        MsgType.CONTINUE -> {
            if (body.size > MAX_THUMB_BYTES) throw ProtocolException("thumbnail too large")
            ContinueCard.of(dec(ContinueHeader.serializer(), header), body)
        }
        MsgType.OPEN_ON_PHONE -> dec(OpenOnPhone.serializer(), header)
        MsgType.OPEN_RESULT -> dec(OpenResult.serializer(), header)
        MsgType.TYPE_TEXT -> dec(TypeText.serializer(), header)
        MsgType.TYPE_RESULT -> dec(TypeResult.serializer(), header)
        MsgType.DND_STATE -> dec(DndState.serializer(), header)
        MsgType.PHONE_STATUS -> dec(PhoneStatus.serializer(), header)
        MsgType.PHONE_STATUS_REQ -> dec(PhoneStatusReq.serializer(), header)
        else -> UnknownMessage(type)
    }

    /** Largest THUMB / CONTINUE body accepted (the phone sends ≤ 48 KiB JPEGs). */
    const val MAX_THUMB_BYTES: Int = 96 * 1024

    /** Largest NOW_PLAYING_ART body accepted (the phone sends ≤ 96 KiB JPEGs). */
    const val MAX_ART_BYTES: Int = 256 * 1024

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

