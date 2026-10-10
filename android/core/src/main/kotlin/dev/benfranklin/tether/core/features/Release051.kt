package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.AlbumInfo
import dev.benfranklin.tether.core.proto.AlbumKinds

/*
 * 0.5.1: pure logic behind the bug fixes, unit-tested in the core.
 */

/** One item of an Android ClipData, reduced to what the classifier needs. */
data class ClipItemInfo(val text: String?, val uri: String?, val uriMime: String?)

/**
 * What a phone clip really is. Gallery apps (OnePlus/ColorOS, Samsung, Google Photos) put an
 * image as a content URI *and* its file name as the item's text, and `coerceToText()` returns
 * that text first — so Tether sent the name instead of the picture. An image anywhere in the
 * clip wins; our own FileProvider (an image that came from the Mac) is never echoed back.
 */
sealed interface ClipContent {
    data class Image(val uri: String, val mime: String) : ClipContent
    data class Text(val text: String) : ClipContent
    data object Empty : ClipContent

    companion object {
        fun classify(descriptionMimes: List<String>, items: List<ClipItemInfo>, ownAuthority: String): ClipContent {
            val descImage = descriptionMimes.firstOrNull { it.startsWith("image/") }
            for (item in items) {
                val uri = item.uri ?: continue
                if (uri.startsWith("content://$ownAuthority/")) return Empty
                val mime = item.uriMime?.takeIf { it.startsWith("image/") }
                    ?: descImage?.takeIf { item.uriMime == null }
                    ?: mimeFromName(uri)
                if (mime != null) return Image(uri, mime)
                // A copied file (content:// or file://) that isn't an image: its name is not clipboard text.
                if (uri.startsWith("content://") || uri.startsWith("file://")) return Empty
            }
            val text = items.firstNotNullOfOrNull { it.text?.takeIf(String::isNotEmpty) }
            return if (text != null) Text(text) else Empty
        }

        private val IMAGE_EXT = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
            "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        )

        fun mimeFromName(name: String): String? = IMAGE_EXT[name.substringAfterLast('.', "").substringBefore('?').lowercase()]
    }
}

/** The FEATURES row values (0.5.1): short, so row titles never wrap. */
object SetupSummary {
    /** "13/17 · 1 required", or "17/17" when nothing required is missing. */
    fun value(ready: Int, total: Int, missingRequired: Int): String =
        if (missingRequired > 0) "$ready/$total · $missingRequired required" else "$ready/$total"

    /** The required-count part (shown in mono), or null. */
    fun requiredPart(missingRequired: Int): String? = if (missingRequired > 0) "$missingRequired required" else null
}

/**
 * Whether Home shows the slim "Setup · n left" row. Hidden by the user, it comes back only
 * when a required item that was fine at that time goes missing (a revoked permission).
 */
class SetupHomeGate(var hiddenMissing: Set<String>? = null) {
    fun shows(missingRequired: Set<String>): Boolean {
        if (missingRequired.isEmpty()) return false
        val hidden = hiddenMissing ?: return true
        return !hidden.containsAll(missingRequired)
    }

    fun hide(missingRequired: Set<String>) {
        hiddenMissing = missingRequired
    }

    fun update(missingRequired: Set<String>) {
        if (missingRequired.isEmpty()) hiddenMissing = null
    }

    fun encode(): String? = hiddenMissing?.sorted()?.joinToString(",")

    companion object {
        fun decode(s: String?): SetupHomeGate =
            SetupHomeGate(s?.let { v -> v.split(',').filter(String::isNotEmpty).toSet() })
    }
}

/**
 * Picks the node to type into when `findFocus(FOCUS_INPUT)` comes back empty (Compose and
 * WebView fields, IME-only focus): the focused editable node, else the only editable one.
 */
object FocusPick {
    data class Candidate(val id: Int, val editable: Boolean, val focused: Boolean, val visible: Boolean)

    fun pick(candidates: List<Candidate>): Int? {
        val editable = candidates.filter { it.editable && it.visible }
        editable.firstOrNull { it.focused }?.let { return it.id }
        return editable.singleOrNull()?.id
    }
}

/** "tether://setup/<id>" opened from the Mac (OPEN_ON_PHONE): the checklist row to fix, or null. */
object PhoneSetupLink {
    fun itemId(url: String): String? {
        val prefix = "tether://setup/"
        if (!url.startsWith(prefix)) return null
        return url.removePrefix(prefix).substringBefore('?').takeIf { it.matches(Regex("[a-z0-9_]{1,32}")) }
    }
}

/**
 * Builds the Photos albums from MediaStore rows, newest first (0.5.1): All Photos, Camera,
 * Screenshots, WhatsApp Images, Downloads, Videos, Favorites (Android 11+), then the other
 * buckets by size. Each album's cover is its newest item.
 */
class AlbumAccumulator {
    private class Acc(val name: String, val kind: String) {
        var count = 0
        var cover: String? = null
        fun add(id: String) {
            if (cover == null) cover = id
            count++
        }
    }

    private val builtIn = linkedMapOf(
        AlbumKinds.ALL to Acc("All Photos", AlbumKinds.ALL),
        AlbumKinds.CAMERA to Acc("Camera", AlbumKinds.CAMERA),
        AlbumKinds.SCREENSHOTS to Acc("Screenshots", AlbumKinds.SCREENSHOTS),
        AlbumKinds.WHATSAPP to Acc("WhatsApp Images", AlbumKinds.WHATSAPP),
        AlbumKinds.DOWNLOADS to Acc("Downloads", AlbumKinds.DOWNLOADS),
        AlbumKinds.VIDEOS to Acc("Videos", AlbumKinds.VIDEOS),
        AlbumKinds.FAVORITES to Acc("Favorites", AlbumKinds.FAVORITES),
    )
    private val buckets = LinkedHashMap<String, Acc>()

    @Suppress("LongParameterList")
    fun add(id: String, video: Boolean, path: String, bucket: String?, bucketName: String?, fav: Boolean) {
        builtIn.getValue(AlbumKinds.ALL).add(id)
        val kind = builtInKind(path)
        kind?.let { builtIn.getValue(it).add(id) }
        if (video) builtIn.getValue(AlbumKinds.VIDEOS).add(id)
        if (fav) builtIn.getValue(AlbumKinds.FAVORITES).add(id)
        if (kind == null && bucket != null) buckets.getOrPut(bucket) { Acc(bucketName ?: "Album", AlbumKinds.BUCKET) }.add(id)
    }

    fun albums(favoritesSupported: Boolean): List<AlbumInfo> {
        val fixed = builtIn.filter { (k, a) ->
            k == AlbumKinds.ALL || (a.count > 0 && (k != AlbumKinds.FAVORITES || favoritesSupported))
        }.map { (k, a) -> AlbumInfo(k, a.name, a.count, a.cover, a.kind) }
        val rest = buckets.entries.sortedByDescending { it.value.count }
            .map { (b, a) -> AlbumInfo(BUCKET_PREFIX + b, a.name, a.count, a.cover, AlbumKinds.BUCKET) }
        return fixed + rest
    }

    companion object {
        const val BUCKET_PREFIX = "b:"

        /** Which built-in album a RELATIVE_PATH (or DATA path) belongs to, if any. */
        fun builtInKind(path: String): String? = when {
            path.contains("DCIM/Camera", ignoreCase = true) -> AlbumKinds.CAMERA
            path.contains("Screenshots", ignoreCase = true) -> AlbumKinds.SCREENSHOTS
            path.contains("WhatsApp Images", ignoreCase = true) -> AlbumKinds.WHATSAPP
            path.contains("Download", ignoreCase = true) -> AlbumKinds.DOWNLOADS
            else -> null
        }
    }
}

/** The feature heading each optional checklist row is listed under (0.5.1; same table as the Mac). */
object SetupFeatures {
    private val map = mapOf(
        "nearby" to "Connection", "postnotif" to "Connection", "battery" to "Connection", "notif" to "Notifications",
        "sms" to "Messages", "phone" to "Calls", "calllog" to "Calls", "contacts" to "Calls", "dnd" to "Calls",
        "photos" to "Files & photos", "allfiles" to "Files & photos", "accessibility" to "Typing & screen",
        "overlay" to "Continue", "install" to "Updates", "updates" to "Updates", "readlogs" to "Clipboard", "sensitive" to "Codes",
    )

    fun of(id: String): String = map[id] ?: "Other"

    /** Optional rows grouped by feature, features in first-seen order. */
    fun <T> group(rows: List<T>, id: (T) -> String): List<Pair<String, List<T>>> =
        rows.groupBy { of(id(it)) }.toList()
}
