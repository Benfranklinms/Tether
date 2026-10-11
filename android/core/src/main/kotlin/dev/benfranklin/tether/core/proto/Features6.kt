package dev.benfranklin.tether.core.proto

import kotlinx.serialization.Serializable

/**
 * Protocol revision 6 (app 0.5.2): Insert from Phone (camera / document scan into the Mac's
 * frontmost app), Find my Mac (loud sound + "Found it" overlay, or lock) and Instant Hotspot.
 * Same rules as revisions 2-5: announced in Hello.caps, only used when the peer announced it,
 * older builds ignore the new types. Every message travels inside the paired, Noise-encrypted
 * session, so only the paired phone/Mac can trigger them and the hotspot password is never
 * sent in the clear.
 */
object Caps6 {
    /** CAPTURE_REQ / CAPTURE_RESULT; the picture arrives as FILE_OFFER.kind = "capture". */
    const val CAPTURE = "capture"
    /** FIND_MAC / FIND_MAC_STATE. */
    const val FIND_MAC = "findmac"
    /** HOTSPOT_REQ / HOTSPOT_STATE. */
    const val HOTSPOT = "hotspot"
    /** SHELF_SYNC / SHELF_FILE_REQ; shelf files arrive as FILE_OFFER.kind = "shelf". */
    const val SHELF = "shelf"
    val ALL: List<String> = listOf(CAPTURE, FIND_MAC, HOTSPOT, SHELF)
}

object FileKinds6 {
    /** A photo or scan the Mac asked for with CAPTURE_REQ: insert it into the frontmost app. */
    const val CAPTURE = "capture"
    /** A Shelf FILE item the receiver asked for with SHELF_FILE_REQ. */
    const val SHELF = "shelf"
}

object CaptureKinds {
    const val PHOTO = "photo"
    const val SCAN = "scan"
}

/** Why a capture didn't produce a file. Anything else is "error:<detail>". */
object CaptureReasons {
    const val CANCELLED = "cancelled"
    const val DISABLED = "disabled"
    const val BUSY = "busy"
    const val NO_CAMERA = "no_camera"
    /** The phone can't open a screen from the background; it posted a tap-to-open notification. */
    const val TAP_PHONE = "tap_phone"
}

/** Mac -> phone: open the camera ([CaptureKinds.PHOTO]) or the document scanner ([CaptureKinds.SCAN]). */
@Serializable data class CaptureReq(val ref: String, val kind: String) : Message {
    override val type get() = MsgType.CAPTURE_REQ
}

/**
 * Phone -> Mac. [state] "open" (camera/scanner is on screen), "sending" (file [id]/[name] is on
 * its way), "done" or "failed" with [reason] (see [CaptureReasons]). [pages] for scans.
 */
@Serializable data class CaptureResult(
    val ref: String,
    val state: String,
    val id: String? = null,
    val name: String? = null,
    val reason: String? = null,
    val pages: Int = 0,
    val via: String? = null,
) : Message {
    override val type get() = MsgType.CAPTURE_RESULT
}

object CaptureStates {
    const val OPEN = "open"
    const val SENDING = "sending"
    const val DONE = "done"
    const val FAILED = "failed"
}

object FindActions {
    const val RING = "ring"
    const val STOP = "stop"
    const val LOCK = "lock"
}

/** Phone -> Mac: [FindActions.RING] (loud looping sound + "Found it" overlay), STOP, or LOCK. */
@Serializable data class FindMac(val id: String, val action: String) : Message {
    override val type get() = MsgType.FIND_MAC
}

/** Mac -> phone: [ringing] after each action; [locked] after LOCK; [reason] when it failed. */
@Serializable data class FindMacState(
    val id: String,
    val ringing: Boolean,
    val ok: Boolean = true,
    val locked: Boolean = false,
    val reason: String? = null,
    /** "mac" when the Mac's user pressed Found it. */
    val by: String? = null,
) : Message {
    override val type get() = MsgType.FIND_MAC_STATE
}

/** Mac -> phone: turn the hotspot on (or off) and send its credentials back. */
@Serializable data class HotspotReq(val ref: String, val on: Boolean = true) : Message {
    override val type get() = MsgType.HOTSPOT_REQ
}

object HotspotStates {
    const val STARTING = "starting"
    /** The phone opened its hotspot settings and asks its user to flip the switch. */
    const val MANUAL = "manual"
    const val ON = "on"
    const val OFF = "off"
    const val FAILED = "failed"
}

/** Why the hotspot couldn't be turned on. Anything else is "error:<detail>". */
object HotspotReasons {
    const val DISABLED = "disabled"
    /** Tether control (accessibility) is off on the phone, so it can't flip the switch. */
    const val NO_SERVICE = "no_service"
    /** The switch wasn't found on this phone's settings screen. */
    const val NO_SWITCH = "no_switch"
    /** The phone has no saved hotspot name/password to send. */
    const val NO_CREDENTIALS = "no_credentials"
    const val TIMEOUT = "timeout"
}

/**
 * Phone -> Mac: hotspot progress. With [state] "on", [ssid] and [psk] (null for an open
 * network) let the Mac join. Only ever sent inside the encrypted session.
 */
@Serializable data class HotspotState(
    val ref: String? = null,
    val state: String,
    val ssid: String? = null,
    val psk: String? = null,
    val reason: String? = null,
) : Message {
    override val type get() = MsgType.HOTSPOT_STATE

    /** Never log the password. */
    override fun toString(): String = "HotspotState(ref=$ref, state=$state, ssid=${ssid != null}, psk=${psk != null}, reason=$reason)"
}

object ShelfKinds {
    const val TEXT = "text"
    const val LINK = "link"
    const val WIFI = "wifi"
    const val ADDRESS = "address"
    const val SNIPPET = "snippet"
    const val FILE = "file"
    val ALL = listOf(TEXT, LINK, WIFI, ADDRESS, SNIPPET, FILE)
}

/** The bytes of a FILE item stay on the device that added it ([owner]) until the other side asks. */
@Serializable data class ShelfFile(val name: String, val size: Long, val mime: String, val sha256: String, val owner: String)

/**
 * One Shelf item. [from] "phone" / "mac". [updated] (ms) decides merges: the newer copy of an
 * id wins, and a removal (tombstone) wins over any copy not newer than it. [order] is a
 * fractional index (lower = higher up); moving an item gives it a new order and a new [updated].
 * [secret] is the Wi-Fi password for kind "wifi".
 */
@Serializable data class ShelfItem(
    val id: String,
    val kind: String,
    val title: String,
    val body: String = "",
    val secret: String? = null,
    val from: String,
    val created: Long,
    val updated: Long,
    val order: Double,
    val file: ShelfFile? = null,
    /** Revision 8 (0.5.4, cap "shelffolders"): the folder this item is in; null = loose. Older builds ignore it. */
    val folderId: String? = null,
)

@Serializable data class ShelfTomb(val id: String, val ts: Long)

/**
 * Both ways: the sender's shelf ([full] = whole state, sent on every connect) or a change.
 * Receivers merge by id + timestamp, so changes made while offline converge on reconnect.
 */
@Serializable data class ShelfSync(
    val items: List<ShelfItem> = emptyList(),
    val removed: List<ShelfTomb> = emptyList(),
    val full: Boolean = false,
    /** Revision 8 (0.5.4, cap "shelffolders"): folders, merged like items (newest [ShelfFolder.updated] wins). */
    val folders: List<ShelfFolder> = emptyList(),
    /** Revision 8: deleted folders (tombstones; a removal wins over any copy not newer than it). */
    val removedFolders: List<ShelfTomb> = emptyList(),
) : Message {
    override val type get() = MsgType.SHELF_SYNC
}

/** Ask the owner of a FILE item for its bytes (answered with FILE_OFFER kind "shelf", name = file name). */
@Serializable data class ShelfFileReq(val id: String) : Message {
    override val type get() = MsgType.SHELF_FILE_REQ
}
