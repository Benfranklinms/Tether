package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.proto.ShelfSync
import dev.benfranklin.tether.core.proto.ShelfTomb
import java.util.Locale

/** What a piece of text is, for a Shelf card. Mirrored in TetherCore `ShelfDetect` (same rules, same tests). */
data class ShelfGuess(val kind: String, val title: String, val body: String, val secret: String? = null)

object ShelfDetect {
    private val url = Regex("""^(https?://\S+|www\.\S+\.\S+)$""", RegexOption.IGNORE_CASE)
    private val bareDomain = Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)*\.(com|org|net|io|in|gle|dev|app|co|edu|gov|me|ly|gl)(/\S*)?$""",
        RegexOption.IGNORE_CASE)
    private val wifiQr = Regex("""^WIFI:(.*);;?$""", RegexOption.IGNORE_CASE)
    private val pin = Regex("""\b\d{6}\b|\b\d{5}(-\d{4})?\b""")
    private val street = Regex("""\b(road|rd|street|st|avenue|ave|lane|ln|nagar|marg|colony|sector|block|floor|building|""" +
        """college|po|p\.o|district|dist|near|opp|cross|main|layout|drive|dr|blvd|kochi|kerala)\b""", RegexOption.IGNORE_CASE)
    private val commands = listOf(
        "adb ", "git ", "npm ", "npx ", "yarn ", "pnpm ", "ssh ", "scp ", "curl ", "wget ", "sudo ", "cd ", "ls ", "pip ", "pip3 ",
        "python ", "python3 ", "docker ", "kubectl ", "brew ", "apt ", "gradle ", "./", "export ", "echo ", "cat ", "chmod ",
        "mkdir ", "rm ", "mv ", "cp ", "java ", "node ", "cargo ", "swift ", "xcrun ", "defaults ", "\$ ",
    )

    fun classify(raw: String): ShelfGuess {
        val text = raw.trim()
        if (text.isEmpty()) return ShelfGuess(ShelfKinds.TEXT, "", "")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        wifi(text, lines)?.let { return it }
        link(lines)?.let { return it }
        if (isSnippet(text, lines)) {
            return if (lines.size > 1 && !looksLikeCode(lines[0])) {
                ShelfGuess(ShelfKinds.SNIPPET, lines[0].take(TITLE), lines.drop(1).joinToString("\n"))
            } else {
                ShelfGuess(ShelfKinds.SNIPPET, lines[0].split(' ').take(TITLE_WORDS).joinToString(" ").take(TITLE), text)
            }
        }
        address(text, lines)?.let { return it }
        return plain(lines)
    }

    private fun wifi(text: String, lines: List<String>): ShelfGuess? {
        wifiQr.find(text)?.let { m ->
            val fields = splitQr(m.groupValues[1])
            val ssid = fields["S"] ?: return null
            return ShelfGuess(ShelfKinds.WIFI, ssid, fields["P"].orEmpty(), fields["P"])
        }
        var ssid: String? = null
        var pass: String? = null
        for (l in lines) {
            val k = l.substringBefore(':', "").trim().lowercase(Locale.ROOT)
            val v = l.substringAfter(':', "").trim()
            if (v.isEmpty()) continue
            if (k in listOf("ssid", "wifi", "wi-fi", "network", "wifi name", "wi-fi name", "network name")) ssid = v
            if (k in listOf("password", "pass", "pwd", "key", "wifi password", "wi-fi password", "psk")) pass = v
        }
        return if (ssid != null && pass != null) ShelfGuess(ShelfKinds.WIFI, ssid, pass, pass) else null
    }

    private fun splitQr(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        val cur = StringBuilder()
        var esc = false
        val parts = ArrayList<String>()
        for (c in s) {
            when {
                esc -> { cur.append(c); esc = false }
                c == '\\' -> esc = true
                c == ';' -> { parts.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        for (p in parts) {
            val i = p.indexOf(':')
            if (i > 0) out[p.substring(0, i).uppercase(Locale.ROOT)] = p.substring(i + 1)
        }
        return out
    }

    private fun link(lines: List<String>): ShelfGuess? {
        val last = lines.last()
        if (lines.size > 2 || !(url.matches(last) || bareDomain.matches(last))) return null
        val title = if (lines.size == 2) lines[0] else hostOf(last)
        return ShelfGuess(ShelfKinds.LINK, title.take(TITLE), last)
    }

    fun hostOf(u: String): String = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
        .substringBefore('?').ifEmpty { u }

    private val assignment = Regex("""^[A-Za-z_][\w.]*\s*[:+-]?=\s*\S""")

    private fun looksLikeCode(l: String): Boolean {
        val lower = l.lowercase(Locale.ROOT)
        return commands.any { lower.startsWith(it) } || l.contains("{") || l.contains("=>") || l.endsWith(";") ||
            l.startsWith("#!") || l.contains("();") || assignment.containsMatchIn(l)
    }

    private fun isSnippet(text: String, lines: List<String>): Boolean {
        if (text.startsWith("```")) return true
        val codeLines = lines.count { looksLikeCode(it) }
        return codeLines >= 1 && codeLines * 2 >= lines.size
    }

    private fun address(text: String, lines: List<String>): ShelfGuess? {
        val hasPin = pin.containsMatchIn(text)
        val commas = text.count { it == ',' }
        val streety = street.containsMatchIn(text)
        val byPin = hasPin && (commas >= 1 || lines.size >= 2)
        val byStreet = streety && commas >= 2
        if (!byPin && !byStreet) return null
        if (text.length > ADDRESS_MAX) return null
        return if (lines.size >= 2) {
            ShelfGuess(ShelfKinds.ADDRESS, lines[0].trimEnd(',').take(TITLE), lines.drop(1).joinToString(", ") { it.trimEnd(',') })
        } else {
            val first = text.substringBefore(',').trim()
            ShelfGuess(ShelfKinds.ADDRESS, first.take(TITLE), text.substringAfter(',').trim())
        }
    }

    private fun plain(lines: List<String>): ShelfGuess {
        if (lines.size == 1) {
            val l = lines[0]
            val i = l.indexOf(':')
            if (i in 1..KEY_MAX && i < l.length - 1) return ShelfGuess(ShelfKinds.TEXT, l.substring(0, i).trim(), l.substring(i + 1).trim())
            return ShelfGuess(ShelfKinds.TEXT, l.take(TITLE), if (l.length > TITLE) l else "")
        }
        return ShelfGuess(ShelfKinds.TEXT, lines[0].take(TITLE), lines.drop(1).joinToString("\n"))
    }

    /** The one primary action on a card. */
    fun action(kind: String): String = when (kind) {
        ShelfKinds.LINK -> "Open"
        ShelfKinds.ADDRESS -> "Map"
        ShelfKinds.FILE -> "Open"
        else -> "Copy"
    }

    /** What Copy puts on the clipboard. */
    fun copyText(item: ShelfItem): String = when (item.kind) {
        ShelfKinds.WIFI -> item.secret ?: item.body
        ShelfKinds.TEXT -> if (item.body.isEmpty()) item.title else item.body
        else -> item.body.ifEmpty { item.title }
    }

    const val TITLE = 80
    private const val TITLE_WORDS = 3
    private const val KEY_MAX = 24
    private const val ADDRESS_MAX = 300
}

/**
 * The Shelf on one device: items, folders and their tombstones, merged by id + timestamp (last
 * writer wins, removals win over copies not newer than them). Pure and deterministic, so both
 * devices converge on the same list after any sequence of offline edits and syncs.
 *
 * Folders (0.5.4): an item's [ShelfItem.folderId] names a live folder or it is loose. An item
 * pointing at a folder that is gone (deleted on the other device while this one was offline)
 * shows as loose, so a folder deletion never hides or loses items.
 */
data class ShelfState(
    val items: List<ShelfItem> = emptyList(),
    val tombs: List<ShelfTomb> = emptyList(),
    val folders: List<ShelfFolder> = emptyList(),
    val folderTombs: List<ShelfTomb> = emptyList(),
) {

    /** Items in display order. */
    val ordered: List<ShelfItem> get() = items.sortedWith(compareBy<ShelfItem> { it.order }.thenBy { it.id })

    /** Folders in chip order. */
    val orderedFolders: List<ShelfFolder> get() = folders.sortedWith(compareBy<ShelfFolder> { it.order }.thenBy { it.id })

    /** The live folder [item] is in, or null when it is loose (or its folder is gone). */
    fun folderOf(item: ShelfItem): String? = item.folderId?.takeIf { id -> folders.any { it.id == id } }

    /** Items of one folder ([folderId] null = loose ones), in display order. */
    fun itemsIn(folderId: String?): List<ShelfItem> = ordered.filter { folderOf(it) == folderId }

    /** How many items each live folder holds. */
    fun counts(): Map<String, Int> = items.mapNotNull { folderOf(it) }.groupingBy { it }.eachCount()

    fun add(item: ShelfItem): ShelfState {
        val top = items.minOfOrNull { it.order } ?: 0.0
        val placed = item.copy(order = top - 1.0)
        return copy(items = items.filter { it.id != item.id } + placed, tombs = tombs.filter { it.id != item.id }).limited()
    }

    fun remove(id: String, now: Long): ShelfState =
        copy(items = items.filter { it.id != id }, tombs = tombs.filter { it.id != id } + ShelfTomb(id, now))

    /** Puts back an item removed by mistake (undo): a fresh timestamp so it beats the tombstone everywhere. */
    fun restore(item: ShelfItem, now: Long): ShelfState =
        copy(items = items.filter { it.id != item.id } + item.copy(updated = now), tombs = tombs.filter { it.id != item.id }).limited()

    /** Moves [id] to position [to] in [ordered]. */
    fun move(id: String, to: Int, now: Long): ShelfState = moveWithin(ordered, id, to, now)

    /**
     * Moves [id] to position [to] within [list] (a folder's or the loose items, as shown), placing
     * it between its new neighbours there; other items keep their order values.
     */
    fun moveWithin(list: List<ShelfItem>, id: String, to: Int, now: Long): ShelfState {
        val l = list.toMutableList()
        val from = l.indexOfFirst { it.id == id }
        if (from < 0) return this
        val item = l.removeAt(from)
        val i = to.coerceIn(0, l.size)
        val before = l.getOrNull(i - 1)?.order
        val after = l.getOrNull(i)?.order
        val order = when {
            before == null && after == null -> item.order
            before == null -> after!! - 1.0
            after == null -> before + 1.0
            else -> (before + after) / 2.0
        }
        return copy(items = items.map { if (it.id == item.id) it.copy(order = order, updated = bump(it, now)) else it })
    }

    fun update(item: ShelfItem): ShelfState = copy(items = items.map { if (it.id == item.id) item else it })

    // ----- folders (0.5.4) -----

    /** A new folder at the end of the chip row; null when [name] is blank or the folder limit is reached. */
    fun createFolder(id: String, name: String, now: Long): ShelfState? {
        val n = ShelfFolders.cleanName(name) ?: return null
        if (folders.size >= FOLDER_LIMIT) return null
        val order = (folders.maxOfOrNull { it.order } ?: 0.0) + 1.0
        return copy(
            folders = folders.filter { it.id != id } + ShelfFolder(id, n, order, now),
            folderTombs = folderTombs.filter { it.id != id },
        )
    }

    /** Null when the folder is gone, [name] is blank or nothing changes. */
    fun renameFolder(id: String, name: String, now: Long): ShelfState? {
        val n = ShelfFolders.cleanName(name) ?: return null
        val f = folders.firstOrNull { it.id == id } ?: return null
        if (f.name == n) return null
        return copy(folders = folders.map { if (it.id == id) it.copy(name = n, updated = maxOf(now, it.updated + 1)) else it })
    }

    /**
     * Deletes a folder: its items become loose (with a fresh timestamp, so the move wins on the
     * other device too) and a tombstone keeps the folder from coming back. Shelf items are never removed.
     */
    fun deleteFolder(id: String, now: Long): ShelfState {
        if (folders.none { it.id == id }) return this
        return copy(
            items = items.map { if (it.folderId == id) it.copy(folderId = null, updated = bump(it, now)) else it },
            folders = folders.filter { it.id != id },
            folderTombs = folderTombs.filter { it.id != id } + ShelfTomb(id, now),
        )
    }

    /** Moves an item into folder [folderId] (null = loose). Null when nothing changes or the folder is gone. */
    fun moveTo(itemId: String, folderId: String?, now: Long): ShelfState? {
        val item = items.firstOrNull { it.id == itemId } ?: return null
        if (folderId != null && folders.none { it.id == folderId }) return null
        if (folderOf(item) == folderId) return null
        val top = itemsIn(folderId).minOfOrNull { it.order }?.minus(1.0) ?: item.order
        return copy(items = items.map { if (it.id == itemId) it.copy(folderId = folderId, order = top, updated = bump(it, now)) else it })
    }

    /** The sync that tells the other device about [changed] items and folders. */
    fun syncOf(itemIds: Collection<String> = emptyList(), folderIds: Collection<String> = emptyList()): ShelfSync =
        ShelfSync(
            items = items.filter { it.id in itemIds },
            folders = folders.filter { it.id in folderIds },
            removedFolders = folderTombs.filter { it.id in folderIds },
        )

    fun merge(sync: ShelfSync): ShelfState {
        val byId = items.associateBy { it.id }.toMutableMap()
        val tomb = tombs.associate { it.id to it.ts }.toMutableMap()
        for (t in sync.removed) tomb[t.id] = maxOf(tomb[t.id] ?: 0L, t.ts)
        for (r in sync.items) {
            val dead = tomb[r.id]
            if (dead != null && dead >= r.updated) continue
            val mine = byId[r.id]
            if (mine == null || r.updated > mine.updated || (r.updated == mine.updated && r.from > mine.from)) byId[r.id] = r
        }
        for ((id, ts) in tomb) {
            val mine = byId[id]
            if (mine != null && mine.updated <= ts) byId.remove(id)
        }
        // A copy newer than its tombstone resurrects it; drop that tombstone.
        val live = byId.keys
        val (f, ft) = mergeFolders(sync)
        return ShelfState(byId.values.toList(), tomb.filterKeys { it !in live }.map { ShelfTomb(it.key, it.value) }, f, ft).limited()
    }

    private fun mergeFolders(sync: ShelfSync): Pair<List<ShelfFolder>, List<ShelfTomb>> {
        val byId = folders.associateBy { it.id }.toMutableMap()
        val tomb = folderTombs.associate { it.id to it.ts }.toMutableMap()
        for (t in sync.removedFolders) tomb[t.id] = maxOf(tomb[t.id] ?: 0L, t.ts)
        for (r in sync.folders) {
            val dead = tomb[r.id]
            if (dead != null && dead >= r.updated) continue
            val mine = byId[r.id]
            if (mine == null || r.updated > mine.updated || (r.updated == mine.updated && r.name > mine.name)) byId[r.id] = r
        }
        for ((id, ts) in tomb) {
            val mine = byId[id]
            if (mine != null && mine.updated <= ts) byId.remove(id)
        }
        val live = byId.keys
        return byId.values.toList() to tomb.filterKeys { it !in live }.map { ShelfTomb(it.key, it.value) }
    }

    fun snapshot(): ShelfSync = ShelfSync(items, tombs, full = true, folders = folders, removedFolders = folderTombs)

    /** Tombstones older than [maxAgeMs] are forgotten. */
    fun pruned(now: Long, maxAgeMs: Long = TOMB_TTL): ShelfState =
        copy(tombs = tombs.filter { now - it.ts < maxAgeMs }, folderTombs = folderTombs.filter { now - it.ts < maxAgeMs })

    /** At most [LIMIT] items: the least recently changed fall off (same rule on both sides, so no tombstones needed). */
    private fun limited(): ShelfState {
        if (items.size <= LIMIT) return this
        val keep = items.sortedWith(compareByDescending<ShelfItem> { it.updated }.thenBy { it.id }).take(LIMIT).map { it.id }.toSet()
        return copy(items = items.filter { it.id in keep })
    }

    private fun bump(item: ShelfItem, now: Long) = maxOf(now, item.updated + 1)

    companion object {
        /** 0.5.4: was 50. */
        const val LIMIT = 500
        const val FOLDER_LIMIT = 100
        const val TOMB_TTL = 30L * 24 * 3600 * 1000
        const val BODY_MAX = 4000

        /** A new item from text the user typed, dropped or shared. */
        fun itemFrom(text: String, id: String, from: String, now: Long, folderId: String? = null): ShelfItem {
            val g = ShelfDetect.classify(text)
            return ShelfItem(
                id = id, kind = g.kind, title = g.title.ifEmpty { "Note" }, body = g.body.take(BODY_MAX), secret = g.secret,
                from = from, created = now, updated = now, order = 0.0, folderId = folderId,
            )
        }

        fun fileItem(file: ShelfFile, id: String, from: String, now: Long, folderId: String? = null): ShelfItem =
            ShelfItem(id = id, kind = ShelfKinds.FILE, title = file.name, body = file.mime, from = from, created = now, updated = now,
                order = 0.0, file = file, folderId = folderId)
    }
}

/** Folder names and what goes on the wire (0.5.4). */
object ShelfFolders {
    const val NAME_MAX = 40

    /** Trimmed, one line, at most [NAME_MAX] characters; null when blank. */
    fun cleanName(raw: String): String? = raw.replace(Regex("\\s+"), " ").trim().take(NAME_MAX).trim().ifEmpty { null }

    /** What a peer without the "shelffolders" cap gets: no folder fields at all (it sees every item as loose). */
    fun forPeer(sync: ShelfSync, folders: Boolean): ShelfSync =
        if (folders) {
            sync
        } else {
            sync.copy(items = sync.items.map { it.copy(folderId = null) }, folders = emptyList(), removedFolders = emptyList())
        }

    /**
     * Splits a (full) sync into messages of at most about [budget] bytes of JSON, so a 500-item
     * Shelf fits in Bluetooth records (128 KiB max). Folders and tombstones ride in the first page;
     * receivers merge every page the same way, so the split is invisible.
     */
    fun pages(sync: ShelfSync, budget: Int = PAGE_BUDGET, size: (ShelfItem) -> Int): List<ShelfSync> {
        val out = ArrayList<ShelfSync>()
        var cur = ArrayList<ShelfItem>()
        var used = 0
        for (item in sync.items) {
            val n = size(item) + 1
            if (cur.isNotEmpty() && used + n > budget) {
                out += ShelfSync(cur, full = sync.full)
                cur = ArrayList()
                used = 0
            }
            cur += item
            used += n
        }
        if (cur.isNotEmpty() || out.isEmpty()) out += ShelfSync(cur, full = sync.full)
        out[0] = out[0].copy(removed = sync.removed, folders = sync.folders, removedFolders = sync.removedFolders)
        return out
    }

    /** JSON bytes per page; tombstones and folders are small, items at most ~4 KB (BODY_MAX). */
    const val PAGE_BUDGET = 48 * 1024
}
