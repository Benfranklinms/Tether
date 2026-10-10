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
    /** Images on the clipboard travel both ways. */
    val clipImages: Boolean = true,
    /** Custom update feed (https); null = the default public feed, then the GitHub API. */
    val updateFeed: String? = null,
    /** Check for updates when Tether opens (at most daily). */
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheck: Long = 0,
)

/** App settings as a hot flow, persisted atomically on every change. */
class SettingsRepository(context: Context) {
    private val file = JsonFile(File(context.filesDir, "settings.json"), AppSettings.serializer())
    private val state = MutableStateFlow(file.read() ?: AppSettings())
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
