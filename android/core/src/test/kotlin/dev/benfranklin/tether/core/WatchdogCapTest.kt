package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.util.ReconnectWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.4: while the screen is on or the app is open, the reconnect watchdog never waits more than 60 s. */
class WatchdogCapTest {
    @Test
    fun interactiveCapsBackoffAtSixtySeconds() {
        val w = ReconnectWatchdog()
        var now = 0L
        // Let it back off for a while in the background (45 s, 90 s, 180 s, 300 s...).
        repeat(4) {
            assertFalse(w.observe(now, up = false, eligible = true))
            now += w.thresholdMs()
            assertTrue(w.observe(now, up = false, eligible = true))
        }
        assertEquals(300_000L, w.thresholdMs())
        assertEquals(ReconnectWatchdog.INTERACTIVE_MAX_MS, w.thresholdMs(interactive = true))
        // Screen on: the next restart comes 60 s after the last one, not 5 minutes.
        assertFalse(w.observe(now + 59_000, up = false, eligible = true, interactive = true))
        assertTrue(w.observe(now + 60_000, up = false, eligible = true, interactive = true))
    }

    @Test
    fun firstWaitIsUnchangedWhenInteractive() {
        val w = ReconnectWatchdog()
        assertEquals(45_000L, w.thresholdMs(interactive = true))
    }
}
