package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.ClipAutoGate
import dev.benfranklin.tether.core.features.ClipAutoGate.Action
import dev.benfranklin.tether.core.features.ClipAutoSetup
import dev.benfranklin.tether.core.features.ClipLogMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipAutoTest {
    private val pkg = "in.benfranklin.tether"

    // Real AOSP wording (ClipboardService.clipboardAccessAllowed), as `logcat -v epoch` prints it.
    private val aosp = "  1760090000.123  1543  2210 E ClipboardService: Denying clipboard access to in.benfranklin.tether, " +
        "application is not in focus nor is it a system service for user 0"

    @Test
    fun matchesTheAospDenialForOurPackageOnly() {
        assertTrue(ClipLogMatcher.isDenial(aosp, pkg))
        assertTrue(ClipLogMatcher.isDenial(aosp.replace(pkg, "$pkg.debug"), "$pkg.debug"))
        // Another app's denial, or a longer/shorter package with ours as a prefix/suffix.
        assertFalse(ClipLogMatcher.isDenial(aosp.replace(pkg, "com.whatsapp"), pkg))
        assertFalse(ClipLogMatcher.isDenial(aosp.replace(pkg, "$pkg.debug"), pkg))
        assertFalse(ClipLogMatcher.isDenial(aosp, "$pkg.debug"))
        assertFalse(ClipLogMatcher.isDenial(aosp.replace(pkg, "x$pkg"), pkg))
        assertFalse(ClipLogMatcher.isDenial("", pkg))
        assertFalse(ClipLogMatcher.isDenial(aosp, ""))
    }

    @Test
    fun acceptsOemRewordingsButNotOtherClipboardLines() {
        // Brief format, sentence-final package, other wording that still says Clipboard + deny.
        assertTrue(ClipLogMatcher.isDenial("E/ClipboardService( 1543): Denying clipboard access to $pkg.", pkg))
        assertTrue(ClipLogMatcher.isDenial("W OplusClipboardService: clipboard read denied for $pkg (not focused)", pkg))
        // Not a clip change: invalid device id variant; the "pasted" toast line; unrelated lines naming us.
        assertFalse(ClipLogMatcher.isDenial("W ClipboardService: Clipboard access denied to 10234/$pkg due to invalid device id", pkg))
        assertFalse(ClipLogMatcher.isDenial("I ClipboardService: Tether pasted from your clipboard", pkg))
        assertFalse(ClipLogMatcher.isDenial("I ActivityManager: Start proc 1234:$pkg/u0a234 for service", pkg))
    }

    @Test
    fun epochTimestampsAndStaleReplay() {
        assertEquals(1760090000.123, ClipLogMatcher.epochSeconds(aosp)!!, 1e-6)
        assertNull(ClipLogMatcher.epochSeconds("E/ClipboardService( 1543): Denying"))
        assertNull(ClipLogMatcher.epochSeconds("10-10 15:00:00.000 E ClipboardService"))
        val start = 1760090000_123L
        assertFalse(ClipLogMatcher.isStale(aosp, start))
        assertTrue(ClipLogMatcher.isStale(aosp, start + 10_000))
        // No timestamp: never treated as stale.
        assertFalse(ClipLogMatcher.isStale("E/ClipboardService: x", start + 10_000))
        val cmd = ClipLogMatcher.logcatCommand()
        assertEquals("logcat", cmd.first())
        assertTrue(cmd.containsAll(listOf("-T", "1", "ClipboardService:W", "*:S")))
    }

    @Test
    fun launchesOnceThenThrottlesWithOneTrailingRead() {
        val g = ClipAutoGate(minGapMs = 1_000)
        assertEquals(Action.LAUNCH, g.onDenial(10_000).action)
        g.onFinished()
        // A burst inside the gap: one LATER, the rest coalesced.
        val later = g.onDenial(10_300)
        assertEquals(Action.LATER, later.action)
        assertEquals(700L, later.delayMs)
        assertEquals("coalesced", g.onDenial(10_500).reason)
        // The trailing re-check launches.
        assertEquals(Action.LAUNCH, g.onDenial(11_000, trailing = true).action)
    }

    @Test
    fun aLaunchInFlightDefersUntilFinishedOrTimedOut() {
        val g = ClipAutoGate(minGapMs = 1_000, inFlightTimeoutMs = 4_000)
        assertEquals(Action.LAUNCH, g.onDenial(0).action)
        assertEquals(Action.LATER, g.onDenial(2_000).action) // still in flight (never finished)
        assertEquals(Action.LAUNCH, g.onDenial(5_000, trailing = true).action) // timed out
        g.onFinished()
        assertEquals(Action.LAUNCH, g.onDenial(6_500).action)
    }

    @Test
    fun ignoresTheEchoOfTextFromTheMacAndOurOwnProbe() {
        val g = ClipAutoGate(echoWindowMs = 2_000, probeWindowMs = 2_500)
        g.onLocalWrite("from mac", 1_000)
        assertEquals("echo", g.onDenial(1_200).reason)
        assertEquals("echo", g.onDenial(3_000).reason)
        assertEquals(Action.LAUNCH, g.onDenial(3_100).action)
        g.onFinished()
        g.onProbe(10_000)
        assertTrue(g.inProbeWindow(11_000))
        assertEquals("probe", g.onDenial(11_000).reason)
        assertFalse(g.inProbeWindow(12_600))
        // Even if the line arrives late and the reader opens, the Mac's own text isn't sent back.
        assertTrue(g.onRead("from mac", sensitive = false, allowSensitive = false, lastHash = null) is ClipAutoGate.Read.Skip)
    }

    @Test
    fun stormGuardPausesAfterTheCap() {
        val g = ClipAutoGate(minGapMs = 100, maxPerMinute = 3, stormPauseMs = 60_000)
        var t = 0L
        repeat(3) {
            assertEquals(Action.LAUNCH, g.onDenial(t).action)
            g.onFinished()
            t += 200
        }
        assertEquals("storm", g.onDenial(t).reason)
        assertEquals("storm", g.onDenial(t + 59_000).reason)
        assertEquals(Action.LAUNCH, g.onDenial(t + 61_000).action)
    }

    @Test
    fun readFilterDedupesAndSkipsSensitiveUnlessAllowed() {
        val g = ClipAutoGate()
        val send = g.onRead("hello", sensitive = false, allowSensitive = false, lastHash = null)
        assertTrue(send is ClipAutoGate.Read.Send)
        val h = (send as ClipAutoGate.Read.Send).hash
        assertEquals(ClipAutoGate.hash("hello"), h)
        assertEquals(ClipAutoGate.Read.Skip("duplicate"), g.onRead("hello", false, false, lastHash = h))
        assertEquals(ClipAutoGate.Read.Skip("empty"), g.onRead(null, false, false, null))
        assertEquals(ClipAutoGate.Read.Skip("empty"), g.onRead("", false, false, null))
        assertEquals(ClipAutoGate.Read.Skip("blank"), g.onRead("  \n", false, false, null))
        assertEquals(ClipAutoGate.Read.Skip("sensitive"), g.onRead("hunter2", sensitive = true, allowSensitive = false, lastHash = null))
        assertTrue(g.onRead("hunter2", sensitive = true, allowSensitive = true, lastHash = null) is ClipAutoGate.Read.Send)
        g.onLocalWrite("mac text", 0)
        assertEquals(ClipAutoGate.Read.Skip("from_mac"), g.onRead("mac text", false, false, null))
        // A new copy after the Mac's text goes through.
        assertTrue(g.onRead("phone text", false, false, ClipAutoGate.hash("mac text")) is ClipAutoGate.Read.Send)
    }

    @Test
    fun setupCommandsAndLogGroup() {
        assertEquals(
            listOf(
                "adb shell pm grant $pkg android.permission.READ_LOGS",
                "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
                "adb shell am force-stop $pkg",
            ),
            ClipAutoSetup.commands(pkg),
        )
        val status = "Name:\tbenfranklin.tether\nUid:\t10234\t10234\t10234\t10234\nGroups:\t1007 3002 3003 9997 20234 50234\n"
        assertTrue(ClipAutoSetup.hasLogGroup(status))
        assertFalse(ClipAutoSetup.hasLogGroup(status.replace("1007 ", "")))
        assertFalse(ClipAutoSetup.hasLogGroup(status.replace("1007", "10071")))
        assertFalse(ClipAutoSetup.hasLogGroup("Name:\tx\n"))
    }

    @Test
    fun statusOrder() {
        fun st(
            enabled: Boolean = true, logs: Boolean = true, group: Boolean = true, overlay: Boolean = true,
            running: Boolean = true, verified: Boolean = false, declined: Boolean = false,
        ) = ClipAutoSetup.status(ClipAutoSetup.Inputs(enabled, logs, group, overlay, running, verified, declined))
        assertEquals(ClipAutoSetup.Status.OFF, st(enabled = false, logs = false))
        assertEquals(ClipAutoSetup.Status.NEEDS_LOGS, st(logs = false, group = false, overlay = false))
        assertEquals(ClipAutoSetup.Status.NEEDS_RESTART, st(group = false))
        assertEquals(ClipAutoSetup.Status.NEEDS_OVERLAY, st(overlay = false))
        assertEquals(ClipAutoSetup.Status.NEEDS_APPROVAL, st(running = false))
        assertEquals(ClipAutoSetup.Status.NEEDS_APPROVAL, st(declined = true))
        assertEquals(ClipAutoSetup.Status.WATCHING, st())
        assertEquals(ClipAutoSetup.Status.WORKING, st(verified = true))
        ClipAutoSetup.Status.entries.forEach { assertTrue(ClipAutoSetup.label(it).isNotBlank()) }
    }
}
