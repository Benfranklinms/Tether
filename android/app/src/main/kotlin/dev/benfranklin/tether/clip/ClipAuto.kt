package dev.benfranklin.tether.clip

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import dev.benfranklin.tether.core.LinkState
import dev.benfranklin.tether.core.features.ClipAutoGate
import dev.benfranklin.tether.core.features.ClipAutoSetup
import dev.benfranklin.tether.core.features.ClipLogMatcher
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime
import dev.benfranklin.tether.service.TetherService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Auto mode (0.4.3): sends what's copied on the phone without opening Tether. Needs READ_LOGS
 * (adb `pm grant`) and, for the background activity start, "Display over other apps".
 *
 * - A primary-clip listener stays registered from the app context. In the background Android
 *   never calls it but logs "Denying clipboard access to <pkg>" each time the clip changes.
 * - A reader thread tails `logcat` for that line ([ClipLogMatcher]); [ClipAutoGate] decides if
 *   it launches [ClipAutoActivity], which takes focus for a moment, reads the clip and finishes.
 * - While Tether itself has focus the listener is called normally and reads directly.
 * - Android 13+ shows "Allow Tether to access all device logs?" when a logcat session starts
 *   with Tether on screen and silently declines one started in the background, so on 13+ the
 *   reader starts only while Tether is in the foreground. A background self-test read
 *   (onBackground) proves the log is readable: it causes our own denial line.
 *
 * [send] gets the text to send (already filtered); [lastHash] is the last clip hash that
 * crossed the link either way. Main-thread confined except the reader thread.
 */
class ClipWatcher(
    private val context: Context,
    private val lastHash: () -> String?,
    private val connected: () -> Boolean,
    private val send: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val gate = ClipAutoGate()
    private val pkg = app.packageName

    @Volatile private var enabled = false
    @Volatile private var allowSensitive = false
    private var foreground = false
    private var listening = false
    private var reader: Thread? = null
    @Volatile private var process: Process? = null
    @Volatile private var readerStartedAt = 0L
    private var restarts = 0
    private var verified = false
    private var declined = false
    private var blocked = false
    private var awaitingProbe = false
    private var launchedAt = 0L
    private var launchCheck: Runnable? = null

    private val _status = MutableStateFlow(ClipAutoSetup.Status.OFF)
    val status: StateFlow<ClipAutoSetup.Status> = _status.asStateFlow()

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        // Only called while Tether has focus (Android 10+), so the read works right here.
        if (enabled) deliver(readClip(app), "listener")
    }

    /** Settings changed; [enabled] = auto mode on and a Mac paired. */
    fun configure(enabled: Boolean, allowSensitive: Boolean) = main.post {
        this.allowSensitive = allowSensitive
        if (this.enabled == enabled) return@post refresh()
        this.enabled = enabled
        Log.i("clip.auto_config", "on" to enabled, "sensitive" to allowSensitive)
        if (enabled) {
            current = this
            listen(true)
            maybeStartReader()
        } else {
            stopReader()
            listen(false)
            if (current === this) current = null
        }
        refresh()
    }

    /** MainActivity resumed / paused (also after returning from a permission screen). */
    fun onForeground(fg: Boolean) = main.post {
        foreground = fg
        if (!enabled) return@post refresh()
        if (fg) {
            restarts = 0
            maybeStartReader()
        }
        refresh()
    }

    /**
     * Tether's screen stopped (not just paused: the Android 13+ log-access dialog pauses it while
     * the session is still pending). Self-test: a background read causes our own denial line,
     * which proves the log is readable.
     */
    fun onBackground() = main.post {
        foreground = false
        if (enabled) main.postDelayed({ probe() }, PROBE_DELAY_MS)
    }

    /** Tether is writing [text] (from the Mac) to the phone clipboard: don't send it back. */
    fun onLocalWrite(text: String) = synchronized(gate) { gate.onLocalWrite(text, SystemClock.elapsedRealtime()) }

    /** The "Allow log access" button: start a fresh logcat session with Tether on screen. */
    fun retryLogAccess() = main.post {
        declined = false
        restarts = 0
        stopReader()
        maybeStartReader()
        refresh()
    }

    fun refresh() {
        val s = ClipAutoSetup.status(
            ClipAutoSetup.Inputs(
                enabled = enabled, logsGranted = logsGranted(app), logGroup = hasLogGroup(), overlay = canOverlay(app),
                readerRunning = reader?.isAlive == true, verified = verified, declined = declined,
            ),
        )
        val shown = if (blocked && s == ClipAutoSetup.Status.WATCHING) ClipAutoSetup.Status.NEEDS_OVERLAY else s
        if (_status.value != shown) Log.i("clip.auto_status", "status" to shown.name)
        _status.value = shown
    }

    // ---------------- reader ----------------

    private fun maybeStartReader() {
        if (!enabled || reader?.isAlive == true) return
        if (!logsGranted(app) || !hasLogGroup()) return
        // 13+: a session started without Tether on screen is declined without asking.
        if (Build.VERSION.SDK_INT >= ClipAutoSetup.ANDROID_13 && !foreground) return
        if (declined || restarts > MAX_RESTARTS) return
        verified = false
        val t = Thread({ readLoop() }, "tether-cliplog")
        t.isDaemon = true
        reader = t
        t.start()
        Log.i("clip.auto_reader_start", "sdk" to Build.VERSION.SDK_INT, "fg" to foreground)
    }

    private fun stopReader() {
        reader?.interrupt()
        process?.destroy()
        process = null
        reader = null
    }

    private fun readLoop() {
        val p = try {
            ProcessBuilder(ClipLogMatcher.logcatCommand()).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.e("clip.auto_reader_failed", e)
            main.post { readerEnded() }
            return
        }
        process = p
        readerStartedAt = System.currentTimeMillis()
        var lines = 0
        try {
            p.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (Thread.currentThread().isInterrupted) break
                    lines++
                    // Never log the raw line: our own log must not look like a denial.
                    if (!ClipLogMatcher.isStale(line, readerStartedAt) && ClipLogMatcher.isDenial(line, pkg)) {
                        main.post { onDenialLine(trailing = false) }
                    }
                }
            }
        } catch (e: IOException) {
            Log.i("clip.auto_reader_io", "err" to e.message)
        } finally {
            p.destroy()
            Log.i("clip.auto_reader_end", "lines" to lines)
            main.post { readerEnded() }
        }
    }

    private fun readerEnded() {
        if (reader?.isAlive == true) return
        reader = null
        if (!enabled) return refresh()
        restarts++
        // Below 13 it restarts in the background too; 13+ only while Tether is on screen
        // (maybeStartReader checks), otherwise on the next resume.
        if (restarts <= MAX_RESTARTS) main.postDelayed({ maybeStartReader(); refresh() }, RESTART_DELAY_MS * restarts)
        else Log.w("clip.auto_reader_gave_up", "restarts" to restarts)
        refresh()
    }

    private fun probe() {
        if (!enabled || foreground || verified || reader?.isAlive != true) return
        synchronized(gate) { gate.onProbe(SystemClock.elapsedRealtime()) }
        awaitingProbe = true
        try {
            app.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip()
        } catch (_: SecurityException) {
            // Denied, which is the point.
        }
        main.postDelayed({
            if (!awaitingProbe) return@postDelayed
            awaitingProbe = false
            Log.w("clip.auto_probe_unseen", "sdk" to Build.VERSION.SDK_INT)
            // 13+: the session was declined (or never approved): only our own app's lines are visible.
            if (Build.VERSION.SDK_INT >= ClipAutoSetup.ANDROID_13) declined = true
            refresh()
        }, PROBE_WAIT_MS)
    }

    private fun onDenialLine(trailing: Boolean) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (!verified) {
            verified = true
            declined = false
            restarts = 0
            Log.i("clip.auto_verified")
        }
        if (awaitingProbe && synchronized(gate) { gate.inProbeWindow(now) }) {
            awaitingProbe = false
            refresh()
            return
        }
        if (!connected()) return Log.i("clip.auto_skip", "why" to "offline")
        val d = synchronized(gate) { gate.onDenial(now, trailing) }
        when (d.action) {
            ClipAutoGate.Action.LAUNCH -> launch()
            ClipAutoGate.Action.LATER -> main.postDelayed({ onDenialLine(trailing = true) }, d.delayMs)
            ClipAutoGate.Action.IGNORE -> if (d.reason != "coalesced") Log.i("clip.auto_skip", "why" to d.reason)
        }
        refresh()
    }

    private fun launch() {
        val km = app.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            synchronized(gate) { gate.onFinished() }
            return Log.i("clip.auto_skip", "why" to "locked")
        }
        launchedAt = SystemClock.elapsedRealtime()
        try {
            app.startActivity(ClipAutoActivity.intent(app))
        } catch (e: ActivityNotFoundException) {
            Log.e("clip.auto_launch_failed", e)
            synchronized(gate) { gate.onFinished() }
            return
        } catch (e: SecurityException) {
            Log.e("clip.auto_launch_failed", e)
            synchronized(gate) { gate.onFinished() }
            return
        }
        // A blocked background start fails silently; notice it by the activity never showing up.
        launchCheck?.let(main::removeCallbacks)
        val check = Runnable {
            if (launchedAt != 0L) {
                blocked = true
                launchedAt = 0L
                Log.w("clip.auto_launch_blocked", "overlay" to canOverlay(app))
                synchronized(gate) { gate.onFinished() }
                refresh()
            }
        }
        launchCheck = check
        main.postDelayed(check, LAUNCH_CHECK_MS)
    }

    /** ClipAutoActivity was created (the background start went through). */
    internal fun onReaderShown() {
        launchedAt = 0L
        if (blocked) {
            blocked = false
            refresh()
        }
    }

    /** ClipAutoActivity read the clip (or found nothing). */
    internal fun onReaderRead(read: ClipRead?, via: String) {
        deliver(read, via)
    }

    internal fun onReaderDone() {
        synchronized(gate) { gate.onFinished() }
    }

    private fun deliver(read: ClipRead?, via: String) {
        val r = synchronized(gate) { gate.onRead(read?.text, read?.sensitive == true, allowSensitive, lastHash()) }
        when (r) {
            is ClipAutoGate.Read.Send -> {
                Log.i("clip.auto_read", "via" to via, "bytes" to r.text.length)
                send(r.text)
            }
            is ClipAutoGate.Read.Skip -> Log.i("clip.auto_skip", "why" to r.reason, "via" to via)
        }
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        if (on) cm.addPrimaryClipChangedListener(listener) else cm.removePrimaryClipChangedListener(listener)
        listening = on
    }

    private fun hasLogGroup(): Boolean = try {
        ClipAutoSetup.hasLogGroup(File("/proc/self/status").readText())
    } catch (_: IOException) {
        false
    }

    companion object {
        private const val PROBE_DELAY_MS = 1_500L
        private const val PROBE_WAIT_MS = 2_000L
        private const val RESTART_DELAY_MS = 2_000L
        private const val MAX_RESTARTS = 10
        private const val LAUNCH_CHECK_MS = 2_500L

        /** The watcher the reader activity reports to (the runtime's, or a test's). */
        @Volatile var current: ClipWatcher? = null

        fun logsGranted(c: Context) = c.checkSelfPermission(ClipAutoSetup.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        fun canOverlay(c: Context) = Settings.canDrawOverlays(c)
    }
}

data class ClipRead(val text: String?, val sensitive: Boolean)

/** Reads the primary clip with its "sensitive" flag (password managers, OTP autofill). */
fun readClip(c: Context): ClipRead? {
    val cm = c.getSystemService(ClipboardManager::class.java) ?: return null
    val clip: ClipData = try {
        cm.primaryClip
    } catch (_: SecurityException) {
        null
    } ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).coerceToText(c)?.toString()
    return ClipRead(text, isSensitive(clip.description))
}

/** ClipDescription.EXTRA_IS_SENSITIVE (Android 13 API; many apps set the same key earlier). */
fun isSensitive(d: ClipDescription?): Boolean = d?.extras?.getBoolean(SENSITIVE_KEY, false) == true

private const val SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

/**
 * Transparent, focusable, no UI: takes focus for a moment so Android lets Tether read the
 * clipboard, hands the clip to the watcher and finishes. A listener registered here catches a
 * change that lands just after the first read.
 */
class ClipAutoActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var read = false
    private val watcher get() = ClipWatcher.current
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { watcher?.onReaderRead(readClip(this), "activity_listener") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        watcher?.onReaderShown()
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(listener)
        // Never linger if focus doesn't come (another window on top).
        main.postDelayed({ finish() }, MAX_LIFETIME_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || read) return
        read = true
        watcher?.onReaderRead(readClip(this), "activity")
        main.postDelayed({ finish() }, LINGER_MS)
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(listener)
        watcher?.onReaderDone()
        super.onDestroy()
    }

    companion object {
        private const val LINGER_MS = 250L
        private const val MAX_LIFETIME_MS = 2_000L

        fun intent(c: Context) = Intent(c, ClipAutoActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
    }
}

/** "Send to Mac" in the text-selection toolbar (ACTION_PROCESS_TEXT). No setup needed. */
class ProcessTextActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        when {
            text.isNullOrEmpty() -> done(text, "Nothing selected")
            runtime.ui.value.peer == null -> done(text, "Pair Tether with your Mac first")
            else -> {
                // A cold start (Tether wasn't running) needs a few seconds to reconnect.
                if (!TetherService.running) TetherService.start(this)
                trySend(text, SystemClock.elapsedRealtime() + CONNECT_WAIT_MS)
            }
        }
    }

    private fun trySend(text: String, deadline: Long) {
        if (runtime.ui.value.link is LinkState.Connected) {
            runtime.sendClip(text, fromUser = false)
            return done(text, "Sent to your Mac")
        }
        if (SystemClock.elapsedRealtime() >= deadline) return done(text, "Not connected to your Mac")
        main.postDelayed({ trySend(text, deadline) }, POLL_MS)
    }

    private fun done(text: String?, msg: String) {
        Log.i("clip.process_text", "bytes" to (text?.length ?: 0), "result" to msg)
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val CONNECT_WAIT_MS = 5_000L
        const val POLL_MS = 200L
    }
}
