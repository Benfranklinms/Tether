package dev.benfranklin.tether.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.features.CallControls
import dev.benfranklin.tether.core.features.CallTracker
import dev.benfranklin.tether.core.features.LiveCallTracker
import dev.benfranklin.tether.core.proto.CallAction
import dev.benfranklin.tether.core.proto.CallActions
import dev.benfranklin.tether.core.proto.CallActions4
import dev.benfranklin.tether.core.proto.CallLive
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.sms.Contacts
import dev.benfranklin.tether.sms.SmsStore
import java.util.UUID

/**
 * Calls for the Mac. Listens to PHONE_STATE broadcasts (READ_PHONE_STATE; the number also
 * needs READ_CALL_LOG) and names the caller from contacts (READ_CONTACTS) or the dialer's
 * call notification. Runs the Mac's call actions:
 *  - Answer: TelecomManager.acceptRingingCall (ANSWER_PHONE_CALLS, Android 8+), optionally
 *    switching the speaker on once the call is up.
 *  - Decline / End: TelecomManager.endCall (ANSWER_PHONE_CALLS, Android 9+).
 *  - Silence: mutes the ring stream (needs Do Not Disturb access).
 *  - Speaker / Mute: AudioManager, verified by reading the state back. Android lets apps that
 *    aren't the phone app do this on most phones, not all; a control that doesn't take effect
 *    is reported and hidden on the Mac instead of staying as a broken button.
 *  - Hold needs the default-phone-app role (InCallService), so it is never offered.
 * Call audio itself always stays on the phone (or its earbuds): Android has no API for
 * another app to route cellular call audio to a computer.
 * Everything runs on the main thread; updates go to [onUpdate].
 */
class CallMonitor(private val context: Context, private val onUpdate: (LiveCallTracker.Update) -> Unit) {
    val tracker = LiveCallTracker({ UUID.randomUUID().toString().take(ID_LEN) })
    private val contacts = Contacts(context)
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var running = false
    private var mutedRing = false
    /** Caller name from the dialer's notification, used when contacts can't be read. */
    private var hintName: String? = null
    /** Answered from the Mac with "speaker on": applied once the call is off-hook. */
    private var speakerOnAnswer = false
    /** Set when Android ignored a speaker / mute change on this phone: the control is hidden. */
    private var speakerBroken = false
    private var muteBroken = false

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

    /** Polls speaker / mic state while a call is up (the user can change them on the phone too). */
    private val audioPoll = object : Runnable {
        override fun run() {
            if (!running || !tracker.isOffhook) return
            publishAudio()
            main.postDelayed(this, AUDIO_POLL_MS)
        }
    }

    val isRunning: Boolean get() = running

    fun start() {
        if (running || !canWatch(context)) return
        running = true
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        refreshControls()
        Log.i("call.watch", "number" to SmsStore.granted(context, Manifest.permission.READ_CALL_LOG), "controls" to tracker.controls)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(audioPoll)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        restoreRing()
    }

    /** Permissions may have changed: recompute which controls work and tell the Mac. */
    fun refreshControls() {
        val c = CallControls(
            answer = canAnswer(context), end = canEnd(context),
            speaker = audio != null && !speakerBroken, mute = audio != null && !muteBroken, hold = false,
        )
        tracker.update(controls = c, now = now())?.let { onUpdate(LiveCallTracker.Update(null, it)) }
    }

    /** The Mac is about to place a call to [number]: the next outgoing call is that one. */
    fun expectOutgoing(number: String, name: String?, sub: Int?) = tracker.expectOutgoing(number, name, sub, now())

    /** The dialer posted its call notification; its title is usually the caller. */
    fun dialerTitle(title: String?) {
        // Contacts are better when we can read them; generic titles ("Incoming call") say nothing.
        if (title.isNullOrBlank() || canNameCallers(context) || title.contains("call", ignoreCase = true)) return
        hintName = title
        if (tracker.currentId == null) return
        val u = tracker.nameFound(title, now())
        if (u.legacy != null || u.live != null) onUpdate(u)
    }

    private fun onState(state: Int, number: String?) {
        val name = number?.let { contacts.lookup(it) } ?: hintName?.takeIf { state == CallTracker.RINGING }
        val u = tracker.onState(state, number, name, now())
        when (state) {
            CallTracker.IDLE -> {
                hintName = null
                speakerOnAnswer = false
                main.removeCallbacks(audioPoll)
                restoreRing()
            }
            CallTracker.OFFHOOK -> {
                restoreRing()
                if (speakerOnAnswer) {
                    speakerOnAnswer = false
                    main.postDelayed({ applySpeaker(true, retry = true) }, SPEAKER_DELAY_MS)
                }
                main.removeCallbacks(audioPoll)
                main.postDelayed(audioPoll, AUDIO_POLL_MS)
            }
        }
        if (u.legacy != null || u.live != null) onUpdate(u)
    }

    /** The current call card (sent to a Mac that just connected). */
    fun current(): CallLive? = tracker.current(now())

    /**
     * Runs a Mac call action. Returns null when it was carried out (the resulting state change
     * reaches the Mac through the normal updates) or a reason: "no_call", "not_allowed",
     * "needs_dnd_access", "unsupported", "failed".
     */
    @SuppressLint("MissingPermission") // every path checks its permission first
    fun act(a: CallAction): String? {
        val ringing = tracker.ringingId == a.id
        val current = tracker.currentId == a.id
        return when (a.action) {
            CallActions.DECLINE -> if (!ringing) "no_call" else endCall().also { if (it == null) tracker.declinedFromMac() }
            CallActions.SILENCE -> if (!ringing) "no_call" else silence()
            CallActions4.ANSWER -> if (!ringing) "no_call" else answer(a.speaker == true)
            CallActions4.END -> if (!current) "no_call" else endCall()
            CallActions4.SPEAKER -> if (!current || !tracker.isOffhook) "no_call" else applySpeaker(a.on ?: !tracker.speaker, retry = false)
            CallActions4.MUTE -> if (!current || !tracker.isOffhook) "no_call" else applyMute(a.on ?: !tracker.muted)
            else -> "unsupported" // hold, and anything newer
        }
    }

    @SuppressLint("MissingPermission")
    private fun answer(speaker: Boolean): String? {
        if (!canAnswer(context)) return "not_allowed"
        val tm = context.getSystemService(TelecomManager::class.java) ?: return "unsupported"
        return try {
            speakerOnAnswer = speaker
            tm.acceptRingingCall()
            Log.i("call.answer", "speaker" to speaker)
            null
        } catch (e: SecurityException) {
            speakerOnAnswer = false
            Log.w("call.answer_denied", "err" to e.message)
            "not_allowed"
        }
    }

    @SuppressLint("MissingPermission")
    private fun endCall(): String? {
        if (!canEnd(context)) return "not_allowed"
        val tm = context.getSystemService(TelecomManager::class.java) ?: return "unsupported"
        return try {
            @Suppress("DEPRECATION")
            if (tm.endCall()) null else "failed"
        } catch (e: SecurityException) {
            Log.w("call.end_denied", "err" to e.message)
            "not_allowed"
        }
    }

    private fun silence(): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm == null || !nm.isNotificationPolicyAccessGranted) return "needs_dnd_access"
        val am = audio ?: return "unsupported"
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            mutedRing = true
            null
        } catch (e: SecurityException) {
            Log.w("call.silence_denied", "err" to e.message)
            "not_allowed"
        }
    }

    /** Sets the speaker, then checks that Android actually switched it. */
    private fun applySpeaker(on: Boolean, retry: Boolean): String? {
        val am = audio ?: return "unsupported"
        if (speakerBroken) return "unsupported"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) {
                    val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (dev == null || !am.setCommunicationDevice(dev)) {
                        @Suppress("DEPRECATION")
                        am.setSpeakerphoneOn(true)
                    }
                } else {
                    am.clearCommunicationDevice()
                    @Suppress("DEPRECATION")
                    am.setSpeakerphoneOn(false)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setSpeakerphoneOn(on)
            }
        } catch (e: SecurityException) {
            Log.w("call.speaker_denied", "err" to e.message)
            return "not_allowed"
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Log.w("call.speaker_failed", "err" to e.javaClass.simpleName)
            return "failed"
        }
        main.postDelayed({ verifySpeaker(on, retry) }, VERIFY_MS)
        return null
    }

    private fun verifySpeaker(wanted: Boolean, retry: Boolean) {
        if (!tracker.isOffhook) return
        if (speakerOn() == wanted) return publishAudio()
        if (retry) {
            main.postDelayed({ applySpeaker(wanted, retry = false) }, SPEAKER_DELAY_MS)
            return
        }
        Log.w("call.speaker_ignored", "wanted" to wanted)
        speakerBroken = true
        refreshControls()
        tracker.failed("unsupported", now())?.let { onUpdate(LiveCallTracker.Update(null, it)) }
    }

    private fun applyMute(on: Boolean): String? {
        val am = audio ?: return "unsupported"
        if (muteBroken) return "unsupported"
        try {
            am.isMicrophoneMute = on
        } catch (e: SecurityException) {
            Log.w("call.mute_denied", "err" to e.message)
            return "not_allowed"
        }
        main.postDelayed({
            if (!tracker.isOffhook) return@postDelayed
            if (am.isMicrophoneMute == on) {
                publishAudio()
            } else {
                Log.w("call.mute_ignored", "wanted" to on)
                muteBroken = true
                refreshControls()
                tracker.failed("unsupported", now())?.let { onUpdate(LiveCallTracker.Update(null, it)) }
            }
        }, VERIFY_MS)
        return null
    }

    private fun speakerOn(): Boolean {
        val am = audio ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }
    }

    private fun publishAudio() {
        val am = audio ?: return
        tracker.update(speaker = speakerOn(), muted = am.isMicrophoneMute, now = now())?.let {
            onUpdate(LiveCallTracker.Update(null, it))
        }
    }

    private fun restoreRing() {
        if (!mutedRing) return
        mutedRing = false
        try {
            audio?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: SecurityException) {
            Log.w("call.unmute_denied", "err" to e.message)
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val ID_LEN = 12
        private const val SPEAKER_DELAY_MS = 700L
        private const val VERIFY_MS = 400L
        private const val AUDIO_POLL_MS = 2_000L

        fun canWatch(c: Context) = SmsStore.granted(c, Manifest.permission.READ_PHONE_STATE)
        fun canNameCallers(c: Context) =
            SmsStore.granted(c, Manifest.permission.READ_CALL_LOG) && SmsStore.granted(c, Manifest.permission.READ_CONTACTS)
        fun canAnswer(c: Context) = SmsStore.granted(c, Manifest.permission.ANSWER_PHONE_CALLS)
        fun canEnd(c: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            SmsStore.granted(c, Manifest.permission.ANSWER_PHONE_CALLS)
        fun canDecline(c: Context) = canEnd(c)
        fun canSilence(c: Context) = c.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    }
}
