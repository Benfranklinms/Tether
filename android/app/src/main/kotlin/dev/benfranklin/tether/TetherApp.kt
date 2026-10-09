package dev.benfranklin.tether

import android.app.Application
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
        if (runtime.ui.value.peer != null) TetherService.start(this)
    }

    private companion object {
        const val CRASH_FLUSH_MS = 150L
    }
}

val android.content.Context.runtime: TetherRuntime get() = (applicationContext as TetherApp).runtime
