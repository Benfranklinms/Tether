package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.link.Fragmentation
import dev.benfranklin.tether.core.link.Fragmenter
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.SmsThreads
import dev.benfranklin.tether.core.proto.SmsThreadsReq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link liveness (0.3.1): the 0.3.0 field bug was a phone write pump wedged by a 514-byte
 * fragment (Android 13+ refuses GATT values over 512 bytes), so the Mac heard nothing after
 * the handshake and closed Bluetooth with "timeout" every ~65 s, losing SMS replies.
 */
class LivenessTest {
    private val loop = Loop()
    private val sides = pairedSides(loop)
    private val phone = sides.first
    private val mac = sides.second

    private fun tickFor(seconds: Int, everySecond: (Int) -> Unit = {}) {
        repeat(seconds) { s ->
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            everySecond(s)
            loop.drain()
        }
    }

    private fun connectWifi(wifi: LoopbackWifi) {
        phone.controller.offerWifi(listOf("10.0.0.2"), 1)
        loop.drain()
        wifi.connect(mac.wifiOffers.last().token)
        loop.drain()
        assertTrue(phone.controller.wifiReady && mac.controller.wifiReady)
    }

    @Test
    fun fragmentsNeverExceedTheGattAttributeLimit() {
        assertEquals(512, TetherController.clampFragment(514))
        assertEquals(20, TetherController.clampFragment(5))
        val ble = LoopbackBle(loop, phone, mac, mtu = 514).apply { androidAttrLimit = true }
        ble.connect()
        loop.drain()
        val list = SmsThreads(realisticThreads(60), more = true)
        assertTrue(phone.controller.send(list))
        loop.drain()
        assertEquals(list, mac.messages.last())
        assertFalse("a >512-byte write would have wedged the phone", ble.wedged)
        assertTrue(ble.maxToMac <= Fragmentation.MAX_BLE_FRAGMENT)
        assertEquals(Fragmentation.MAX_BLE_FRAGMENT, ble.maxToMac)
    }

    @Test
    fun wedgedPhoneWritesAreWhat065sTimeoutsLookedLike() {
        // Reproduces the field log with a writer that ignores the clamp: after one oversized
        // write nothing reaches the Mac; the Mac (not the phone) times out ~65 s later.
        val ble = LoopbackBle(loop, phone, mac, mtu = 185)
        ble.connect()
        loop.drain()
        ble.androidAttrLimit = true
        val oversized = Fragmenter().fragment(ByteArray(600) { 1 }, 600).first() // 600 > 512 bytes
        ble.forceToMac(oversized)
        tickFor(70)
        assertTrue(ble.wedged)
        assertTrue(mac.closes.any { it == CloseReason.TIMEOUT to false })
        assertTrue(phone.closes.any { it == CloseReason.TIMEOUT to true })
    }

    @Test
    fun idleBluetoothStaysUpForHours() {
        val ble = LoopbackBle(loop, phone, mac, mtu = 514).apply { androidAttrLimit = true }
        ble.connect()
        loop.drain()
        tickFor(6 * 3600) { s -> if (s % 900 == 0) phone.controller.send(SmsThreads(realisticThreads(40))) }
        assertTrue(phone.closes.isEmpty() && mac.closes.isEmpty())
        assertTrue(mac.state is LinkState.Connected)
    }

    @Test
    fun wifiTrafficWithIdleBluetoothStaysUpForHours() {
        val ble = LoopbackBle(loop, phone, mac, mtu = 514).apply { androidAttrLimit = true }
        val wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        connectWifi(wifi)
        val bleFragments = ble.fragmentsSent
        var replies = 0
        tickFor(6 * 3600) { s ->
            if (s % 10 == 0) phone.controller.send(Clip("c$s", loop.now, "clip $s")) // Wi-Fi only traffic
            if (s % 300 == 0) {
                mac.controller.send(SmsThreadsReq(40))
                phone.controller.send(SmsThreads(realisticThreads(80)))
                replies++
            }
        }
        assertTrue("closes: phone=${phone.closes} mac=${mac.closes}", phone.closes.isEmpty() && mac.closes.isEmpty())
        assertEquals(LinkState.Connected("Pixel", true), mac.state)
        assertEquals(replies, mac.messages.count { it is SmsThreads })
        // Big lists rode Wi-Fi; Bluetooth only carried requests and keepalives (~1 ping/45 s each way).
        val perHour = (ble.fragmentsSent - bleFragments) / 6
        assertTrue("BLE fragments/hour $perHour", perHour < 400)
    }

    @Test
    fun anyFragmentCountsAsProofOfLife() {
        val ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.drain()
        // A long record arriving slowly (never finished here) keeps the Mac from timing out.
        val parts = Fragmenter().fragment(ByteArray(40_000) { 3 }, 185)
        var i = 0
        repeat(90) {
            loop.now += 1_000
            if (it % 5 == 0 && i < parts.size - 1) mac.controller.bleFragment(parts[i++])
            mac.controller.tick()
            loop.drain()
        }
        assertTrue(mac.closes.isEmpty())
    }

    @Test
    fun bluetoothReconnectUnderWifiIsInvisible() {
        val ble = LoopbackBle(loop, phone, mac)
        val wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        connectWifi(wifi)
        mac.states.clear()
        ble.disconnect()
        loop.drain()
        ble.connect()
        loop.drain()
        assertTrue("states while reconnecting: ${mac.states}", mac.states.all { it is LinkState.Connected })
        assertTrue(mac.controller.bleReady && mac.controller.wifiReady)
    }

    @Test
    fun bigListsPreferWifiAndFallBackToBluetooth() {
        val ble = LoopbackBle(loop, phone, mac)
        val wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        connectWifi(wifi)
        val before = ble.fragmentsSent
        assertTrue(phone.controller.send(SmsThreads(realisticThreads(100))))
        loop.drain()
        assertEquals(before, ble.fragmentsSent)
        wifi.disconnect()
        loop.drain()
        assertTrue(phone.controller.send(SmsThreads(realisticThreads(10))))
        loop.drain()
        assertTrue(ble.fragmentsSent > before)
        assertEquals(2, mac.messages.count { it is SmsThreads })
    }
}
