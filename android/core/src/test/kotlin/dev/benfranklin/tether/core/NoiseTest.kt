package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.crypto.HandshakeState
import dev.benfranklin.tether.core.crypto.KeyPair
import dev.benfranklin.tether.core.crypto.NoiseException
import dev.benfranklin.tether.core.crypto.Role
import dev.benfranklin.tether.core.util.Hex
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseTest {
    @Test
    fun cacophonyXxVectors() {
        val vectors = Vectors.load("noise_xx.json").arr("vectors")
        assertTrue("expected at least one XX vector", vectors.isNotEmpty())
        for (v in vectors) {
            val init = HandshakeState(
                Role.INITIATOR, KeyPair.fromPrivate(Hex.decode(v.str("init_static"))),
                Hex.decode(v.str("init_prologue")), fixedEphemeral = Hex.decode(v.str("init_ephemeral")),
            )
            val resp = HandshakeState(
                Role.RESPONDER, KeyPair.fromPrivate(Hex.decode(v.str("resp_static"))),
                Hex.decode(v.str("resp_prologue")), fixedEphemeral = Hex.decode(v.str("resp_ephemeral")),
            )
            val messages = v.arr("messages")
            for (i in 0 until 3) {
                val m = messages[i]
                val (writer, reader) = if (i % 2 == 0) init to resp else resp to init
                val ct = writer.writeMessage(Hex.decode(m.str("payload")))
                assertEquals("handshake message $i", m.str("ciphertext"), Hex.encode(ct))
                assertArrayEquals(Hex.decode(m.str("payload")), reader.readMessage(ct))
            }
            val ri = init.split()
            val rr = resp.split()
            assertEquals(v.str("handshake_hash"), Hex.encode(ri.handshakeHash))
            assertArrayEquals(ri.handshakeHash, rr.handshakeHash)
            assertArrayEquals(KeyPair.fromPrivate(Hex.decode(v.str("resp_static"))).publicKey, ri.remoteStatic)
            for (i in 3 until messages.size) {
                val m = messages[i]
                val initiatorSends = i % 2 == 0
                val ct = if (initiatorSends) ri.send.encrypt(Hex.decode(m.str("payload"))) else rr.send.encrypt(Hex.decode(m.str("payload")))
                assertEquals("transport message $i", m.str("ciphertext"), Hex.encode(ct))
                val pt = if (initiatorSends) rr.receive.decrypt(ct) else ri.receive.decrypt(ct)
                assertArrayEquals(Hex.decode(m.str("payload")), pt)
            }
        }
    }

    @Test
    fun randomKeysRoundTrip() {
        val a = HandshakeState(Role.INITIATOR, KeyPair.generate(), "p".toByteArray())
        val b = HandshakeState(Role.RESPONDER, KeyPair.generate(), "p".toByteArray())
        assertTrue(a.isMyTurn)
        assertFalse(b.isMyTurn)
        b.readMessage(a.writeMessage())
        a.readMessage(b.writeMessage("hi".toByteArray()))
        b.readMessage(a.writeMessage("yo".toByteArray()))
        assertTrue(a.isComplete && b.isComplete)
        val ra = a.split()
        val rb = b.split()
        repeat(100) { n ->
            val msg = "message $n".toByteArray()
            assertArrayEquals(msg, rb.receive.decrypt(ra.send.encrypt(msg)))
            assertArrayEquals(msg, ra.receive.decrypt(rb.send.encrypt(msg)))
        }
    }

    @Test(expected = NoiseException::class)
    fun prologueMismatchFails() {
        val a = HandshakeState(Role.INITIATOR, KeyPair.generate(), "tether/1".toByteArray())
        val b = HandshakeState(Role.RESPONDER, KeyPair.generate(), "tether/1/wifi".toByteArray())
        b.readMessage(a.writeMessage())
        a.readMessage(b.writeMessage())
    }

    @Test
    fun tamperedTransportIsRejectedWithoutDesync() {
        val (ra, rb) = pair()
        val ct = ra.send.encrypt("secret".toByteArray())
        val bad = ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            rb.receive.decrypt(bad)
            error("tampered ciphertext accepted")
        } catch (_: NoiseException) {
            // expected
        }
        // The genuine message still decrypts: a forgery must not advance the nonce.
        assertArrayEquals("secret".toByteArray(), rb.receive.decrypt(ct))
    }

    @Test(expected = NoiseException::class)
    fun lowOrderPublicKeyRejected() {
        val b = HandshakeState(Role.RESPONDER, KeyPair.generate(), ByteArray(0))
        // An all-zero ephemeral is a low-order point; the ee DH must refuse it.
        b.readMessage(ByteArray(32))
        b.writeMessage()
    }

    @Test(expected = NoiseException::class)
    fun truncatedHandshakeRejected() {
        val a = HandshakeState(Role.INITIATOR, KeyPair.generate(), ByteArray(0))
        val b = HandshakeState(Role.RESPONDER, KeyPair.generate(), ByteArray(0))
        b.readMessage(a.writeMessage())
        a.readMessage(b.writeMessage().copyOf(40))
    }

    private fun pair(): Pair<dev.benfranklin.tether.core.crypto.HandshakeResult, dev.benfranklin.tether.core.crypto.HandshakeResult> {
        val a = HandshakeState(Role.INITIATOR, KeyPair.generate(), ByteArray(0))
        val b = HandshakeState(Role.RESPONDER, KeyPair.generate(), ByteArray(0))
        b.readMessage(a.writeMessage())
        a.readMessage(b.writeMessage())
        b.readMessage(a.writeMessage())
        return a.split() to b.split()
    }
}
