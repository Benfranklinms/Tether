package dev.benfranklin.tether.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import dev.benfranklin.tether.R
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.service.Notifications
import dev.benfranklin.tether.sms.SmsStore

/**
 * Places calls the Mac asks for.
 *
 * Android 10+ blocks activity starts from the background, so `startActivity(ACTION_CALL)` from
 * Tether's service would silently do nothing while the phone sits in a pocket. Tether instead
 * uses TelecomManager.placeCall (CALL_PHONE): a call into the system telecom service, not an
 * activity start, so the background-activity rules (incl. Android 14/15/16's stricter ones)
 * don't apply; Telecom brings up the phone's own in-call screen itself. Verified on the CI
 * emulator from a process with no visible activity (DeviceCallTests.placeCallFromBackground).
 * If Telecom refuses for any other reason, Tether posts a "Tap to call" notification whose
 * PendingIntent starts ACTION_CALL; a user tap is always allowed to start an activity.
 */
class Dialer(private val context: Context) {
    data class Result(val ok: Boolean, val reason: String? = null, val via: String? = null)

    fun canCall() = SmsStore.granted(context, Manifest.permission.CALL_PHONE)

    @SuppressLint("MissingPermission") // canCall() is checked first
    fun place(number: String, sub: Int?, label: String): Result {
        if (!canCall()) return Result(false, "needs_permission")
        if (isEmergency(number)) return Result(false, "emergency")
        val tm = context.getSystemService(TelecomManager::class.java) ?: return Result(false, "unsupported")
        val extras = Bundle()
        if (sub != null) {
            val handle = accountFor(tm, sub) ?: return Result(false, "no_sim")
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        val uri = Uri.fromParts("tel", number, null)
        return try {
            tm.placeCall(uri, extras)
            Log.i("call.dial", "via" to "telecom", "sub" to (sub != null))
            Result(true, via = VIA_TELECOM)
        } catch (e: SecurityException) {
            Log.w("call.dial_denied", "err" to e.message)
            Result(false, "needs_permission")
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Log.w("call.dial_failed", "err" to e.javaClass.simpleName, "fallback" to "notification")
            tapToCall(uri, label)
        }
    }

    /** Fallback: a notification the user taps to start the call (always allowed to open an activity). */
    fun tapToCall(uri: Uri, label: String): Result {
        val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(context, uri.hashCode(), intent, flags)
        val n = NotificationCompat.Builder(context, Notifications.CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(context.getString(R.string.call_tap_title, label))
            .setContentText(context.getString(R.string.call_tap_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(TAP_TIMEOUT_MS)
            .build()
        Notifications.postRaw(context, TAP_ID, n)
        return Result(true, via = VIA_NOTIFICATION)
    }

    fun cancelTapToCall() = Notifications.cancelId(context, TAP_ID)

    @SuppressLint("MissingPermission")
    private fun accountFor(tm: TelecomManager, sub: Int): PhoneAccountHandle? {
        if (!SmsStore.granted(context, Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            val handles = tm.callCapablePhoneAccounts
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val tel = context.getSystemService(TelephonyManager::class.java)
                handles.firstOrNull { tel?.getSubscriptionId(it) == sub }?.let { return it }
            }
            // Older Androids: the handle id is the subscription id or the SIM's ICCID.
            val info = context.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(sub)
            val iccid = info?.iccId?.takeIf { it.isNotBlank() }
            handles.firstOrNull { it.id == sub.toString() || iccid != null && it.id.startsWith(iccid) }
        } catch (e: SecurityException) {
            Log.w("call.accounts_denied", "err" to e.message)
            null
        }
    }

    private fun isEmergency(number: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == true
        } else {
            @Suppress("DEPRECATION")
            android.telephony.PhoneNumberUtils.isEmergencyNumber(number)
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        Log.w("call.emergency_check_failed", "err" to e.javaClass.simpleName)
        false
    }

    companion object {
        const val VIA_TELECOM = "telecom"
        const val VIA_NOTIFICATION = "notification"
        private const val TAP_ID = 0x7E1E
        private const val TAP_TIMEOUT_MS = 60_000L
    }
}
