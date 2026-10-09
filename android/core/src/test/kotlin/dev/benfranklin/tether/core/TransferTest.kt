package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** End-to-end transfers through two controllers over the loopback Bluetooth and Wi-Fi links. */
class TransferTest {
    private val loop = Loop()
    private val sides = pairedSides(loop)
    private val phone = sides.first
    private val mac = sides.second
    private val ble = LoopbackBle(loop, phone, mac, mtu = 247)
    private val wifi = LoopbackWifi(loop, phone, mac)

    private fun data(n: Int) = Random(n).nextBytes(n)

    private fun connect() {
        ble.connect()
        loop.drain()
        assertTrue(phone.state is LinkState.Connected)
    }

    @Test
    fun phoneToMacOverBluetooth() {
        connect()
        val bytes = data(200_000)
        val rec = phone.files.stage("photo.jpg", bytes, loop.now)
        phone.controller.sendFile(rec)
        loop.drain()
        assertEquals(TransferStatus.DONE, phone.transfers[rec.id]!!.status)
        val inRec = mac.transfers[rec.id]!!
        assertEquals(TransferStatus.DONE, inRec.status)
        assertEquals(Direction.IN, inRec.direction)
        assertEquals(Transport.BLE, inRec.via)
        assertArrayEquals(bytes, mac.files.finished[rec.id])
        assertTrue(rec.id in phone.files.released)
    }

    @Test
    fun emptyFileTransfers() {
        connect()
        val rec = mac.files.stage("empty.txt", ByteArray(0), loop.now)
        mac.controller.sendFile(rec)
        loop.drain()
        assertArrayEquals(ByteArray(0), phone.files.finished[rec.id])
    }

    @Test
    fun queuedWhileOfflineStartsOnConnect() {
        val a = phone.files.stage("a.bin", data(10_000), loop.now)
        val b = phone.files.stage("b.bin", data(20_000), loop.now)
        phone.controller.sendFile(a)
        phone.controller.sendFile(b)
        assertEquals(TransferStatus.QUEUED, phone.transfers[a.id]!!.status)
        connect()
        loop.drain()
        assertEquals(TransferStatus.DONE, phone.transfers[a.id]!!.status)
        assertEquals(TransferStatus.DONE, phone.transfers[b.id]!!.status)
    }

    @Test
    fun resumesAfterBluetoothDropFromLastAckedChunk() {
        connect()
        val bytes = data(300_000)
        val rec = phone.files.stage("video.mp4", bytes, loop.now)
        ble.cutAfter = 400 // roughly 100 KB in
        phone.controller.sendFile(rec)
        loop.drain()
        assertEquals(TransferStatus.WAITING, phone.transfers[rec.id]!!.status)
        val partial = mac.files.partialLength(rec.id)
        assertTrue("some bytes landed before the drop: $partial", partial in 1 until bytes.size)
        val sentBefore = ble.fragmentsSent
        connect()
        loop.drain()
        assertEquals(TransferStatus.DONE, mac.transfers[rec.id]!!.status)
        assertArrayEquals(bytes, mac.files.finished[rec.id])
        // Resumed, not restarted: the second leg moved far fewer fragments than a full send.
        val fullSend = bytes.size / (247 - 7)
        assertTrue(ble.fragmentsSent - sentBefore < fullSend)
    }

    @Test
    fun appRestartMidTransferResumes() {
        connect()
        val bytes = data(150_000)
        val rec = phone.files.stage("doc.pdf", bytes, loop.now)
        ble.cutAfter = 300
        phone.controller.sendFile(rec)
        loop.drain()
        // Simulate the phone app being killed: new controller restoring persisted records.
        val restored = Side("Pixel", phone.role, loop).also {
            it.identity.localKey = phone.identity.localKey
            it.identity.peer = phone.identity.peer
            it.files.sources.putAll(phone.files.sources)
            it.controller.restoreTransfers(phone.transfers.values.toList())
        }
        val link2 = LoopbackBle(loop, restored, mac)
        link2.connect()
        loop.drain()
        assertEquals(TransferStatus.DONE, restored.transfers[rec.id]!!.status)
        assertArrayEquals(bytes, mac.files.finished[rec.id])
    }

    @Test
    fun corruptedFileIsRetriedOnceThenSucceeds() {
        connect()
        val bytes = data(40_000)
        mac.files.corruptNextFinish = true
        val rec = phone.files.stage("x.bin", bytes, loop.now)
        phone.controller.sendFile(rec)
        loop.drain()
        assertEquals(TransferStatus.DONE, phone.transfers[rec.id]!!.status)
        assertEquals(1, phone.transfers[rec.id]!!.retries)
        assertArrayEquals(bytes, mac.files.finished[rec.id])
    }

    @Test
    fun receiverCancelStopsSender() {
        connect()
        val rec = phone.files.stage("big.bin", data(500_000), loop.now)
        ble.cutAfter = null
        phone.controller.sendFile(rec)
        // Let a few chunks through, then cancel on the Mac.
        loop.step(2_000)
        mac.controller.cancelTransfer(rec.id)
        loop.drain()
        assertEquals(TransferStatus.CANCELLED, phone.transfers[rec.id]!!.status)
        assertEquals(TransferStatus.CANCELLED, mac.transfers[rec.id]!!.status)
        assertTrue(mac.files.partial[rec.id] == null)
    }

    @Test
    fun senderCancelIsFinal() {
        connect()
        val rec = phone.files.stage("r.bin", data(60_000), loop.now)
        phone.controller.sendFile(rec)
        phone.controller.cancelTransfer(rec.id)
        loop.drain()
        assertEquals(TransferStatus.CANCELLED, phone.transfers[rec.id]!!.status)
        assertEquals(TransferStatus.CANCELLED, mac.transfers[rec.id]!!.status)
        phone.controller.retryTransfer(rec.id) // only failures are retryable
        loop.drain()
        assertEquals(TransferStatus.CANCELLED, phone.transfers[rec.id]!!.status)
    }

    @Test
    fun unreadableSourceFailsThenRetrySucceeds() {
        connect()
        phone.files.failReads = true
        val bytes = data(1000)
        val rec = phone.files.stage("gone.bin", bytes, loop.now)
        phone.controller.sendFile(rec)
        loop.drain()
        assertEquals(TransferStatus.FAILED, phone.transfers[rec.id]!!.status)
        assertTrue(mac.transfers[rec.id]!!.status.isFinal)
        phone.files.failReads = false
        phone.controller.retryTransfer(rec.id)
        loop.drain()
        assertTrue(rec.id !in phone.transfers)
        val retried = phone.transfers.values.single()
        assertEquals(TransferStatus.DONE, retried.status)
        assertArrayEquals(bytes, mac.files.finished[retried.id])
    }

    @Test
    fun switchesToWifiMidTransferAndFallsBackToBluetooth() {
        connect()
        val bytes = data(2_000_000)
        val rec = phone.files.stage("movie.mov", bytes, loop.now)
        phone.controller.sendFile(rec)
        loop.step(4_000)
        val viaBle = mac.files.partialLength(rec.id)
        assertTrue(viaBle > 0)
        // Phone offers Wi-Fi over BLE, the Mac connects with the token.
        assertTrue(phone.controller.offerWifi(listOf("192.168.1.20"), 5000))
        loop.step(2_000)
        val offer = mac.wifiOffers.single()
        wifi.cutAfter = 10 // Wi-Fi dies after 10 records (handshake, bind, a few chunks)
        wifi.connect(offer.token)
        loop.drain()
        assertEquals(TransferStatus.DONE, mac.transfers[rec.id]!!.status)
        assertArrayEquals(bytes, mac.files.finished[rec.id])
        assertEquals(Transport.BLE, phone.transfers[rec.id]!!.via) // finished on Bluetooth after the drop
        assertTrue(phone.state is LinkState.Connected && !(phone.state as LinkState.Connected).wifi)
    }

    @Test
    fun wifiCarriesWholeTransferAndLargeClips() {
        connect()
        phone.controller.offerWifi(listOf("10.0.0.2"), 1)
        loop.drain()
        wifi.connect(mac.wifiOffers.single().token)
        loop.drain()
        assertEquals(LinkState.Connected("MacBook", wifi = true), phone.state)
        val bytes = data(3_000_000)
        val rec = mac.files.stage("disk.img", bytes, loop.now)
        mac.controller.sendFile(rec)
        loop.drain()
        assertEquals(Transport.WIFI, phone.transfers[rec.id]!!.via)
        assertArrayEquals(bytes, phone.files.finished[rec.id])
        val big = "y".repeat(Protocol.MAX_CLIP_BYTES * 4)
        assertTrue(mac.controller.send(dev.benfranklin.tether.core.proto.Clip("c", 1, big)))
        loop.drain()
        assertEquals(big, (phone.messages.last() as dev.benfranklin.tether.core.proto.Clip).text)
    }

    @Test
    fun wrongWifiTokenIsRefused() {
        connect()
        phone.controller.offerWifi(listOf("10.0.0.2"), 1)
        loop.drain()
        wifi.connect("AAAAAAAAAAAAAAAAAAAAAA")
        loop.drain()
        assertEquals(LinkState.Connected("MacBook", wifi = false), phone.state)
        assertTrue(!phone.controller.wifiReady)
    }

    @Test
    fun tokenIsSingleUse() {
        connect()
        phone.controller.offerWifi(listOf("10.0.0.2"), 1)
        loop.drain()
        val token = mac.wifiOffers.single().token
        wifi.connect(token)
        loop.drain()
        assertTrue(phone.controller.wifiReady)
        wifi.disconnect()
        loop.drain()
        wifi.connect(token)
        loop.drain()
        assertTrue(!phone.controller.wifiReady)
    }

    @Test
    fun hugeFileWaitsForWifi() {
        connect()
        val size = Protocol.FILE_MAX_BYTES + 1
        val rec = phone.files.stage("huge.bin", ByteArray(0), loop.now).copy(size = size)
        phone.controller.sendFile(rec)
        loop.drain()
        val r = phone.transfers[rec.id]!!
        assertEquals(TransferStatus.WAITING, r.status)
        assertEquals(TransferEngine.WAITING_FOR_WIFI, r.error)
        assertTrue(mac.transfers[rec.id] == null)
    }

    @Test
    fun receiverRejectsOverLimit() {
        val engineMac = TransferEngine(mac.files, mac.files, object : dev.benfranklin.tether.core.transfer.TransferListener {
            override fun onTransferUpdated(record: dev.benfranklin.tether.core.transfer.TransferRecord, important: Boolean) = Unit
        }, maxIncoming = 10)
        val replies = ArrayList<dev.benfranklin.tether.core.proto.Message>()
        val ch = dev.benfranklin.tether.core.transfer.Channel(Transport.BLE) { replies += it }
        engineMac.handle(dev.benfranklin.tether.core.proto.FileOffer("id", "a", 11, "0".repeat(64)), ch)
        assertEquals(dev.benfranklin.tether.core.proto.FileReject("id", "too_large"), replies.single())
        assertEquals(TransferStatus.FAILED, engineMac["id"]!!.status)
    }

    @Test
    fun safeFileNames() {
        for (v in Vectors.array("filenames.json")) {
            assertEquals(v.str("raw"), v.str("safe"), TransferEngine.safeFileName(v.str("raw")))
        }
    }
}
