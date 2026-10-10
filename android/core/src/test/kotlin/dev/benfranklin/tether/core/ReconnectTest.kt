package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.util.ReconnectWatchdog
import dev.benfranklin.tether.core.util.ScanBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.1: every way the link used to get stuck until the app was quit and reopened. */
class ReconnectTest {
    private val loop = Loop()
    private val sides = pairedSides(loop)
    private val phone = sides.first
    private val mac = sides.second

    private fun tick(seconds: Int) = repeat(seconds) {
        loop.now += 1_000
        phone.controller.tick()
        mac.controller.tick()
        loop.drain()
    }

    @Test
    fun watchdogRestartsAfterADownLinkWithBackoff() {
        val w = ReconnectWatchdog(firstAfterMs = 45_000, maxAfterMs = 300_000)
        var t = 0L
        val restarts = ArrayList<Long>()
        while (t <= 20 * 60_000L) {
            if (w.observe(t, up = false, eligible = true)) restarts += t
            t += 5_000
        }
        // 45 s, then +90, +180, +300, +300 ...
        assertEquals(listOf(45_000L, 135_000L, 315_000L, 615_000L, 915_000L), restarts)
    }

    @Test
    fun watchdogStaysQuietWhenLinkedOrNotExpectedToBe() {
        val w = ReconnectWatchdog()
        for (t in 0L..600_000L step 5_000) {
            assertFalse(w.observe(t, up = true, eligible = true))
            assertFalse(w.observe(t, up = false, eligible = false)) // unpaired, Bluetooth off, pairing
        }
        assertEquals(0, w.attempts)
    }

    @Test
    fun relinkingResetsTheBackoff() {
        val w = ReconnectWatchdog(firstAfterMs = 45_000)
        assertTrue(w.observe(0, false, true).not() && w.observe(45_000, false, true))
        assertTrue(w.thresholdMs() > 45_000)
        w.observe(50_000, up = true, eligible = true)
        assertEquals(45_000, w.thresholdMs())
    }

    @Test
    fun pokeAllowsOneEarlyRestart() {
        val w = ReconnectWatchdog(firstAfterMs = 45_000, pokeAfterMs = 10_000)
        assertFalse(w.observe(0, false, true))
        w.poke() // screen on / wake / network change
        assertFalse(w.observe(5_000, false, true)) // down only 5 s
        assertTrue(w.observe(10_000, false, true))
        assertFalse(w.observe(20_000, false, true)) // poke used up
    }

    @Test
    fun scanBudgetNeverExceedsAndroidsLimit() {
        val b = ScanBudget(maxStarts = 4, windowMs = 30_000)
        var t = 0L
        val starts = ArrayList<Long>()
        repeat(12) {
            val wait = b.waitMs(t)
            t += wait
            b.started(t)
            starts += t
            t += 100 // a failure path that restarts the scan immediately
        }
        for (i in starts.indices) {
            val inWindow = starts.count { it in starts[i]..(starts[i] + 30_000) }
            assertTrue("starts in 30 s from ${starts[i]}: $inWindow", inWindow <= 5)
        }
        assertTrue(b.cycleMs < 30 * 60_000L) // cycled before Android makes it opportunistic
    }

    @Test
    fun handshakeTimeoutOnTheMacIsRecoveredByThePhoneRetrying() {
        val ble = LoopbackBle(loop, phone, mac)
        ble.dropToPhone = 1_000 // the Mac's replies are lost for a while
        ble.connect()
        loop.drain()
        tick(40)
        assertTrue("mac closes ${mac.closes}", mac.closes.any { it.first == CloseReason.TIMEOUT })
        assertTrue("phone closes ${phone.closes}", phone.closes.any { it.first == CloseReason.TIMEOUT })
        // Radio recovers; the phone's reconnect (new GATT link) must link up again.
        ble.dropToPhone = 0
        ble.disconnect()
        loop.drain()
        ble.connect()
        loop.drain()
        assertTrue(phone.state is LinkState.Connected && mac.state is LinkState.Connected)
    }

    @Test
    fun halfOpenWifiIsDetectedAndBluetoothCarriesOn() {
        val ble = LoopbackBle(loop, phone, mac)
        val wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        phone.controller.offerWifi(listOf("10.0.0.2"), 1)
        loop.drain()
        wifi.connect(mac.wifiOffers.last().token)
        loop.drain()
        assertTrue(mac.controller.wifiReady)
        wifi.blackhole = true // socket stays "open" but nothing gets through, no close event
        tick(70)
        assertFalse(mac.controller.wifiReady)
        assertFalse(phone.controller.wifiReady)
        assertEquals(LinkState.Connected("Pixel", false), mac.state)
        assertTrue(phone.controller.send(Clip("c", 1, "over bluetooth")))
        loop.drain()
        assertEquals(Clip("c", 1, "over bluetooth"), mac.messages.last())
    }

    @Test
    fun deadBluetoothWithoutDisconnectEventIsClosedOnBothSides() {
        val ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.drain()
        ble.dropToMac = Int.MAX_VALUE // radio silently dead (no GATT callback)
        ble.dropToPhone = Int.MAX_VALUE
        tick(70)
        assertFalse(phone.controller.bleReady)
        assertFalse(mac.controller.bleReady)
        // ...and a fresh link afterwards works (no state left behind).
        ble.dropToMac = 0
        ble.dropToPhone = 0
        ble.disconnect()
        loop.drain()
        ble.connect()
        loop.drain()
        assertTrue(phone.controller.bleReady && mac.controller.bleReady)
    }

    @Test
    fun bluetoothToggleMidHandshakeLeavesNothingStuck() {
        val ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.step(3) // part-way through the handshake
        ble.disconnect() // Bluetooth turned off
        loop.drain()
        tick(30)
        ble.connect() // and on again
        loop.drain()
        assertTrue(phone.controller.bleReady && mac.controller.bleReady)
    }
}
