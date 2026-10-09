package dev.benfranklin.tether.core.wifi

import dev.benfranklin.tether.core.proto.Protocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Record framing on a TCP stream: `u32 length (big-endian) || record`. The records are the
 * same HANDSHAKE / TRANSPORT / CLOSE records used over Bluetooth, so [Session] is shared.
 */
object StreamFraming {
    const val HEADER_LEN: Int = 4

    @Throws(IOException::class)
    fun write(out: OutputStream, record: ByteArray) {
        if (record.size > Protocol.MAX_TCP_RECORD) throw IOException("record too large: ${record.size}")
        val d = DataOutputStream(out)
        d.writeInt(record.size)
        d.write(record)
    }

    /** Reads one record, or returns null on a clean EOF between records. */
    @Throws(IOException::class)
    fun read(input: InputStream, maxRecord: Int = Protocol.MAX_TCP_RECORD): ByteArray? {
        val d = DataInputStream(input)
        val first = d.read()
        if (first < 0) return null
        val rest = ByteArray(HEADER_LEN - 1)
        d.readFully(rest)
        val len = (first shl 24) or ((rest[0].toInt() and 0xFF) shl 16) or
            ((rest[1].toInt() and 0xFF) shl 8) or (rest[2].toInt() and 0xFF)
        if (len < 1 || len > maxRecord) throw IOException("bad record length $len")
        val buf = ByteArray(len)
        try {
            d.readFully(buf)
        } catch (e: EOFException) {
            throw IOException("stream ended mid-record", e)
        }
        return buf
    }
}
