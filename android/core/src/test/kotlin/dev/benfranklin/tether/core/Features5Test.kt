package dev.benfranklin.tether.core

import dev.benfranklin.tether.core.features.ContinueGate
import dev.benfranklin.tether.core.features.DndSync
import dev.benfranklin.tether.core.features.FsPaths
import dev.benfranklin.tether.core.features.OtpDetector
import dev.benfranklin.tether.core.features.TypeEditor
import dev.benfranklin.tether.core.features.UpdateFeed
import dev.benfranklin.tether.core.features.Versions
import dev.benfranklin.tether.core.proto.Caps
import dev.benfranklin.tether.core.proto.Caps5
import dev.benfranklin.tether.core.proto.ContinueCard
import dev.benfranklin.tether.core.proto.ContinueKinds
import dev.benfranklin.tether.core.proto.DndState
import dev.benfranklin.tether.core.proto.FsEntry
import dev.benfranklin.tether.core.proto.FsGet
import dev.benfranklin.tether.core.proto.FsGetResult
import dev.benfranklin.tether.core.proto.FsList
import dev.benfranklin.tether.core.proto.FsListReq
import dev.benfranklin.tether.core.proto.FsRoots
import dev.benfranklin.tether.core.proto.Message
import dev.benfranklin.tether.core.proto.MessageCodec
import dev.benfranklin.tether.core.proto.MsgType
import dev.benfranklin.tether.core.proto.OpenOnPhone
import dev.benfranklin.tether.core.proto.OpenResult
import dev.benfranklin.tether.core.proto.Otp
import dev.benfranklin.tether.core.proto.PermInfo
import dev.benfranklin.tether.core.proto.PhoneStatus
import dev.benfranklin.tether.core.proto.PhoneStatusReq
import dev.benfranklin.tether.core.proto.PhotoItem
import dev.benfranklin.tether.core.proto.Photos
import dev.benfranklin.tether.core.proto.PhotosReq
import dev.benfranklin.tether.core.proto.ProtocolException
import dev.benfranklin.tether.core.proto.Thumb
import dev.benfranklin.tether.core.proto.ThumbReq
import dev.benfranklin.tether.core.proto.TypeResult
import dev.benfranklin.tether.core.proto.TypeText
import dev.benfranklin.tether.core.proto.UnknownMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Every revision-5 message, shared with the interop and soak tests. */
object Rev5Samples {
    val jpeg = ByteArray(9000) { (it * 31 % 251).toByte() }

    fun all(): List<Message> = listOf(
        Otp("482913", "HDFC Bank", "sms", 1),
        FsListReq("l1", FsRoots.DOWNLOADS, "Telegram/Docs", 200, 100),
        FsList(
            "l1", FsRoots.WHATSAPP, "WhatsApp Images",
            (0 until 150).map { FsEntry("IMG-2026101$it-WA00$it.jpg — ✓", it % 10 == 0, it * 1000L, 1_700_000_000_000 + it, "image/jpeg") },
            total = 400, more = true,
        ),
        FsList("l2", FsRoots.DOCUMENTS, "", emptyList(), needs = "allfiles"),
        FsGet("g1", FsRoots.PHOTOS, "1001"), FsGetResult("g1", true, "t-1", "IMG_1.jpg", 3_100_000),
        FsGetResult("g2", false, reason = "missing"),
        ThumbReq("t1", FsRoots.PHOTOS, "1001", 256), Thumb("t1", true, jpeg), Thumb("t2", false, ByteArray(0), "unsupported"),
        PhotosReq("p1", 60, 1_700_000_000_000),
        Photos("p1", (0 until 60).map { PhotoItem("${1000 + it}", "IMG_$it.jpg", 2_000_000, 1_700_000_000_000 - it, 4000, 3000) }, true),
        Photos("p2", emptyList(), needs = "photos"),
        ContinueCard(
            "tab", ContinueKinds.TAB, "Placement drive: Armada 2027", "careers.armada.ai/apply", "https://careers.armada.ai/apply",
            app = "Chrome", ts = 5, ttl = 600,
        ),
        ContinueCard(
            "photo-1001", ContinueKinds.PHOTO, "IMG_20261010_151502.jpg", "Photo · 3.1 MB", root = FsRoots.PHOTOS, path = "1001",
            size = 3_100_000, ts = 6, thumb = jpeg,
        ),
        OpenOnPhone("o1", url = "https://maps.google.com/?q=MEC+Kochi"), OpenOnPhone("o2", text = "Hello from the Mac ✓"),
        OpenResult("o1", true, "activity"), OpenResult("o3", false, reason = "no_app"),
        TypeText(1, "Hello, ✓ 😀"), TypeText(2, key = "backspace"), TypeResult(1, true, field = "Chrome · Search"),
        TypeResult(2, false, "no_field"),
        DndState(true, "mac", 7, canSet = false), DndState(false, "phone", 8, true),
        PhoneStatus(listOf(PermInfo("notif", "Notification access", true, true), PermInfo("allfiles", "All files access", false)), 36, 9),
        PhoneStatusReq(10),
    )
}

class Features5CodecTest {
    @Test fun everyRevision5MessageRoundTrips() {
        for (m in Rev5Samples.all()) assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }

    @Test fun typesAreDistinctAndNew() {
        val types = Rev5Samples.all().map { it.type }.toSet()
        assertTrue(types.containsAll(listOf(MsgType.OTP, MsgType.FS_LIST, MsgType.CONTINUE, MsgType.PHONE_STATUS_REQ)))
        // Never reuse a revision 1-4 number.
        val old = listOf(0x10, 0x11, 0x20, 0x21, 0x22, 0x23, 0x24, 0x30, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45) +
            (0x50..0x57) + (0x60..0x62) + (0x70..0x73) + (0x80..0x85) + (0x90..0x98) + (0xA0..0xA8) + (0xB0..0xB2) + (0xD0..0xD1)
        assertTrue(types.none { it in old })
    }

    @Test fun capsAreAnnouncedAndOldCapsKept() {
        assertTrue(Caps.CURRENT.containsAll(Caps5.ALL))
        assertTrue(Caps.CURRENT.containsAll(Caps.V1))
        assertEquals(Caps.CURRENT.size, Caps.CURRENT.toSet().size)
    }

    @Test fun thumbnailBodiesAreBounded() {
        val big = Thumb("x", true, ByteArray(MessageCodec.MAX_THUMB_BYTES + 1))
        try {
            MessageCodec.decode(MessageCodec.encode(big))
            throw AssertionError("expected a ProtocolException")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test fun unknownOptionalFieldsAreIgnoredAndMissingOnesDefault() {
        val header = """{"ref":"l","root":"downloads","path":"","entries":[{"name":"a","future":1}],"newField":true}"""
        val bytes = ByteBuffer.allocate(5 + header.length).put(MsgType.FS_LIST.toByte()).putInt(header.length)
            .put(header.toByteArray()).array()
        val m = MessageCodec.decode(bytes) as FsList
        assertEquals(1, m.entries.size)
        assertFalse(m.entries[0].dir)
        assertNull(m.needs)
    }

    @Test fun anOldDecoderSeesUnknownTypes() {
        // A type above every revision decodes as UnknownMessage (what a 0.4 build does with rev 5).
        val bytes = ByteBuffer.allocate(7).put(0xEF.toByte()).putInt(2).put("{}".toByteArray()).array()
        assertTrue(MessageCodec.decode(bytes) is UnknownMessage)
    }
}

class OtpDetectorTest {
    private fun otp(s: String?) = OtpDetector.find(s)

    @Test fun indianBankAndUpiFormats() {
        assertEquals("123456", otp("123456 is your OTP for login to Paytm. Do not share it with anyone."))
        assertEquals(
            "482913",
            otp("Dear Customer, OTP for txn of Rs 2,500.00 at AMAZON on card XX1234 is 482913. Valid for 10 mins. Do not share. - HDFC Bank"),
        )
        assertEquals("7731", otp("Your UPI PIN reset OTP is 7731. -SBI"))
        assertEquals("908172", otp("Use 908172 as your one time password for ICICI Bank NetBanking. Never share it."))
        assertEquals("551122", otp("Transaction of INR 4,999 at FLIPKART: OTP 551122. Do not share."))
        assertEquals("12345678", otp("OTP: 12345678 for Aadhaar authentication (valid 10 min)."))
        assertEquals("664422", otp("664422 is OTP to set UPI PIN for A/c XX9012 on BHIM. OTP valid for 5 mins. -Axis Bank"))
        assertEquals(
            "301122",
            otp("Rs.1,250.00 to be debited from A/c no. XXXXXX4567 for UPI mandate. OTP is 301122. Ref No 123456789012"),
        )
    }

    @Test fun appFormats() {
        assertEquals("551234", otp("G-551234 is your Google verification code."))
        assertEquals("123456", otp("Your WhatsApp code: 123-456\nDon't share this code with others"))
        assertEquals("654321", otp("Amazon: 654321 is your one-time password (OTP). Do not share it."))
        assertEquals("334455", otp("Your login code for Instagram is 334455"))
        assertEquals("4455", otp("Call 9876543210 for help. Your OTP is 4455"))
        assertEquals("789012", otp("Dear user, 789012 is the OTP to verify your mobile number +91 98765 43210 on Flipkart"))
        assertEquals("123456", otp("Your OTP is 123456 and is valid till 10:45 AM"))
        assertEquals("2024", otp("Your Zomato OTP is 2024"))
        assertEquals("8812", otp("Telegram code: 8812"))
        assertEquals("990011", otp("Microsoft account security code: 990011"))
    }

    @Test fun ignoresAmountsAccountsPhonesAndTextsWithoutACode() {
        assertNull(otp("Rs. 500.00 debited from A/c XX5678 on 10-10-26. UPI Ref 627384950112. Not you? Call 18001234"))
        assertNull(otp("Your Swiggy order #4512 is on the way"))
        assertNull(otp("Rs 1499 spent on your card ending 4321. Bank never asks for OTP."))
        assertNull(otp("₹250 cashback! Use code FLAT250 on your next order"))
        assertNull(otp("Meeting at 10:30 in room 4021"))
        assertNull(otp("Your OTP has expired. Request a new one."))
        assertNull(otp(""))
        assertNull(otp(null))
        assertNull(otp("Call us on +91 98470 12345 for your PIN"))
    }
}

class Features5LogicTest {
    @Test fun fsPathsRefuseTraversal() {
        assertEquals("a/b", FsPaths.clean("/a//b/"))
        assertEquals("", FsPaths.clean(""))
        assertEquals("a/b", FsPaths.clean("./a/./b"))
        assertNull(FsPaths.clean("../etc"))
        assertNull(FsPaths.clean("a/../../b"))
        assertNull(FsPaths.clean("a\\b"))
        assertNull(FsPaths.clean("a\u0000b"))
        assertEquals("x/y", FsPaths.child("x", "y"))
        assertEquals("y", FsPaths.child("", "y"))
        assertEquals("x", FsPaths.parent("x/y"))
        assertEquals("", FsPaths.parent("y"))
        val sorted = FsPaths.sorted(listOf("b.txt" to false, "Z" to true, "a.txt" to false, "c" to true), { it.second }, { it.first })
        assertEquals(listOf("c", "Z", "a.txt", "b.txt"), sorted.map { it.first })
    }

    @Test fun typeEditorBehavesLikeAKeyboard() {
        assertEquals(TypeEditor.Result("hello", 5), TypeEditor.apply("hell", null, "o", null))
        assertEquals(TypeEditor.Result("hXello", 2), TypeEditor.apply("hello", 1, "X", null))
        assertEquals(TypeEditor.Result("hell", 4), TypeEditor.apply("hello", null, null, "backspace"))
        assertEquals(TypeEditor.Result("", 0), TypeEditor.apply("", null, null, "backspace"))
        assertEquals(TypeEditor.Result("a", 1), TypeEditor.apply("a😀", null, null, "backspace"))
        assertEquals(TypeEditor.Result("", 0), TypeEditor.apply("abc", 2, null, "clear"))
        assertEquals(TypeEditor.Result("a\tb", 2), TypeEditor.apply("ab", 1, null, "tab"))
        assertEquals(TypeEditor.Result("abc", 3), TypeEditor.apply("ab", 99, "c", null))
    }

    @Test fun dndSyncNeverPingPongs() {
        val s = DndSync(echoWindowMs = 4000)
        assertTrue(s.local(false, 0)) // first report goes out
        assertFalse(s.local(false, 10)) // unchanged
        assertTrue(s.remote(true, 100)) // peer turned on → apply
        assertFalse(s.local(true, 200)) // our change caused by the peer is not echoed
        assertFalse(s.remote(true, 300)) // already on
        assertTrue(s.local(false, 10_000)) // the user turned it off here → send
        s.reset()
        assertTrue(s.local(false, 20_000))
    }

    @Test fun continueGateDedupes() {
        val g = ContinueGate(windowMs = 1000, max = 2)
        assertTrue(g.offer("a", 0))
        assertFalse(g.offer("a", 500))
        assertTrue(g.offer("a", 1600))
        assertTrue(g.offer("b", 1700))
        assertTrue(g.offer("c", 1800)) // evicts the oldest
        assertTrue(g.offer("a", 1900))
    }

    @Test fun versionsCompare() {
        assertTrue(Versions.newer("0.5.0", "0.4.3"))
        assertTrue(Versions.newer("v0.5.1", "0.5.0"))
        assertTrue(Versions.newer("0.5.0", "0.5.0-dev"))
        assertTrue(Versions.newer("0.4.3", "0.0.0-ci.120"))
        assertFalse(Versions.newer("0.5.0", "0.5.0"))
        assertFalse(Versions.newer("0.4.10", "0.10.0"))
        assertTrue(Versions.newer("0.10.0", "0.9.9"))
        assertEquals(0, Versions.compare("1.0", "1.0.0"))
    }

    @Test fun parsesTetherFeed() {
        val feed = """
            {"schema":1,"version":"0.5.0","notes":"Big one","url":"https://github.com/Benfranklinms/Tether-releases/releases/tag/v0.5.0",
             "published":"2026-10-10T12:00:00Z",
             "android":{"url":"https://github.com/x/Tether-0.5.0.apk","sha256":"ab12","size":3400000,"versionCode":51,"cert":"661e"},
             "mac":{"url":"https://github.com/x/Tether-0.5.0.zip","sha256":"CD34","size":4000000,"dmg":"https://github.com/x/Tether-0.5.0.dmg"},
             "future":{"x":1}}
        """.trimIndent()
        val u = UpdateFeed.parse(feed)
        assertNotNull(u)
        u!!
        assertEquals("0.5.0", u.version)
        assertEquals(51L, u.android!!.versionCode)
        assertEquals("661e", u.android!!.cert)
        assertEquals("cd34", u.macZip!!.sha256)
        assertEquals("https://github.com/x/Tether-0.5.0.dmg", u.macDmg!!.url)
        assertNull(UpdateFeed.parse("{\"version\":\"\"}"))
        assertNull(UpdateFeed.parse("not json"))
        // Plain-http downloads are refused.
        assertNull(UpdateFeed.parse("""{"version":"1.0","android":{"url":"http://evil/x.apk"}}""")!!.android)
    }

    @Test fun parsesGitHubLatestRelease() {
        val gh = """
            {"tag_name":"v0.5.0","name":"Tether 0.5.0","draft":false,"prerelease":false,"body":"Notes",
             "html_url":"https://github.com/Benfranklinms/Tether/releases/tag/v0.5.0","published_at":"2026-10-10T12:00:00Z",
             "assets":[
               {"name":"Tether-0.5.0.apk","size":3400000,"digest":"sha256:AABB","browser_download_url":"https://github.com/d/Tether-0.5.0.apk"},
               {"name":"Tether-0.5.0.zip","size":4000000,"browser_download_url":"https://github.com/d/Tether-0.5.0.zip"},
               {"name":"Tether-0.5.0.dmg","size":4100000,"browser_download_url":"https://github.com/d/Tether-0.5.0.dmg"}]}
        """.trimIndent()
        val u = UpdateFeed.parse(gh)!!
        assertEquals("0.5.0", u.version)
        assertEquals("aabb", u.android!!.sha256)
        assertNull(u.macZip!!.sha256)
        assertEquals(4_000_000L, u.macZip!!.size)
        assertNull(UpdateFeed.parse("""{"tag_name":"v9","prerelease":true,"assets":[]}"""))
        assertNull(UpdateFeed.parse("""{"message":"Not Found","documentation_url":"https://docs.github.com"}"""))
    }
}
