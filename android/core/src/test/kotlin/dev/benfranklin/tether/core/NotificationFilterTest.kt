package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.notify.AppRule
import dev.benfranklin.tether.core.notify.DeviceContext
import dev.benfranklin.tether.core.notify.DropReason
import dev.benfranklin.tether.core.notify.FilterDecision
import dev.benfranklin.tether.core.notify.NotificationDeduper
import dev.benfranklin.tether.core.notify.NotificationFacts
import dev.benfranklin.tether.core.notify.NotificationFilter
import dev.benfranklin.tether.core.notify.NotificationSettings
import dev.benfranklin.tether.core.notify.QuietHours
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.Notif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val own = "in.benfranklin.tether"
    private val awake = DeviceContext(minuteOfDay = 12 * 60, isLocked = false)
    private val base = NotificationFacts(
        key = "k", pkg = "com.whatsapp", appLabel = "WhatsApp", title = "Asha", text = "Lunch?", postedAt = 5,
        category = "msg",
    )

    private fun decide(f: NotificationFacts = base, s: NotificationSettings = NotificationSettings(), ctx: DeviceContext = awake) =
        NotificationFilter.decide(f, s, ctx, own)

    private fun dropped(r: DropReason, d: FilterDecision) = assertEquals(FilterDecision.Drop(r), d)

    @Test
    fun forwardsMessengerByDefault() {
        val d = decide() as FilterDecision.Forward
        assertEquals(Notif("k", "com.whatsapp", "WhatsApp", "Asha", "Lunch?", 5), d.notif)
    }

    @Test
    fun defaultsBySortOfApp() {
        assertTrue(NotificationFilter.defaultAllowed(base))
        assertTrue(NotificationFilter.defaultAllowed(base.copy(pkg = "x.social", category = null, isSocialApp = true)))
        assertFalse(NotificationFilter.defaultAllowed(base.copy(pkg = "android", category = null, isSystemApp = true)))
        assertTrue(NotificationFilter.defaultAllowed(base.copy(pkg = "com.android.phone", category = "missed_call", isSystemApp = true)))
        assertFalse(NotificationFilter.defaultAllowed(base.copy(pkg = "com.shop", category = "promo")))
        assertTrue(NotificationFilter.defaultAllowed(base.copy(pkg = "com.bank", category = null)))
        // A system app on the messenger list is still on.
        assertTrue(NotificationFilter.defaultAllowed(base.copy(pkg = "com.google.android.apps.messaging", isSystemApp = true)))
    }

    @Test
    fun perAppRulesOverrideDefaults() {
        val sys = base.copy(pkg = "com.android.vending", isSystemApp = true, category = null)
        dropped(DropReason.APP_BLOCKED, decide(sys))
        assertTrue(decide(sys, NotificationSettings().withRule(sys.pkg, AppRule.ALLOW)) is FilterDecision.Forward)
        dropped(DropReason.APP_BLOCKED, decide(base, NotificationSettings().withRule("com.whatsapp", AppRule.BLOCK)))
        val s = NotificationSettings().withRule("a", AppRule.BLOCK).withRule("a", AppRule.INHERIT)
        assertTrue(s.apps.isEmpty())
    }

    @Test
    fun skipToggles() {
        dropped(DropReason.ONGOING, decide(base.copy(isOngoing = true)))
        dropped(DropReason.GROUP_SUMMARY, decide(base.copy(isGroupSummary = true)))
        dropped(DropReason.LOW_PRIORITY, decide(base.copy(importance = 2)))
        dropped(DropReason.SILENT, decide(base.copy(isSilent = true)))
        val loose = NotificationSettings(skipOngoing = false, skipSilent = false, skipLowPriority = false, skipGroupSummaries = false)
        assertTrue(decide(base.copy(isOngoing = true, isSilent = true, importance = 1, isGroupSummary = true), loose) is FilterDecision.Forward)
    }

    @Test
    fun ownAppEmptyAndDisabled() {
        dropped(DropReason.OWN_APP, decide(base.copy(pkg = own)))
        dropped(DropReason.EMPTY, decide(base.copy(title = " ", text = "")))
        dropped(DropReason.DISABLED, decide(s = NotificationSettings(enabled = false)))
    }

    @Test
    fun keywordMutesAreCaseInsensitive() {
        val s = NotificationSettings(mutedKeywords = listOf("  OTP ", ""))
        dropped(DropReason.KEYWORD, decide(base.copy(text = "Your otp is 1234"), s))
        assertTrue(decide(base, s) is FilterDecision.Forward)
    }

    @Test
    fun quietHoursWrapMidnight() {
        val q = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(q.contains(23 * 60))
        assertTrue(q.contains(0))
        assertTrue(q.contains(6 * 60 + 59))
        assertFalse(q.contains(7 * 60))
        assertFalse(q.contains(12 * 60))
        val day = QuietHours(enabled = true, startMinute = 9 * 60, endMinute = 17 * 60)
        assertTrue(day.contains(9 * 60))
        assertFalse(day.contains(17 * 60))
        assertFalse(QuietHours(enabled = false).contains(23 * 60))
        dropped(DropReason.QUIET_HOURS, decide(s = NotificationSettings(quietHours = q), ctx = DeviceContext(23 * 60, true)))
    }

    @Test
    fun onlyWhenLocked() {
        val s = NotificationSettings(onlyWhenLocked = true)
        dropped(DropReason.UNLOCKED, decide(s = s))
        assertTrue(decide(s = s, ctx = awake.copy(isLocked = true)) is FilterDecision.Forward)
    }

    @Test
    fun hideContentRedacts() {
        val d = decide(s = NotificationSettings(hideContent = true)) as FilterDecision.Forward
        assertEquals("WhatsApp", d.notif.title)
        assertEquals("New notification from WhatsApp", d.notif.text)
    }

    @Test
    fun longContentTruncated() {
        val d = decide(base.copy(title = "t".repeat(1000), text = "x".repeat(10_000))) as FilterDecision.Forward
        assertEquals(256, d.notif.title.length)
        assertEquals(2048, d.notif.text.length)
    }

    @Test
    fun settingsPersistAsJson() {
        val s = NotificationSettings(
            apps = mapOf("a" to AppRule.ALLOW, "b" to AppRule.BLOCK), mutedKeywords = listOf("otp"),
            quietHours = QuietHours(true, 60, 120), hideContent = true, onlyWhenLocked = true, dismissSync = false,
        )
        val json = MessageCodec.json.encodeToString(NotificationSettings.serializer(), s)
        assertEquals(s, MessageCodec.json.decodeFromString(NotificationSettings.serializer(), json))
        // Unknown future keys and missing keys are tolerated.
        assertEquals(NotificationSettings(), MessageCodec.json.decodeFromString(NotificationSettings.serializer(), "{\"future\":1}"))
    }

    @Test
    fun deduperForwardsOnlyChanges() {
        val d = NotificationDeduper(capacity = 2)
        val n = Notif("k", "p", "A", "t", "x", 1)
        assertTrue(d.shouldForward(n))
        assertFalse(d.shouldForward(n.copy(ts = 2)))
        assertTrue(d.shouldForward(n.copy(text = "y")))
        d.removed("k")
        assertTrue(d.shouldForward(n))
        d.shouldForward(n.copy(key = "a"))
        d.shouldForward(n.copy(key = "b")) // evicts "k"
        assertTrue(d.shouldForward(n))
    }
}
