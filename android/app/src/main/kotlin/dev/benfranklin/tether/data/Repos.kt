package dev.benfranklin.tether.data

import android.content.Context
import dev.benfranklin.tether.core.transfer.TransferRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Transfer history. Status changes are written immediately; progress-only updates are
 * written at most every [PROGRESS_WRITE_MS] so a fast Wi-Fi transfer isn't IO-bound.
 */
class TransferRepository(context: Context) {
    private val file = JsonFile(File(context.filesDir, "transfers.json"), ListSerializer(TransferRecord.serializer()))
    private val state = MutableStateFlow(file.read().orEmpty().sortedByDescending { it.createdAt })
    val transfers: StateFlow<List<TransferRecord>> = state.asStateFlow()
    private var lastWrite = 0L

    val saved: List<TransferRecord> get() = state.value

    @Synchronized
    fun upsert(record: TransferRecord, important: Boolean) {
        val list = state.value.toMutableList()
        val i = list.indexOfFirst { it.id == record.id }
        if (i >= 0) list[i] = record else list.add(0, record)
        state.value = list.take(MAX_KEPT)
        val now = System.currentTimeMillis()
        if (important || now - lastWrite > PROGRESS_WRITE_MS) {
            lastWrite = now
            file.write(state.value)
        }
    }

    @Synchronized
    fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
        file.write(state.value)
    }

    private companion object {
        const val PROGRESS_WRITE_MS = 2_000L
        const val MAX_KEPT = 200
    }
}

@Serializable
data class SeenApp(val label: String, val lastSeen: Long, val system: Boolean = false)

/** Apps that have posted notifications, for the per-app filter list. */
class SeenAppsRepository(context: Context) {
    private val file = JsonFile(File(context.filesDir, "seen_apps.json"), MapSerializer(String.serializer(), SeenApp.serializer()))
    private val state = MutableStateFlow(file.read().orEmpty())
    val apps: StateFlow<Map<String, SeenApp>> = state.asStateFlow()

    @Synchronized
    fun saw(pkg: String, label: String, system: Boolean, now: Long) {
        val prev = state.value[pkg]
        // Persist new apps right away; refresh timestamps at most hourly.
        if (prev != null && prev.label == label && now - prev.lastSeen < HOUR) return
        state.value = state.value + (pkg to SeenApp(label, now, system))
        file.write(state.value)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
