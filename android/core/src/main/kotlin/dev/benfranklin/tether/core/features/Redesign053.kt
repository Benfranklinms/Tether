@file:Suppress("MagicNumber", "TooManyFunctions")

package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// 0.5.3 redesign: the pure rules behind the customizable Home, the premium Shelf rows and the
// Transfers list. The Shelf metadata rules are mirrored in Swift
// (mac/TetherCore/Sources/TetherCore/Redesign053.swift) so both apps label things alike.

// ---------------- Home panels ----------------

/** How much room a Home panel takes: half a row, a full row, or a full row with more inside. */
enum class PanelSize(val id: String) {
    SMALL("small"),
    WIDE("wide"),
    LARGE("large"),
    ;

    companion object {
        fun of(id: String?): PanelSize? = entries.firstOrNull { it.id == id }
    }
}

/** Every panel Home can show, the sizes it comes in and the size it starts at. */
enum class PanelKind(val id: String, val title: String, val sizes: List<PanelSize>, val defaultSize: PanelSize) {
    SEND_CLIPBOARD("send_clipboard", "Send clipboard", listOf(PanelSize.SMALL, PanelSize.WIDE), PanelSize.SMALL),
    SEND_FILE("send_file", "Send file", listOf(PanelSize.SMALL, PanelSize.WIDE), PanelSize.SMALL),
    SHELF("shelf", "Shelf", listOf(PanelSize.WIDE, PanelSize.LARGE), PanelSize.WIDE),
    MAC("mac", "Mac status", listOf(PanelSize.SMALL, PanelSize.WIDE), PanelSize.SMALL),
    CLIPBOARD("clipboard", "Clipboard", listOf(PanelSize.SMALL, PanelSize.WIDE), PanelSize.SMALL),
    NOW_PLAYING("now_playing", "Now playing", listOf(PanelSize.WIDE, PanelSize.LARGE), PanelSize.WIDE),
    TRANSFERS("transfers", "Recent transfers", listOf(PanelSize.WIDE, PanelSize.LARGE), PanelSize.WIDE),
    PHOTOS("photos", "Photos", listOf(PanelSize.WIDE, PanelSize.LARGE), PanelSize.WIDE),
    HOTSPOT("hotspot", "Hotspot", listOf(PanelSize.SMALL, PanelSize.WIDE), PanelSize.SMALL),
    ;

    companion object {
        fun of(id: String?): PanelKind? = entries.firstOrNull { it.id == id }
    }
}

/** What settings.json keeps for one panel. Unknown kinds/sizes from newer builds are dropped on read. */
@Serializable data class PanelSpec(val kind: String, val size: String)

data class Panel(val kind: PanelKind, val size: PanelSize) {
    /** Columns out of two. */
    val span: Int get() = if (size == PanelSize.SMALL) 1 else 2
}

/**
 * The Home layout: an ordered list of panels, each kind at most once. Every change returns a
 * new layout; a change that isn't allowed (a size the panel doesn't come in, a duplicate, an
 * index out of range) returns the layout unchanged. Pure; unit-tested.
 */
data class HomeLayout(val panels: List<Panel>) {
    val kinds: List<PanelKind> get() = panels.map { it.kind }

    operator fun contains(kind: PanelKind): Boolean = panels.any { it.kind == kind }

    fun sizeOf(kind: PanelKind): PanelSize? = panels.firstOrNull { it.kind == kind }?.size

    /** Panels not on Home yet, in picker order. */
    fun available(): List<PanelKind> = PanelKind.entries.filterNot { it in this }

    /** Adds [kind] at the top (where the user is looking, as on iOS); [size] must be one it comes in. */
    fun add(kind: PanelKind, size: PanelSize = kind.defaultSize, at: Int = 0): HomeLayout {
        if (kind in this || size !in kind.sizes) return this
        val i = at.coerceIn(0, panels.size)
        return copy(panels = panels.toMutableList().apply { add(i, Panel(kind, size)) })
    }

    fun remove(kind: PanelKind): HomeLayout =
        if (kind in this) copy(panels = panels.filterNot { it.kind == kind }) else this

    fun resize(kind: PanelKind, size: PanelSize): HomeLayout {
        if (size !in kind.sizes || sizeOf(kind) == null || sizeOf(kind) == size) return this
        return copy(panels = panels.map { if (it.kind == kind) it.copy(size = size) else it })
    }

    /** The resize handle: the next size this panel comes in, wrapping around. */
    fun cycleSize(kind: PanelKind): HomeLayout {
        val now = sizeOf(kind) ?: return this
        val sizes = kind.sizes
        return resize(kind, sizes[(sizes.indexOf(now) + 1) % sizes.size])
    }

    /** Moves the panel at [from] to [to] (drag to reorder). */
    fun move(from: Int, to: Int): HomeLayout {
        if (from !in panels.indices || to !in panels.indices || from == to) return this
        return copy(panels = panels.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun move(kind: PanelKind, to: Int): HomeLayout = move(panels.indexOfFirst { it.kind == kind }, to)

    /**
     * Rows on a two-column grid, in order: two small panels share a row; a small panel followed
     * by a full-width one sits alone (the grid never reorders panels behind the user's back).
     */
    fun rows(): List<List<Panel>> {
        val rows = mutableListOf<List<Panel>>()
        var pending: Panel? = null
        for (p in panels) {
            if (p.span == 1) {
                val half = pending
                if (half == null) {
                    pending = p
                } else {
                    rows += listOf(half, p)
                    pending = null
                }
            } else {
                pending?.let { rows += listOf(it) }
                pending = null
                rows += listOf(p)
            }
        }
        pending?.let { rows += listOf(it) }
        return rows
    }

    fun specs(): List<PanelSpec> = panels.map { PanelSpec(it.kind.id, it.size.id) }

    companion object {
        /** A fresh install: the mockup's Home. Hotspot waits in the picker. */
        val DEFAULT = HomeLayout(
            listOf(
                Panel(PanelKind.SEND_CLIPBOARD, PanelSize.SMALL),
                Panel(PanelKind.SEND_FILE, PanelSize.SMALL),
                Panel(PanelKind.SHELF, PanelSize.WIDE),
                Panel(PanelKind.MAC, PanelSize.SMALL),
                Panel(PanelKind.CLIPBOARD, PanelSize.SMALL),
                Panel(PanelKind.NOW_PLAYING, PanelSize.WIDE),
                Panel(PanelKind.TRANSFERS, PanelSize.WIDE),
                Panel(PanelKind.PHOTOS, PanelSize.WIDE),
            ),
        )

        /**
         * Reads what settings.json kept. Nothing saved → [DEFAULT]. Unknown kinds are skipped, a
         * size the panel doesn't come in falls back to its default, duplicates keep the first.
         * An empty saved list stays empty (the user removed everything on purpose).
         */
        fun from(specs: List<PanelSpec>?): HomeLayout {
            if (specs == null) return DEFAULT
            val seen = mutableSetOf<PanelKind>()
            val panels = specs.mapNotNull { s ->
                val kind = PanelKind.of(s.kind) ?: return@mapNotNull null
                if (!seen.add(kind)) return@mapNotNull null
                val size = PanelSize.of(s.size)?.takeIf { it in kind.sizes } ?: kind.defaultSize
                Panel(kind, size)
            }
            return HomeLayout(panels)
        }
    }
}

// ---------------- Relative times ----------------

object When {
    private val EN = Locale.ENGLISH

    /** Shelf rows: "now", "2m", "1h", then "Yesterday", a weekday ("Fri") within a week, else "Oct 9". */
    fun short(at: Long, now: Long, zone: ZoneId): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        val day = day(at, zone)
        val today = day(now, zone)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            day == today -> "${s / 3600}h"
            else -> dayLabel(day, today)
        }
    }

    /** Transfers: "08:42" today, "Yesterday", a weekday within a week, else "Oct 9". */
    fun clock(at: Long, now: Long, zone: ZoneId): String {
        val day = day(at, zone)
        val today = day(now, zone)
        if (day == today) {
            val t = Instant.ofEpochMilli(at).atZone(zone)
            return "%02d:%02d".format(EN, t.hour, t.minute)
        }
        return dayLabel(day, today)
    }

    private fun dayLabel(day: LocalDate, today: LocalDate): String = when {
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, EN)
        else -> day.month.getDisplayName(TextStyle.SHORT, EN) + " " + day.dayOfMonth
    }

    fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** "Today" or "Earlier". */
    fun section(at: Long, now: Long, zone: ZoneId): String = if (day(at, zone) == day(now, zone)) TODAY else EARLIER

    const val TODAY = "Today"
    const val EARLIER = "Earlier"
}

// ---------------- Shelf rows ----------------

object ShelfMeta {
    /** The short word on a file's thumbnail ("PDF", "DOC", "ZIP"). */
    fun badge(mime: String?, name: String?): String {
        val ext = FileKinds.extension(name)
        return when (ext) {
            "doc", "docx", "dot", "dotx", "odt", "rtf", "pages" -> "DOC"
            "xls", "xlsx", "ods", "numbers", "csv" -> "XLS"
            "ppt", "pptx", "odp", "key" -> "PPT"
            "zip", "rar", "7z", "gz", "tar", "tgz" -> "ZIP"
            "txt", "md" -> "TXT"
            "" -> when {
                mime == "application/pdf" -> "PDF"
                mime?.startsWith("text/") == true -> "TXT"
                else -> "FILE"
            }
            else -> ext.uppercase(Locale.ROOT).take(4)
        }
    }

    /** One calm word for a file's kind ("PDF", "Word", "JPEG", "Archive"). */
    fun kind(mime: String?, name: String?): String {
        val ext = FileKinds.extension(name)
        val long = FileKinds.describe(mime, name)
        return when {
            ext in setOf("zip", "rar", "7z", "gz", "tar", "tgz") || long.endsWith("archive") -> "Archive"
            long.endsWith(" document") && long.startsWith("Word") -> "Word"
            long.startsWith("Excel") -> "Excel"
            long.startsWith("PowerPoint") -> "PowerPoint"
            long.endsWith(" image") -> long.removeSuffix(" image")
            long.endsWith(" video") -> "Video"
            long.endsWith(" audio") -> "Audio"
            long == "Android app" || long == "Disk image" || long == "PDF" -> long
            long.endsWith(" spreadsheet") || long.endsWith(" presentation") || long.endsWith(" document") ->
                long.substringBeforeLast(' ')
            else -> long
        }
    }

    /** True when the row shows a picture (the file is an image) rather than a document icon. */
    fun hasPicture(item: ShelfItem): Boolean = item.file?.let { FileKinds.isImage(it.mime, it.name) } == true

    /** "Mac" or "Phone": where the item came from. */
    fun source(item: ShelfItem): String = if (item.from == "mac") "Mac" else "Phone"

    /** "forms.gle" from a link. */
    fun host(url: String): String {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
        return s.removePrefix("www.").ifEmpty { url.trim() }
    }

    /**
     * Title and the calm second line of a Shelf row, as in the mockup:
     * files "PDF · 454 KB", links their host, Wi-Fi "Wi-Fi · ••••••••", snippets "Snippet · 2 lines",
     * addresses and notes their next line. The title never repeats (ShelfCardText.split).
     */
    fun lines(item: ShelfItem, sizeLabel: (Long) -> String, missing: Boolean = false): Pair<String, String> = when (item.kind) {
        ShelfKinds.FILE -> {
            val f = item.file
            item.title to if (f == null) "File" else {
                listOfNotNull(kind(f.mime, f.name), sizeLabel(f.size), "Not downloaded".takeIf { missing }).joinToString(" · ")
            }
        }
        ShelfKinds.WIFI -> item.title to (if (item.secret.isNullOrEmpty()) "Wi-Fi · Open" else "Wi-Fi · " + "•".repeat(SECRET_DOTS))
        ShelfKinds.LINK -> {
            val parts = ShelfCardText.split(item.title, item.body)
            val url = item.body.lines().firstOrNull { it.contains("://") || it.startsWith("www.") }?.trim() ?: item.body.trim()
            parts.title to host(url)
        }
        ShelfKinds.SNIPPET -> {
            val parts = ShelfCardText.split(item.title, item.body)
            val n = item.body.lines().count { it.isNotBlank() }.coerceAtLeast(1)
            parts.title to "Snippet · $n " + if (n == 1) "line" else "lines"
        }
        else -> {
            val parts = ShelfCardText.split(item.title, item.body)
            parts.title to parts.preview.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        }
    }

    /**
     * Where the "Today" / "Earlier" headers go: index in [items] → header. Headers only make sense
     * while the list is newest-first by section; once the user drags things into their own order
     * (an Earlier item above a Today one), no headers are shown.
     */
    fun sections(items: List<ShelfItem>, now: Long, zone: ZoneId): Map<Int, String> {
        if (items.isEmpty()) return emptyMap()
        val names = items.map { When.section(it.created, now, zone) }
        val firstEarlier = names.indexOf(When.EARLIER)
        if (firstEarlier >= 0 && names.drop(firstEarlier).any { it == When.TODAY }) return emptyMap()
        return buildMap {
            if (names[0] == When.TODAY) put(0, When.TODAY)
            if (firstEarlier >= 0) put(firstEarlier, When.EARLIER)
        }
    }

    const val SECRET_DOTS = 9
}

// ---------------- Transfers rows ----------------

object TransferMeta {
    /** Second line: size, "2.2 of 3.4 MB" while moving, or what went wrong. */
    fun subtitle(t: TransferRecord, sizeLabel: (Long) -> String): String = when (t.status) {
        TransferStatus.ACTIVE -> {
            val done = sizeLabel(t.transferred)
            val total = sizeLabel(t.size)
            val sameUnit = done.substringAfter(' ') == total.substringAfter(' ')
            (if (sameUnit) done.substringBefore(' ') else done) + " of " + total
        }
        TransferStatus.QUEUED -> "Queued · ${sizeLabel(t.size)}"
        TransferStatus.OFFERED -> "Starting · ${sizeLabel(t.size)}"
        TransferStatus.VERIFYING -> "Verifying · ${sizeLabel(t.size)}"
        TransferStatus.WAITING -> t.error ?: "Waiting to resume"
        TransferStatus.DONE -> sizeLabel(t.size)
        TransferStatus.FAILED -> t.error ?: "Failed"
        TransferStatus.CANCELLED -> "Cancelled"
    }

    /** "64%" while running; null once finished. */
    fun percent(t: TransferRecord): String? =
        if (t.status.isFinal || t.status == TransferStatus.WAITING) null else "${(t.progress * 100).toInt().coerceIn(0, 100)}%"

    /** Trailing time: "08:42" today, "Yesterday", "Fri", "Oct 9". */
    fun time(t: TransferRecord, now: Long, zone: ZoneId): String = When.clock(maxOf(t.createdAt, t.updatedAt), now, zone)

    /** "Screenshot_2026-10-11-08-…0-62.png": keep the start and the end (with the extension) of a long name. */
    fun middle(name: String, max: Int = 34): String {
        if (name.length <= max) return name
        val keepEnd = (max - 1) / 3
        val keepStart = max - 1 - keepEnd
        return name.take(keepStart) + "…" + name.takeLast(keepEnd)
    }
}

// ---------------- Clipboard panel ----------------

/** The last few texts that crossed the link (newest first, no repeats); kept in memory only. */
class ClipRecents(private val max: Int = MAX) {
    data class Entry(val text: String, val at: Long, val fromMac: Boolean)

    private val list = ArrayDeque<Entry>()

    @Synchronized
    fun add(text: String, at: Long, fromMac: Boolean): List<Entry> {
        val clean = text.trim()
        if (clean.isEmpty()) return snapshot()
        list.removeAll { it.text.trim() == clean }
        list.addFirst(Entry(text, at, fromMac))
        while (list.size > max) list.removeLast()
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<Entry> = list.toList()

    companion object {
        const val MAX = 8
        /** One line for the panel: whitespace folded, capped. */
        fun preview(text: String, cap: Int = 80): String {
            val one = text.trim().replace(Regex("\\s+"), " ")
            return if (one.length > cap) one.take(cap - 1) + "…" else one
        }
    }
}

// ---------------- Mac status panel ----------------

object MacStatusPolicy {
    /** While the Now playing / Mac panel is on screen, ask the Mac this often. */
    const val POLL_MS = 5_000L
    /** A battery reading older than this is shown as unknown. */
    const val STALE_MS = 10 * 60_000L

    /** Position now, extrapolated from the last report while playing. */
    fun position(positionMs: Long, durationMs: Long, playing: Boolean, reportedAt: Long, now: Long): Long {
        val p = if (playing) positionMs + (now - reportedAt).coerceAtLeast(0) else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    /** "1:42" / "1:02:07". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, sec) else "%d:%02d".format(Locale.ROOT, m, sec)
    }
}
