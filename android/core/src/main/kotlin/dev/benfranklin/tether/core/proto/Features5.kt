package dev.benfranklin.tether.core.proto

import kotlinx.serialization.Serializable

/**
 * Protocol revision 5 (app 0.5.0): phone file and photo browsing, one-time codes, Continue on
 * Mac cards, open on phone, typing into the phone, Focus/DND sync, the phone's setup status
 * and clipboard images. Same rules as revisions 2-4: every feature is announced in Hello.caps
 * and only used when the peer announced it; older builds ignore the new message types, the
 * new optional JSON fields and the new FILE_OFFER kinds they never asked for.
 */
object Caps5 {
    /** FS_LIST_REQ / FS_LIST, FS_GET / FS_GET_RESULT, THUMB_REQ / THUMB for files. */
    const val FS = "fs"
    /** PHOTOS_REQ / PHOTOS (+ THUMB and FS_GET with root "photos"). */
    const val PHOTOS = "photos"
    /** OTP: a one-time code the phone saw, for the Mac clipboard. */
    const val OTP = "otp"
    /** CONTINUE cards. */
    const val CONTINUE = "continue"
    /** OPEN_ON_PHONE / OPEN_RESULT. */
    const val OPEN_URL = "openurl"
    /** TYPE_TEXT / TYPE_RESULT. */
    const val TYPE = "type"
    /** DND_STATE in both directions. */
    const val DND = "dnd"
    /** PHONE_STATUS / PHONE_STATUS_REQ. */
    const val STATUS = "status"
    /** Clipboard images travel as file transfers with FILE_OFFER.kind = "clip". */
    const val CLIP_IMAGE = "clipimg"
    /** 0.5.1: ALBUMS_REQ / ALBUMS and PHOTOS_REQ album/filter, PhotoItem duration/bucket/favorite. */
    const val ALBUMS = "albums"

    val ALL: List<String> = listOf(FS, PHOTOS, OTP, CONTINUE, OPEN_URL, TYPE, DND, STATUS, CLIP_IMAGE, ALBUMS)
}

/** FILE_OFFER kinds added in revision 5. */
object FileKinds5 {
    /** A clipboard image: the receiver puts it on the clipboard instead of saving it as a download. */
    const val CLIP = "clip"
    /** A file the Mac asked for with FS_GET (browser download / drag to Finder). */
    const val FS = "fs"
}

/** Roots the phone file browser exposes. Paths are always relative to one of these. */
object FsRoots {
    const val DOWNLOADS = "downloads"
    const val DOCUMENTS = "documents"
    const val WHATSAPP = "whatsapp"
    const val DCIM = "dcim"
    const val PICTURES = "pictures"
    /** MediaStore photos and videos; path = the MediaStore id. */
    const val PHOTOS = "photos"

    val BROWSABLE: List<String> = listOf(DOWNLOADS, DOCUMENTS, WHATSAPP, DCIM, PICTURES)
}

// ---------------- Files ----------------

/** Mac -> phone: list [path] (relative, "" = the root itself) under [root]. */
@Serializable data class FsListReq(
    val ref: String,
    val root: String,
    val path: String = "",
    val offset: Int = 0,
    val limit: Int = 200,
) : Message { override val type get() = MsgType.FS_LIST_REQ }

/** One directory entry. [mtime] in ms; [size] is 0 for directories. */
@Serializable data class FsEntry(
    val name: String,
    val dir: Boolean = false,
    val size: Long = 0,
    val mtime: Long = 0,
    val mime: String? = null,
)

/**
 * Phone -> Mac: the listing. [needs] = "allfiles" (All files access missing), "disabled",
 * "missing" (no such folder) or "error:<reason>"; entries are then empty.
 */
@Serializable data class FsList(
    val ref: String,
    val root: String,
    val path: String,
    val entries: List<FsEntry>,
    val total: Int = entries.size,
    val more: Boolean = false,
    val needs: String? = null,
) : Message { override val type get() = MsgType.FS_LIST }

/** Mac -> phone: send me this file (it arrives as a transfer with kind "fs"). */
@Serializable data class FsGet(val ref: String, val root: String, val path: String) : Message {
    override val type get() = MsgType.FS_GET
}

/** Phone -> Mac: the FS_GET was queued as transfer [transferId], or why not. */
@Serializable data class FsGetResult(
    val ref: String,
    val ok: Boolean,
    val transferId: String? = null,
    val name: String? = null,
    val size: Long = 0,
    val reason: String? = null,
) : Message { override val type get() = MsgType.FS_GET_RESULT }

/** Mac -> phone: a small JPEG preview of [path] under [root] (an image, a video, or a photo id). */
@Serializable data class ThumbReq(val ref: String, val root: String, val path: String, val size: Int = 256) : Message {
    override val type get() = MsgType.THUMB_REQ
}

/** Phone -> Mac: the preview; [data] (JPEG, ≤ [MessageCodec.MAX_THUMB_BYTES]) travels in the body. */
class Thumb(val ref: String, val ok: Boolean, val data: ByteArray, val reason: String? = null) : Message {
    override val type get() = MsgType.THUMB
    override fun equals(other: Any?) =
        other is Thumb && other.ref == ref && other.ok == ok && other.reason == reason && other.data.contentEquals(data)
    override fun hashCode() = (ref.hashCode() * HASH_PRIME + ok.hashCode()) * HASH_PRIME + data.contentHashCode()
    override fun toString() = "Thumb(ref=$ref, ok=$ok, len=${data.size}, reason=$reason)"
    private companion object { const val HASH_PRIME = 31 }
}

// ---------------- Photos ----------------

/** Mac -> phone: newest [limit] photos/videos taken before [before] (ms) when paging. */
/**
 * Mac -> phone: a page of the camera roll. 0.5.1 (cap "albums"): [album] = an [AlbumInfo.id]
 * (null = all), [filter] = [PhotoFilters] value (null = everything). Older phones ignore both.
 */
@Serializable data class PhotosReq(
    val ref: String,
    val limit: Int = 60,
    val before: Long? = null,
    val album: String? = null,
    val filter: String? = null,
) : Message {
    override val type get() = MsgType.PHOTOS_REQ
}

@Serializable data class PhotoItem(
    val id: String,
    val name: String,
    val size: Long = 0,
    val date: Long = 0,
    val w: Int = 0,
    val h: Int = 0,
    val mime: String = "image/jpeg",
    val video: Boolean = false,
    /** 0.5.1: video duration in ms (0 = unknown or a photo). */
    val dur: Long = 0,
    /** 0.5.1: MediaStore bucket id. */
    val bucket: String? = null,
    /** 0.5.1: MediaStore IS_FAVORITE (Android 11+). */
    val fav: Boolean = false,
)

/** PhotosReq.filter values (0.5.1). */
object PhotoFilters {
    const val PHOTOS = "photos"
    const val VIDEOS = "videos"
    const val SCREENSHOTS = "screenshots"
}

/** Album kinds (0.5.1). Built-in albums first, then other MediaStore buckets. */
object AlbumKinds {
    const val ALL = "all"
    const val CAMERA = "camera"
    const val SCREENSHOTS = "screenshots"
    const val WHATSAPP = "whatsapp"
    const val DOWNLOADS = "downloads"
    const val VIDEOS = "videos"
    const val FAVORITES = "favorites"
    const val BUCKET = "bucket"
}

/** One album: [id] goes back in PhotosReq.album; [cover] is a photo id for THUMB_REQ root "photos". */
@Serializable data class AlbumInfo(
    val id: String,
    val name: String,
    val count: Int,
    val cover: String? = null,
    val kind: String = AlbumKinds.BUCKET,
)

/** Mac -> phone: list the albums (0.5.1). */
@Serializable data class AlbumsReq(val ref: String) : Message {
    override val type get() = MsgType.ALBUMS_REQ
}

/** Phone -> Mac: albums with counts and covers. [needs] as in [Photos]. */
@Serializable data class Albums(val ref: String, val albums: List<AlbumInfo>, val needs: String? = null) : Message {
    override val type get() = MsgType.ALBUMS
}

/** Phone -> Mac: newest first. [needs] = "photos" (permission), "disabled" or "error:<reason>". */
@Serializable data class Photos(
    val ref: String,
    val items: List<PhotoItem>,
    val more: Boolean = false,
    val needs: String? = null,
    val before: Long? = null,
) : Message { override val type get() = MsgType.PHOTOS }

// ---------------- One-time codes ----------------

/** Phone -> Mac: a one-time code from an SMS ([src] "sms") or a notification ("notif") of [app]. */
@Serializable data class Otp(val code: String, val app: String, val src: String, val ts: Long) : Message {
    override val type get() = MsgType.OTP
}

// ---------------- Continue on Mac ----------------

object ContinueKinds {
    const val PHOTO = "photo"
    const val DOWNLOAD = "download"
    const val LINK = "link"
    const val SHARE = "share"
    const val SONG = "song"
    const val TAB = "tab"
}

/**
 * Phone -> Mac: something to pick up on the Mac. [url] for links/tabs/songs (a search URL the
 * Mac may rewrite for Spotify / YouTube Music); [root]+[path] for a file the Mac fetches with
 * FS_GET when the user opens it; [ttl] seconds until the card expires. A card with the same
 * [id] replaces the previous one. An optional JPEG thumbnail travels in the body.
 */
class ContinueCard(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val url: String? = null,
    val root: String? = null,
    val path: String? = null,
    val app: String? = null,
    val size: Long = 0,
    val ts: Long,
    val ttl: Int = DEFAULT_TTL,
    val thumb: ByteArray = ByteArray(0),
) : Message {
    override val type get() = MsgType.CONTINUE

    fun header() = ContinueHeader(id, kind, title, subtitle, url, root, path, app, size, ts, ttl)

    override fun equals(other: Any?) = other is ContinueCard && other.header() == header() && other.thumb.contentEquals(thumb)
    override fun hashCode() = header().hashCode() * HASH_PRIME + thumb.contentHashCode()
    override fun toString() = "ContinueCard(${header()}, thumb=${thumb.size})"

    companion object {
        const val DEFAULT_TTL = 15 * 60
        private const val HASH_PRIME = 31
        fun of(h: ContinueHeader, thumb: ByteArray) =
            ContinueCard(h.id, h.kind, h.title, h.subtitle, h.url, h.root, h.path, h.app, h.size, h.ts, h.ttl, thumb)
    }
}

@Serializable data class ContinueHeader(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val url: String? = null,
    val root: String? = null,
    val path: String? = null,
    val app: String? = null,
    val size: Long = 0,
    val ts: Long,
    val ttl: Int = ContinueCard.DEFAULT_TTL,
)

// ---------------- Open on phone ----------------

/** Mac -> phone: open [url] (http/https/mailto/tel/geo…) or put [text] on the phone's clipboard. */
@Serializable data class OpenOnPhone(val id: String, val url: String? = null, val text: String? = null) : Message {
    override val type get() = MsgType.OPEN_ON_PHONE
}

/**
 * Phone -> Mac: [via] = "activity" (opened), "notification" (Android needed a tap on the
 * phone), "clipboard" (text copied). [reason] on failure: "disabled", "invalid", "no_app", "failed".
 */
@Serializable data class OpenResult(val id: String, val ok: Boolean, val via: String? = null, val reason: String? = null) :
    Message { override val type get() = MsgType.OPEN_RESULT }

// ---------------- Typing ----------------

object TypeKeys {
    const val BACKSPACE = "backspace"
    const val ENTER = "enter"
    const val TAB = "tab"
    const val CLEAR = "clear"
}

/** Mac -> phone: insert [text] into the focused field, or press [key] (see [TypeKeys]). [seq] increases. */
@Serializable data class TypeText(val seq: Long, val text: String? = null, val key: String? = null) : Message {
    override val type get() = MsgType.TYPE_TEXT
}

/**
 * Phone -> Mac: how [TypeText] [seq] went. [reason]: "no_service" (accessibility off),
 * "no_field" (nothing focused that takes text), "disabled", "failed". [field] describes the target.
 */
@Serializable data class TypeResult(val seq: Long, val ok: Boolean, val reason: String? = null, val field: String? = null) :
    Message { override val type get() = MsgType.TYPE_RESULT }

// ---------------- Focus / Do Not Disturb ----------------

/**
 * Either direction: Do Not Disturb (phone) / Focus (Mac) turned [on] on the sender's side.
 * [canSet] tells the other side whether this device can be switched remotely (phone: DND
 * access granted; Mac: a "Tether Focus" shortcut exists).
 */
@Serializable data class DndState(val on: Boolean, val src: String, val ts: Long, val canSet: Boolean? = null) : Message {
    override val type get() = MsgType.DND_STATE
}

// ---------------- Phone setup status ----------------

/** One permission/setting on the phone's setup checklist. */
@Serializable data class PermInfo(val id: String, val label: String, val ok: Boolean, val required: Boolean = false)

/** Phone -> Mac: the phone's setup checklist (sent on connect, on change, and on request). */
@Serializable data class PhoneStatus(val perms: List<PermInfo>, val sdk: Int = 0, val ts: Long) : Message {
    override val type get() = MsgType.PHONE_STATUS
}

/** Mac -> phone: please send PHONE_STATUS now. */
@Serializable data class PhoneStatusReq(val ts: Long = 0) : Message { override val type get() = MsgType.PHONE_STATUS_REQ }
