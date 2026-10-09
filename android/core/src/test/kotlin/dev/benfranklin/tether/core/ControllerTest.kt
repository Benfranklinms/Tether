package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.Notif
import dev.benfranklin.tether.core.proto.NotifDismiss
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.session.PairingContext
import dev.benfranklin.tether.core.session.PairingUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTest {
    private val loop = Loop()

    private fun fresh(): Pair<Side, Side> = Side("Pixel", Role.INITIATOR, loop) to Side("MacBook", Role.RESPONDER, loop)

    @Test
    fun manualPairingWithMatchingCodes() {
        val (phone, mac) = fresh()
        val link = LoopbackBle(loop, phone, mac)
        link.connect(PairingContext(acceptNew = true), PairingContext(acceptNew = true))
        loop.drain()
        val ps = phone.state as LinkState.Pairing
        val ms = mac.state as LinkState.Pairing
        assertEquals(ps.sas, ms.sas)
        assertEquals(6, ps.sas.length)
        assertEquals("MacBook", ps.peerName)
        assertFalse(ps.auto)
        phone.controller.confirmPairing()
        loop.drain()
        assertTrue(phone.state is LinkState.Pairing) // still waiting for the Mac
        mac.controller.confirmPairing()
        loop.drain()
        assertEquals(LinkState.Connected("MacBook", wifi = false), phone.state)
        assertEquals(LinkState.Connected("Pixel", wifi = false), mac.state)
        assertArrayEquals(mac.identity.localKey.publicKey, phone.identity.peer!!.keyBytes)
        assertArrayEquals(phone.identity.localKey.publicKey, mac.identity.peer!!.keyBytes)
    }

    @Test
    fun qrPairingIsOneTap() {
        val (phone, mac) = fresh()
        val token = ByteArray(16) { it.toByte() }
        val qr = PairingUri.parse(PairingUri(mac.identity.localKey.publicKey, token, "Ben’s MacBook Air").encode())!!
        assertEquals("Ben’s MacBook Air", qr.name)
        LoopbackBle(loop, phone, mac).connect(
            PairingContext(acceptNew = true, expectedRemoteKey = qr.key, token = qr.token),
            PairingContext(acceptNew = true, token = token),
        )
        loop.drain()
        assertEquals(LinkState.Connected("MacBook", false), phone.state)
        assertEquals(LinkState.Connected("Pixel", false), mac.state)
    }

    @Test
    fun staleQrTokenFallsBackToManualConfirmOnMac() {
        val (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(
            PairingContext(acceptNew = true, expectedRemoteKey = mac.identity.localKey.publicKey, token = ByteArray(16)),
            PairingContext(acceptNew = true, token = ByteArray(16) { 1 }),
        )
        loop.drain()
        assertTrue(mac.state is LinkState.Pairing)
        mac.controller.confirmPairing()
        loop.drain()
        assertTrue(phone.state is LinkState.Connected)
    }

    @Test
    fun qrForDifferentMacIsRefused() {
        val (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(
            PairingContext(acceptNew = true, expectedRemoteKey = ByteArray(32) { 5 }, token = ByteArray(16)),
            PairingContext(acceptNew = true),
        )
        loop.drain()
        assertEquals(LinkState.Offline, phone.state)
        assertTrue(phone.closes.any { it.first == CloseReason.WRONG_DEVICE })
        assertNull(phone.identity.peer)
    }

    @Test
    fun unknownPhoneRejectedWhenMacNotPairing() {
        val (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(PairingContext(acceptNew = true), PairingContext.CLOSED)
        loop.drain()
        assertEquals(LinkState.Offline, mac.state)
        assertTrue(phone.closes.any { it.first == CloseReason.UNTRUSTED && it.second })
    }

    @Test
    fun rejectingCodeClosesBothSides() {
        val (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(PairingContext(acceptNew = true), PairingContext(acceptNew = true))
        loop.drain()
        mac.controller.rejectPairing()
        loop.drain()
        assertEquals(LinkState.Offline, phone.state)
        assertTrue(phone.closes.any { it.first == CloseReason.PAIRING_REJECTED })
        assertNull(phone.identity.peer)
        assertNull(mac.identity.peer)
    }

    @Test
    fun pairingTimesOut() {
        val (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(PairingContext(acceptNew = true), PairingContext(acceptNew = true))
        loop.drain()
        loop.now += TetherController.PAIRING_TIMEOUT_MS + 1
        mac.controller.tick()
        loop.drain()
        assertTrue(mac.closes.any { it.first == CloseReason.TIMEOUT })
        assertEquals(LinkState.Offline, phone.state)
    }

    @Test
    fun pairedDevicesReconnectSilently() {
        val (phone, mac) = pairedSides(loop)
        LoopbackBle(loop, phone, mac).connect()
        loop.drain()
        assertEquals(LinkState.Connected("MacBook", false), phone.state)
        assertTrue(phone.states.none { it is LinkState.Pairing })
    }

    @Test
    fun clipboardAndNotificationsFlow() {
        val (phone, mac) = pairedSides(loop)
        LoopbackBle(loop, phone, mac, mtu = 23).connect()
        loop.drain()
        val text = "multi-line ✓\n".repeat(200)
        assertTrue(mac.controller.send(Clip("c1", 5, text)))
        val n = Notif("k1", "com.whatsapp", "WhatsApp", "Asha", "hey", 9)
        assertTrue(phone.controller.send(n))
        assertTrue(mac.controller.send(NotifDismiss("k1")))
        loop.drain()
        assertEquals(Clip("c1", 5, text), phone.messages.single { it is Clip })
        assertEquals(n, mac.messages.single())
        assertEquals(NotifDismiss("k1"), phone.messages.last())
    }

    @Test
    fun oversizedClipRefusedOverBluetooth() {
        val (phone, mac) = pairedSides(loop)
        LoopbackBle(loop, phone, mac).connect()
        loop.drain()
        assertFalse(mac.controller.send(Clip("big", 1, "x".repeat(Protocol.MAX_CLIP_BYTES + 1))))
    }

    @Test
    fun sendWhileOfflineReturnsFalse() {
        val (phone, _) = pairedSides(loop)
        assertFalse(phone.controller.send(Clip("c", 1, "hi")))
    }

    @Test
    fun unpairClearsBothSides() {
        val (phone, mac) = pairedSides(loop)
        LoopbackBle(loop, phone, mac).connect()
        loop.drain()
        mac.controller.unpair()
        loop.drain()
        assertNull(mac.identity.peer)
        assertNull(phone.identity.peer)
        assertEquals(true, phone.unpairedByRemote)
        assertEquals(false, mac.unpairedByRemote)
        assertEquals(LinkState.Offline, phone.state)
    }

    @Test
    fun keyRotationTakesEffectOnNextConnect() {
        val (phone, mac) = pairedSides(loop)
        val link = LoopbackBle(loop, phone, mac)
        link.connect()
        loop.drain()
        val old = phone.identity.localKey.publicKey
        assertTrue(phone.controller.rotateKey())
        loop.drain()
        assertEquals(1, phone.rotated)
        assertFalse(old.contentEquals(phone.identity.localKey.publicKey))
        assertNotNull(mac.identity.peer!!.pendingKey)
        link.disconnect()
        loop.drain()
        link.connect()
        loop.drain()
        assertTrue(phone.state is LinkState.Connected)
        assertArrayEquals(phone.identity.localKey.publicKey, mac.identity.peer!!.keyBytes)
        assertNull(mac.identity.peer!!.pendingKey)
        assertFalse(mac.identity.peer!!.trusts(old))
    }

    @Test
    fun lostRotationAckIsRetriedAndOldKeyStillWorks() {
        val (phone, mac) = pairedSides(loop)
        val link = LoopbackBle(loop, phone, mac)
        link.connect()
        loop.drain()
        val old = phone.identity.localKey.publicKey
        link.cutAfter = 1 // KEY_ROTATE goes out, the ACK is lost
        phone.controller.rotateKey()
        loop.drain()
        assertArrayEquals(old, phone.identity.localKey.publicKey)
        assertNotNull(phone.identity.pendingLocalKey)
        link.connect()
        loop.drain()
        // Reconnected with the old key, rotation retried and completed.
        assertEquals(1, phone.rotated)
        assertNull(phone.identity.pendingLocalKey)
    }

    @Test
    fun heartbeatDetectsDeadLink() {
        val (phone, mac) = pairedSides(loop)
        val link = LoopbackBle(loop, phone, mac)
        link.connect()
        loop.drain()
        link.cutAfter = 0 // silently drop everything from now on, no disconnect callback yet
        loop.now += 46_000
        phone.controller.tick()
        loop.drain()
        loop.now += 21_000
        phone.controller.tick()
        loop.drain()
        assertTrue(phone.closes.any { it.first == CloseReason.TIMEOUT || it.first == CloseReason.LINK_LOST })
    }

    @Test
    fun handshakeTimeout() {
        val (phone, mac) = pairedSides(loop)
        val link = LoopbackBle(loop, phone, mac)
        link.cutAfter = 0
        link.connect()
        loop.drain()
        loop.now += TetherController.HANDSHAKE_TIMEOUT_MS + 1
        phone.controller.tick()
        loop.drain()
        assertEquals(LinkState.Offline, phone.state)
    }
}
