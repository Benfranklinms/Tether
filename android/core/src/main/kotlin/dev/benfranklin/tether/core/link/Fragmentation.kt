package dev.benfranklin.tether.core.link

/**
 * Splits records into BLE-sized fragments and reassembles them.
 *
 * Fragment layout (big-endian), adapted from bitchat's fragment header but trimmed for a
 * single point-to-point link (no sender IDs, no TTL):
 * ```
 * u8  version   (= 1)
 * u16 recordId  (wraps)
 * u16 index     (0-based)
 * u16 total     (>= 1)
 * ..  data
 * ```
 * GATT delivers writes and notifications in order on one connection, so the reassembler
 * expects fragments of a record to arrive consecutively. Anything else drops the partial
 * record and is counted, never thrown, so one bad fragment can't wedge the link.
 */
object Fragmentation {
    const val VERSION: Int = 1
    const val HEADER_LEN: Int = 7
    /** Smallest usable fragment (default ATT MTU 23 minus 3 bytes of ATT header). */
    const val MIN_FRAGMENT: Int = 20
    /** Upper bound on a reassembled record. File chunks are 16 KiB, so this is generous. */
    const val MAX_RECORD: Int = 128 * 1024
    const val MAX_FRAGMENTS: Int = 0xFFFF
    /**
     * Largest fragment ever put on the air. A GATT attribute value is at most 512 bytes; since
     * Android 13 `BluetoothGatt.writeCharacteristic` throws for anything longer, even when the
     * negotiated ATT MTU is 517 (MTU - 3 = 514). See docs/PROTOCOL.md §2.
     */
    const val MAX_BLE_FRAGMENT: Int = 512
}

class FragmentException(message: String) : Exception(message)

class Fragmenter {
    private var nextId = 0

    /**
     * Split [record] into fragments no larger than [maxFragment] bytes (header included).
     */
    fun fragment(record: ByteArray, maxFragment: Int): List<ByteArray> {
        if (maxFragment < Fragmentation.MIN_FRAGMENT) throw FragmentException("fragment size $maxFragment too small")
        if (record.size > Fragmentation.MAX_RECORD) throw FragmentException("record of ${record.size} bytes too large")
        val chunk = maxFragment - Fragmentation.HEADER_LEN
        val total = maxOf(1, (record.size + chunk - 1) / chunk)
        if (total > Fragmentation.MAX_FRAGMENTS) throw FragmentException("too many fragments")
        val id = nextId
        nextId = (nextId + 1) and U16
        return List(total) { index ->
            val start = index * chunk
            val end = minOf(record.size, start + chunk)
            val out = ByteArray(Fragmentation.HEADER_LEN + (end - start))
            out[0] = Fragmentation.VERSION.toByte()
            putU16(out, 1, id)
            putU16(out, 3, index)
            putU16(out, 5, total)
            record.copyInto(out, Fragmentation.HEADER_LEN, start, end)
            out
        }
    }

    private companion object {
        const val U16 = 0xFFFF
    }
}

class Reassembler(private val maxRecord: Int = Fragmentation.MAX_RECORD) {
    private var currentId = -1
    private var expectedIndex = 0
    private var total = 0
    private var buffer = java.io.ByteArrayOutputStream()

    /** Fragments discarded since creation (malformed, out of order, oversized). */
    var dropped: Int = 0
        private set

    /** Feed one fragment. Returns a complete record, or null if more fragments are needed. */
    fun accept(fragment: ByteArray): ByteArray? {
        if (fragment.size < Fragmentation.HEADER_LEN || fragment[0].toInt() != Fragmentation.VERSION) {
            return drop()
        }
        val id = getU16(fragment, 1)
        val index = getU16(fragment, 3)
        val count = getU16(fragment, 5)
        if (count == 0 || index >= count) return drop()
        if (index == 0) {
            if (currentId != -1) dropped++ // previous record never finished
            reset()
            currentId = id
            total = count
        } else if (id != currentId || index != expectedIndex || count != total) {
            return drop()
        }
        val dataLen = fragment.size - Fragmentation.HEADER_LEN
        if (buffer.size() + dataLen > maxRecord) return drop()
        buffer.write(fragment, Fragmentation.HEADER_LEN, dataLen)
        expectedIndex = index + 1
        if (expectedIndex < total) return null
        val record = buffer.toByteArray()
        reset()
        return record
    }

    fun reset() {
        currentId = -1
        expectedIndex = 0
        total = 0
        buffer = java.io.ByteArrayOutputStream()
    }

    private fun drop(): ByteArray? {
        dropped++
        reset()
        return null
    }
}

internal fun putU16(b: ByteArray, at: Int, v: Int) {
    b[at] = (v ushr 8).toByte()
    b[at + 1] = v.toByte()
}

internal fun getU16(b: ByteArray, at: Int): Int =
    ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
