package dev.benfranklin.tether.core.proto

import kotlinx.serialization.Serializable

/**
 * Feature messages added in protocol revision 2 (app 0.2.0). The wire format and the
 * handshake are unchanged (Hello.v is still 1): each feature is announced in Hello.caps and
 * a peer only uses a feature the other side announced. Older builds ignore the new message
 * types and the new optional JSON fields, so 0.1.x and 0.2.x interoperate.
 */
object Caps {
    const val CLIP = "clip"
    const val NOTIF = "notif"
    const val FILE = "file"
    const val REPLY = "reply"
    const val MEDIA = "media"
    const val BATTERY = "battery"
    const val RING = "ring"
    const val SCREEN = "screen"

    /** Everything a 0.1.x build announced. */
    val V1: List<String> = listOf(CLIP, NOTIF, FILE)

    /** What this build announces. */
    val CURRENT: List<String> =
        V1 + listOf(REPLY, MEDIA, BATTERY, RING, SCREEN) + Caps3.ALL + Caps4.ALL + Caps5.ALL + Caps6.ALL + Caps7.ALL + Caps8.ALL
}

/** One button on a mirrored notification. [reply] marks a RemoteInput (inline text) action. */
@Serializable
data class NotifActionInfo(val id: String, val title: String, val reply: Boolean = false)

/** Mac -> phone: run action [action] of notification [key], with [text] for a reply. */
@Serializable data class NotifAction(val key: String, val action: String, val text: String? = null) : Message {
    override val type get() = MsgType.NOTIF_ACTION
}

/** Phone -> Mac: how [NotifAction] went. */
@Serializable data class NotifActionResult(
    val key: String,
    val action: String,
    val ok: Boolean,
    val reason: String? = null,
) : Message {
    override val type get() = MsgType.NOTIF_ACTION_RESULT
}

/**
 * Phone -> Mac battery and network snapshot. [plug] is "ac", "usb", "wireless" or null;
 * [net] is "wifi", "cellular", "ethernet" or "none"; [gen] e.g. "5G" (needs a permission,
 * so optional); [signal] is the cellular signal level 0-4 when known.
 */
@Serializable data class Battery(
    val level: Int,
    val charging: Boolean,
    val plug: String? = null,
    val net: String? = null,
    val gen: String? = null,
    val signal: Int? = null,
    val ts: Long,
) : Message {
    override val type get() = MsgType.BATTERY
}

/** Mac -> phone: ring loudly until stopped or [timeoutS] seconds pass. */
@Serializable data class Ring(val timeoutS: Int = 60) : Message { override val type get() = MsgType.RING }

@Serializable data class RingStop(val reason: String = "user") : Message { override val type get() = MsgType.RING_STOP }

/** Phone -> Mac: whether the phone is ringing now, and why it stopped or couldn't ring. */
@Serializable data class RingState(val ringing: Boolean, val reason: String? = null) : Message {
    override val type get() = MsgType.RING_STATE
}

/** Mac -> phone: please share your screen. Limits are upper bounds; the phone may go lower. */
@Serializable data class ScreenStart(
    val maxSize: Int = 1280,
    val fps: Int = 60,
    val bitrate: Int = 6_000_000,
) : Message {
    override val type get() = MsgType.SCREEN_START
}

/**
 * Phone -> Mac: the screen session's state. [state] is one of [ScreenStates]; [w]/[h] are the
 * encoded frame size and [rotation] the display rotation in degrees (0, 90, 180, 270) while
 * live. [control] says whether input (the accessibility service) is available.
 */
@Serializable data class ScreenState(
    val state: String,
    val w: Int = 0,
    val h: Int = 0,
    val rotation: Int = 0,
    val control: Boolean = false,
    val reason: String? = null,
) : Message {
    override val type get() = MsgType.SCREEN_STATE
}

object ScreenStates {
    /** Waiting for the user to approve the capture prompt on the phone. */
    const val CONSENT = "consent"
    const val LIVE = "live"
    const val STOPPED = "stopped"
    const val DENIED = "denied"
    /** Needs the Wi-Fi fast path (screen video never goes over Bluetooth). */
    const val NEEDS_WIFI = "needs_wifi"
    const val ERROR = "error"
}

@Serializable data class ScreenStop(val reason: String = "user") : Message { override val type get() = MsgType.SCREEN_STOP }

/**
 * One H.264 access unit (Annex B byte stream) of stream [src] ("screen"). Frames larger than
 * [VideoFrame.MAX_PART] are split: [part] of [parts], same [seq]. [config] marks SPS/PPS,
 * [key] an IDR frame. [pts] is in microseconds. Wi-Fi only.
 */
class VideoFrame(
    val src: String,
    val seq: Long,
    val pts: Long,
    val key: Boolean,
    val config: Boolean,
    val part: Int,
    val parts: Int,
    val data: ByteArray,
) : Message {
    override val type get() = MsgType.VIDEO_FRAME

    override fun equals(other: Any?) = other is VideoFrame && other.src == src && other.seq == seq && other.pts == pts &&
        other.key == key && other.config == config && other.part == part && other.parts == parts &&
        other.data.contentEquals(data)

    override fun hashCode(): Int = (seq.hashCode() * PRIME + part) * PRIME + data.contentHashCode()

    override fun toString() = "VideoFrame(src=$src, seq=$seq, key=$key, config=$config, part=$part/$parts, len=${data.size})"

    companion object {
        private const val PRIME = 31
        /** Largest body per message: keeps every record well under [Protocol.MAX_TCP_RECORD]. */
        const val MAX_PART: Int = 1024 * 1024
    }
}

/**
 * Mac -> phone input. Coordinates are normalised (0..1) in the frame the Mac is showing, which
 * has the size and rotation of the last [ScreenState]. [k] is one of [InputKinds].
 */
@Serializable data class ScreenInput(
    val k: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val x2: Double? = null,
    val y2: Double? = null,
    val dur: Long? = null,
    val text: String? = null,
    /** Frame rotation the coordinates refer to (so a rotation in flight can be corrected). */
    val rot: Int? = null,
) : Message {
    override val type get() = MsgType.SCREEN_INPUT
}

object InputKinds {
    const val TAP = "tap"
    const val LONG_PRESS = "long"
    const val SWIPE = "swipe"
    const val SCROLL = "scroll"
    const val TEXT = "text"
    const val BACKSPACE = "backspace"
    const val ENTER = "enter"
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
}

/** Mac -> phone receive statistics, about once a second, for adaptive bitrate. */
@Serializable data class VideoFeedback(
    val src: String = "screen",
    val kbps: Int = 0,
    val lagMs: Int = 0,
    val dropped: Int = 0,
    /** The decoder lost sync: send a keyframe. */
    val keyframe: Boolean = false,
) : Message {
    override val type get() = MsgType.VIDEO_FEEDBACK
}

@Serializable internal data class VideoHeader(
    val src: String,
    val seq: Long,
    val pts: Long,
    val key: Boolean = false,
    val config: Boolean = false,
    val part: Int = 0,
    val parts: Int = 1,
)
