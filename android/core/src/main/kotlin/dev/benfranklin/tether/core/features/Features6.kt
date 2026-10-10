package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.CaptureKinds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One node of an accessibility tree, flattened for [HotspotSwitchFinder]. [label] is the text
 * of the row the node sits in (its own text, else its parent row's title).
 */
data class UiNode(
    val label: String,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val className: String = "",
)

/**
 * Finds the hotspot on/off switch on an OEM's tethering settings screen. Android has no public
 * API for third-party apps to start tethering, so Tether control (the accessibility service)
 * opens the screen and clicks this node. Labels differ per OEM: "Wi-Fi hotspot" (Pixel),
 * "Personal hotspot" (OnePlus/ColorOS), "Mobile Hotspot" (Samsung), "Portable hotspot" (MIUI).
 */
object HotspotSwitchFinder {
    private val positive = listOf("hotspot", "point d'accès", "zugangspunkt", "punto de acceso")
    private val negative = listOf(
        "usb", "bluetooth", "ethernet", "share", "settings", "password", "name", "band", "limit", "automatic", "connected",
    )

    fun isHotspotLabel(label: String): Boolean {
        val l = label.lowercase(Locale.ROOT).trim()
        if (l.isEmpty() || positive.none { l.contains(it) }) return false
        return negative.none { l.contains(it) }
    }

    /** Index of the switch to click, or null when this screen has no hotspot switch. Prefers real switches. */
    fun pick(nodes: List<UiNode>): Int? {
        val candidates = nodes.withIndex().filter { (_, n) -> n.checkable && isHotspotLabel(n.label) }
        return (candidates.firstOrNull { it.value.className.endsWith("Switch") } ?: candidates.firstOrNull())?.index
    }

    /** A row (not a switch) that opens the hotspot sub-screen, e.g. "Wi-Fi hotspot" on Pixel's Network › Hotspot & tethering. */
    fun pickEntry(nodes: List<UiNode>): Int? =
        nodes.withIndex().firstOrNull { (_, n) -> !n.checkable && n.clickable && isHotspotLabel(n.label) }?.index
}

/** The hotspot name and password the phone's user saved once in Tether (Android hides the real ones). */
object HotspotCredentials {
    fun validSsid(ssid: String?): Boolean = !ssid.isNullOrBlank() && ssid.toByteArray(Charsets.UTF_8).size in 1..MAX_SSID_BYTES

    /** WPA2/WPA3 passphrase: 8–63 printable ASCII characters (or empty for an open network). */
    fun validPsk(psk: String?): Boolean =
        psk.isNullOrEmpty() || (psk.length in MIN_PSK..MAX_PSK && psk.all { it.code in PRINTABLE_MIN..PRINTABLE_MAX })

    private const val MAX_SSID_BYTES = 32
    private const val MIN_PSK = 8
    private const val MAX_PSK = 63
    private const val PRINTABLE_MIN = 0x20
    private const val PRINTABLE_MAX = 0x7E
}

/** File names for Insert from Phone, like macOS screenshots: "Photo 2026-10-10 at 22.05.12.jpg". */
object CaptureNaming {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH.mm.ss", Locale.ROOT)

    fun fileName(kind: String, pages: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = format.format(Instant.ofEpochMilli(nowMs).atZone(zone))
        return if (kind == CaptureKinds.SCAN) {
            "Scan $stamp" + if (pages > 1) ".pdf" else ".jpg"
        } else {
            "Photo $stamp.jpg"
        }
    }
}

/** A crop rectangle in 0..1 image coordinates (the camera fallback's crop step). */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    /** Moves one edge/corner by (dx, dy) in 0..1 units, keeping a minimum size and the image bounds. */
    fun drag(handle: Handle, dx: Float, dy: Float): CropRect {
        var l = left
        var t = top
        var r = right
        var b = bottom
        if (handle.left) l = (l + dx).coerceIn(0f, r - MIN)
        if (handle.right) r = (r + dx).coerceIn(l + MIN, 1f)
        if (handle.top) t = (t + dy).coerceIn(0f, b - MIN)
        if (handle.bottom) b = (b + dy).coerceIn(t + MIN, 1f)
        if (handle == Handle.MOVE) {
            val mx = dx.coerceIn(-left, 1f - right)
            val my = dy.coerceIn(-top, 1f - bottom)
            return CropRect(left + mx, top + my, right + mx, bottom + my)
        }
        return CropRect(l, t, r, b)
    }

    /** Pixel rectangle for a bitmap of [w] × [h] (always at least 1 × 1, inside the bitmap). */
    fun pixels(w: Int, h: Int): IntArray {
        val x = (left * w).toInt().coerceIn(0, w - 1)
        val y = (top * h).toInt().coerceIn(0, h - 1)
        val cw = ((right - left) * w).toInt().coerceIn(1, w - x)
        val ch = ((bottom - top) * h).toInt().coerceIn(1, h - y)
        return intArrayOf(x, y, cw, ch)
    }

    enum class Handle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
        TOP_LEFT(true, true, false, false), TOP_RIGHT(false, true, true, false),
        BOTTOM_LEFT(true, false, false, true), BOTTOM_RIGHT(false, false, true, true),
        MOVE(false, false, false, false),
    }

    companion object {
        const val MIN = 0.1f
        val FULL = CropRect(0f, 0f, 1f, 1f)
        val INSET = CropRect(0.06f, 0.06f, 0.94f, 0.94f)
    }
}
