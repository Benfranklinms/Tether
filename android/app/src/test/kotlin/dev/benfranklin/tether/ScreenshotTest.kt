package dev.benfranklin.tether

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
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

    private val zone = java.time.ZoneId.of("Asia/Kolkata")

    /** A calm mono "photo" (sky, sun, hills) so thumbnails render without real pictures. */
    private fun fakePhoto(seed: Int, w: Int = 240, h: Int = 240): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val top = 0xFF000000.toInt() or ((60 + seed * 23 % 90) * 0x010101)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), top, 0xFF0C0C0E.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFFE6E6E6.toInt()
        c.drawCircle(w * (0.3f + seed % 3 * 0.2f), h * 0.32f, w * 0.1f, p)
        p.color = 0xFF2A2A2E.toInt()
        val path = android.graphics.Path().apply {
            moveTo(0f, h * 0.75f)
            lineTo(w * 0.35f, h * (0.45f + seed % 2 * 0.1f))
            lineTo(w * 0.6f, h * 0.68f)
            lineTo(w * 0.8f, h * 0.5f)
            lineTo(w.toFloat(), h * 0.7f)
            lineTo(w.toFloat(), h.toFloat())
            lineTo(0f, h.toFloat())
            close()
        }
        c.drawPath(path, p)
        return bmp.asImageBitmap()
    }

    private val homeShelf = listOf(
        dev.benfranklin.tether.core.proto.ShelfItem(
            "h1", "link", "Event registration", "https://forms.gle/x7Tq2", null, "phone", now - 14 * 60_000L, now, -3.0,
        ),
        dev.benfranklin.tether.core.proto.ShelfItem(
            "h2", "file", "Aptitude Practice Set 15 Questions.pdf", "", null, "mac", now - 2 * 60_000L, now, -2.0,
            dev.benfranklin.tether.core.proto.ShelfFile("Aptitude Practice Set 15 Questions.pdf", 464_000, "application/pdf", "bb", "mac"),
        ),
        dev.benfranklin.tether.core.proto.ShelfItem("h3", "wifi", "Studio_5G", "", "pw", "phone", now - 3_600_000L, now, -1.0),
    )

    private fun homeData() = dev.benfranklin.tether.ui.screens.HomeData(
        shelf = homeShelf, clipboard = "forms.gle/x7Tq2",
        clips = listOf(
            dev.benfranklin.tether.core.features.ClipRecents.Entry("forms.gle/x7Tq2", now, false),
            dev.benfranklin.tether.core.features.ClipRecents.Entry("1 Market St, San Francisco", now - 60_000, true),
            dev.benfranklin.tether.core.features.ClipRecents.Entry("See you at 6", now - 120_000, true),
        ),
        mac = dev.benfranklin.tether.v7.MacStatusUi(
            82, true, dev.benfranklin.tether.core.proto.MacMedia(true, "Nights", "Frank Ocean", "Music", false, 307_000, 102_000), now,
        ),
        photos = (1L..8L).map { dev.benfranklin.tether.v7.RecentPhoto(it, android.net.Uri.parse("content://demo/$it")) },
        hotspotSsid = "Alex's Phone",
    )

    private val homeActions = object : dev.benfranklin.tether.ui.screens.HomeActions {
        override suspend fun photoThumb(p: dev.benfranklin.tether.v7.RecentPhoto) = fakePhoto(p.id.toInt())
    }

    private val linked = HomeModel(
        title = "MacBook Air", subtitle = "Connected · Wi-Fi", connected = true, searching = false,
        wifi = true, error = null, notice = null, mirroringOff = false, now = now + 60_000,
        transfers = listOf(
            rec("Screenshot_2026-10-11-08-41.png", Direction.OUT, 282_000, 282_000, TransferStatus.DONE, Transport.WIFI),
            rec("Lecture_notes_unit4.pdf", Direction.IN, 1_400_000, 1_400_000, TransferStatus.DONE, Transport.WIFI),
            rec("AdaFace_Summary.pdf", Direction.OUT, 20_000, 20_000, TransferStatus.DONE, Transport.WIFI),
        ),
    )

    /** 0.5.3: Home as customizable panels (the default layout). */
    @Test
    fun home() = shot("home") {
        HomeScreen(linked, dev.benfranklin.tether.core.features.HomeLayout.DEFAULT, homeActions, homeData())
    }

    /** 0.5.3: Home in edit mode: "−" badges, resize corners, + and Done. */
    @Test
    fun homeEdit() = shot("home-edit") {
        HomeScreen(linked, dev.benfranklin.tether.core.features.HomeLayout.DEFAULT, homeActions, homeData(), startEditing = true)
    }

    /** 0.5.3: the panel picker (sheet content on its own; the modal sheet needs a window). */
    @Test
    fun homeAddPanel() = shot("home-add-panel") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.sheetBackdrop()) {
            dev.benfranklin.tether.ui.screens.AddPanelContent(
                dev.benfranklin.tether.core.features.HomeLayout.DEFAULT.remove(dev.benfranklin.tether.core.features.PanelKind.SHELF)
                    .remove(dev.benfranklin.tether.core.features.PanelKind.CLIPBOARD)
                    .remove(dev.benfranklin.tether.core.features.PanelKind.TRANSFERS),
                linked, homeData(), onAdd = { _, _ -> }, onClose = {},
            )
        }
    }

    /** 0.5.3: other sizes: wide Mac and clipboard, large Now playing, Hotspot. */
    @Test
    fun homeSizes() = shot("home-sizes") {
        val l = dev.benfranklin.tether.core.features.HomeLayout(
            listOf(
                dev.benfranklin.tether.core.features.Panel(dev.benfranklin.tether.core.features.PanelKind.MAC, dev.benfranklin.tether.core.features.PanelSize.WIDE),
                dev.benfranklin.tether.core.features.Panel(dev.benfranklin.tether.core.features.PanelKind.NOW_PLAYING, dev.benfranklin.tether.core.features.PanelSize.LARGE),
                dev.benfranklin.tether.core.features.Panel(dev.benfranklin.tether.core.features.PanelKind.HOTSPOT, dev.benfranklin.tether.core.features.PanelSize.SMALL),
                dev.benfranklin.tether.core.features.Panel(dev.benfranklin.tether.core.features.PanelKind.SEND_FILE, dev.benfranklin.tether.core.features.PanelSize.SMALL),
                dev.benfranklin.tether.core.features.Panel(dev.benfranklin.tether.core.features.PanelKind.PHOTOS, dev.benfranklin.tether.core.features.PanelSize.LARGE),
            ),
        )
        HomeScreen(linked, l, homeActions, homeData())
    }

    /**
     * 0.5.3 fix check: Home as on Ben's phone with nothing in it: empty Shelf, nothing playing, no
     * transfers, a phone number on the clipboard (never shown: "Tap to send"). Empty panels are compact.
     */
    @Test
    fun homeIdle() = shot("home-idle") {
        HomeScreen(
            linked.copy(transfers = emptyList()), dev.benfranklin.tether.core.features.HomeLayout.DEFAULT, homeActions,
            dev.benfranklin.tether.ui.screens.HomeData(
                clipboard = "+917554626501",
                mac = dev.benfranklin.tether.v7.MacStatusUi(40, false, null, now),
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
            onBack = {}, now = now + 60_000, zone = zone,
        )
    }

    @Test
    fun homeSearching() = shot("home-searching") {
        HomeScreen(
            HomeModel(
                title = "MacBook Air", subtitle = "Looking nearby…", connected = false, searching = true, wifi = false,
                error = null, notice = null, transfers = emptyList(), mirroringOff = true,
            ),
            dev.benfranklin.tether.core.features.HomeLayout.DEFAULT, homeActions,
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
    private fun shelfFile(id: String, name: String, size: Long, mime: String, from: String, ago: Long, order: Double) =
        dev.benfranklin.tether.core.proto.ShelfItem(
            id, "file", name, "", null, from, now - ago, now - ago, order,
            dev.benfranklin.tether.core.proto.ShelfFile(name, size, mime, id + "sha", from),
        )

    private val shelfItems = listOf(
        shelfFile("s1", "Aptitude Practice Set 15 Questions.pdf", 464_000, "application/pdf", "mac", 2 * 60_000L, -9.0),
        shelfFile("s2", "Quiz_answer_script.docx", 11_264, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "mac", 5 * 60_000L, -8.0),
        shelfFile("s3", "IMG_20261010_195129.jpg", 4_300_000, "image/jpeg", "phone", 12 * 60_000L, -7.0),
        dev.benfranklin.tether.core.proto.ShelfItem(
            "s4", "link", "Event registration", "https://forms.gle/x7Tq2", null, "phone", now - 14 * 60_000L, now, -6.0,
        ),
        dev.benfranklin.tether.core.proto.ShelfItem("s5", "wifi", "Studio_5G", "", "correct-horse", "phone", now - 3_600_000L, now, -5.0),
        dev.benfranklin.tether.core.proto.ShelfItem(
            "s6", "snippet", "adb mdns services", "adb mdns services\nadb connect 192.168.29.142:37099", null, "mac",
            now - 3 * 3_600_000L, now, -4.0,
        ),
        shelfFile("s7", "Resume - Alex.pdf", 82_000, "application/pdf", "mac", 26 * 3_600_000L, -3.0),
        dev.benfranklin.tether.core.proto.ShelfItem(
            "s8", "address", "Public Library", "Public Library\n1 Market St, San Francisco", null, "phone", now - 27 * 3_600_000L, now, -2.0,
        ),
        shelfFile("s9", "Screenshot_2026-10-09-21-14.png", 1_150_000, "image/png", "phone", 3 * 86_400_000L, -1.0),
    )

    private val noShelf = object : dev.benfranklin.tether.ui.screens.ShelfActions {
        override fun addText(text: String) = Unit
        override fun addFile() = Unit
        override fun pasteText(): String? = null
        override fun remove(id: String): dev.benfranklin.tether.core.proto.ShelfItem? = null
        override fun restore(item: dev.benfranklin.tether.core.proto.ShelfItem) = Unit
        override fun move(id: String, to: Int) = Unit
        override fun primary(item: dev.benfranklin.tether.core.proto.ShelfItem) = "Copied"
        override fun copy(item: dev.benfranklin.tether.core.proto.ShelfItem) = "Copied"
        override fun open(id: String) = Unit
        override suspend fun thumbnail(item: dev.benfranklin.tether.core.proto.ShelfItem) = fakePhoto(item.id.hashCode() and 7)
    }

    @Test
    fun shelf() = shot("shelf") {
        dev.benfranklin.tether.ui.screens.ShelfScreen(
            shelfItems, connected = true, a = noShelf, onBack = {}, now = now, zone = zone,
            files = dev.benfranklin.tether.ui.screens.ShelfFilesUi(local = shelfItems.map { it.id }.toSet()),
        )
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
            shelfFileItems, connected = false, a = noShelf, onBack = {},
            files = dev.benfranklin.tether.ui.screens.ShelfFilesUi(local = setOf("f2"), connected = false),
        )
    }

    @Test
    fun shelfSearch() = shot("shelf-search") {
        dev.benfranklin.tether.ui.screens.ShelfScreen(shelfFileItems, connected = true, a = noShelf, onBack = {}, initialQuery = "word")
    }

    @Test
    fun shelfEmpty() = shot("shelf-empty") {
        dev.benfranklin.tether.ui.screens.ShelfScreen(emptyList(), connected = false, a = noShelf, onBack = {})
    }

    @Test
    fun shelfDetail() = shot("shelf-detail") {
        dev.benfranklin.tether.ui.screens.ShelfDetailScreen(shelfItems[4], noShelf, onBack = {})
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
