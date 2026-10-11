package dev.benfranklin.tether

import android.app.Application
import dev.benfranklin.tether.log.CrashLog
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.service.Notifications
import dev.benfranklin.tether.service.TetherService

class TetherApp : Application() {
    val runtime: TetherRuntime by lazy { TetherRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        Log.init(this)
        Notifications.createChannels(this)
        Log.i("app.start", "version" to BuildConfig.VERSION_NAME, "sdk" to android.os.Build.VERSION.SDK_INT)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("app.crash", e, "thread" to t.name)
            Thread.sleep(CRASH_FLUSH_MS) // give the log thread a moment
            previous?.uncaughtException(t, e)
        }
        // Writes the stack trace to files/last-crash.txt synchronously, then the handler above runs.
        CrashLog.install(this)
        if (runtime.ui.value.peer != null) {
            TetherService.start(this)
            // A process restart (after a crash, or Android reclaiming memory) resumes the filtered
            // scan for the paired Mac at once, even if Android refuses the foreground service from
            // the background; the service takes over when it can (0.5.4).
            Log.i("ble.resume_after_restart")
            runtime.startLink()
        }
    }

    private companion object {
        const val CRASH_FLUSH_MS = 150L
    }
}

val android.content.Context.runtime: TetherRuntime get() = (applicationContext as TetherApp).runtime
