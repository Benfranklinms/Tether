package dev.benfranklin.tether

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.benfranklin.tether.core.notify.AppRule
import dev.benfranklin.tether.core.notify.NotificationSettings
import dev.benfranklin.tether.core.notify.QuietHours
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.ui.screens.AppRow
import dev.benfranklin.tether.ui.screens.AppsScreen
import dev.benfranklin.tether.ui.screens.DeviceInfo
import dev.benfranklin.tether.ui.screens.HomeModel
import dev.benfranklin.tether.ui.screens.HomeScreen
import dev.benfranklin.tether.ui.screens.OnboardingScreen
import dev.benfranklin.tether.ui.screens.OnboardingStep
import dev.benfranklin.tether.ui.screens.PairView
import dev.benfranklin.tether.ui.screens.PermissionItem
import dev.benfranklin.tether.ui.screens.SettingsActions
import dev.benfranklin.tether.ui.screens.SettingsScreen
import dev.benfranklin.tether.ui.theme.TetherTheme
import org.junit.Rule
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose screens with sample data and writes PNGs to docs/screenshots/android
 * (`./gradlew :app:recordRoborazziDebug`). These are the images in the README.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { TetherTheme { content() } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(ANIMATION_SETTLE_MS)
        compose.onRoot().captureRoboImage(File(System.getProperty("tether.screens") ?: "build/screens", "$name.png").path)
    }

    private val now = 1_760_000_000_000L

    private fun rec(name: String, dir: Direction, size: Long, done: Long, status: TransferStatus, via: Transport?, err: String? = null) =
        TransferRecord(
            id = name, direction = dir, name = name, size = size, sha256 = "0".repeat(64), mime = "application/octet-stream",
            status = status, transferred = done, error = err, createdAt = now, via = via,
        )

    @Test
    fun home() = shot("home") {
        HomeScreen(
            HomeModel(
                title = "Ben's MacBook Air", subtitle = "Connected · Wi-Fi fast path", connected = true, searching = false,
                wifi = true, error = null, notice = null, mirroringOff = false,
                transfers = listOf(
                    rec("IMG_2041.HEIC", Direction.OUT, 4_800_000, 3_100_000, TransferStatus.ACTIVE, Transport.WIFI),
                    rec("Lecture-notes.pdf", Direction.IN, 2_300_000, 2_300_000, TransferStatus.DONE, Transport.BLE),
                    rec("demo-video.mp4", Direction.OUT, 180_000_000, 0, TransferStatus.WAITING, Transport.BLE,
                        "Waiting for Wi-Fi (too large for Bluetooth)"),
                ),
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun homeSearching() = shot("home-searching") {
        HomeScreen(
            HomeModel(
                title = "Ben's MacBook Air", subtitle = "Looking nearby…", connected = false, searching = true, wifi = false,
                error = null, notice = null, transfers = emptyList(), mirroringOff = true,
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun welcome() = shot("onboarding-welcome") {
        OnboardingScreen(OnboardingStep.WELCOME, emptyList(), PairView.Idle, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun permissions() = shot("onboarding-permissions") {
        OnboardingScreen(
            OnboardingStep.PERMISSIONS,
            listOf(
                PermissionItem("bluetooth", "Nearby devices", "Find and stay connected to your Mac over Bluetooth", true, true),
                PermissionItem("notifications", "Notifications", "Show the connection and transfer progress", true, false),
                PermissionItem("listener", "Notification access", "Mirror your phone's notifications to the Mac", false, false),
                PermissionItem("camera", "Camera", "Scan the pairing code (you can pair without it)", false, false),
            ),
            PairView.Idle, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun pairingCode() = shot("pairing-code") {
        OnboardingScreen(OnboardingStep.PAIR, emptyList(), PairView.Confirm("482913", "Ben's MacBook Air"), {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun settings() = shot("settings") {
        SettingsScreen(
            AppSettings(
                notifications = NotificationSettings(
                    apps = mapOf("com.instagram.android" to AppRule.BLOCK),
                    mutedKeywords = listOf("OTP"), quietHours = QuietHours(enabled = true),
                ),
            ),
            DeviceInfo("Ben's MacBook Air", "3F9A·0C21·77B4", "8B12·E4D0·19AF", true, true, "0.1.0", false),
            NoActions, onBack = {},
        )
    }

    @Test
    fun apps() = shot("apps") {
        AppsScreen(
            listOf(
                AppRow("com.whatsapp", "WhatsApp", AppRule.INHERIT, true, null),
                AppRow("org.telegram.messenger", "Telegram", AppRule.INHERIT, true, null),
                AppRow("com.google.android.gm", "Gmail", AppRule.ALLOW, true, null),
                AppRow("com.instagram.android", "Instagram", AppRule.BLOCK, false, null),
                AppRow("com.android.vending", "Play Store", AppRule.INHERIT, false, null),
            ),
            { _, _ -> }, {},
        )
    }

    private object NoActions : SettingsActions {
        override fun update(change: (AppSettings) -> AppSettings) = Unit
        override fun openApps() = Unit
        override fun openKeywords() = Unit
        override fun pickQuietStart() = Unit
        override fun pickQuietEnd() = Unit
        override fun openNotificationAccess() = Unit
        override fun pickFolder() = Unit
        override fun resetFolder() = Unit
        override fun openBatterySettings() = Unit
        override fun rotateKeys() = Unit
        override fun unpair() = Unit
        override fun exportLogs() = Unit
        override fun openLicenses() = Unit
    }

    private companion object {
        const val ANIMATION_SETTLE_MS = 2_000L
    }
}
