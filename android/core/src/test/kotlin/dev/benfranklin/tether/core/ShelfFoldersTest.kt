package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.ShelfFolders
import dev.benfranklin.tether.core.features.ShelfOffline
import dev.benfranklin.tether.core.features.ShelfState
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Caps8
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfFolder
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.proto.ShelfSync
import dev.benfranklin.tether.core.proto.ShelfTomb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 0.5.4: Shelf folders (protocol revision 8), the 500 limit, paged snapshots and offline copies. Mirrored in ShelfFoldersTests.swift. */
class ShelfFoldersTest {
    private fun item(id: String, t: Long, from: String = "mac", folder: String? = null, file: ShelfFile? = null) =
        ShelfItem(id, if (file == null) ShelfKinds.TEXT else ShelfKinds.FILE, "t$id", "b$t", null, from, t, t, 0.0, file, folder)

    private fun size(i: ShelfItem) = MessageCodec.json.encodeToString(ShelfItem.serializer(), i).length

    // ---------------- codec ----------------

    @Test fun capIsAnnounced() = assertTrue(Caps8.SHELF_FOLDERS in Caps.CURRENT)

    @Test fun codecRoundTripWithFolders() {
        val m = ShelfSync(
            listOf(item("a", 1, folder = "f1")), listOf(ShelfTomb("x", 3)), full = true,
            folders = listOf(ShelfFolder("f1", "Placement", 1.0, 2)), removedFolders = listOf(ShelfTomb("f0", 4)),
        )
        assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test fun oldFormatDecodesAsLoose() {
        val json = """{"items":[{"id":"a","kind":"text","title":"t","from":"phone","created":1,"updated":1,"order":0}],"removed":[],"full":true}"""
        val p = MessageCodec.json.decodeFromString(ShelfSync.serializer(), json)
        assertNull(p.items[0].folderId)
        assertTrue(p.folders.isEmpty())
        assertEquals(listOf("a"), ShelfState().merge(p).itemsIn(null).map { it.id })
    }

    @Test fun looseItemsCarryNoFolderField() {
        val text = MessageCodec.json.encodeToString(ShelfItem.serializer(), item("a", 1))
        assertFalse(text.contains("folderId"))
    }

    // ---------------- model ----------------

    @Test fun createRenameDeleteAndMove() {
        var s = ShelfState().add(item("a", 1)).add(item("b", 2))
        s = s.createFolder("f1", "  Placement \n drives ", 5)!!
        assertEquals("Placement drives", s.folders.single().name)
        assertNull(s.createFolder("f2", "   ", 6))
        s = s.createFolder("f2", "College", 6)!!
        assertEquals(listOf("f1", "f2"), s.orderedFolders.map { it.id })
        s = s.moveTo("a", "f1", 7)!!
        assertNull("dropping onto the same folder changes nothing", s.moveTo("a", "f1", 8))
        assertNull(s.moveTo("a", "nope", 8))
        assertEquals(listOf("a"), s.itemsIn("f1").map { it.id })
        assertEquals(listOf("b"), s.itemsIn(null).map { it.id })
        assertEquals(mapOf("f1" to 1), s.counts())
        s = s.renameFolder("f1", "Placement", 9)!!
        assertNull(s.renameFolder("f1", "Placement", 10))
        s = s.deleteFolder("f1", 11)
        assertEquals("deleting a folder never deletes items", 2, s.items.size)
        assertEquals(2, s.itemsIn(null).size)
        assertNull(s.items.first { it.id == "a" }.folderId)
        assertEquals(listOf("f1"), s.folderTombs.map { it.id })
    }

    @Test fun rapidRepeatedMovesAreIdempotent() {
        var s = ShelfState().add(item("a", 1)).createFolder("f", "F", 2)!!
        s = s.moveTo("a", "f", 3)!!
        repeat(20) { i -> s.moveTo("a", "f", 4L + i)?.let { s = it } }
        assertEquals(3L, s.items.single().updated)
        s = s.deleteFolder("f", 30).deleteFolder("f", 31)
        assertEquals(listOf(ShelfTomb("f", 30)), s.folderTombs)
    }

    @Test fun moveIntoAFolderDeletedOnTheOtherDeviceShowsLoose() {
        val base = ShelfState().add(item("a", 1)).createFolder("f", "F", 2)!!
        val mac = base.moveTo("a", "f", 10)!!
        val phone = base.deleteFolder("f", 5)
        val m = mac.merge(phone.snapshot())
        val p = phone.merge(mac.snapshot())
        assertEquals(m.ordered, p.ordered)
        assertEquals(listOf("a"), m.itemsIn(null).map { it.id })
        assertTrue(m.folders.isEmpty() && p.folders.isEmpty())
    }

    @Test fun renameConflictNewestWins() {
        val base = ShelfState().createFolder("f", "A", 1)!!
        val mac = base.renameFolder("f", "Mac name", 5)!!
        val phone = base.renameFolder("f", "Phone name", 6)!!
        assertEquals("Phone name", mac.merge(phone.snapshot()).folders.single().name)
        assertEquals("Phone name", phone.merge(mac.snapshot()).folders.single().name)
    }

    @Test fun offlineFolderEditsConverge() {
        val rnd = Random(54)
        repeat(150) { round ->
            var mac = ShelfState()
            var phone = ShelfState()
            var clock = 1L
            repeat(40) {
                val onMac = rnd.nextBoolean()
                var s = if (onMac) mac else phone
                val ids = s.items.map { it.id }
                val fids = s.folders.map { it.id }
                s = when (rnd.nextInt(8)) {
                    0, 1 -> s.add(item("r${round}x${rnd.nextInt(10)}", clock, if (onMac) "mac" else "phone"))
                    2 -> if (ids.isEmpty()) s else s.remove(ids.random(rnd), clock)
                    3 -> s.createFolder("f${rnd.nextInt(4)}", "F${rnd.nextInt(3)}", clock) ?: s
                    4 -> if (fids.isEmpty()) s else s.renameFolder(fids.random(rnd), "N${rnd.nextInt(5)}", clock) ?: s
                    5 -> if (fids.isEmpty()) s else s.deleteFolder(fids.random(rnd), clock)
                    6 -> if (ids.isEmpty()) s else s.moveTo(ids.random(rnd), if (rnd.nextInt(3) == 0 || fids.isEmpty()) null else fids.random(rnd), clock) ?: s
                    else -> if (ids.isEmpty()) s else s.move(ids.random(rnd), rnd.nextInt(5), clock)
                }
                clock++
                if (onMac) mac = s else phone = s
                if (rnd.nextInt(6) == 0) {
                    val m = mac
                    mac = mac.merge(phone.snapshot())
                    phone = phone.merge(m.snapshot())
                }
            }
            val a = mac.merge(phone.snapshot())
            val b = phone.merge(mac.snapshot())
            assertEquals(a.ordered, b.ordered)
            assertEquals(a.orderedFolders, b.orderedFolders)
            assertEquals(a.itemsIn(null).map { it.id }, b.itemsIn(null).map { it.id })
        }
    }

    // ---------------- wire ----------------

    @Test fun oldPeersGetNoFolderFields() {
        val s = ShelfState().add(item("a", 1)).createFolder("f", "F", 2)!!.moveTo("a", "f", 3)!!
        val stripped = ShelfFolders.forPeer(s.snapshot(), folders = false)
        assertNull(stripped.items.single().folderId)
        assertTrue(stripped.folders.isEmpty())
        assertFalse(MessageCodec.json.encodeToString(ShelfItem.serializer(), stripped.items.single()).contains("folderId"))
        assertEquals(s.snapshot(), ShelfFolders.forPeer(s.snapshot(), folders = true))
    }

    @Test fun fiveHundredItemSnapshotIsPagedUnderTheRecordLimit() {
        var s = ShelfState()
        val body = "x".repeat(3_900)
        for (i in 0 until 500) s = s.add(ShelfItem("i$i", ShelfKinds.TEXT, "t", body, null, "mac", i.toLong(), i.toLong(), 0.0))
        assertEquals(500, s.items.size)
        val pages = ShelfFolders.pages(s.snapshot(), size = ::size)
        assertTrue(pages.size > 10)
        for (p in pages) assertTrue(MessageCodec.encode(p).size < 64 * 1024)
        var other = ShelfState()
        for (p in pages) other = other.merge(p)
        assertEquals(s.ordered, other.ordered)
    }

    // ---------------- offline copies ----------------

    private fun e(id: String, mb: Long, local: Boolean = true, used: Long, pinned: Boolean = false, own: Boolean = false) =
        ShelfOffline.Entry(id, mb * ShelfOffline.MB, local, used, pinned, own)

    @Test fun lruEvictionDropsLeastRecentlyUsed() {
        val files = listOf(e("old", 40, used = 1), e("mid", 40, used = 2), e("new", 40, used = 3))
        assertEquals(listOf("old"), ShelfOffline.plan(files, 100 * ShelfOffline.MB).evict)
        assertEquals(emptyList<String>(), ShelfOffline.plan(files, 200 * ShelfOffline.MB).evict)
        val opened = listOf(e("old", 40, used = 9), e("mid", 40, used = 2), e("new", 40, used = 3))
        assertEquals(listOf("mid"), ShelfOffline.plan(opened, 100 * ShelfOffline.MB).evict)
    }

    @Test fun pinsAndOwnFilesAreNeverEvicted() {
        val files = listOf(e("pin", 80, used = 0, pinned = true), e("own", 80, used = 0, own = true), e("other", 10, used = 5))
        assertEquals(listOf("other"), ShelfOffline.plan(files, 50 * ShelfOffline.MB).evict)
        assertEquals("Off keeps pinned and own copies", listOf("other"), ShelfOffline.plan(files, 0).evict)
    }

    @Test fun fetchPlanRespectsCapAnd100Mb() {
        val files = listOf(
            e("a", 30, local = false, used = 3), e("b", 30, local = false, used = 2), e("big", 150, local = false, used = 9),
            e("pin", 20, local = false, used = 0, pinned = true),
        )
        assertEquals(setOf("a", "pin"), ShelfOffline.plan(files, 64 * ShelfOffline.MB).fetch.toSet())
        assertEquals(listOf("pin"), ShelfOffline.plan(files, 0).fetch)
    }

    @Test fun folderPinAndLabels() {
        val s = ShelfState().add(item("a", 1, file = ShelfFile("a.pdf", 5, "", "", "phone")))
            .createFolder("f", "F", 2)!!.moveTo("a", "f", 3)!!
        val pins = ShelfOffline.Pins().toggleFolder("f")
        assertTrue(ShelfOffline.entries(s.items, s::folderOf, emptySet(), pins, "mac").single().pinned)
        assertFalse(ShelfOffline.entries(s.items, s::folderOf, emptySet(), pins.toggleFolder("f"), "mac").single().pinned)
        assertEquals("312 MB of 1 GB", ShelfOffline.usageLabel(312 * ShelfOffline.MB, ShelfOffline.GB))
        assertEquals("0 MB · Off", ShelfOffline.usageLabel(0, 0))
        assertEquals(listOf("500 MB", "1 GB", "2 GB", "5 GB", "Off"), ShelfOffline.CAP_OPTIONS.map(ShelfOffline::capLabel))
        assertEquals(ShelfOffline.GB, ShelfOffline.PHONE_DEFAULT)
    }
}

/** 0.5.4 bug: a snippet's detail showed its title, then the same line again as the body's first line. */
class ShelfDetailTextTest {
    @Test fun snippetDetailNeverRepeatsTheTitle() {
        val text = "adb mdns services\nadb connect 192.168.1.4:5555"
        val d = dev.benfranklin.tether.core.features.ShelfCardText.detail(ShelfState.itemFrom(text, "s", "phone", 1))
        assertEquals("adb mdns services", d.title)
        assertFalse(d.preview, d.preview.startsWith("adb mdns services"))
        assertTrue(d.preview.contains("adb connect"))
        val renamed = ShelfItem("r", ShelfKinds.SNIPPET, "Deploy", "git push\nfly deploy", null, "mac", 1, 1, 0.0)
        assertEquals("Deploy", dev.benfranklin.tether.core.features.ShelfCardText.detail(renamed).title)
        assertEquals("git push\nfly deploy", dev.benfranklin.tether.core.features.ShelfCardText.detail(renamed).preview)
    }
}
