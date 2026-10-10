package dev.benfranklin.tether.core.proto

import kotlinx.serialization.Serializable

/**
 * Protocol revision 4 (app 0.4.0): calling from the Mac, call control, call history, contact
 * search and the sensitive-notification status. Same rules as revisions 2 and 3: every
 * feature is announced in Hello.caps and only used when the peer announced it; older builds
 * ignore the new message types and the new optional JSON fields.
 */
object Caps4 {
    /** CALL_DIAL / CALL_DIAL_RESULT, CONTACT_SEARCH / CONTACTS, SIMS sent for calling too. */
    const val DIAL = "dial"
    /** CALL_LIVE and the CALL_ACTION actions answer / end / speaker / mute / hold. */
    const val CALL_CONTROL = "callctl"
    /** CALL_LOG_REQ / CALL_LOG. */
    const val CALL_LOG = "calllog"
    /** NOTIF_ACCESS. */
    const val NOTIF_ACCESS = "notifaccess"

    val ALL: List<String> = listOf(DIAL, CALL_CONTROL, CALL_LOG, NOTIF_ACCESS)
}

/** Actions added to [CallAction] in revision 4 (sent only to peers with [Caps4.CALL_CONTROL]). */
object CallActions4 {
    const val ANSWER = "answer"
    const val END = "end"
    const val SPEAKER = "speaker"
    const val MUTE = "mute"
    const val HOLD = "hold"
}

// ---------------- Dialing ----------------

/** Mac -> phone: call [number] from SIM [sub] (subscription id; null = the phone's default). */
@Serializable data class CallDial(val ref: String, val number: String, val sub: Int? = null) : Message {
    override val type get() = MsgType.CALL_DIAL
}

/**
 * Phone -> Mac: how [CallDial] went. [via] is "telecom" when the phone placed the call itself,
 * or "notification" when Android needed a tap on the phone to start it. [reason] on failure:
 * "needs_permission", "disabled", "invalid_number", "no_sim", "busy", "failed".
 */
@Serializable data class CallDialResult(
    val ref: String,
    val ok: Boolean,
    val reason: String? = null,
    val via: String? = null,
) : Message {
    override val type get() = MsgType.CALL_DIAL_RESULT
}

// ---------------- Live call ----------------

object CallDirs {
    const val IN = "in"
    const val OUT = "out"
}

object CallLiveStates {
    const val RINGING = "ringing"
    /** Outgoing call placed, not known to be answered (Android doesn't tell other apps). */
    const val DIALING = "dialing"
    const val ACTIVE = "active"
    const val ENDED = "ended"
}

/**
 * Phone -> Mac: the call in progress, sent on every change (and once on connect while a call
 * is up). [since] is the phone's clock (ms) when the call went off-hook, 0 while ringing.
 * The can* flags say which controls work on this phone right now; the Mac hides the rest.
 */
@Serializable data class CallLive(
    val id: String,
    val dir: String,
    val state: String,
    val number: String? = null,
    val name: String? = null,
    val since: Long = 0,
    val speaker: Boolean = false,
    val muted: Boolean = false,
    val held: Boolean = false,
    val canAnswer: Boolean = false,
    val canEnd: Boolean = false,
    val canSpeaker: Boolean = false,
    val canMute: Boolean = false,
    val canHold: Boolean = false,
    val sub: Int? = null,
    val ts: Long,
    /** Why the last Mac action on this call failed, if it did. */
    val reason: String? = null,
) : Message {
    override val type get() = MsgType.CALL_LIVE
}

// ---------------- Call history ----------------

object CallLogTypes {
    const val IN = "in"
    const val OUT = "out"
    const val MISSED = "missed"
    const val REJECTED = "rejected"
    const val BLOCKED = "blocked"
    const val VOICEMAIL = "voicemail"
    const val OTHER = "other"
}

/** One row of the phone's call log. [dur] in seconds; [isNew] = missed call not yet seen on the phone. */
@Serializable data class CallLogEntry(
    val id: Long,
    val number: String,
    val name: String? = null,
    val type: String,
    val date: Long,
    val dur: Long = 0,
    val sub: Int? = null,
    val isNew: Boolean = false,
)

/** Mac -> phone: newest [limit] calls older than [before] (ms) when paging. */
@Serializable data class CallLogReq(val limit: Int = 50, val before: Long? = null) : Message {
    override val type get() = MsgType.CALL_LOG_REQ
}

/** Phone -> Mac: calls, newest first. [needs] = "calllog" (permission), "disabled" or "error:<reason>". */
@Serializable data class CallLog(
    val calls: List<CallLogEntry>,
    val more: Boolean = false,
    val needs: String? = null,
    /** Echo of the request's `before`, so the Mac knows a page from a refresh. */
    val before: Long? = null,
) : Message {
    override val type get() = MsgType.CALL_LOG
}

// ---------------- Contacts ----------------

@Serializable data class ContactNumber(val n: String, val label: String? = null)

@Serializable data class ContactInfo(val name: String, val numbers: List<ContactNumber>)

/** Mac -> phone: contacts whose name or number matches [query]. */
@Serializable data class ContactSearch(val query: String, val limit: Int = 20) : Message {
    override val type get() = MsgType.CONTACT_SEARCH
}

/** Phone -> Mac: the answer to [ContactSearch]. [needs] = "contacts" when READ_CONTACTS is missing. */
@Serializable data class Contacts(
    val query: String,
    val contacts: List<ContactInfo>,
    val needs: String? = null,
) : Message {
    override val type get() = MsgType.CONTACTS
}

// ---------------- Sensitive notifications ----------------

/**
 * Phone -> Mac: whether Android 15+ hides sensitive notification content (one-time codes)
 * from Tether. [redacting] = a redacted notification was seen and access is not granted;
 * [granted] = the RECEIVE_SENSITIVE_NOTIFICATIONS app op is allowed (null when unknown).
 */
@Serializable data class NotifAccess(
    val redacting: Boolean,
    val granted: Boolean? = null,
    val sdk: Int,
    val oem: String = "",
    val ts: Long,
) : Message {
    override val type get() = MsgType.NOTIF_ACCESS
}
