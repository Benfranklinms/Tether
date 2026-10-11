package dev.benfranklin.tether.data

import android.content.Context
import dev.benfranklin.tether.core.notify.NotificationSettings
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

@Serializable
data class AppSettings(
    /** Put text copied on the Mac straight onto the phone clipboard. */
    val receiveClipboard: Boolean = true,
    /** When Tether comes to the foreground, send the phone clipboard if it changed. */
    val sendClipboardOnOpen: Boolean = true,
    val notifications: NotificationSettings = NotificationSettings(),
    /** SAF tree URI for received files; null = Downloads/Tether. */
    val downloadTreeUri: String? = null,
    val downloadTreeLabel: String? = null,
    /** Use the Wi-Fi fast path when both devices share a network. */
    val wifiFastPath: Boolean = true,
    /** Show a system notification when a file arrives. */
    val notifyOnReceive: Boolean = true,
    val onboardingDone: Boolean = false,
    /** Offer notification buttons (reply, mark as read...) on the Mac. */
    val notificationActions: Boolean = true,
    /** Send new screenshots to the Mac automatically. */
    val sendScreenshots: Boolean = true,
    /** Send new camera photos to the Mac automatically (over Wi-Fi only). */
    val sendPhotos: Boolean = false,
    /** Share battery level, charging and network with the Mac. */
    val shareBattery: Boolean = true,
    /** Let the Mac ring this phone. */
    val allowRing: Boolean = true,
    /** Let the Mac ask to view (and, with accessibility, control) this phone's screen. */
    val allowScreen: Boolean = true,
    /** Longest edge of the shared screen video, in pixels (720, 1080, 1280 or 1600). */
    val screenMaxSize: Int = 1280,
    /** Show and send text messages from the Mac (0.3.0). */
    val shareSms: Boolean = true,
    /** Show incoming calls on the Mac, with Decline and Silence (0.3.0). */
    val callAlerts: Boolean = true,
    /** Show what's playing on the phone on the Mac, with controls (0.3.0). */
    val shareNowPlaying: Boolean = true,
    /** Let the Mac measure Bluetooth signal strength to lock itself when the phone is away (0.3.0). */
    val allowProximity: Boolean = true,
    /** Let the Mac place calls and control the call in progress (0.4.0). */
    val allowCalling: Boolean = true,
    /** Show recent calls on the Mac (0.4.0). */
    val shareCallLog: Boolean = true,
    /** The sensitive-notifications guide was dismissed (it comes back if the problem returns). */
    val sensitiveGuideDismissed: Boolean = false,
    /** Send what's copied on the phone without opening Tether (0.4.3; needs the adb setup). */
    val autoClip: Boolean = false,
    /** Auto mode also sends clips apps mark as sensitive (passwords, codes). */
    val autoClipSensitive: Boolean = false,
    // ---- 0.5.0 ----
    /** Copy one-time codes from texts and notifications to the Mac clipboard. */
    val otpAutoCopy: Boolean = true,
    /** Continue on Mac cards (master switch) and their sources. */
    val continueCards: Boolean = true,
    val continuePhotos: Boolean = true,
    val continueDownloads: Boolean = true,
    val continueLinks: Boolean = true,
    val continueSongs: Boolean = true,
    /** The current browser tab (reads the address bar via the accessibility service). Off by default. */
    val continueTabs: Boolean = false,
    /** Let the Mac browse Downloads, Documents and WhatsApp media. */
    val shareFiles: Boolean = true,
    /** Let the Mac browse recent photos. */
    val sharePhotoBrowser: Boolean = true,
    /** Let the Mac open links and send text on this phone. */
    val allowOpenOnPhone: Boolean = true,
    /** Let the Mac type into the focused field (needs the accessibility service). */
    val allowTyping: Boolean = true,
    /** Do Not Disturb follows the Mac's Focus, and the Mac sees this phone's DND. */
    val dndSync: Boolean = true,
    /** 0.5.4 Settings › Shelf › Offline files: storage cap for automatic copies (bytes; 0 = Off). */
    val shelfOfflineCap: Long = dev.benfranklin.tether.core.features.ShelfOffline.PHONE_DEFAULT,
    /** Images on the clipboard travel both ways. */
    val clipImages: Boolean = true,
    /** Check for updates when Tether opens (at most daily). */
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheck: Long = 0,
    // 0.5.2
    /** Let the Mac open the camera / document scanner (Insert from Phone). */
    val allowCapture: Boolean = true,
    /** Let the Mac ask this phone to turn on its hotspot. */
    val allowHotspot: Boolean = true,
    /** Update popup: a version the user skipped, and "Later" until this time. */
    val skippedVersion: String? = null,
    val updateSnoozedUntil: Long = 0,
    /** The version whose "What's new" sheet was shown. */
    val whatsNewSeen: String? = null,
    /** Release notes of the version being installed, shown once by "What's new" after the update. */
    val whatsNewVersion: String? = null,
    val whatsNewNotes: String? = null,
    /** Instant Hotspot: let Tether control (accessibility) open hotspot settings and tap the switch. */
    val hotspotAutomation: Boolean = true,
    /** 0.5.3 Home panels in order with their sizes; null = the default layout. */
    @Serializable(with = LenientPanelSpecs::class)
    val homePanels: List<dev.benfranklin.tether.core.features.PanelSpec>? = null,
    /** 0.5.4 Home sections in order with visibility; null = migrate [homePanels] or the default (see HomeSections.from). */
    @Serializable(with = LenientSectionSpecs::class)
    val homeSections: List<SectionSpec>? = null,
    /** 0.5.4: newest MediaStore photo id seen when Photos was last opened (0 = not yet recorded). */
    val homePhotosSeenId: Long = 0,
    // 0.5.5 Settings › Appearance
    /** The theme when not following the system (Themes ids; unknown ids fall back to Mono). */
    val theme: String = "mono",
    /** Follow the system light/dark setting with [themeLight] / [themeDark]. */
    val themeFollowSystem: Boolean = false,
    val themeLight: String = "light",
    val themeDark: String = "mono",
    /** Text size step: 0 Small, 1 Default, 2 Large, 3 Larger. */
    val textSize: Int = 1,
)

/** The Appearance choice in [AppSettings]. */
val AppSettings.appearance: dev.benfranklin.tether.ui.theme.Appearance
    get() = dev.benfranklin.tether.ui.theme.Appearance(theme, themeFollowSystem, themeLight, themeDark, textSize)

fun AppSettings.withAppearance(a: dev.benfranklin.tether.ui.theme.Appearance): AppSettings =
    copy(theme = a.themeId, themeFollowSystem = a.followSystem, themeLight = a.lightId, themeDark = a.darkId, textSize = a.textSize)

/** App settings as a hot flow, persisted atomically on every change. */
class SettingsRepository(context: Context) {
    private val file = JsonFile(File(context.filesDir, "settings.json"), AppSettings.serializer())
    /**
     * 0.5.4: settings were on disk before this launch, so this app was used before: a missing
     * "What's new" version means an update from a build that never saved one, not a fresh install.
     */
    val existedBefore: Boolean = File(context.filesDir, "settings.json").exists()
    private val state = MutableStateFlow(file.read() ?: AppSettings())

    init {
        // 0.5.3: the custom update feed setting is gone; rewrite once so it isn't kept on disk.
        val raw = File(context.filesDir, "settings.json")
        if (raw.exists() && raw.readText().contains("\"updateFeed\"")) file.write(state.value)
    }

    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    /** Called after every change (on the caller's thread). */
    @Volatile var onChanged: ((AppSettings) -> Unit)? = null

    fun update(change: (AppSettings) -> AppSettings) {
        val next = synchronized(this) {
            val n = change(state.value)
            if (n == state.value) return
            state.value = n
            file.write(n)
            n
        }
        onChanged?.invoke(next)
    }
}
