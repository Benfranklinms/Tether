package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.CapturePolicy
import dev.benfranklin.tether.core.features.ChunkSeal
import dev.benfranklin.tether.core.features.FileKinds
import dev.benfranklin.tether.core.features.MediaReady
import dev.benfranklin.tether.core.features.ShelfCache
import dev.benfranklin.tether.core.features.ShelfCardText
import dev.benfranklin.tether.core.features.ShelfRename
import dev.benfranklin.tether.core.features.ShelfSearch
import dev.benfranklin.tether.core.features.ShelfState
import dev.benfranklin.tether.core.features.TransferList
import dev.benfranklin.tether.core.features.WhatsNewPolicy
import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.proto.ShelfSync
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class Release053Test {
    // ---------- friendly kinds ----------

    @Test fun fileKindsMatchSharedVectors() {
        val f = File("../../protocol/vectors/file-kinds.json").takeIf { it.exists() } ?: File("../protocol/vectors/file-kinds.json")
        val cases = Json.parseToJsonElement(f.readText()).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 15)
        for (c in cases) {
            val o = c.jsonObject
            val mime = o["mime"]!!.jsonPrimitive.content
            val name = o["name"]!!.jsonPrimitive.content
            assertEquals("$mime / $name", o["kind"]!!.jsonPrimitive.content, FileKinds.describe(mime, name))
            assertEquals("$mime / $name image", o["image"]!!.jsonPrimitive.boolean, FileKinds.isImage(mime, name))
        }
    }

    @Test fun fileKindsNeverShowRawMime() {
        val raw = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertFalse(FileKinds.describe(raw, "x.docx").contains("/"))
        assertFalse(FileKinds.describe("application/x-weird", null).contains("/"))
        assertEquals("File", FileKinds.describe(null, null))
    }

    // ---------- search ----------

    private fun file(name: String, mime: String, size: Long = 1000) = ShelfFile(name, size, mime, "ab$name", "mac")

    private val items = listOf(
        ShelfItem("1", ShelfKinds.LINK, "Placement form", "forms.gle/x7Tq2", null, "mac", 1, 1, 0.0),
        ShelfItem("2", ShelfKinds.WIFI, "MEC-Library", "", "s3cretPass", "phone", 1, 1, 1.0),
        ShelfItem("3", ShelfKinds.FILE, "PES_quiz_answer_script.docx", "", null, "mac", 1, 1, 2.0,
            file("PES_quiz_answer_script.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
        ShelfItem("4", ShelfKinds.ADDRESS, "Govt. Model Engineering College", "Thrikkakara, Kochi 682021", null, "mac", 1, 1, 3.0),
        ShelfItem("5", ShelfKinds.SNIPPET, "adb shell", "adb shell pm grant x", null, "mac", 1, 1, 4.0),
        ShelfItem("6", ShelfKinds.FILE, "Café menu", "", null, "phone", 1, 1, 5.0, file("menu.jpg", "image/jpeg")),
    )

    private fun ids(q: String) = ShelfSearch.filter(items, q).map { it.id }

    @Test fun searchCoversTitleContentFileNameAndType() {
        assertEquals(listOf("1"), ids("placement"))
        assertEquals(listOf("1"), ids("forms.gle"))
        assertEquals(listOf("3"), ids("word"))
        assertEquals(listOf("3"), ids("quiz docx"))
        assertEquals(listOf("4"), ids("kochi 682021"))
        assertEquals(listOf("2"), ids("wifi"))
        assertEquals(listOf("5"), ids("snippet"))
        assertEquals(listOf("6"), ids("cafe")) // accents folded
        assertEquals(listOf("6"), ids("JPEG"))
        assertEquals(listOf("6"), ids("menu.jpg"))
        assertEquals(items.map { it.id }, ids("  "))
        assertEquals(emptyList<String>(), ids("placement wifi"))
    }

    @Test fun searchNeverMatchesTheWifiPassword() {
        assertEquals(emptyList<String>(), ids("s3cret"))
    }

    // ---------- rename ----------

    @Test fun renameChangesTitleAndFileNameKeepingExtension() {
        val item = items[2]
        val r = ShelfRename.rename(item, "  Quiz answers \n", 10)!!
        assertEquals("Quiz answers", r.title)
        assertEquals("Quiz answers.docx", r.file?.name)
        assertEquals(10L, r.updated)
        assertEquals("Quiz.docx", ShelfRename.rename(item, "Quiz.docx", 10)!!.file?.name)
        assertEquals("Quiz.DOCX", ShelfRename.rename(item, "Quiz.DOCX", 10)!!.file?.name)
        assertEquals("a_b.docx", ShelfRename.rename(item, "a/b", 10)!!.file?.name)
        assertNull(ShelfRename.rename(item, "   ", 10))
        assertNull(ShelfRename.rename(item, item.title, 10))
        assertEquals("Library", ShelfRename.rename(items[1], "Library", 10)!!.title)
        assertNull(ShelfRename.rename(items[1], "Library", 10)!!.file)
    }

    @Test fun renameAlwaysMovesTheTimestampForward() {
        val item = items[0].copy(updated = 500)
        assertEquals(501L, ShelfRename.rename(item, "New", 100)!!.updated)
    }

    @Test fun renameSyncsAndWinsTheMergeOnTheOtherDevice() {
        val base = ShelfState(listOf(items[2]))
        val renamed = ShelfRename.rename(items[2], "Answers", 50)!!
        val phone = base.update(renamed)
        val mac = base.merge(ShelfSync(listOf(renamed)))
        assertEquals(phone.items, mac.items)
        // An older copy arriving later does not undo the rename.
        assertEquals("Answers", mac.merge(ShelfSync(listOf(items[2]))).items.single().title)
    }

    // ---------- cache policy ----------

    @Test fun cacheFetchPolicy() {
        val small = file("a.pdf", "application/pdf", 500_000)
        val big = file("b.mov", "video/quicktime", 40L * 1024 * 1024)
        val huge = file("c.zip", "application/zip", ShelfCache.MAX_BYTES + 1)
        assertTrue(ShelfCache.shouldFetch(small, haveLocal = false, inFlight = false, connected = true, wifi = false))
        assertFalse(ShelfCache.shouldFetch(big, false, false, connected = true, wifi = false))
        assertTrue(ShelfCache.shouldFetch(big, false, false, connected = true, wifi = true))
        assertFalse(ShelfCache.shouldFetch(huge, false, false, true, true))
        assertFalse(ShelfCache.shouldFetch(small, haveLocal = true, inFlight = false, connected = true, wifi = true))
        assertFalse(ShelfCache.shouldFetch(small, false, inFlight = true, connected = true, wifi = true))
        assertFalse(ShelfCache.shouldFetch(small, false, false, connected = false, wifi = false))
        assertTrue(ShelfCache.shouldFetch(file("max", "x", ShelfCache.MAX_BYTES), false, false, true, true))
    }

    @Test fun notDownloadedOnlyWhenMissingAndOffline() {
        assertEquals(ShelfCache.State.LOCAL, ShelfCache.state(haveLocal = true, fetching = false, connected = false))
        assertEquals(ShelfCache.State.FETCHING, ShelfCache.state(false, fetching = true, connected = true))
        assertEquals(ShelfCache.State.REMOTE, ShelfCache.state(false, false, connected = true))
        assertEquals(ShelfCache.State.NOT_DOWNLOADED, ShelfCache.state(false, false, connected = false))
    }

    // ---------- at-rest encryption ----------

    private val key = ByteArray(32) { it.toByte() }

    @Test fun chunkSealRoundTripsEverySizeAroundChunkEdges() {
        val r = Random(53)
        for (n in listOf(0, 1, 15, 16, 17, ChunkSeal.CHUNK - 1, ChunkSeal.CHUNK, ChunkSeal.CHUNK + 1, 3 * ChunkSeal.CHUNK, 200_001)) {
            val plain = r.nextBytes(n)
            val blob = ChunkSeal.encryptBytes(plain, key)
            assertArrayEquals("size $n", plain, ChunkSeal.decryptBytes(blob, key))
            if (n > 32) assertFalse(blob.toList().windowed(32).any { it == plain.take(32) })
        }
    }

    @Test fun chunkSealRejectsTamperingTruncationAndWrongKey() {
        val plain = Random(1).nextBytes(3 * ChunkSeal.CHUNK + 10)
        val blob = ChunkSeal.encryptBytes(plain, key)
        fun rejects(b: ByteArray, k: ByteArray = key) {
            try {
                ChunkSeal.decryptBytes(b, k)
                fail("accepted a bad blob")
            } catch (_: Exception) {
                // expected
            }
        }
        rejects(blob.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() })
        rejects(blob.copyOf(12 + 2 * (ChunkSeal.CHUNK + 16))) // cut at a chunk boundary
        rejects(blob.copyOf(blob.size - 1))
        rejects(blob + byteArrayOf(0))
        rejects(blob, ByteArray(32))
        rejects("nope".toByteArray())
    }

    @Test fun chunkSealStreamsWithoutWholeFileBuffers() {
        val n = 5 * ChunkSeal.CHUNK + 123
        val src = Random(7).nextBytes(n)
        val sealed = ByteArrayOutputStream()
        ChunkSeal.encrypt(src.inputStream(), sealed, key, ByteArray(8))
        val out = ByteArrayOutputStream()
        ChunkSeal.decrypt(sealed.toByteArray().inputStream(), out, key)
        assertArrayEquals(src, out.toByteArray())
        assertEquals(12 + 6 * 16 + n, sealed.size())
    }

    // ---------- capture launch ----------

    @Test fun captureLaunchPlanUsesRealExemptionsThenTheNotification() {
        val l = CapturePolicy.Launch.entries.associateBy { it.name }
        assertEquals(listOf(l["DIRECT"], l["NOTIFICATION"]), CapturePolicy.plan(foreground = true, accessibility = false, canOverlay = false, sdk = 36))
        // Ben's phone: Android 16, overlay allowed, Tether control off → overlay bridge (SAW alone no longer exempts on 15+).
        assertEquals(listOf(l["OVERLAY_BRIDGE"], l["NOTIFICATION"]), CapturePolicy.plan(false, false, true, 36))
        assertEquals(listOf(l["DIRECT"], l["NOTIFICATION"]), CapturePolicy.plan(false, false, true, 34))
        assertEquals(listOf(l["ACCESSIBILITY"], l["OVERLAY_BRIDGE"], l["NOTIFICATION"]), CapturePolicy.plan(false, true, true, 35))
        assertEquals(listOf(l["NOTIFICATION"]), CapturePolicy.plan(false, false, false, 36))
        assertEquals(listOf(l["DIRECT"], l["NOTIFICATION"]), CapturePolicy.plan(true, false, true, 30))
    }

    @Test fun aLaunchThatNeverOpenedNeverBlocksTheNextRequest() {
        assertFalse(CapturePolicy.busy(openedAt = null, now = 1_000))
        assertTrue(CapturePolicy.busy(openedAt = 1_000, now = 61_000))
        assertFalse(CapturePolicy.busy(openedAt = 1_000, now = 1_000 + CapturePolicy.STALE_MS))
    }

    // ---------- what's new ----------

    @Test fun whatsNewAfterUpgradeFromBuildsThatNeverSavedAVersion() {
        val a = WhatsNewPolicy.Action.entries.associateBy { it.name }
        assertEquals(a["SHOW"], WhatsNewPolicy.decide("0.5.3", seen = null, usedBefore = true))
        assertEquals(a["RECORD"], WhatsNewPolicy.decide("0.5.3", seen = null, usedBefore = false))
        assertEquals(a["SHOW"], WhatsNewPolicy.decide("0.5.3", "0.5.2", usedBefore = true))
        assertEquals(a["NONE"], WhatsNewPolicy.decide("0.5.3", "0.5.3", usedBefore = true))
        assertEquals(a["NONE"], WhatsNewPolicy.decide("0.5.3-dev", "0.5.3", usedBefore = true))
        assertEquals(a["SHOW"], WhatsNewPolicy.decide("0.5.3-dev", "0.5.2", usedBefore = true))
    }

    @Test fun whatsNewNotesComeFromTheBundledChangelog() {
        val md = "# Changelog\n\nintro\n\n## 0.5.3\n\n- **A.** one\n- B\n\n## 0.5.2\n\n- old\n"
        assertEquals("- **A.** one\n- B", WhatsNewPolicy.section(md, "0.5.3-dev"))
        assertEquals("- old", WhatsNewPolicy.section(md, "0.5.2"))
        assertNull(WhatsNewPolicy.section(md, "0.4.0"))
        val real = File("../../CHANGELOG.md").takeIf { it.exists() } ?: File("../CHANGELOG.md")
        assertNotNull(WhatsNewPolicy.section(real.readText(), "0.5.2"))
        assertNotNull(WhatsNewPolicy.section(real.readText(), "0.5.3"))
    }

    // ---------- transfers ----------

    private val now = 10L * 24 * 3600_000
    private fun t(id: String, ageH: Double, dir: Direction = Direction.OUT, status: TransferStatus = TransferStatus.DONE) =
        TransferRecord(id, dir, "$id.jpg", 10, "x", "image/jpeg", status, createdAt = now - (ageH * 3600_000).toLong())

    @Test fun homeShowsAtMostThreeRunningFirstAndHidesOldFinished() {
        val list = listOf(t("a", 0.1), t("b", 0.2), t("c", 30.0), t("d", 2.0, status = TransferStatus.ACTIVE), t("e", 0.3), t("f", 0.05))
        assertEquals(listOf("d", "f", "a"), TransferList.home(list, now).map { it.id })
        assertEquals(emptyList<String>(), TransferList.home(listOf(t("old", 25.0)), now).map { it.id })
        // A running transfer is never folded away however old.
        assertEquals(listOf("r"), TransferList.home(listOf(t("r", 50.0, status = TransferStatus.WAITING)), now).map { it.id })
    }

    @Test fun fullListGroupsTodayEarlierFiltersAndCollapses() {
        val midnight = now - 5 * 3600_000
        val list = listOf(t("in", 1.0, Direction.IN), t("out", 2.0), t("y", 8.0), t("old", 40.0, Direction.IN))
        val s = TransferList.sections(list, TransferList.Filter.ALL, now, midnight)
        assertEquals(listOf("Today", "Earlier"), s.map { it.title })
        assertEquals(listOf("in", "out"), s[0].items.map { it.id })
        assertEquals(listOf("y", "old"), s[1].items.map { it.id })
        assertFalse(s[1].collapsed) // "y" is under 24 h old
        val received = TransferList.sections(list, TransferList.Filter.RECEIVED, now, midnight)
        assertEquals(listOf(listOf("in"), listOf("old")), received.map { sec -> sec.items.map { it.id } })
        assertTrue(received[1].collapsed)
        assertEquals(listOf("out", "y"), TransferList.filter(list, TransferList.Filter.SENT).map { it.id })
        assertEquals(emptyList<TransferList.Section>(), TransferList.sections(emptyList(), TransferList.Filter.ALL, now, midnight))
    }

    // ---------- new photos ----------

    @Test fun photoIsAnnouncedOnlyOnceStable() {
        assertFalse(MediaReady.stable(pending = true, size = 15_000, previousSize = 15_000))
        assertFalse(MediaReady.stable(false, 15_000, null))
        assertFalse(MediaReady.stable(false, 280_000, 15_000))
        assertTrue(MediaReady.stable(false, 280_000, 280_000))
        assertFalse(MediaReady.stable(false, 0, 0))
    }

    @Test fun blankDarkThumbnailsAreDetected() {
        assertTrue(MediaReady.isBlank(IntArray(64)))
        assertTrue(MediaReady.isBlank(IntArray(64) { 3 + it % 5 }))
        assertFalse(MediaReady.isBlank(IntArray(64) { it * 4 }))
        assertFalse(MediaReady.isBlank(IntArray(64) { 240 })) // a white page is a real picture
        assertTrue(MediaReady.isBlank(IntArray(0)))
    }

    @Test fun snippetCardDoesNotRepeatItsTitle() {
        // Title taken from the first words of a one-line command.
        assertEquals(ShelfCardText.Parts("git rebase -i HEAD~3", ""), ShelfCardText.split("git rebase -i", "git rebase -i HEAD~3"))
        // Title = first line exactly; the preview is what follows.
        assertEquals(ShelfCardText.Parts("Deploy", "npm run build\nnpm run ship"), ShelfCardText.split("Deploy", "Deploy\nnpm run build\nnpm run ship"))
        // Renamed item: left alone.
        assertEquals(ShelfCardText.Parts("My cmd", "git status"), ShelfCardText.split("My cmd", "git status"))
        assertEquals(ShelfCardText.Parts("Note", "hello"), ShelfCardText.split("Note", "  hello  "))
    }
}
