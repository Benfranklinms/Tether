package dev.benfranklin.tether

import dev.benfranklin.tether.ui.screens.SensitiveSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveGuideTest {
    @Test
    fun colorOsPhonesGetThePermissionMonitoringStep() {
        assertTrue(SensitiveSteps.isColorOs("OnePlus"))
        assertTrue(SensitiveSteps.isColorOs("OPPO"))
        assertTrue(SensitiveSteps.isColorOs("realme"))
        assertFalse(SensitiveSteps.isColorOs("Google"))
        val oneplus = SensitiveSteps.steps("OnePlus")
        val pixel = SensitiveSteps.steps("Google")
        assertEquals(pixel.size + 1, oneplus.size)
        assertTrue(oneplus.any { "Disable permission monitoring" in it })
        assertTrue(pixel.none { "Disable permission monitoring" in it })
        assertTrue(pixel.last().startsWith("Restart the phone"))
        assertTrue(pixel.any { "Wireless debugging" in it })
    }
}
