package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.proto.Battery
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.NotifAction
import dev.benfranklin.tether.core.proto.Ring
import dev.benfranklin.tether.core.proto.ScreenInput
import dev.benfranklin.tether.core.proto.ScreenState
import dev.benfranklin.tether.core.proto.VideoFrame
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerFeaturesTest {
    private val loop = Loop()
    private val sides = pairedSides(loop)
    private val phone = sides.first
    private val mac = sides.second
    private val ble = LoopbackBle(loop, phone, mac)
    private val wifi = LoopbackWifi(loop, phone, mac)

    private fun connect() {
        ble.connect()
        loop.drain()
        assertTrue(phone.state is LinkState.Connected)
    }

    private fun connectWifi() {
        phone.controller.offerWifi(listOf("10.0.0.2"), 1)
        loop.drain()
        wifi.connect(mac.wifiOffers.last().token)
        loop.drain()
        assertTrue(phone.controller.wifiReady)
    }

    @Test
    fun capsAreExchanged() {
        assertTrue(phone.controller.peerCaps.isEmpty())
        connect()
        assertTrue(phone.controller.peerSupports(Caps.SCREEN))
        assertTrue(mac.controller.peerSupports(Caps.REPLY))
        assertEquals("test", mac.controller.peerApp)
        ble.disconnect()
        loop.drain()
        assertTrue(phone.controller.peerCaps.isEmpty())
    }

    @Test
    fun oldPeerHidesNewFeaturesButStillLinks() {
        mac.caps = Caps.V1 // the Mac still runs 0.1.1
        connect()
        assertFalse(phone.controller.peerSupports(Caps.BATTERY))
        assertTrue(phone.controller.peerSupports(Caps.CLIP))
        // Even if a new message is sent anyway, the old side just ignores unknown types; the link survives.
        assertTrue(phone.controller.send(Battery(50, false, ts = 1)))
        loop.drain()
        assertTrue(mac.state is LinkState.Connected)
    }

    @Test
    fun featureMessagesFlowOverBluetooth() {
        connect()
        assertTrue(mac.controller.send(Ring(60)))
        assertTrue(mac.controller.send(NotifAction("k", "reply", "hi")))
        assertTrue(phone.controller.send(Battery(42, true, "ac", "cellular", "5G", 4, 7)))
        loop.drain()
        assertEquals(listOf(Ring(60), NotifAction("k", "reply", "hi")), phone.messages)
        assertEquals(listOf(Battery(42, true, "ac", "cellular", "5G", 4, 7)), mac.messages)
    }

    @Test
    fun videoNeedsWifiAndScreenControlPrefersIt() {
        connect()
        val frame = VideoFrame("screen", 1, 0, true, true, 0, 1, ByteArray(100_000) { it.toByte() })
        assertFalse(phone.controller.send(frame)) // Bluetooth only: refused
        assertTrue(phone.controller.send(ScreenState("needs_wifi"))) // still reaches the Mac over BLE
        loop.drain()
        assertEquals(ScreenState("needs_wifi"), mac.messages.last())
        connectWifi()
        assertTrue(phone.controller.send(frame))
        assertTrue(mac.controller.send(ScreenInput("tap", 0.5, 0.5)))
        loop.drain()
        val got = mac.messages.last() as VideoFrame
        assertArrayEquals(frame.data, got.data)
        assertEquals(ScreenInput("tap", 0.5, 0.5), phone.messages.last())
    }

    @Test
    fun wifiOnlyMediaWaitsForWifiAndCarriesKind() {
        connect()
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val photo = phone.files.stage("IMG_1.jpg", bytes, loop.now).copy(kind = "photo", wifiOnly = true, mime = "image/jpeg")
        val shot = phone.files.stage("Screenshot_1.png", ByteArray(20_000) { 1 }, loop.now).copy(kind = "screenshot")
        phone.controller.sendFile(photo)
        phone.controller.sendFile(shot)
        loop.drain()
        // The screenshot goes over Bluetooth; the photo waits.
        assertEquals(TransferStatus.DONE, mac.transfers[shot.id]!!.status)
        assertEquals("screenshot", mac.transfers[shot.id]!!.kind)
        assertEquals(TransferStatus.WAITING, phone.transfers[photo.id]!!.status)
        assertEquals(TransferEngine.WAITING_FOR_WIFI_ONLY, phone.transfers[photo.id]!!.error)
        assertTrue(mac.transfers[photo.id] == null)
        assertTrue(phone.controller.transfers.hasOutgoing(photo.sha256))
        connectWifi()
        assertEquals(TransferStatus.DONE, mac.transfers[photo.id]!!.status)
        assertEquals(Transport.WIFI, mac.transfers[photo.id]!!.via)
        assertEquals("photo", mac.transfers[photo.id]!!.kind)
        assertArrayEquals(bytes, mac.files.finished[photo.id])
    }
}
