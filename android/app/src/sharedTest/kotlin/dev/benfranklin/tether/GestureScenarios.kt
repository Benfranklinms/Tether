package dev.benfranklin.tether

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.benfranklin.tether.core.features.ClipRecents
import dev.benfranklin.tether.core.features.HomeLayout
import dev.benfranklin.tether.core.features.PanelKind
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.ui.LocalSharedScopes
import dev.benfranklin.tether.ui.SharedScopes
import dev.benfranklin.tether.ui.screens.HomeActions
import dev.benfranklin.tether.ui.screens.HomeData
import dev.benfranklin.tether.ui.screens.HomeModel
import dev.benfranklin.tether.ui.screens.HomeScreen
import dev.benfranklin.tether.ui.screens.ShelfActions
import dev.benfranklin.tether.ui.screens.ShelfScreen
import dev.benfranklin.tether.ui.screens.TransfersActions
import dev.benfranklin.tether.ui.screens.TransfersScreen
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.TetherTheme

/**
 * Long-press / drag / edit-mode scenarios for Home panels, Shelf rows and Transfers rows (0.5.3
 * crash on Ben's phone: "when I hold things I exit the app"). Shared by the Robolectric test
 * (app/src/test) and the emulator instrumented test (app/src/androidTest), so the same gestures
 * run on the JVM and on a real Android image. Every screen is hosted the way MainActivity hosts
 * it: inside a SharedTransitionLayout with a navigation-like AnimatedVisibility scope, so the
 * shared-element modifiers on the panels are live, and Home's layout callback feeds back into
 * the layout it is given (as the settings store does).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
object GestureScenarios {
    private const val NOW = 1_760_000_000_000L
    private var shelfOpened = 0

    @Composable
    private fun Hosted(content: @Composable () -> Unit) {
        TetherTheme {
            // Full motion, as on Ben's phone: the springs (and their overshoot) and the shared
            // elements only run with reduce-motion off, whatever the emulator's animator scale is.
            CompositionLocalProvider(LocalReduceMotion provides false) {
            SharedTransitionLayout {
                AnimatedVisibility(visible = true) {
                    CompositionLocalProvider(LocalSharedScopes provides SharedScopes(this@SharedTransitionLayout, this)) {
                        content()
                    }
                }
            }
            }
        }
    }

    private fun rec(id: String, dir: Direction, status: TransferStatus) = TransferRecord(
        id = id, direction = dir, name = "$id.pdf", size = 120_000, sha256 = "0".repeat(64), mime = "application/pdf",
        status = status, transferred = if (status == TransferStatus.DONE) 120_000 else 40_000, error = null,
        createdAt = NOW, via = Transport.WIFI,
    )

    private val transfers = listOf(
        rec("a", Direction.OUT, TransferStatus.DONE),
        rec("b", Direction.IN, TransferStatus.DONE),
        rec("c", Direction.OUT, TransferStatus.ACTIVE),
        rec("d", Direction.IN, TransferStatus.FAILED),
    )

    private val shelf = listOf(
        ShelfItem("s1", "link", "Event registration", "https://forms.gle/x7Tq2", null, "phone", NOW - 60_000, NOW, -3.0),
        ShelfItem("s2", "text", "Note", "Buy milk", null, "mac", NOW - 120_000, NOW, -2.0),
        ShelfItem("s3", "wifi", "Studio_5G", "", "pw", "phone", NOW - 3_600_000, NOW, -1.0),
    )

    private fun model(connected: Boolean) = HomeModel(
        title = "MacBook Air", subtitle = if (connected) "Connected · Wi-Fi" else "Looking nearby…", connected = connected,
        searching = !connected, wifi = connected, error = null, notice = null, mirroringOff = false, now = NOW,
        transfers = transfers,
    )

    private fun data(empty: Boolean) = if (empty) {
        HomeData()
    } else {
        HomeData(
            shelf = shelf, clipboard = "+91 98765 43210",
            clips = listOf(ClipRecents.Entry("forms.gle/x7Tq2", NOW, false), ClipRecents.Entry("See you at 6", NOW - 60_000, true)),
        )
    }

    /** Hosts Home; returns a getter for the last layout Home saved. */
    fun home(rule: ComposeContentTestRule, connected: Boolean = true, empty: Boolean = false): () -> HomeLayout {
        var saved = HomeLayout.DEFAULT
        rule.setContent {
            var layout by remember { mutableStateOf(HomeLayout.DEFAULT) }
            Hosted {
                HomeScreen(
                    model(connected), layout,
                    object : HomeActions {
                        override fun layoutChanged(layout2: HomeLayout) {
                            saved = layout2
                            layout = layout2
                        }
                    },
                    data(empty),
                )
            }
        }
        rule.waitForIdle()
        return { saved }
    }

    private fun ComposeContentTestRule.panel(desc: String) = onAllNodesWithContentDescription(desc, substring = true).onFirst()

    /** Long-press (hold, no movement) on each kind of panel: enters edit mode, never crashes. */
    fun holdEveryPanel(rule: ComposeContentTestRule) {
        for (d in listOf("Send clipboard", "Send file", "Open Shelf", "Mac battery")) {
            rule.panel(d).performTouchInput { longClick(durationMillis = 1_200) }
            rule.waitForIdle()
            rule.onNodeWithText("Done").assertExists()
            rule.onNodeWithText("Done").performClick()
            rule.waitForIdle()
        }
    }

    /** Hold then drag (the iOS gesture) without lifting, in both directions, far beyond the grid. */
    fun holdAndDrag(rule: ComposeContentTestRule) {
        rule.panel("Send clipboard").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            for (i in 1..30) {
                moveBy(Offset(12f, 40f))
                advanceEventTime(16)
            }
            for (i in 1..60) {
                moveBy(Offset(-8f, -60f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        rule.panel("Send file").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            for (i in 1..80) {
                moveBy(Offset(0f, 90f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
    }

    /** In edit mode: drag every panel around, cancel a drag mid-way, resize and remove. */
    fun editMode(rule: ComposeContentTestRule, saved: () -> HomeLayout) {
        // Enter edit mode the way people do: hold a panel.
        rule.panel("Send clipboard").performTouchInput { longClick(durationMillis = 1_200) }
        rule.waitForIdle()
        rule.onNodeWithText("Done").assertExists()
        rule.panel("Mac battery").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            repeat(20) {
                moveBy(Offset(0f, -50f))
                advanceEventTime(16)
            }
            cancel()
        }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Resize ", substring = true).onFirst().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(20f, 20f)) }
            repeat(20) { moveBy(Offset(-20f, -20f)) }
            up()
        }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Resize ", substring = true).onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Remove Send file").performClick()
        rule.waitForIdle()
        check(PanelKind.SEND_FILE !in saved()) { "remove did not save" }
        // Hold-drag a panel while panels are removed under it.
        rule.panel("Send clipboard").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            repeat(40) {
                moveBy(Offset(30f, 70f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Done"))
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
    }

    fun shelf(rule: ComposeContentTestRule) {
        shelfOpened = 0
        rule.setContent {
            Hosted {
                ShelfScreen(shelf, connected = true, a = NoShelf, onBack = {}, now = NOW)
            }
        }
        rule.waitForIdle()
        // Hold (menu), then hold-and-drag (reorder) each row.
        rule.onNodeWithContentDescription("Event registration", substring = true).performTouchInput { longClick(durationMillis = 1_200) }
        rule.waitForIdle()
        rule.onNodeWithText("Rename").assertExists()
        check(shelfOpened == 0) { "releasing a hold opened the item" }
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        for (title in listOf("Event registration", "Studio_5G")) {
            rule.onNodeWithContentDescription(title, substring = true).performTouchInput {
                down(center)
                advanceEventTime(1_000)
                repeat(30) {
                    moveBy(Offset(0f, if (title == "Studio_5G") -60f else 60f))
                    advanceEventTime(16)
                }
                up()
            }
            rule.waitForIdle()
        }
    }

    fun transfers(rule: ComposeContentTestRule) {
        rule.setContent {
            Hosted {
                TransfersScreen(transfers, NoTransfers, onBack = {}, now = NOW)
            }
        }
        rule.waitForIdle()
        for (name in listOf("a.pdf", "c.pdf", "d.pdf")) {
            rule.onNodeWithContentDescription(name, substring = true).performTouchInput { longClick(durationMillis = 1_200) }
            rule.waitForIdle()
            androidx.test.espresso.Espresso.pressBack()
            rule.waitForIdle()
        }
        rule.onNodeWithContentDescription("b.pdf", substring = true).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            repeat(20) {
                moveBy(Offset(0f, 50f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
    }

    private object NoShelf : ShelfActions {
        override fun addText(text: String) = Unit
        override fun addFile() = Unit
        override fun pasteText(): String? = null
        override fun remove(id: String): ShelfItem? = null
        override fun restore(item: ShelfItem) = Unit
        override fun move(id: String, to: Int) = Unit
        override fun primary(item: ShelfItem) = "Copied"
        override fun copy(item: ShelfItem) = "Copied"
        override fun open(id: String) {
            shelfOpened++
        }
        override suspend fun thumbnail(item: ShelfItem): androidx.compose.ui.graphics.ImageBitmap? = null
    }

    private object NoTransfers : TransfersActions {
        override fun cancel(id: String) = Unit
        override fun retry(id: String) = Unit
        override fun remove(id: String) = Unit
        override fun clearFinished() = Unit
        override fun open(t: TransferRecord): String? = null
    }
}
