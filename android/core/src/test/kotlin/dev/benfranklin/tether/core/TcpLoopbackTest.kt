package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.core.wifi.StreamFraming
import dev.benfranklin.tether.core.wifi.TcpLink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/** The Wi-Fi fast path over real TCP sockets on 127.0.0.1, with Bluetooth as the loopback. */
class TcpLoopbackTest {
    private val loop = Loop()
    private val sides = pairedSides(loop)
    private val phone = sides.first
    private val mac = sides.second
    private val ble = LoopbackBle(loop, phone, mac, mtu = 185)

    private var phoneLink: TcpLink? = null
    private var macLink: TcpLink? = null

    /** Phone listens, offers over BLE; the Mac connects with the token. */
    private fun bringUpWifi(server: ServerSocket) {
        thread(isDaemon = true) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                return@thread
            }
            loop.execute {
                val link = TcpLink(s, loop, { phone.controller.wifiRecord(it) }, { phone.controller.wifiDisconnected() })
                phoneLink = link
                phone.controller.wifiConnected(link::write, null)
                link.start()
            }
        }
        assertTrue(phone.controller.offerWifi(listOf("127.0.0.1"), server.localPort))
        loop.drain()
        val offer = mac.wifiOffers.last()
        val socket = Socket(InetAddress.getByName(offer.addrs.first()), offer.port)
        val link = TcpLink(socket, loop, { mac.controller.wifiRecord(it) }, { mac.controller.wifiDisconnected() })
        macLink = link
        mac.controller.wifiConnected(link::write, offer.token)
        link.start()
        loop.runUntil { phone.controller.wifiReady && mac.controller.wifiReady }
    }

    @Test
    fun streamFramingRoundTrip() {
        val out = ByteArrayOutputStream()
        val records = listOf(ByteArray(1) { 1 }, Random(1).nextBytes(70_000), ByteArray(3) { 9 })
        records.forEach { StreamFraming.write(out, it) }
        val input = ByteArrayInputStream(out.toByteArray())
        records.forEach { assertArrayEquals(it, StreamFraming.read(input)) }
        assertNull(StreamFraming.read(input))
    }

    @Test(expected = IOException::class)
    fun streamFramingRejectsHugeLength() {
        StreamFraming.read(ByteArrayInputStream(byteArrayOf(0x7f, 0, 0, 0)))
    }

    @Test(expected = IOException::class)
    fun streamFramingRejectsTruncation() {
        StreamFraming.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 10, 1, 2)))
    }

    @Test
    fun fileOverRealTcpThenFallbackToBluetoothAndResume() {
        ble.connect()
        loop.drain()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            bringUpWifi(server)
            assertEquals(LinkState.Connected("MacBook", wifi = true), phone.state)

            val bytes = Random(42).nextBytes(8 * 1024 * 1024)
            val rec = phone.files.stage("archive.zip", bytes, loop.now)
            phone.controller.sendFile(rec)
            // Let a good part go over Wi-Fi, then yank the socket.
            loop.runUntil { mac.files.partialLength(rec.id) > 2 * 1024 * 1024 }
            assertEquals(Transport.WIFI, mac.transfers[rec.id]!!.via)
            macLink!!.close()
            loop.runUntil { !phone.controller.wifiReady && !mac.controller.wifiReady }
            val atDrop = mac.files.partialLength(rec.id)
            // Bluetooth picks it up from the last durable byte. Move the rest quickly by
            // bringing Wi-Fi back (new token), proving a second resume as well.
            loop.step(2_000)
            assertTrue(mac.files.partialLength(rec.id) >= atDrop)
            bringUpWifi(server)
            loop.runUntil(60_000) { mac.transfers[rec.id]?.status == TransferStatus.DONE }
            assertArrayEquals(bytes, mac.files.finished[rec.id])
            assertEquals(TransferStatus.DONE, phone.transfers[rec.id]!!.status)
        }
        phoneLink?.close()
        macLink?.close()
    }

    @Test
    fun largeClipboardGoesOverTcp() {
        ble.connect()
        loop.drain()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            bringUpWifi(server)
            val text = "z".repeat(Protocol.MAX_CLIP_BYTES_WIFI - 10)
            assertTrue(mac.controller.send(dev.benfranklin.tether.core.proto.Clip("c", 1, text)))
            loop.runUntil { phone.messages.isNotEmpty() }
            assertEquals(text, (phone.messages.single() as dev.benfranklin.tether.core.proto.Clip).text)
        }
        phoneLink?.close()
        macLink?.close()
    }

    @Test
    fun strangerOnTheTcpPortIsRefused() {
        ble.connect()
        loop.drain()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            bringUpWifi(server)
            // A second, unpaired device dials in: the handshake itself fails (unknown static key).
            val stranger = Side("Evil", mac.role, loop)
            stranger.identity.peer = mac.identity.peer // pretends, but holds a different static key
            val s2 = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            s2.close()
            assertTrue(phone.controller.wifiReady) // the real session is unaffected
        }
        phoneLink?.close()
        macLink?.close()
    }
}
