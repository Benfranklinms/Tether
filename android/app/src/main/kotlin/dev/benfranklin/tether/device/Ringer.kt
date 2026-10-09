package dev.benfranklin.tether.device

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.benfranklin.tether.R
import dev.benfranklin.tether.core.features.RingTimer
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime
import dev.benfranklin.tether.service.Notifications
import dev.benfranklin.tether.ui.TetherMark
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.TetherTheme

/**
 * "Find my phone": plays the alarm sound at full alarm volume on the alarm stream
 * (USAGE_ALARM), which rings through silent and vibrate mode and, on most phones, through Do
 * Not Disturb (alarms are allowed in DND by default). Shows a full-screen / heads-up
 * notification with Stop, vibrates, and stops by itself after the timeout. The previous alarm
 * volume is restored afterwards.
 */
class Ringer(private val context: Context, private val onState: (ringing: Boolean, reason: String?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val timer = RingTimer()
    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null
    private val autoStop = Runnable { stopNow("timeout") }

    val ringing: Boolean get() = timer.ringing

    fun start(timeoutS: Int) {
        main.post { startNow(timeoutS) }
    }

    private fun startNow(timeoutS: Int) {
        val limit = timer.start(System.currentTimeMillis(), timeoutS)
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, limit)
        if (player == null) {
            if (!play()) {
                timer.stop()
                main.removeCallbacks(autoStop)
                onState(false, "no_sound")
                return
            }
            vibrate()
        }
        showNotification()
        Log.i("ring.start", "limit_s" to limit / MS)
        onState(true, null)
    }

    fun stop(reason: String) {
        main.post { stopNow(reason) }
    }

    private fun stopNow(reason: String) {
        if (!timer.ringing && player == null) return
        timer.stop()
        main.removeCallbacks(autoStop)
        player?.runCatching { stop() }
        player?.release()
        player = null
        restoreVolume()
        vibrator()?.cancel()
        NotificationManagerCompat.from(context).cancel(Notifications.ID_RING)
        context.sendBroadcast(Intent(RingActivity.ACTION_FINISH).setPackage(context.packageName))
        Log.i("ring.stop", "reason" to reason)
        onState(false, reason)
    }

    private fun play(): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        if (am != null) {
            try {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: SecurityException) {
                Log.w("ring.volume_denied", "reason" to e.message)
            }
        }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(context, uri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
            true
        } catch (e: java.io.IOException) {
            Log.e("ring.play_failed", e)
            false
        } catch (e: IllegalStateException) {
            Log.e("ring.play_failed", e)
            false
        }
    }

    private fun restoreVolume() {
        val v = savedVolume ?: return
        savedVolume = null
        runCatching { context.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private fun vibrate() {
        val v = vibrator() ?: return
        val pattern = longArrayOf(0, VIB_ON, VIB_OFF)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
    }

    private fun showNotification() {
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, RingStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val full = PendingIntent.getActivity(
            context, 0, Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notifications.CH_FIND)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(context.getString(R.string.ring_title))
            .setContentText(context.getString(R.string.ring_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, context.getString(R.string.ring_stop), stop)
            .setDeleteIntent(stop)
            .build()
        Notifications.postRaw(context, Notifications.ID_RING, n)
    }

    companion object {
        private const val MS = 1000L
        private const val VIB_ON = 800L
        private const val VIB_OFF = 600L

        /** Android 14+ may withhold full-screen alerts from sideloaded apps; heads-up still works. */
        fun canUseFullScreen(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            c.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
    }
}

class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.runtime.stopRingLocally()
    }
}

/** Full-screen "Your Mac is ringing this phone" with one big Stop, shown over the lock screen. */
class RingActivity : ComponentActivity() {
    private val finisher = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, finisher, android.content.IntentFilter(ACTION_FINISH), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (!runtime.ringing) {
            finish()
            return
        }
        setContent {
            TetherTheme {
                Column(
                    Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    TetherMark(96.dp, linked = true, pulse = true)
                    Spacer(Modifier.height(32.dp))
                    Text(getString(R.string.ring_title), style = MaterialTheme.typography.headlineMedium, color = Mono.Text)
                    Spacer(Modifier.height(8.dp))
                    Text(getString(R.string.ring_text), style = MaterialTheme.typography.bodyLarge, color = Mono.Muted)
                    Spacer(Modifier.height(48.dp))
                    Button(
                        onClick = {
                            runtime.stopRingLocally()
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Mono.Text, contentColor = Mono.Bg),
                    ) { Text(getString(R.string.ring_stop), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(finisher) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_FINISH = "dev.benfranklin.tether.RING_FINISH"
    }
}
