package dev.benfranklin.tether.notify

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Process
import dev.benfranklin.tether.core.features.SensitiveNotifications
import dev.benfranklin.tether.core.proto.NotifAccess
import dev.benfranklin.tether.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 15+ hides one-time codes and other "sensitive" notification content from listeners
 * that don't hold RECEIVE_SENSITIVE_NOTIFICATIONS (granted to OEM apps, the launcher and
 * companion-device roles, not to a sideloaded app). Detects the redaction by comparing with
 * the system's own (localised) "Sensitive notification content hidden" string and reads the
 * app op that the adb fix sets, so the phone and the Mac can show the guide, and then that
 * it's fixed. Thread-safe.
 */
class SensitiveWatch(private val context: Context) {
    private val logic = SensitiveNotifications(systemStrings(), Build.VERSION.SDK_INT, Build.MANUFACTURER.orEmpty())
    private val _state = MutableStateFlow(Snapshot(redacting = false, granted = null, applies = applies))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    data class Snapshot(val redacting: Boolean, val granted: Boolean?, val applies: Boolean)

    /** A notification arrived; returns a status for the Mac when it changed. */
    @Synchronized
    fun onNotification(title: String?, text: String?): NotifAccess? {
        val s = logic.onNotification(title, text, System.currentTimeMillis()) ?: return null
        Log.i("notify.sensitive_hidden", "granted" to s.granted)
        publish(s)
        return s
    }

    /** Re-reads the app op (app start, coming back to the app); returns a status when it changed. */
    @Synchronized
    fun recheck(): NotifAccess? {
        if (!applies) return null
        val s = logic.onGrant(opAllowed(), System.currentTimeMillis()) ?: return null
        Log.i("notify.sensitive_access", "granted" to s.granted, "redacting" to s.redacting)
        publish(s)
        return s
    }

    @Synchronized
    fun status(): NotifAccess = logic.status(System.currentTimeMillis()).also(::publish)

    private fun publish(s: NotifAccess) {
        _state.value = Snapshot(s.redacting, s.granted, applies)
    }

    /** True / false when Android reports the app op, null when this build can't say. */
    @SuppressLint("NewApi")
    private fun opAllowed(): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops?.unsafeCheckOpNoThrow(SensitiveNotifications.OP, Process.myUid(), context.packageName)
        when (mode) {
            null -> null
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> null
            else -> false
        }
    } catch (e: IllegalArgumentException) {
        // The op name is unknown on this build.
        Log.i("notify.sensitive_op_unknown", "err" to e.message)
        null
    } catch (e: SecurityException) {
        Log.w("notify.sensitive_op_denied", "err" to e.message)
        null
    }

    companion object {
        val applies: Boolean get() = Build.VERSION.SDK_INT >= SensitiveNotifications.ANDROID_15

        /** The system's redaction text in the phone's language (hidden resource, looked up by name). */
        @SuppressLint("DiscouragedApi")
        fun systemStrings(): List<String> = listOf("redacted_notification_message", "redacted_otp_notification_text").mapNotNull { n ->
            try {
                val id = Resources.getSystem().getIdentifier(n, "string", "android")
                if (id != 0) Resources.getSystem().getString(id) else null
            } catch (_: Resources.NotFoundException) {
                null
            }
        }

        fun adbCommand(context: Context) = SensitiveNotifications.adbCommand(context.packageName)
    }
}
