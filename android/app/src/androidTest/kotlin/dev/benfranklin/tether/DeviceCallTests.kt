package dev.benfranklin.tether

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.benfranklin.tether.call.CallBridge
import dev.benfranklin.tether.call.CallLogStore
import dev.benfranklin.tether.core.proto.CallAction
import dev.benfranklin.tether.core.proto.CallActions
import dev.benfranklin.tether.core.proto.CallActions4
import dev.benfranklin.tether.core.proto.CallDial
import dev.benfranklin.tether.core.proto.CallDialResult
import dev.benfranklin.tether.core.proto.CallDirs
import dev.benfranklin.tether.core.proto.CallLive
import dev.benfranklin.tether.core.proto.CallLiveStates
import dev.benfranklin.tether.core.proto.CallLogTypes
import dev.benfranklin.tether.core.proto.CallState
import dev.benfranklin.tether.core.proto.CallStates
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.sms.NameLookup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Real calls on the CI emulator, end to end through the phone's call bridge (everything but
 * the radio): the emulator's modem rings the phone (`adb emu gsm call`, driven by
 * scripts/emulator-tests.sh when the test logs "TetherEmu: CALL <n>"), the bridge produces the
 * events a Mac would get, and the "Mac" answers, declines or ends through CALL_ACTION; dialing
 * goes through TelecomManager.placeCall from a process with no visible activity, which is how
 * the phone runs it from the background.
 *
 * Isolation: every test uses its own caller number; before and after each test the driver
 * hangs up everything the modem still lists (`gsm cancel` until `gsm list` is empty) and waits
 * for the system's broadcast queue to drain, and the test waits until telephony says IDLE.
 * The tests run in name order so the decline (the one that has wedged the emulator's
 * telephony) is last.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceCallTests {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sent = CopyOnWriteArrayList<Message>()
    private val engine = Executors.newSingleThreadScheduledExecutor()
    private lateinit var bridge: CallBridge

    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE,
    )

    @Before
    fun setUp() {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        assumeTrue("no telephony on this device", tm != null && tm.phoneType != TelephonyManager.PHONE_TYPE_NONE)
        // The rule's grant can take a moment to reach telephony's permission check right
        // after install (getCallState threw "requires READ_PHONE_STATE" once on CI).
        waitFor("READ_PHONE_STATE in effect", 10_000) { callStateOrNull() != null }
        resetModem()
        bridge = CallBridge(ctx, { AppSettings() }, engine, { m -> sent += m; true }, { c -> c in Caps.CURRENT })
        engine.submit { bridge.apply(true, AppSettings()) }.get()
        waitFor("monitor running", 5_000) { bridge.monitor.isRunning }
        idle()
    }

    @After
    fun tearDown() {
        if (!::bridge.isInitialized) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bridge.monitor.current()?.let { bridge.monitor.act(CallAction(it.id, CallActions4.END)) }
        }
        try {
            resetModem()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { bridge.monitor.stop() }
            engine.shutdownNow()
        }
    }

    /**
     * Asks the host-side driver (scripts/emulator-tests.sh) to run [cmd] and waits until it says
     * it's done: it sets the debug.tether.ack property to the request's id afterwards.
     *  - CALL <n> / CANCEL <n>: `adb emu gsm call|cancel <n>`
     *  - RESET: `gsm cancel` every call `gsm list` shows until it's empty, then wait for the
     *    broadcast queue to drain (so this test's phone-state broadcasts aren't stuck behind
     *    the last test's).
     */
    private fun driver(cmd: String, ms: Long = DRIVER_MS) {
        val id = "r${++requests}x${SystemClock.uptimeMillis()}"
        Log.i("TetherEmu", "$id $cmd")
        waitFor("driver: $cmd", ms) {
            val done = shell("getprop $ACK_PROP").trim() == id
            if (!done) Thread.sleep(ACK_POLL_MS)
            done
        }
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** No call left on the modem or in telephony, and the broadcast queue drained. */
    private fun resetModem() {
        driver("RESET")
        waitFor("telephony IDLE after the modem was cleared", 30_000) { callStateOrNull() == TelephonyManager.CALL_STATE_IDLE }
    }

    @Suppress("DEPRECATION")
    private fun callState(): Int = ctx.getSystemService(TelephonyManager::class.java).callState

    private fun callStateOrNull(): Int? = try {
        callState()
    } catch (_: SecurityException) {
        null
    }

    private fun idle() {
        waitFor("phone idle", 30_000) { callState() == TelephonyManager.CALL_STATE_IDLE }
        Thread.sleep(1_000)
        sent.clear()
    }

    private fun waitFor(what: String, ms: Long, failIfNot: Boolean = true, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (cond()) return
            Thread.sleep(100)
        }
        if (failIfNot) throw AssertionError("timed out waiting for $what; sent=$sent")
    }

    private inline fun <reified T : Message> waitMsg(what: String, ms: Long = MSG_MS, crossinline pred: (T) -> Boolean): T {
        waitFor(what, ms) { sent.any { it is T && pred(it) } }
        return sent.filterIsInstance<T>().last { pred(it) }
    }

    private fun mac(m: Message) = engine.submit { assertTrue(bridge.handle(m)) }.get()

    /**
     * Rings the phone and waits until the Mac has the caller's number. Android sends the
     * RINGING broadcast twice when the app also holds READ_CALL_LOG, one copy without the
     * number and one with it, in no fixed order; the bridge sends a first card at once and
     * an updated one (same call id) when the number arrives, so the test waits for that.
     */
    private fun ring(number: String): CallLive {
        driver("CALL $number")
        val first = waitMsg<CallLive>("CALL_LIVE ringing") { it.state == CallLiveStates.RINGING }
        val live = waitMsg<CallLive>("CALL_LIVE ringing with the number (the number reaches the Mac)") {
            it.state == CallLiveStates.RINGING && hasNumber(it.number, number)
        }
        val legacy = waitMsg<CallState>("CALL_STATE ringing with the number") {
            it.state == CallStates.RINGING && hasNumber(it.number, number)
        }
        assertEquals("one call, one id", first.id, live.id)
        assertEquals(legacy.id, live.id)
        assertEquals(CallDirs.IN, live.dir)
        assertTrue("Answer offered", live.canAnswer)
        // Act like a person would, not within milliseconds of the first ring: answering while
        // Telecom is still starting the ringtone crashed the emulator's system_server
        // (AsyncRingtonePlayer.handlePlay NPE on API 34 with -noaudio), which no app can cause
        // on a real phone at human speed.
        Thread.sleep(RING_SETTLE_MS)
        return live
    }

    private fun hasNumber(n: String?, number: String) = n.orEmpty().filter(Char::isDigit).endsWith(number)

    @Test
    fun call1IncomingAnsweredFromMacThenEnded() {
        val live = ring(N_ANSWER)
        mac(CallAction(live.id, CallActions4.ANSWER, speaker = true))
        val up = waitMsg<CallLive>("answered") { it.id == live.id && it.state == CallLiveStates.ACTIVE }
        assertTrue(up.since > 0)
        assertTrue(up.canEnd)
        waitMsg<CallState>("legacy offhook") { it.state == CallStates.OFFHOOK }
        assertEquals(TelephonyManager.CALL_STATE_OFFHOOK, callState())
        Thread.sleep(2_000) // speaker is applied ~0.7 s after off-hook and verified
        mac(CallAction(live.id, CallActions4.END))
        waitMsg<CallLive>("ended") { it.id == live.id && it.state == CallLiveStates.ENDED }
        val end = waitMsg<CallState>("legacy end") { it.state == CallStates.ENDED || it.state == CallStates.MISSED }
        assertEquals(CallStates.ENDED, end.state)
    }

    @Test
    fun call2UnansweredIsMissedAndLandsInTheCallLog() {
        ring(N_MISSED)
        driver("CANCEL $N_MISSED")
        waitMsg<CallState>("missed") { it.state == CallStates.MISSED }
        val store = CallLogStore(ctx, NameLookup { null })
        var found = false
        waitFor("missed call in the log", 15_000) {
            found = store.recent(20, null).first.any { it.type == CallLogTypes.MISSED && it.number.filter(Char::isDigit).endsWith(N_MISSED) }
            found
        }
        assertTrue(found)
    }

    @Test
    fun call3DialFromMacPlacesTheCallFromTheBackground() {
        // No activity of this app is in the foreground: this is the background-start case.
        mac(CallDial("r1", "+1 (555) 123-4567"))
        val r = waitMsg<CallDialResult>("dial result", 25_000) { it.ref == "r1" }
        assertTrue("dial failed: ${r.reason}", r.ok)
        assertEquals("telecom", r.via)
        val live = waitMsg<CallLive>("outgoing card") { it.dir == CallDirs.OUT }
        assertEquals("+15551234567", live.number)
        assertEquals(TelephonyManager.CALL_STATE_OFFHOOK, callState())
        mac(CallAction(live.id, CallActions4.END))
        waitMsg<CallLive>("outgoing ended") { it.id == live.id && it.state == CallLiveStates.ENDED }
        assertTrue("0.3 Macs never saw outgoing calls", sent.filterIsInstance<CallState>().none { it.id == live.id })
    }

    /**
     * Last on purpose. Tether's Decline is one TelecomManager.endCall(): Telecom rejects the
     * call within ~0.1 s (logcat: performReject, onDisconnect INCOMING_REJECTED); the IDLE
     * phone-state broadcast can still reach apps many seconds later on a busy emulator.
     */
    @Test
    fun call4IncomingDeclinedFromMacIsNotMissed() {
        val live = ring(N_DECLINE)
        val t0 = SystemClock.elapsedRealtime()
        mac(CallAction(live.id, CallActions.DECLINE))
        waitFor("telephony IDLE after Decline", MSG_MS) { callState() == TelephonyManager.CALL_STATE_IDLE }
        val telephonyMs = SystemClock.elapsedRealtime() - t0
        val end = waitMsg<CallState>("declined") { it.state == CallStates.ENDED || it.state == CallStates.MISSED }
        Log.i("TetherTest", "decline: telephony idle after ${telephonyMs} ms, IDLE broadcast after ${SystemClock.elapsedRealtime() - t0} ms")
        assertEquals(CallStates.ENDED, end.state)
        assertEquals(live.id, end.id)
    }

    private companion object {
        // One caller per test, so nothing left over from one test can pass for the next one's call.
        const val N_ANSWER = "5551001"
        const val N_MISSED = "5551002"
        const val N_DECLINE = "5551004"
        const val RING_SETTLE_MS = 2_500L
        /** Phone-state broadcasts can lag on the CI emulator; a real phone takes well under a second. */
        const val MSG_MS = 45_000L
        const val DRIVER_MS = 150_000L
        const val ACK_PROP = "debug.tether.ack"
        const val ACK_POLL_MS = 300L
        var requests = 0
    }
}
