package dev.benfranklin.tether

import dev.benfranklin.tether.core.features.UpdateInfo
import dev.benfranklin.tether.screen.BrowserTabs
import dev.benfranklin.tether.ui.screens.SetupRow
import dev.benfranklin.tether.ui.screens.setupSummary
import dev.benfranklin.tether.ui.screens.updateUi
import dev.benfranklin.tether.v5.Features5
import dev.benfranklin.tether.v5.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Features5AppTest {
    @Test fun setupSummaryCountsRequired() {
        val rows = listOf(
            SetupRow("a", "A", "", ok = true, required = true),
            SetupRow("b", "B", "", ok = false, required = true),
            SetupRow("c", "C", "", ok = false, required = false),
        )
        assertEquals("1 of 3 ready · 1 required missing", setupSummary(rows))
        assertEquals("1 of 1 ready", setupSummary(rows.take(1)))
    }

    @Test fun browserAddressBarsBecomeUrls() {
        assertEquals("https://careers.armada.ai/apply", BrowserTabs.clean(" careers.armada.ai/apply "))
        assertEquals("http://x.com", BrowserTabs.clean("http://x.com"))
        assertNull(BrowserTabs.clean("best biryani kochi"))
        assertNull(BrowserTabs.clean("chrome://newtab"))
        assertNull(BrowserTabs.clean(""))
        assertTrue(BrowserTabs.browsers.containsKey("com.brave.browser"))
        assertEquals(listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text", "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text"),
            BrowserTabs.urlBarIds("com.sec.android.app.sbrowser"))
    }

    @Test fun humanSizes() {
        assertEquals("512 B", Features5.human(512))
        assertEquals("3 KB", Features5.human(3 * 1024))
        assertEquals("3.1 MB", Features5.human(3_250_000))
        assertEquals("1.5 GB", Features5.human(1_610_612_736))
    }

    @Test fun updateRowStates() {
        val info = UpdateInfo("0.5.1")
        assertEquals("Check for updates", updateUi(Updater.State.Idle).action)
        assertNull(updateUi(Updater.State.Checking).action)
        assertEquals("Download and install", updateUi(Updater.State.Available(info)).action)
        assertEquals(0.5f, updateUi(Updater.State.Downloading(info, 0.5f)).progress)
        assertTrue(updateUi(Updater.State.Downloading(info, 0.5f)).line.contains("50%"))
        assertEquals("Try again", updateUi(Updater.State.Failed("x")).action)
        assertTrue(updateUi(Updater.State.UpToDate("0.5.0")).line.contains("0.5.0"))
    }
}
