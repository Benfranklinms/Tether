package dev.benfranklin.tether

import android.Manifest
import android.content.Context
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Real calls on the CI emulator, end to end through the phone's call bridge (everything but
 * the radio): the emulator's modem rings the phone (`adb emu gsm call`, driven by
 * scripts/emulator-tests.sh when the test logs "TetherEmu: CALL <n>"), the bridge produces the
 * events a Mac would get, and the "Mac" answers, declines or ends through CALL_ACTION; dialing
 * goes through TelecomManager.placeCall from a process with no visible activity, which is how
 * the phone runs it from the background.
 */
@RunWith(AndroidJUnit4::class)
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
        bridge = CallBridge(ctx, { AppSettings() }, engine, { m -> sent += m; true }, { c -> c in Caps.CURRENT })
        engine.submit { bridge.apply(true, AppSettings()) }.get()
        waitFor("monitor running", 5_000) { bridge.monitor.isRunning }
        idle()
    }

    @After
    fun tearDown() {
        driver("CANCEL $NUMBER")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            bridge.monitor.current()?.let { bridge.monitor.act(CallAction(it.id, CallActions4.END)) }
            bridge.monitor.stop()
        }
        waitFor("idle", 15_000, failIfNot = false) { callStateOrNull() == TelephonyManager.CALL_STATE_IDLE }
        engine.shutdownNow()
    }

    /** Asks the host-side driver to run `adb emu gsm <cmd>`. */
    private fun driver(cmd: String) = Log.i("TetherEmu", cmd)

    @Suppress("DEPRECATION")
    private fun callState(): Int = ctx.getSystemService(TelephonyManager::class.java).callState

    private fun callStateOrNull(): Int? = try {
        callState()
    } catch (_: SecurityException) {
        null
    }

    private fun idle() {
        waitFor("phone idle", 20_000) { callState() == TelephonyManager.CALL_STATE_IDLE }
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

    private inline fun <reified T : Message> waitMsg(what: String, ms: Long = 20_000, crossinline pred: (T) -> Boolean): T {
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
    private fun ring(): CallLive {
        driver("CALL $NUMBER")
        val first = waitMsg<CallLive>("CALL_LIVE ringing") { it.state == CallLiveStates.RINGING }
        val live = waitMsg<CallLive>("CALL_LIVE ringing with the number (the number reaches the Mac)") {
            it.state == CallLiveStates.RINGING && hasNumber(it.number)
        }
        val legacy = waitMsg<CallState>("CALL_STATE ringing with the number") {
            it.state == CallStates.RINGING && hasNumber(it.number)
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

    private fun hasNumber(n: String?) = n.orEmpty().filter(Char::isDigit).endsWith(NUMBER)

    @Test
    fun incomingCallAnsweredFromMacThenEnded() {
        val live = ring()
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
    fun incomingCallDeclinedFromMacIsNotMissed() {
        val live = ring()
        mac(CallAction(live.id, CallActions.DECLINE))
        val end = waitMsg<CallState>("declined") { it.state == CallStates.ENDED || it.state == CallStates.MISSED }
        assertEquals(CallStates.ENDED, end.state)
    }

    @Test
    fun unansweredCallIsMissedAndLandsInTheCallLog() {
        ring()
        driver("CANCEL $NUMBER")
        waitMsg<CallState>("missed") { it.state == CallStates.MISSED }
        val store = CallLogStore(ctx, NameLookup { null })
        var found = false
        waitFor("missed call in the log", 15_000) {
            found = store.recent(20, null).first.any { it.type == CallLogTypes.MISSED && it.number.filter(Char::isDigit).endsWith(NUMBER) }
            found
        }
        assertTrue(found)
    }

    @Test
    fun dialFromMacPlacesTheCallFromTheBackground() {
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

    private companion object {
        const val NUMBER = "5551234"
        const val RING_SETTLE_MS = 2_500L
    }
}
