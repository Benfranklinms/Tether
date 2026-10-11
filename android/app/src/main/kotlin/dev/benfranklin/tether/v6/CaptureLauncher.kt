@file:Suppress("MagicNumber")

package dev.benfranklin.tether.v6

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dev.benfranklin.tether.R
import dev.benfranklin.tether.core.features.CapturePolicy
import dev.benfranklin.tether.core.proto.CaptureKinds
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.screen.ControlService
import dev.benfranklin.tether.service.Notifications

/**
 * Gets CaptureActivity on screen for an Insert from Phone request, from the background.
 *
 * Root cause of "Take Photo does nothing" (0.5.2): Tether called startActivity() whenever it held
 * "Display over other apps". Since Android 15 that permission only exempts an app from the
 * background-activity-start block while it shows an overlay window, so the call returned
 * normally and Android silently dropped the launch; Tether still told the Mac "camera open", and
 * the never-finished request then answered every later one with "busy".
 *
 * Now each step of [CapturePolicy.plan] is tried and must be confirmed by CaptureActivity's
 * onCreate ([confirmed]) within [CapturePolicy.CONFIRM_MS]; otherwise the next step runs:
 *  - DIRECT: Tether is in front;
 *  - ACCESSIBILITY: Tether control is on (the system's binding carries the background-start privilege);
 *  - OVERLAY_BRIDGE: a 1-px invisible overlay window is shown for the launch (Android 15+ with
 *    "Display over other apps"), then removed;
 *  - NOTIFICATION: a high-priority notification (full-screen intent when allowed) the user taps.
 */
class CaptureLauncher(private val app: Context, private val foreground: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private var bridge: View? = null
    private var ref: String? = null
    private var opened = false

    /** Starts the attempts for request [r]; [onNotification] runs when it falls back to the notification. */
    fun launch(r: String, kind: String, onNotification: () -> Unit) {
        main.removeCallbacksAndMessages(null)
        removeBridge()
        ref = r
        opened = false
        val plan = CapturePolicy.plan(
            foreground = foreground(), accessibility = ControlService.available,
            canOverlay = Settings.canDrawOverlays(app), sdk = Build.VERSION.SDK_INT,
        )
        Log.i("capture.plan", "steps" to plan.joinToString(",") { it.name.lowercase() })
        step(r, kind, plan, 0, onNotification)
    }

    /** CaptureActivity is on screen for [r]. */
    fun confirmed(r: String) {
        if (r != ref) return
        opened = true
        main.removeCallbacksAndMessages(null)
        removeBridge()
        Notifications.cancelId(app, ID)
    }

    fun cancel() {
        main.removeCallbacksAndMessages(null)
        removeBridge()
        Notifications.cancelId(app, ID)
        ref = null
    }

    private fun step(r: String, kind: String, plan: List<CapturePolicy.Launch>, i: Int, onNotification: () -> Unit) {
        if (r != ref || opened || i >= plan.size) return
        val how = plan[i]
        val intent = CaptureActivity.intent(app, r, kind)
        if (how == CapturePolicy.Launch.NOTIFICATION) {
            notify(intent, kind)
            onNotification()
            return
        }
        try {
            when (how) {
                CapturePolicy.Launch.DIRECT -> app.startActivity(intent)
                CapturePolicy.Launch.ACCESSIBILITY -> (ControlService.current ?: app).startActivity(intent)
                CapturePolicy.Launch.OVERLAY_BRIDGE -> withBridge { app.startActivity(intent) }
                CapturePolicy.Launch.NOTIFICATION -> Unit
            }
        } catch (e: SecurityException) {
            Log.w("capture.launch_threw", "how" to how.name, "error" to e.javaClass.simpleName)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("capture.launch_threw", "how" to how.name, "error" to e.javaClass.simpleName)
        }
        main.postDelayed({
            if (r == ref && !opened) {
                Log.w("capture.launch_blocked", "how" to how.name.lowercase())
                removeBridge()
                step(r, kind, plan, i + 1, onNotification)
            }
        }, CapturePolicy.CONFIRM_MS)
    }

    /** Shows a 1-px, untouchable, transparent overlay, then runs [then] once it is on screen. */
    private fun withBridge(then: () -> Unit) {
        val wm = app.getSystemService(WindowManager::class.java)
        val v = View(app)
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(v, lp)
            bridge = v
            // Start once the window is really visible (Android checks for a visible overlay window).
            v.post { main.postDelayed(then, BRIDGE_SETTLE_MS) }
        } catch (e: WindowManager.BadTokenException) {
            Log.w("capture.bridge_failed", "error" to e.javaClass.simpleName)
            then()
        } catch (e: SecurityException) {
            Log.w("capture.bridge_failed", "error" to e.javaClass.simpleName)
            then()
        }
    }

    private fun removeBridge() {
        val v = bridge ?: return
        bridge = null
        runCatching { app.getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun notify(intent: Intent, kind: String) {
        val pi = PendingIntent.getActivity(app, 7001, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = app.getSystemService(NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm?.canUseFullScreenIntent() == true
        val b = NotificationCompat.Builder(app, Notifications.CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(if (kind == CaptureKinds.SCAN) "Scan a document for your Mac" else "Take a photo for your Mac")
            .setContentText(if (kind == CaptureKinds.SCAN) "Tap to open the scanner" else "Tap to open the camera")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(CapturePolicy.STALE_MS)
            .setAutoCancel(true)
            .setContentIntent(pi)
        if (fullScreen) b.setFullScreenIntent(pi, true)
        Notifications.postRaw(app, ID, b.build())
        Log.i("capture.notification", "fullscreen" to fullScreen)
    }

    companion object {
        const val ID = 7_100
        private const val BRIDGE_SETTLE_MS = 120L
    }
}
