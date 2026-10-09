package dev.benfranklin.tether.clip

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.benfranklin.tether.core.LinkState
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
        val text = readClipboardText(this)
        val rt = runtime
        val msg = when {
            text.isNullOrEmpty() -> "Clipboard is empty"
            rt.ui.value.link !is LinkState.Connected -> "Not connected to your Mac"
            else -> {
                rt.sendClip(text, fromUser = false)
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
