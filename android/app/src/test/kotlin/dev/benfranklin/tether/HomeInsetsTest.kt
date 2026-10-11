package dev.benfranklin.tether

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.data.HomeSections
import dev.benfranklin.tether.ui.screens.HomeActions
import dev.benfranklin.tether.ui.screens.HomeData
import dev.benfranklin.tether.ui.screens.HomeModel
import dev.benfranklin.tether.ui.screens.HomeScreen
import dev.benfranklin.tether.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.5.4 Home edge-to-edge: real status / navigation bar insets are dispatched to the window (24 dp
 * status bar; 48 dp three-button bar or a 20 dp gesture bar), then the list is scrolled to its end.
 * The app bar must start below the status bar and the last section (0.5.5: the empty Shelf card;
 * the "Edit Home" pill moved into the app bar) must end above the navigation bar. The bars are drawn as grey strips in the screenshots so any overlap is visible.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi", application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomeInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_760_000_000_000L

    private fun rec(name: String, status: TransferStatus) = TransferRecord(
        id = name, direction = Direction.OUT, name = name, size = 100_000, sha256 = "0".repeat(64), mime = "application/pdf",
        status = status, transferred = if (status == TransferStatus.DONE) 100_000 else 40_000, error = null, createdAt = now,
        via = Transport.WIFI,
    )

    private val model = HomeModel(
        title = "Ben\u2019s MacBook Air", subtitle = "", connected = true, searching = false, wifi = true, error = null, notice = null,
        transfers = listOf(rec("a.pdf", TransferStatus.ACTIVE), rec("b.pdf", TransferStatus.DONE), rec("c.pdf", TransferStatus.DONE)),
        mirroringOff = true, now = now,
    )

    private fun run(name: String, navDp: Int) {
        val density = compose.activity.resources.displayMetrics.density
        val statusPx = (STATUS_DP * density).toInt()
        val navPx = (navDp * density).toInt()
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            TetherTheme {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(model, HomeSections.DEFAULT, object : HomeActions {}, HomeData())
                    // Stand-ins for the system bars, drawn over the app.
                    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(BAR))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(BAR))
                }
            }
        }
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                .build()
            val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        compose.waitForIdle()
        val headerTop = compose.onNodeWithText("Ben\u2019s MacBook Air").fetchSemanticsNode().boundsInRoot.top
        assertTrue("header under the status bar: $headerTop < $statusPx", headerTop >= statusPx)
        val barTop = compose.onNodeWithTag("home-settings").fetchSemanticsNode().boundsInRoot.top
        assertTrue("app bar under the status bar: $barTop < $statusPx", barTop >= statusPx)
        compose.onNodeWithTag("home-edit").assertExists()
        repeat(SWIPES) { compose.onNodeWithTag("home-list").performTouchInput { swipeUp() } }
        compose.waitForIdle()
        val rootH = compose.onRoot().fetchSemanticsNode().size.height
        val lastBottom = compose.onNodeWithTag("home-shelf-empty").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("last section under the navigation bar: $lastBottom > ${rootH - navPx}", lastBottom <= rootH - navPx)
        compose.onNodeWithTag("home-list").assertExists()
        compose.onRoot().captureRoboImage(File(System.getProperty("tether.screens") ?: "build/screens", "$name.png").path)
    }

    /** Three-button navigation: a 48 dp bar. */
    @Test fun threeButtonNav() = run("home-3button-nav", navDp = 48)

    /** Gesture navigation: a 20 dp handle area. */
    @Test fun gestureNav() = run("home-gesture-nav", navDp = 20)

    /** Three-button navigation with 1.3× font. */
    @Test
    @Config(fontScale = 1.3f)
    fun threeButtonNavLargeFont() = run("home-3button-font130", navDp = 48)

    private companion object {
        const val STATUS_DP = 24
        const val SWIPES = 6
        val BAR = Color(0x99808080)
    }
}
