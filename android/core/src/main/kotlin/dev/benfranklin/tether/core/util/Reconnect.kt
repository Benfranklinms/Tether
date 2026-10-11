package dev.benfranklin.tether.core.util

/**
 * The "quit and reopen" safety net (0.3.1). Fed the link state every few seconds; answers
 * true when the Bluetooth link has been down long enough, while it should be up (paired,
 * radio on, not pairing), that the app should tear its radio stack down and start it again.
 * Waits [firstAfterMs], then doubles up to [maxAfterMs] between restarts; [poke] (screen on,
 * wake from sleep, network change) allows one early restart once the link has been down for
 * [pokeAfterMs]. Pure logic, mirrored in Reconnect.swift.
 */
class ReconnectWatchdog(
    private val firstAfterMs: Long = 45_000,
    private val maxAfterMs: Long = 300_000,
    private val pokeAfterMs: Long = 10_000,
) {
    var attempts: Int = 0
        private set
    private var downSince = -1L
    private var poked = false

    /**
     * Restart now? [up] is "Bluetooth session established"; [eligible] "it should be".
     * [interactive] (screen on or app in the foreground, 0.5.4) caps the wait at [INTERACTIVE_MAX_MS]:
     * someone is looking at the phone, so minutes of exponential backoff aren't acceptable.
     */
    fun observe(now: Long, up: Boolean, eligible: Boolean, interactive: Boolean = false): Boolean {
        if (up) {
            reset()
            return false
        }
        if (!eligible) {
            downSince = -1
            poked = false
            return false
        }
        if (downSince < 0) downSince = now
        val down = now - downSince
        if (down < thresholdMs(interactive) && !(poked && down >= pokeAfterMs)) return false
        attempts++
        downSince = now
        poked = false
        return true
    }

    /** Wait before the next restart. */
    fun thresholdMs(interactive: Boolean = false): Long {
        val t = (firstAfterMs shl attempts.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxAfterMs)
        return if (interactive) t.coerceAtMost(INTERACTIVE_MAX_MS) else t
    }

    /** Something suggests the radio may be fresh again: allow one early restart. */
    fun poke() {
        poked = true
    }

    /** Linked again, or the user asked for a reconnect: start over. */
    fun reset() {
        attempts = 0
        downSince = -1
        poked = false
    }

    companion object {
        private const val MAX_SHIFT = 8

        /** Longest wait between restarts while the screen is on or the app is open. */
        const val INTERACTIVE_MAX_MS = 60_000L
    }
}

/**
 * Android allows an app about five BLE scan starts per 30 s; a sixth is silently ignored
 * (no results, no error), which left 0.3.0's scanner "running" but deaf. Keeps starts within
 * [maxStarts] per [windowMs] and says when a long scan should be cycled (Android downgrades
 * scans older than 30 min to opportunistic, which see nothing on their own).
 */
class ScanBudget(
    private val maxStarts: Int = 4,
    private val windowMs: Long = 30_000,
    private val slackMs: Long = 250,
    /** Restart a scan that ran this long without finding the Mac. */
    val cycleMs: Long = 5 * 60_000L,
) {
    private val starts = ArrayDeque<Long>()

    /** Milliseconds to wait before the next start may happen (0 = now). */
    fun waitMs(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.first() > windowMs) starts.removeFirst()
        if (starts.size < maxStarts) return 0
        return windowMs - (now - starts.first()) + slackMs
    }

    fun started(now: Long) {
        starts.addLast(now)
    }
}
