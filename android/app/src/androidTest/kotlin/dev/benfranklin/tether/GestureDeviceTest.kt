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

    @Test fun editModeDragResizeRemove() {
        val saved = GestureScenarios.home(compose)
        GestureScenarios.editMode(compose, saved)
    }

    @Test fun shelfRowsHoldAndDrag() = GestureScenarios.shelf(compose)

    @Test fun transfersRowsHold() = GestureScenarios.transfers(compose)
}
