package dev.benfranklin.tether.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.ble.BleCentral
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime

/**
 * Foreground service (type connectedDevice) that keeps the process alive while a Mac is
 * paired. All the work happens in [dev.benfranklin.tether.TetherRuntime]; this only owns the
 * process lifetime and the ongoing notification.
 */
class TetherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rt = runtime
        if (!BleCentral.permissionsGranted(this)) {
            Log.w("service.no_permission")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_ONGOING, Notifications.ongoing(this, rt.ui.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: IllegalStateException) {
            // Android 12+ refuses background FGS starts in some states; we'll retry on next open.
            Log.e("service.fgs_refused", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            Log.e("service.fgs_denied", e)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        rt.startLink()
        Log.i("service.started")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runtime.stopLink()
        Log.i("service.stopped")
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, TetherService::class.java))
            } catch (e: IllegalStateException) {
                Log.e("service.start_refused", e)
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, TetherService::class.java))
        }
    }
}

/** Restarts the link after a reboot or an app update, if a Mac is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.runtime.ui.value.peer != null) TetherService.start(context)
    }
}
