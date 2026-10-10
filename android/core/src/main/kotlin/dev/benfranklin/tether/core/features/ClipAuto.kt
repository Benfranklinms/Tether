package dev.benfranklin.tether.core.features

import java.security.MessageDigest
import java.util.Base64

/**
 * Auto mode for phone → Mac clipboard (0.4.3), the platform-free half.
 *
 * Android 10+ lets only the focused app (or the keyboard) read the clipboard. With READ_LOGS
 * granted over adb, Tether keeps a primary-clip listener registered from its background
 * service; every time anything is copied, ClipboardService refuses to notify that listener and
 * logs (AOSP ClipboardService.clipboardAccessAllowed):
 *
 *     E ClipboardService: Denying clipboard access to <package>, application is not in focus
 *     nor is it a system service for user 0
 *
 * Tether tails the log for that line with its own package name, briefly shows a transparent
 * focusable activity, reads the clip and sends it. The same approach KDE Connect, XClipper and
 * ClipCascade use. This file holds what is unit-tested: the line matcher, the gate that decides
 * whether a line launches the reader (echo suppression, probes, throttle, storm guard), the
 * read filter (dedupe, Mac echo, sensitive clips) and the setup commands and status.
 */
object ClipLogMatcher {
    /** Only ClipboardService warnings and errors; everything else silenced (cheap to tail). */
    fun logcatCommand(): List<String> = listOf("logcat", "-v", "epoch", "-T", "1", "ClipboardService:W", "*:S")

    private val denyWords = Regex("""\bden(y|ying|ied)\b""", RegexOption.IGNORE_CASE)

    /**
     * True for the "access denied" line ClipboardService logs for [pkg] when a clip changes and
     * the listener of a background app is skipped. The package must match exactly (so
     * `x.tether` doesn't match `x.tether.debug` and the other way round). OEM rewordings are
     * accepted when the line still names ClipboardService, the package and a deny word. The
     * "invalid device id" variant is not a clip change and is ignored.
     */
    fun isDenial(line: String, pkg: String): Boolean {
        if (pkg.isEmpty() || !line.contains(pkg)) return false
        if (!mentionsExactly(line, pkg)) return false
        if (line.contains("invalid device id", ignoreCase = true)) return false
        if (line.contains("Denying clipboard access to $pkg")) return true
        return line.contains("Clipboard", ignoreCase = true) && denyWords.containsMatchIn(line)
    }

    private fun mentionsExactly(line: String, pkg: String): Boolean {
        var i = line.indexOf(pkg)
        while (i >= 0) {
            val end = i + pkg.length
            val beforeOk = i == 0 || !isPkgChar(line[i - 1])
            val afterOk = end >= line.length || when (line[end]) {
                // "pkg." ends a sentence; "pkg.debug" is another package.
                '.' -> end + 1 >= line.length || !isPkgChar(line[end + 1])
                else -> !isPkgChar(line[end])
            }
            if (beforeOk && afterOk) return true
            i = line.indexOf(pkg, i + 1)
        }
        return false
    }

    private fun isPkgChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

    /** Seconds since the epoch at the start of a `-v epoch` line, or null. */
    fun epochSeconds(line: String): Double? {
        val t = line.trimStart()
        val end = t.indexOfFirst { !(it.isDigit() || it == '.') }
        val num = if (end < 0) t else t.substring(0, end)
        if (num.length < MIN_EPOCH_DIGITS || !num.contains('.')) return null
        return num.toDoubleOrNull()
    }

    /** `-T 1` replays the newest buffered line; anything older than the reader's start is history. */
    fun isStale(line: String, startedAtMs: Long, slackMs: Long = 500): Boolean {
        val s = epochSeconds(line) ?: return false
        return (s * MS) < startedAtMs - slackMs
    }

    private const val MIN_EPOCH_DIGITS = 9
    private const val MS = 1000.0
}

/**
 * Decides, for each denial line, whether to launch the reader activity now, a little later
 * (one coalesced trailing read after a burst) or not at all. Not thread-safe: the app calls it
 * from one thread. Times are elapsed-realtime milliseconds.
 */
class ClipAutoGate(
    /** Least time between two reader launches. */
    private val minGapMs: Long = 1_200,
    /** After Tether itself writes the clipboard (text from the Mac), its own change is ignored this long. */
    private val echoWindowMs: Long = 2_000,
    /** After a deliberate background probe read, the denial it causes is ignored this long. */
    private val probeWindowMs: Long = 2_500,
    /** A launch that never reported back is considered over after this. */
    private val inFlightTimeoutMs: Long = 4_000,
    /** Hard cap on launches per minute (an app rewriting the clipboard in a loop must not steal focus forever). */
    private val maxPerMinute: Int = 20,
    private val stormPauseMs: Long = 60_000,
) {
    enum class Action { LAUNCH, LATER, IGNORE }

    data class Decision(val action: Action, val reason: String, val delayMs: Long = 0)

    sealed class Read {
        data class Send(val text: String, val hash: String) : Read()
        data class Skip(val reason: String) : Read()
    }

    private var macHash: String? = null
    private var localWriteAt = Long.MIN_VALUE / 2
    private var probeAt = Long.MIN_VALUE / 2
    private var lastLaunchAt = Long.MIN_VALUE / 2
    private var inFlightSince: Long? = null
    private var trailingPending = false
    private var pausedUntil = Long.MIN_VALUE / 2
    private val launches = ArrayDeque<Long>()

    /** Tether put [text] on the phone clipboard (it came from the Mac). */
    fun onLocalWrite(text: String, now: Long) {
        macHash = hash(text)
        localWriteAt = now
    }

    /** Tether is about to read the clipboard from the background on purpose (self-test). */
    fun onProbe(now: Long) {
        probeAt = now
    }

    fun inProbeWindow(now: Long) = now - probeAt in 0..probeWindowMs

    /** A denial line arrived; [trailing] when this is the delayed re-check a LATER asked for. */
    fun onDenial(now: Long, trailing: Boolean = false): Decision {
        if (trailing) trailingPending = false
        quietReason(now)?.let { return Decision(Action.IGNORE, it) }
        inFlightSince?.let { if (now - it > inFlightTimeoutMs) inFlightSince = null }
        val wait = maxOf(minGapMs - (now - lastLaunchAt), if (inFlightSince != null) minGapMs else 0L)
        if (wait > 0) {
            if (trailingPending) return Decision(Action.IGNORE, "coalesced")
            trailingPending = true
            return Decision(Action.LATER, "throttled", wait)
        }
        while (launches.isNotEmpty() && now - launches.first() > MINUTE_MS) launches.removeFirst()
        if (launches.size >= maxPerMinute) {
            pausedUntil = now + stormPauseMs
            return Decision(Action.IGNORE, "storm")
        }
        launches.addLast(now)
        lastLaunchAt = now
        inFlightSince = now
        return Decision(Action.LAUNCH, "changed")
    }

    private fun quietReason(now: Long): String? = when {
        now - localWriteAt in 0..echoWindowMs -> "echo"
        inProbeWindow(now) -> "probe"
        now < pausedUntil -> "storm"
        else -> null
    }

    /** The reader activity finished (read or not). */
    fun onFinished() {
        inFlightSince = null
    }

    /**
     * What to do with the text the reader got. [lastHash] is the hash of the last clip that
     * crossed the link either way (sent to the Mac or received from it).
     */
    fun onRead(text: String?, sensitive: Boolean, allowSensitive: Boolean, lastHash: String?): Read {
        if (text.isNullOrEmpty()) return Read.Skip("empty")
        if (text.isBlank()) return Read.Skip("blank")
        val h = hash(text)
        if (h == macHash) return Read.Skip("from_mac")
        if (h == lastHash) return Read.Skip("duplicate")
        if (sensitive && !allowSensitive) return Read.Skip("sensitive")
        return Read.Send(text, h)
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /** Same hash as the runtime's clipboard dedupe (base64 SHA-256 of the UTF-8 text). */
        fun hash(s: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
    }
}

/** Setup commands and the status line for Settings › On your Mac › Clipboard. */
object ClipAutoSetup {
    const val READ_LOGS = "android.permission.READ_LOGS"

    /** Android 13+ asks the user (system dialog) the first time a logcat session starts. */
    const val ANDROID_13 = 33

    /** The `log` group (AID_LOG); a process gets it at start only if READ_LOGS was granted before. */
    const val AID_LOG = 1007

    fun grantLogs(pkg: String) = "adb shell pm grant $pkg $READ_LOGS"
    fun grantOverlay(pkg: String) = "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow"
    fun restart(pkg: String) = "adb shell am force-stop $pkg"

    /** The three commands, one per line, for the guide and the Copy button. */
    fun commands(pkg: String): List<String> = listOf(grantLogs(pkg), grantOverlay(pkg), restart(pkg))

    /** True when /proc/self/status's `Groups:` line lists AID_LOG. */
    fun hasLogGroup(procStatus: String): Boolean {
        val line = procStatus.lineSequence().firstOrNull { it.startsWith("Groups:") } ?: return false
        return line.removePrefix("Groups:").trim().split(Regex("\\s+")).any { it == AID_LOG.toString() }
    }

    enum class Status {
        /** Auto mode off in settings. */
        OFF,

        /** READ_LOGS not granted: run the adb commands. */
        NEEDS_LOGS,

        /** Granted after this process started: Tether must restart (force-stop, then open). */
        NEEDS_RESTART,

        /** Can't open the reader from the background: allow "Display over other apps". */
        NEEDS_OVERLAY,

        /** Android 13+: log access not allowed (yet) for this session; open Tether and tap Allow. */
        NEEDS_APPROVAL,

        /** Everything granted, watching; not yet seen a line (verifies on the first copy). */
        WATCHING,

        /** A denial line was seen: auto mode works. */
        WORKING,
    }

    data class Inputs(
        val enabled: Boolean,
        val logsGranted: Boolean,
        val logGroup: Boolean,
        val overlay: Boolean,
        val readerRunning: Boolean,
        val verified: Boolean,
        val declined: Boolean,
    )

    fun status(i: Inputs): Status = when {
        !i.enabled -> Status.OFF
        !i.logsGranted -> Status.NEEDS_LOGS
        !i.logGroup -> Status.NEEDS_RESTART
        !i.overlay -> Status.NEEDS_OVERLAY
        i.declined || !i.readerRunning -> Status.NEEDS_APPROVAL
        i.verified -> Status.WORKING
        else -> Status.WATCHING
    }

    fun label(s: Status): String = when (s) {
        Status.OFF -> "Off"
        Status.NEEDS_LOGS -> "Needs one-time setup from your Mac"
        Status.NEEDS_RESTART -> "Restart Tether to finish"
        Status.NEEDS_OVERLAY -> "Allow display over other apps"
        Status.NEEDS_APPROVAL -> "Allow log access"
        Status.WATCHING -> "On · copy something to check"
        Status.WORKING -> "Working"
    }
}
