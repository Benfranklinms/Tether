package dev.benfranklin.tether.clip

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.benfranklin.tether.core.LinkState
import dev.benfranklin.tether.core.features.ClipContent
import dev.benfranklin.tether.core.features.ClipItemInfo
import dev.benfranklin.tether.runtime

/**
 * Android 10+ only lets the focused app (or the keyboard) read the clipboard. This
 * invisible activity takes focus for a moment, reads the clip, hands it to the runtime and
 * closes. Used by the Quick Settings tile and the in-app button.
 */
class ClipSendActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val content = readClipContent(this)
        val rt = runtime
        val msg = when {
            content is ClipContent.Empty -> "Clipboard is empty"
            rt.ui.value.link !is LinkState.Connected -> "Not connected to your Mac"
            content is ClipContent.Image -> {
                rt.clipboardImageSeen(android.net.Uri.parse(content.uri), force = true)
                "Sending the image to your Mac"
            }
            else -> {
                rt.sendClip((content as ClipContent.Text).text, fromUser = false)
                "Sent to your Mac"
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        fun intent(c: Context) = Intent(c, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * What the clipboard holds, image first (0.5.1). Gallery apps put the image URI *and* its file
 * name as text in one item; reading text first sent the name instead of the picture.
 * Only works while Tether has focus (Android 10+).
 */
fun readClipContent(c: Context): ClipContent {
    val cm = c.getSystemService(ClipboardManager::class.java) ?: return ClipContent.Empty
    val clip: ClipData = try {
        cm.primaryClip
    } catch (_: SecurityException) {
        null
    } ?: return ClipContent.Empty
    return classifyClip(c, clip)
}

fun classifyClip(c: Context, clip: ClipData): ClipContent {
    val desc = clip.description
    val mimes = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }
    val items = (0 until clip.itemCount).map { i ->
        val item = clip.getItemAt(i)
        val uri = item.uri
        val mime = uri?.let { u -> runCatching { c.contentResolver.getType(u) }.getOrNull() }
        ClipItemInfo(item.text?.toString(), uri?.toString(), mime)
    }
    val content = ClipContent.classify(mimes, items, c.packageName + ".files")
    if (content !is ClipContent.Empty || items.isEmpty()) return content
    // A URI-only text clip (rare): fall back to the platform's text coercion, except for files.
    val first = clip.getItemAt(0)
    if (first.uri != null) return ClipContent.Empty
    return first.coerceToText(c)?.toString()?.takeIf { it.isNotEmpty() }?.let { ClipContent.Text(it) } ?: ClipContent.Empty
}

fun readClipboardText(c: Context): String? {
    val cm = c.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = try {
        cm.primaryClip
    } catch (_: SecurityException) {
        null
    } ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(c)?.toString()
}

/** Quick Settings tile: "Send clipboard" to the Mac. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val connected = applicationContext.runtime.ui.value.link is LinkState.Connected
        tile.state = if (connected) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (connected) "To your Mac" else "Not connected"
        tile.updateTile()
    }

    override fun onClick() {
        val i = ClipSendActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
