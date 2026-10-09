package dev.benfranklin.tether.notify

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.benfranklin.tether.core.notify.DeviceContext
import dev.benfranklin.tether.core.notify.FilterDecision
import dev.benfranklin.tether.core.notify.NotificationDeduper
import dev.benfranklin.tether.core.notify.NotificationFacts
import dev.benfranklin.tether.core.notify.NotificationFilter
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime
import java.lang.ref.WeakReference
import java.util.Calendar

/**
 * Reads the phone's notifications (after the user grants notification access), runs each
 * through [NotificationFilter] with the user's settings, and mirrors the survivors to the
 * Mac. Removals are mirrored too when dismiss-sync is on.
 */
class MirrorListenerService : NotificationListenerService() {
    private val deduper = NotificationDeduper()
    private val forwarded = HashSet<String>()

    override fun onListenerConnected() {
        instance = WeakReference(this)
        Log.i("notify.listener_connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i("notify.listener_disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val rt = applicationContext.runtime
        if (rt.ui.value.peer == null) return
        val facts = facts(sbn, rankingMap) ?: return
        rt.seenApps.saw(facts.pkg, facts.appLabel, facts.isSystemApp, System.currentTimeMillis())
        val decision = NotificationFilter.decide(facts, rt.settings.current.notifications, deviceContext(), packageName)
        if (decision is FilterDecision.Forward) {
            val actions = if (rt.settings.current.notificationActions) {
                NotificationActions.extract(sbn.notification)
            } else {
                emptyList()
            }
            NotificationActions.register(sbn.key, actions)
            val notif = decision.notif.copy(actions = actions.map { it.info })
            if (deduper.shouldForward(notif)) {
                forwarded += sbn.key
                rt.mirror(notif)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        deduper.removed(sbn.key)
        NotificationActions.remove(sbn.key)
        // Only tell the Mac about notifications it actually has, and not when the Mac itself asked.
        if (forwarded.remove(sbn.key) && reason != REASON_LISTENER_CANCEL) {
            applicationContext.runtime.mirrorRemoved(sbn.key)
        }
    }

    private fun facts(sbn: StatusBarNotification, ranking: RankingMap?): NotificationFacts? {
        val n = sbn.notification ?: return null
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val r = Ranking()
        val ranked = ranking?.getRanking(sbn.key, r) == true
        val importance = if (ranked) r.importance else NotificationManager.IMPORTANCE_DEFAULT
        val channel = if (ranked) r.channel else null
        val silent = channel != null && channel.sound == null && !channel.shouldVibrate()
        val ai = appInfo(sbn.packageName)
        val system = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        val social = ai != null && ai.category == ApplicationInfo.CATEGORY_SOCIAL
        return NotificationFacts(
            key = sbn.key, pkg = sbn.packageName, appLabel = label(ai, sbn.packageName), title = title, text = text,
            postedAt = sbn.postTime, category = n.category,
            isOngoing = sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            importance = importance, isSilent = silent,
            isSystemApp = system, isSocialApp = social,
        )
    }

    private fun appInfo(pkg: String): ApplicationInfo? = try {
        packageManager.getApplicationInfo(pkg, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun label(ai: ApplicationInfo?, pkg: String): String =
        ai?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg

    private fun deviceContext(): DeviceContext {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val locked = km?.isKeyguardLocked == true || pm?.isInteractive == false
        val cal = Calendar.getInstance()
        return DeviceContext(cal.get(Calendar.HOUR_OF_DAY) * MINUTES + cal.get(Calendar.MINUTE), locked)
    }

    companion object {
        private const val MINUTES = 60
        @Volatile private var instance: WeakReference<MirrorListenerService>? = null

        val isConnected: Boolean get() = instance?.get() != null

        /** The Mac dismissed a mirrored notification: clear it here too. */
        fun dismiss(key: String) {
            val s = instance?.get() ?: return
            try {
                s.cancelNotification(key)
            } catch (e: SecurityException) {
                Log.w("notify.cancel_denied", "reason" to e.message)
            }
        }
    }
}
