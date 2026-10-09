package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.core.proto.CallStates

/**
 * Turns the phone's call-state callbacks (RINGING / OFFHOOK / IDLE) into the events the Mac
 * shows: an incoming call, answered, ended, or missed (rang and went idle without being
 * answered). Outgoing calls (OFFHOOK without RINGING) are not reported. Single-threaded.
 */
class CallTracker(private val idGen: () -> String) {
    private var current: String? = null
    private var number: String? = null
    private var name: String? = null
    private var answered = false
    private var declinedByMac = false

    val ringingId: String? get() = current.takeIf { !answered }

    /** Feed a state change; returns the event to send, or null. */
    fun onState(state: Int, incomingNumber: String?, contactName: String?, now: Long): CallState? = when (state) {
        RINGING -> {
            val id = current ?: idGen().also {
                current = it
                answered = false
                declinedByMac = false
            }
            if (!incomingNumber.isNullOrEmpty()) number = incomingNumber
            if (!contactName.isNullOrEmpty()) name = contactName
            CallState(id, CallStates.RINGING, number, name, now)
        }
        OFFHOOK -> {
            val id = current
            if (id == null || answered) {
                null
            } else {
                answered = true
                CallState(id, CallStates.OFFHOOK, number, name, now)
            }
        }
        IDLE -> {
            val id = current
            val event = when {
                id == null -> null
                !answered && !declinedByMac -> CallState(id, CallStates.MISSED, number, name, now)
                else -> CallState(id, CallStates.ENDED, number, name, now)
            }
            current = null
            number = null
            name = null
            answered = false
            declinedByMac = false
            event
        }
        else -> null
    }

    /** The Mac declined the call, so its end isn't reported as missed. */
    fun declinedFromMac() {
        declinedByMac = true
    }

    companion object {
        // TelephonyManager.CALL_STATE_*
        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
    }
}

/**
 * Decides when to send NOW_PLAYING: track/state changes at once, position only as a periodic
 * resync (the Mac extrapolates from position + speed + timestamp), never more than [minGapMs].
 */
class NowPlayingThrottle(private val minGapMs: Long = 300, private val resyncMs: Long = 15_000) {
    private var lastKey: String? = null
    private var lastAt = Long.MIN_VALUE / 2

    /** [key] identifies everything except the position. */
    fun offer(key: String, now: Long): Boolean {
        val changed = key != lastKey
        val due = changed && now - lastAt >= minGapMs || now - lastAt >= resyncMs
        if (due) {
            lastKey = key
            lastAt = now
        }
        return due
    }

    fun reset() {
        lastKey = null
    }
}

/** Normalises phone numbers for matching threads and contacts ("+91 98470-12345" ~ "9847012345"). Pure. */
object PhoneNumbers {
    private const val SIGNIFICANT = 10

    fun digits(n: String): String = n.filter { it.isDigit() }

    /** True when two numbers are the same subscriber (last 10 digits match, or exact for short codes). */
    fun same(a: String, b: String): Boolean {
        val da = digits(a)
        val db = digits(b)
        if (da.isEmpty() || db.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true) // alphanumeric senders
        if (da.length < SIGNIFICANT || db.length < SIGNIFICANT) return da == db
        return da.takeLast(SIGNIFICANT) == db.takeLast(SIGNIFICANT)
    }
}
