@file:Suppress("MaxLineLength") // one checklist row per line reads best

package dev.benfranklin.tether.v5

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.proto.PermInfo
import dev.benfranklin.tether.notify.MirrorListenerService
import dev.benfranklin.tether.screen.ControlService

/**
 * The setup checklist: every permission and special access Tether can use, whether it is
 * granted, and how to fix it. The same list goes to the Mac (PHONE_STATUS) so its checklist
 * shows the phone side too.
 */
object Setup {
    /** How a row is fixed: a runtime permission request, a Settings screen, or adb steps shown in the app. */
    sealed interface Fix {
        data class Runtime(val permissions: List<String>) : Fix
        data class Screen(val intent: Intent) : Fix
        data class Adb(val commands: List<String>, val note: String) : Fix
    }

    data class Item(val id: String, val label: String, val why: String, val ok: Boolean, val required: Boolean, val fix: Fix?)

    private const val PKG_PLACEHOLDER = "in.benfranklin.tether"

    fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun allFilesAccess(c: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        has(c, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun photosAccess(c: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        has(c, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        has(c, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun videoAccess(c: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        has(c, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        has(c, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun notificationAccess(c: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun dndAccess(c: Context): Boolean = c.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    fun batteryUnrestricted(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    fun readLogs(c: Context): Boolean = has(c, Manifest.permission.READ_LOGS)

    /** Android 15+: the receive_sensitive_notifications app op (null below 15, where nothing is hidden). */
    fun sensitive(c: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return null
        return runCatching {
            ops.unsafeCheckOpNoThrow("android:receive_sensitive_notifications", android.os.Process.myUid(), c.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    private fun nearbyPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun adb(c: Context, vararg cmd: String) = cmd.map { it.replace(PKG_PLACEHOLDER, c.packageName) }

    private fun pkgUri(c: Context) = Uri.parse("package:" + c.packageName)

    @Suppress("LongMethod")
    fun items(c: Context): List<Item> {
        val sdk = Build.VERSION.SDK_INT
        val list = ArrayList<Item>()
        list += Item(
            "nearby", "Nearby devices", "Find and connect to your Mac over Bluetooth",
            nearbyPermissions().all { has(c, it) }, true, Fix.Runtime(nearbyPermissions()),
        )
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            list += Item(
                "postnotif", "Notifications", "Tether's own alerts (link status, files, calls from the Mac)",
                has(c, Manifest.permission.POST_NOTIFICATIONS), true, Fix.Runtime(listOf(Manifest.permission.POST_NOTIFICATIONS)),
            )
        }
        list += Item(
            "notif", "Notification access", "Mirror notifications to the Mac, reply, one-time codes",
            notificationAccess(c), true, Fix.Screen(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        )
        list += Item(
            "battery", "Battery unrestricted", "Keep the link alive in the background",
            batteryUnrestricted(c), true, Fix.Screen(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
        )
        val sms = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        list += Item("sms", "SMS", "Messages on the Mac, codes from texts", sms.all { has(c, it) }, false, Fix.Runtime(sms))
        val phone = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS)
        list += Item("phone", "Phone", "Call alerts, answering and calling from the Mac", phone.all { has(c, it) }, false, Fix.Runtime(phone))
        list += Item(
            "calllog", "Call log", "Caller numbers and recent calls on the Mac",
            has(c, Manifest.permission.READ_CALL_LOG), false, Fix.Runtime(listOf(Manifest.permission.READ_CALL_LOG)),
        )
        list += Item(
            "contacts", "Contacts", "Names instead of numbers, contact search on the Mac",
            has(c, Manifest.permission.READ_CONTACTS), false, Fix.Runtime(listOf(Manifest.permission.READ_CONTACTS)),
        )
        val photoPerms = if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        list += Item(
            "photos", "Photos and videos", "Photos browser, screenshots and photo cards on the Mac",
            photosAccess(c), false, Fix.Runtime(photoPerms),
        )
        list += Item(
            "allfiles", "All files access", "Browse Downloads, Documents and WhatsApp media from the Mac",
            allFilesAccess(c), false,
            if (sdk >= Build.VERSION_CODES.R) {
                Fix.Screen(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(c)))
            } else {
                Fix.Runtime(listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            },
        )
        list += Item(
            "dnd", "Do Not Disturb access", "Silence from the Mac and Focus sync",
            dndAccess(c), false, Fix.Screen(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
        )
        list += Item(
            "accessibility", "Accessibility (Tether control)", "Control the screen and type from the Mac, current browser tab",
            ControlService.enabledInSettings(c), false, Fix.Screen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        )
        list += Item(
            "overlay", "Display over other apps", "Open links sent from the Mac, clipboard auto mode",
            Settings.canDrawOverlays(c), false, Fix.Screen(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c))),
        )
        list += Item(
            "install", "Install updates", "Install Tether updates from inside the app",
            canInstall(c), false, Fix.Screen(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(c))),
        )
        list += Item(
            "readlogs", "Read logs (adb)", "Clipboard auto mode: send copies without opening Tether",
            readLogs(c), false,
            Fix.Adb(
                adb(c, "adb shell pm grant $PKG_PLACEHOLDER android.permission.READ_LOGS", "adb shell am force-stop $PKG_PLACEHOLDER"),
                "Needs a computer with adb once (wireless debugging works). See Settings › On your Mac › Clipboard.",
            ),
        )
        sensitive(c)?.let { ok ->
            list += Item(
                "sensitive", "Sensitive notifications (adb)", "Android 15+ hides one-time codes from Tether unless allowed",
                ok, false,
                Fix.Adb(
                    adb(c, "adb shell appops set $PKG_PLACEHOLDER RECEIVE_SENSITIVE_NOTIFICATIONS allow"),
                    "Needs adb once. Without it, codes in notifications show as hidden (codes from SMS still work).",
                ),
            )
        }
        return list
    }

    fun missingRequired(c: Context): Int = items(c).count { it.required && !it.ok }

    fun perms(c: Context): List<PermInfo> = items(c).map { PermInfo(it.id, it.label, it.ok, it.required) }

    /** Notification listener rebind helper (some OEMs drop the binding after updates). */
    fun rebindListener(c: Context) {
        if (notificationAccess(c)) {
            runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(c, MirrorListenerService::class.java),
                )
            }
        }
    }
}
