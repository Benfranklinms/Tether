package dev.benfranklin.tether

import dev.benfranklin.tether.core.proto.HotspotReasons
import dev.benfranklin.tether.core.proto.HotspotStates
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.ui.screens.changelogText
import dev.benfranklin.tether.ui.screens.hotspotStatusText
import dev.benfranklin.tether.ui.screens.shelfDropIndex
import dev.benfranklin.tether.ui.screens.shelfKindLabel
import dev.benfranklin.tether.ui.screens.shelfPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the 0.5.2 phone screens (Shelf, hotspot, update sheets). */
class Screens6LogicTest {
    private fun item(kind: String, body: String = "", secret: String? = null, file: ShelfFile? = null) =
        ShelfItem("id", kind, "Title", body, secret, "mac", 1, 1, 0.0, file)

    @Test
    fun kindLabels() {
        assertEquals("WI-FI", shelfKindLabel(ShelfKinds.WIFI))
        assertEquals("LINK", shelfKindLabel(ShelfKinds.LINK))
        assertEquals("NOTE", shelfKindLabel(ShelfKinds.TEXT))
        assertEquals("NOTE", shelfKindLabel("something-new"))
    }

    @Test
    fun previewNeverShowsTheWifiPassword() {
        val p = shelfPreview(item(ShelfKinds.WIFI, "Home", secret = "hunter22"))
        assertFalse(p.contains("hunter22"))
        assertEquals("Open network", shelfPreview(item(ShelfKinds.WIFI, "Cafe")))
        val f = shelfPreview(item(ShelfKinds.FILE, file = ShelfFile("a.pdf", 2048, "application/pdf", "00", "mac")))
        assertTrue(f.startsWith("a.pdf"))
        assertEquals("body", shelfPreview(item(ShelfKinds.TEXT, "body")))
    }

    @Test
    fun dropIndexFollowsTheCentre() {
        val slots = listOf(0 to 100, 110 to 100, 220 to 100)
        assertEquals(0, shelfDropIndex(slots, 1, 50f))
        assertEquals(1, shelfDropIndex(slots, 0, 150f))
        assertEquals(2, shelfDropIndex(slots, 0, 260f))
        // In a gap: stays where it is; past the ends: clamps.
        assertEquals(1, shelfDropIndex(slots, 1, 105f))
        assertEquals(0, shelfDropIndex(slots, 2, -40f))
        assertEquals(2, shelfDropIndex(slots, 0, 999f))
        assertEquals(3, shelfDropIndex(emptyList(), 3, 10f))
    }

    @Test
    fun hotspotStatus() {
        assertNull(hotspotStatusText(null, null))
        assertEquals("Hotspot on · your Mac joined", hotspotStatusText(HotspotStates.ON, null))
        assertTrue(hotspotStatusText(HotspotStates.FAILED, HotspotReasons.NO_CREDENTIALS)!!.contains("no hotspot name"))
        assertTrue(hotspotStatusText(HotspotStates.MANUAL, HotspotReasons.NO_SWITCH)!!.contains("by hand"))
    }

    @Test
    fun changelogIsPlainText() {
        val md = "## Highlights\n- **Shelf** on both devices\n* `Find my Mac`\nPlain line"
        assertEquals("Highlights\n•  Shelf on both devices\n•  Find my Mac\nPlain line", changelogText(md))
    }
}
