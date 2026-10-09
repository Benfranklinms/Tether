package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.features.AnnexB
import dev.benfranklin.tether.core.features.FrameSplitter
import dev.benfranklin.tether.core.features.GestureMapper
import dev.benfranklin.tether.core.proto.Battery
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.CloseReason
import dev.benfranklin.tether.core.proto.InputKinds
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.Notif
import dev.benfranklin.tether.core.proto.NotifAction
import dev.benfranklin.tether.core.proto.NotifActionInfo
import dev.benfranklin.tether.core.proto.NotifActionResult
import dev.benfranklin.tether.core.proto.Ring
import dev.benfranklin.tether.core.proto.RingState
import dev.benfranklin.tether.core.proto.RingStop
import dev.benfranklin.tether.core.proto.ScreenInput
import dev.benfranklin.tether.core.proto.ScreenStart
import dev.benfranklin.tether.core.proto.ScreenState
import dev.benfranklin.tether.core.proto.ScreenStop
import dev.benfranklin.tether.core.proto.VideoFeedback
import dev.benfranklin.tether.core.session.PairingContext
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Kotlin phone (initiator) against the real Swift Mac core (responder) over a pipe, with real
 * fragmentation on "Bluetooth" and record framing on "Wi-Fi". Runs only when TETHER_SWIFT_PEER
 * points at the tether-peer binary (see tools/interop); CI runs it in the "Interop" job.
 */
class InteropTest {
    private val peerPath: String? = System.getenv("TETHER_SWIFT_PEER")

    private class Peer(path: String, macFragment: Int, private val loop: Loop, private val phone: Side, vararg flags: String) {
        val proc: Process = ProcessBuilder(listOf(path, macFragment.toString()) + flags).redirectErrorStream(false).start()
        private val toPeer = PrintWriter(proc.outputStream, true)
        val lines = CopyOnWriteArrayList<String>()
        var key: ByteArray? = null
        var token: ByteArray? = null

        init {
            val r = BufferedReader(InputStreamReader(proc.inputStream))
            key = B64.decode(r.readLine().removePrefix("KEY "))
            token = B64.decode(r.readLine().removePrefix("TOKEN "))
            Thread {
                while (true) {
                    val l = r.readLine() ?: break
                    when {
                        l.startsWith("F ") -> {
                            val f = Hex.decode(l.substring(2))
                            loop.execute { phone.controller.bleFragment(f) }
                        }
                        l.startsWith("W ") -> {
                            val rec = Hex.decode(l.substring(2))
                            loop.execute { phone.controller.wifiRecord(rec) }
                        }
                        else -> lines += l
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun send(line: String) = synchronized(toPeer) { toPeer.println(line) }

        fun has(prefix: String, contains: String = ""): Boolean = lines.any { it.startsWith(prefix) && it.contains(contains) }

        fun close() {
            send("QUIT")
            proc.destroy()
        }
    }

    private fun session(
        phoneFragment: Int,
        macFragment: Int,
        qr: Boolean,
        vararg flags: String,
        body: (Loop, Side, Peer) -> Unit = { _, _, _ -> },
    ) {
        val path = peerPath
        assumeTrue("TETHER_SWIFT_PEER not set", path != null)
        val loop = Loop()
        loop.now = System.currentTimeMillis()
        val phone = Side("OnePlus Nord CE5", Role.INITIATOR, loop)
        val peer = Peer(path!!, macFragment, loop, phone, *flags)
        try {
            body(loop, phone, peer.also { pair(loop, phone, it, phoneFragment, qr) })
        } finally {
            peer.close()
        }
    }

    private fun pair(loop: Loop, phone: Side, peer: Peer, phoneFragment: Int, qr: Boolean) {
        val ctx = if (qr) {
            PairingContext(acceptNew = true, expectedRemoteKey = peer.key, token = peer.token)
        } else {
            PairingContext(acceptNew = true)
        }
        loop.execute { phone.controller.bleConnected(phoneFragment, { f -> peer.send("F " + Hex.encode(f)) }, ctx) }
        loop.runUntil(10_000) { phone.state is LinkState.Pairing || phone.state is LinkState.Connected || phone.closes.isNotEmpty() }
        if (phone.closes.isNotEmpty()) return
        val sas = (phone.state as? LinkState.Pairing)?.sas
        if (!qr) {
            loop.runUntil(10_000) { peer.has("STATE pairing") }
            val macLine = peer.lines.first { it.startsWith("STATE pairing") }
            assertTrue("SAS differs: phone=$sas mac=$macLine", macLine.contains("\"$sas\""))
            peer.send("CONFIRM")
            loop.execute { phone.controller.confirmPairing() }
        }
        loop.runUntil(10_000) { phone.state is LinkState.Connected && peer.has("PAIRED") }
    }

    private fun clipBothWays(loop: Loop, phone: Side, peer: Peer) {
        loop.execute { phone.controller.send(Clip("c1", 1, "hello from android ✓")) }
        loop.runUntil(10_000) { peer.has("MSG", "hello from android") }
        peer.send("CLIP hello from mac ✓")
        loop.runUntil(10_000) { phone.messages.any { it is Clip && it.text == "hello from mac ✓" } }
    }

    private fun run(phoneFragment: Int, macFragment: Int, qr: Boolean) = session(phoneFragment, macFragment, qr) { loop, phone, peer ->
        clipBothWays(loop, phone, peer)
        assertEquals("Ben’s MacBook Air", (phone.state as LinkState.Connected).peerName)
    }

    @Test fun codePairingSmallMtu() = run(phoneFragment = 20, macFragment = 20, qr = false)

    @Test fun codePairingBigMtu() = run(phoneFragment = 514, macFragment = 182, qr = false)

    @Test fun qrPairingAsymmetric() = run(phoneFragment = 182, macFragment = 509, qr = true)

    // ---------------- revision 2 ----------------

    /** Phone -> Mac messages: Swift decodes, re-encodes and echoes; Kotlin must decode the same value. */
    @Test
    fun phoneToMacFeatureMessagesRoundTrip() = session(185, 185, qr = true) { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps.SCREEN))
        loop.runUntil(5_000) { peer.has("CAPS", "battery,clip,file,media,notif,reply,ring,screen") }
        peer.send("ECHO on")
        val sent: List<Message> = listOf(
            Battery(83, true, "usb", "cellular", "5G", 3, 1_700_000_000_000),
            Battery(7, false, ts = 1),
            NotifActionResult("0|com.whatsapp|1|null|1", "reply", true),
            NotifActionResult("k", "a2", false, "action gone"),
            RingState(true), RingState(false, "timeout"),
            ScreenState("live", 720, 1600, 90, control = true), ScreenState("needs_wifi", reason = "bluetooth only"),
            Notif(
                "0|org.telegram.messenger|9|null|1", "org.telegram.messenger", "Telegram", "Asha", "Lunch? 🍜", 5,
                listOf(NotifActionInfo("reply", "Reply", true), NotifActionInfo("1", "Mark as read")),
            ),
        )
        for (m in sent) {
            val before = phone.messages.size
            loop.execute { assertTrue(phone.controller.send(m)) }
            loop.runUntil(10_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        // The Swift encoding (RAW lines) decodes in Kotlin to the same values too.
        val raws = peer.lines.filter { it.startsWith("RAW ") }.map { MessageCodec.decode(Hex.decode(it.substring(4))) }
        assertTrue(raws.containsAll(sent))
        clipBothWays(loop, phone, peer)
    }

    /** Mac -> phone messages: encoded by Kotlin, decoded + sent by the Swift controller. */
    @Test
    fun macToPhoneFeatureMessages() = session(244, 244, qr = false) { loop, phone, peer ->
        val fromMac: List<Message> = listOf(
            NotifAction("0|com.whatsapp|1|null|1", "reply", "On my way 🚲"), NotifAction("k", "1"),
            Ring(60), RingStop("user"), ScreenStart(1280, 60, 6_000_000), ScreenStop("user"),
            VideoFeedback(kbps = 5200, lagMs = 35, dropped = 1, keyframe = true),
            ScreenInput(InputKinds.TEXT, text = "héllo wörld"), ScreenInput(InputKinds.BACK),
        )
        for (m in fromMac) {
            val before = phone.messages.size
            peer.send("SEND " + Hex.encode(MessageCodec.encode(m)))
            loop.runUntil(10_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        assertFalse(peer.has("DECODE_ERROR"))
    }

    /** Wi-Fi session over the pipe: media transfers (BLE + Wi-Fi only), screen video, input mapping. */
    @Test
    fun wifiScreenAndMediaEndToEnd() = session(244, 244, qr = true) { loop, phone, peer ->
        // A screenshot over Bluetooth first; a camera photo must wait for Wi-Fi.
        val shotBytes = Random(1).nextBytes(60_000)
        val shot = phone.files.stage("Screenshot_20261010.png", shotBytes, loop.now).copy(kind = "screenshot", mime = "image/png")
        val photoBytes = Random(2).nextBytes(400_000)
        val photo = phone.files.stage("IMG_20261010.jpg", photoBytes, loop.now).copy(kind = "photo", wifiOnly = true)
        loop.execute {
            phone.controller.sendFile(shot)
            phone.controller.sendFile(photo)
        }
        loop.runUntil(30_000) { peer.has("FILE ${shot.id}") }
        assertTrue(peer.has("FILE ${shot.id} screenshot ${shot.sha256} DONE via=BLE"))
        assertFalse(peer.has("FILE ${photo.id}"))
        // Video is refused while only Bluetooth is up.
        var refused = false
        loop.execute { refused = !phone.controller.send(FrameSplitter.split("screen", 0, 0, true, true, ByteArray(10)).single()) }
        loop.runUntil(2_000) { refused }

        // Wi-Fi comes up: offer over BLE, the Swift side opens the session and binds.
        loop.execute { assertTrue(phone.controller.offerWifi(listOf("192.168.43.1"), 4040)) }
        loop.runUntil(10_000) { peer.has("WIFI") }
        loop.execute { phone.controller.wifiConnected({ r -> peer.send("W " + Hex.encode(r)) }, null) }
        loop.runUntil(10_000) { phone.controller.wifiReady }
        loop.runUntil(30_000) { peer.has("FILE ${photo.id}") }
        assertTrue(peer.has("FILE ${photo.id} photo ${photo.sha256} DONE via=WIFI"))

        // A 2.3 MB keyframe (SPS + PPS + IDR) split into parts, then a small P-frame.
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idr = byteArrayOf(0, 0, 0, 1, 0x65) + Random(3).nextBytes(2_300_000).map { if (it == 0.toByte()) 1 else it }.toByteArray()
        val key = sps + pps + idr
        val pFrame = byteArrayOf(0, 0, 0, 1, 0x41) + Random(4).nextBytes(9_000).map { if (it == 0.toByte()) 2 else it }.toByteArray()
        loop.execute {
            FrameSplitter.split("screen", 1, 0, AnnexB.isKeyframe(key), AnnexB.hasConfig(key), key).forEach { assertTrue(phone.controller.send(it)) }
            FrameSplitter.split("screen", 2, 16_666, false, false, pFrame).forEach { assertTrue(phone.controller.send(it)) }
        }
        loop.runUntil(30_000) { peer.has("FRAME 2") }
        assertTrue(peer.has("FRAME 1 ${Hex.encode(sha256(key))} ${key.size} key=true types=[7, 8, 5]"))
        assertTrue(peer.has("FRAME 2 ${Hex.encode(sha256(pFrame))} ${pFrame.size} key=false types=[1]"))

        // A click in the Mac window: 600x600 view showing a 720x1600 portrait frame (fitted 270x600 at x=165).
        // Clicking (300, 150) = frame (0.5, 0.25); the phone (1080x2400, now ROTATION_0) taps (540, 600).
        var before = phone.messages.size
        peer.send("TAP 600 600 720 1600 300 150 0")
        loop.runUntil(10_000) { phone.messages.size > before }
        val tap = phone.messages.last() as ScreenInput
        assertEquals(InputKinds.TAP, tap.k)
        val p = GestureMapper.toDisplay(tap.x, tap.y, tap.rot ?: 0, 0, 1080, 2400)
        assertEquals(540f, p.x, 1f)
        assertEquals(600f, p.y, 1f)
        // ...and if the phone turned to landscape (ROTATION_90) before the tap landed, the same
        // physical spot is (600, 540) on the 2400x1080 display.
        val r = GestureMapper.toDisplay(tap.x, tap.y, tap.rot ?: 0, 90, 2400, 1080)
        assertEquals(600f, r.x, 1f)
        assertEquals(540f, r.y, 1f)
        // A click in the letterbox does nothing.
        peer.send("TAP 600 600 720 1600 20 300 0")
        loop.runUntil(5_000) { peer.has("LETTERBOX") }
        // A drag becomes a swipe with its duration.
        before = phone.messages.size
        peer.send("DRAG 600 600 720 1600 300 500 300 100 300 0")
        loop.runUntil(10_000) { phone.messages.size > before }
        val swipe = phone.messages.last() as ScreenInput
        assertEquals(InputKinds.SWIPE, swipe.k)
        assertEquals(300L, swipe.dur)
        assertTrue(swipe.y2!! < swipe.y)
        // Clipboard still works with Wi-Fi up (clips prefer Wi-Fi).
        clipBothWays(loop, phone, peer)
        assertEquals(Transport.WIFI, phone.controller.transfers[photo.id]!!.via)
    }

    /** A 0.1.1 Mac: still pairs and syncs, and the phone sees that new features are unavailable. */
    @Test
    fun legacyMacHidesNewFeatures() = session(185, 185, qr = false, "legacy") { loop, phone, peer ->
        assertFalse(phone.controller.peerSupports(Caps.SCREEN))
        assertFalse(phone.controller.peerSupports(Caps.RING))
        assertTrue(phone.controller.peerSupports(Caps.FILE))
        clipBothWays(loop, phone, peer)
    }

    /** A peer speaking a future protocol version is refused with VERSION_MISMATCH, not a hang. */
    @Test
    fun futureProtocolVersionIsRefused() = session(185, 185, qr = false, "future") { loop, phone, _ ->
        loop.runUntil(10_000) { phone.closes.isNotEmpty() }
        assertEquals(CloseReason.VERSION_MISMATCH, phone.closes.first().first)
        assertFalse(phone.controller.isConnected)
    }
}
