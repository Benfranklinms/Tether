package dev.benfranklin.tether.screen

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.R
import dev.benfranklin.tether.core.proto.ScreenStates
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime
import dev.benfranklin.tether.service.Notifications
import java.io.IOException

/** Display size and rotation right now, in the current orientation. */
data class DisplayShape(val width: Int, val height: Int, val rotation: Int, val dpi: Int)

fun currentDisplay(c: Context): DisplayShape {
    val dm = c.getSystemService(DisplayManager::class.java)
    val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
    val rotation = when (display?.rotation) {
        Surface.ROTATION_90 -> DEG_90
        Surface.ROTATION_180 -> DEG_180
        Surface.ROTATION_270 -> DEG_270
        else -> 0
    }
    val metrics = DisplayMetrics()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        return DisplayShape(b.width(), b.height(), rotation, metrics.densityDpi.takeIf { it > 0 } ?: DEFAULT_DPI)
    }
    @Suppress("DEPRECATION")
    display?.getRealMetrics(metrics)
    return DisplayShape(metrics.widthPixels, metrics.heightPixels, rotation, metrics.densityDpi.takeIf { it > 0 } ?: DEFAULT_DPI)
}

/** Encoded size for a display: longest edge at most [maxSize], both sides multiples of 16. Pure; unit-tested. */
fun encodedSize(width: Int, height: Int, maxSize: Int): Pair<Int, Int> {
    val longest = maxOf(width, height).coerceAtLeast(1)
    val scale = minOf(1.0, maxSize.toDouble() / longest)
    fun align(v: Double) = maxOf(ALIGN, (v.toInt() / ALIGN) * ALIGN)
    return align(width * scale) to align(height * scale)
}

private const val DEG_90 = 90
private const val DEG_180 = 180
private const val DEG_270 = 270
private const val DEFAULT_DPI = 320
private const val ALIGN = 16

/**
 * Asks Android for screen-capture consent (required for every session) and hands the result
 * to [ScreenCaptureService]. Launched from the "Your Mac wants to see this screen"
 * notification, or directly when Tether is in the foreground.
 */
class ScreenConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ScreenCaptureService.start(this, r.resultCode, r.data!!)
        } else {
            runtime.screenConsentResult(granted = false)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.cancelId(this, Notifications.ID_SCREEN_REQUEST)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole screen only: a single-app capture would leave the Mac's input misaligned.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            launcher.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e("screen.consent_unavailable", e)
            runtime.screenConsentResult(granted = false)
            finish()
        }
    }

    companion object {
        fun intent(c: Context) = Intent(c, ScreenConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        fun pending(c: Context): PendingIntent = PendingIntent.getActivity(c, REQUEST_CODE, intent(c), PendingIntent.FLAG_IMMUTABLE)

        private const val REQUEST_CODE = 7
    }
}

/**
 * Foreground service (type mediaProjection) that owns one screen session: MediaProjection ->
 * VirtualDisplay -> hardware H.264 encoder -> runtime (which frames it over the Noise Wi-Fi
 * session). Follows rotation by resizing the virtual display and restarting the encoder at
 * the new size (Android 14 allows only one virtual display per consent).
 */
class ScreenCaptureService : Service() {
    private val thread = HandlerThread("tether-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var shape: DisplayShape? = null
    private var stopping = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) handler.post { reconfigureIfRotated() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("phone")
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(EXTRA_DATA)
            }
        }
        if (data == null || code != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SCREEN, notification(controlAvailable = ControlService.available),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
            )
        } catch (e: IllegalStateException) {
            Log.e("screen.fgs_refused", e)
            runtime.screenEnded(ScreenStates.ERROR, "foreground_refused")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            Log.e("screen.fgs_refused", e)
            runtime.screenEnded(ScreenStates.ERROR, "foreground_refused")
            stopSelf()
            return START_NOT_STICKY
        }
        handler.post { begin(code, data) }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: SecurityException) {
            Log.e("screen.projection_denied", e)
            null
        } ?: return fail("projection")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSession("phone") }
            }
        }, handler)
        val s = currentDisplay(this)
        shape = s
        val enc = startEncoder(s) ?: return fail("encoder")
        display = try {
            p.createVirtualDisplay(
                "Tether", enc.width, enc.height, s.dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, handler,
            )
        } catch (e: SecurityException) {
            Log.e("screen.display_denied", e)
            null
        } ?: return fail("display")
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        active = this
        runtime.screenLive(enc.width, enc.height, s.rotation)
        Log.i("screen.live", "w" to enc.width, "h" to enc.height, "rot" to s.rotation, "bps" to enc.bitrate)
    }

    private fun startEncoder(s: DisplayShape): ScreenEncoder? {
        val req = runtime.screenRequest
        val (w, h) = encodedSize(s.width, s.height, req.maxSize)
        return try {
            val e = ScreenEncoder(w, h, req.fps, runtime.screenBitrate(), handler) { data, key, config, pts ->
                runtime.sendVideo(data, key, config, pts)
            }
            e.start()
            encoder = e
            e
        } catch (e: IOException) {
            Log.e("screen.encoder_failed", e)
            null
        } catch (e: IllegalStateException) {
            Log.e("screen.encoder_failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e("screen.encoder_failed", e)
            null
        }
    }

    private fun reconfigureIfRotated() {
        val old = shape ?: return
        val now = currentDisplay(this)
        if (now.rotation == old.rotation && now.width == old.width) return
        val vd = display ?: return
        shape = now
        encoder?.stop()
        val enc = startEncoder(now) ?: return fail("encoder")
        vd.resize(enc.width, enc.height, now.dpi)
        vd.surface = enc.surface
        encoder = enc
        runtime.screenLive(enc.width, enc.height, now.rotation)
        Log.i("screen.rotated", "rot" to now.rotation, "w" to enc.width, "h" to enc.height)
    }

    /** Called (on any thread) when the Mac asks for a keyframe or the bitrate changes. */
    fun requestKeyframe() = handler.post { encoder?.requestKeyframe() }

    fun setBitrate(bps: Int) = handler.post { encoder?.setBitrate(bps) }

    fun refreshNotification() = handler.post {
        Notifications.postRaw(this, Notifications.ID_SCREEN, notification(ControlService.available))
    }

    private fun fail(what: String) {
        Log.w("screen.failed", "stage" to what)
        stopSession("error:$what")
    }

    fun stopSession(reason: String) = handler.post {
        if (stopping) return@post
        stopping = true
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        encoder?.stop()
        encoder = null
        display?.release()
        display = null
        projection?.stop()
        projection = null
        if (active === this) active = null
        val state = if (reason.startsWith("error")) ScreenStates.ERROR else ScreenStates.STOPPED
        runtime.screenEnded(state, reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i("screen.stopped", "reason" to reason)
    }

    override fun onDestroy() {
        if (!stopping) stopSession("destroyed")
        thread.quitSafely()
        super.onDestroy()
    }

    private fun notification(controlAvailable: Boolean) = NotificationCompat.Builder(this, Notifications.CH_SCREEN)
        .setSmallIcon(R.drawable.ic_stat_tether)
        .setContentTitle(getString(R.string.screen_sharing_title))
        .setContentText(getString(if (controlAvailable) R.string.screen_sharing_control else R.string.screen_sharing_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(
            0, getString(R.string.screen_stop),
            PendingIntent.getService(
                this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "dev.benfranklin.tether.SCREEN_STOP"

        @Volatile var active: ScreenCaptureService? = null
            private set

        fun start(c: Context, code: Int, data: Intent) {
            ContextCompat.startForegroundService(
                c, Intent(c, ScreenCaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }
    }
}

/**
 * Hardware H.264 encoder fed by a Surface. Output access units are passed on in Annex B form;
 * the codec config (SPS/PPS) is sent on its own and also prepended to every keyframe so the
 * Mac can (re)start decoding at any keyframe.
 */
class ScreenEncoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    startBitrate: Int,
    private val handler: Handler,
    private val onFrame: (data: ByteArray, key: Boolean, config: Boolean, ptsUs: Long) -> Unit,
) {
    var bitrate: Int = startBitrate
        private set
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    lateinit var surface: Surface
        private set
    private var config: ByteArray? = null
    @Volatile private var running = false

    fun start() {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Static screens produce no frames from a VirtualDisplay; repeat so the Mac stays current.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (!running) return
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes, 0, info.size)
                        handle(bytes, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w("screen.encoder_output", "reason" to e.message)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("screen.encoder_error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        running = true
        codec.start()
    }

    private fun handle(bytes: ByteArray, info: MediaCodec.BufferInfo) {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (isConfig) {
            config = bytes
            onFrame(bytes, false, true, info.presentationTimeUs)
            return
        }
        val out = if (isKey) (config ?: ByteArray(0)) + bytes else bytes
        onFrame(out, isKey, isKey && config != null, info.presentationTimeUs)
    }

    fun requestKeyframe() {
        if (!running) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(bps: Int) {
        if (!running || bps == bitrate) return
        bitrate = bps
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { codec.signalEndOfInputStream() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }

    private companion object {
        const val KEYFRAME_INTERVAL_S = 2
        const val REPEAT_AFTER_US = 100_000L
    }
}
