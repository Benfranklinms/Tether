package dev.benfranklin.tether

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.benfranklin.tether.data.HomeSection
import dev.benfranklin.tether.data.HomeSections
import dev.benfranklin.tether.data.SettingsRepository
import dev.benfranklin.tether.ui.screens.HomeActions
import dev.benfranklin.tether.ui.screens.HomeData
import dev.benfranklin.tether.ui.screens.HomeModel
import dev.benfranklin.tether.ui.screens.HomeScreen
import dev.benfranklin.tether.ui.theme.TetherTheme
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
 * 0.5.4 ("can't even add a panel" on 0.5.3): Edit Home changes reach Home at once and survive a
 * restart (a new SettingsRepository reading settings.json, as after the app is killed), and old or
 * broken saved layouts never crash or wipe the other settings.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi", application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomePersistenceTest {
    @get:Rule val compose = createComposeRule()

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val file: File get() = File(ctx.filesDir, "settings.json")

    @Before fun clean() {
        file.delete()
    }

    private fun sectionsOf(repo: SettingsRepository) = HomeSections.from(repo.current.homeSections, repo.current.homePanels)

    /** The same save MainActivity does in HomeActions.sectionsChanged. */
    private fun save(repo: SettingsRepository, s: HomeSections) = repo.update { it.copy(homeSections = s.specs(), homePanels = null) }

    @Test fun addRemoveReorderThenRestart() {
        val repo = SettingsRepository(ctx)
        assertEquals(HomeSections.DEFAULT, sectionsOf(repo))
        save(repo, sectionsOf(repo).set(HomeSection.CLIPBOARD, true)) // add
        save(repo, sectionsOf(repo).set(HomeSection.SHELF, false)) // remove
        save(repo, sectionsOf(repo).move(HomeSection.TRANSFERS, 0)) // reorder
        val expected = sectionsOf(repo)
        val restarted = SettingsRepository(ctx)
        assertEquals(expected, sectionsOf(restarted))
        assertEquals(HomeSection.TRANSFERS, sectionsOf(restarted).order.first())
        assertTrue(sectionsOf(restarted).isOn(HomeSection.CLIPBOARD))
        assertFalse(sectionsOf(restarted).isOn(HomeSection.SHELF))
    }

    /** Through the real UI: Edit Home sheet → toggle on/off, drag, Done; Home updates at once; then restart. */
    @Test fun editHomeSheetAddRemoveReorderThenRestart() {
        val repo = SettingsRepository(ctx)
        compose.setContent {
            val settings by repo.settings.collectAsState()
            val sections = remember(settings.homeSections, settings.homePanels) {
                HomeSections.from(settings.homeSections, settings.homePanels)
            }
            TetherTheme {
                HomeScreen(
                    HomeModel("MacBook Air", "", true, false, true, null, null, emptyList(), false, now = 1_760_000_000_000L),
                    sections,
                    object : HomeActions {
                        override fun sectionsChanged(sections: HomeSections) = save(repo, sections)
                    },
                    HomeData(),
                )
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Clipboard history").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("home-edit").performClick()
        GestureScenarios.settle(compose)
        // Driven through semantics (Robolectric doesn't hit-test the animated sheet; the emulator
        // GestureDeviceTest does the same with real touches).
        compose.onNodeWithTag("edit-row-clipboard").performSemanticsAction(SemanticsActions.OnClick) // add
        compose.onNodeWithTag("edit-row-shelf").performSemanticsAction(SemanticsActions.OnClick) // remove
        repeat(3) { GestureScenarios.customAction(compose, "Move Recent transfers up") } // reorder: Transfers to the top
        compose.waitForIdle()
        assertTrue(sectionsOf(repo).isOn(HomeSection.CLIPBOARD))
        compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick)
        GestureScenarios.settle(compose)
        // On Home immediately, without leaving the screen.
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Clipboard history"))
        compose.onNodeWithText("Clipboard history").assertExists()
        assertTrue(compose.onAllNodesWithText("Shelf").fetchSemanticsNodes().isEmpty())
        val saved = sectionsOf(repo)
        assertEquals(HomeSection.TRANSFERS, saved.order.first())
        // Restart.
        assertEquals(saved, sectionsOf(SettingsRepository(ctx)))
    }

    @Test fun migrates053PanelsAndKeepsOtherSettings() {
        file.writeText(
            """{"onboardingDone":true,"shareSms":false,"homePanels":[{"kind":"send_clipboard","size":"small"},""" +
                """{"kind":"transfers","size":"large"},{"kind":"mac","size":"small"},{"kind":"photos","size":"wide"}]}""",
        )
        val repo = SettingsRepository(ctx)
        assertTrue(repo.current.onboardingDone)
        assertFalse(repo.current.shareSms)
        val s = sectionsOf(repo)
        assertEquals(listOf(HomeSection.QUICK, HomeSection.TRANSFERS, HomeSection.PHOTOS), s.visible)
        // First change writes the new format and drops the old one.
        save(repo, s.set(HomeSection.SHELF, true))
        val after = SettingsRepository(ctx)
        assertEquals(null, after.current.homePanels)
        assertTrue(sectionsOf(after).isOn(HomeSection.SHELF))
    }

    @Test fun corruptOrUnknownLayoutsFallBackWithoutWipingSettings() {
        val cases = listOf(
            """"homePanels":"garbage"""",
            """"homePanels":42""",
            """"homePanels":{"kind":"shelf"}""",
            """"homeSections":"x"""",
            """"homeSections":[1,null,"quick"]""",
            """"homeSections":{"id":"quick","on":true}""",
            """"homeSections":[{"id":"quick"},{"on":true},{"id":5,"on":"yes"}]""",
            """"homeSections":null,"homePanels":null""",
        )
        for (c in cases) {
            file.writeText("""{"onboardingDone":true,"allowRing":false,$c}""")
            val repo = SettingsRepository(ctx)
            assertTrue("settings wiped by $c", repo.current.onboardingDone)
            assertFalse("settings wiped by $c", repo.current.allowRing)
            assertEquals("layout for $c", HomeSections.DEFAULT, sectionsOf(repo))
        }
        // Unknown section ids from a newer build are skipped, known ones kept.
        file.writeText("""{"homeSections":[{"id":"weather","on":true},{"id":"shelf","on":false},{"id":"quick","on":true}]}""")
        val s = sectionsOf(SettingsRepository(ctx))
        assertEquals(HomeSection.SHELF, s.order.first())
        assertFalse(s.isOn(HomeSection.SHELF))
        // Garbage inside a 0.5.3 list: bad entries skipped (a bad size alone doesn't drop the panel), the rest migrated.
        file.writeText("""{"homePanels":[7,{"size":"wide"},{"kind":"shelf","size":9},{"kind":"now_playing","size":"wide"}]}""")
        assertEquals(listOf(HomeSection.SHELF, HomeSection.NOW_PLAYING), sectionsOf(SettingsRepository(ctx)).visible)
    }
}
