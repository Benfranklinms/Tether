package dev.benfranklin.tether

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.5.3 crash ("when I hold things I exit the app") on a real Android image: the same long-press,
 * hold-and-drag and edit-mode scenarios as the Robolectric GestureCrashTest, with real
 * RenderNodes, haptics and touch dispatch.
 */
@RunWith(AndroidJUnit4::class)
class GestureDeviceTest {
    @get:Rule val compose = createComposeRule()

    // The CI emulator runs with animations off (animator scale 0), which makes every spring jump
    // to its end and hides overshoot bugs. Ben's phone animates, so these tests do too.
    @Before fun animationsOn() = shell("settings put global animator_duration_scale 1")

    @After fun animationsOff() = shell("settings put global animator_duration_scale 0")

    private fun shell(cmd: String) {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    @Test fun holdEveryHomePanel() = GestureScenarios.holdEveryPanel(compose.also { GestureScenarios.home(it) })

    @Test fun holdAndDragHomePanels() = GestureScenarios.holdAndDrag(compose.also { GestureScenarios.home(it) })

    @Test fun holdAndDragWhileDisconnectedAndEmpty() =
        GestureScenarios.holdAndDrag(compose.also { GestureScenarios.home(it, connected = false, empty = true) })

    @Test fun editHomeSheetToggleAndReorder() {
        val saved = GestureScenarios.home(compose)
        GestureScenarios.editMode(compose, saved)
    }

    @Test fun editHomeListDragRapidAndMultiTouch() {
        val saved = GestureScenarios.editContent(compose)
        GestureScenarios.editSheetGestures(compose, saved)
    }

    @Test fun disconnectedControlsDimmedAndSafe() {
        GestureScenarios.home(compose, connected = false)
        GestureScenarios.disconnectedControls(compose)
        GestureScenarios.holdAndDrag(compose)
    }

    @Test fun connectingHoldAndDrag() {
        GestureScenarios.home(compose, connected = false, connecting = true)
        GestureScenarios.holdAndDrag(compose)
    }

    @Test fun clipboardNeverShownOnHome() {
        GestureScenarios.home(compose)
        GestureScenarios.noClipboardText(compose)
    }

    @Test fun clipboardNeverShownWithEverySectionOn() {
        val all = dev.benfranklin.tether.data.HomeSection.entries
            .fold(dev.benfranklin.tether.data.HomeSections.DEFAULT) { s, x -> s.set(x, true) }
        GestureScenarios.home(compose, start = all)
        GestureScenarios.noClipboardText(compose)
    }

    @Test fun shelfRowsHoldAndDrag() = GestureScenarios.shelf(compose)

    @Test fun transfersRowsHold() = GestureScenarios.transfers(compose)

    // 0.5.4 Shelf folders
    @Test fun shelfDragOntoFolderChip() {
        ShelfGestureScenarios.dragOntoChip(compose)
    }

    @Test fun shelfMoveToSheet() {
        ShelfGestureScenarios.moveSheet(compose)
    }

    @Test fun shelfDeleteFolderFromChip() {
        ShelfGestureScenarios.deleteFolder(compose)
    }

    @Test fun shelfDeleteFolderFromItsPage() {
        ShelfGestureScenarios.deleteFolderFromItsPage(compose)
    }

    @Test fun shelfAddSheetInsideAppSharedScope() = ShelfGestureScenarios.toggleAddSheet(compose)
}
