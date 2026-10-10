package dev.benfranklin.tether.core.wifi

/**
 * When to (re)try the Wi-Fi fast path while the link is Bluetooth-only (0.5.2).
 *
 * Before 0.5.2 the phone offered its addresses once per Bluetooth connect and the Mac tried
 * them three times, so a network change while both apps ran (the Mac joining the phone's
 * hotspot, the phone switching Wi-Fi) left the link on Bluetooth until a restart. Now: an
 * attempt right after Bluetooth comes up, another a beat after any network change (addresses
 * settle first), and in between a backoff that doubles from [baseMs] to [capMs] for as long
 * as the link stays Bluetooth-only. Mirrored in TetherCore `FastPathRetry`.
 */
class FastPathRetry(
    private val baseMs: Long = BASE_MS,
    private val capMs: Long = CAP_MS,
    private val settleMs: Long = SETTLE_MS,
) {
    var attempts: Int = 0
        private set

    /** When the next attempt is due (null = idle: no Bluetooth link, or Wi-Fi is up). */
    var nextAt: Long? = null
        private set

    /** Bluetooth session established and Wi-Fi isn't: try now. */
    fun linkUp(now: Long) {
        attempts = 0
        nextAt = now
    }

    /** Any network change (interface up/down, address change, hotspot on/off, Mac path change). */
    fun networkChanged(now: Long) {
        attempts = 0
        val soon = now + settleMs
        nextAt = nextAt?.let { minOf(it, soon) }?.coerceAtLeast(now) ?: soon
    }

    /** An attempt was made (offer sent / candidates tried) and Wi-Fi isn't up yet. */
    fun attempted(now: Long) {
        attempts++
        nextAt = now + delayFor(attempts)
    }

    /** The fast path is up: stop retrying until it drops. */
    fun connected() {
        attempts = 0
        nextAt = null
    }

    /** Wi-Fi dropped while Bluetooth is still up: retry soon. */
    fun wifiLost(now: Long) {
        attempts = 0
        nextAt = now + settleMs
    }

    /** Bluetooth went away: nothing to upgrade. */
    fun linkDown() {
        attempts = 0
        nextAt = null
    }

    fun due(now: Long, bleReady: Boolean, wifiReady: Boolean): Boolean {
        if (!bleReady || wifiReady) return false
        val at = nextAt ?: return false
        return now >= at
    }

    fun delayFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        return minOf(capMs, baseMs shl shift)
    }

    companion object {
        const val BASE_MS = 4_000L
        const val CAP_MS = 60_000L
        const val SETTLE_MS = 1_200L
        private const val MAX_SHIFT = 10

        /**
         * Orders the phone's addresses so the ones on the Mac's own subnet (/24) come first,
         * e.g. the hotspot address 10.x.y.1 when the Mac just joined the hotspot.
         */
        fun rank(addrs: List<String>, local: List<String>): List<String> {
            val nets = local.mapNotNull { prefix24(it) }.toSet()
            return addrs.sortedBy { if (prefix24(it) in nets) 0 else 1 }
        }

        private fun prefix24(a: String): String? {
            val p = a.split('.')
            return if (p.size == 4) p.take(3).joinToString(".") else null
        }
    }
}
