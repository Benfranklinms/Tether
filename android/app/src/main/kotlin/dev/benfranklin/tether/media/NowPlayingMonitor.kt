package dev.benfranklin.tether.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.benfranklin.tether.core.features.NowPlayingThrottle
import dev.benfranklin.tether.core.proto.MediaCmd
import dev.benfranklin.tether.core.proto.MediaCmds
import dev.benfranklin.tether.core.proto.NowPlaying
import dev.benfranklin.tether.core.proto.NowPlayingArt
import dev.benfranklin.tether.core.util.Hex
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.notify.MirrorListenerService
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * What's playing on the phone, read from the active MediaSession (Spotify, YouTube Music,
 * podcasts...) through the notification-listener grant, and remote control of it. State goes
 * out through [onState] (throttled; the Mac extrapolates the position), artwork once per
 * track through [onArt]. Main thread.
 */
class NowPlayingMonitor(
    private val context: Context,
    private val onState: (NowPlaying) -> Unit,
    private val onArt: (NowPlayingArt) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val throttle = NowPlayingThrottle()
    private var running = false
    private var controller: MediaController? = null
    private val sentArt = HashSet<String>()
    private var lastActive = false

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push(force = true)
        override fun onPlaybackStateChanged(state: PlaybackState?) = push(force = false)
        override fun onSessionDestroyed() = refresh()
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = push(force = false)
    }

    private val resync = object : Runnable {
        override fun run() {
            if (!running) return
            push(force = false)
            main.postDelayed(this, RESYNC_MS)
        }
    }

    /** Start (needs notification access; silently does nothing without it). */
    fun start() = main.post {
        if (running) return@post
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return@post
        try {
            msm.addOnActiveSessionsChangedListener(sessions, listener(), main)
            running = true
            pick(msm.getActiveSessions(listener()))
            main.postDelayed(resync, RESYNC_MS)
        } catch (e: SecurityException) {
            Log.w("media.sessions_denied", "err" to e.message)
        }
    }

    fun stop() = main.post {
        if (!running) return@post
        running = false
        main.removeCallbacks(resync)
        context.getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessions)
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** New connection: send the current state and artwork again. */
    fun resend() = main.post {
        throttle.reset()
        sentArt.clear()
        lastActive = false
        push(force = true)
    }

    private fun refresh() {
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            pick(msm.getActiveSessions(listener()))
        } catch (e: SecurityException) {
            Log.w("media.sessions_denied", "err" to e.message)
        }
    }

    private fun listener() = ComponentName(context, MirrorListenerService::class.java)

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        push(force = true)
    }

    private fun push(force: Boolean) {
        if (!running) return
        val c = controller
        val md = c?.metadata
        val ps = c?.playbackState
        val now = System.currentTimeMillis()
        if (c == null || md == null) {
            if (lastActive || force) {
                lastActive = false
                throttle.reset()
                onState(NowPlaying(false, ts = now))
            }
            return
        }
        val art = artwork(md)
        val np = snapshot(c, md, ps, art?.first, now)
        lastActive = true
        val key = listOf(
            np.pkg, np.title, np.artist, np.album, np.durationMs, np.playing, np.canSeek, np.canNext, np.canPrev, np.volume, np.art,
        ).joinToString("\u0001")
        if (art != null && art.first !in sentArt) {
            sentArt += art.first
            onArt(NowPlayingArt(art.first, art.second))
        }
        if (throttle.offer(key, now) || force) onState(np)
    }

    private fun snapshot(c: MediaController, md: MediaMetadata, ps: PlaybackState?, art: String?, now: Long): NowPlaying {
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING
        val actions = ps?.actions ?: 0L
        val speed = ps?.playbackSpeed?.takeIf { playing && it > 0f } ?: 1f
        return NowPlaying(
            active = true, pkg = c.packageName, app = appLabel(c.packageName), title = title, artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0), positionMs = position(ps),
            playing = playing, speed = speed,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            volume = volume(c), art = art, ts = now,
        )
    }

    private fun position(ps: PlaybackState?): Long {
        if (ps == null || ps.position < 0) return 0
        if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
        val elapsed = android.os.SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
        return (ps.position + elapsed * ps.playbackSpeed).toLong().coerceAtLeast(0)
    }

    private fun volume(c: MediaController): Float? {
        val info = c.playbackInfo
        if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            return if (info.maxVolume > 0) info.currentVolume.toFloat() / info.maxVolume else null
        }
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else null
    }

    /** (hash, JPEG) of the track artwork, scaled to ≤ [ART_PX] and ≤ [ART_MAX_BYTES]; cached per bitmap. */
    private fun artwork(md: MediaMetadata): Pair<String, ByteArray>? {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return null
        val id = System.identityHashCode(bmp).toString() + "x" + bmp.generationId
        if (id == lastArtSource) return lastArt
        lastArtSource = id
        lastArt = encode(bmp)
        return lastArt
    }

    private var lastArtSource: String? = null
    private var lastArt: Pair<String, ByteArray>? = null

    private fun encode(src: Bitmap): Pair<String, ByteArray>? = try {
        val scale = ART_PX.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) {
            val w = (src.width * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, (src.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            src
        }
        var q = JPEG_Q
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            bytes = out.toByteArray()
            q -= JPEG_STEP
        } while (bytes.size > ART_MAX_BYTES && q > JPEG_MIN)
        if (bytes.size > ART_MAX_BYTES) {
            null
        } else {
            Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_LEN) to bytes
        }
    } catch (e: IllegalArgumentException) {
        Log.w("media.art_failed", "err" to e.message)
        null
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    /** Runs a Mac command on the current session. Returns null on success or a reason. */
    fun command(cmd: MediaCmd): String? {
        val c = controller ?: return "nothing_playing"
        val t = c.transportControls
        when (cmd.cmd) {
            MediaCmds.PLAY -> t.play()
            MediaCmds.PAUSE -> t.pause()
            MediaCmds.TOGGLE -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            MediaCmds.NEXT -> t.skipToNext()
            MediaCmds.PREV -> t.skipToPrevious()
            MediaCmds.SEEK -> t.seekTo((cmd.pos ?: return "bad_position").coerceAtLeast(0))
            MediaCmds.VOLUME -> setVolume(c, (cmd.vol ?: return "bad_volume").coerceIn(0f, 1f))
            else -> return "unsupported"
        }
        main.postDelayed({ push(force = true) }, AFTER_CMD_MS)
        return null
    }

    private fun setVolume(c: MediaController, v: Float) {
        val info = c.playbackInfo
        if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            c.setVolumeTo((v * info.maxVolume).roundToInt(), 0)
            return
        }
        val am = context.getSystemService(AudioManager::class.java) ?: return
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0)
        } catch (e: SecurityException) {
            Log.w("media.volume_denied", "err" to e.message)
        }
    }

    private companion object {
        const val RESYNC_MS = 15_000L
        const val AFTER_CMD_MS = 250L
        const val ART_PX = 320
        const val ART_MAX_BYTES = 96 * 1024
        const val JPEG_Q = 85
        const val JPEG_STEP = 15
        const val JPEG_MIN = 30
        const val HASH_LEN = 16
    }
}
