package dev.benfranklin.tether

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.benfranklin.tether.log.CrashLog
import dev.benfranklin.tether.log.guarded
import dev.benfranklin.tether.ui.clamp01
import dev.benfranklin.tether.ui.screens.HomeData
import dev.benfranklin.tether.ui.screens.HomeModel
import dev.benfranklin.tether.ui.screens.nowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** 0.5.3 crash-safety net: overshoot clamp, the last-crash file, guarded callbacks, compact panels. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CrashSafetyTest {
    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun springOvershootIsClamped() {
        assertEquals(0f, (-0.0109f).clamp01())
        assertEquals(1f, 1.011f.clamp01())
        assertEquals(0.5f, 0.5f.clamp01())
    }

    @Test fun lastCrashIsWrittenReadAndCleared() {
        CrashLog.clear(ctx)
        assertNull(CrashLog.read(ctx))
        assertNull(CrashLog.stamp(ctx))
        guarded(ctx, "test.drag") { listOf(1)[3] }
        val text = CrashLog.read(ctx)
        assertNotNull(text)
        assertTrue(text!!.contains("Caught in test.drag"))
        assertTrue(text.contains("IndexOutOfBoundsException"))
        assertTrue(text.contains("CrashSafetyTest"))
        assertEquals(16, CrashLog.stamp(ctx)!!.length)
        CrashLog.clear(ctx)
        assertNull(CrashLog.read(ctx))
    }

    @Test fun formatCarriesVersionDeviceAndStack() {
        val s = CrashLog.format("Crash", "main", IllegalArgumentException("Corner size in Px can't be negative"))
        assertTrue(s.startsWith("Tether "))
        assertTrue(s.contains("Crash on thread main"))
        assertTrue(s.contains("Android "))
        assertTrue(s.contains("Corner size in Px can't be negative"))
    }

    @Test fun guardedLetsCancellationThrough() {
        val result = runCatching {
            guarded(ctx, "test.cancel") { throw kotlinx.coroutines.CancellationException("gesture restarted") }
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    private val model = HomeModel("Mac", "", true, false, true, null, null, emptyList(), false, now = 1_760_000_000_000L)

    /** 0.5.4: Now playing is idle (calm state, no cut-off title) unless the linked Mac plays something. */
    @Test fun nowPlayingIdleUnlessLinkedAndActive() {
        assertNull(nowPlaying(model, HomeData()))
        val playing = HomeData(
            mac = dev.benfranklin.tether.v7.MacStatusUi(
                50, false, dev.benfranklin.tether.core.proto.MacMedia(true, "Nights", "Frank Ocean", "Music", true, 300_000, 1_000), 1L,
            ),
        )
        assertNotNull(nowPlaying(model, playing))
        // Playing on the Mac but not linked: idle.
        assertNull(nowPlaying(model.copy(connected = false), playing))
        assertFalse(nowPlaying(model, playing) == null)
        assertEquals("Nights", nowPlaying(model, playing)?.title)
        assertTrue(clampSanity())
    }

    private fun clampSanity() = (-0.2f).clamp01() == 0f && 1.3f.clamp01() == 1f
}
