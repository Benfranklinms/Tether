package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.link.FragmentException
import dev.benfranklin.tether.core.link.Fragmentation
import dev.benfranklin.tether.core.link.Fragmenter
import dev.benfranklin.tether.core.link.Reassembler
import dev.benfranklin.tether.core.util.Hex
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class FragmentationTest {
    @Test
    fun matchesSharedVectors() {
        for (v in Vectors.array("fragments.json")) {
            val record = Hex.decode(v.str("record"))
            val max = v.jsonObject["maxFragment"]!!.jsonPrimitive.int
            val id = v.jsonObject["recordId"]!!.jsonPrimitive.int
            val f = Fragmenter()
            repeat(id) { f.fragment(ByteArray(0), 20) } // advance the record id
            val frags = f.fragment(record, max)
            val expected = v.arr("fragments").map { it.jsonPrimitive.content }
            assertEquals(v.str("name"), expected, frags.map { Hex.encode(it) })
            val r = Reassembler()
            var out: ByteArray? = null
            for (x in expected) out = r.accept(Hex.decode(x))
            assertArrayEquals(record, out)
        }
    }

    @Test
    fun roundTripsRandomSizes() {
        val rnd = Random(7)
        val f = Fragmenter()
        val r = Reassembler()
        repeat(300) {
            val rec = rnd.nextBytes(rnd.nextInt(0, 20_000))
            val mtu = rnd.nextInt(20, 513)
            val frags = f.fragment(rec, mtu)
            frags.forEach { assert(it.size <= mtu) }
            frags.dropLast(1).forEach { assertNull(r.accept(it)) }
            assertArrayEquals(rec, r.accept(frags.last()))
        }
        assertEquals(0, r.dropped)
    }

    @Test
    fun outOfOrderFragmentDropsRecordAndRecovers() {
        val f = Fragmenter()
        val r = Reassembler()
        val a = f.fragment(ByteArray(100) { 1 }, 30)
        val b = f.fragment(ByteArray(50) { 2 }, 30)
        assertNull(r.accept(a[0]))
        assertNull(r.accept(a[2])) // skipped a[1]
        assertEquals(1, r.dropped)
        var out: ByteArray? = null
        b.forEach { out = r.accept(it) }
        assertArrayEquals(ByteArray(50) { 2 }, out)
    }

    @Test
    fun newRecordStartAbandonsPartial() {
        val f = Fragmenter()
        val r = Reassembler()
        val a = f.fragment(ByteArray(100), 30)
        val b = f.fragment(ByteArray(10) { 9 }, 30)
        r.accept(a[0])
        assertArrayEquals(ByteArray(10) { 9 }, r.accept(b[0]))
        assertEquals(1, r.dropped)
    }

    @Test
    fun malformedFragmentsAreCountedNotThrown() {
        val r = Reassembler()
        assertNull(r.accept(ByteArray(3)))
        assertNull(r.accept(byteArrayOf(2, 0, 0, 0, 0, 0, 1))) // wrong version
        assertNull(r.accept(byteArrayOf(1, 0, 0, 0, 0, 0, 0))) // total 0
        assertNull(r.accept(byteArrayOf(1, 0, 0, 0, 5, 0, 2))) // index >= total
        assertEquals(4, r.dropped)
    }

    @Test
    fun oversizedRecordDropped() {
        val r = Reassembler(maxRecord = 100)
        val frags = Fragmenter().fragment(ByteArray(500), 60)
        frags.forEach { assertNull(r.accept(it)) }
        assert(r.dropped >= 1)
    }

    @Test(expected = FragmentException::class)
    fun tinyMtuRejected() {
        Fragmenter().fragment(ByteArray(5), Fragmentation.MIN_FRAGMENT - 1)
    }
}
