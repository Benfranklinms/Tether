package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.KeyPair
import dev.benfranklin.tether.core.session.PairingUri
import dev.benfranklin.tether.core.session.PeerRecord
import dev.benfranklin.tether.core.session.Session
import dev.benfranklin.tether.core.util.B64
import dev.benfranklin.tether.core.util.Backoff
import dev.benfranklin.tether.core.util.Heartbeat
import dev.benfranklin.tether.core.util.Hex
import dev.benfranklin.tether.core.util.fingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class MiscTest {
    @Test
    fun sasMatchesVectors() {
        for (v in Vectors.array("sas.json")) {
            assertEquals(v.str("sas"), Session.computeSas(Hex.decode(v.str("handshakeHash"))))
        }
    }

    @Test
    fun sasUsesAsciiDigitsInAnyLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertTrue(Session.computeSas(ByteArray(32)).all { it in '0'..'9' })
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun backoffGrowsAndCaps() {
        val b = Backoff(baseMs = 1000, maxMs = 60_000, jitter = 0.0)
        assertEquals(listOf(1000L, 2000, 4000, 8000, 16000, 32000, 60000, 60000), List(8) { b.next() })
        b.reset()
        assertEquals(1000L, b.next())
    }

    @Test
    fun backoffJitterStaysInRange() {
        val b = Backoff(baseMs = 1000, maxMs = 8000, jitter = 0.5, random = Random(1))
        repeat(50) {
            val d = b.next()
            assertTrue(d in 500L..8000L)
        }
    }

    @Test
    fun heartbeatPingsThenDeclaresDead() {
        val h = Heartbeat(intervalMs = 100, timeoutMs = 50)
        h.reset(0)
        assertEquals(Heartbeat.Action.NONE, h.tick(50))
        assertEquals(Heartbeat.Action.SEND_PING, h.tick(100))
        assertEquals(Heartbeat.Action.NONE, h.tick(120))
        assertEquals(Heartbeat.Action.DEAD, h.tick(151))
        h.heard(160)
        assertEquals(Heartbeat.Action.NONE, h.tick(200))
    }

    @Test
    fun peerRecordRotation() {
        val a = KeyPair.generate().publicKey
        val b = KeyPair.generate().publicKey
        var p = PeerRecord.paired("Mac", "macos", a, 1)
        assertTrue(p.trusts(a))
        assertFalse(p.trusts(b))
        p = p.withPendingKey(b)
        assertTrue(p.trusts(a) && p.trusts(b))
        p = p.afterHandshake(a, "Mac")
        assertTrue(p.trusts(b)) // old key used: pending stays
        p = p.afterHandshake(b, "Mac 2")
        assertFalse(p.trusts(a))
        assertNull(p.pendingKey)
        assertEquals("Mac 2", p.name)
    }

    @Test
    fun pairingUriRoundTripAndRejectsJunk() {
        val k = KeyPair.generate().publicKey
        val t = ByteArray(16) { (it * 3).toByte() }
        val u = PairingUri(k, t, "Ben's Mac & co")
        val s = u.encode()
        assertTrue(s.startsWith("tether://pair?v=1&k="))
        assertFalse(s.contains("+"))
        assertEquals(u, PairingUri.parse(s))
        assertNull(PairingUri.parse("https://example.com"))
        assertNull(PairingUri.parse("tether://pair?v=2&k=${B64.encode(k)}&t=${B64.encode(t)}"))
        assertNull(PairingUri.parse("tether://pair?v=1&k=short&t=${B64.encode(t)}"))
        assertNull(PairingUri.parse("tether://pair?v=1&k=${B64.encode(k)}"))
        assertEquals("Mac", PairingUri.parse("tether://pair?v=1&k=${B64.encode(k)}&t=${B64.encode(t)}")!!.name)
    }

    @Test
    fun base64UrlAndFingerprint() {
        val bytes = ByteArray(32) { it.toByte() }
        assertArrayEquals(bytes, B64.decode(B64.encode(bytes)))
        assertNull(B64.decode("!!!"))
        assertEquals(14, fingerprint(bytes).length)
    }
}
