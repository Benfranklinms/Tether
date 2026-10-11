package dev.benfranklin.tether.core.features

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Finds the one-time code in an SMS or notification text, or null when there is none.
 *
 * A text only counts when it talks about a code ("OTP", "one time password", "verification
 * code", "is your code", "passcode", "PIN"…). Candidates are 4–8 digit runs (or 3+3 split with
 * a space or hyphen, "123-456"); amounts (Rs/INR/₹/$ before, "rs" after), masked account and
 * card numbers ("A/c XX1234", "card ending 4321"), reference/transaction ids, phone numbers
 * (10+ digits, possibly spaced, "+91 98765 43210"), times and dates are ignored. Among the
 * rest, the one closest to a code keyword wins.
 */
@Suppress("MagicNumber", "ComplexCondition", "LoopWithTooManyJumpStatements")
object OtpDetector {
    private val keyword = Regex(
        "\\b(otp|one[- ]?time(?: pass(?:word|code))?|verification|verify|passcode|pass code|security code|auth(?:entication)? code|" +
            "login code|sign[- ]?in code|access code|confirmation code|code|pin|2fa|tan)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val strong = Regex(
        "\\b(otp|one[- ]?time|verification code|passcode|security code|login code|code|pin)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val candidate = Regex("(?<![\\w₹$.,:/@#*+])(?:G-)?(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\w₹$]|[.,:/]\\d)")
    private val currencyBefore = Regex("(?:\\b(?:rs|inr|usd|amt|amount)\\.?|₹|\\$)\\s*$", RegexOption.IGNORE_CASE)
    private val currencyAfter = Regex("^\\s*(?:\\b(?:rs|inr|rupees|usd)\\b|/-)", RegexOption.IGNORE_CASE)
    private val accountBefore = Regex(
        "(?:\\b(?:a/c|ac|acct|account|card|ending(?: with| in)?|x{2,}|no|number|ref(?: ?no)?|reference|txn(?: id)?|" +
            "transaction(?: id)?|utr|order(?: id| no)?|id|imps|neft|upi ref|ticket|pnr|call|dial|helpline)\\.?|\\*+)\\s*[:#-]?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private const val LOOK_BEHIND = 18
    private const val PHONE_DIGITS = 10
    private const val MAX_TEXT = 1200

    fun find(text: String?): String? {
        val t = text?.take(MAX_TEXT) ?: return null
        if (!keyword.containsMatchIn(t)) return null
        val keys = strong.findAll(t).map { it.range }.toList().ifEmpty { keyword.findAll(t).map { it.range }.toList() }
        var best: Pair<Int, String>? = null
        for (m in candidate.findAll(t)) {
            val g = m.groups[1] ?: continue
            val start = g.range.first
            val end = g.range.last + 1
            if (excluded(t, start, end)) continue
            val code = g.value.replace(" ", "").replace("-", "")
            val dist = keys.minOf { k -> if (k.last < start) start - k.last else if (k.first > end) k.first - end else 0 }
            // Prefer 6 digits on ties (the common OTP length), then the earlier one.
            val score = dist * 4 + (if (code.length == 6) 0 else 1) + (if (looksLikeYear(code)) 2 else 0)
            if (best == null || score < best.first) best = score to code
        }
        return best?.second
    }

    private fun excluded(t: String, start: Int, end: Int): Boolean {
        val before = t.substring(maxOf(0, start - LOOK_BEHIND), start).removeSuffix("G-")
        val after = t.substring(end, minOf(t.length, end + 8))
        return currencyBefore.containsMatchIn(before) || currencyAfter.containsMatchIn(after) ||
            accountBefore.containsMatchIn(before) ||
            // Part of a longer number: a phone number written with spaces ("+91 98765 43210").
            digitsAround(t, start, end) >= PHONE_DIGITS
    }

    /** Digits in the run of [0-9 +-] around the match (phone numbers "+91 98765 43210"). */
    private fun digitsAround(t: String, start: Int, end: Int): Int {
        var a = start
        while (a > 0 && (t[a - 1].isDigit() || t[a - 1] == ' ' || t[a - 1] == '+') &&
            !(t[a - 1] == ' ' && a - 2 >= 0 && !t[a - 2].isDigit() && t[a - 2] != '+')
        ) a--
        var b = end
        while (b < t.length && (t[b].isDigit() || t[b] == ' ') && !(t[b] == ' ' && (b + 1 >= t.length || !t[b + 1].isDigit()))) b++
        return t.substring(a, b).count { it.isDigit() }
    }

    private fun looksLikeYear(code: String) = code.length == 4 && (code.startsWith("19") || code.startsWith("20"))
}

/**
 * Turns "dir/sub" requests into safe relative paths: no "..", no absolute paths, no NULs,
 * normalised separators. Returns null for anything that tries to leave the root.
 */
object FsPaths {
    private const val MAX_PATH = 1024

    fun clean(path: String): String? {
        if (path.length > MAX_PATH || path.contains('\u0000') || path.contains('\\')) return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    fun child(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    /** Folders first, then names (case-insensitive), like Finder. */
    fun <T> sorted(items: List<T>, isDir: (T) -> Boolean, name: (T) -> String): List<T> =
        items.sortedWith(compareBy<T>({ !isDir(it) }, { name(it).lowercase() }))
}

/**
 * Applies typing operations to a field's text the way a keyboard would, so the phone can use
 * ACTION_SET_TEXT when a field doesn't accept paste. [cursor] is the caret (end when unknown).
 */
object TypeEditor {
    data class Result(val text: String, val cursor: Int)

    fun apply(current: String, cursor: Int?, insert: String?, key: String?): Result {
        val c = (cursor ?: current.length).coerceIn(0, current.length)
        return when (key) {
            "backspace" -> if (c == 0) Result(current, 0) else {
                val cut = previousBoundary(current, c)
                Result(current.removeRange(cut, c), cut)
            }
            "clear" -> Result("", 0)
            "tab" -> Result(current.substring(0, c) + "\t" + current.substring(c), c + 1)
            else -> {
                val s = insert.orEmpty()
                Result(current.substring(0, c) + s + current.substring(c), c + s.length)
            }
        }
    }

    /** Deletes a whole surrogate pair / emoji code point, not half of it. */
    private fun previousBoundary(s: String, c: Int): Int =
        if (c >= 2 && Character.isLowSurrogate(s[c - 1]) && Character.isHighSurrogate(s[c - 2])) c - 2 else c - 1
}

/**
 * Two-way DND/Focus sync without ping-pong: a state we just applied because the other side
 * asked is not echoed back, and nothing is sent when the state didn't change.
 */
class DndSync(private val echoWindowMs: Long = 4_000) {
    private var last: Boolean? = null
    private var appliedRemote: Pair<Boolean, Long>? = null

    /** Our local state is [on] (at [now]); returns true when it should be sent to the peer. */
    fun local(on: Boolean, now: Long): Boolean {
        val r = appliedRemote
        val echo = r != null && r.first == on && now - r.second < echoWindowMs
        val changed = last != on
        last = on
        if (echo) return false
        return changed
    }

    /** The peer reported [on]; returns true when we should apply it locally. */
    fun remote(on: Boolean, now: Long): Boolean {
        if (last == on) return false
        appliedRemote = on to now
        return true
    }

    fun reset() {
        last = null
        appliedRemote = null
    }
}

/** De-duplicates Continue cards: the same thing within [windowMs] is only offered once. */
class ContinueGate(private val windowMs: Long = DEFAULT_WINDOW_MS, private val max: Int = 64) {
    companion object {
        const val DEFAULT_WINDOW_MS = 600_000L
    }

    private val seen = LinkedHashMap<String, Long>()

    fun offer(key: String, now: Long): Boolean {
        seen.entries.removeAll { now - it.value > windowMs }
        if (seen.containsKey(key)) return false
        seen[key] = now
        while (seen.size > max) seen.remove(seen.keys.first())
        return true
    }
}

// ---------------- Updates ----------------

/** Compares dotted versions ("0.5.0" > "0.4.3"; "0.5.0" > "0.5.0-dev"; CI builds "0.0.0-ci.N" are oldest). */
object Versions {
    fun compare(a: String, b: String): Int {
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val d = (an.getOrElse(i) { 0 }).compareTo(bn.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return when {
            ap == null && bp == null -> 0
            ap == null -> 1
            bp == null -> -1
            else -> ap.compareTo(bp)
        }
    }

    fun newer(candidate: String, current: String) = compare(candidate, current) > 0

    private fun split(v: String): Pair<List<Int>, String?> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val pre = clean.substringAfter('-', "").ifEmpty { null }
        val nums = clean.substringBefore('-').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return nums to pre
    }
}

/** One downloadable build in an update feed. [sha256] lower-case hex. */
@Serializable data class UpdateAsset(
    val url: String,
    val sha256: String? = null,
    val size: Long = 0,
    val versionCode: Long = 0,
    /** APK signer certificate SHA-256 (hex), so the phone can refuse a differently signed build. */
    val cert: String? = null,
)

/** What an update check found. */
@Serializable data class UpdateInfo(
    val version: String,
    val notes: String = "",
    val page: String? = null,
    val android: UpdateAsset? = null,
    val macZip: UpdateAsset? = null,
    val macDmg: UpdateAsset? = null,
    val published: String? = null,
)

/**
 * Parses an update check: the public GitHub Releases API (`/releases/latest` of
 * Benfranklinms/Tether). 0.5.3 removed the token and custom-feed options; this is the only source.
 */
object UpdateFeed {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** GitHub `GET /repos/{o}/{r}/releases/latest`. Asset digests ("sha256:…") are used when GitHub provides them. */
    fun parseGitHub(text: String): UpdateInfo? = runCatching {
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (o["draft"]?.jsonPrimitive?.contentOrNull == "true" || o["prerelease"]?.jsonPrimitive?.contentOrNull == "true") return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun asset(suffix: String): UpdateAsset? {
            val a = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(suffix, true) == true } ?: return null
            val url = a["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            val digest = a["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            return UpdateAsset(
                url, digest?.lowercase(), a["size"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
        UpdateInfo(
            version = tag.removePrefix("v"),
            notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            page = o["html_url"]?.jsonPrimitive?.contentOrNull,
            android = asset(".apk"),
            macZip = asset(".zip"),
            macDmg = asset(".dmg"),
            published = o["published_at"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    /** A `/releases/latest` answer, or null (draft, pre-release, error body, not JSON). */
    fun parse(text: String): UpdateInfo? = parseGitHub(text)
}

/**
 * Updates come from Tether's public GitHub releases, unauthenticated (60 API requests an hour per
 * IP is far more than a check on launch and every few hours needs). HTTPS only.
 */
object GitHubReleases {
    const val OWNER = "Benfranklinms"
    const val REPO = "Tether"
    const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    fun headers(binary: Boolean): Map<String, String> = mapOf(
        "Accept" to (if (binary) "application/octet-stream" else "application/vnd.github+json"),
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    @Suppress("MagicNumber")
    fun explain(status: Int): String = when (status) {
        200 -> "OK"
        403, 429 -> "GitHub is busy right now (rate limited). Tether tries again later."
        404 -> "No release found."
        else -> "GitHub answered $status."
    }
}
