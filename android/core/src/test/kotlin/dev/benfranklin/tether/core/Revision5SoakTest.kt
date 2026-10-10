package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.proto.ContinueCard
import dev.benfranklin.tether.core.proto.ContinueKinds
import dev.benfranklin.tether.core.proto.DndState
import dev.benfranklin.tether.core.proto.FileKinds5
import dev.benfranklin.tether.core.proto.FsEntry
import dev.benfranklin.tether.core.proto.FsList
import dev.benfranklin.tether.core.proto.FsListReq
import dev.benfranklin.tether.core.proto.FsRoots
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.Otp
import dev.benfranklin.tether.core.proto.PhotoItem
import dev.benfranklin.tether.core.proto.Photos
import dev.benfranklin.tether.core.proto.PhotosReq
import dev.benfranklin.tether.core.proto.Thumb
import dev.benfranklin.tether.core.proto.ThumbReq
import dev.benfranklin.tether.core.proto.TypeResult
import dev.benfranklin.tether.core.proto.TypeText
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Link soak with 0.5 traffic (simulated hours on the real controllers): browsing phone folders
 * with thumbnails, photo pages, Continue cards with thumbnails, one-time codes, typing bursts,
 * DND flips and clipboard images, over Bluetooth only (Android's 512-byte write limit on) and
 * over Wi-Fi with idle Bluetooth. Nothing may be lost, reordered or close the link.
 */
class Revision5SoakTest {
    private val loop = Loop()
    private val sides = pairedSides(loop)
    private val phone = sides.first
    private val mac = sides.second
    private val jpeg = Random(9).nextBytes(24_000)

    private fun listing(ref: String, n: Int) = FsList(
        ref, FsRoots.WHATSAPP, "WhatsApp Images",
        (0 until n).map { FsEntry("IMG-20261010-WA$it.jpg", it % 12 == 0, 150_000L + it, loop.now - it, "image/jpeg") },
        total = n,
    )

    private fun soak(wifiUp: Boolean) {
        val ble = LoopbackBle(loop, phone, mac, mtu = 514).apply { androidAttrLimit = true }
        ble.connect()
        loop.drain()
        if (wifiUp) {
            val wifi = LoopbackWifi(loop, phone, mac)
            phone.controller.offerWifi(listOf("10.0.0.2"), 1)
            loop.drain()
            wifi.connect(mac.wifiOffers.last().token)
            loop.drain()
            assertTrue(phone.controller.wifiReady)
        }
        val phoneSent = ArrayList<Message>()
        val macSent = ArrayList<Message>()
        fun p(m: Message) {
            assertTrue(phone.controller.send(m))
            phoneSent += m
        }
        fun m(x: Message) {
            assertTrue(mac.controller.send(x))
            macSent += x
        }
        var clipImages = 0
        repeat(4 * 3600) { s ->
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            if (s % 300 == 0) {
                m(FsListReq("l$s", FsRoots.WHATSAPP, "WhatsApp Images"))
                p(listing("l$s", 120))
                repeat(4) { i ->
                    m(ThumbReq("t$s-$i", FsRoots.WHATSAPP, "WhatsApp Images/IMG-$i.jpg"))
                    p(Thumb("t$s-$i", true, jpeg))
                }
            }
            if (s % 600 == 10) {
                m(PhotosReq("p$s", 60))
                p(Photos("p$s", (0 until 60).map { PhotoItem("${s + it}", "IMG_$it.jpg", 3_000_000, loop.now - it, 4000, 3000) }, true))
            }
            if (s % 450 == 20) {
                p(ContinueCard("photo-$s", ContinueKinds.PHOTO, "IMG_$s.jpg", "Photo · 3.1 MB", root = FsRoots.PHOTOS, path = "$s",
                    ts = loop.now, thumb = jpeg))
                p(ContinueCard("tab", ContinueKinds.TAB, "Tab $s", url = "https://example.com/$s", app = "Chrome", ts = loop.now))
            }
            if (s % 777 == 5) p(Otp("${100_000 + s}", "HDFC Bank", "sms", loop.now))
            if (s % 120 == 30) {
                repeat(10) { k -> m(TypeText(s * 10L + k, "hello $k")) }
                p(TypeResult(s * 10L + 9, true, field = "Chrome · Search"))
            }
            if (s % 1800 == 40) {
                m(DndState(s % 3600 == 40, "mac", loop.now, false))
                p(DndState(s % 3600 == 40, "phone", loop.now, true))
            }
            if (s % 1200 == 50) {
                val rec = phone.files.stage("clip$s.png", Random(s).nextBytes(60_000), loop.now)
                    .copy(kind = FileKinds5.CLIP, mime = "image/png")
                phone.controller.sendFile(rec)
                clipImages++
            }
            loop.drain()
        }
        repeat(120) {
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            loop.drain()
        }
        assertTrue("closes: phone=${phone.closes} mac=${mac.closes}", phone.closes.isEmpty() && mac.closes.isEmpty())
        assertFalse(ble.wedged)
        val rev5 = { x: Message -> x.type == 0x32 || x.type in 0xC0..0xCC || x.type in 0xE0..0xE2 }
        assertEquals("phone -> Mac, in order", phoneSent, mac.messages.filter(rev5))
        assertEquals("Mac -> phone, in order", macSent, phone.messages.filter(rev5))
        assertTrue("phone sent ${phoneSent.size}", phoneSent.size > 300)
        val done = mac.transfers.values.count { it.direction == Direction.IN && it.kind == FileKinds5.CLIP && it.status == TransferStatus.DONE }
        assertEquals(clipImages, done)
        assertTrue(mac.state is LinkState.Connected)
    }

    @Test
    fun fourHoursOfRevision5TrafficOverBluetoothOnly() = soak(wifiUp = false)

    @Test
    fun fourHoursOfRevision5TrafficOverWifiWithIdleBluetooth() = soak(wifiUp = true)
}
