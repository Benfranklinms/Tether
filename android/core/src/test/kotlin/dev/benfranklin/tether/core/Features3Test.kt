package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.CallTracker
import dev.benfranklin.tether.core.features.NowPlayingThrottle
import dev.benfranklin.tether.core.features.PhoneNumbers
import dev.benfranklin.tether.core.proto.CallAction
import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.core.proto.CallStates
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Caps3
import dev.benfranklin.tether.core.proto.MediaCmd
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.MsgType
import dev.benfranklin.tether.core.proto.NowPlaying
import dev.benfranklin.tether.core.proto.NowPlayingArt
import dev.benfranklin.tether.core.proto.ProtocolException
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Features3CodecTest {
    private val msg = SmsMessage(
        "s42", 7, "+919847012345", "Asha", "Running late — 10 min 🚲", 1_700_000_000_000, out = false,
        status = "received", sub = 2, att = listOf(SmsAttachment("p1", "image/jpeg", 640, 480)), read = false,
    )

    @Test
    fun revision3TypesRoundTrip() {
        val all = listOf(
            SmsThreadsReq(), SmsThreadsReq(20, 1_699_000_000_000, "asha"),
            SmsThreads(listOf(SmsThread(7, listOf("+919847012345"), listOf("Asha"), "hi", 1, 2, true)), more = true),
            SmsThreads(emptyList(), needs = "sms"),
            SmsThreadReq(7), SmsThreadReq(7, 30, 1_699_000_000_000),
            SmsMessages(7, listOf(msg, msg.copy(id = "m3", out = true, status = "sent", att = emptyList())), more = true),
            SmsMessages(-1, listOf(msg), search = "late"),
            SmsSend("r1", listOf("+919847012345", "12345"), "On my way", 7, 1), SmsSend("r2", listOf("x"), "b"),
            SmsSendResult("r1", true), SmsSendResult("r2", false, "no_service"),
            SmsNew(msg), SmsSearch("late", 25),
            Sims(listOf(SimInfo(1, 0, "Jio", "+91 98470 12345"), SimInfo(2, 1, "Airtel"))),
            CallState("c1", CallStates.RINGING, "+919847012345", "Asha", 5),
            CallState("c1", CallStates.MISSED, ts = 6), CallState("c1", CallStates.ENDED, ts = 7, reason = "not_allowed"),
            CallAction("c1", "decline"), CallAction("c1", "silence"),
            NowPlaying(
                true, "com.spotify.music", "Spotify", "Song", "Artist", "Album", 200_000, 12_000, true, 1f,
                canSeek = true, canNext = true, canPrev = false, volume = 0.4f, art = "abcd", ts = 9,
            ),
            NowPlaying(false, ts = 1),
            MediaCmd("toggle"), MediaCmd("seek", pos = 61_000), MediaCmd("volume", vol = 0.75f),
            NowPlayingArt("abcd", ByteArray(4000) { (it * 7).toByte() }),
            Rssi(-63, 10), ProximityCfg(true, 1500), ProximityCfg(false),
        )
        for (m in all) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test
    fun typesAreTheDocumentedOnes() {
        assertEquals(0x90, SmsThreadsReq().type)
        assertEquals(0x98, Sims(emptyList()).type)
        assertEquals(0xA0, CallState("c", "ringing", ts = 0).type)
        assertEquals(0xB2, NowPlayingArt("h", ByteArray(0)).type)
        assertEquals(0xD0, Rssi(-50, 0).type)
        assertEquals(MsgType.PROXIMITY_CFG, ProximityCfg(true).type)
    }

    @Test
    fun capsAnnounceRevision3() {
        assertTrue(Caps.CURRENT.containsAll(Caps3.ALL))
        assertEquals(listOf("sms", "call", "nowplaying", "proximity"), Caps3.ALL)
    }

    @Test
    fun minimalHeadersFromAnotherImplementationDecode() {
        // Optional fields may be omitted by the Swift encoder (nil) — defaults apply.
        val raw = """{"msg":{"id":"s1","thread":3,"addr":"123","date":5}}"""
        val m = MessageCodec.decode(frame(MsgType.SMS_NEW, raw)) as SmsNew
        assertEquals("", m.msg.body)
        assertFalse(m.msg.out)
        assertTrue(m.msg.read)
        val np = MessageCodec.decode(frame(MsgType.NOW_PLAYING, """{"active":false,"ts":1,"extra":true}""")) as NowPlaying
        assertNull(np.art)
    }

    @Test
    fun oversizedArtworkIsRefused() {
        val big = MessageCodec.encode(NowPlayingArt("h", ByteArray(MessageCodec.MAX_ART_BYTES + 1)))
        try {
            MessageCodec.decode(big)
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
        }
    }

    private fun frame(type: Int, header: String): ByteArray {
        val h = header.toByteArray()
        return byteArrayOf(type.toByte(), 0, 0, (h.size shr 8).toByte(), h.size.toByte()) + h
    }
}

class CallTrackerTest {
    private var n = 0
    private val t = CallTracker { "c${++n}" }

    @Test
    fun ringingThenIdleIsMissed() {
        val r = t.onState(CallTracker.RINGING, "+91 98470 12345", "Asha", 1)!!
        assertEquals(CallStates.RINGING, r.state)
        assertEquals("Asha", r.name)
        assertEquals("c1", t.ringingId)
        // Duplicate RINGING (number arrives late) keeps the id.
        assertEquals("c1", t.onState(CallTracker.RINGING, null, null, 2)!!.id)
        val end = t.onState(CallTracker.IDLE, null, null, 3)!!
        assertEquals(CallStates.MISSED, end.state)
        assertEquals("+91 98470 12345", end.number)
        assertNull(t.ringingId)
    }

    @Test
    fun answeredCallEndsNormally() {
        t.onState(CallTracker.RINGING, "1", null, 1)
        assertEquals(CallStates.OFFHOOK, t.onState(CallTracker.OFFHOOK, null, null, 2)!!.state)
        assertNull(t.ringingId)
        assertNull(t.onState(CallTracker.OFFHOOK, null, null, 3))
        assertEquals(CallStates.ENDED, t.onState(CallTracker.IDLE, null, null, 4)!!.state)
    }

    @Test
    fun declinedFromMacIsNotMissedAndOutgoingIsSilent() {
        t.onState(CallTracker.RINGING, "1", null, 1)
        t.declinedFromMac()
        assertEquals(CallStates.ENDED, t.onState(CallTracker.IDLE, null, null, 2)!!.state)
        // Outgoing call: OFFHOOK without RINGING.
        assertNull(t.onState(CallTracker.OFFHOOK, null, null, 3))
        assertNull(t.onState(CallTracker.IDLE, null, null, 4))
        // Next incoming call gets a fresh id and is missed again.
        assertEquals("c2", t.onState(CallTracker.RINGING, "2", null, 5)!!.id)
        assertEquals(CallStates.MISSED, t.onState(CallTracker.IDLE, null, null, 6)!!.state)
    }
}

class NowPlayingThrottleTest {
    @Test
    fun changesGoOutButNotFasterThanTheGap() {
        val th = NowPlayingThrottle(minGapMs = 300, resyncMs = 15_000)
        assertTrue(th.offer("a", 0))
        assertFalse(th.offer("a", 100))
        assertFalse(th.offer("b", 100)) // too soon
        assertTrue(th.offer("b", 400))
        assertFalse(th.offer("b", 10_000))
        assertTrue(th.offer("b", 15_400)) // periodic resync
        th.reset()
        assertTrue(th.offer("b", 15_800))
    }
}

class PhoneNumbersTest {
    @Test
    fun matchesTheSameSubscriber() {
        assertTrue(PhoneNumbers.same("+91 98470-12345", "09847012345"))
        assertTrue(PhoneNumbers.same("9847012345", "+919847012345"))
        assertFalse(PhoneNumbers.same("9847012345", "9847012346"))
        assertTrue(PhoneNumbers.same("12345", "12345"))
        assertFalse(PhoneNumbers.same("12345", "112345"))
        assertTrue(PhoneNumbers.same("JM-HDFCBK", "jm-hdfcbk"))
        assertEquals("919847012345", PhoneNumbers.digits("+91 98470 12345"))
    }
}
