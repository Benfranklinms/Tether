package dev.benfranklin.tether

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.ui.LocalSharedScopes
import dev.benfranklin.tether.ui.SharedScopes
import dev.benfranklin.tether.ui.screens.ShelfActions
import dev.benfranklin.tether.ui.screens.ShelfFilesUi
import dev.benfranklin.tether.ui.screens.ShelfScreen
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.TetherTheme

/**
 * 0.5.4 Shelf folders: drag a row onto a chip, the Move to sheet, delete a folder (from its chip
 * and from its own page), and toggling the add sheet while the Shelf sits inside the app-level
 * SharedTransitionLayout (the 0.5.4 launch crash "layouts are not part of the same hierarchy"
 * came from a nested one). Shared by Robolectric (GestureCrashTest) and the emulator
 * (GestureDeviceTest). Nothing shared (sharedElement / sharedBounds) may run inside a popup,
 * dialog or sheet: these scenarios open each of them with the shared scope live.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
object ShelfGestureScenarios {
    private const val NOW = 1_760_000_000_000L

    private val folders = listOf(
        ShelfFolder("f1", "Work", 1.0, NOW),
        ShelfFolder("f2", "Trip", 2.0, NOW),
    )

    private val items = listOf(
        ShelfItem("s1", "link", "Event registration", "https://forms.gle/x7Tq2", null, "phone", NOW - 60_000, NOW, -3.0),
        ShelfItem("s2", "text", "Note", "Buy milk", null, "mac", NOW - 120_000, NOW, -2.0),
        ShelfItem("s3", "text", "Agenda", "Monday", null, "mac", NOW - 180_000, NOW, -1.0, folderId = "f1"),
    )

    /** Records what the screen asked for. */
    class Recorder : ShelfActions {
        val moves = mutableListOf<Pair<String, String?>>()
        val deleted = mutableListOf<String>()
        val created = mutableListOf<String>()
        var opened = 0

        override fun addText(text: String) = Unit
        override fun addFile() = Unit
        override fun pasteText(): String? = null
        override fun remove(id: String): ShelfItem? = null
        override fun restore(item: ShelfItem) = Unit
        override fun move(id: String, to: Int) = Unit
        override fun primary(item: ShelfItem) = "Copied"
        override fun copy(item: ShelfItem) = "Copied"
        override fun open(id: String) {
            opened++
        }
        override fun moveTo(itemId: String, folderId: String?): Boolean {
            moves += itemId to folderId
            return true
        }
        override fun deleteFolder(id: String) {
            deleted += id
        }
        override fun createFolder(name: String): String {
            created += name
            return "new-${created.size}"
        }
        override suspend fun thumbnail(item: ShelfItem): androidx.compose.ui.graphics.ImageBitmap? = null
    }

    @Composable
    private fun Hosted(content: @Composable () -> Unit) {
        TetherTheme {
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

    private fun show(rule: ComposeContentTestRule, a: Recorder, folderId: String? = null, connected: Boolean = true) {
        rule.setContent {
            Hosted {
                ShelfScreen(
                    items, connected = connected, a = a, onBack = {}, files = ShelfFilesUi(connected = connected),
                    now = NOW, folders = folders, folderId = folderId,
                )
            }
        }
        rule.waitForIdle()
    }

    /** Hold a row, drag it up onto the "Work" chip, let go: the item moves into that folder. */
    fun dragOntoChip(rule: ComposeContentTestRule): Recorder {
        val a = Recorder()
        show(rule, a)
        val chip = rule.onNodeWithContentDescription("Folder Work", substring = true).fetchSemanticsNode().boundsInRoot
        val rowNode = rule.onNodeWithContentDescription("Event registration", substring = true)
        val row = rowNode.fetchSemanticsNode().boundsInRoot
        val delta = chip.center - row.center
        rowNode.performTouchInput {
            down(center)
            advanceEventTime(1_000)
            val steps = 24
            repeat(steps) {
                moveBy(Offset(delta.x / steps, delta.y / steps))
                advanceEventTime(16)
            }
            advanceEventTime(200)
            up()
        }
        rule.waitForIdle()
        check(a.moves == listOf("s1" to "f1")) { "drop on chip moved ${a.moves}" }
        check(a.opened == 0) { "dropping a row opened it" }
        return a
    }

    /** Hold a row › Move to… › pick "Trip" in the sheet. Then the sheet's New folder path. */
    fun moveSheet(rule: ComposeContentTestRule): Recorder {
        val a = Recorder()
        show(rule, a)
        rule.onNodeWithContentDescription("Note", substring = true).performTouchInput { longClick(durationMillis = 1_200) }
        rule.waitForIdle()
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Move to").assertExists()
        rule.onNodeWithContentDescription("Trip").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        check(a.moves == listOf("s2" to "f2")) { "Move to sheet moved ${a.moves}" }
        // Again, this time into a new folder made from the sheet.
        rule.onNodeWithContentDescription("Note", substring = true).performTouchInput { longClick(durationMillis = 1_200) }
        rule.waitForIdle()
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New folder").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Folder name").performTextInput("Receipts")
        rule.onNodeWithText("Create").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        check(a.created == listOf("Receipts")) { "created ${a.created}" }
        check(a.moves.last() == ("s2" to "new-1")) { "new folder move ${a.moves}" }
        return a
    }

    /** Delete a folder from its chip's menu, then from its own page's "…" menu. Items are never removed. */
    fun deleteFolder(rule: ComposeContentTestRule): Recorder {
        val a = Recorder()
        show(rule, a)
        rule.onNodeWithContentDescription("Folder Work", substring = true).performTouchInput { longClick(durationMillis = 1_200) }
        rule.waitForIdle()
        rule.onNodeWithText("Delete folder").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Its item moves back to your Shelf. Nothing is removed.").assertExists()
        rule.onNodeWithText("Delete folder").performClick()
        rule.waitForIdle()
        check(a.deleted == listOf("f1")) { "deleted ${a.deleted}" }
        return a
    }

    fun deleteFolderFromItsPage(rule: ComposeContentTestRule): Recorder {
        val a = Recorder()
        show(rule, a, folderId = "f1")
        rule.onNodeWithContentDescription("Folder options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete folder").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete folder").performClick()
        rule.waitForIdle()
        check(a.deleted == listOf("f1")) { "deleted ${a.deleted}" }
        return a
    }

    /**
     * The Shelf inside the app-level shared scope: open and close the add sheet several times (the
     * + ⇄ sheet morph), on the Shelf and inside a folder, plus the Move sheet in between.
     */
    fun toggleAddSheet(rule: ComposeContentTestRule) {
        val a = Recorder()
        var folder by mutableStateOf<String?>(null)
        rule.setContent {
            Hosted {
                ShelfScreen(items, connected = true, a = a, onBack = {}, now = NOW, folders = folders, folderId = folder)
            }
        }
        rule.waitForIdle()
        repeat(3) {
            rule.onNodeWithContentDescription("Add to Shelf").performClick()
            rule.mainClock.advanceTimeBy(120) // mid-morph
            rule.waitForIdle()
            androidx.test.espresso.Espresso.pressBack()
            rule.waitForIdle()
        }
        rule.onNodeWithContentDescription("Note", substring = true).performTouchInput { longClick(durationMillis = 1_200) }
        rule.waitForIdle()
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        folder = "f1"
        rule.waitForIdle()
        repeat(2) {
            rule.onNodeWithContentDescription("Add to Work").performClick()
            rule.waitForIdle()
            androidx.test.espresso.Espresso.pressBack()
            rule.waitForIdle()
        }
    }
}
