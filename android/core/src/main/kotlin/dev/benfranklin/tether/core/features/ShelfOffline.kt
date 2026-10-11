package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.ShelfItem
import kotlinx.serialization.Serializable

/**
 * Offline copies of Shelf files (0.5.4), local to each device (nothing here travels).
 *
 * Every file up to [ShelfCache.MAX_BYTES] is copied automatically while it fits in the storage
 * cap. Over the cap, the copies used least recently (last open, else when it was added) go
 * first. Never dropped: items the user pinned with "Keep offline" (or whose folder is pinned),
 * and files this device added itself (it may hold the only copy). An item without a copy keeps
 * its metadata and thumbnail and reads "Available when connected".
 */
object ShelfOffline {
    const val MB = 1024L * 1024
    const val GB = 1024L * MB
    private const val HALF_GB = 500L * MB
    /** Settings › Shelf › Offline files. 0 = Off (no automatic copies). */
    val CAP_OPTIONS: List<Long> = listOf(HALF_GB, GB, 2 * GB, 5 * GB, 0L)
    const val PHONE_DEFAULT = GB
    const val MAC_DEFAULT = 2 * GB
    const val AWAY = "Available when connected"

    fun capLabel(cap: Long): String = when {
        cap <= 0 -> "Off"
        cap % GB == 0L -> "${cap / GB} GB"
        else -> "${cap / MB} MB"
    }

    private const val KB = 1024L

    /** "312 MB of 1 GB" (or "312 MB · Off"). */
    fun usageLabel(used: Long, cap: Long): String {
        val u = when {
            used >= GB -> String.format(java.util.Locale.ROOT, "%.1f GB", used.toDouble() / GB).replace(".0 GB", " GB")
            used >= MB -> "${(used + MB / 2) / MB} MB"
            used > 0 -> "${maxOf(1L, (used + KB / 2) / KB)} KB"
            else -> "0 MB"
        }
        return if (cap <= 0) "$u · Off" else "$u of ${capLabel(cap)}"
    }

    /** True when [item] is pinned itself or its (live) folder is. */
    fun pinned(item: ShelfItem, folderId: String?, pins: Pins): Boolean =
        item.id in pins.items || (folderId != null && folderId in pins.folders)

    /**
     * What to keep: the pinned and own copies always, then the most recently used others while
     * they fit in [cap]. Returns which local copies to drop and which missing ones to fetch.
     * Deterministic for the same inputs, so fetching and evicting never ping-pong.
     */
    fun plan(files: List<Entry>, cap: Long): Plan {
        val keep = HashSet<String>()
        var used = 0L
        for (e in files) if (e.pinned || (e.own && e.local)) {
            keep += e.id
            if (e.local || e.size in 0..ShelfCache.MAX_BYTES) used += e.size
        }
        val rest = files.filter { it.id !in keep }.sortedWith(compareByDescending<Entry> { it.lastUsed }.thenBy { it.id })
        for (e in rest) {
            if (cap > 0 && e.size <= ShelfCache.MAX_BYTES && used + e.size <= cap) {
                keep += e.id
                used += e.size
            }
        }
        val evict = files.filter { it.local && it.id !in keep }.map { it.id }
        val fetch = files.filter { !it.local && it.id in keep && it.size in 0..ShelfCache.MAX_BYTES }.map { it.id }
        return Plan(evict, fetch)
    }

    /** One Shelf file as the planner sees it. [lastUsed] = last open, else when the item arrived. */
    data class Entry(val id: String, val size: Long, val local: Boolean, val lastUsed: Long, val pinned: Boolean, val own: Boolean)

    data class Plan(val evict: List<String>, val fetch: List<String>)

    /** "Keep offline" pins and last-open times; stored on this device only. */
    @Serializable
    data class Pins(
        val items: Set<String> = emptySet(),
        val folders: Set<String> = emptySet(),
        val opened: Map<String, Long> = emptyMap(),
    ) {
        fun toggleItem(id: String) = copy(items = if (id in items) items - id else items + id)
        fun toggleFolder(id: String) = copy(folders = if (id in folders) folders - id else folders + id)
        fun opened(id: String, now: Long) = copy(opened = opened + (id to now))
        /** Forget what no longer exists. */
        fun pruned(liveItems: Set<String>, liveFolders: Set<String>) =
            Pins(items intersect liveItems, folders intersect liveFolders, opened.filterKeys { it in liveItems })
    }

    /** The planner's inputs for [items] (FILE items only). */
    fun entries(
        items: List<ShelfItem>, folderOf: (ShelfItem) -> String?, local: Set<String>, pins: Pins, me: String,
    ): List<Entry> = items.mapNotNull { i ->
        val f = i.file ?: return@mapNotNull null
        Entry(i.id, f.size, i.id in local, pins.opened[i.id] ?: i.created, pinned(i, folderOf(i), pins), f.owner == me)
    }
}
