package dev.benfranklin.tether.core.notify

import dev.benfranklin.tether.core.proto.Notif
import dev.benfranklin.tether.core.proto.Protocol
import kotlinx.serialization.Serializable

/** Everything the filter needs to know about one posted Android notification. */
data class NotificationFacts(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** Notification.category (e.g. "msg", "email", "social"), may be null. */
    val category: String? = null,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    /** NotificationManager.IMPORTANCE_* of the channel (0..5). */
    val importance: Int = IMPORTANCE_DEFAULT,
    /** Channel has no sound and no vibration, or the notification is marked silent. */
    val isSilent: Boolean = false,
    /** App is a system app (FLAG_SYSTEM) and not on the known-messaging list. */
    val isSystemApp: Boolean = false,
    /** ApplicationInfo.category is CATEGORY_SOCIAL (or the app is a known messenger). */
    val isSocialApp: Boolean = false,
) {
    companion object {
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
    }
}

/** Per-app choice. INHERIT means "use the default rule for this kind of app". */
@Serializable
enum class AppRule { INHERIT, ALLOW, BLOCK }

@Serializable
data class QuietHours(
    val enabled: Boolean = false,
    /** Minutes after local midnight. A window may wrap past midnight (22:00 -> 07:00). */
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
) {
    fun contains(minuteOfDay: Int): Boolean {
        if (!enabled || startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }
}

/** Phone-side mirroring settings. Persisted as JSON. */
@Serializable
data class NotificationSettings(
    val enabled: Boolean = true,
    val apps: Map<String, AppRule> = emptyMap(),
    val skipOngoing: Boolean = true,
    val skipSilent: Boolean = true,
    val skipLowPriority: Boolean = true,
    val skipGroupSummaries: Boolean = true,
    /** Case-insensitive substrings; a match in title or text mutes the notification. */
    val mutedKeywords: List<String> = emptyList(),
    val quietHours: QuietHours = QuietHours(),
    /** Only mirror while the phone is locked or its screen is off. */
    val onlyWhenLocked: Boolean = false,
    /** Send "New notification from <App>" instead of the title and text. */
    val hideContent: Boolean = false,
    /** Dismissing on one device clears the notification on the other. */
    val dismissSync: Boolean = true,
) {
    fun ruleFor(pkg: String): AppRule = apps[pkg] ?: AppRule.INHERIT

    fun withRule(pkg: String, rule: AppRule): NotificationSettings =
        copy(apps = if (rule == AppRule.INHERIT) apps - pkg else apps + (pkg to rule))
}

/** Device state at the moment the notification was posted. */
data class DeviceContext(val minuteOfDay: Int, val isLocked: Boolean)

sealed interface FilterDecision {
    data class Forward(val notif: Notif) : FilterDecision
    data class Drop(val reason: DropReason) : FilterDecision
}

enum class DropReason {
    DISABLED, OWN_APP, APP_BLOCKED, ONGOING, SILENT, LOW_PRIORITY, GROUP_SUMMARY, EMPTY, KEYWORD, QUIET_HOURS, UNLOCKED,
}

object NotificationFilter {
    /** Messaging / social apps that are mirrored by default even if Android flags them as system apps. */
    val KNOWN_MESSENGERS: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.google.android.gm", "com.microsoft.office.outlook", "com.slack", "com.discord",
        "com.instagram.android", "com.facebook.orca", "com.twitter.android", "com.snapchat.android",
        "com.google.android.apps.dynamite", "com.linkedin.android", "com.microsoft.teams",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.truecaller",
    )

    private val MESSAGE_CATEGORIES = setOf("msg", "email", "social", "call", "missed_call")
    private val NOISE_CATEGORIES = setOf("progress", "service", "status", "transport", "sys", "recommendation", "promo")

    /**
     * Default (INHERIT) answer: messengers and social apps on; system apps off; other apps on
     * unless the notification is background noise (progress, media transport, promos...).
     */
    fun defaultAllowed(f: NotificationFacts): Boolean = when {
        f.pkg in KNOWN_MESSENGERS -> true
        f.isSocialApp -> true
        f.category in MESSAGE_CATEGORIES -> !f.isSystemApp || f.category == "call" || f.category == "missed_call"
        f.isSystemApp -> false
        f.category in NOISE_CATEGORIES -> false
        else -> true
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun decide(f: NotificationFacts, s: NotificationSettings, ctx: DeviceContext, ownPackage: String): FilterDecision {
        if (!s.enabled) return drop(DropReason.DISABLED)
        if (f.pkg == ownPackage) return drop(DropReason.OWN_APP)
        val allowed = when (s.ruleFor(f.pkg)) {
            AppRule.ALLOW -> true
            AppRule.BLOCK -> false
            AppRule.INHERIT -> defaultAllowed(f)
        }
        if (!allowed) return drop(DropReason.APP_BLOCKED)
        if (s.skipGroupSummaries && f.isGroupSummary) return drop(DropReason.GROUP_SUMMARY)
        if (s.skipOngoing && f.isOngoing) return drop(DropReason.ONGOING)
        if (s.skipLowPriority && f.importance <= NotificationFacts.IMPORTANCE_LOW) return drop(DropReason.LOW_PRIORITY)
        if (s.skipSilent && f.isSilent) return drop(DropReason.SILENT)
        if (f.title.isBlank() && f.text.isBlank()) return drop(DropReason.EMPTY)
        if (matchesKeyword(f, s.mutedKeywords)) return drop(DropReason.KEYWORD)
        if (s.quietHours.contains(ctx.minuteOfDay)) return drop(DropReason.QUIET_HOURS)
        if (s.onlyWhenLocked && !ctx.isLocked) return drop(DropReason.UNLOCKED)
        val title = if (s.hideContent) f.appLabel else f.title.take(Protocol.MAX_TITLE_CHARS)
        val text = if (s.hideContent) "New notification from ${f.appLabel}" else f.text.take(Protocol.MAX_TEXT_CHARS)
        return FilterDecision.Forward(
            Notif(key = f.key, pkg = f.pkg, app = f.appLabel, title = title, text = text, ts = f.postedAt),
        )
    }

    fun matchesKeyword(f: NotificationFacts, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val hay = (f.title + "\n" + f.text).lowercase()
        return keywords.any { k -> k.isNotBlank() && hay.contains(k.trim().lowercase()) }
    }

    private fun drop(r: DropReason) = FilterDecision.Drop(r)
}

/**
 * Apps re-post the same notification constantly (progress ticks, re-sorts). Forward a key
 * again only when what the Mac would show actually changed.
 */
class NotificationDeduper(private val capacity: Int = 512) {
    private val seen = object : LinkedHashMap<String, Int>(capacity, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > capacity
    }

    fun shouldForward(n: Notif): Boolean {
        val sig = listOf(n.app, n.title, n.text).hashCode()
        if (seen[n.key] == sig) return false
        seen[n.key] = sig
        return true
    }

    fun removed(key: String) {
        seen.remove(key)
    }

    fun clear() = seen.clear()

    private companion object {
        const val LOAD = 0.75f
    }
}
