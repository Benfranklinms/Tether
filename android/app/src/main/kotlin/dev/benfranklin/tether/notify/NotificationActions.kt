package dev.benfranklin.tether.notify

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.benfranklin.tether.core.proto.NotifActionInfo
import dev.benfranklin.tether.log.Log

/**
 * The live (PendingIntent-backed) buttons of the notifications mirrored to the Mac, so a
 * button pressed on the Mac can be fired here. Replies fill the app's own RemoteInput and send
 * its PendingIntent, exactly as the notification shade (or a watch) does, so they work for any
 * app with an inline reply: WhatsApp, Messages, Telegram, Signal, Slack, Gmail and so on.
 */
object NotificationActions {
    class Live(
        val id: String,
        val title: String,
        val reply: Boolean,
        val intent: PendingIntent,
        val inputs: Array<RemoteInput>,
    ) {
        val info: NotifActionInfo get() = NotifActionInfo(id, title, reply)
    }

    data class Result(val ok: Boolean, val reason: String? = null)

    private const val MAX_KEYS = 300
    private const val MAX_ACTIONS = 4
    private const val MAX_TITLE = 40
    private val live = object : LinkedHashMap<String, List<Live>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Live>>?) = size > MAX_KEYS
    }

    /** The buttons worth offering on the Mac: titled, not contextual (smart) suggestions, free-form replies only. */
    fun extract(n: Notification): List<Live> {
        val out = ArrayList<Live>()
        n.actions?.forEachIndexed { i, a ->
            val pi = a.actionIntent ?: return@forEachIndexed
            val title = a.title?.toString()?.trim()?.take(MAX_TITLE).orEmpty()
            if (title.isEmpty()) return@forEachIndexed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && a.isContextual) return@forEachIndexed
            val inputs: Array<RemoteInput> = a.remoteInputs?.toList().orEmpty().toTypedArray()
            val freeForm = inputs.any { it.allowFreeFormInput }
            if (inputs.isNotEmpty() && !freeForm) return@forEachIndexed // canned choices only
            out += Live(if (freeForm) "reply$i" else "a$i", title, freeForm, pi, inputs)
        }
        // A reply first, then the rest, at most four.
        return out.sortedByDescending { it.reply }.take(MAX_ACTIONS)
    }

    @Synchronized
    fun register(key: String, actions: List<Live>) {
        if (actions.isEmpty()) live.remove(key) else live[key] = actions
    }

    @Synchronized
    fun remove(key: String) {
        live.remove(key)
    }

    @Synchronized
    private fun find(key: String, id: String): Live? = live[key]?.firstOrNull { it.id == id }

    /** Run button [id] of notification [key]; [text] is the reply for an inline-reply button. */
    fun perform(context: Context, key: String, id: String, text: String?): Result {
        val a = find(key, id) ?: return Result(false, "gone")
        return try {
            if (a.reply) {
                val reply = text?.trim().orEmpty()
                if (reply.isEmpty()) return Result(false, "empty")
                val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                val results = Bundle()
                for (input in a.inputs) results.putCharSequence(input.resultKey, reply)
                RemoteInput.addResultsToIntent(a.inputs, fill, results)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
                }
                send(context, a.intent, fill)
            } else {
                send(context, a.intent, null)
            }
            Log.i("notify.action", "reply" to a.reply, "ok" to true)
            Result(true)
        } catch (e: PendingIntent.CanceledException) {
            Log.w("notify.action_cancelled", "reason" to e.message)
            remove(key)
            Result(false, "gone")
        } catch (e: SecurityException) {
            Log.w("notify.action_denied", "reason" to e.message)
            Result(false, "denied")
        }
    }

    private fun send(context: Context, pi: PendingIntent, fill: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Actions that open an activity (e.g. "Mark as read" in some apps) need explicit
            // permission to start from the background on Android 14+.
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            pi.send(context, 0, fill, null, null, null, opts)
        } else {
            pi.send(context, 0, fill)
        }
    }
}
