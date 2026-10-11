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
import dev.benfranklin.tether.ui.screens.FeatureActions
import dev.benfranklin.tether.ui.screens.FeatureStatus
import dev.benfranklin.tether.ui.screens.FeaturesScreen
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Rule
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose screens with sample data and writes PNGs to docs/screenshots/android
 * (`./gradlew :app:recordRoborazziDebug`). These are the images in the README.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xxhdpi", application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF) // no conscrypt JNI on linux-aarch64 hosts; TLS is never used here
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
                title = "MacBook Air", subtitle = "Connected · Wi-Fi fast path", connected = true, searching = false,
                wifi = true, error = null, notice = null, mirroringOff = false, now = now + 60_000,
                transfers = listOf(
                    rec("IMG_2041.HEIC", Direction.OUT, 4_800_000, 3_100_000, TransferStatus.ACTIVE, Transport.WIFI),
                    rec("Lecture-notes.pdf", Direction.IN, 2_300_000, 2_300_000, TransferStatus.DONE, Transport.BLE),
                    rec("demo-video.mp4", Direction.OUT, 180_000_000, 0, TransferStatus.WAITING, Transport.BLE,
                        "Waiting for Wi-Fi (too large for Bluetooth)"),
                ),
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
            extras = dev.benfranklin.tether.ui.screens.HomeExtras(
                shelfCount = 4, updateVersion = "0.5.3",
            ),
        )
    }

    private fun many(): List<TransferRecord> = listOf(
        rec("Screenshot_2026-10-11-08-41.jpg", Direction.OUT, 282_000, 282_000, TransferStatus.DONE, Transport.WIFI),
        rec("AdaFace_Summary.pdf", Direction.OUT, 20_000, 20_000, TransferStatus.DONE, Transport.WIFI),
        rec("IMG_20260604_120359.png", Direction.IN, 1_900_000, 1_900_000, TransferStatus.DONE, Transport.WIFI),
        rec("IMG20260821093336.jpg", Direction.OUT, 2_900_000, 2_900_000, TransferStatus.DONE, Transport.WIFI),
        rec("Lecture-notes.pdf", Direction.IN, 2_300_000, 2_300_000, TransferStatus.DONE, Transport.BLE),
    ).mapIndexed { i, r -> r.copy(createdAt = now - i * 3_600_000L * 7, updatedAt = now - i * 3_600_000L * 7) }

    /** 0.5.3: Home shows the latest three transfers and "See all (N)". */
    @Test
    fun homeTransfers() = shot("home-transfers") {
        HomeScreen(
            HomeModel(
                title = "MacBook Air", subtitle = "Connected · Wi-Fi fast path", connected = true, searching = false,
                wifi = true, error = null, notice = null, mirroringOff = false, transfers = many(), now = now + 60_000,
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
            extras = dev.benfranklin.tether.ui.screens.HomeExtras(shelfCount = 3),
        )
    }

    @Test
    fun transfers() = shot("transfers") {
        dev.benfranklin.tether.ui.screens.TransfersScreen(
            many(),
            object : dev.benfranklin.tether.ui.screens.TransfersActions {
                override fun cancel(id: String) = Unit
                override fun retry(id: String) = Unit
                override fun remove(id: String) = Unit
                override fun clearFinished() = Unit
                override fun open(t: TransferRecord): String? = null
            },
            onBack = {}, now = now + 60_000,
        )
    }

    @Test
    fun homeSearching() = shot("home-searching") {
        HomeScreen(
            HomeModel(
                title = "MacBook Air", subtitle = "Looking nearby…", connected = false, searching = true, wifi = false,
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
    fun pairingSearching() = shot("pairing-searching") {
        OnboardingScreen(
            OnboardingStep.PAIR, emptyList(),
            PairView.Searching(null, "Found your Mac · connecting (try 2)"), {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun pairingFailed() = shot("pairing-failed") {
        OnboardingScreen(
            OnboardingStep.PAIR, emptyList(),
            PairView.Failed(pairFailure(dev.benfranklin.tether.ble.BleCentral.Diag(adsSeen = 87), dev.benfranklin.tether.core.LinkState.Offline)),
            {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun pairingCode() = shot("pairing-code") {
        OnboardingScreen(OnboardingStep.PAIR, emptyList(), PairView.Confirm("482913", "MacBook Air"), {}, {}, {}, {}, {}, {}, {})
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
            DeviceInfo("MacBook Air", "3F9A·0C21·77B4", "8B12·E4D0·19AF", true, true, "0.1.0", false),
            NoActions, onBack = {},
            find = dev.benfranklin.tether.ui.screens.FindModel(
                ringing = false, pending = null, locked = false, error = null, available = true,
            ),
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

    @Test
    fun features() = shot("features") {
        FeaturesScreen(
            AppSettings(sendPhotos = true),
            FeatureStatus(connected = true, macCaps = dev.benfranklin.tether.core.proto.Caps.CURRENT.toSet(), mediaPermission = false),
            NoFeatureActions,
            onBack = {},
        )
    }

    /** Android 15+ with one-time codes hidden: the guide opens by itself (0.4.0). */
    @Test
    fun featuresCodes() = shot("features-codes") {
        FeaturesScreen(
            AppSettings(),
            FeatureStatus(
                connected = true, macCaps = dev.benfranklin.tether.core.proto.Caps.CURRENT.toSet(), sensitiveApplies = true,
                sensitiveRedacting = true, oem = "OnePlus",
                adbCommand = "adb shell cmd appops set --user 0 in.benfranklin.tether RECEIVE_SENSITIVE_NOTIFICATIONS allow",
            ),
            NoFeatureActions,
            onBack = {},
        )
    }

    private object NoFeatureActions : FeatureActions {
        override fun update(change: (AppSettings) -> AppSettings) = Unit
        override fun grantMedia() = Unit
        override fun grantPhoneState() = Unit
        override fun openFullScreenSettings() = Unit
        override fun openAccessibility() = Unit
        override fun openNotificationAccess() = Unit
        override fun grantSms() = Unit
        override fun grantContacts() = Unit
        override fun grantCalls() = Unit
        override fun openDndAccess() = Unit
        override fun copyAdbCommand() = Unit
        override fun openDeveloperOptions() = Unit
    }

    private object NoActions : SettingsActions {
        override fun setupSummary() = "13/17 · 1 required"
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

    // ---------------- 0.5.0 ----------------

    private val setupRowsSample = listOf(
        dev.benfranklin.tether.ui.screens.SetupRow("nearby", "Nearby devices", "Find and connect to your Mac over Bluetooth", true, true),
        dev.benfranklin.tether.ui.screens.SetupRow("notif", "Notification access", "Mirror notifications to the Mac, reply, one-time codes", true, true),
        dev.benfranklin.tether.ui.screens.SetupRow("battery", "Battery unrestricted", "Keep the link alive in the background", false, true),
        dev.benfranklin.tether.ui.screens.SetupRow("sms", "SMS", "Messages on the Mac, codes from texts", true, false),
        dev.benfranklin.tether.ui.screens.SetupRow("allfiles", "All files access", "Browse Downloads, Documents and WhatsApp media from the Mac", false, false),
        dev.benfranklin.tether.ui.screens.SetupRow("accessibility", "Accessibility (Tether control)", "Control the screen and type from the Mac", false, false),
        dev.benfranklin.tether.ui.screens.SetupRow(
            "readlogs", "Read logs (adb)", "Clipboard auto mode: send copies without opening Tether", false, false,
            listOf("adb shell pm grant in.benfranklin.tether android.permission.READ_LOGS", "adb shell am force-stop in.benfranklin.tether"),
            "Needs a computer with adb once (wireless debugging works).",
        ),
    )

    /** 0.5.1: row titles stay on one line at a large font scale (Settings › Display size and text). */
    @Test
    @Config(fontScale = 1.3f)
    fun settingsLargeFont() = shot("settings-font130") {
        SettingsScreen(AppSettings(), DeviceInfo("MacBook Air", "3F9A·0C21·77B4", "8B12·E4D0·19AF", true, true, "0.1.0", false),
            NoActions, onBack = {})
    }

    @Test
    @Config(fontScale = 1.3f)
    fun setupLargeFont() = shot("setup-font130") {
        dev.benfranklin.tether.ui.screens.SetupScreen(setupRowsSample, onFix = {}, onCopy = {}, onBack = {})
    }

    @Test
    fun setup() = shot("setup") {
        dev.benfranklin.tether.ui.screens.SetupScreen(setupRowsSample, onFix = {}, onCopy = {}, onBack = {})
    }

    @Test
    fun more5() = shot("continue-files-updates") {
        dev.benfranklin.tether.ui.screens.More5Screen(
            AppSettings(continueTabs = true), "11/16 · 1 required",
            dev.benfranklin.tether.ui.screens.UpdateUi("Tether 0.5.1 is available", "Download and install"), "0.5.0",
            object : dev.benfranklin.tether.ui.screens.More5Actions {
                override fun update(change: (AppSettings) -> AppSettings) = Unit
                override fun openSetup() = Unit
                override fun updateAction() = Unit
            },
            onBack = {},
        )
    }

    // ---- 0.5.2 ----
    private val shelfItems = listOf(
        dev.benfranklin.tether.core.proto.ShelfItem("1", "link", "Lab schedule", "https://example.com/lab", null, "mac", now, now, -4.0),
        dev.benfranklin.tether.core.proto.ShelfItem("2", "wifi", "Studio Wi-Fi", "Studio-5G", "correct-horse", "phone", now, now, -3.0),
        dev.benfranklin.tether.core.proto.ShelfItem(
            "3", "address", "Sample Street", "12 Sample Street, Springfield 682021", null, "mac", now, now, -2.0,
        ),
        dev.benfranklin.tether.core.proto.ShelfItem(
            "4", "snippet", "ssh", "ssh -L 8080:localhost:80 demo@example.com", null, "phone", now, now, -1.0,
        ),
    )

    private object NoShelf : dev.benfranklin.tether.ui.screens.ShelfActions {
        override fun addText(text: String) = Unit
        override fun addFile() = Unit
        override fun pasteText(): String? = null
        override fun remove(id: String): dev.benfranklin.tether.core.proto.ShelfItem? = null
        override fun restore(item: dev.benfranklin.tether.core.proto.ShelfItem) = Unit
        override fun move(id: String, to: Int) = Unit
        override fun primary(item: dev.benfranklin.tether.core.proto.ShelfItem) = "Copied"
        override fun copy(item: dev.benfranklin.tether.core.proto.ShelfItem) = "Copied"
        override fun open(id: String) = Unit
    }

    @Test
    fun shelf() = shot("shelf") {
        dev.benfranklin.tether.ui.screens.ShelfScreen(shelfItems, connected = true, a = NoShelf, onBack = {})
    }

    private val shelfFileItems = listOf(
        dev.benfranklin.tether.core.proto.ShelfItem(
            "f1", "file", "PES_quiz_answer_script.docx", "", null, "mac", now, now, -6.0,
            dev.benfranklin.tether.core.proto.ShelfFile(
                "PES_quiz_answer_script.docx", 11_264,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "aa", "mac",
            ),
        ),
        dev.benfranklin.tether.core.proto.ShelfItem(
            "f2", "file", "Aptitude Practice Set 15.pdf", "", null, "mac", now, now, -5.0,
            dev.benfranklin.tether.core.proto.ShelfFile("Aptitude Practice Set 15.pdf", 464_000, "application/pdf", "bb", "mac"),
        ),
    ) + shelfItems

    /** 0.5.3: friendly kinds ("Word document", "PDF") and "Not downloaded yet" while the Mac is away. */
    @Test
    fun shelfFiles() = shot("shelf-files") {
        dev.benfranklin.tether.ui.screens.ShelfScreen(
            shelfFileItems, connected = false, a = NoShelf, onBack = {},
            files = dev.benfranklin.tether.ui.screens.ShelfFilesUi(local = setOf("f2"), connected = false),
        )
    }

    @Test
    fun shelfSearch() = shot("shelf-search") {
        dev.benfranklin.tether.ui.screens.ShelfScreen(shelfFileItems, connected = true, a = NoShelf, onBack = {}, initialQuery = "word")
    }

    @Test
    fun shelfEmpty() = shot("shelf-empty") {
        dev.benfranklin.tether.ui.screens.ShelfScreen(emptyList(), connected = false, a = NoShelf, onBack = {})
    }

    @Test
    fun shelfDetail() = shot("shelf-detail") {
        dev.benfranklin.tether.ui.screens.ShelfDetailScreen(shelfItems[1], NoShelf, onBack = {})
    }

    @Test
    fun hotspot() = shot("hotspot") {
        dev.benfranklin.tether.ui.screens.HotspotScreen(
            AppSettings(), savedSsid = "Pixel hotspot", hasPassword = true, controlOn = false,
            status = dev.benfranklin.tether.ui.screens.hotspotStatusText("on", null),
            a = object : dev.benfranklin.tether.ui.screens.HotspotActions {
                override fun update(change: (AppSettings) -> AppSettings) = Unit
                override fun save(ssid: String, password: String) = Unit
                override fun forget() = Unit
                override fun openAccessibility() = Unit
            },
            onBack = {},
        )
    }

    @Test
    fun updateSheet() = shot("update-available") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.sheetBackdrop(),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
        ) {
            dev.benfranklin.tether.ui.screens.UpdateSheetContent(
                dev.benfranklin.tether.ui.screens.UpdateSheetModel(
                    "0.5.2", "0.5.1",
                    "- Shelf: links, Wi-Fi and addresses on both devices\n- Find my Mac\n- Instant Hotspot\n- Insert from Phone",
                    progress = 0.42f, installing = false, error = null,
                ),
                {}, {}, {},
            )
        }
    }

    @Test
    fun whatsNew() = shot("whats-new") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.sheetBackdrop(),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
        ) {
            dev.benfranklin.tether.ui.screens.WhatsNewContent(
                "0.5.2", "- Shelf on both devices\n- Find my Mac\n- Instant Hotspot", onDone = {},
            )
        }
    }
}

private fun androidx.compose.ui.Modifier.sheetBackdrop() = this.then(
    androidx.compose.ui.Modifier.fillMaxSize().background(dev.benfranklin.tether.ui.theme.Mono.Surface),
)
