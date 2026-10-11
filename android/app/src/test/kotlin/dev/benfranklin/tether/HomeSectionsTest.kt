package dev.benfranklin.tether

import dev.benfranklin.tether.core.features.PanelSpec
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.data.ClipLabel
import dev.benfranklin.tether.data.HeaderLine
import dev.benfranklin.tether.data.HomeRow
import dev.benfranklin.tether.data.HomeSection
import dev.benfranklin.tether.data.HomeSection.CLIPBOARD
import dev.benfranklin.tether.data.HomeSection.FIND_MAC
import dev.benfranklin.tether.data.HomeSection.NOW_PLAYING
import dev.benfranklin.tether.data.HomeSection.PHOTOS
import dev.benfranklin.tether.data.HomeSection.QUICK
import dev.benfranklin.tether.data.HomeSection.SHELF
import dev.benfranklin.tether.data.HomeSection.TRANSFERS
import dev.benfranklin.tether.data.HomeSections
import dev.benfranklin.tether.data.PhotoBadge
import dev.benfranklin.tether.data.SectionSpec
import dev.benfranklin.tether.ui.screens.dropIndex
import dev.benfranklin.tether.ui.screens.isVideo
import dev.benfranklin.tether.ui.screens.squareLabelSp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.4 Home: section model, defaults, 0.5.3 migration, header line, drag math. */
class HomeSectionsTest {
    @Test fun defaultsMatchTheMockup() {
        val d = HomeSections.DEFAULT
        assertEquals(listOf(QUICK, NOW_PLAYING, TRANSFERS, SHELF, CLIPBOARD, PHOTOS, FIND_MAC), d.order)
        assertEquals(listOf(QUICK, NOW_PLAYING, TRANSFERS, SHELF), d.visible)
        assertEquals(setOf(CLIPBOARD, PHOTOS, FIND_MAC), d.hidden)
        assertEquals(listOf(HomeRow.Control, HomeRow.Single(TRANSFERS), HomeRow.Single(SHELF)), d.rows())
        assertEquals(d, HomeSections.from(null, null))
        assertEquals(d, HomeSections.from(null))
    }

    @Test fun savedListWinsOverLegacy() {
        val saved = listOf(SectionSpec("shelf", true), SectionSpec("quick", false))
        val s = HomeSections.from(saved, listOf(PanelSpec("photos", "wide")))
        assertEquals(listOf(SHELF, QUICK, NOW_PLAYING, TRANSFERS, CLIPBOARD, PHOTOS, FIND_MAC), s.order)
        assertFalse(s.isOn(QUICK))
        assertTrue(s.isOn(SHELF))
        // Sections the saved list doesn't name come back at their default visibility.
        assertTrue(s.isOn(NOW_PLAYING))
        assertFalse(s.isOn(PHOTOS))
    }

    @Test fun savedListDropsUnknownAndDuplicates() {
        val saved = listOf(SectionSpec("widgets_v9", true), SectionSpec("transfers", false), SectionSpec("transfers", true))
        val s = HomeSections.from(saved)
        assertEquals(TRANSFERS, s.order.first())
        assertFalse(s.isOn(TRANSFERS))
        assertEquals(HomeSection.entries.size, s.order.size)
        assertEquals(HomeSection.entries.toSet(), s.order.toSet())
    }

    @Test fun migratesThe053DefaultLayout() {
        // The 0.5.3 default, as saved after any edit.
        val legacy = listOf(
            PanelSpec("send_clipboard", "small"), PanelSpec("send_file", "small"), PanelSpec("shelf", "wide"),
            PanelSpec("mac", "small"), PanelSpec("clipboard", "small"), PanelSpec("now_playing", "wide"),
            PanelSpec("transfers", "wide"), PanelSpec("photos", "wide"),
        )
        val s = HomeSections.from(null, legacy)
        assertEquals(listOf(QUICK, SHELF, CLIPBOARD, NOW_PLAYING, TRANSFERS, PHOTOS, FIND_MAC), s.order)
        assertEquals(listOf(QUICK, SHELF, CLIPBOARD, NOW_PLAYING, TRANSFERS, PHOTOS), s.visible)
        assertFalse(s.isOn(FIND_MAC))
    }

    @Test fun migrationKeepsWhatTheUserRemoved() {
        val s = HomeSections.migrate(listOf(PanelSpec("transfers", "large"), PanelSpec("hotspot", "small"), PanelSpec("mac", "wide")))
        assertEquals(listOf(TRANSFERS, QUICK), s.visible)
        assertEquals(listOf(TRANSFERS, QUICK, NOW_PLAYING, SHELF, CLIPBOARD, PHOTOS, FIND_MAC), s.order)
        assertEquals(listOf(HomeRow.Single(TRANSFERS), HomeRow.QuickOnly), s.rows())
    }

    @Test fun emptyLegacyLayoutMigratesToAllHidden() {
        val s = HomeSections.from(null, emptyList())
        assertTrue(s.visible.isEmpty())
        assertTrue(s.rows().isEmpty())
        assertEquals(HomeSection.entries.size, s.order.size)
    }

    @Test fun unknownLegacyKindsAreSkipped() {
        val s = HomeSections.migrate(listOf(PanelSpec("weather", "wide"), PanelSpec("shelf", "huge"), PanelSpec("shelf", "wide")))
        assertEquals(listOf(SHELF), s.visible)
    }

    @Test fun toggleSetAndMove() {
        var s = HomeSections.DEFAULT
        s = s.toggle(PHOTOS)
        assertTrue(s.isOn(PHOTOS))
        s = s.set(PHOTOS, false).set(QUICK, false)
        assertFalse(s.isOn(PHOTOS))
        assertEquals(listOf(HomeRow.PlayerOnly, HomeRow.Single(TRANSFERS), HomeRow.Single(SHELF)), s.rows())
        s = s.set(QUICK, true).move(SHELF, 0)
        assertEquals(SHELF, s.order.first())
        // Out of range never throws: clamped or ignored.
        assertEquals(s, s.move(-1, 3))
        assertEquals(s, s.move(99, 0))
        assertEquals(SHELF, s.move(0, 99).order.last())
        assertEquals(s.order, s.move(2, 2).order)
    }

    @Test fun quickAndPlayerMergeOnlyWhenAdjacent() {
        val swapped = HomeSections.DEFAULT.move(NOW_PLAYING, 0)
        assertEquals(HomeRow.Control, swapped.rows().first())
        val apart = HomeSections.DEFAULT.move(NOW_PLAYING, 2)
        assertEquals(listOf(HomeRow.QuickOnly, HomeRow.Single(TRANSFERS), HomeRow.PlayerOnly, HomeRow.Single(SHELF)), apart.rows())
        // A hidden section between them doesn't separate them.
        val gap = HomeSections.DEFAULT.move(PHOTOS, 1)
        assertEquals(HomeRow.Control, gap.rows().first())
    }

    @Test fun specsRoundTripThroughSettingsJson() {
        val s = HomeSections.DEFAULT.toggle(FIND_MAC).move(SHELF, 1)
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(),
            AppSettings(homeSections = s.specs())))
        assertEquals(s, HomeSections.from(back.homeSections, back.homePanels))
        // Settings written by 0.5.3 (no homeSections key) still read.
        val old = json.decodeFromString(AppSettings.serializer(), """{"homePanels":[{"kind":"shelf","size":"wide"}]}""")
        assertNull(old.homeSections)
        assertEquals(listOf(SHELF), HomeSections.from(old.homeSections, old.homePanels).visible)
    }

    @Test fun headerLineCoversEveryState() {
        assertEquals(HeaderLine("Connected · Wi-Fi · 82%", HeaderLine.Dot.FILLED, false), HeaderLine.of(true, false, true, 82, false))
        assertEquals(HeaderLine("Connected · Bluetooth · 82%", HeaderLine.Dot.FILLED, false), HeaderLine.of(true, false, false, 82, true))
        assertEquals("Connected · Wi-Fi", HeaderLine.of(true, false, true, null, false).text)
        assertEquals("Connected · Wi-Fi · 100%", HeaderLine.of(true, false, true, 140, false).text)
        assertEquals(HeaderLine("Connecting…", HeaderLine.Dot.PULSE, false), HeaderLine.of(false, true, false, 50, true))
        assertEquals(HeaderLine("Not connected", HeaderLine.Dot.HOLLOW, true), HeaderLine.of(false, false, false, 82, true))
        assertEquals(HeaderLine("Not connected · Bluetooth off", HeaderLine.Dot.HOLLOW, false),
            HeaderLine.of(false, false, false, null, false, "Bluetooth off"))
    }

    @Test fun photoBadge() {
        assertEquals(0, PhotoBadge.count(listOf(5, 6, 7), 0))
        assertEquals(2, PhotoBadge.count(listOf(9, 8, 7), 7))
        assertNull(PhotoBadge.label(0))
        assertEquals("2", PhotoBadge.label(2))
        assertEquals("9+", PhotoBadge.label(14))
    }

    @Test fun dropIndexIsAlwaysInRange() {
        val row = 100f
        assertEquals(-1, dropIndex(-1, 50f, row, 7))
        assertEquals(-1, dropIndex(0, 50f, row, 0))
        assertEquals(-1, dropIndex(0, 50f, 0f, 7))
        assertEquals(2, dropIndex(2, 40f, row, 7))
        assertEquals(3, dropIndex(2, 60f, row, 7))
        assertEquals(0, dropIndex(2, -100_000f, row, 7))
        assertEquals(6, dropIndex(2, 100_000f, row, 7))
        assertEquals(6, dropIndex(2, Float.MAX_VALUE, row, 7))
    }

    @Test fun squareLabelStopsGrowingAt115Percent() {
        assertEquals(15f, squareLabelSp(80.dp, 1f).value, 0.01f)
        assertEquals(15f * 1.15f / 1.3f, squareLabelSp(80.dp, 1.3f).value, 0.01f)
        assertEquals(13f, squareLabelSp(60.dp, 1f).value, 0.01f)
        assertEquals(15f, squareLabelSp(80.dp, 0f).value, 0.01f)
    }

    @Test fun clipLabelsNeverContainTheText() {
        assertEquals("Phone number", ClipLabel.describe("+91 98765 43210"))
        assertEquals("Phone number", ClipLabel.describe("+917554626501"))
        assertEquals("Code · 6 digits", ClipLabel.describe("482913"))
        assertEquals("Link", ClipLabel.describe("https://forms.gle/x7Tq2"))
        assertEquals("Text · 12 characters", ClipLabel.describe("See you at 6"))
        assertEquals("Text · 2 lines", ClipLabel.describe("a\nb"))
        assertEquals("Empty", ClipLabel.describe("  "))
        for (t in listOf("hunter2", "1 Market St", "forms.gle/x7Tq2")) assertFalse(ClipLabel.describe(t).contains(t))
    }

    @Test fun videoDetection() {
        assertTrue(isVideo("video/mp4", "x"))
        assertTrue(isVideo(null, "VID_20261011_0812.MP4"))
        assertFalse(isVideo("application/pdf", "notes.pdf"))
    }
}
