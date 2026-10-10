@file:Suppress("MagicNumber", "SwallowedException")

package dev.benfranklin.tether.v6

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import dev.benfranklin.tether.core.features.HotspotSwitchFinder
import dev.benfranklin.tether.core.features.UiNode
import dev.benfranklin.tether.log.Log

/**
 * Instant Hotspot, best effort. Android has no API that lets a third-party app start Wi-Fi
 * tethering, so Tether control (the accessibility service the user already enabled for typing
 * and screen control) opens the hotspot settings screen and clicks the switch, as a person
 * would. When the switch can't be found the screen stays open for the user (manual fallback).
 */
object HotspotFlipper {
    private val main = Handler(Looper.getMainLooper())
    private const val POLL_MS = 350L
    private const val GIVE_UP_MS = 9_000L
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 30

    /** Settings screens that hold the hotspot switch, most specific first (AOSP / Pixel / OEM builds). */
    private val screens = listOf(
        "com.android.settings/.Settings\$WifiTetherSettingsActivity",
        "com.android.settings/.Settings\$TetherSettingsActivity",
        "com.android.settings/.TetherSettings",
        "com.oplus.wirelesssettings/.wifi.tether.WifiTetherSettingsActivity",
        "com.android.settings/.wifi.tether.WifiTetherSettingsActivity",
    )

    /** Opens the hotspot settings screen; true when one could be started. */
    fun openSettings(c: Context): Boolean {
        for (s in screens) {
            val cn = ComponentName.unflattenFromString(s) ?: continue
            val i = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (i.resolveActivity(c.packageManager) != null) {
                try {
                    c.startActivity(i)
                    Log.i("hotspot.settings_opened", "screen" to s.substringAfterLast('.'))
                    return true
                } catch (e: SecurityException) {
                    Log.w("hotspot.settings_blocked", "screen" to s.substringAfterLast('.'), "reason" to e.message)
                } catch (e: android.content.ActivityNotFoundException) {
                    Log.w("hotspot.settings_missing", "screen" to s.substringAfterLast('.'))
                }
            }
        }
        return try {
            c.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        }
    }

    /** Is the soft AP on? (hidden WifiManager API, read by reflection; null = unknown). */
    fun apEnabled(c: Context): Boolean? = try {
        val wm = c.applicationContext.getSystemService(WifiManager::class.java)
        wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as? Boolean
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /**
     * Opens the screen and flips the switch. [done] runs on the main thread with true when the
     * switch was clicked (or was already on), false when it wasn't found in time.
     */
    fun flip(service: AccessibilityService, done: (Boolean) -> Unit) {
        if (apEnabled(service) == true) return done(true)
        if (!openSettings(service)) return done(false)
        val start = System.currentTimeMillis()
        var entered = 0
        fun step() {
            val root = service.rootInActiveWindow
            if (root != null) {
                val nodes = ArrayList<AccessibilityNodeInfo>()
                val flat = ArrayList<UiNode>()
                collect(root, null, nodes, flat, 0)
                val sw = HotspotSwitchFinder.pick(flat)
                if (sw != null) {
                    val n = nodes[sw]
                    if (flat[sw].checked) {
                        Log.i("hotspot.switch_already_on")
                        return done(true)
                    }
                    val ok = click(n)
                    Log.i("hotspot.switch_clicked", "ok" to ok)
                    if (ok) {
                        main.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 1_200)
                        return done(true)
                    }
                } else if (entered < 2) {
                    HotspotSwitchFinder.pickEntry(flat)?.let { i ->
                        entered++
                        click(nodes[i])
                        Log.i("hotspot.entry_clicked")
                    }
                }
            }
            if (System.currentTimeMillis() - start > GIVE_UP_MS) {
                Log.w("hotspot.switch_not_found")
                return done(false)
            }
            main.postDelayed({ step() }, POLL_MS)
        }
        main.postDelayed({ step() }, POLL_MS * 2)
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            hops++
        }
        return false
    }

    /** Flattens the tree; a switch without text takes its row's title as its label. */
    private fun collect(
        n: AccessibilityNodeInfo,
        rowLabel: String?,
        nodes: MutableList<AccessibilityNodeInfo>,
        flat: MutableList<UiNode>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || nodes.size > MAX_NODES) return
        val own = (n.text ?: n.contentDescription)?.toString().orEmpty()
        val row = rowTitle(n) ?: rowLabel
        val label = own.ifEmpty { row.orEmpty() }
        nodes += n
        flat += UiNode(label, n.isCheckable, n.isChecked, n.isClickable, n.className?.toString().orEmpty())
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, row, nodes, flat, depth + 1) }
    }

    /** The first text among a node's direct children (a settings row's title). */
    private fun rowTitle(n: AccessibilityNodeInfo): String? {
        for (i in 0 until minOf(n.childCount, 6)) {
            val c = n.getChild(i) ?: continue
            val t = c.text?.toString()
            if (!t.isNullOrBlank()) return t
            for (j in 0 until minOf(c.childCount, 4)) {
                val t2 = c.getChild(j)?.text?.toString()
                if (!t2.isNullOrBlank()) return t2
            }
        }
        return null
    }
}
