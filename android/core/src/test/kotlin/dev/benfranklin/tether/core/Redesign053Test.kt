package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.ClipRecents
import dev.benfranklin.tether.core.features.HomeLayout
import dev.benfranklin.tether.core.features.MacStatusPolicy
import dev.benfranklin.tether.core.features.Panel
import dev.benfranklin.tether.core.features.PanelKind
import dev.benfranklin.tether.core.features.PanelSize
import dev.benfranklin.tether.core.features.PanelSpec
import dev.benfranklin.tether.core.features.ShelfMeta
import dev.benfranklin.tether.core.features.TransferMeta
import dev.benfranklin.tether.core.features.When
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Caps7
import dev.benfranklin.tether.core.proto.MacMedia
import dev.benfranklin.tether.core.proto.MacMediaCmd
import dev.benfranklin.tether.core.proto.MacStatus
import dev.benfranklin.tether.core.proto.MacStatusReq
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class Redesign053Test {
    // ---------- Home panel layout ----------

    @Test fun defaultLayoutIsTheMockupAndHasNoDuplicates() {
        val d = HomeLayout.DEFAULT
        assertEquals(
            listOf(
                PanelKind.SEND_CLIPBOARD, PanelKind.SEND_FILE, PanelKind.SHELF, PanelKind.MAC, PanelKind.CLIPBOARD,
                PanelKind.NOW_PLAYING, PanelKind.TRANSFERS, PanelKind.PHOTOS,
            ),
            d.kinds,
        )
        assertEquals(d.kinds.size, d.kinds.toSet().size)
        d.panels.forEach { assertTrue("${it.kind} size", it.size in it.kind.sizes) }
        assertEquals(listOf(PanelKind.HOTSPOT), d.available())
        assertEquals(HomeLayout.DEFAULT, HomeLayout.from(null))
    }

    @Test fun addPutsThePanelOnTopOnceWithAnAllowedSize() {
        val l = HomeLayout.DEFAULT.add(PanelKind.HOTSPOT)
        assertEquals(PanelKind.HOTSPOT, l.panels.first().kind)
        assertEquals(PanelSize.SMALL, l.sizeOf(PanelKind.HOTSPOT))
        assertTrue(l.available().isEmpty())
        assertSame(l, l.add(PanelKind.HOTSPOT)) // already there
        val removed = HomeLayout.DEFAULT.remove(PanelKind.SHELF)
        assertSame(removed, removed.add(PanelKind.SHELF, PanelSize.SMALL)) // Shelf has no small size
        assertEquals(PanelSize.LARGE, removed.add(PanelKind.SHELF, PanelSize.LARGE).sizeOf(PanelKind.SHELF))
        assertEquals(PanelKind.SHELF, removed.add(PanelKind.SHELF, at = 99).panels.last().kind)
    }

    @Test fun removeDropsOnlyThatPanel() {
        val l = HomeLayout.DEFAULT.remove(PanelKind.CLIPBOARD)
        assertFalse(PanelKind.CLIPBOARD in l)
        assertEquals(HomeLayout.DEFAULT.panels.size - 1, l.panels.size)
        assertEquals(listOf(PanelKind.CLIPBOARD, PanelKind.HOTSPOT), l.available())
        assertSame(l, l.remove(PanelKind.CLIPBOARD))
    }

    @Test fun resizeOnlyToSizesThePanelComesIn() {
        val d = HomeLayout.DEFAULT
        assertEquals(PanelSize.WIDE, d.resize(PanelKind.MAC, PanelSize.WIDE).sizeOf(PanelKind.MAC))
        assertSame(d, d.resize(PanelKind.MAC, PanelSize.LARGE))
        assertSame(d, d.resize(PanelKind.SHELF, PanelSize.SMALL))
        assertSame(d, d.resize(PanelKind.HOTSPOT, PanelSize.WIDE)) // not on Home
        assertSame(d, d.resize(PanelKind.SHELF, PanelSize.WIDE)) // unchanged
        // The handle cycles and wraps.
        val once = d.cycleSize(PanelKind.NOW_PLAYING)
        assertEquals(PanelSize.LARGE, once.sizeOf(PanelKind.NOW_PLAYING))
        assertEquals(PanelSize.WIDE, once.cycleSize(PanelKind.NOW_PLAYING).sizeOf(PanelKind.NOW_PLAYING))
        assertSame(d, d.cycleSize(PanelKind.HOTSPOT))
    }

    @Test fun moveReordersAndIgnoresBadIndices() {
        val d = HomeLayout.DEFAULT
        val m = d.move(0, 3)
        assertEquals(listOf(PanelKind.SEND_FILE, PanelKind.SHELF, PanelKind.MAC, PanelKind.SEND_CLIPBOARD), m.kinds.take(4))
        assertEquals(d, m.move(3, 0))
        assertEquals(PanelKind.PHOTOS, d.move(PanelKind.PHOTOS, 0).panels.first().kind)
        assertSame(d, d.move(-1, 2))
        assertSame(d, d.move(0, 99))
        assertSame(d, d.move(2, 2))
        assertSame(d, d.move(PanelKind.HOTSPOT, 0))
    }

    @Test fun rowsPairSmallPanelsInOrder() {
        val rows = HomeLayout.DEFAULT.rows().map { r -> r.map { it.kind } }
        assertEquals(
            listOf(
                listOf(PanelKind.SEND_CLIPBOARD, PanelKind.SEND_FILE), listOf(PanelKind.SHELF),
                listOf(PanelKind.MAC, PanelKind.CLIPBOARD), listOf(PanelKind.NOW_PLAYING), listOf(PanelKind.TRANSFERS),
                listOf(PanelKind.PHOTOS),
            ),
            rows,
        )
        // A small panel before a wide one sits alone; a trailing small one too.
        val l = HomeLayout(
            listOf(
                Panel(PanelKind.MAC, PanelSize.SMALL), Panel(PanelKind.SHELF, PanelSize.WIDE),
                Panel(PanelKind.HOTSPOT, PanelSize.SMALL),
            ),
        )
        assertEquals(listOf(1, 1, 1), l.rows().map { it.size })
    }

    @Test fun persistenceRoundTripsAndToleratesOldOrNewData() {
        val json = Json { ignoreUnknownKeys = true }
        val ser = ListSerializer(PanelSpec.serializer())
        val l = HomeLayout.DEFAULT.add(PanelKind.HOTSPOT).resize(PanelKind.SHELF, PanelSize.LARGE).move(0, 4)
        val text = json.encodeToString(ser, l.specs())
        assertEquals(l, HomeLayout.from(json.decodeFromString(ser, text)))
        // Unknown kind (a newer build), bad size, duplicate.
        val odd = listOf(
            PanelSpec("weather", "small"), PanelSpec("shelf", "small"), PanelSpec("mac", "huge"), PanelSpec("shelf", "large"),
        )
        assertEquals(
            HomeLayout(listOf(Panel(PanelKind.SHELF, PanelSize.WIDE), Panel(PanelKind.MAC, PanelSize.SMALL))),
            HomeLayout.from(odd),
        )
        // Everything removed on purpose stays empty.
        assertTrue(HomeLayout.from(emptyList()).panels.isEmpty())
        assertEquals(PanelKind.entries.size, HomeLayout.from(emptyList()).available().size)
    }

    // ---------- times ----------

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 11, 8, 45) // a Sunday

    @Test fun shortAndClockLabels() {
        assertEquals("now", When.short(now - 20_000, now, zone))
        assertEquals("2m", When.short(now - 2 * 60_000, now, zone))
        assertEquals("1h", When.short(now - 61 * 60_000, now, zone))
        assertEquals("Yesterday", When.short(at(2026, 10, 10, 23, 0), now, zone))
        assertEquals("Fri", When.short(at(2026, 10, 9, 21, 14), now, zone))
        assertEquals("Oct 2", When.short(at(2026, 10, 2, 9, 0), now, zone))
        assertEquals("08:42", When.clock(at(2026, 10, 11, 8, 42), now, zone))
        assertEquals("Yesterday", When.clock(at(2026, 10, 10, 7, 0), now, zone))
        assertEquals("Today", When.section(at(2026, 10, 11, 0, 1), now, zone))
        assertEquals("Earlier", When.section(at(2026, 10, 10, 23, 59), now, zone))
    }

    // ---------- Shelf rows ----------

    private fun file(name: String, size: Long, mime: String) = ShelfFile(name, size, mime, "0".repeat(64), "mac")
    private fun item(id: String, kind: String, title: String, body: String = "", created: Long = now, file: ShelfFile? = null, secret: String? = null) =
        ShelfItem(id, kind, title, body, secret, "mac", created, created, 0.0, file)
    private val kb: (Long) -> String = { "${it / 1000} KB" }

    @Test fun fileBadgesAndKinds() {
        assertEquals("PDF", ShelfMeta.badge("application/pdf", "Set 15.pdf"))
        assertEquals("DOC", ShelfMeta.badge(null, "PES_quiz_answer_script.docx"))
        assertEquals("ZIP", ShelfMeta.badge(null, "tether-v0.5.2-debug.zip"))
        assertEquals("APK", ShelfMeta.badge(null, "app.apk"))
        assertEquals("PDF", ShelfMeta.badge("application/pdf", "noext"))
        assertEquals("FILE", ShelfMeta.badge(null, "noext"))
        assertEquals("PDF", ShelfMeta.kind("application/pdf", "a.pdf"))
        assertEquals("Word", ShelfMeta.kind(null, "a.docx"))
        assertEquals("JPEG", ShelfMeta.kind(null, "IMG_1.jpg"))
        assertEquals("PNG", ShelfMeta.kind("image/png", "s.png"))
        assertEquals("Archive", ShelfMeta.kind(null, "x.zip"))
        assertEquals("Excel", ShelfMeta.kind(null, "x.xlsx"))
        assertEquals("Video", ShelfMeta.kind(null, "x.mp4"))
    }

    @Test fun rowLinesNeverRepeatTheTitle() {
        assertEquals("PDF · 454 KB", ShelfMeta.lines(item("1", ShelfKinds.FILE, "Set.pdf", file = file("Set.pdf", 454_000, "application/pdf")), kb).second)
        assertEquals(
            "Word · 11 KB · Available when connected",
            ShelfMeta.lines(item("1", ShelfKinds.FILE, "q.docx", file = file("q.docx", 11_000, "")), kb, missing = true).second,
        )
        assertEquals("forms.gle", ShelfMeta.lines(item("2", ShelfKinds.LINK, "Titan Engineering registration", "https://forms.gle/x7Tq2"), kb).second)
        assertEquals("Wi-Fi · •••••••••", ShelfMeta.lines(item("3", ShelfKinds.WIFI, "MEC_Lab_5G", secret = "pw"), kb).second)
        assertEquals("Wi-Fi · Open", ShelfMeta.lines(item("3", ShelfKinds.WIFI, "Cafe"), kb).second)
        val snippet = ShelfMeta.lines(item("4", ShelfKinds.SNIPPET, "adb mdns services", "adb mdns services\nadb connect 1.2.3.4"), kb)
        assertEquals("adb mdns services" to "Snippet · 2 lines", snippet)
        val addr = ShelfMeta.lines(item("5", ShelfKinds.ADDRESS, "Govt. Model Engineering College", "Govt. Model Engineering College\nThrikkakara, Kochi"), kb)
        assertEquals("Govt. Model Engineering College" to "Thrikkakara, Kochi", addr)
        assertEquals("forms.gle", ShelfMeta.host("https://www.forms.gle/x?y"))
    }

    @Test fun sectionHeadersOnlyWhileNewestFirst() {
        val today = item("a", ShelfKinds.TEXT, "a", created = now - 60_000)
        val today2 = item("b", ShelfKinds.TEXT, "b", created = now - 120_000)
        val old = item("c", ShelfKinds.TEXT, "c", created = now - 2 * 86_400_000L)
        assertEquals(mapOf(0 to "Today", 2 to "Earlier"), ShelfMeta.sections(listOf(today, today2, old), now, zone))
        assertEquals(mapOf(0 to "Earlier"), ShelfMeta.sections(listOf(old), now, zone))
        assertEquals(mapOf(0 to "Today"), ShelfMeta.sections(listOf(today), now, zone))
        assertTrue(ShelfMeta.sections(listOf(today, old, today2), now, zone).isEmpty())
        assertTrue(ShelfMeta.sections(emptyList(), now, zone).isEmpty())
    }

    // ---------- Transfers rows ----------

    private fun rec(status: TransferStatus, done: Long, size: Long, err: String? = null) =
        TransferRecord("t", Direction.IN, "IMG_20261011_084512.jpg", size, "0", "image/jpeg", status, done, err, now)
    private val mb: (Long) -> String = { b -> if (b >= 1_000_000) "%.1f MB".format(b / 1_000_000.0) else "${b / 1000} KB" }

    @Test fun transferLines() {
        assertEquals("2.2 of 3.4 MB", TransferMeta.subtitle(rec(TransferStatus.ACTIVE, 2_200_000, 3_400_000), mb))
        assertEquals("800 KB of 3.4 MB", TransferMeta.subtitle(rec(TransferStatus.ACTIVE, 800_000, 3_400_000), mb))
        assertEquals("64%", TransferMeta.percent(rec(TransferStatus.ACTIVE, 640, 1000)))
        assertNull(TransferMeta.percent(rec(TransferStatus.DONE, 1000, 1000)))
        assertEquals("282 KB", TransferMeta.subtitle(rec(TransferStatus.DONE, 282_000, 282_000), mb))
        assertEquals("No space", TransferMeta.subtitle(rec(TransferStatus.FAILED, 0, 10, "No space"), mb))
        assertEquals("08:45", TransferMeta.time(rec(TransferStatus.DONE, 1, 1), now, zone))
        assertEquals("Screenshot_2026-10-1…20-62.png", TransferMeta.middle("Screenshot_2026-10-11-08-41-20-62.png", 30))
        assertEquals("short.pdf", TransferMeta.middle("short.pdf"))
    }

    // ---------- clipboard + Mac status ----------

    @Test fun clipRecentsDedupesAndCaps() {
        val c = ClipRecents(max = 3)
        c.add("a", 1, false)
        c.add("b", 2, true)
        c.add("  ", 3, true)
        c.add("a ", 4, true)
        assertEquals(listOf("a ", "b"), c.snapshot().map { it.text })
        c.add("c", 5, false)
        c.add("d", 6, false)
        assertEquals(listOf("d", "c", "a "), c.snapshot().map { it.text })
        assertEquals("one two", ClipRecents.preview(" one\n two "))
        assertEquals(5, ClipRecents.preview("abcdefgh", 5).length)
    }

    @Test fun macStatusCodecAndPolicy() {
        assertTrue(Caps7.MAC_STATUS in Caps.CURRENT)
        val s = MacStatus(battery = 82, charging = true, media = MacMedia(true, "Nights", "Frank Ocean", "Music", true, 307_000, 102_000), ts = 5)
        assertEquals(s, MessageCodec.decode(MessageCodec.encode(s)))
        assertEquals(MacStatusReq(media = true), MessageCodec.decode(MessageCodec.encode(MacStatusReq(media = true))))
        assertEquals(MacMediaCmd("toggle"), MessageCodec.decode(MessageCodec.encode(MacMediaCmd("toggle"))))
        // A Mac without a battery and nothing playing.
        val bare = MessageCodec.decode(MessageCodec.encode(MacStatus(ts = 1))) as MacStatus
        assertNull(bare.battery)
        assertNull(bare.media)
        assertEquals(1_500L, MacStatusPolicy.position(1_000, 10_000, true, 100, 600))
        assertEquals(10_000L, MacStatusPolicy.position(9_900, 10_000, true, 0, 5_000))
        assertEquals(1_000L, MacStatusPolicy.position(1_000, 10_000, false, 0, 5_000))
        assertEquals("1:42", MacStatusPolicy.clock(102_000))
        assertEquals("1:02:07", MacStatusPolicy.clock(3_727_000))
    }
}
