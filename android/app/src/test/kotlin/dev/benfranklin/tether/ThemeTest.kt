package dev.benfranklin.tether

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.data.HomeSections
import dev.benfranklin.tether.data.SettingsRepository
import dev.benfranklin.tether.data.appearance
import dev.benfranklin.tether.data.withAppearance
import dev.benfranklin.tether.ui.middleFit
import dev.benfranklin.tether.ui.screens.AppearanceScreen
import dev.benfranklin.tether.ui.screens.HomeActions
import dev.benfranklin.tether.ui.screens.HomeData
import dev.benfranklin.tether.ui.screens.HomeModel
import dev.benfranklin.tether.ui.screens.HomeScreen
import dev.benfranklin.tether.ui.screens.appearanceSummary
import dev.benfranklin.tether.ui.theme.Appearance
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.TetherTheme
import dev.benfranklin.tether.ui.theme.TextSizes
import dev.benfranklin.tether.ui.theme.ThemeState
import dev.benfranklin.tether.ui.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * 0.5.5: the 18 themes (exact mockup hex), Appearance choices and their persistence, the palette
 * reaching the screens, and Home v2 (app bar Edit + Settings, no bottom pill, Shelf carousel and
 * empty card, larger text at 360 dp).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi", application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        File(ctx.filesDir, "settings.json").delete()
    }

    // ---------------- palettes ----------------

    @Test fun eighteenThemesNineDarkNineLight() {
        assertEquals(18, Themes.all.size)
        assertEquals(18, Themes.all.map { it.id }.toSet().size)
        assertTrue(Themes.dark.none { it.palette.light })
        assertTrue(Themes.light.all { it.palette.light })
        assertEquals(
            listOf("Mono", "OLED Black", "Midnight", "Sand", "Violet", "Ocean", "Rose", "Slate", "Mocha"), Themes.dark.map { it.name },
        )
        assertEquals(
            listOf("Light", "Ice", "Lilac", "Snow", "Paper", "Linen", "Sky", "Blush", "Periwinkle"), Themes.light.map { it.name },
        )
    }

    @Test fun exactMockupHex() {
        // Mono = the kit's base constants.
        assertEquals(Color(0xFF09090A), Themes.MONO.palette.bg)
        assertEquals(Color(0xFF111112), Themes.MONO.palette.surface)
        assertEquals(Color(0xFFE8E8E8), Themes.MONO.palette.accent)
        assertEquals(Color(0xFF48484A), Themes.MONO.palette.t4)
        // kit.PALETTES spot checks.
        assertEquals(Color(0xFF1F7AE0), Themes.SKY.palette.accent)
        assertEquals(Color(0xFFEEF5FC), Themes.SKY.palette.bg)
        assertEquals(Color(0xFFA9BEFF), Themes.MIDNIGHT.palette.accent)
        assertEquals(Color(0xFF000000), Themes.OLED.palette.bg)
        assertEquals(Color(0xFF9A6B3F), Themes.LINEN.palette.accent)
        assertEquals(Color(0xFF5865F2), Themes.PERI.palette.accent)
        assertEquals(Color(0xFFD5C6AE), Themes.LINEN.palette.line3)
        assertEquals(Color(0xFF3A2C22), Themes.MOCHA.palette.surface4)
    }

    @Test fun unknownThemeFallsBackToMono() {
        assertEquals(Themes.MONO, Themes.byId("removed-theme"))
        assertEquals(Themes.MONO, Themes.byId(null))
        assertEquals(Themes.MONO, Appearance(themeId = "nope").resolve(systemDark = false))
    }

    @Test fun followSystemPicksTheLightOrDarkSlot() {
        val a = Appearance(themeId = "rose", followSystem = true, lightId = "paper", darkId = "midnight")
        assertEquals("paper", a.resolve(systemDark = false).id)
        assertEquals("midnight", a.resolve(systemDark = true).id)
        assertEquals("rose", a.copy(followSystem = false).resolve(systemDark = false).id)
        // Default pair: Light and Mono.
        val d = Appearance(followSystem = true)
        assertEquals("light", d.resolve(false).id)
        assertEquals("mono", d.resolve(true).id)
        // A dark theme saved in the light slot (hand-edited file) never shows a dark UI in light mode.
        assertEquals("light", Appearance(followSystem = true, lightId = "mocha").resolve(false).id)
    }

    @Test fun pickingACard() {
        assertEquals("sky", Appearance().pick("sky").themeId)
        val f = Appearance(followSystem = true)
        assertEquals("lilac", f.pick("lilac").lightId)
        assertEquals("ocean", f.pick("ocean").darkId)
        assertTrue(f.pick("ocean").isSelected("ocean"))
        assertTrue(f.pick("ocean").isSelected("light"))
        assertFalse(f.pick("ocean").isSelected("mono"))
    }

    @Test fun textSizes() {
        assertEquals(1f, TextSizes.scale(TextSizes.DEFAULT))
        assertTrue(TextSizes.scale(0) < 1f && TextSizes.scale(3) > TextSizes.scale(2))
        assertEquals(TextSizes.scale(3), TextSizes.scale(99))
        assertEquals("Mono", appearanceSummary(Appearance()))
        assertEquals("Light / Mono · Larger", appearanceSummary(Appearance(followSystem = true, textSize = 3)))
    }

    // ---------------- persistence ----------------

    @Test fun themeSurvivesRestart() {
        val repo = SettingsRepository(ctx)
        assertEquals(Appearance(), repo.current.appearance)
        val chosen = Appearance(themeId = "linen", followSystem = true, lightId = "sky", darkId = "oled", textSize = 2)
        repo.update { it.withAppearance(chosen) }
        assertEquals(chosen, SettingsRepository(ctx).current.appearance)
    }

    /** settings.json from 0.5.4 (no theme keys) reads as the default Mono theme, other settings kept. */
    @Test fun oldSettingsReadAsMono() {
        File(ctx.filesDir, "settings.json").writeText("""{"receiveClipboard":false,"onboardingDone":true}""")
        val repo = SettingsRepository(ctx)
        assertEquals(Appearance(), repo.current.appearance)
        assertFalse(repo.current.receiveClipboard)
    }

    // ---------------- palette applied ----------------

    @Test fun paletteReachesTheScreens() {
        var seen = Color.Unspecified
        compose.setContent { TetherTheme(Appearance(themeId = "sky")) { seen = Mono.Accent; Box(Modifier.size(10.dp)) } }
        compose.waitForIdle()
        assertEquals(Themes.SKY.palette, ThemeState.palette)
        assertEquals(Themes.SKY.palette.accent, seen)
    }

    @Test fun appearanceScreenSavesThePick() {
        val repo = SettingsRepository(ctx)
        compose.setContent {
            val s by repo.settings.collectAsState()
            TetherTheme(s.appearance) {
                AppearanceScreen(s.appearance, onChange = { a -> repo.update { it.withAppearance(a) } }, onBack = {})
            }
        }
        compose.onNodeWithTag("theme-mono").assertIsSelected()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("theme-blush"))
        compose.onNodeWithTag("theme-blush").performClick()
        compose.waitForIdle()
        assertEquals("blush", repo.current.theme)
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.waitForIdle()
        assertEquals(Themes.BLUSH.palette, ThemeState.palette)
        assertEquals("blush", SettingsRepository(ctx).current.theme)
    }

    // ---------------- Home v2 ----------------

    private val now = 1_760_000_000_000L
    private val model = HomeModel("MacBook Air", "", true, false, true, null, null, emptyList(), false, now = now)

    private fun item(id: String, kind: String, title: String, body: String = "", file: ShelfFile? = null, folder: String? = null) =
        ShelfItem(id, kind, title, body, null, "phone", now, now, 0.0, file, folder)

    private val shelf = listOf(
        item("a", "file", "IMG_1.jpg", file = ShelfFile("IMG_1.jpg", 4_000_000, "image/jpeg", "aa", "phone"), folder = "w"),
        item("b", "link", "Titan registration", "https://forms.gle/x"),
        item("c", "file", "offer.pdf", file = ShelfFile("offer.pdf", 80_000, "application/pdf", "bb", "mac")),
        item("d", "snippet", "adb", "adb mdns services"),
    )

    private class Taps : HomeActions {
        val log = mutableListOf<String>()
        override fun settings() { log += "settings" }
        override fun openShelf() { log += "shelf" }
        override fun addToShelf() { log += "add" }
        override fun pasteToShelf() { log += "paste" }
        override fun addFileToShelf() { log += "file" }
        override fun openShelfFolder(id: String) { log += "folder:$id" }
        override fun openShelfItem(id: String) { log += "item:$id" }
    }

    @Test fun appBarHasEditAndSettingsAndNoPill() {
        val a = Taps()
        compose.setContent { TetherTheme(Appearance()) { HomeScreen(model, HomeSections.DEFAULT, a, HomeData(shelf = shelf)) } }
        compose.onNodeWithText("Tether").assertExists()
        compose.onNodeWithContentDescription("Settings").performClick()
        assertTrue("settings" in a.log)
        // The bottom "Edit Home" pill is gone: no such text anywhere until the sheet opens.
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-shelf"))
        assertTrue(compose.onAllNodesWithText("Edit Home").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-edit"))
        compose.onNodeWithTag("home-edit").performClick()
        compose.mainClock.advanceTimeBy(SETTLE)
        compose.onNodeWithTag("edit-sheet").assertExists()
        compose.onNodeWithText("Done").assertExists()
    }

    @Test fun shelfCarouselWithFolders() {
        val a = Taps()
        val folders = listOf(ShelfFolder("w", "Work", 0.0, now))
        compose.setContent {
            TetherTheme(Appearance()) { HomeScreen(model, HomeSections.DEFAULT, a, HomeData(shelf = shelf, folders = folders)) }
        }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-shelf"))
        compose.onNodeWithTag("home-shelf-add").assertExists()
        // A lazy row: the cards that fit on screen are composed (the rest scroll in).
        assertTrue(compose.onAllNodesWithTag("home-shelf-card").fetchSemanticsNodes().size >= 3)
        compose.onNodeWithContentDescription("Folder Work, 1 items").performClick()
        compose.onNodeWithTag("home-shelf-add").performClick()
        compose.onNodeWithContentDescription("Open Shelf").performClick()
        assertEquals(listOf("folder:w", "add", "shelf"), a.log)
        compose.onNodeWithTag("home-shelf").performScrollToNode(hasContentDescription("adb", substring = true))
        compose.onNodeWithContentDescription("adb", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithTag("home-shelf-empty").fetchSemanticsNodes().isEmpty())
    }

    @Test fun emptyShelfCard() {
        val a = Taps()
        compose.setContent { TetherTheme(Appearance()) { HomeScreen(model, HomeSections.DEFAULT, a, HomeData()) } }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-shelf-empty"))
        compose.onNodeWithText("Nothing on your Shelf").assertExists()
        compose.onNodeWithTag("home-shelf-paste").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("home-shelf-addfile").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("paste", "file"), a.log)
        assertTrue(compose.onAllNodesWithTag("home-shelf").fetchSemanticsNodes().isEmpty())
    }

    /** Larger text + 1.3× system font on a 360 dp phone: the app bar and device card stay inside the screen. */
    @Test
    @Config(fontScale = 1.3f, qualifiers = "w360dp-h740dp-night-xxhdpi")
    fun largerTextAt360dp() {
        val mac = dev.benfranklin.tether.v7.MacStatusUi(82, false, null, now)
        compose.setContent {
            TetherTheme(Appearance(textSize = 3)) {
                HomeScreen(model.copy(title = "Alex's MacBook Air (M3, 2024)"), HomeSections.DEFAULT, Taps(), HomeData(shelf = shelf, mac = mac))
            }
        }
        val width = compose.onRoot().fetchSemanticsNode().size.width
        for (tag in listOf("home-settings", "home-edit", "device-card")) {
            val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag runs off a 360 dp screen: ${b.right} > $width", b.right <= width + 0.5f)
            assertTrue("$tag collapsed", b.width > 0f && b.height > 0f)
        }
        compose.onNodeWithText("82%").assertExists()
        val pct = compose.onNodeWithText("82%").fetchSemanticsNode().boundsInRoot
        assertTrue("battery cut off", pct.right <= width + 0.5f)
    }

    @Test fun middleEllipsisCutsOnce() {
        val name = "VID_20261011_0812_long_file_name.mp4"
        val fitted = middleFit(name, 20) { it.length }
        assertEquals(1, fitted.count { it == '…' })
        assertTrue(fitted.endsWith(".mp4"))
        assertTrue(fitted.length <= 20)
        assertEquals("short.mp4", middleFit("short.mp4", 20) { it.length })
    }

    private companion object {
        const val SETTLE = 1_000L
    }
}
