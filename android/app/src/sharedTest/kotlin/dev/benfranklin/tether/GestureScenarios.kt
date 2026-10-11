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
import dev.benfranklin.tether.core.proto.MacMedia
import dev.benfranklin.tether.data.HomeSection
import dev.benfranklin.tether.data.HomeSections
import dev.benfranklin.tether.ui.screens.EDIT_ROW
import dev.benfranklin.tether.ui.screens.EditHomeContent
import dev.benfranklin.tether.v7.MacStatusUi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.performSemanticsAction
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

    private fun model(connected: Boolean, connecting: Boolean = false) = HomeModel(
        title = "MacBook Air", subtitle = "", connected = connected, connecting = connecting,
        searching = !connected, wifi = connected, error = null, notice = null, mirroringOff = false, now = NOW,
        transfers = transfers, canReconnect = !connected && !connecting,
    )

    private fun data(empty: Boolean) = if (empty) {
        HomeData()
    } else {
        HomeData(
            shelf = shelf, clipboard = "+91 98765 43210",
            clips = listOf(ClipRecents.Entry("Meet at 6:30 near gate", NOW, false), ClipRecents.Entry("See you at 6", NOW - 60_000, true)),
            mac = MacStatusUi(82, false, MacMedia(true, "Nights", "Frank Ocean", "Music", true, 307_000, 102_000), NOW),
            hotspotState = "on", newPhotos = 2,
        )
    }

    /** Taps that reached Home's actions (to check nothing fires when it shouldn't). */
    var taps = mutableListOf<String>()

    /** Hosts Home (0.5.4); returns a getter for the last sections Home saved. */
    fun home(
        rule: ComposeContentTestRule,
        connected: Boolean = true,
        empty: Boolean = false,
        connecting: Boolean = false,
        start: HomeSections = HomeSections.DEFAULT,
    ): () -> HomeSections {
        var saved = start
        taps = mutableListOf()
        rule.setContent {
            var sections by remember { mutableStateOf(start) }
            Hosted {
                HomeScreen(
                    model(connected, connecting), sections,
                    object : HomeActions {
                        override fun sectionsChanged(sections2: HomeSections) {
                            saved = sections2
                            sections = sections2
                        }
                        override fun sendClipboard() { taps += "clipboard" }
                        override fun sendFile() { taps += "file" }
                        override fun openHotspot() { taps += "hotspot" }
                        override fun pickPhotos() { taps += "photos" }
                        override fun mediaCommand(cmd: String) { taps += "media:$cmd" }
                        override fun reconnect() { taps += "reconnect" }
                        override fun openShelf() { taps += "shelf" }
                        override fun seeAllTransfers() { taps += "transfers" }
                    },
                    data(empty),
                )
            }
        }
        rule.waitForIdle()
        return { saved }
    }

    private fun ComposeContentTestRule.node(desc: String) = onAllNodesWithContentDescription(desc, substring = true).onFirst()

    private val homeControls = listOf("Send clipboard", "Send file", "Hotspot", "Photos", "Settings")

    /** Hold (long-press) every Home control: Home has no long-press mode, so nothing appears or breaks. */
    fun holdEveryPanel(rule: ComposeContentTestRule) {
        for (d in homeControls) {
            rule.node(d).performTouchInput { longClick(durationMillis = 1_200) }
            rule.waitForIdle()
            check(rule.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty()) { "a hold on $d opened an edit mode" }
        }
        rule.onNodeWithText("Nights").assertExists()
    }

    /** Hold then drag far beyond the screen, in both directions, on controls and rows. */
    fun holdAndDrag(rule: ComposeContentTestRule) {
        for (d in listOf("Send clipboard", "Photos")) {
            // A drag on a button scrolls the list; bring the control back before holding it (small emulator screens).
            rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(d, substring = true))
            rule.node(d).performTouchInput {
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
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Edit Home"))
        rule.onNodeWithText("Edit Home").performTouchInput {
            down(center)
            advanceEventTime(900)
            repeat(40) {
                moveBy(Offset(0f, -80f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
    }

    /** Disconnected: Send clipboard is dimmed and inert, File still queues, Reconnect is offered. */
    fun disconnectedControls(rule: ComposeContentTestRule) {
        rule.onNodeWithText("Not connected", substring = true).assertExists()
        rule.node("Send clipboard").performClick()
        rule.node("Send file").performClick()
        rule.waitForIdle()
        check("clipboard" !in taps) { "Send clipboard fired while disconnected" }
        check("file" in taps) { "Send file should still queue while disconnected" }
        rule.onNodeWithText("Mac not connected").assertExists()
        rule.node("Reconnect").performClick()
        rule.waitForIdle()
        check("reconnect" in taps) { "Reconnect did nothing" }
    }

    /** Home never shows what's on the clipboard (a phone number sits on it in [data]). */
    fun noClipboardText(rule: ComposeContentTestRule) {
        // The phone clipboard and the clipboard history texts: none may appear as text or in semantics.
        for (raw in listOf("98765", "Meet at 6:30", "See you at 6")) {
            check(rule.onAllNodesWithText(raw, substring = true).fetchSemanticsNodes().isEmpty()) { "clipboard text \"$raw\" on Home" }
            check(rule.onAllNodesWithContentDescription(raw, substring = true).fetchSemanticsNodes().isEmpty()) {
                "clipboard text \"$raw\" in Home semantics"
            }
        }
    }

    /** Edit Home: open the sheet, toggle sections, drag-reorder (incl. rapid flicks and two fingers), Move up/down, Done. */
    /**
     * [touch] = real touches inside the sheet (the emulator). Robolectric doesn't hit-test the sheet
     * reliably while it animates in (taps landed on the scrim behind it although the same test passes
     * on the emulator), so on the JVM the sheet is driven through its semantics actions instead: the
     * same toggles and the Move up / Move down actions; touch reordering on the JVM is covered by
     * [editContent] + [editSheetGestures] on the list itself.
     */
    fun editMode(rule: ComposeContentTestRule, saved: () -> HomeSections, touch: Boolean = true) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Edit Home"))
        rule.onNodeWithText("Edit Home").performClick()
        settle(rule)
        rule.onNodeWithText("Done").assertExists()
        if (touch) {
            editSheetGestures(rule, saved)
        } else {
            editSheetBySemantics(rule, saved)
        }
        val done = rule.onNodeWithText("Done")
        if (touch) done.performClick() else done.performSemanticsAction(SemanticsActions.OnClick)
        settle(rule)
        check(rule.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty()) { "Done did not close the sheet" }
        // The toggled sections are on Home now, without leaving the screen.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Clipboard history"))
        rule.onNodeWithText("Clipboard history").assertExists()
    }

    /** Toggle and reorder through the sheet's semantics (what TalkBack uses). */
    fun editSheetBySemantics(rule: ComposeContentTestRule, saved: () -> HomeSections) {
        rule.onNodeWithTag("edit-row-clipboard").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        check(saved().isOn(HomeSection.CLIPBOARD)) { "Clipboard history toggle did not save" }
        rule.onNodeWithTag("edit-row-photos").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("edit-row-photos").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        check(!saved().isOn(HomeSection.PHOTOS)) { "Photos double toggle should be off" }
        repeat(3) { customAction(rule, "Move Shelf up") }
        check(saved().order.first() == HomeSection.SHELF) { "Move up did not move Shelf to the top: ${saved().order}" }
        // Past the top: no-op, never out of range.
        customAction(rule, "Move Shelf up")
        customAction(rule, "Move Find my Mac down")
        check(saved().order.size == HomeSection.entries.size) { "moves broke the list" }
    }

    /** Runs the accessibility custom action labelled [label] on the first node that has it. */
    fun customAction(rule: ComposeContentTestRule, label: String) {
        val matcher = SemanticsMatcher("has custom action \"$label\"") { n ->
            n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
        }
        val node = rule.onAllNodes(matcher, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
        rule.runOnIdle { action.action() }
        rule.waitForIdle()
    }

    /** Lets the sheet's spring finish (the test clock doesn't always run it to rest on waitForIdle). */
    fun settle(rule: ComposeContentTestRule) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private const val SETTLE_MS = 1_500L

    /** The Edit Home list on its own (no modal window), as the sheet hosts it. */
    fun editContent(rule: ComposeContentTestRule): () -> HomeSections {
        var saved = HomeSections.DEFAULT
        rule.setContent {
            var sections by remember { mutableStateOf(HomeSections.DEFAULT) }
            Hosted {
                EditHomeContent(sections, onChange = {
                    saved = it
                    sections = it
                }, onDone = {})
            }
        }
        rule.waitForIdle()
        return { saved }
    }

    fun editSheetGestures(rule: ComposeContentTestRule, saved: () -> HomeSections) {
        // Toggles.
        rule.onNodeWithTag("edit-row-clipboard").performClick()
        rule.waitForIdle()
        check(saved().isOn(HomeSection.CLIPBOARD)) { "Clipboard history toggle did not save" }
        rule.onNodeWithTag("edit-row-photos").performClick()
        rule.onNodeWithTag("edit-row-photos").performClick()
        rule.waitForIdle()
        check(!saved().isOn(HomeSection.PHOTOS)) { "Photos double toggle should be off" }
        // Drag Shelf (4th) to the top.
        val rowPx = with(rule.density) { EDIT_ROW.toPx() }
        rule.onNodeWithTag("edit-handle-shelf").performTouchInput {
            down(center)
            repeat(30) {
                moveBy(Offset(0f, -rowPx * 3 / 30f - 1f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        check(saved().order.first() == HomeSection.SHELF) { "drag did not move Shelf to the top: ${saved().order}" }
        // Rapid flicks far past both ends: never out of range, every section still there once.
        repeat(6) { n ->
            rule.onNodeWithTag("edit-handle-transfers").performTouchInput {
                down(center)
                moveBy(Offset(0f, if (n % 2 == 0) 50_000f else -50_000f))
                moveBy(Offset(0f, if (n % 2 == 0) -120_000f else 120_000f))
                up()
            }
        }
        rule.waitForIdle()
        check(saved().order.toSet() == HomeSection.entries.toSet() && saved().order.size == HomeSection.entries.size) {
            "flicks lost or duplicated a section: ${saved().order}"
        }
        // Two fingers on two handles at once: only the first drives; nothing crashes.
        val before = saved().order
        rule.onNodeWithTag("edit-handle-quick").performTouchInput {
            down(0, center)
            down(1, center + Offset(0f, rowPx * 2))
            repeat(10) {
                moveBy(0, Offset(0f, rowPx / 5))
                moveBy(1, Offset(0f, -rowPx / 5))
                advanceEventTime(16)
            }
            up(1)
            up(0)
        }
        rule.waitForIdle()
        check(saved().order.size == before.size) { "multi-touch broke the list" }
        // A drag the system cancels ends cleanly: the list stays whole and the row isn't left lifted (toggles work below).
        rule.onNodeWithTag("edit-handle-photos").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -rowPx / 4)) }
            cancel()
        }
        rule.waitForIdle()
        check(saved().order.toSet().size == HomeSection.entries.size) { "a cancelled drag broke the list" }
        // Toggling while nothing is dragged still works after all that.
        rule.onNodeWithTag("edit-row-find_mac").performClick()
        rule.waitForIdle()
        check(saved().isOn(HomeSection.FIND_MAC)) { "toggle after drags did not save" }
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
