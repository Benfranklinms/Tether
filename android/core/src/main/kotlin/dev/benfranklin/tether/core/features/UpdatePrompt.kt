package dev.benfranklin.tether.core.features

/**
 * When the in-app update popup and the one-time "What's new" sheet appear (0.5.2). Mirrored
 * in TetherCore `UpdatePrompt` with the same tests.
 */
object UpdatePrompt {
    const val SNOOZE_MS = 24L * 3600 * 1000
    const val FOREGROUND_CHECK_MS = 6L * 3600 * 1000
    const val DAILY_MS = 24L * 3600 * 1000
    private const val CHANGELOG_MAX = 1_800

    /** Show the popup for [latest]? Not when it isn't newer, was skipped, or "Later" is still running. */
    fun shouldShow(latest: String, current: String, skipped: String?, snoozedUntil: Long, now: Long): Boolean =
        Versions.compare(latest, current) > 0 && latest != skipped && now >= snoozedUntil

    /** Check now? On launch always; on coming to the foreground at most every 6 h; otherwise daily. */
    fun shouldCheck(lastCheck: Long, now: Long, launch: Boolean, foreground: Boolean): Boolean = when {
        launch -> true
        foreground -> now - lastCheck >= FOREGROUND_CHECK_MS
        else -> now - lastCheck >= DAILY_MS
    }

    /** One-time "What's new in X" after an update: when this version's sheet wasn't shown and an older one ran before. */
    fun showWhatsNew(current: String, seen: String?): Boolean = seen != null && Versions.compare(current, seen) > 0

    /**
     * The human changelog out of a release body: the "What's new" section when there is one
     * (until the next heading of the same or higher level), without the downloads list, trimmed.
     */
    fun changelog(notes: String): String {
        val lines = notes.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("#") && it.contains("what's new", ignoreCase = true) }
        val body = if (start >= 0) {
            val level = lines[start].trimStart().takeWhile { it == '#' }.length
            val rest = lines.drop(start + 1)
            val end = rest.indexOfFirst { l -> l.trimStart().startsWith("#") && l.trimStart().takeWhile { it == '#' }.length <= level }
            if (end >= 0) rest.take(end) else rest
        } else {
            lines.takeWhile { !(it.trimStart().startsWith("#") && it.contains("download", ignoreCase = true)) }
        }
        val text = body.joinToString("\n").trim()
        return if (text.length <= CHANGELOG_MAX) text else text.take(CHANGELOG_MAX).substringBeforeLast('\n') + "\n…"
    }
}
