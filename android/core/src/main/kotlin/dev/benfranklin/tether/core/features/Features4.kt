package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.CallDirs
import dev.benfranklin.tether.core.proto.CallLive
import dev.benfranklin.tether.core.proto.CallLiveStates
import dev.benfranklin.tether.core.proto.CallLogTypes
import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.core.proto.CallStates
import dev.benfranklin.tether.core.proto.NotifAccess

/** Which call controls work on this phone right now (permissions and what Android allows). */
data class CallControls(
    val answer: Boolean = false,
    val end: Boolean = false,
    val speaker: Boolean = false,
    val mute: Boolean = false,
    /** Hold needs the default-dialer role (InCallService); Tether never has it. */
    val hold: Boolean = false,
)

/**
 * Turns the phone's call-state callbacks (RINGING / OFFHOOK / IDLE) into
 *  - the revision-3 [CallState] events older Macs show (incoming calls only, as in 0.3), and
 *  - the revision-4 [CallLive] card: incoming and outgoing calls, timer start, audio state and
 *    which controls work.
 * A call the Mac asked for ([expectOutgoing]) gets its number and name when the phone goes
 * off-hook within [dialWindowMs]. Call waiting: a second incoming call while one is active
 * replaces the card (Android reports only one aggregate state to apps). Single-threaded.
 */
class LiveCallTracker(
    private val idGen: () -> String,
    private val dialWindowMs: Long = 60_000,
) {
    data class Update(val legacy: CallState?, val live: CallLive?)

    private class Call(val id: String, val dir: String, var number: String?, var name: String?, val sub: Int?) {
        var state = CallLiveStates.RINGING
        var since = 0L
        var declinedByMac = false
        var reason: String? = null
    }

    private class Expected(val number: String, val name: String?, val sub: Int?, val at: Long)

    private var call: Call? = null
    private var expected: Expected? = null
    var controls = CallControls()
        private set
    var speaker = false
        private set
    var muted = false
        private set

    /** The incoming call ringing right now, if any. */
    val ringingId: String? get() = call?.takeIf { it.dir == CallDirs.IN && it.state == CallLiveStates.RINGING }?.id

    /** The call that is up (ringing, dialing or active), if any. */
    val currentId: String? get() = call?.id

    val isOffhook: Boolean get() = call?.let { it.state == CallLiveStates.ACTIVE || it.state == CallLiveStates.DIALING } == true

    /** The Mac asked the phone to call [number]; the next outgoing call is that one. */
    fun expectOutgoing(number: String, name: String?, sub: Int?, now: Long) {
        expected = Expected(number, name, sub, now)
    }

    @Suppress("CyclomaticComplexMethod")
    fun onState(state: Int, incomingNumber: String?, contactName: String?, now: Long): Update = when (state) {
        CallTracker.RINGING -> {
            var c = call
            if (c == null || c.state != CallLiveStates.RINGING) {
                // A new incoming call (or a waiting call while another one is up).
                c = Call(idGen(), CallDirs.IN, null, null, null)
                call = c
                resetAudio()
            }
            if (!incomingNumber.isNullOrEmpty()) c.number = incomingNumber
            if (!contactName.isNullOrEmpty()) c.name = contactName
            Update(CallState(c.id, CallStates.RINGING, c.number, c.name, now), live(c, now))
        }
        CallTracker.OFFHOOK -> {
            val c = call
            when {
                c == null -> {
                    val e = expected?.takeIf { now - it.at in 0..dialWindowMs }
                    expected = null
                    val n = Call(idGen(), CallDirs.OUT, e?.number, e?.name, e?.sub)
                    n.state = CallLiveStates.DIALING
                    n.since = now
                    call = n
                    resetAudio()
                    Update(null, live(n, now))
                }
                c.state == CallLiveStates.RINGING -> {
                    c.state = CallLiveStates.ACTIVE
                    c.since = now
                    Update(CallState(c.id, CallStates.OFFHOOK, c.number, c.name, now), live(c, now))
                }
                else -> Update(null, null)
            }
        }
        CallTracker.IDLE -> {
            val c = call
            call = null
            if (c == null) {
                Update(null, null)
            } else {
                val legacy = if (c.dir == CallDirs.IN) {
                    val missed = c.state == CallLiveStates.RINGING && !c.declinedByMac
                    CallState(c.id, if (missed) CallStates.MISSED else CallStates.ENDED, c.number, c.name, now)
                } else {
                    null
                }
                c.state = CallLiveStates.ENDED
                val end = live(c, now)
                resetAudio()
                Update(legacy, end)
            }
        }
        else -> Update(null, null)
    }

    /** A caller name found later (the dialer's notification). */
    fun nameFound(name: String, now: Long): Update {
        val c = call ?: return Update(null, null)
        if (c.name == name) return Update(null, null)
        c.name = name
        val legacy = if (c.dir == CallDirs.IN && c.state == CallLiveStates.RINGING) {
            CallState(c.id, CallStates.RINGING, c.number, c.name, now)
        } else {
            null
        }
        return Update(legacy, live(c, now))
    }

    /** The Mac declined the ringing call, so its end isn't reported as missed. */
    fun declinedFromMac() {
        call?.declinedByMac = true
    }

    /** Controls or audio changed; returns the card to send while a call is up. */
    fun update(controls: CallControls = this.controls, speaker: Boolean = this.speaker, muted: Boolean = this.muted, now: Long): CallLive? {
        val changed = controls != this.controls || speaker != this.speaker || muted != this.muted
        this.controls = controls
        this.speaker = speaker
        this.muted = muted
        val c = call ?: return null
        return if (changed) live(c, now) else null
    }

    /** A Mac action on the current call failed: report [reason] on the card. */
    fun failed(reason: String, now: Long): CallLive? {
        val c = call ?: return null
        c.reason = reason
        val l = live(c, now)
        c.reason = null
        return l
    }

    /** The card for the call that is up (sent again when the Mac reconnects). */
    fun current(now: Long): CallLive? = call?.let { live(it, now) }

    private fun resetAudio() {
        speaker = false
        muted = false
    }

    private fun live(c: Call, now: Long): CallLive {
        val up = c.state != CallLiveStates.ENDED
        val offhook = c.state == CallLiveStates.ACTIVE || c.state == CallLiveStates.DIALING
        return CallLive(
            id = c.id, dir = c.dir, state = c.state, number = c.number, name = c.name, since = c.since,
            speaker = speaker && up, muted = muted && up, held = false,
            canAnswer = controls.answer && c.state == CallLiveStates.RINGING && c.dir == CallDirs.IN,
            canEnd = controls.end && up,
            canSpeaker = controls.speaker && offhook,
            canMute = controls.mute && offhook,
            canHold = controls.hold && offhook,
            sub = c.sub, ts = now, reason = c.reason,
        )
    }
}

/** Android CallLog.Calls.TYPE values -> protocol strings. Pure. */
object CallLogTypeMap {
    private const val INCOMING = 1
    private const val OUTGOING = 2
    private const val MISSED = 3
    private const val VOICEMAIL = 4
    private const val REJECTED = 5
    private const val BLOCKED = 6

    fun of(type: Int): String = when (type) {
        INCOMING -> CallLogTypes.IN
        OUTGOING -> CallLogTypes.OUT
        MISSED -> CallLogTypes.MISSED
        VOICEMAIL -> CallLogTypes.VOICEMAIL
        REJECTED -> CallLogTypes.REJECTED
        BLOCKED -> CallLogTypes.BLOCKED
        else -> CallLogTypes.OTHER
    }
}

/** Cleans a number typed or clicked on the Mac into something the phone can dial. Pure. */
object Dialable {
    private const val MAX_LEN = 40
    private val SEPARATORS = setOf(' ', '-', '(', ')', '.', '/', '\u00A0', '\u2011', '\u2012', '\u2013', '\u2212')

    /**
     * Keeps digits, a leading +, * and #, and pauses (',' or 'p' = pause, ';' or 'w' = wait).
     * Returns null when nothing dialable is left or anything else is in the number.
     */
    fun clean(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val out = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (ch == '+') {
                if (out.isNotEmpty() || i != s.indexOf('+')) return null
                out.append(ch)
                continue
            }
            when (val m = map(ch)) {
                SKIP -> Unit
                INVALID -> return null
                else -> out.append(m)
            }
        }
        val body = out.toString()
        val main = body.takeWhile { it != ',' && it != ';' }
        return body.takeIf { main.any { it.isDigit() || it == '*' || it == '#' } && it.length <= MAX_LEN }
    }

    private const val SKIP = '\u0000'
    private const val INVALID = '\uFFFF'
    private const val ASCII = 128

    private fun map(ch: Char): Char = when {
        ch.isDigit() && ch.code < ASCII -> ch
        ch == '*' || ch == '#' -> ch
        ch == ',' || ch == 'p' || ch == 'P' -> ','
        ch == ';' || ch == 'w' || ch == 'W' -> ';'
        ch in SEPARATORS -> SKIP
        else -> INVALID
    }
}

/**
 * Android 15+ replaces one-time codes and similar "sensitive" notification text with a system
 * string ("Sensitive notification content hidden") for listeners without the
 * RECEIVE_SENSITIVE_NOTIFICATIONS app op. Detects that string (localised: the caller passes
 * the system's own string) and tracks whether to show the fix. Single-threaded.
 */
class SensitiveNotifications(systemStrings: Collection<String>, private val sdk: Int, private val oem: String) {
    private val redactedTexts: Set<String> =
        (systemStrings + ENGLISH).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    private var lastRedacted: Long? = null
    private var granted: Boolean? = null
    private var grantedAt = 0L
    private var lastSent: NotifAccess? = null

    fun isRedacted(title: String?, text: String?): Boolean =
        listOfNotNull(title, text).any { it.trim().lowercase() in redactedTexts }

    /** Android hides content from Tether right now (and the fix isn't known to be in place). */
    val redacting: Boolean
        get() {
            val seen = lastRedacted ?: return false
            return granted != true || seen > grantedAt
        }

    /** A notification arrived; returns a status to send when it changed. */
    fun onNotification(title: String?, text: String?, now: Long): NotifAccess? {
        if (sdk < ANDROID_15 || !isRedacted(title, text)) return null
        lastRedacted = now
        return changed(now)
    }

    /** The app op was (re)checked: true allowed, false denied, null unknown. */
    fun onGrant(allowed: Boolean?, now: Long): NotifAccess? {
        if (allowed == true && granted != true) grantedAt = now
        granted = allowed
        return changed(now)
    }

    /** The status to send on connect. */
    fun status(now: Long): NotifAccess = NotifAccess(redacting, granted, sdk, oem, now).also { lastSent = it }

    private fun changed(now: Long): NotifAccess? {
        val s = NotifAccess(redacting, granted, sdk, oem, now)
        val prev = lastSent
        if (prev != null && prev.redacting == s.redacting && prev.granted == s.granted) return null
        lastSent = s
        return s
    }

    companion object {
        const val ANDROID_15 = 35
        const val ENGLISH = "Sensitive notification content hidden"
        /** The app op behind the permission (AppOpsManager.OPSTR_RECEIVE_SENSITIVE_NOTIFICATIONS, hidden API). */
        const val OP = "android:receive_sensitive_notifications"
        fun adbCommand(pkg: String) = "adb shell cmd appops set --user 0 $pkg RECEIVE_SENSITIVE_NOTIFICATIONS allow"
    }
}
