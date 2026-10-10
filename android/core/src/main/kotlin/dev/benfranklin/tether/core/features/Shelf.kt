package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.ShelfFile
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
 * The Shelf on one device: items + tombstones, merged by id + timestamp (last writer wins,
 * removals win over copies not newer than them). Pure and deterministic, so both devices
 * converge on the same list after any sequence of offline edits and syncs.
 */
data class ShelfState(val items: List<ShelfItem> = emptyList(), val tombs: List<ShelfTomb> = emptyList()) {

    /** Items in display order. */
    val ordered: List<ShelfItem> get() = items.sortedWith(compareBy<ShelfItem> { it.order }.thenBy { it.id })

    fun add(item: ShelfItem): ShelfState {
        val top = items.minOfOrNull { it.order } ?: 0.0
        val placed = item.copy(order = top - 1.0)
        return ShelfState(items.filter { it.id != item.id } + placed, tombs.filter { it.id != item.id }).limited()
    }

    fun remove(id: String, now: Long): ShelfState =
        ShelfState(items.filter { it.id != id }, tombs.filter { it.id != id } + ShelfTomb(id, now))

    /** Puts back an item removed by mistake (undo): a fresh timestamp so it beats the tombstone everywhere. */
    fun restore(item: ShelfItem, now: Long): ShelfState =
        ShelfState(items.filter { it.id != item.id } + item.copy(updated = now), tombs.filter { it.id != item.id }).limited()

    /** Moves [id] to position [to] in [ordered]. */
    fun move(id: String, to: Int, now: Long): ShelfState {
        val list = ordered.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return this
        val item = list.removeAt(from)
        val i = to.coerceIn(0, list.size)
        val before = list.getOrNull(i - 1)?.order
        val after = list.getOrNull(i)?.order
        val order = when {
            before == null && after == null -> 0.0
            before == null -> after!! - 1.0
            after == null -> before + 1.0
            else -> (before + after) / 2.0
        }
        return ShelfState(items.map { if (it.id == item.id) it.copy(order = order, updated = now) else it }, tombs)
    }

    fun update(item: ShelfItem): ShelfState = ShelfState(items.map { if (it.id == item.id) item else it }, tombs)

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
        return ShelfState(byId.values.toList(), tomb.filterKeys { it !in live }.map { ShelfTomb(it.key, it.value) }).limited()
    }

    fun snapshot(): ShelfSync = ShelfSync(items, tombs, full = true)

    /** Tombstones older than [maxAgeMs] are forgotten. */
    fun pruned(now: Long, maxAgeMs: Long = TOMB_TTL): ShelfState = copy(tombs = tombs.filter { now - it.ts < maxAgeMs })

    /** At most [LIMIT] items: the least recently changed fall off (same rule on both sides, so no tombstones needed). */
    private fun limited(): ShelfState {
        if (items.size <= LIMIT) return this
        val keep = items.sortedWith(compareByDescending<ShelfItem> { it.updated }.thenBy { it.id }).take(LIMIT).map { it.id }.toSet()
        return copy(items = items.filter { it.id in keep })
    }

    companion object {
        const val LIMIT = 50
        const val TOMB_TTL = 30L * 24 * 3600 * 1000
        const val BODY_MAX = 4000

        /** A new item from text the user typed, dropped or shared. */
        fun itemFrom(text: String, id: String, from: String, now: Long): ShelfItem {
            val g = ShelfDetect.classify(text)
            return ShelfItem(
                id = id, kind = g.kind, title = g.title.ifEmpty { "Note" }, body = g.body.take(BODY_MAX), secret = g.secret,
                from = from, created = now, updated = now, order = 0.0,
            )
        }

        fun fileItem(file: ShelfFile, id: String, from: String, now: Long): ShelfItem =
            ShelfItem(id = id, kind = ShelfKinds.FILE, title = file.name, body = file.mime, from = from, created = now, updated = now,
                order = 0.0, file = file)
    }
}
