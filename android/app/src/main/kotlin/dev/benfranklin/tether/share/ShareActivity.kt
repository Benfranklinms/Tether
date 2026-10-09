package dev.benfranklin.tether.share

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri

import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.runtime
import dev.benfranklin.tether.ui.MainActivity
import java.util.Locale

/** Share-sheet target: files go to the Mac's Downloads, text goes to its clipboard. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rt = runtime
        if (rt.ui.value.peer == null) {
            Toast.makeText(this, "Pair Tether with your Mac first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            return finish()
        }
        val uris = streams(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        when {
            uris.isNotEmpty() -> confirmAndSend(uris)
            !text.isNullOrEmpty() -> {
                rt.sendClip(text, fromUser = false)
                Toast.makeText(this, "Sent to your Mac's clipboard", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> {
                Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun streams(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    private fun confirmAndSend(uris: List<Uri>) {
        val total = uris.sumOf { sizeOf(it) }
        val wifi = runtime.wifiReady
        val warning = sizeWarning(total, wifi)
        if (warning == null) return send(uris)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(warning.first)
            .setMessage(warning.second)
            .setPositiveButton("Send") { _, _ -> send(uris) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun send(uris: List<Uri>) {
        runtime.sendFiles(uris)
        Toast.makeText(this, if (uris.size == 1) "Sending to your Mac" else "Sending ${uris.size} files", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun sizeOf(u: Uri): Long = try {
        contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (_: SecurityException) {
        0L
    }

    companion object {
        private const val BLE_BYTES_PER_SEC = 20_000.0
        private const val SECONDS_PER_MINUTE = 60.0
        private const val BYTES_PER_MB = 1_048_576.0

        /** Title and message for a size warning, or null when no warning is needed. */
        fun sizeWarning(total: Long, wifi: Boolean): Pair<String, String>? {
            if (wifi || total <= Protocol.FILE_WARN_BYTES) return null
            val mb = String.format(Locale.ROOT, "%.0f MB", total / BYTES_PER_MB)
            if (total > Protocol.FILE_MAX_BYTES) {
                return "Waits for Wi-Fi" to "$mb is too large for Bluetooth. It will send when your phone and Mac are on the same Wi-Fi."
            }
            val minutes = (total / BLE_BYTES_PER_SEC / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)
            return "Large file" to "$mb over Bluetooth takes about $minutes min. Join the same Wi-Fi to send it much faster."
        }
    }
}

