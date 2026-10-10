package dev.benfranklin.tether

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.benfranklin.tether.call.CallBridge
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
import dev.benfranklin.tether.core.proto.CallStates
import dev.benfranklin.tether.core.proto.Caps3
import dev.benfranklin.tether.core.proto.Caps4
import dev.benfranklin.tether.core.proto.ContactSearch
import dev.benfranklin.tether.core.proto.Contacts
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.data.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's call bridge against old and new Macs (caps gating), settings and permissions:
 * a 0.3 Mac only ever gets 0.3 CALL_STATE events; a 0.4 Mac gets the live card, dial results,
 * history and contact answers; every request is answered even when blocked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF) // no conscrypt JNI on every host (e.g. linux-aarch64); not needed here
class CallBridgeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sent = CopyOnWriteArrayList<Message>()
    private var caps = setOf(Caps3.CALL)
    private var settings = AppSettings()
    private val engine = Executors.newSingleThreadScheduledExecutor()
    private lateinit var bridge: CallBridge

    @Before
    fun setUp() {
        bridge = CallBridge(app, { settings }, engine, { m -> sent += m; true }, { c -> c in caps })
    }

    @After
    fun tearDown() {
        bridge.monitor.stop()
        engine.shutdownNow()
    }

    private fun grant(vararg p: String) = shadowOf(app).grantPermissions(*p)
    private fun deny(vararg p: String) = shadowOf(app).denyPermissions(*p)

    /** Runs the engine, main looper and I/O thread until [done] or 5 s. */
    private fun settle(done: () -> Boolean = { false }) {
        val until = System.currentTimeMillis() + if (done === NEVER) 300 else 5_000
        while (System.currentTimeMillis() < until) {
            engine.submit {}.get(1, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private fun start() {
        engine.submit { bridge.apply(true, settings) }.get()
        settle(NEVER)
    }

    private fun phone(state: String, number: String? = null) {
        @Suppress("DEPRECATION")
        app.sendBroadcast(
            Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE, state)
                .putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, number),
        )
        settle(NEVER)
    }

    private fun handle(m: Message) {
        engine.submit { assertTrue(bridge.handle(m)) }.get()
    }

    private inline fun <reified T : Message> last(): T? = sent.filterIsInstance<T>().lastOrNull()

    @Test
    fun v03MacGetsOnlyTheOldCallEvents() {
        grant(Manifest.permission.READ_PHONE_STATE)
        start()
        phone(TelephonyManager.EXTRA_STATE_RINGING, "+919847012345")
        assertEquals(CallStates.RINGING, last<CallState>()?.state)
        phone(TelephonyManager.EXTRA_STATE_IDLE)
        assertEquals(CallStates.MISSED, last<CallState>()?.state)
        assertTrue("no revision-4 message to a 0.3 Mac", sent.none { it is CallLive || it is CallDialResult || it is CallLog })
    }

    @Test
    fun v04MacGetsTheLiveCardAndControlsFollowPermissions() {
        caps = setOf(Caps3.CALL, Caps4.CALL_CONTROL)
        grant(Manifest.permission.READ_PHONE_STATE)
        deny(Manifest.permission.ANSWER_PHONE_CALLS)
        start()
        phone(TelephonyManager.EXTRA_STATE_RINGING, "+919847012345")
        val ringing = last<CallLive>()
        assertNotNull(ringing)
        assertEquals(CallLiveStates.RINGING, ringing!!.state)
        assertFalse("no answer permission yet", ringing.canAnswer)
        // Answer without the permission: explained on the card, not silently dropped.
        handle(CallAction(ringing.id, CallActions4.ANSWER, speaker = true))
        settle { last<CallLive>()?.reason != null }
        assertEquals("not_allowed", last<CallLive>()?.reason)
        // Granting it shows Answer.
        grant(Manifest.permission.ANSWER_PHONE_CALLS)
        bridge.permissionsChanged()
        settle { last<CallLive>()?.canAnswer == true }
        assertTrue(last<CallLive>()!!.canAnswer)
        phone(TelephonyManager.EXTRA_STATE_OFFHOOK)
        val up = last<CallLive>()!!
        assertEquals(CallLiveStates.ACTIVE, up.state)
        assertTrue(up.since > 0)
        assertTrue(up.canEnd && up.canSpeaker && up.canMute)
        assertFalse("hold needs the dialer role", up.canHold)
        assertEquals(CallStates.OFFHOOK, last<CallState>()?.state)
        handle(CallAction(up.id, CallActions4.HOLD, on = true))
        settle { last<CallLive>()?.reason == "unsupported" }
        assertEquals("unsupported", last<CallLive>()?.reason)
        phone(TelephonyManager.EXTRA_STATE_IDLE)
        assertEquals(CallLiveStates.ENDED, last<CallLive>()?.state)
        assertEquals(CallStates.ENDED, last<CallState>()?.state)
    }

    @Test
    fun actionsOnAnUnknownCallSayNoCall() {
        caps = setOf(Caps3.CALL, Caps4.CALL_CONTROL)
        grant(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
        start()
        handle(CallAction("nope", CallActions4.END))
        settle { sent.isNotEmpty() }
        assertEquals("no_call", last<CallLive>()?.reason)
    }

    @Test
    fun oldMacDeclineFailureUsesTheOldShape() {
        grant(Manifest.permission.READ_PHONE_STATE)
        deny(Manifest.permission.ANSWER_PHONE_CALLS)
        start()
        phone(TelephonyManager.EXTRA_STATE_RINGING, "1")
        val id = last<CallState>()!!.id
        handle(CallAction(id, CallActions.DECLINE))
        settle { last<CallState>()?.reason != null }
        assertEquals("not_allowed", last<CallState>()?.reason)
        assertTrue(sent.none { it is CallLive })
    }

    @Test
    fun callAlertsOffHidesIncomingButOutgoingStillShows() {
        caps = setOf(Caps3.CALL, Caps4.CALL_CONTROL, Caps4.DIAL)
        settings = settings.copy(callAlerts = false, allowCalling = true)
        grant(Manifest.permission.READ_PHONE_STATE)
        start()
        phone(TelephonyManager.EXTRA_STATE_RINGING, "1")
        phone(TelephonyManager.EXTRA_STATE_IDLE)
        assertTrue(sent.isEmpty())
        phone(TelephonyManager.EXTRA_STATE_OFFHOOK)
        assertEquals(CallDirs.OUT, last<CallLive>()?.dir)
        assertEquals(CallLiveStates.DIALING, last<CallLive>()?.state)
    }

    @Test
    fun dialRequestsAlwaysGetAnAnswer() {
        caps = setOf(Caps3.CALL, Caps4.CALL_CONTROL, Caps4.DIAL)
        grant(Manifest.permission.READ_PHONE_STATE)
        deny(Manifest.permission.CALL_PHONE)
        start()
        settings = settings.copy(allowCalling = false)
        handle(CallDial("r1", "+919847012345"))
        assertEquals(CallDialResult("r1", false, "disabled"), last<CallDialResult>())
        settings = settings.copy(allowCalling = true)
        handle(CallDial("r2", "call me"))
        assertEquals(CallDialResult("r2", false, "invalid_number"), last<CallDialResult>())
        handle(CallDial("r3", "+91 98470 12345"))
        settle { last<CallDialResult>()?.ref == "r3" }
        assertEquals(CallDialResult("r3", false, "needs_permission"), last<CallDialResult>())
        // With the permission the phone places the call; the answer comes when it goes off-hook.
        grant(Manifest.permission.CALL_PHONE)
        handle(CallDial("r4", "+91 98470 12345"))
        settle(NEVER)
        assertFalse(sent.filterIsInstance<CallDialResult>().any { it.ref == "r4" })
        phone(TelephonyManager.EXTRA_STATE_OFFHOOK)
        settle { last<CallDialResult>()?.ref == "r4" }
        assertEquals(CallDialResult("r4", true, via = "telecom"), last<CallDialResult>())
        val live = last<CallLive>()!!
        assertEquals("+919847012345", live.number)
        assertEquals(CallDirs.OUT, live.dir)
        // A second dial while on a call is refused.
        handle(CallDial("r5", "121"))
        settle { last<CallDialResult>()?.ref == "r5" }
        assertEquals(CallDialResult("r5", false, "busy"), last<CallDialResult>())
    }

    @Test
    fun historyAndContactsAnswerEvenWhenBlocked() {
        caps = setOf(Caps4.CALL_LOG, Caps4.DIAL)
        settings = settings.copy(shareCallLog = false)
        handle(CallLogReq(60))
        assertEquals("disabled", last<CallLog>()?.needs)
        settings = settings.copy(shareCallLog = true)
        deny(Manifest.permission.READ_CALL_LOG)
        handle(CallLogReq(60, before = 5))
        settle { sent.count { it is CallLog } == 2 }
        assertEquals(CallLog(emptyList(), needs = "calllog", before = 5), last<CallLog>())
        grant(Manifest.permission.READ_CALL_LOG)
        handle(CallLogReq(60))
        settle { sent.count { it is CallLog } == 3 }
        assertEquals(null, last<CallLog>()?.needs)
        deny(Manifest.permission.READ_CONTACTS)
        handle(ContactSearch("asha"))
        settle { last<Contacts>() != null }
        assertEquals(Contacts("asha", emptyList(), needs = "contacts"), last<Contacts>())
        settings = settings.copy(allowCalling = false)
        handle(ContactSearch("x"))
        assertEquals("disabled", last<Contacts>()?.needs)
    }

    private companion object {
        val NEVER: () -> Boolean = { false }
    }
}
