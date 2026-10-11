package dev.benfranklin.tether

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.benfranklin.tether.core.features.ShelfCache
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.ui.screens.MoveSheet
import dev.benfranklin.tether.ui.screens.ShelfActions
import dev.benfranklin.tether.ui.screens.ShelfFilesUi
import dev.benfranklin.tether.ui.screens.ShelfScreen
import dev.benfranklin.tether.ui.screens.ShelfSettingsScreen
import dev.benfranklin.tether.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.5.4 Shelf folders and offline copies (approved Style A "chips" mockups): the Shelf with its
 * folder chips, inside a folder, the Move to sheet, the Shelf while the Mac is away (cached items
 * open, the rest show the quiet cloud), and Settings › Shelf offline files. Demo data only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi", application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ShelfFolderScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_760_000_000_000L
    private val zone = java.time.ZoneId.of("Asia/Kolkata")

    private fun path(name: String) = File(System.getProperty("tether.screens") ?: "build/screens", "$name.png").path

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { TetherTheme { content() } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onRoot().captureRoboImage(path(name))
    }

    private val folders = listOf(
        ShelfFolder("w", "Work", 1.0, now),
        ShelfFolder("r", "Receipts", 2.0, now),
        ShelfFolder("t", "Trip", 3.0, now),
    )

    private fun file(id: String, name: String, size: Long, mime: String, from: String, ago: Long, order: Double, folder: String? = null) =
        ShelfItem(id, "file", name, "", null, from, now - ago, now - ago, order, ShelfFile(name, size, mime, id + "sha", from), folder)

    private val items = listOf(
        file("a1", "Quarterly_Report.pdf", 464_000, "application/pdf", "mac", 2 * 60_000L, -12.0),
        ShelfItem("a2", "link", "Event registration", "https://forms.gle/x7Tq2", null, "phone", now - 14 * 60_000L, now, -11.0),
        ShelfItem("a3", "wifi", "Studio_5G", "", "correct-horse", "phone", now - 3_600_000L, now, -10.0),
        ShelfItem(
            "a4", "snippet", "adb mdns services", "adb mdns services\nadb connect 192.168.1.42:37099", null, "mac",
            now - 3 * 3_600_000L, now, -9.0,
        ),
        file("a5", "IMG_20261010_195129.jpg", 4_300_000, "image/jpeg", "phone", 26 * 3_600_000L, -8.0),
        // In folders
        file("w1", "Roadmap.key", 2_400_000, "application/octet-stream", "mac", 40 * 60_000L, -7.0, "w"),
        ShelfItem("w2", "link", "Design review notes", "https://example.com/review", null, "mac", now - 5 * 3_600_000L, now, -6.0,
            folderId = "w"),
        file("w3", "Budget_2027.xlsx", 88_000, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "phone",
            28 * 3_600_000L, -5.0, "w"),
        ShelfItem("w4", "snippet", "Standup", "Standup\nShip folders, review offline cap", null, "phone", now - 2 * 86_400_000L, now,
            -4.0, folderId = "w"),
        file("r1", "Receipt_Cafe.pdf", 52_000, "application/pdf", "phone", 3 * 86_400_000L, -3.0, "r"),
        file("r2", "Invoice_0042.pdf", 120_000, "application/pdf", "mac", 4 * 86_400_000L, -2.0, "r"),
        ShelfItem("t1", "address", "Public Library", "Public Library\n1 Market St, San Francisco", null, "phone",
            now - 27 * 3_600_000L, now, -1.0, folderId = "t"),
    )

    private val actions = object : ShelfActions {
        override fun addText(text: String) = Unit
        override fun addFile() = Unit
        override fun pasteText(): String? = null
        override fun remove(id: String): ShelfItem? = null
        override fun restore(item: ShelfItem) = Unit
        override fun move(id: String, to: Int) = Unit
        override fun primary(item: ShelfItem) = "Copied"
        override fun copy(item: ShelfItem) = "Copied"
        override fun open(id: String) = Unit
        override fun keptOffline(item: ShelfItem) = item.id == "w1"
    }

    private val allLocal = ShelfFilesUi(local = items.map { it.id }.toSet())

    @Test
    fun shelfFolders() = shot("shelf-folders") {
        ShelfScreen(items, connected = true, a = actions, onBack = {}, files = allLocal, now = now, zone = zone, folders = folders)
    }

    @Test
    fun shelfInsideFolder() = shot("shelf-inside-folder") {
        ShelfScreen(
            items, connected = true, a = actions, onBack = {}, files = allLocal, now = now, zone = zone, folders = folders,
            folderId = "w",
        )
    }

    /** The Mac is away: cached items still open and copy; uncached ones carry only the quiet cloud. */
    @Test
    fun shelfDisconnected() = shot("shelf-disconnected") {
        ShelfScreen(
            items, connected = false, a = actions, onBack = {}, now = now, zone = zone, folders = folders,
            files = ShelfFilesUi(local = setOf("a1", "w1", "r1"), connected = false),
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun shelfMoveSheet() {
        compose.setContent {
            TetherTheme {
                ShelfScreen(items, connected = true, a = actions, onBack = {}, files = allLocal, now = now, zone = zone, folders = folders)
                MoveSheet(
                    items[0], null, folders, mapOf("w" to 4, "r" to 2, "t" to 1), ShelfCache.State.LOCAL, { null },
                    onPick = {}, onNewFolder = {}, onDismiss = {},
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        captureScreenRoboImage(path("shelf-move-sheet"))
    }

    @Test
    fun settingsShelfOffline() = shot("settings-shelf-offline") {
        ShelfSettingsScreen(
            used = 312L * 1024 * 1024, cap = 1024L * 1024 * 1024, onCap = {}, onRemoveCopies = {}, onOpenShelf = {}, onBack = {},
        )
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
