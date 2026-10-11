package dev.benfranklin.tether.core.proto

import kotlinx.serialization.Serializable

/**
 * Protocol revision 7 (app 0.5.3): the Mac's own status for the phone's Home panels — battery
 * and what is playing on the Mac — plus play/pause/skip from the phone. Same rules as
 * revisions 2-6: announced in Hello.caps, only used when the peer announced it, older builds
 * ignore the new types. Everything travels inside the paired, Noise-encrypted session.
 */
object Caps7 {
    /** MAC_STATUS / MAC_STATUS_REQ / MAC_MEDIA_CMD. */
    const val MAC_STATUS = "macstatus"
    val ALL: List<String> = listOf(MAC_STATUS)
}

/** What is playing on the Mac; [active] false = nothing (or the Mac can't tell). */
@Serializable data class MacMedia(
    val active: Boolean,
    val title: String = "",
    val artist: String = "",
    val app: String = "",
    val playing: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
)

/**
 * Mac -> phone: battery ([battery] null on a Mac without one) and, when the phone asked for
 * it, what is playing. Sent on connect, when the battery changes, and in answer to
 * MAC_STATUS_REQ / MAC_MEDIA_CMD.
 */
@Serializable data class MacStatus(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val media: MacMedia? = null,
    val ts: Long,
) : Message {
    override val type get() = MsgType.MAC_STATUS
}

/** Phone -> Mac: send MAC_STATUS now; [media] = include what is playing (the Now playing panel is showing). */
@Serializable data class MacStatusReq(val media: Boolean = false) : Message {
    override val type get() = MsgType.MAC_STATUS_REQ
}

object MacMediaCmds {
    const val TOGGLE = "toggle"
    const val NEXT = "next"
    const val PREV = "prev"
    val ALL = setOf(TOGGLE, NEXT, PREV)
}

/** Phone -> Mac: control what is playing on the Mac. Unknown commands are ignored. */
@Serializable data class MacMediaCmd(val cmd: String) : Message {
    override val type get() = MsgType.MAC_MEDIA_CMD
}
