package dev.benfranklin.tether

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * 0.5.3 crash ("when I hold things I exit the app"): long-press, hold-and-drag and edit mode on
 * Home panels, and long-press / drag on Shelf and Transfers rows, on the JVM. The same
 * scenarios run on the CI emulator (androidTest GestureDeviceTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi", application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GestureCrashTest {
    @get:Rule val compose = createComposeRule()

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
