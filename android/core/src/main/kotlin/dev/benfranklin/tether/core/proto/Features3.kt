package dev.benfranklin.tether.core.proto

import kotlinx.serialization.Serializable

/**
 * Protocol revision 3 (app 0.3.0): SMS, call alerts, now playing, proximity. Same rules as
 * revision 2: announced in Hello.caps, ignored by older builds.
 */
object Caps3 {
    const val SMS = "sms"
    const val CALL = "call"
    const val NOW_PLAYING = "nowplaying"
    const val PROXIMITY = "proximity"

    val ALL: List<String> = listOf(SMS, CALL, NOW_PLAYING, PROXIMITY)
}

// ---------------- SMS ----------------

/** One SIM the phone can send from. [id] is the Android subscription id. */
@Serializable data class SimInfo(val id: Int, val slot: Int, val name: String, val number: String? = null)

/** An MMS attachment, listed so the Mac can label it ("Photo", "Video"); its data is not sent in 0.3.0. */
@Serializable data class SmsAttachment(val id: String, val mime: String, val w: Int = 0, val h: Int = 0)

/**
 * One message. [id] is "s<n>" for SMS and "m<n>" for MMS (provider ids). [out] is true for
 * messages this phone sent. [status] is "received", "sent", "pending" or "failed".
 */
@Serializable data class SmsMessage(
    val id: String,
    val thread: Long,
    val addr: String,
    val name: String? = null,
    val body: String = "",
    val date: Long,
    val out: Boolean = false,
    val status: String = "received",
    val sub: Int? = null,
    val att: List<SmsAttachment> = emptyList(),
    val read: Boolean = true,
)

/** A conversation in the list. [addrs]/[names] are its participants. */
@Serializable data class SmsThread(
    val id: Long,
    val addrs: List<String>,
    val names: List<String?> = emptyList(),
    val snippet: String = "",
    val date: Long,
    val unread: Int = 0,
    val outLast: Boolean = false,
)

/** Mac -> phone: conversations, newest first, older than [before] (ms) when paging; [query] filters by name/number. */
@Serializable data class SmsThreadsReq(val limit: Int = 50, val before: Long? = null, val query: String? = null) : Message {
    override val type get() = MsgType.SMS_THREADS_REQ
}

@Serializable data class SmsThreads(
    val threads: List<SmsThread>,
    val more: Boolean = false,
    val query: String? = null,
    /**
     * Why the list is empty or partial: a missing permission ("sms"), "disabled", or
     * "error:<reason>" when the phone's message store failed (the list may still hold what was
     * read before the failure).
     */
    val needs: String? = null,
) : Message {
    override val type get() = MsgType.SMS_THREADS
}

/** Mac -> phone: messages of [thread], newest [limit] before [before] (ms). */
@Serializable data class SmsThreadReq(val thread: Long, val limit: Int = 60, val before: Long? = null) : Message {
    override val type get() = MsgType.SMS_THREAD_REQ
}

/**
 * Phone -> Mac: a page of messages (oldest first). [search] is set for search results.
 * [error] (revision 3.1) says why the page is empty: "disabled", "sms" or "error:<reason>".
 */
@Serializable data class SmsMessages(
    val thread: Long,
    val msgs: List<SmsMessage>,
    val more: Boolean = false,
    val search: String? = null,
    val error: String? = null,
) : Message {
    override val type get() = MsgType.SMS_MESSAGES
}

/** Mac -> phone: send [body] to [to] (one SMS per recipient) from SIM [sub]. [ref] matches the result. */
@Serializable data class SmsSend(
    val ref: String,
    val to: List<String>,
    val body: String,
    val thread: Long? = null,
    val sub: Int? = null,
) : Message {
    override val type get() = MsgType.SMS_SEND
}

@Serializable data class SmsSendResult(val ref: String, val ok: Boolean, val reason: String? = null) : Message {
    override val type get() = MsgType.SMS_SEND_RESULT
}

/** Phone -> Mac: a message arrived (or one we sent changed status). */
@Serializable data class SmsNew(val msg: SmsMessage) : Message { override val type get() = MsgType.SMS_NEW }

/** Mac -> phone: full-text search over message bodies (answered with SMS_MESSAGES, search = query). */
@Serializable data class SmsSearch(val query: String, val limit: Int = 50) : Message {
    override val type get() = MsgType.SMS_SEARCH
}

/** Phone -> Mac: SIMs to send from (sent on connect and when they change). */
@Serializable data class Sims(val sims: List<SimInfo>) : Message { override val type get() = MsgType.SIMS }

// ---------------- Calls ----------------

object CallStates {
    const val RINGING = "ringing"
    const val OFFHOOK = "offhook"
    const val ENDED = "ended"
    const val MISSED = "missed"
}

/** Phone -> Mac: incoming call [id] changed state. [number]/[name] when the phone may reveal them. */
@Serializable data class CallState(
    val id: String,
    val state: String,
    val number: String? = null,
    val name: String? = null,
    val ts: Long,
    /** Why a Mac action failed ("not_allowed", "no_call"), if this answers one. */
    val reason: String? = null,
) : Message {
    override val type get() = MsgType.CALL_STATE
}

object CallActions {
    const val DECLINE = "decline"
    const val SILENCE = "silence"
}

/**
 * Mac -> phone: "decline" or "silence" the ringing call [id]; with [Caps4.CALL_CONTROL] also
 * "answer" ([speaker] = turn the speaker on once answered), "end", and "speaker" / "mute" /
 * "hold" set to [on].
 */
@Serializable data class CallAction(
    val id: String,
    val action: String,
    val on: Boolean? = null,
    val speaker: Boolean? = null,
) : Message { override val type get() = MsgType.CALL_ACTION }

// ---------------- Now playing ----------------

/** Phone -> Mac: what's playing. [art] is a hash of the artwork sent separately (NOW_PLAYING_ART). */
@Serializable data class NowPlaying(
    val active: Boolean,
    val pkg: String = "",
    val app: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val canSeek: Boolean = false,
    val canNext: Boolean = false,
    val canPrev: Boolean = false,
    /** Music stream volume 0..1. */
    val volume: Float? = null,
    val art: String? = null,
    val ts: Long,
) : Message {
    override val type get() = MsgType.NOW_PLAYING
}

/** Phone -> Mac: artwork JPEG for [hash] in the body. */
class NowPlayingArt(val hash: String, val data: ByteArray) : Message {
    override val type get() = MsgType.NOW_PLAYING_ART
    override fun equals(other: Any?) = other is NowPlayingArt && other.hash == hash && other.data.contentEquals(data)
    override fun hashCode() = hash.hashCode() * PRIME + data.contentHashCode()
    override fun toString() = "NowPlayingArt(hash=$hash, len=${data.size})"
    private companion object { const val PRIME = 31 }
}

/** Mac -> phone: "play", "pause", "toggle", "next", "prev", "seek" ([pos] ms) or "volume" ([vol] 0..1). */
@Serializable data class MediaCmd(val cmd: String, val pos: Long? = null, val vol: Float? = null) : Message {
    override val type get() = MsgType.MEDIA_CMD
}

// ---------------- Proximity ----------------

/** Mac -> phone: report Bluetooth signal strength every [intervalMs] while [on]. */
@Serializable data class ProximityCfg(val on: Boolean, val intervalMs: Int = 2000) : Message {
    override val type get() = MsgType.PROXIMITY_CFG
}

object MediaCmds {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val TOGGLE = "toggle"
    const val NEXT = "next"
    const val PREV = "prev"
    const val SEEK = "seek"
    const val VOLUME = "volume"
}

/** Phone -> Mac: RSSI (dBm) of the Bluetooth link as the phone measures it. */
@Serializable data class Rssi(val rssi: Int, val ts: Long) : Message { override val type get() = MsgType.RSSI }

@Serializable internal data class ArtHeader(val hash: String)

