package dev.benfranklin.tether.core.util

import kotlin.random.Random

/**
 * Capped exponential backoff with full-range jitter, for reconnect attempts.
 * `delay(n) = min(max, base * 2^n) * (1 - jitter .. 1)`.
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val jitter: Double = 0.25,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    init {
        require(baseMs > 0 && maxMs >= baseMs) { "bad backoff bounds" }
        require(jitter in 0.0..1.0) { "jitter must be within 0..1" }
    }

    /** Delay before the next attempt; advances the attempt counter. */
    fun next(): Long {
        val exp = attempt.coerceAtMost(MAX_SHIFT)
        val raw = (baseMs shl exp).coerceAtMost(maxMs)
        attempt++
        val factor = 1.0 - jitter * random.nextDouble()
        return (raw * factor).toLong().coerceAtLeast(1)
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        const val MAX_SHIFT = 30
    }
}

/**
 * Keepalive bookkeeping. The owner calls [tick] from a timer; it answers what to do.
 */
class Heartbeat(private val intervalMs: Long = 45_000, private val timeoutMs: Long = 20_000) {
    enum class Action { NONE, SEND_PING, DEAD }

    private var lastHeard = 0L
    private var pingSentAt = -1L

    fun reset(now: Long) {
        lastHeard = now
        pingSentAt = -1
    }

    /** Any inbound record counts as proof of life. */
    fun heard(now: Long) {
        lastHeard = now
        pingSentAt = -1
    }

    fun tick(now: Long): Action = when {
        pingSentAt >= 0 && now - pingSentAt >= timeoutMs -> Action.DEAD
        pingSentAt < 0 && now - lastHeard >= intervalMs -> {
            pingSentAt = now
            Action.SEND_PING
        }
        else -> Action.NONE
    }
}
