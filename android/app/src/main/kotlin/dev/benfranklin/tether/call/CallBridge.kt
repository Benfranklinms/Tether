package dev.benfranklin.tether.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.benfranklin.tether.core.features.Dialable
import dev.benfranklin.tether.core.features.LiveCallTracker
import dev.benfranklin.tether.core.proto.CallAction
import dev.benfranklin.tether.core.proto.CallActions
import dev.benfranklin.tether.core.proto.CallActions4
import dev.benfranklin.tether.core.proto.CallDial
import dev.benfranklin.tether.core.proto.CallDialResult
import dev.benfranklin.tether.core.proto.CallDirs
import dev.benfranklin.tether.core.proto.CallLive
import dev.benfranklin.tether.core.proto.CallLiveStates
import dev.benfranklin.tether.core.proto.CallLog
import dev.benfranklin.tether.core.proto.CallLogReq
import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.core.proto.Caps3
import dev.benfranklin.tether.core.proto.Caps4
import dev.benfranklin.tether.core.proto.ContactSearch
import dev.benfranklin.tether.core.proto.Contacts
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.log.LoggedExecutor
import dev.benfranklin.tether.sms.Contacts as ContactNames
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Everything about calls between the protocol and Android: incoming-call alerts (0.3),
 * dialing from the Mac, the live call card and its controls, call history and contact search
 * (0.4). Protocol work runs on the runtime's [engine] thread, Android call APIs on the main
 * thread, provider reads on a small I/O thread. Each message type is only sent when the Mac
 * announced the matching capability, so 0.3 Macs keep getting exactly what they got before.
 */
class CallBridge(
    private val app: Context,
    private val settings: () -> AppSettings,
    private val engine: ScheduledExecutorService,
    private val send: (Message) -> Boolean,
    private val supports: (String) -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = LoggedExecutor("tether-calls")
    private val names = ContactNames(app)
    val monitor = CallMonitor(app) { u -> engine.execute { push(u) } }
    private val dialer = Dialer(app)
    private val store = CallLogStore(app, names)

    /** A dial request waiting for the phone to go off-hook (engine thread). */
    private var pendingDial: String? = null
    private var dialTimeout: ScheduledFuture<*>? = null

    /** Start/stop watching calls (engine thread). */
    fun apply(paired: Boolean, s: AppSettings) {
        val watch = paired && (s.callAlerts || s.allowCalling)
        main.post {
            if (watch) monitor.start() else monitor.stop()
            if (watch) monitor.refreshControls()
        }
    }

    /** Permissions changed: the set of working controls may have too. */
    fun permissionsChanged() = main.post { if (monitor.isRunning) monitor.refreshControls() }

    /** The Mac (re)connected: show it the call that is up, if any (engine thread). */
    fun connected() = main.post {
        val live = monitor.current() ?: return@post
        engine.execute { if (liveAllowed(live)) send(live) }
    }

    /** The Mac shows calls itself, so the dialer's call notifications aren't mirrored. */
    fun alertsActive(connected: Boolean, caps: Set<String>): Boolean =
        settings().callAlerts && CallMonitor.canWatch(app) && Caps3.CALL in caps && connected

    fun dialerTitle(title: String?) = main.post { monitor.dialerTitle(title) }

    /** Handles a call message from the Mac (engine thread); false when it isn't one. */
    fun handle(m: Message): Boolean {
        when (m) {
            is CallAction -> onAction(m)
            is CallDial -> onDial(m)
            is CallLogReq -> onLogReq(m)
            is ContactSearch -> onContactSearch(m)
            else -> return false
        }
        return true
    }

    // ---------------- phone -> Mac ----------------

    private fun push(u: LiveCallTracker.Update) {
        val s = settings()
        u.legacy?.let { if (s.callAlerts && supports(Caps3.CALL)) send(it) }
        val live = u.live ?: return
        if (live.dir == CallDirs.OUT && live.state == CallLiveStates.DIALING) finishDial(live)
        if (liveAllowed(live)) send(live)
    }

    private fun liveAllowed(l: CallLive): Boolean {
        val s = settings()
        val wanted = if (l.dir == CallDirs.IN) s.callAlerts else s.allowCalling || s.callAlerts
        return wanted && supports(Caps4.CALL_CONTROL)
    }

    // ---------------- actions ----------------

    private fun onAction(a: CallAction) {
        val s = settings()
        val incomingOnly = a.action == CallActions.DECLINE || a.action == CallActions.SILENCE ||
            a.action == CallActions4.ANSWER
        val allowed = if (incomingOnly) s.callAlerts else s.callAlerts || s.allowCalling
        if (!allowed) return reply(a, "disabled", null)
        main.post {
            val reason = monitor.act(a)
            if (reason != null) {
                Log.i("call.action_failed", "action" to a.action, "reason" to reason)
                val card = monitor.tracker.failed(reason, System.currentTimeMillis())
                engine.execute { reply(a, reason, card) }
            }
        }
    }

    /** Tells the Mac why [a] failed: the 0.3 way (CALL_STATE with a reason) and on the live card. */
    private fun reply(a: CallAction, reason: String, card: CallLive?) {
        val now = System.currentTimeMillis()
        if (supports(Caps4.CALL_CONTROL)) {
            send(card ?: CallLive(a.id, CallDirs.IN, CallLiveStates.ENDED, ts = now, reason = reason))
        } else if (supports(Caps3.CALL)) {
            val state = if (reason == "disabled") "ended" else "ringing"
            send(CallState(a.id, state, ts = now, reason = reason))
        }
    }

    // ---------------- dialing ----------------

    private fun onDial(d: CallDial) {
        if (!settings().allowCalling) return dialResult(d.ref, false, "disabled")
        val number = Dialable.clean(d.number) ?: return dialResult(d.ref, false, "invalid_number")
        if (pendingDial != null) return dialResult(d.ref, false, "busy")
        pendingDial = d.ref
        dialTimeout = engine.schedule({ dialTimedOut(d.ref) }, DIAL_TIMEOUT_S, TimeUnit.SECONDS)
        io.execute {
            val name = names.lookup(number)
            main.post {
                if (monitor.tracker.isOffhook) {
                    engine.execute { failDial(d.ref, "busy") }
                    return@post
                }
                monitor.expectOutgoing(number, name, d.sub)
                val r = dialer.place(number, d.sub, name ?: number)
                val watching = monitor.isRunning
                engine.execute {
                    when {
                        !r.ok -> failDial(d.ref, r.reason ?: "failed")
                        // Without phone-state access Tether can't see the call start: report the request.
                        r.via == Dialer.VIA_NOTIFICATION || !watching -> {
                            clearDial()
                            dialResult(d.ref, true, null, r.via)
                        }
                    }
                }
            }
        }
    }

    private fun finishDial(live: CallLive) {
        val ref = pendingDial ?: return
        clearDial()
        main.post { dialer.cancelTapToCall() }
        dialResult(ref, true, null, Dialer.VIA_TELECOM)
        Log.i("call.dial_up", "sub" to (live.sub != null))
    }

    private fun dialTimedOut(ref: String) {
        if (pendingDial != ref) return
        Log.w("call.dial_no_call")
        failDial(ref, "no_call")
    }

    private fun failDial(ref: String, reason: String) {
        if (pendingDial == ref) clearDial()
        dialResult(ref, false, reason)
    }

    private fun clearDial() {
        pendingDial = null
        dialTimeout?.cancel(false)
        dialTimeout = null
    }

    private fun dialResult(ref: String, ok: Boolean, reason: String?, via: String? = null) {
        send(CallDialResult(ref, ok, reason, via))
    }

    // ---------------- history & contacts ----------------

    private fun onLogReq(r: CallLogReq) {
        if (!settings().shareCallLog) {
            send(CallLog(emptyList(), needs = "disabled", before = r.before))
            return
        }
        io.execute {
            val started = System.currentTimeMillis()
            val msg = when {
                !store.canRead() -> CallLog(emptyList(), needs = "calllog", before = r.before)
                else -> try {
                    val (calls, more) = store.recent(r.limit, r.before)
                    fitLog(CallLog(calls, more, before = r.before))
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    Log.w("calllog.failed", "err" to e.javaClass.simpleName)
                    CallLog(emptyList(), needs = "error:${e.javaClass.simpleName}", before = r.before)
                }
            }
            Log.i("calllog.read", "ms" to System.currentTimeMillis() - started, "count" to msg.calls.size, "needs" to msg.needs)
            engine.execute { send(msg) }
        }
    }

    private fun fitLog(log: CallLog): CallLog {
        var l = log
        while (l.calls.size > 1 && MessageCodec.encode(l).size > MAX_RECORD) {
            l = l.copy(calls = l.calls.take(l.calls.size * SHRINK_NUM / SHRINK_DEN), more = true)
        }
        return l
    }

    private fun onContactSearch(q: ContactSearch) {
        if (!settings().allowCalling) {
            send(Contacts(q.query, emptyList(), needs = "disabled"))
            return
        }
        io.execute {
            val msg = if (!store.canSearchContacts()) {
                Contacts(q.query, emptyList(), needs = "contacts")
            } else {
                try {
                    Contacts(q.query, store.searchContacts(q.query, q.limit))
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    Log.w("contacts.search_failed", "err" to e.javaClass.simpleName)
                    Contacts(q.query, emptyList(), needs = "error:${e.javaClass.simpleName}")
                }
            }
            engine.execute { send(msg) }
        }
    }

    companion object {
        private const val DIAL_TIMEOUT_S = 12L
        private const val MAX_RECORD = 48 * 1024
        private const val SHRINK_NUM = 3
        private const val SHRINK_DEN = 4
    }
}
