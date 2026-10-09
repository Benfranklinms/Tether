package dev.benfranklin.tether

import android.telephony.TelephonyManager
import dev.benfranklin.tether.device.BatteryMonitor
import dev.benfranklin.tether.media.MediaKinds
import dev.benfranklin.tether.screen.TextEdit
import dev.benfranklin.tether.screen.encodedSize
import dev.benfranklin.tether.ui.screens.nextScreenSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureLogicTest {
    @Test
    fun classifiesMedia() {
        assertEquals(MediaKinds.SCREENSHOT, MediaKinds.classify("Pictures/Screenshots/", null, "Screenshots"))
        assertEquals(MediaKinds.SCREENSHOT, MediaKinds.classify("DCIM/Screenshots/", null, null)) // OnePlus / OxygenOS
        assertEquals(MediaKinds.SCREENSHOT, MediaKinds.classify(null, "/storage/emulated/0/Pictures/Screenshots/a.png", null))
        assertEquals(MediaKinds.PHOTO, MediaKinds.classify("DCIM/Camera/", null, "Camera"))
        assertEquals(MediaKinds.PHOTO, MediaKinds.classify(null, "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", null))
        assertNull(MediaKinds.classify("Pictures/WhatsApp/", null, "WhatsApp Images"))
        assertNull(MediaKinds.classify("Download/", null, "Download"))
    }

    @Test
    fun encodedSizeKeepsAspectAndAlignment() {
        assertEquals(576 to 1280, encodedSize(1080, 2400, 1280)) // 1080x2400 -> 576x1280
        assertEquals(1280 to 576, encodedSize(2400, 1080, 1280))
        assertEquals(1072 to 2400, encodedSize(1080, 2400, 4000)) // never upscales, 16-aligned
        val (w, h) = encodedSize(1080, 2412, 720)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertEquals(1080.0 / 2412, w.toDouble() / h, 0.02)
    }

    @Test
    fun textEditing() {
        assertEquals("hello world", TextEdit.append("hello", false, " world"))
        assertEquals("hi", TextEdit.append("Search…", true, "hi")) // hint text is replaced
        assertEquals("hell", TextEdit.backspace("hello", false))
        assertEquals("a", TextEdit.backspace("a👍", false)) // whole emoji, not half a surrogate pair
        assertEquals("", TextEdit.backspace("", false))
        assertEquals("", TextEdit.backspace("Message", true))
    }

    @Test
    fun screenSizesCycle() {
        assertEquals(1080, nextScreenSize(720))
        assertEquals(1600, nextScreenSize(1280))
        assertEquals(720, nextScreenSize(1600))
    }

    @Test
    fun networkGenerations() {
        assertEquals("5G", BatteryMonitor.generation(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("4G", BatteryMonitor.generation(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G", BatteryMonitor.generation(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("2G", BatteryMonitor.generation(TelephonyManager.NETWORK_TYPE_EDGE))
        assertNull(BatteryMonitor.generation(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }
}
