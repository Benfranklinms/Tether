package dev.benfranklin.tether.v7

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.features.ClipRecents
import dev.benfranklin.tether.core.proto.Caps7
import dev.benfranklin.tether.core.proto.MacMedia
import dev.benfranklin.tether.core.proto.MacMediaCmd
import dev.benfranklin.tether.core.proto.MacMediaCmds
import dev.benfranklin.tether.core.proto.MacStatus
import dev.benfranklin.tether.core.proto.MacStatusReq
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.media.MediaWatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Mac's battery and what is playing on it, as last reported (0.5.3, protocol revision 7). */
data class MacStatusUi(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val media: MacMedia? = null,
    /** Phone clock (ms) when this arrived, for extrapolating the playback position. */
    val at: Long = 0,
)

/**
 * What the 0.5.3 Home panels read that the rest of the runtime doesn't keep: the Mac's status
 * (MAC_STATUS), recent clipboard texts (memory only) and the latest photos for the Photos panel.
 */
class HomeFeeds(
    private val send: (Message) -> Boolean,
    private val supports: (String) -> Boolean,
) {
    private val _mac = MutableStateFlow<MacStatusUi?>(null)
    val mac: StateFlow<MacStatusUi?> = _mac.asStateFlow()

    private val recents = ClipRecents()
    private val _clips = MutableStateFlow<List<ClipRecents.Entry>>(emptyList())
    val clips: StateFlow<List<ClipRecents.Entry>> = _clips.asStateFlow()

    /** True when the connected Mac reports its status (0.5.3+). */
    fun macSupported(): Boolean = supports(Caps7.MAC_STATUS)

    fun handle(m: Message): Boolean {
        if (m !is MacStatus) return false
        val prev = _mac.value
        // A battery-only push keeps the last known media (the Mac only includes media when asked).
        _mac.value = MacStatusUi(m.battery, m.charging, m.media ?: prev?.media, System.currentTimeMillis())
        return true
    }

    /** Ask the Mac for its status; [media] while the Now playing panel is on screen. */
    fun request(media: Boolean) {
        if (macSupported()) send(MacStatusReq(media))
    }

    fun mediaCommand(cmd: String) {
        if (cmd !in MacMediaCmds.ALL || !macSupported()) return
        val sent = send(MacMediaCmd(cmd))
        Log.i("mac.media_cmd", "cmd" to cmd, "ok" to sent)
        // Optimistic play/pause so the button answers at once; the Mac's reply corrects it.
        if (sent && cmd == MacMediaCmds.TOGGLE) {
            _mac.value?.let { s ->
                val m = s.media ?: return@let
                val now = System.currentTimeMillis()
                val pos = if (m.playing) m.positionMs + (now - s.at) else m.positionMs
                _mac.value = s.copy(media = m.copy(playing = !m.playing, positionMs = pos), at = now)
            }
        }
    }

    fun clip(text: String, fromMac: Boolean) {
        _clips.value = recents.add(text, System.currentTimeMillis(), fromMac)
    }

    fun linkDown() {
        // Media from a Mac we can no longer reach would be stale; the battery stays as last seen.
        _mac.value = _mac.value?.copy(media = null)
    }
}

/** One recent photo for the Photos panel. */
data class RecentPhoto(val id: Long, val uri: Uri)

/** Latest camera-roll images (MediaStore), read only when the user granted photo access. */
object RecentPhotos {
    fun allowed(c: Context): Boolean {
        if (granted(c, MediaWatcher.permission())) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(c, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun latest(c: Context, limit: Int): List<RecentPhoto> {
        if (!allowed(c)) return emptyList()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = mutableListOf<RecentPhoto>()
        try {
            c.contentResolver.query(
                base, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cur ->
                val idCol = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cur.moveToNext() && out.size < limit) {
                    val id = cur.getLong(idCol)
                    out += RecentPhoto(id, ContentUris.withAppendedId(base, id))
                }
            }
        } catch (e: SecurityException) {
            Log.w("photos.recent_denied", "err" to e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            Log.w("photos.recent_failed", "err" to e.javaClass.simpleName)
        }
        return out
    }

    fun thumbnail(c: Context, p: RecentPhoto, px: Int): ImageBitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            c.contentResolver.loadThumbnail(p.uri, Size(px, px), null).asImageBitmap()
        } else {
            decodeSampled(c, p.uri, px)
        }
    } catch (e: java.io.IOException) {
        Log.w("photos.thumb_failed", "err" to e.javaClass.simpleName)
        null
    } catch (e: SecurityException) {
        Log.w("photos.thumb_denied", "err" to e.javaClass.simpleName)
        null
    }

    private fun decodeSampled(c: Context, uri: Uri, px: Int): ImageBitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
    }
}
