package dev.benfranklin.tether

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.benfranklin.tether.clip.ClipWatcher
import dev.benfranklin.tether.core.features.ClipAutoSetup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Auto mode end to end on the CI emulator, with READ_LOGS and the overlay app op granted by
 * scripts/emulator-tests.sh before the process starts (`-e clipAuto 1`). Text is put on the
 * clipboard while no Tether window has focus; Android denies the background listener and logs
 * it; the watcher's logcat reader sees the line, opens the invisible reader activity, reads the
 * clip and hands it to the "Mac" (a queue standing in for the link). Instrumented runs get log
 * access without the Android 13+ dialog, so the dialog itself is not covered here.
 */
@RunWith(AndroidJUnit4::class)
class DeviceClipTests {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val main = Handler(Looper.getMainLooper())
    private val mac = LinkedBlockingQueue<String>()
    private var lastHash: String? = null
    private lateinit var watcher: ClipWatcher

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run by emulator-tests.sh with the grants (-e clipAuto 1)", args.getString("clipAuto") == "1")
        assertTrue("READ_LOGS granted", ClipWatcher.logsGranted(ctx))
        assertTrue("overlay app op granted", ClipWatcher.canOverlay(ctx))
        // The runtime's own watcher stays off (nothing paired on the emulator).
        watcher = ClipWatcher(
            ctx, lastHash = { lastHash }, connected = { true },
            send = { text ->
                lastHash = dev.benfranklin.tether.core.features.ClipAutoGate.hash(text)
                mac.offer(text)
            },
        )
        watcher.configure(enabled = true, allowSensitive = false)
        watcher.onForeground(true)
        watcher.onForeground(false)
        watcher.onBackground()
        // Status settles to WATCHING (reader running) then WORKING once the self-test line is seen.
        val deadline = System.currentTimeMillis() + STATUS_WAIT_MS
        while (System.currentTimeMillis() < deadline && watcher.status.value != ClipAutoSetup.Status.WORKING) Thread.sleep(POLL_MS)
        Log.i("TetherTest", "clip auto status ${watcher.status.value}")
        assertEquals("self-test (background probe line seen)", ClipAutoSetup.Status.WORKING, watcher.status.value)
        Thread.sleep(SETTLE_MS) // out of the probe window
    }

    @After
    fun tearDown() {
        if (::watcher.isInitialized) watcher.configure(enabled = false, allowSensitive = false)
        Thread.sleep(POLL_MS)
    }

    private fun copy(text: String, sensitive: Boolean = false) {
        val done = CountDownLatch(1)
        main.post {
            val clip = ClipData.newPlainText("test", text)
            if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            ctx.getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(clip)
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun copiedTextReachesTheMacWithoutOpeningTether() {
        val text = "tether auto ${UUID.randomUUID()}"
        copy(text)
        assertEquals(text, mac.poll(DELIVERY_S, TimeUnit.SECONDS))
        // A second, different copy arrives too (throttle lets it through after the gap).
        Thread.sleep(GAP_MS)
        val second = "second ${UUID.randomUUID()}"
        copy(second)
        assertEquals(second, mac.poll(DELIVERY_S, TimeUnit.SECONDS))
        // The same text again is not re-sent.
        Thread.sleep(GAP_MS)
        copy(second)
        assertNull(mac.poll(QUIET_S, TimeUnit.SECONDS))
    }

    @Test
    fun textFromTheMacIsNotEchoedBack() {
        val text = "from mac ${UUID.randomUUID()}"
        watcher.onLocalWrite(text)
        copy(text)
        assertNull(mac.poll(QUIET_S, TimeUnit.SECONDS))
    }

    @Test
    fun sensitiveClipsStayOnThePhone() {
        copy("hunter2-${UUID.randomUUID()}", sensitive = true)
        assertNull(mac.poll(QUIET_S, TimeUnit.SECONDS))
        // A normal copy right after still goes through.
        Thread.sleep(GAP_MS)
        val ok = "plain ${UUID.randomUUID()}"
        copy(ok)
        assertEquals(ok, mac.poll(DELIVERY_S, TimeUnit.SECONDS))
    }

    private companion object {
        const val STATUS_WAIT_MS = 20_000L
        const val POLL_MS = 200L
        const val SETTLE_MS = 3_000L
        const val GAP_MS = 1_600L
        const val DELIVERY_S = 20L
        const val QUIET_S = 6L
    }
}
