package dev.benfranklin.tether.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.features.CallTracker
import dev.benfranklin.tether.core.proto.CallActions
import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.sms.Contacts
import dev.benfranklin.tether.sms.SmsStore
import java.util.UUID

/**
 * Incoming-call alerts for the Mac. Listens to PHONE_STATE broadcasts (READ_PHONE_STATE; the
 * number also needs READ_CALL_LOG), names the caller from contacts (READ_CONTACTS) or from
 * the dialer's call notification, and runs Decline (TelecomManager.endCall, needs
 * ANSWER_PHONE_CALLS) and Silence (mutes the ring stream, needs Do Not Disturb access).
 * Everything runs on the main thread; events go to [onEvent].
 */
class CallMonitor(private val context: Context, private val onEvent: (CallState) -> Unit) {
    private val tracker = CallTracker { UUID.randomUUID().toString().take(ID_LEN) }
    private val contacts = Contacts(context)
    private var running = false
    private var mutedRing = false
    /** Caller name from the dialer's notification, used when contacts can't be read. */
    private var hintName: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> CallTracker.RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CallTracker.OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> CallTracker.IDLE
                else -> return
            }
            @Suppress("DEPRECATION")
            val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            onState(state, number)
        }
    }

    fun start() {
        if (running || !canWatch(context)) return
        running = true
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i("call.watch", "number" to SmsStore.granted(context, Manifest.permission.READ_CALL_LOG))
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        restoreRing()
    }

    /** The dialer posted its incoming-call notification; its title is usually the caller. */
    fun dialerTitle(title: String?) {
        // Contacts are better when we can read them; generic titles ("Incoming call") say nothing.
        if (title.isNullOrBlank() || canNameCallers(context) || title.contains("call", ignoreCase = true)) return
        hintName = title
        if (tracker.ringingId == null) return
        tracker.onState(CallTracker.RINGING, null, title, System.currentTimeMillis())?.let(onEvent)
    }

    private fun onState(state: Int, number: String?) {
        val name = number?.let { contacts.lookup(it) } ?: hintName?.takeIf { state == CallTracker.RINGING }
        val ev = tracker.onState(state, number, name, System.currentTimeMillis()) ?: return
        if (state == CallTracker.IDLE) {
            hintName = null
            restoreRing()
        }
        onEvent(ev)
    }

    /** Mac pressed Decline or Silence on call [id]. Returns null on success or a reason. */
    @SuppressLint("MissingPermission")
    fun act(id: String, action: String): String? {
        if (tracker.ringingId != id) return "no_call"
        return when (action) {
            CallActions.DECLINE -> {
                if (!canDecline(context)) return "not_allowed"
                val tm = context.getSystemService(TelecomManager::class.java) ?: return "unsupported"
                try {
                    @Suppress("DEPRECATION")
                    if (tm.endCall()) {
                        tracker.declinedFromMac()
                        null
                    } else {
                        "failed"
                    }
                } catch (e: SecurityException) {
                    Log.w("call.decline_denied", "err" to e.message)
                    "not_allowed"
                }
            }
            CallActions.SILENCE -> silence()
            else -> "unsupported"
        }
    }

    private fun silence(): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm == null || !nm.isNotificationPolicyAccessGranted) return "needs_dnd_access"
        val am = context.getSystemService(AudioManager::class.java) ?: return "unsupported"
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            mutedRing = true
            null
        } catch (e: SecurityException) {
            Log.w("call.silence_denied", "err" to e.message)
            "not_allowed"
        }
    }

    private fun restoreRing() {
        if (!mutedRing) return
        mutedRing = false
        try {
            context.getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: SecurityException) {
            Log.w("call.unmute_denied", "err" to e.message)
        }
    }

    companion object {
        private const val ID_LEN = 12

        fun canWatch(c: Context) = SmsStore.granted(c, Manifest.permission.READ_PHONE_STATE)
        fun canNameCallers(c: Context) =
            SmsStore.granted(c, Manifest.permission.READ_CALL_LOG) && SmsStore.granted(c, Manifest.permission.READ_CONTACTS)
        fun canDecline(c: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            SmsStore.granted(c, Manifest.permission.ANSWER_PHONE_CALLS)
        fun canSilence(c: Context) = c.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    }
}
