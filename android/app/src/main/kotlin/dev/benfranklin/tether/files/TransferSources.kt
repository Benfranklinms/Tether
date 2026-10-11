package dev.benfranklin.tether.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.log.Log
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Where each sent file came from (0.5.3), so tapping a sent transfer opens the original. The
 * staged copy is deleted once sent; the source URI stays readable when the picker granted a
 * persistable permission (Send file), and usually not for one-off shares (then we say so).
 * Received files open from where they were saved.
 */
class TransferSources(private val context: Context) {
    private val file = File(context.filesDir, "transfer_sources.json")
    private val ser = MapSerializer(String.serializer(), String.serializer())
    private val map: MutableMap<String, String> = runCatching {
        MessageCodec.json.decodeFromString(ser, file.readText()).toMutableMap()
    }.getOrDefault(HashMap())

    @Synchronized fun remember(transferId: String, source: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // A share-sheet grant can't be kept; opening may fail later and says so.
        }
        map[transferId] = source.toString()
        save()
    }

    /** Forget sources of transfers that are no longer listed. */
    @Synchronized fun retain(ids: Set<String>) {
        if (map.keys.retainAll(ids)) save()
    }

    private fun save() {
        runCatching { file.writeText(MessageCodec.json.encodeToString(ser, map)) }
    }

    /** Opens the transfer's file; null when it opened, else a short reason for the user. */
    fun open(t: TransferRecord): String? {
        val raw = if (t.direction == Direction.IN) t.ref else synchronized(this) { map[t.id] }
        if (raw.isNullOrBlank()) return if (t.direction == Direction.OUT) "The original of this sent file isn't known" else "File not found"
        val uri = Uri.parse(raw)
        if (uri.scheme == "file" && uri.path?.let { File(it).exists() } != true) return "This file was moved or deleted"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, t.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (uri.scheme == "content") context.contentResolver.openInputStream(uri)?.close()
            context.startActivity(view)
            null
        } catch (_: ActivityNotFoundException) {
            "No app can open ${t.name}"
        } catch (_: SecurityException) {
            Log.w("transfer.open_denied", "dir" to t.direction.name)
            "Tether can't open this file any more"
        } catch (_: java.io.IOException) {
            "This file was moved or deleted"
        }
    }
}
