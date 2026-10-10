package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.CaptureNaming
import dev.benfranklin.tether.core.features.CropRect
import dev.benfranklin.tether.core.features.HotspotCredentials
import dev.benfranklin.tether.core.features.HotspotSwitchFinder
import dev.benfranklin.tether.core.features.ShelfDetect
import dev.benfranklin.tether.core.features.ShelfState
import dev.benfranklin.tether.core.features.UiNode
import dev.benfranklin.tether.core.features.UpdatePrompt
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Caps6
import dev.benfranklin.tether.core.proto.CaptureKinds
import dev.benfranklin.tether.core.proto.CaptureReq
import dev.benfranklin.tether.core.proto.CaptureResult
import dev.benfranklin.tether.core.proto.FindMac
import dev.benfranklin.tether.core.proto.FindMacState
import dev.benfranklin.tether.core.proto.HotspotReq
import dev.benfranklin.tether.core.proto.HotspotState
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfFileReq
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.proto.ShelfSync
import dev.benfranklin.tether.core.proto.ShelfTomb
import dev.benfranklin.tether.core.wifi.FastPathRetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import kotlin.random.Random

class Release052Test {

    private fun roundTrip(m: Message) = assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))

    @Test fun revision6MessagesRoundTrip() {
        roundTrip(CaptureReq("r1", CaptureKinds.SCAN))
        roundTrip(CaptureResult("r1", "sending", "f1", "Scan.pdf", null, 3, "wifi"))
        roundTrip(FindMac("f", "ring"))
        roundTrip(FindMacState("f", ringing = false, ok = true, locked = true, by = "mac"))
        roundTrip(HotspotReq("h", true))
        roundTrip(HotspotState("h", "on", "Nord", "secret123"))
        val file = ShelfFile("resume.pdf", 1234, "application/pdf", "ab", "phone")
        roundTrip(
            ShelfSync(
                listOf(ShelfItem("1", ShelfKinds.FILE, "resume.pdf", "", null, "phone", 1, 2, -1.5, file)),
                listOf(ShelfTomb("x", 9)), full = true,
            ),
        )
        roundTrip(ShelfFileReq("1"))
        assertTrue(Caps.CURRENT.containsAll(Caps6.ALL))
    }

    @Test fun hotspotStateNeverPrintsThePassword() {
        assertFalse(HotspotState("h", "on", "Nord", "hunter22").toString().contains("hunter22"))
    }

    @Test fun findsHotspotSwitchAcrossOems() {
        val pixel = listOf(
            UiNode("Wi-Fi hotspot", checkable = false, checked = false, clickable = true),
            UiNode("Use Wi-Fi hotspot", checkable = true, checked = false, clickable = true, className = "android.widget.Switch"),
            UiNode("Turn off hotspot automatically", checkable = true, checked = true, clickable = true, className = "android.widget.Switch"),
        )
        assertEquals(1, HotspotSwitchFinder.pick(pixel))
        val oneplus = listOf(
            UiNode("USB tethering", checkable = true, checked = false, clickable = true, className = "android.widget.Switch"),
            UiNode("Personal hotspot", checkable = true, checked = false, clickable = true, className = "androidx.appcompat.widget.SwitchCompat"),
        )
        assertEquals(1, HotspotSwitchFinder.pick(oneplus))
        val menu = listOf(UiNode("Hotspot & tethering", checkable = false, checked = false, clickable = true))
        assertNull(HotspotSwitchFinder.pick(menu))
        assertEquals(0, HotspotSwitchFinder.pickEntry(menu))
        assertFalse(HotspotSwitchFinder.isHotspotLabel("Hotspot password"))
        assertFalse(HotspotSwitchFinder.isHotspotLabel("Bluetooth tethering"))
    }

    @Test fun hotspotCredentialRules() {
        assertTrue(HotspotCredentials.validSsid("Nord CE5"))
        assertFalse(HotspotCredentials.validSsid(""))
        assertFalse(HotspotCredentials.validSsid("x".repeat(33)))
        assertTrue(HotspotCredentials.validPsk(null))
        assertTrue(HotspotCredentials.validPsk("12345678"))
        assertFalse(HotspotCredentials.validPsk("1234567"))
        assertFalse(HotspotCredentials.validPsk("x".repeat(64)))
    }

    @Test fun captureNames() {
        val t = 1_791_656_112_000L // 2026-10-10 18:15:12 UTC
        assertEquals("Photo 2026-10-10 at 18.15.12.jpg", CaptureNaming.fileName(CaptureKinds.PHOTO, 0, t, ZoneOffset.UTC))
        assertEquals("Scan 2026-10-10 at 18.15.12.pdf", CaptureNaming.fileName(CaptureKinds.SCAN, 3, t, ZoneOffset.UTC))
        assertEquals("Scan 2026-10-10 at 18.15.12.jpg", CaptureNaming.fileName(CaptureKinds.SCAN, 1, t, ZoneOffset.UTC))
    }

    @Test fun cropRectStaysInsideAndKeepsMinimumSize() {
        val r = CropRect.FULL.drag(CropRect.Handle.TOP_LEFT, 0.95f, 0.95f)
        assertEquals(0.9f, r.left, 1e-4f)
        assertEquals(0.9f, r.top, 1e-4f)
        val moved = CropRect(0.2f, 0.2f, 0.6f, 0.6f).drag(CropRect.Handle.MOVE, 0.9f, -0.9f)
        assertEquals(1f, moved.right, 1e-4f)
        assertEquals(0f, moved.top, 1e-4f)
        assertEquals(0.4f, moved.width, 1e-4f)
        val px = CropRect(0.5f, 0.5f, 1f, 1f).pixels(1000, 800)
        assertEquals(listOf(500, 400, 500, 400), px.toList())
    }

    @Test fun shelfDetectMatchesSharedVectors() {
        val f = File("../../protocol/vectors/shelf-detect.json").takeIf { it.exists() }
            ?: File("../protocol/vectors/shelf-detect.json")
        val cases = Json.parseToJsonElement(f.readText()).jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val o = c.jsonObject
            val text = o["text"]!!.jsonPrimitive.content
            val g = ShelfDetect.classify(text)
            assertEquals(text, o["kind"]!!.jsonPrimitive.content, g.kind)
            assertEquals(text, o["title"]!!.jsonPrimitive.content, g.title)
            assertEquals(text, o["body"]!!.jsonPrimitive.content, g.body)
            o["secret"]?.let { assertEquals(text, it.jsonPrimitive.content, g.secret) }
        }
    }

    private fun item(id: String, t: Long, from: String = "mac", order: Double = 0.0) =
        ShelfItem(id, ShelfKinds.TEXT, "t$id", "b$t", null, from, t, t, order)

    @Test fun shelfAddPutsNewItemsOnTopAndCapsAtFifty() {
        var s = ShelfState()
        for (i in 1..55) s = s.add(item("i$i", i.toLong()))
        assertEquals(50, s.items.size)
        assertEquals("i55", s.ordered.first().id)
        assertFalse(s.items.any { it.id == "i1" })
    }

    @Test fun shelfRemoveRestoreAndMove() {
        var s = ShelfState().add(item("a", 1)).add(item("b", 2)).add(item("c", 3))
        assertEquals(listOf("c", "b", "a"), s.ordered.map { it.id })
        s = s.move("a", 0, 10)
        assertEquals(listOf("a", "c", "b"), s.ordered.map { it.id })
        s = s.move("a", 2, 11)
        assertEquals(listOf("c", "b", "a"), s.ordered.map { it.id })
        val b = s.items.first { it.id == "b" }
        s = s.remove("b", 20)
        assertEquals(listOf("c", "a"), s.ordered.map { it.id })
        s = s.restore(b, 21)
        assertEquals(listOf("c", "b", "a"), s.ordered.map { it.id })
        assertTrue(s.tombs.isEmpty())
    }

    @Test fun shelfMergeHonoursTimestampsAndTombstones() {
        val mac = ShelfState().add(item("a", 5)).add(item("b", 6))
        // Phone removed "a" at 7 while offline, edited "b" at 4 (older than the Mac's copy).
        val phone = ShelfState(listOf(item("b", 4, "phone")), listOf(ShelfTomb("a", 7)))
        val merged = mac.merge(phone.snapshot())
        assertEquals(listOf("b"), merged.items.map { it.id })
        assertEquals("b6", merged.items.single().body)
        // A copy newer than the tombstone comes back.
        val back = merged.merge(ShelfSync(listOf(item("a", 9)), emptyList()))
        assertTrue(back.items.any { it.id == "a" })
        assertTrue(back.tombs.none { it.id == "a" })
    }

    @Test fun offlineEditsConvergeInAnyOrder() {
        val rnd = Random(52)
        repeat(200) { round ->
            var mac = ShelfState()
            var phone = ShelfState()
            var clock = 1L
            repeat(30) {
                val onMac = rnd.nextBoolean()
                var s = if (onMac) mac else phone
                val ids = s.items.map { it.id }
                s = when (rnd.nextInt(4)) {
                    0, 1 -> s.add(item("r${round}x${rnd.nextInt(12)}", clock, if (onMac) "mac" else "phone"))
                    2 -> if (ids.isEmpty()) s else s.remove(ids.random(rnd), clock)
                    else -> if (ids.isEmpty()) s else s.move(ids.random(rnd), rnd.nextInt(ids.size), clock)
                }
                clock++
                if (onMac) mac = s else phone = s
                if (rnd.nextInt(5) == 0) {
                    val m = mac
                    mac = mac.merge(phone.snapshot())
                    phone = phone.merge(m.snapshot())
                }
            }
            val a = mac.merge(phone.snapshot())
            val b = phone.merge(mac.snapshot())
            assertEquals(a.ordered, b.ordered)
        }
    }

    @Test fun primaryActionsAndCopyText() {
        assertEquals("Open", ShelfDetect.action(ShelfKinds.LINK))
        assertEquals("Map", ShelfDetect.action(ShelfKinds.ADDRESS))
        assertEquals("Copy", ShelfDetect.action(ShelfKinds.WIFI))
        val wifi = ShelfState.itemFrom("WIFI:S:Lab;T:WPA;P:abcdefgh;;", "w", "phone", 1)
        assertEquals("abcdefgh", ShelfDetect.copyText(wifi))
        val note = ShelfState.itemFrom("Buy milk", "n", "mac", 1)
        assertEquals("Buy milk", ShelfDetect.copyText(note))
    }

    @Test fun fastPathRetriesWithBackoffAndImmediatelyOnNetworkChange() {
        val r = FastPathRetry()
        assertFalse(r.due(0, bleReady = true, wifiReady = false)) // idle until Bluetooth is up
        r.linkUp(0)
        assertTrue(r.due(0, bleReady = true, wifiReady = false))
        r.attempted(0) // the Mac couldn't reach any offered address (different networks)
        assertEquals(4_000L, r.nextAt)
        r.attempted(4_000)
        assertEquals(12_000L, r.nextAt)
        repeat(10) { r.attempted(20_000) }
        assertEquals(80_000L, r.nextAt) // capped at 60 s
        // Ben's case: the Mac joins the phone's hotspot at t = 30 s while both apps run.
        r.networkChanged(30_000)
        assertFalse(r.due(30_500, bleReady = true, wifiReady = false))
        assertTrue(r.due(31_200, bleReady = true, wifiReady = false)) // not at 80 s, and no restart needed
        assertEquals(0, r.attempts)
        r.connected()
        assertFalse(r.due(100_000, bleReady = true, wifiReady = true))
        r.wifiLost(200_000)
        assertTrue(r.due(201_200, bleReady = true, wifiReady = false))
        r.linkDown()
        assertFalse(r.due(300_000, bleReady = false, wifiReady = false))
    }

    @Test fun fastPathPrefersAddressesOnTheMacsSubnet() {
        val phone = listOf("192.168.29.142", "10.183.42.1")
        assertEquals(listOf("10.183.42.1", "192.168.29.142"), FastPathRetry.rank(phone, listOf("10.183.42.77")))
        assertEquals(phone, FastPathRetry.rank(phone, listOf("172.16.0.2")))
    }

    @Test fun updatePopupPolicy() {
        val now = 1_000_000_000L
        assertTrue(UpdatePrompt.shouldShow("0.5.3", "0.5.2", null, 0, now))
        assertFalse(UpdatePrompt.shouldShow("0.5.2", "0.5.2", null, 0, now))
        assertFalse(UpdatePrompt.shouldShow("0.5.3", "0.5.2", "0.5.3", 0, now)) // skipped
        assertFalse(UpdatePrompt.shouldShow("0.5.3", "0.5.2", null, now + 1, now)) // Later
        assertTrue(UpdatePrompt.shouldShow("0.5.4", "0.5.2", "0.5.3", 0, now)) // a newer one than the skipped
        assertTrue(UpdatePrompt.shouldCheck(now, now, launch = true, foreground = false))
        assertFalse(UpdatePrompt.shouldCheck(now - 5 * 3_600_000, now, launch = false, foreground = true))
        assertTrue(UpdatePrompt.shouldCheck(now - 6 * 3_600_000, now, launch = false, foreground = true))
        assertFalse(UpdatePrompt.shouldCheck(now - 23 * 3_600_000, now, launch = false, foreground = false))
        assertTrue(UpdatePrompt.showWhatsNew("0.5.2", "0.5.1"))
        assertFalse(UpdatePrompt.showWhatsNew("0.5.2", "0.5.2"))
        assertFalse(UpdatePrompt.showWhatsNew("0.5.2", null)) // fresh install: no sheet
        val notes = "## What's new in 0.5.2\n\n- Motion everywhere\n- Shelf\n\n### Mac\n- Videos play\n\n## Downloads\n- Tether-0.5.2.apk"
        assertEquals("- Motion everywhere\n- Shelf\n\n### Mac\n- Videos play", UpdatePrompt.changelog(notes))
        assertEquals("Fixes", UpdatePrompt.changelog("Fixes\n\n## Downloads\n- x"))
    }
}
