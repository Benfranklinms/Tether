package dev.benfranklin.tether.data

import dev.benfranklin.tether.core.features.PanelSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 0.5.4 Home ("Control Center"): a fixed set of sections the user can show, hide and reorder
 * from the Edit Home sheet. Replaces the 0.5.3 free-form panel grid (sizes, wiggle edit mode).
 */
enum class HomeSection(val id: String, val title: String, val defaultOn: Boolean) {
    QUICK("quick", "Quick controls", true),
    NOW_PLAYING("now_playing", "Now playing", true),
    TRANSFERS("transfers", "Recent transfers", true),
    SHELF("shelf", "Shelf", true),
    CLIPBOARD("clipboard", "Clipboard history", false),
    PHOTOS("photos", "Photos", false),
    FIND_MAC("find_mac", "Find my Mac", false),
    ;

    companion object {
        fun of(id: String?): HomeSection? = entries.firstOrNull { it.id == id }
    }
}

/** What settings.json keeps for one section. Unknown ids from newer builds are dropped on read. */
@Serializable data class SectionSpec(val id: String, val on: Boolean)

/** How Home lays out the visible sections: Quick controls next to Now playing share one block. */
sealed interface HomeRow {
    /** Quick controls (2×2) and the Now playing tile in one rounded block. */
    data object Control : HomeRow

    /** Quick controls on their own: four square buttons in a row. */
    data object QuickOnly : HomeRow

    /** Now playing on its own, full width. */
    data object PlayerOnly : HomeRow

    data class Single(val section: HomeSection) : HomeRow
}

data class HomeSections(val order: List<HomeSection>, val hidden: Set<HomeSection>) {
    val visible: List<HomeSection> get() = order.filter { it !in hidden }

    fun isOn(s: HomeSection): Boolean = s in order && s !in hidden

    fun set(s: HomeSection, on: Boolean): HomeSections = copy(hidden = if (on) hidden - s else hidden + s)

    fun toggle(s: HomeSection): HomeSections = set(s, !isOn(s))

    /** Moves the section at [from] to [to] (both clamped); out-of-range [from] is a no-op. */
    fun move(from: Int, to: Int): HomeSections {
        if (from !in order.indices) return this
        val t = to.coerceIn(0, order.lastIndex)
        if (t == from) return this
        val list = order.toMutableList()
        val s = list.removeAt(from)
        list.add(t, s)
        return copy(order = list)
    }

    fun move(s: HomeSection, to: Int): HomeSections = move(order.indexOf(s), to)

    fun specs(): List<SectionSpec> = order.map { SectionSpec(it.id, it !in hidden) }

    /** The visible sections as rows; Quick controls and Now playing merge when they are next to each other. */
    fun rows(): List<HomeRow> {
        val v = visible
        val out = mutableListOf<HomeRow>()
        var i = 0
        while (i < v.size) {
            val s = v[i]
            val next = v.getOrNull(i + 1)
            val pair = (s == HomeSection.QUICK && next == HomeSection.NOW_PLAYING) ||
                (s == HomeSection.NOW_PLAYING && next == HomeSection.QUICK)
            when {
                pair -> {
                    out += HomeRow.Control
                    i += 2
                    continue
                }
                s == HomeSection.QUICK -> out += HomeRow.QuickOnly
                s == HomeSection.NOW_PLAYING -> out += HomeRow.PlayerOnly
                else -> out += HomeRow.Single(s)
            }
            i++
        }
        return out
    }

    companion object {
        /** 0.5.5 (approved Home v2): the Shelf carousel sits above Recent transfers. Saved layouts keep their order. */
        val DEFAULT = HomeSections(
            listOf(
                HomeSection.QUICK, HomeSection.NOW_PLAYING, HomeSection.SHELF, HomeSection.TRANSFERS,
                HomeSection.CLIPBOARD, HomeSection.PHOTOS, HomeSection.FIND_MAC,
            ),
            HomeSection.entries.filterNot { it.defaultOn }.toSet(),
        )

        /**
         * Reads settings: the 0.5.4 [saved] list wins; otherwise a 0.5.3 panel layout ([legacy]) is
         * migrated; nothing saved → [DEFAULT]. Unknown ids are skipped, duplicates keep the first,
         * and sections missing from what was saved are appended at their default visibility.
         */
        fun from(saved: List<SectionSpec>?, legacy: List<PanelSpec>? = null): HomeSections = when {
            saved != null -> fromSaved(saved)
            legacy != null -> migrate(legacy)
            else -> DEFAULT
        }

        private fun fromSaved(saved: List<SectionSpec>): HomeSections {
            val order = mutableListOf<HomeSection>()
            val hidden = mutableSetOf<HomeSection>()
            for (spec in saved) {
                val s = HomeSection.of(spec.id)?.takeIf { it !in order } ?: continue
                order += s
                if (!spec.on) hidden += s
            }
            return complete(order, hidden)
        }

        /** The 0.5.3 panel kinds that become each section. Mac status moved into the header. */
        private fun sectionOf(panelKind: String): HomeSection? = when (panelKind) {
            "send_clipboard", "send_file", "hotspot" -> HomeSection.QUICK
            "now_playing" -> HomeSection.NOW_PLAYING
            "transfers" -> HomeSection.TRANSFERS
            "shelf" -> HomeSection.SHELF
            "clipboard" -> HomeSection.CLIPBOARD
            "photos" -> HomeSection.PHOTOS
            else -> null
        }

        /**
         * 0.5.3 → 0.5.4: a section is on when the user kept its panel, in the order the panels had
         * (Quick controls where the first of Send clipboard / Send file / Hotspot was). Sections
         * that had no panel (and Find my Mac, new on Home) come after, hidden. An empty 0.5.3 layout
         * (everything removed on purpose) migrates to every section hidden.
         */
        fun migrate(legacy: List<PanelSpec>): HomeSections {
            val order = mutableListOf<HomeSection>()
            for (p in legacy) {
                val s = sectionOf(p.kind) ?: continue
                if (s !in order) order += s
            }
            val kept = order.toSet()
            HomeSection.entries.filterNot { it in kept }.forEach { order += it }
            return HomeSections(order, HomeSection.entries.filterNot { it in kept }.toSet())
        }

        private fun complete(order: MutableList<HomeSection>, hidden: MutableSet<HomeSection>): HomeSections {
            // Missing sections go in at their default place (0.5.5 default order: Shelf before Transfers).
            DEFAULT.order.filterNot { it in order }.forEach {
                order += it
                if (!it.defaultOn) hidden += it
            }
            return HomeSections(order, hidden)
        }
    }
}

private const val FULL = 100

/** The header's connection line (0.5.4): text, dot style and whether "Reconnect" is offered. */
data class HeaderLine(val text: String, val dot: Dot, val reconnect: Boolean) {
    enum class Dot { FILLED, PULSE, HOLLOW }


    companion object {
        /**
         * Connected → "Connected · Wi-Fi · 82%" (battery when the Mac reports one); connecting →
         * "Connecting…"; otherwise "Not connected", with the reason when Bluetooth is the problem.
         */
        fun of(connected: Boolean, connecting: Boolean, wifi: Boolean, battery: Int?, canReconnect: Boolean, reason: String? = null) =
            when {
                connected -> HeaderLine(
                    "Connected · " + (if (wifi) "Wi-Fi" else "Bluetooth") + (battery?.coerceIn(0, FULL)?.let { " · $it%" } ?: ""),
                    Dot.FILLED, reconnect = false,
                )
                connecting -> HeaderLine("Connecting…", Dot.PULSE, reconnect = false)
                else -> HeaderLine("Not connected" + (reason?.let { " · $it" } ?: ""), Dot.HOLLOW, reconnect = canReconnect)
            }
    }
}

/** The Photos button's badge: photos newer than the last one seen (MediaStore ids grow). "9+" past nine. */
object PhotoBadge {
    fun count(ids: List<Long>, seenId: Long): Int = if (seenId <= 0L) 0 else ids.count { it > seenId }

    fun label(n: Int): String? = when {
        n <= 0 -> null
        n > MAX -> "$MAX+"
        else -> "$n"
    }

    private const val MAX = 9
}

/**
 * Clipboard history rows on Home say what kind of thing was copied, never the text itself (0.5.4:
 * no raw clipboard content anywhere on Home; a copied phone number or code used to sit there for
 * anyone to read). Tapping a row still copies the full text.
 */
object ClipLabel {
    private val PHONE = Regex("^\\+?[0-9][0-9 ()\\-]{5,}[0-9]$")
    private val CODE = Regex("^[0-9]{4,8}$")
    private val LINK = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)

    fun describe(text: String): String {
        val t = text.trim()
        val lines = t.lines().count { it.isNotBlank() }
        return when {
            t.isEmpty() -> "Empty"
            LINK.matches(t) -> "Link"
            CODE.matches(t) -> "Code · ${t.length} digits"
            PHONE.matches(t) && t.count { it.isDigit() } >= MIN_PHONE_DIGITS -> "Phone number"
            lines > 1 -> "Text · $lines lines"
            else -> "Text · ${t.length} " + if (t.length == 1) "character" else "characters"
        }
    }

    private const val MIN_PHONE_DIGITS = 7
}

/**
 * Reads a saved Home layout without ever failing the whole settings file: anything that isn't a
 * list of the expected objects (a hand-edited or half-written settings.json, a newer build's
 * format) reads as null → the default Home, and bad entries inside a list are skipped. Before
 * 0.5.4 a malformed `homePanels` value made settings.json unreadable, which reset every setting.
 */
abstract class LenientListSerializer<T : Any>(private val item: KSerializer<T>, private val parse: (JsonObject) -> T?) :
    KSerializer<List<T>?> {
    private val strict = ListSerializer(item).nullable
    override val descriptor: SerialDescriptor = strict.descriptor

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: List<T>?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(ListSerializer(item), value)
    }

    override fun deserialize(decoder: Decoder): List<T>? {
        val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonObject)?.let(parse) }
    }
}

object LenientSectionSpecs : LenientListSerializer<SectionSpec>(SectionSpec.serializer(), { o ->
    val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val on = (o["on"] as? JsonPrimitive)?.booleanOrNull
    if (id != null && on != null) SectionSpec(id, on) else null
})

object LenientPanelSpecs : LenientListSerializer<PanelSpec>(PanelSpec.serializer(), { o ->
    val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val size = (o["size"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (kind != null) PanelSpec(kind, size.orEmpty()) else null
})
