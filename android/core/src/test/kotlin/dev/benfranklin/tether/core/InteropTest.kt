package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.features.AnnexB
import dev.benfranklin.tether.core.features.FrameSplitter
import dev.benfranklin.tether.core.features.GestureMapper
import dev.benfranklin.tether.core.proto.CallAction
import dev.benfranklin.tether.core.proto.CallActions4
import dev.benfranklin.tether.core.proto.NotifAccess
import dev.benfranklin.tether.core.proto.Contacts
import dev.benfranklin.tether.core.proto.ContactSearch
import dev.benfranklin.tether.core.proto.ContactNumber
import dev.benfranklin.tether.core.proto.ContactInfo
import dev.benfranklin.tether.core.proto.Caps4
import dev.benfranklin.tether.core.proto.CallLogTypes
import dev.benfranklin.tether.core.proto.CallLogReq
import dev.benfranklin.tether.core.proto.CallLogEntry
import dev.benfranklin.tether.core.proto.CallLog
import dev.benfranklin.tether.core.proto.CallLiveStates
import dev.benfranklin.tether.core.proto.CallLive
import dev.benfranklin.tether.core.proto.CallDirs
import dev.benfranklin.tether.core.proto.CallDialResult
import dev.benfranklin.tether.core.proto.CallDial
import dev.benfranklin.tether.core.proto.CallActions
import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.core.proto.CallStates
import dev.benfranklin.tether.core.proto.Caps3
import dev.benfranklin.tether.core.proto.Caps5
import dev.benfranklin.tether.core.proto.FileKinds5
import dev.benfranklin.tether.core.proto.MsgType
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.proto.MediaCmd
import dev.benfranklin.tether.core.proto.MediaCmds
import dev.benfranklin.tether.core.proto.NowPlaying
import dev.benfranklin.tether.core.proto.NowPlayingArt
import dev.benfranklin.tether.core.proto.ProximityCfg
import dev.benfranklin.tether.core.proto.Rssi
import dev.benfranklin.tether.core.proto.SimInfo
import dev.benfranklin.tether.core.proto.Sims
import dev.benfranklin.tether.core.proto.SmsAttachment
import dev.benfranklin.tether.core.proto.SmsMessage
import dev.benfranklin.tether.core.proto.SmsMessages
import dev.benfranklin.tether.core.proto.SmsNew
import dev.benfranklin.tether.core.proto.SmsSearch
import dev.benfranklin.tether.core.proto.SmsSend
import dev.benfranklin.tether.core.proto.SmsSendResult
import dev.benfranklin.tether.core.proto.SmsThread
import dev.benfranklin.tether.core.proto.SmsThreadReq
import dev.benfranklin.tether.core.proto.SmsThreads
import dev.benfranklin.tether.core.proto.SmsThreadsReq
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
    /** The released 0.3.1 Swift Mac core (tools/interop built at tag v0.3.1), for old-peer compatibility. */
    private val oldPeerPath: String? = System.getenv("TETHER_SWIFT_PEER_OLD")
    /** The released 0.4.3 Swift Mac core (tag v0.4.3): the 0.5 old-peer compatibility target. */
    private val old043PeerPath: String? = System.getenv("TETHER_SWIFT_PEER_OLD043")
    /** Largest fragment the Kotlin phone wrote towards the Swift Mac in this test. */
    private val maxPhoneFragment = java.util.concurrent.atomic.AtomicInteger()

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
        path: String? = peerPath,
        body: (Loop, Side, Peer) -> Unit = { _, _, _ -> },
    ) {
        assumeTrue("Swift peer binary not set (TETHER_SWIFT_PEER / TETHER_SWIFT_PEER_OLD)", path != null)
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
        loop.execute {
            phone.controller.bleConnected(phoneFragment, { f ->
                maxPhoneFragment.accumulateAndGet(f.size, ::maxOf)
                peer.send("F " + Hex.encode(f))
            }, ctx)
        }
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
        assertEquals("Alex’s MacBook Air", (phone.state as LinkState.Connected).peerName)
    }

    @Test fun codePairingSmallMtu() = run(phoneFragment = 20, macFragment = 20, qr = false)

    @Test fun codePairingBigMtu() = run(phoneFragment = 514, macFragment = 182, qr = false)

    @Test fun qrPairingAsymmetric() = run(phoneFragment = 182, macFragment = 509, qr = true)

    // ---------------- revision 2 ----------------

    /** Phone -> Mac messages: Swift decodes, re-encodes and echoes; Kotlin must decode the same value. */
    @Test
    fun phoneToMacFeatureMessagesRoundTrip() = session(185, 185, qr = true) { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps.SCREEN))
        loop.runUntil(5_000) {
            peer.has(
                "CAPS",
                "battery,call,callctl,calllog,capture,clip,clipimg,continue,dial,dnd,file,findmac,fs,hotspot,macstatus,media,notif,notifaccess," +
                    "nowplaying,openurl,otp,photos,proximity,reply,ring,screen,shelf,sms,status,type",
            )
        }
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
            // revision 7 (0.5.3)
            dev.benfranklin.tether.core.proto.MacStatusReq(media = true),
            dev.benfranklin.tether.core.proto.MacMediaCmd("next"),
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
            dev.benfranklin.tether.core.proto.MacStatus(
                battery = 82, charging = true,
                media = dev.benfranklin.tether.core.proto.MacMedia(true, "Nights", "Frank Ocean", "Music", true, 307_000, 102_000), ts = 9,
            ),
            dev.benfranklin.tether.core.proto.MacStatus(ts = 10),
        )
        for (m in fromMac) {
            val before = phone.messages.size
            peer.send("SEND " + Hex.encode(MessageCodec.encode(m)))
            loop.runUntil(10_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        assertFalse(peer.has("DECODE_ERROR"))
    }

    // ---------------- revision 3 ----------------

    /** Phone -> Mac 0.3.0 messages: Swift decodes, re-encodes and echoes; Kotlin decodes the same value. */
    @Test
    fun phoneToMacRevision3RoundTrip() = session(185, 185, qr = true) { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps3.SMS))
        peer.send("ECHO on")
        val msg = SmsMessage(
            "s42", 7, "+919847012345", "Asha", "Running late — 10 min 🚲", 1_700_000_000_000, sub = 2,
            att = listOf(SmsAttachment("p1", "image/jpeg")), read = false,
        )
        val sent: List<Message> = listOf(
            SmsThreads(listOf(SmsThread(7, listOf("+919847012345", "12345"), listOf("Asha", null), "hi", 9, 2, true)), more = true),
            SmsThreads(emptyList(), needs = "sms"),
            SmsMessages(7, listOf(msg, msg.copy(id = "s43", out = true, status = "sent", att = emptyList())), more = true),
            SmsMessages(-1, listOf(msg), search = "late"),
            SmsNew(msg), SmsSendResult("r1", true), SmsSendResult("r2", false, "no_service"),
            Sims(listOf(SimInfo(1, 0, "Jio"), SimInfo(2, 1, "Airtel", "+91 98470 12345"))),
            CallState("c1", CallStates.RINGING, "+919847012345", "Asha", 5), CallState("c1", CallStates.MISSED, ts = 6),
            CallState("c1", CallStates.RINGING, ts = 7, reason = "not_allowed"),
            NowPlaying(
                true, "com.spotify.music", "Spotify", "Song ♪", "Artist", "Album", 200_000, 12_000, true, 1f,
                canSeek = true, canNext = true, canPrev = true, volume = 0.5f, art = "abcd", ts = 9,
            ),
            NowPlaying(false, ts = 10),
            NowPlayingArt("abcd", ByteArray(1500) { (it * 13).toByte() }),
            Rssi(-67, 11),
        )
        for (m in sent) {
            val before = phone.messages.size
            loop.execute { assertTrue(phone.controller.send(m)) }
            loop.runUntil(10_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        val raws = peer.lines.filter { it.startsWith("RAW ") }.map { MessageCodec.decode(Hex.decode(it.substring(4))) }
        assertTrue(raws.containsAll(sent))
    }

    /**
     * 0.3.1: realistic, large conversation lists both ways at the real-device fragment sizes
     * (phone MTU 517 -> clamped to 512, Mac 512), many fragments per record, plus the new
     * error fields. Swift decodes, re-encodes and echoes; Kotlin must get the same values.
     */
    @Test
    fun largeThreadListsAtRealDeviceFragmentSizes() = session(514, 512, qr = true) { loop, phone, peer ->
        peer.send("ECHO on")
        val big = SmsThreads(realisticThreads(200), more = true)
        assertTrue("record should span many fragments", MessageCodec.encode(big).size > 40 * 512)
        val msgs = realisticThreads(60).mapIndexed { i, t ->
            SmsMessage(
                "s$i", 7, t.addrs.first(), t.names.first(), t.snippet, t.date, out = i % 3 == 0,
                status = if (i % 7 == 0) "failed" else "received", sub = if (i % 4 == 0) null else i % 2 + 1,
                att = if (i % 5 == 0) listOf(SmsAttachment("p$i", "image/jpeg")) else emptyList(), read = i % 2 == 0,
            )
        }
        val sent: List<Message> = listOf(
            big,
            SmsThreads(emptyList(), needs = "error:SQLiteException"),
            SmsMessages(7, msgs, more = true),
            SmsMessages(7, emptyList(), error = "error:timeout"),
            SmsMessages(-1, emptyList(), search = "otp", error = "sms"),
        )
        for (m in sent) {
            val before = phone.messages.size
            loop.execute { assertTrue(phone.controller.send(m)) }
            loop.runUntil(20_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        assertTrue("phone wrote ${maxPhoneFragment.get()}-byte fragments", maxPhoneFragment.get() <= 512)
        assertFalse(peer.has("CLOSED"))
    }

    /** Mac -> phone 0.3.0 messages: encoded by Kotlin, decoded + re-sent by the Swift controller. */
    @Test
    fun macToPhoneRevision3() = session(244, 244, qr = false) { loop, phone, peer ->
        val fromMac: List<Message> = listOf(
            SmsThreadsReq(40), SmsThreadsReq(40, 1_699_000_000_000, "asha"), SmsThreadReq(7, 40, 1_699_000_000_000),
            SmsSend("r1", listOf("+919847012345"), "On my way 🚲", 7, 2), SmsSearch("late", 25),
            CallAction("c1", CallActions.DECLINE), CallAction("c1", CallActions.SILENCE),
            MediaCmd(MediaCmds.TOGGLE), MediaCmd(MediaCmds.SEEK, pos = 61_000), MediaCmd(MediaCmds.VOLUME, vol = 0.25f),
            ProximityCfg(true, 2000), ProximityCfg(false),
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
        assertFalse(phone.controller.peerSupports(Caps3.SMS))
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

    // ---------------- revision 4 (0.4.0) ----------------

    private fun callLogEntries(n: Int) = (0 until n).map { i ->
        CallLogEntry(
            1000L - i, if (i % 9 == 0) "" else "+91 98470 ${10000 + i}", if (i % 3 == 0) null else "Contact ${i} — ✓",
            listOf(CallLogTypes.IN, CallLogTypes.OUT, CallLogTypes.MISSED, CallLogTypes.REJECTED)[i % 4],
            1_700_000_000_000 - i * 60_000L, (i * 7 % 600).toLong(), if (i % 2 == 0) 1 else null, isNew = i % 5 == 0,
        )
    }

    /** Phone -> Mac 0.4 messages at the real-device fragment sizes: Swift decodes, re-encodes, echoes. */
    @Test
    fun phoneToMacRevision4RoundTrip() = session(514, 512, qr = true) { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps4.DIAL) && phone.controller.peerSupports(Caps4.CALL_CONTROL))
        peer.send("ECHO on")
        val sent: List<Message> = listOf(
            CallLive("c1", CallDirs.IN, CallLiveStates.RINGING, "+919847012345", "Asha", canAnswer = true, canEnd = true, ts = 1),
            CallLive(
                "c1", CallDirs.IN, CallLiveStates.ACTIVE, "+919847012345", "Asha", since = 2, speaker = true,
                canEnd = true, canSpeaker = true, canMute = true, sub = 2, ts = 3,
            ),
            CallLive("c2", CallDirs.OUT, CallLiveStates.ENDED, ts = 4, reason = "unsupported"),
            CallDialResult("r1", true, via = "telecom"), CallDialResult("r2", false, "needs_permission"),
            CallLog(callLogEntries(100), more = true), CallLog(emptyList(), needs = "calllog", before = 5),
            Contacts("ra", listOf(ContactInfo("Rahul", listOf(ContactNumber("+91 99955 51234", "Mobile"), ContactNumber("0484"))))),
            Contacts("x", emptyList(), needs = "contacts"),
            NotifAccess(true, null, 36, "OnePlus", 6), NotifAccess(false, true, 36, "OnePlus", 7),
        )
        assertTrue("call log spans many fragments", MessageCodec.encode(sent[5]).size > 20 * 512)
        for (m in sent) {
            val before = phone.messages.size
            loop.execute { assertTrue(phone.controller.send(m)) }
            loop.runUntil(20_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        val raws = peer.lines.filter { it.startsWith("RAW ") }.map { MessageCodec.decode(Hex.decode(it.substring(4))) }
        assertTrue(raws.containsAll(sent.filter { MessageCodec.encode(it).size < 4096 }))
        assertFalse(peer.has("CLOSED"))
        assertTrue(maxPhoneFragment.get() <= 512)
    }

    /** Mac -> phone 0.4 messages: encoded by Kotlin, decoded + re-sent by the Swift controller. */
    @Test
    fun macToPhoneRevision4() = session(244, 244, qr = false) { loop, phone, peer ->
        val fromMac: List<Message> = listOf(
            CallDial("r1", "+919847012345"), CallDial("r2", "*121#", 2), CallLogReq(60), CallLogReq(60, 1_699_000_000_000),
            ContactSearch("ra", 12),
            CallAction("c1", CallActions4.ANSWER, speaker = true), CallAction("c1", CallActions4.END),
            CallAction("c1", CallActions4.SPEAKER, on = true), CallAction("c1", CallActions4.MUTE, on = false),
            CallAction("c1", CallActions.DECLINE),
        )
        for (m in fromMac) {
            val before = phone.messages.size
            peer.send("SEND " + Hex.encode(MessageCodec.encode(m)))
            loop.runUntil(10_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        assertFalse(peer.has("DECODE_ERROR"))
    }

    /** A 0.3.x Mac (revision 3 caps): the phone sees calling is unavailable; 0.3 call alerts still flow. */
    @Test
    fun v03MacDoesNotGetRevision4() = session(185, 185, qr = true, "v03") { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps3.CALL))
        for (c in Caps4.ALL) assertFalse(c, phone.controller.peerSupports(c))
        peer.send("ECHO on")
        val ring = CallState("c1", CallStates.RINGING, "+919847012345", "Asha", 5)
        loop.execute { assertTrue(phone.controller.send(ring)) }
        loop.runUntil(10_000) { phone.messages.lastOrNull() == ring }
        clipBothWays(loop, phone, peer)
    }

    /**
     * The real released 0.3.1 Mac core (built from tag v0.3.1): even if a 0.4 phone sent it every
     * revision-4 message, it ignores them (unknown types) and the link keeps working.
     */
    @Test
    fun released031MacIgnoresRevision4() = session(185, 185, qr = true, path = oldPeerPath) { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps3.SMS))
        for (c in Caps4.ALL) assertFalse(c, phone.controller.peerSupports(c))
        val rev4: List<Message> = listOf(
            CallLive("c1", CallDirs.OUT, CallLiveStates.DIALING, ts = 1), CallDialResult("r", true), CallLog(callLogEntries(30)),
            Contacts("a", emptyList()), NotifAccess(true, null, 36, ts = 1),
        )
        for (m in rev4) loop.execute { assertTrue(phone.controller.send(m)) }
        loop.runUntil(10_000) { peer.lines.count { it.startsWith("MSG unknown") } >= rev4.size }
        // 0.3 traffic and the link itself are unaffected.
        peer.send("SEND " + Hex.encode(MessageCodec.encode(CallAction("c9", CallActions.DECLINE))))
        loop.runUntil(10_000) { phone.messages.lastOrNull() == CallAction("c9", CallActions.DECLINE) }
        clipBothWays(loop, phone, peer)
        assertFalse(peer.has("CLOSED"))
        assertFalse(peer.has("DECODE_ERROR"))
    }

    // ---------------- revision 5 (0.5.0) ----------------

    /** Messages the phone sends in revision 5 (the rest go Mac -> phone). */
    private val phoneToMac5 = setOf(
        MsgType.OTP, MsgType.FS_LIST, MsgType.FS_GET_RESULT, MsgType.THUMB, MsgType.PHOTOS, MsgType.CONTINUE,
        MsgType.OPEN_RESULT, MsgType.TYPE_RESULT, MsgType.DND_STATE, MsgType.PHONE_STATUS,
    )

    /** Phone -> Mac 0.5 messages at real-device fragment sizes: Swift decodes, re-encodes, echoes them back. */
    @Test
    fun phoneToMacRevision5RoundTrip() = session(514, 512, qr = true) { loop, phone, peer ->
        for (c in Caps5.ALL) assertTrue(c, phone.controller.peerSupports(c))
        peer.send("ECHO on")
        val sent = Rev5Samples.all().filter { it.type in phoneToMac5 }
        assertTrue("a 150-entry listing spans many fragments", sent.any { MessageCodec.encode(it).size > 10 * 512 })
        for (m in sent) {
            val before = phone.messages.size
            loop.execute { assertTrue(phone.controller.send(m)) }
            loop.runUntil(20_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        val raws = peer.lines.filter { it.startsWith("RAW ") }.map { MessageCodec.decode(Hex.decode(it.substring(4))) }
        assertTrue(raws.containsAll(sent.filter { MessageCodec.encode(it).size < 4096 }))
        assertFalse(peer.has("CLOSED"))
        assertTrue(maxPhoneFragment.get() <= 512)
    }

    /** Mac -> phone 0.5 messages: encoded by Kotlin, decoded and re-sent by the Swift controller. */
    @Test
    fun macToPhoneRevision5() = session(244, 244, qr = false) { loop, phone, peer ->
        val fromMac = Rev5Samples.all().filter { it.type !in phoneToMac5 }
        assertTrue(fromMac.size >= 8)
        for (m in fromMac) {
            val before = phone.messages.size
            peer.send("SEND " + Hex.encode(MessageCodec.encode(m)))
            loop.runUntil(10_000) { phone.messages.size > before }
            assertEquals(m, phone.messages.last())
        }
        assertFalse(peer.has("DECODE_ERROR"))
    }

    /** Clipboard images both ways as transfers with kind "clip" (BLE), verified by SHA-256 on each side. */
    @Test
    fun clipboardImagesBothWays() = session(244, 244, qr = true) { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps5.CLIP_IMAGE))
        val png = Random(5).nextBytes(48_000)
        val rec = phone.files.stage("clip.png", png, loop.now).copy(kind = FileKinds5.CLIP, mime = "image/png")
        loop.execute { phone.controller.sendFile(rec) }
        loop.runUntil(30_000) { peer.has("FILE ${rec.id}") }
        assertTrue(peer.has("FILE ${rec.id} clip ${rec.sha256} DONE via=BLE"))
        val jpeg = Random(6).nextBytes(30_000)
        peer.send("SENDFILE clip.jpg clip " + Hex.encode(jpeg))
        loop.runUntil(30_000) {
            phone.transfers.values.any { it.direction == Direction.IN && it.status == TransferStatus.DONE && it.kind == FileKinds5.CLIP }
        }
        val got = phone.transfers.values.first { it.direction == Direction.IN && it.kind == FileKinds5.CLIP }
        assertTrue(phone.files.finished[got.id]!!.contentEquals(jpeg))
        clipBothWays(loop, phone, peer)
    }

    /** A 0.4.x Mac (revision 4 caps): no 0.5 features are announced, 0.4 calling still works. */
    @Test
    fun v04MacDoesNotGetRevision5() = session(185, 185, qr = true, "v04") { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps4.DIAL))
        for (c in Caps5.ALL) assertFalse(c, phone.controller.peerSupports(c))
        peer.send("ECHO on")
        val live = CallLive("c1", CallDirs.IN, CallLiveStates.RINGING, "+919847012345", ts = 1)
        loop.execute { assertTrue(phone.controller.send(live)) }
        loop.runUntil(10_000) { phone.messages.lastOrNull() == live }
        clipBothWays(loop, phone, peer)
    }

    /**
     * The real released 0.4.3 Mac core (built from tag v0.4.3): every revision-5 message is
     * ignored as an unknown type, and the link, clipboard and 0.4 traffic keep working.
     */
    @Test
    fun released043MacIgnoresRevision5() = session(185, 185, qr = true, path = old043PeerPath) { loop, phone, peer ->
        assertTrue(phone.controller.peerSupports(Caps4.CALL_LOG))
        for (c in Caps5.ALL) assertFalse(c, phone.controller.peerSupports(c))
        val rev5: List<Message> = Rev5Samples.all().filter { it.type in phoneToMac5 }
        for (m in rev5) loop.execute { assertTrue(phone.controller.send(m)) }
        loop.runUntil(20_000) { peer.lines.count { it.startsWith("MSG unknown") } >= rev5.size }
        peer.send("SEND " + Hex.encode(MessageCodec.encode(CallLogReq(10))))
        loop.runUntil(10_000) { phone.messages.lastOrNull() == CallLogReq(10) }
        clipBothWays(loop, phone, peer)
        assertFalse(peer.has("CLOSED"))
        assertFalse(peer.has("DECODE_ERROR"))
    }
}
