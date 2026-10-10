package dev.benfranklin.tether

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.benfranklin.tether.call.CallLogStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Recents numbers are grouped like contacts' numbers; odd ones pass through untouched. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CallLogFormatTest {
    private fun f(n: String, iso: String = "IN") = CallLogStore.displayNumber(n, iso)

    @Test
    fun bareNumbersAreSpacedLikeContacts() {
        assertEquals("+91 98470 12345", f("+919847012345"))
        assertEquals("+1 650-253-0000", f("+16502530000"))
        assertEquals("(650) 253-0000", f("6502530000", "US"))
    }

    @Test
    fun formattedShortAndOddNumbersAreUnchanged() {
        assertEquals("1800 425 1425", f("1800 425 1425"))
        assertEquals("+91 99955 51234", f("+91 99955 51234"))
        assertEquals("121", f("121"))
        assertEquals("", f(""))
        assertEquals("VM-HDFCBK", f("VM-HDFCBK"))
        assertEquals("*123#", f("*123#"))
    }
}
