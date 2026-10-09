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
)

/** App settings as a hot flow, persisted atomically on every change. */
class SettingsRepository(context: Context) {
    private val file = JsonFile(File(context.filesDir, "settings.json"), AppSettings.serializer())
    private val state = MutableStateFlow(file.read() ?: AppSettings())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        file.write(next)
    }
}
