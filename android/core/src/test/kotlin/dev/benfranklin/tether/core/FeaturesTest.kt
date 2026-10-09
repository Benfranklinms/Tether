package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.AdaptiveBitrate
import dev.benfranklin.tether.core.features.AnnexB
import dev.benfranklin.tether.core.features.BatteryThrottle
import dev.benfranklin.tether.core.features.FrameAssembler
import dev.benfranklin.tether.core.features.FrameSplitter
import dev.benfranklin.tether.core.features.GestureMapper
import dev.benfranklin.tether.core.features.MediaLedger
import dev.benfranklin.tether.core.features.MediaLedgerState
import dev.benfranklin.tether.core.features.RingTimer
import dev.benfranklin.tether.core.proto.Battery
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.FileOffer
import dev.benfranklin.tether.core.proto.Hello
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.Notif
import dev.benfranklin.tether.core.proto.NotifAction
import dev.benfranklin.tether.core.proto.NotifActionInfo
import dev.benfranklin.tether.core.proto.NotifActionResult
import dev.benfranklin.tether.core.proto.ProtocolException
import dev.benfranklin.tether.core.proto.Ring
import dev.benfranklin.tether.core.proto.RingState
import dev.benfranklin.tether.core.proto.RingStop
import dev.benfranklin.tether.core.proto.ScreenInput
import dev.benfranklin.tether.core.proto.ScreenStart
import dev.benfranklin.tether.core.proto.ScreenState
import dev.benfranklin.tether.core.proto.ScreenStop
import dev.benfranklin.tether.core.proto.UnknownMessage
import dev.benfranklin.tether.core.proto.VideoFeedback
import dev.benfranklin.tether.core.proto.VideoFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesCodecTest {
    @Test
    fun revision2TypesRoundTrip() {
        val all = listOf(
            NotifAction("k", "reply", "On my way 👍"), NotifAction("k", "a1"), NotifActionResult("k", "reply", true),
            NotifActionResult("k", "a1", false, "gone"),
            Battery(81, true, "usb", "wifi", null, 3, 1700),
            Battery(5, false, ts = 9),
            Ring(), Ring(30), RingStop(), RingState(true), RingState(false, "timeout"),
            ScreenStart(), ScreenStart(720, 30, 2_000_000), ScreenState("live", 720, 1600, 90, true),
            ScreenState("needs_wifi", reason = "ble"), ScreenStop("phone"),
            VideoFrame("screen", 7, 33_000, key = true, config = false, part = 0, parts = 1, data = ByteArray(5000) { it.toByte() }),
            ScreenInput("tap", 0.25, 0.75, rot = 0), ScreenInput("swipe", 0.1, 0.2, 0.3, 0.4, 250),
            ScreenInput("text", text = "héllo"), ScreenInput("back"),
            VideoFeedback(kbps = 4000, lagMs = 40, dropped = 2, keyframe = true),
            Notif("k", "com.whatsapp", "WhatsApp", "A", "B", 1, listOf(NotifActionInfo("reply", "Reply", true), NotifActionInfo("1", "Mark as read"))),
            FileOffer("i", "Screenshot.png", 10, "c".repeat(64), "image/png", "screenshot"),
        )
        for (m in all) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test
    fun v1PeerFieldsDefault() {
        // A 0.1.x NOTIF / FILE_OFFER header has no actions / kind.
        val h = """{"key":"k","pkg":"p","app":"A","title":"t","text":"x","ts":1}""".toByteArray()
        val n = MessageCodec.decode(byteArrayOf(0x40, 0, 0, 0, h.size.toByte()) + h) as Notif
        assertTrue(n.actions.isEmpty())
        val o = """{"id":"i","name":"n","size":1,"sha256":"${"a".repeat(64)}","mime":"x"}""".toByteArray()
        val f = MessageCodec.decode(byteArrayOf(0x50, 0, 0, 0, o.size.toByte()) + o) as FileOffer
        assertNull(f.kind)
    }

    @Test
    fun helloCaps() {
        val old = MessageCodec.decodeHello("""{"v":1,"name":"Mac","platform":"macos","app":"0.1.1"}""".toByteArray())
        assertEquals(Caps.V1, old.caps)
        val new = Hello(name = "P", platform = "android", app = "0.2.0", caps = Caps.CURRENT)
        assertTrue(MessageCodec.decodeHello(MessageCodec.encodeHello(new)).caps.containsAll(listOf("reply", "screen", "battery")))
    }

    @Test
    fun unknownFutureTypeIgnored() {
        val h = "{}".toByteArray()
        assertEquals(UnknownMessage(0xEE), MessageCodec.decode(byteArrayOf(0xEE.toByte(), 0, 0, 0, 2) + h))
    }

    @Test(expected = ProtocolException::class)
    fun badFramePartRejected() {
        val h = """{"src":"screen","seq":1,"pts":0,"part":3,"parts":2}""".toByteArray()
        MessageCodec.decode(byteArrayOf(0x83.toByte(), 0, 0, 0, h.size.toByte()) + h)
    }

    @Test
    fun frameBodyIsRaw() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val enc = MessageCodec.encode(VideoFrame("screen", 1, 0, true, false, 0, 1, data))
        assertArrayEquals(data, enc.copyOfRange(enc.size - data.size, enc.size))
    }
}

class BatteryThrottleTest {
    private fun b(level: Int, charging: Boolean = false, net: String? = "wifi") = Battery(level, charging, net = net, ts = 0)

    @Test
    fun firstChargingAndThresholdsImmediate() {
        val t = BatteryThrottle(minIntervalMs = 60_000, heartbeatMs = 600_000)
        assertTrue(t.offer(b(50), 0))
        assertFalse(t.offer(b(50), 1_000)) // unchanged
        assertFalse(t.offer(b(49), 2_000)) // level change, rate limited
        assertTrue(t.offer(b(49, charging = true), 3_000)) // plug in: immediate
        assertTrue(t.offer(b(21, charging = false), 4_000)) // unplug: immediate
        assertTrue(t.offer(b(20), 5_000)) // reached 20 (low-battery alert level): immediate
        assertFalse(t.offer(b(19), 6_000))
        assertTrue(t.offer(b(10), 7_000)) // reached 10
    }

    @Test
    fun rateLimitedChangesAndHeartbeat() {
        val t = BatteryThrottle(minIntervalMs = 60_000, heartbeatMs = 600_000)
        t.offer(b(80), 0)
        assertFalse(t.offer(b(79), 30_000))
        assertTrue(t.offer(b(79), 60_000))
        assertFalse(t.offer(b(79, net = "cellular"), 70_000))
        assertTrue(t.offer(b(79, net = "cellular"), 121_000))
        assertFalse(t.offer(b(79, net = "cellular"), 400_000))
        assertTrue(t.offer(b(79, net = "cellular"), 721_000)) // heartbeat
    }

    @Test
    fun resetResends() {
        val t = BatteryThrottle()
        t.offer(b(60), 0)
        assertFalse(t.offer(b(60), 1))
        t.reset()
        assertTrue(t.offer(b(60), 2))
    }
}

class MediaLedgerTest {
    private val sha1 = "a".repeat(64)
    private val sha2 = "b".repeat(64)

    @Test
    fun skipsOldGalleryAndDuplicates() {
        val l = MediaLedger()
        assertFalse(l.started)
        l.startAt(1_000)
        assertTrue(l.started)
        assertEquals(MediaLedger.Decision.SKIP_OLD, l.decide(10, 999, sha1))
        assertEquals(MediaLedger.Decision.SKIP_OLD, l.decide(11, 1_000, sha1)) // same second as start counts as old
        assertEquals(MediaLedger.Decision.SEND, l.decide(12, 1_001, sha1))
        assertEquals(MediaLedger.Decision.SKIP_OLD, l.decide(12, 1_001, sha1)) // re-scan
        assertEquals(MediaLedger.Decision.SEND, l.decide(13, 1_001, sha2)) // same second, newer id
        assertEquals(MediaLedger.Decision.SKIP_DUPLICATE, l.decide(20, 1_005, sha1.uppercase())) // same bytes, new id
    }

    @Test
    fun survivesPersistence() {
        val l = MediaLedger()
        l.startAt(100)
        l.decide(5, 200, sha1)
        val restored = MediaLedger(MediaLedgerState(l.state.watermark, l.state.lastId, l.state.sent))
        assertEquals(MediaLedger.Decision.SKIP_OLD, restored.decide(5, 200, sha1))
        assertTrue(restored.alreadySent(sha1))
        assertEquals(MediaLedger.Decision.SEND, restored.decide(6, 200, sha2))
    }

    @Test
    fun hashListIsCapped() {
        val l = MediaLedger(maxHashes = 3)
        l.startAt(0)
        for (i in 1..5) l.markSent(i.toString().repeat(64))
        assertEquals(3, l.state.sent.size)
        assertFalse(l.alreadySent("1".repeat(64)))
        assertTrue(l.alreadySent("5".repeat(64)))
    }
}

class GestureMapperTest {
    private fun map(x: Double, y: Double, frameRot: Int, dispRot: Int, w: Int, h: Int) =
        GestureMapper.toDisplay(x, y, frameRot, dispRot, w, h)

    @Test
    fun sameRotationIsScale() {
        val p = map(0.5, 0.25, 0, 0, 1080, 2400)
        assertEquals(540f, p.x, 0.01f)
        assertEquals(600f, p.y, 0.01f)
        val l = map(0.5, 0.25, 90, 90, 2400, 1080)
        assertEquals(1200f, l.x, 0.01f)
        assertEquals(270f, l.y, 0.01f)
    }

    @Test
    fun clampsToScreen() {
        val p = map(1.0, 1.0, 0, 0, 1080, 2400)
        assertEquals(1079f, p.x, 0.01f)
        assertEquals(2399f, p.y, 0.01f)
        val q = map(-3.0, 9.0, 0, 0, 100, 100)
        assertEquals(0f, q.x, 0.01f)
        assertEquals(99f, q.y, 0.01f)
    }

    @Test
    fun rotationInFlightLandsOnSamePhysicalSpot() {
        // Portrait frame, tap near the natural top-right corner; the phone has since turned to
        // ROTATION_90 (landscape, top edge on the left): that corner is now the top-left.
        val p = map(0.9, 0.1, 0, 90, 2400, 1080)
        assertEquals(0.1 * 2400, p.x.toDouble(), 0.5)
        assertEquals((1 - 0.9) * 1080, p.y.toDouble(), 0.5)
        // ...and at ROTATION_270 it is the bottom-right.
        val q = map(0.9, 0.1, 0, 270, 2400, 1080)
        assertEquals(0.9 * 2400, q.x.toDouble(), 0.5)
        assertEquals(0.9 * 1080, q.y.toDouble(), 0.5)
        // 180: centre stays put, corners swap.
        val c = map(0.5, 0.5, 0, 180, 1080, 2400)
        assertEquals(540f, c.x, 0.5f)
        val r = map(0.0, 0.0, 180, 0, 1080, 2400)
        assertEquals(1079f, r.x, 0.5f)
    }

    @Test
    fun forwardAndInverseAreConsistent() {
        for (rot in listOf(0, 90, 180, 270, -90, 450)) {
            for ((x, y) in listOf(0.1 to 0.2, 0.7 to 0.3, 0.5 to 0.5)) {
                val n = ((rot % 360) + 360) % 360
                val (u, v) = GestureMapper.toNatural(x, y, n)
                val (x2, y2) = GestureMapper.fromNatural(u, v, n)
                assertEquals(x, x2, 1e-9)
                assertEquals(y, y2, 1e-9)
            }
        }
    }
}

class VideoFramingTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0, 0, 3, 1)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte(), 0x22)

    @Test
    fun parsesNalTypes() {
        val au = sps + pps + idr
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(au))
        assertTrue(AnnexB.hasConfig(au))
        assertTrue(AnnexB.isKeyframe(au))
        assertFalse(AnnexB.isKeyframe(pFrame))
        assertFalse(AnnexB.hasConfig(pFrame))
    }

    @Test
    fun avccLengthPrefixes() {
        val avcc = AnnexB.toAvcc(sps + idr)
        // 4-byte length 4 + sps payload, then 4-byte length 7 + idr payload (emulation byte kept)
        assertArrayEquals(byteArrayOf(0, 0, 0, 4, 0x67, 0x42, 0x00, 0x1f, 0, 0, 0, 7, 0x65, 0x88.toByte(), 0x84.toByte(), 0, 0, 3, 1), avcc)
    }

    @Test
    fun splitAndReassemble() {
        val data = ByteArray(2_500_123) { (it * 31).toByte() }
        val parts = FrameSplitter.split("screen", 42, 1000, true, false, data)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.data.size <= VideoFrame.MAX_PART })
        val a = FrameAssembler()
        assertNull(a.accept(parts[0]))
        assertNull(a.accept(parts[1]))
        val f = assertNotNullFrame(a.accept(parts[2]))
        assertArrayEquals(data, f.data)
        assertEquals(42, f.seq)
        assertTrue(f.key)
        // round trip through the codec too
        val dec = parts.map { MessageCodec.decode(MessageCodec.encode(it)) as VideoFrame }
        val b = FrameAssembler()
        val out = dec.mapNotNull { b.accept(it) }.single()
        assertArrayEquals(data, out.data)
    }

    @Test
    fun incompleteFrameIsDroppedForNewer() {
        val a = FrameAssembler()
        val big = FrameSplitter.split("screen", 1, 0, true, false, ByteArray(30), maxPart = 10)
        assertNull(a.accept(big[0]))
        // part 1 lost; frame 2 (single part) arrives
        val f2 = a.accept(FrameSplitter.split("screen", 2, 1, false, false, ByteArray(5)).single())
        assertNotNull(f2)
        assertEquals(1, a.dropped)
        // a stray tail part of frame 1 is ignored
        assertNull(a.accept(big[2]))
        assertEquals(2, a.dropped)
    }

    @Test
    fun outOfOrderPartAbandons() {
        val a = FrameAssembler()
        val p = FrameSplitter.split("screen", 9, 0, false, false, ByteArray(30), maxPart = 10)
        assertNull(a.accept(p[0]))
        assertNull(a.accept(p[2]))
        assertEquals(1, a.dropped)
    }

    private fun assertNotNullFrame(f: FrameAssembler.Frame?): FrameAssembler.Frame {
        assertNotNull(f)
        return f!!
    }
}

class AdaptiveBitrateTest {
    @Test
    fun backsOffAndRecovers() {
        val abr = AdaptiveBitrate(minBps = 1_000_000, maxBps = 8_000_000, startBps = 4_000_000)
        assertEquals(2_800_000, abr.update(queuedBytes = 400_000, lagMs = 0, dropped = 0, now = 0))
        assertNull(abr.update(400_000, 0, 0, 500)) // at most once per second
        assertEquals(1_960_000, abr.update(0, 300, 0, 1_000)) // lag
        assertEquals(1_372_000, abr.update(0, 0, 3, 2_000)) // drops
        assertEquals(1_000_000, abr.update(500_000, 0, 0, 3_000)) // floor
        assertNull(abr.update(500_000, 0, 0, 4_000))
        // calm for 3 s, then +15% every 2 s
        assertNull(abr.update(0, 10, 0, 5_000))
        assertNull(abr.update(0, 10, 0, 7_000))
        assertEquals(1_150_000, abr.update(0, 10, 0, 8_000))
        assertNull(abr.update(0, 10, 0, 9_000))
        assertEquals(1_322_500, abr.update(0, 10, 0, 10_000))
        // middling (neither calm nor congested) resets the calm timer
        assertNull(abr.update(100_000, 0, 0, 13_000))
        assertNull(abr.update(0, 0, 0, 14_000))
    }

    @Test
    fun dropsOnlyNonKeyFramesWhenBackedUp() {
        val abr = AdaptiveBitrate()
        assertTrue(abr.shouldDrop(1_000_000, keyframe = false))
        assertFalse(abr.shouldDrop(1_000_000, keyframe = true))
        assertFalse(abr.shouldDrop(10_000, keyframe = false))
    }
}

class RingTimerTest {
    @Test
    fun capsAndExpires() {
        val t = RingTimer(maxMs = 60_000)
        assertFalse(t.ringing)
        val limit = t.start(1_000, timeoutS = 600)
        assertEquals(60_000, limit)
        assertTrue(t.ringing)
        assertFalse(t.expired(30_000, limit))
        assertTrue(t.expired(61_000, limit))
        t.stop()
        assertFalse(t.expired(100_000, limit))
        assertEquals(5_000, t.start(0, 0))
    }
}
