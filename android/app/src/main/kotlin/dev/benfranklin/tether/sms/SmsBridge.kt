package dev.benfranklin.tether.sms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.proto.SimInfo
import dev.benfranklin.tether.core.proto.SmsMessage
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.log.LoggedExecutor
import java.util.concurrent.ExecutorService

/**
 * The phone side of Messages on the Mac: sending (SmsManager, per SIM, multipart), watching
 * the provider for new/updated messages, and listing SIMs. Results arrive on the callbacks
 * from a background thread.
 */
class SmsBridge(
    private val context: Context,
    private val onNew: (SmsMessage) -> Unit,
    private val onSent: (ref: String, ok: Boolean, reason: String?) -> Unit,
    private val onSimsChanged: () -> Unit,
) {
    val store = SmsStore(context)
    val io: ExecutorService = LoggedExecutor("tether-sms")
    private val main = Handler(Looper.getMainLooper())
    private var watching = false
    private var lastId = 0L
    private var receiving = false
    /** Our outgoing rows still pending, re-read on every change so the Mac sees sent/failed. */
    private val pendingRows = HashSet<Long>()
    private val sends = HashMap<String, SendState>()

    private class SendState(var partsLeft: Int, var failure: String? = null)

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            io.execute { scan() }
        }
    }

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val ref = i.getStringExtra(EXTRA_REF) ?: return
            val code = resultCode
            io.execute { partSent(ref, code) }
        }
    }

    /** SMS_RECEIVED (RECEIVE_SMS) nudges a scan; some phones write the provider late, so look twice. */
    private val incomingReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            main.postDelayed({ io.execute { scan() } }, NUDGE_MS)
            main.postDelayed({ io.execute { scan() } }, NUDGE_LATE_MS)
        }
    }

    private val subListener by lazy {
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() = onSimsChanged()
        }
    }

    init {
        ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---------------- watching ----------------

    /** Start or stop reporting new messages (needs READ_SMS). */
    fun watch(on: Boolean) = main.post {
        if (on == watching) return@post
        if (on && !store.canRead()) return@post
        watching = on
        if (on) {
            io.execute { lastId = store.latestSmsId() }
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            if (canReadPhone()) watchSims(true)
            if (SmsStore.granted(context, Manifest.permission.RECEIVE_SMS)) {
                ContextCompat.registerReceiver(
                    context, incomingReceiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION), ContextCompat.RECEIVER_EXPORTED,
                )
                receiving = true
            }
        } else {
            context.contentResolver.unregisterContentObserver(observer)
            watchSims(false)
            if (receiving) context.unregisterReceiver(incomingReceiver)
            receiving = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun watchSims(on: Boolean) {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return
        try {
            if (on) sm.addOnSubscriptionsChangedListener(subListener) else sm.removeOnSubscriptionsChangedListener(subListener)
        } catch (e: SecurityException) {
            Log.w("sms.sims_listen_denied", "err" to e.message)
        }
    }

    private fun scan() {
        try {
            scanNow()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e("sms.scan_failed", e)
        }
    }

    private fun scanNow() {
        if (!store.canRead()) return
        val fresh = store.since(lastId)
        for (m in fresh) {
            val raw = m.id.removePrefix("s").toLongOrNull() ?: continue
            if (raw > lastId) lastId = raw
            if (m.status == "pending") pendingRows += raw
            onNew(m)
        }
        if (pendingRows.isNotEmpty()) {
            val now = store.byIds(pendingRows.toList())
            for (m in now) {
                val raw = m.id.removePrefix("s").toLongOrNull() ?: continue
                if (m.status != "pending") {
                    pendingRows -= raw
                    onNew(m)
                }
            }
            // Rows deleted while pending (cancelled by the user) stop being tracked.
            pendingRows.retainAll(now.mapNotNull { it.id.removePrefix("s").toLongOrNull() }.toSet())
        }
    }

    // ---------------- SIMs ----------------

    fun canReadPhone() = SmsStore.granted(context, Manifest.permission.READ_PHONE_STATE)

    @SuppressLint("MissingPermission")
    fun sims(): List<SimInfo> {
        if (!canReadPhone()) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }.map {
                val label = it.displayName?.toString()?.takeIf { n -> n.isNotBlank() } ?: it.carrierName?.toString().orEmpty()
                SimInfo(it.subscriptionId, it.simSlotIndex, label.ifBlank { "SIM ${it.simSlotIndex + 1}" })
            }
        } catch (e: SecurityException) {
            Log.w("sms.sims_denied", "err" to e.message)
            emptyList()
        }
    }

    // ---------------- sending ----------------

    fun canSend() = SmsStore.granted(context, Manifest.permission.SEND_SMS)

    /** Sends [body] to each of [to] from SIM [sub] (default SIM when null). Reports once through onSent. */
    @SuppressLint("MissingPermission") // checked by canSend()
    fun send(ref: String, to: List<String>, body: String, sub: Int?) = io.execute {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val reason = when {
            !canSend() -> "needs_permission"
            recipients.isEmpty() -> "no_recipient"
            body.isBlank() -> "empty"
            recipients.size > MAX_RECIPIENTS -> "too_many_recipients"
            else -> null
        }
        if (reason != null) return@execute onSent(ref, false, reason)
        val mgr = manager(sub) ?: return@execute onSent(ref, false, "no_sim")
        try {
            val partsPer = recipients.map { mgr.divideMessage(body) }
            sends[ref] = SendState(partsPer.sumOf { it.size })
            recipients.forEachIndexed { i, addr ->
                val parts = partsPer[i]
                val intents = ArrayList(parts.indices.map { sentIntent(ref, i * PARTS_STRIDE + it) })
                if (parts.size == 1) {
                    mgr.sendTextMessage(addr, null, parts[0], intents[0], null)
                } else {
                    mgr.sendMultipartTextMessage(addr, null, parts, intents, null)
                }
            }
            Log.i("sms.sending", "recipients" to recipients.size)
        } catch (e: SecurityException) {
            sends.remove(ref)
            onSent(ref, false, "needs_permission")
            Log.w("sms.send_denied", "err" to e.message)
        } catch (e: IllegalArgumentException) {
            sends.remove(ref)
            onSent(ref, false, "invalid")
            Log.w("sms.send_invalid", "err" to e.message)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            sends.remove(ref)
            onSent(ref, false, "failed")
            Log.e("sms.send_failed", e)
        }
    }

    private fun partSent(ref: String, code: Int) {
        val st = sends[ref] ?: return
        if (code != Activity.RESULT_OK && st.failure == null) st.failure = sendFailure(code)
        st.partsLeft--
        if (st.partsLeft <= 0) {
            sends.remove(ref)
            onSent(ref, st.failure == null, st.failure)
            io.execute { scan() }
        }
    }

    private fun sentIntent(ref: String, n: Int): PendingIntent {
        val i = Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_REF, ref)
        // Unique request code per part so the extras aren't merged.
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        return PendingIntent.getBroadcast(context, (ref.hashCode() * PRIME) xor n, i, flags)
    }

    @Suppress("DEPRECATION")
    private fun manager(sub: Int?): SmsManager? {
        val id = sub ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val base = context.getSystemService(SmsManager::class.java) ?: return null
                if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(id) else base
            } else if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(id)
            } else {
                SmsManager.getDefault()
            }
        } catch (e: IllegalArgumentException) {
            Log.w("sms.no_manager", "err" to e.message)
            null
        }
    }

    companion object {
        private const val ACTION_SENT = "dev.benfranklin.tether.SMS_SENT"
        private const val EXTRA_REF = "ref"
        private const val MAX_RECIPIENTS = 20
        private const val PARTS_STRIDE = 1000
        private const val PRIME = 31
        private const val NUDGE_MS = 600L
        private const val NUDGE_LATE_MS = 2500L

        fun sendFailure(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "invalid"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short_code"
            else -> "failed"
        }
    }
}
