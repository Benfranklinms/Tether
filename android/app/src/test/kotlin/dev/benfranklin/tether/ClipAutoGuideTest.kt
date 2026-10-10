package dev.benfranklin.tether

import dev.benfranklin.tether.core.features.ClipAutoSetup
import dev.benfranklin.tether.ui.screens.ClipAutoSteps
import dev.benfranklin.tether.ui.screens.clipAutoHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipAutoGuideTest {
    @Test
    fun wirelessAdbStepsInOrderWithTheOnePlusExtra() {
        val oneplus = ClipAutoSteps.steps("OnePlus")
        val pixel = ClipAutoSteps.steps("Google")
        assertEquals(pixel.size + 1, oneplus.size)
        assertTrue(oneplus.any { "Disable permission monitoring" in it })
        // No USB: pair, then connect, then the commands, in that order.
        val pair = pixel.indexOfFirst { "adb pair" in it }
        val connect = pixel.indexOfFirst { "adb connect" in it }
        val run = pixel.indexOfFirst { "three commands" in it }
        assertTrue(pair in 0 until connect)
        assertTrue(connect < run)
        assertTrue(pixel.any { "Wireless debugging" in it })
        assertTrue(pixel.any { "Allow Tether to access all device logs" in it })
    }

    @Test
    fun commandBlockIsTheThreeCommandsForThisBuild() {
        val block = ClipAutoSteps.commandBlock("in.benfranklin.tether")
        assertEquals(
            "adb shell pm grant in.benfranklin.tether android.permission.READ_LOGS\n" +
                "adb shell appops set in.benfranklin.tether SYSTEM_ALERT_WINDOW allow\n" +
                "adb shell am force-stop in.benfranklin.tether",
            block,
        )
    }

    @Test
    fun everyStatusButOffHasAHint() {
        assertNull(clipAutoHint(ClipAutoSetup.Status.OFF))
        ClipAutoSetup.Status.entries.filter { it != ClipAutoSetup.Status.OFF }.forEach { assertTrue(clipAutoHint(it)!!.isNotBlank()) }
    }
}
