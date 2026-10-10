package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.AlbumAccumulator
import dev.benfranklin.tether.core.features.ClipContent
import dev.benfranklin.tether.core.features.ClipItemInfo
import dev.benfranklin.tether.core.features.FocusPick
import dev.benfranklin.tether.core.features.PhoneSetupLink
import dev.benfranklin.tether.core.features.SetupHomeGate
import dev.benfranklin.tether.core.features.SetupSummary
import dev.benfranklin.tether.core.proto.AlbumInfo
import dev.benfranklin.tether.core.proto.AlbumKinds
import dev.benfranklin.tether.core.proto.Albums
import dev.benfranklin.tether.core.proto.AlbumsReq
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.PhotoFilters
import dev.benfranklin.tether.core.proto.PhotoItem
import dev.benfranklin.tether.core.proto.PhotosReq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Release051Test {
    private val own = "in.benfranklin.tether.files"

    @Test fun galleryImageWithFileNameTextSendsTheImage() {
        // ColorOS Gallery "Copy": text = file name, uri = content image.
        val c = ClipContent.classify(
            listOf("text/plain", "image/jpeg"),
            listOf(ClipItemInfo("IMG_20261010_192601.jpg", "content://com.oplus.gallery/img/42", "image/jpeg")),
            own,
        )
        assertEquals(ClipContent.Image("content://com.oplus.gallery/img/42", "image/jpeg"), c)
    }

    @Test fun imageMimeFromDescriptionOrName() {
        val byDesc = ClipContent.classify(listOf("image/png"), listOf(ClipItemInfo(null, "content://x/1", null)), own)
        assertEquals(ClipContent.Image("content://x/1", "image/png"), byDesc)
        val byName = ClipContent.classify(listOf("text/uri-list"), listOf(ClipItemInfo(null, "content://x/shot.PNG", null)), own)
        assertEquals(ClipContent.Image("content://x/shot.PNG", "image/png"), byName)
    }

    @Test fun ownImagesAreNotEchoed() {
        val c = ClipContent.classify(listOf("image/png"), listOf(ClipItemInfo(null, "content://$own/clips/clipboard-1.png", "image/png")), own)
        assertEquals(ClipContent.Empty, c)
    }

    @Test fun plainTextAndCopiedDocuments() {
        assertEquals(ClipContent.Text("hello"), ClipContent.classify(listOf("text/plain"), listOf(ClipItemInfo("hello", null, null)), own))
        val doc = ClipContent.classify(listOf("application/pdf"), listOf(ClipItemInfo("Report.pdf", "content://docs/7", "application/pdf")), own)
        assertEquals(ClipContent.Empty, doc)
        val link = ClipContent.classify(listOf("text/plain"), listOf(ClipItemInfo("https://a.b", "https://a.b", null)), own)
        assertEquals(ClipContent.Text("https://a.b"), link)
        assertEquals(ClipContent.Empty, ClipContent.classify(emptyList(), emptyList(), own))
    }

    @Test fun setupSummaryIsShort() {
        assertEquals("13/17 · 1 required", SetupSummary.value(13, 17, 1))
        assertEquals("17/17", SetupSummary.value(17, 17, 0))
        assertEquals("1 required", SetupSummary.requiredPart(1))
        assertNull(SetupSummary.requiredPart(0))
    }

    @Test fun homeGateHideAndRevoke() {
        val g = SetupHomeGate()
        assertTrue(g.shows(setOf("battery")))
        g.hide(setOf("battery"))
        assertFalse(g.shows(setOf("battery")))
        assertTrue(g.shows(setOf("battery", "notif")))
        val round = SetupHomeGate.decode(g.encode())
        assertFalse(round.shows(setOf("battery")))
        g.update(emptySet())
        assertNull(g.hiddenMissing)
        assertFalse(g.shows(emptySet()))
        assertTrue(g.shows(setOf("battery")))
    }

    @Test fun focusFallback() {
        fun c(id: Int, e: Boolean, f: Boolean, v: Boolean) = FocusPick.Candidate(id, e, f, v)
        assertEquals(2, FocusPick.pick(listOf(c(1, true, false, true), c(2, true, true, true))))
        assertEquals(1, FocusPick.pick(listOf(c(1, true, false, true), c(3, false, true, true))))
        assertNull(FocusPick.pick(listOf(c(1, true, false, true), c(2, true, false, true))))
        assertNull(FocusPick.pick(listOf(c(1, true, true, false))))
    }

    @Test fun setupLinks() {
        assertEquals("accessibility", PhoneSetupLink.itemId("tether://setup/accessibility"))
        assertNull(PhoneSetupLink.itemId("https://setup/accessibility"))
        assertNull(PhoneSetupLink.itemId("tether://setup/../x"))
    }

    @Test fun albumsCodecAndOldFields() {
        val m = Albums("r", listOf(AlbumInfo("camera", "Camera", 3, "9", AlbumKinds.CAMERA)))
        assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
        assertEquals(AlbumsReq("q"), MessageCodec.decode(MessageCodec.encode(AlbumsReq("q"))))
        val req = PhotosReq("x", 10, null, "camera", PhotoFilters.VIDEOS)
        assertEquals(req, MessageCodec.decode(MessageCodec.encode(req)))
        val old = MessageCodec.json.decodeFromString(PhotoItem.serializer(), """{"id":"1","name":"a.jpg"}""")
        assertEquals(0L, old.dur)
        assertFalse(old.fav)
    }

    @Test fun albumsFromMediaStoreRows() {
        val a = AlbumAccumulator()
        a.add("9", false, "DCIM/Camera/", "1", "Camera", fav = true)
        a.add("8", false, "Pictures/Screenshots/", "2", "Screenshots", fav = false)
        a.add("7", true, "DCIM/Camera/", "1", "Camera", fav = false)
        a.add("6", false, "Pictures/Instagram/", "3", "Instagram", fav = false)
        a.add("5", false, "Pictures/Instagram/", "3", "Instagram", fav = false)
        a.add("4", false, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "4", "WhatsApp Images", fav = false)
        val list = a.albums(favoritesSupported = true)
        assertEquals(listOf("all", "camera", "screenshots", "whatsapp", "videos", "favorites", "b:3"), list.map { it.id })
        assertEquals(6, list[0].count)
        assertEquals("9", list[0].cover)
        assertEquals(2, list.first { it.id == "camera" }.count)
        assertEquals("7", list.first { it.id == "videos" }.cover)
        assertEquals("Instagram", list.last().name)
        assertFalse(a.albums(favoritesSupported = false).any { it.id == "favorites" })
    }
}
