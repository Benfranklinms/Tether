package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.CallControls
import dev.benfranklin.tether.core.features.CallLogTypeMap
import dev.benfranklin.tether.core.features.CallTracker
import dev.benfranklin.tether.core.features.Dialable
import dev.benfranklin.tether.core.features.LiveCallTracker
import dev.benfranklin.tether.core.features.SensitiveNotifications
import dev.benfranklin.tether.core.proto.CallAction
import dev.benfranklin.tether.core.proto.CallActions4
import dev.benfranklin.tether.core.proto.CallDial
import dev.benfranklin.tether.core.proto.CallDialResult
import dev.benfranklin.tether.core.proto.CallDirs
import dev.benfranklin.tether.core.proto.CallLive
import dev.benfranklin.tether.core.proto.CallLiveStates
import dev.benfranklin.tether.core.proto.CallLog
import dev.benfranklin.tether.core.proto.CallLogEntry
import dev.benfranklin.tether.core.proto.CallLogReq
import dev.benfranklin.tether.core.proto.CallLogTypes
import dev.benfranklin.tether.core.proto.CallStates
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Caps4
import dev.benfranklin.tether.core.proto.ContactInfo
import dev.benfranklin.tether.core.proto.ContactNumber
import dev.benfranklin.tether.core.proto.ContactSearch
import dev.benfranklin.tether.core.proto.Contacts
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.MsgType
import dev.benfranklin.tether.core.proto.NotifAccess
import dev.benfranklin.tether.core.proto.UnknownMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class Features4CodecTest {
    private val entry = CallLogEntry(41, "+919847012345", "Asha", CallLogTypes.MISSED, 1_700_000_000_000, 0, 2, isNew = true)

    @Test
    fun revision4TypesRoundTrip() {
        val all = listOf(
            CallDial("r1", "+919847012345"), CallDial("r2", "*121#", 2),
            CallDialResult("r1", true, via = "telecom"), CallDialResult("r2", false, "needs_permission"),
            CallLive("c1", CallDirs.IN, CallLiveStates.RINGING, "+919847012345", "Asha", canAnswer = true, canEnd = true, ts = 5),
            CallLive(
                "c2", CallDirs.OUT, CallLiveStates.ACTIVE, "121", null, since = 10, speaker = true, muted = true,
                canEnd = true, canSpeaker = true, canMute = true, sub = 1, ts = 11, reason = "not_allowed",
            ),
            CallLogReq(), CallLogReq(30, 1_699_000_000_000),
            CallLog(listOf(entry, entry.copy(id = 42, type = CallLogTypes.OUT, dur = 75, isNew = false, name = null)), more = true),
            CallLog(emptyList(), needs = "calllog"), CallLog(emptyList(), before = 99),
            ContactSearch("ash", 10),
            Contacts("ash", listOf(ContactInfo("Asha", listOf(ContactNumber("+919847012345", "Mobile"), ContactNumber("0484 2345678"))))),
            Contacts("x", emptyList(), needs = "contacts"),
            NotifAccess(true, null, 36, "OnePlus", 7), NotifAccess(false, true, 35, ts = 8),
            CallAction("c1", CallActions4.ANSWER, speaker = true), CallAction("c1", CallActions4.SPEAKER, on = false),
            CallAction("c1", CallActions4.MUTE, on = true), CallAction("c1", CallActions4.END),
        )
        for (m in all) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test
    fun typesAreTheDocumentedOnes() {
        assertEquals(0x45, MsgType.NOTIF_ACCESS)
        assertEquals(listOf(0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8), listOf(
            MsgType.CALL_DIAL, MsgType.CALL_DIAL_RESULT, MsgType.CALL_LIVE, MsgType.CALL_LOG_REQ, MsgType.CALL_LOG,
            MsgType.CONTACT_SEARCH, MsgType.CONTACTS,
        ))
        assertTrue(Caps.CURRENT.containsAll(Caps4.ALL))
        assertEquals(listOf("dial", "callctl", "calllog", "notifaccess"), Caps4.ALL)
    }

    @Test
    fun revision3CallActionHeaderIsUnchangedWithoutNewFields() {
        // A 0.3 phone decodes {"id","action"} only; the new fields are omitted when null.
        val h = String(MessageCodec.encode(CallAction("c1", "decline")), Charsets.UTF_8)
        assertTrue(h.endsWith("{\"id\":\"c1\",\"action\":\"decline\"}"))
    }

    @Test
    fun minimalHeadersDecodeWithDefaults() {
        val live = MessageCodec.decode(frame(MsgType.CALL_LIVE, """{"id":"c","dir":"in","state":"ringing","ts":1}""")) as CallLive
        assertFalse(live.canAnswer)
        assertFalse(live.canHold)
        assertEquals(0L, live.since)
        val log = MessageCodec.decode(frame(MsgType.CALL_LOG, """{"calls":[{"id":1,"number":"1","type":"in","date":2}],"x":1}""")) as CallLog
        assertFalse(log.calls.single().isNew)
        assertFalse(log.more)
    }

    @Test
    fun futureTypesStayUnknown() {
        assertEquals(UnknownMessage(0xA9), MessageCodec.decode(frame(0xA9, "{}")))
        assertEquals(UnknownMessage(0x46), MessageCodec.decode(frame(0x46, "{}")))
    }

    private fun frame(type: Int, header: String): ByteArray {
        val h = header.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(5 + h.size).put(type.toByte()).putInt(h.size).put(h).array()
    }
}

class LiveCallTrackerTest {
    private var n = 0
    private fun tracker() = LiveCallTracker({ "c${++n}" }).also {
        it.update(CallControls(answer = true, end = true, speaker = true, mute = true), now = 0)
    }

    @Test
    fun incomingAnsweredThenEnded() {
        val t = tracker()
        val ring = t.onState(CallTracker.RINGING, "+919847012345", "Asha", 1)
        assertEquals(CallStates.RINGING, ring.legacy!!.state)
        assertTrue(ring.live!!.canAnswer)
        assertFalse(ring.live!!.canSpeaker) // not off-hook yet
        assertEquals("c1", t.ringingId)
        val up = t.onState(CallTracker.OFFHOOK, null, null, 5)
        assertEquals(CallStates.OFFHOOK, up.legacy!!.state)
        assertEquals(CallLiveStates.ACTIVE, up.live!!.state)
        assertEquals(5L, up.live!!.since)
        assertFalse(up.live!!.canAnswer)
        assertTrue(up.live!!.canSpeaker && up.live!!.canMute && up.live!!.canEnd)
        assertNull(t.ringingId)
        val sp = t.update(speaker = true, now = 6)!!
        assertTrue(sp.speaker)
        assertNull("no change, nothing to send", t.update(speaker = true, now = 7))
        val end = t.onState(CallTracker.IDLE, null, null, 9)
        assertEquals(CallStates.ENDED, end.legacy!!.state)
        assertEquals(CallLiveStates.ENDED, end.live!!.state)
        assertFalse(end.live!!.speaker)
        assertFalse(end.live!!.canEnd)
        assertFalse(t.speaker)
        assertNull(t.currentId)
    }

    @Test
    fun ringingThenIdleIsMissedUnlessDeclinedFromMac() {
        val t = tracker()
        t.onState(CallTracker.RINGING, "1", null, 1)
        assertEquals(CallStates.MISSED, t.onState(CallTracker.IDLE, null, null, 2).legacy!!.state)
        t.onState(CallTracker.RINGING, "1", null, 3)
        t.declinedFromMac()
        assertEquals(CallStates.ENDED, t.onState(CallTracker.IDLE, null, null, 4).legacy!!.state)
    }

    @Test
    fun outgoingFromMacGetsNumberAndNoLegacyEvent() {
        val t = tracker()
        t.expectOutgoing("+919847012345", "Asha", 2, 100)
        val up = t.onState(CallTracker.OFFHOOK, null, null, 200)
        assertNull("0.3 Macs never saw outgoing calls", up.legacy)
        val l = up.live!!
        assertEquals(CallDirs.OUT, l.dir)
        assertEquals(CallLiveStates.DIALING, l.state)
        assertEquals("+919847012345", l.number)
        assertEquals("Asha", l.name)
        assertEquals(2, l.sub)
        assertTrue(l.canEnd && l.canSpeaker)
        assertFalse(l.canAnswer)
        val end = t.onState(CallTracker.IDLE, null, null, 300)
        assertNull(end.legacy)
        assertEquals(CallLiveStates.ENDED, end.live!!.state)
    }

    @Test
    fun staleDialRequestIsNotAttachedToALaterCall() {
        val t = tracker()
        t.expectOutgoing("111", null, null, 0)
        val l = t.onState(CallTracker.OFFHOOK, null, null, 120_000).live!!
        assertNull(l.number)
    }

    @Test
    fun callWaitingStartsANewCard() {
        val t = tracker()
        t.onState(CallTracker.RINGING, "1", null, 1)
        t.onState(CallTracker.OFFHOOK, null, null, 2)
        val waiting = t.onState(CallTracker.RINGING, "2", null, 3)
        assertEquals("c2", waiting.live!!.id)
        assertEquals("2", waiting.live!!.number)
        assertEquals("c2", t.ringingId)
    }

    @Test
    fun repeatedRingingKeepsTheIdAndLearnsTheName() {
        val t = tracker()
        val a = t.onState(CallTracker.RINGING, null, null, 1).live!!
        val b = t.onState(CallTracker.RINGING, "+91 1", null, 2).live!!
        assertEquals(a.id, b.id)
        val named = t.nameFound("Asha", 3)
        assertEquals("Asha", named.legacy!!.name)
        assertEquals("Asha", named.live!!.name)
        assertNull(t.nameFound("Asha", 4).live)
    }

    @Test
    fun unsupportedControlsAreNeverOffered() {
        val t = LiveCallTracker({ "x" })
        t.onState(CallTracker.RINGING, "1", null, 1)
        val l = t.onState(CallTracker.OFFHOOK, null, null, 2).live!!
        assertFalse(l.canAnswer || l.canEnd || l.canSpeaker || l.canMute || l.canHold)
        val f = t.failed("not_allowed", 3)!!
        assertEquals("not_allowed", f.reason)
        assertNull(t.current(4)!!.reason)
    }

    @Test
    fun idleWithoutCallAndOffhookTwiceAreQuiet() {
        val t = tracker()
        assertNull(t.onState(CallTracker.IDLE, null, null, 1).live)
        t.onState(CallTracker.OFFHOOK, null, null, 2)
        val again = t.onState(CallTracker.OFFHOOK, null, null, 3)
        assertNull(again.live)
        assertNull(again.legacy)
    }
}

class CallHelpersTest {
    @Test
    fun callLogTypes() {
        assertEquals(
            listOf("in", "out", "missed", "voicemail", "rejected", "blocked", "other", "other"),
            listOf(1, 2, 3, 4, 5, 6, 7, 0).map(CallLogTypeMap::of),
        )
    }

    @Test
    fun dialableNumbers() {
        assertEquals("+919847012345", Dialable.clean(" +91 98470-12345 "))
        assertEquals("04842345678", Dialable.clean("(0484) 234.5678"))
        assertEquals("*121#", Dialable.clean("*121#"))
        assertEquals("18001234,1;2", Dialable.clean("1800 1234 p1w2"))
        assertNull(Dialable.clean(""))
        assertNull(Dialable.clean("call me"))
        assertNull(Dialable.clean("1+1"))
        assertNull(Dialable.clean(",,,"))
        assertNull(Dialable.clean("١٢٣")) // non-ASCII digits are refused rather than misdialled
        assertNull(Dialable.clean("1".repeat(41)))
    }
}

class SensitiveNotificationsTest {
    @Test
    fun detectsTheSystemStringAndTracksTheGrant() {
        val s = SensitiveNotifications(listOf("Contenido sensible oculto"), 36, "OnePlus")
        assertTrue(s.isRedacted(null, "Sensitive notification content hidden"))
        assertTrue(s.isRedacted("contenido sensible oculto ", null))
        assertFalse(s.isRedacted("OTP", "Your code is 123456"))
        assertFalse(s.status(0).redacting)
        assertNull(s.onNotification("Bank", "Your code is 1", 1))
        val first = s.onNotification("Messages", "Sensitive notification content hidden", 2)
        assertNotNull(first)
        assertTrue(first!!.redacting)
        assertNull("unchanged: not sent again", s.onNotification("x", "Sensitive notification content hidden", 3))
        val granted = s.onGrant(true, 4)!!
        assertFalse(granted.redacting)
        assertEquals(true, granted.granted)
        // Still redacted after the grant (e.g. OnePlus permission monitoring reverted it).
        assertTrue(s.onNotification("x", "Sensitive notification content hidden", 5)!!.redacting)
    }

    @Test
    fun olderAndroidNeverReports() {
        val s = SensitiveNotifications(emptyList(), 34, "")
        assertNull(s.onNotification(null, "Sensitive notification content hidden", 1))
        assertFalse(s.redacting)
        assertEquals(
            "adb shell cmd appops set --user 0 in.benfranklin.tether RECEIVE_SENSITIVE_NOTIFICATIONS allow",
            SensitiveNotifications.adbCommand("in.benfranklin.tether"),
        )
    }
}
