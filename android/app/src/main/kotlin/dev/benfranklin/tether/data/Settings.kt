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
