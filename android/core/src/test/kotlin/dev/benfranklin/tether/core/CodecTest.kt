package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.proto.Clip
import dev.benfranklin.tether.core.proto.FileAccept
import dev.benfranklin.tether.core.proto.FileAck
import dev.benfranklin.tether.core.proto.FileCancel
import dev.benfranklin.tether.core.proto.FileChunk
import dev.benfranklin.tether.core.proto.FileComplete
import dev.benfranklin.tether.core.proto.FileDone
import dev.benfranklin.tether.core.proto.FileOffer
import dev.benfranklin.tether.core.proto.FileReject
import dev.benfranklin.tether.core.proto.Hello
import dev.benfranklin.tether.core.proto.KeyRotate
import dev.benfranklin.tether.core.proto.KeyRotateAck
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.Notif
import dev.benfranklin.tether.core.proto.NotifDismiss
import dev.benfranklin.tether.core.proto.NotifRemoved
import dev.benfranklin.tether.core.proto.PairConfirm
import dev.benfranklin.tether.core.proto.PairReject
import dev.benfranklin.tether.core.proto.Ping
import dev.benfranklin.tether.core.proto.Pong
import dev.benfranklin.tether.core.proto.ProtocolException
import dev.benfranklin.tether.core.proto.Unpair
import dev.benfranklin.tether.core.proto.UnknownMessage
import dev.benfranklin.tether.core.proto.WifiBind
import dev.benfranklin.tether.core.proto.WifiOffer
import dev.benfranklin.tether.core.proto.WifiRevoke
import dev.benfranklin.tether.core.util.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {
    @Test
    fun decodesSharedVectors() {
        val byName = Vectors.array("messages.json").associate { it.str("name") to MessageCodec.decode(Hex.decode(it.str("encoded"))) }
        assertEquals(Ping(1700000000000), byName["ping"])
        assertEquals(PairConfirm("AAECAwQFBgcICQoLDA0ODw"), byName["pair_confirm_token"])
        assertEquals(PairConfirm(null), byName["pair_confirm_plain"])
        assertEquals(Clip("c1", 1700000000123, "héllo, Mac 👋\nline two"), byName["clip"])
        assertEquals(
            Notif("0|com.whatsapp|1|null|10123", "com.whatsapp", "WhatsApp", "Asha", "On my way \"now\"", 1700000001000),
            byName["notif"],
        )
        assertEquals(NotifDismiss("k"), byName["notif_dismiss"])
        assertEquals(FileOffer("f-1", "report.pdf", 1048576, "a".repeat(64), "application/pdf"), byName["file_offer"])
        assertEquals(FileChunk("f-1", 16384, ByteArray(64) { it.toByte() }), byName["file_chunk"])
        assertEquals(FileComplete("f-1", false, "hash"), byName["file_complete"])
        assertEquals(WifiOffer(listOf("192.168.1.20", "10.0.0.5"), 51234, "q83vEjRWeJA"), byName["wifi_offer"])
        assertEquals(UnknownMessage(0x7f), byName["unknown_future"])
        assertEquals(Pong(5), byName["extra_fields_ignored"])
    }

    @Test
    fun everyTypeRoundTrips() {
        val all: List<Message> = listOf(
            Ping(1), Pong(2), PairConfirm("t"), PairConfirm(), PairReject("user"), Unpair(), KeyRotate("k"), KeyRotateAck("k"),
            Clip("id", 3, ""), Clip("id", 3, "日本語 and emoji 🎉"), Notif("k", "p", "App", "T", "x", 4), NotifRemoved("k"),
            NotifDismiss("k"), FileOffer("i", "n", 0, "b".repeat(64)), FileAccept("i", 7), FileReject("i", "space"),
            FileChunk("i", 9, ByteArray(0)), FileChunk("i", 9, ByteArray(1000) { 3 }), FileAck("i", 10), FileDone("i"),
            FileComplete("i", true), FileCancel("i"), WifiOffer(emptyList(), 0, "t"), WifiRevoke(), WifiBind("t"),
        )
        for (m in all) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test
    fun helloRoundTrip() {
        val h = Hello(name = "Alex’s Pixel \"8\"", platform = "android", app = "0.1.0")
        assertEquals(h, MessageCodec.decodeHello(MessageCodec.encodeHello(h)))
    }

    @Test(expected = ProtocolException::class)
    fun truncatedRejected() {
        MessageCodec.decode(byteArrayOf(0x10, 0, 0))
    }

    @Test(expected = ProtocolException::class)
    fun headerLengthBeyondEndRejected() {
        MessageCodec.decode(byteArrayOf(0x10, 0, 0, 0, 50, '{'.code.toByte()))
    }

    @Test(expected = ProtocolException::class)
    fun missingRequiredFieldRejected() {
        val h = "{}".toByteArray()
        MessageCodec.decode(byteArrayOf(0x10, 0, 0, 0, h.size.toByte()) + h)
    }

    @Test
    fun clipTextIsInBodyNotJson() {
        val enc = MessageCodec.encode(Clip("x", 1, "BODY"))
        assertArrayEquals("BODY".toByteArray(), enc.copyOfRange(enc.size - 4, enc.size))
    }
}
