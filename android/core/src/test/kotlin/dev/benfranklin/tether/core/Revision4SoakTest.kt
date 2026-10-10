package dev.benfranklin.tether.core

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
import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.core.proto.CallStates
import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.ContactInfo
import dev.benfranklin.tether.core.proto.ContactNumber
import dev.benfranklin.tether.core.proto.ContactSearch
import dev.benfranklin.tether.core.proto.Contacts
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.NotifAccess
import dev.benfranklin.tether.core.proto.SmsThreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link soak with 0.4 traffic (simulated hours on the real controllers): calls every few
 * minutes (ring, answer, speaker, end, dial from the Mac), call-log pages of 100 calls,
 * contact searches and the sensitive-notification status, over Bluetooth only (Android's
 * 512-byte write limit on) and over Wi-Fi with idle Bluetooth. Nothing may be lost, reordered
 * or close the link.
 */
class Revision4SoakTest {
    private val loop = Loop()
    private val sides = pairedSides(loop)
    private val phone = sides.first
    private val mac = sides.second

    private fun tickFor(seconds: Int, everySecond: (Int) -> Unit) {
        repeat(seconds) { s ->
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            everySecond(s)
            loop.drain()
        }
    }

    private fun log(n: Int) = CallLog(
        (0 until n).map { i ->
            CallLogEntry(
                10_000L - i, "+91 98470 ${10_000 + i}", if (i % 3 == 0) null else "Contact $i",
                listOf(CallLogTypes.IN, CallLogTypes.OUT, CallLogTypes.MISSED)[i % 3], loop.now - i * 60_000L, i % 400L,
                i % 2 + 1, isNew = i % 7 == 0,
            )
        },
        more = true,
    )

    /** One full call, both directions, as the phone and Mac would exchange it. */
    private fun call(s: Int, phoneSent: MutableList<Message>, macSent: MutableList<Message>) {
        val id = "c$s"
        val t = loop.now
        fun p(m: Message) {
            assertTrue(phone.controller.send(m))
            phoneSent += m
        }
        fun m(x: Message) {
            assertTrue(mac.controller.send(x))
            macSent += x
        }
        if (s % 2 == 0) {
            p(CallState(id, CallStates.RINGING, "+919847012345", "Asha", t))
            p(CallLive(id, CallDirs.IN, CallLiveStates.RINGING, "+919847012345", "Asha", canAnswer = true, canEnd = true, ts = t))
            m(CallAction(id, CallActions4.ANSWER, speaker = true))
            p(CallState(id, CallStates.OFFHOOK, "+919847012345", "Asha", t))
        } else {
            m(CallDial("r$s", "+919847012345", 2))
            p(CallDialResult("r$s", true, via = "telecom"))
        }
        p(CallLive(id, CallDirs.IN, CallLiveStates.ACTIVE, since = t, speaker = true, canEnd = true, canSpeaker = true, ts = t))
        m(CallAction(id, CallActions4.MUTE, on = true))
        m(CallAction(id, CallActions4.END))
        p(CallLive(id, CallDirs.IN, CallLiveStates.ENDED, ts = t))
    }

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
        val bleBefore = ble.fragmentsSent
        tickFor(6 * 3600) { s ->
            if (s % 600 == 0) call(s, phoneSent, macSent)
            if (s % 1800 == 0) {
                assertTrue(mac.controller.send(CallLogReq(100)))
                macSent += CallLogReq(100)
                val l = log(100)
                assertTrue(phone.controller.send(l))
                phoneSent += l
            }
            if (s % 900 == 300) {
                val q = ContactSearch("as$s")
                val a = Contacts("as$s", listOf(ContactInfo("Asha", listOf(ContactNumber("+919847012345", "Mobile")))))
                assertTrue(mac.controller.send(q))
                macSent += q
                assertTrue(phone.controller.send(a))
                phoneSent += a
            }
            if (s % 3600 == 1) {
                val n = NotifAccess(s % 7200 == 1, null, 36, "OnePlus", loop.now)
                assertTrue(phone.controller.send(n))
                phoneSent += n
            }
            if (wifiUp && s % 20 == 0) assertTrue(phone.controller.send(Clip("k$s", loop.now, "clip $s")))
            if (s % 2700 == 0) assertTrue(phone.controller.send(SmsThreads(realisticThreads(40))))
        }
        assertTrue("closes: phone=${phone.closes} mac=${mac.closes}", phone.closes.isEmpty() && mac.closes.isEmpty())
        assertFalse(ble.wedged)
        val rev4 = { m: Message -> m.type in 0xA0..0xA8 || m.type == 0x45 }
        assertEquals("phone -> Mac, in order", phoneSent, mac.messages.filter(rev4))
        assertEquals("Mac -> phone, in order", macSent, phone.messages.filter(rev4))
        assertTrue("phone sent ${phoneSent.size}", phoneSent.size > 200)
        if (wifiUp) {
            // Call logs and contact answers rode Wi-Fi; Bluetooth carried small call events and keepalives.
            val perHour = (ble.fragmentsSent - bleBefore) / 6
            assertTrue("BLE fragments/hour $perHour", perHour < 400)
            assertEquals(LinkState.Connected("Pixel", true), mac.state)
        } else {
            assertTrue("a 100-call page spans many fragments", MessageCodec.encode(log(100)).size > 20 * 512)
            assertTrue(mac.state is LinkState.Connected)
        }
    }

    @Test
    fun sixHoursOfCallsOverBluetoothOnly() = soak(wifiUp = false)

    @Test
    fun sixHoursOfCallsOverWifiWithIdleBluetooth() = soak(wifiUp = true)
}
