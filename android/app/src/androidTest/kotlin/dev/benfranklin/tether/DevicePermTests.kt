package dev.benfranklin.tether

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.benfranklin.tether.core.features.OtpDetector
import dev.benfranklin.tether.core.proto.FsRoots
import dev.benfranklin.tether.v5.PhoneFiles
import dev.benfranklin.tether.v5.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 0.5 permission paths on a real Android runtime (CI emulator, API 34): the setup checklist
 * follows runtime-permission grants and special app ops (overlay, installs, DND), the Mac's file
 * browser lists Downloads with All files access (granted by the driver before this runs), and the photos
 * browser lists and thumbnails a new MediaStore image once the photos permission is granted.
 */
@RunWith(AndroidJUnit4::class)
class DevicePermTests {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val pkg = ctx.packageName

    private fun sh(cmd: String): String =
        ui.executeShellCommand(cmd).use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes().toString(Charsets.UTF_8) }

    private fun row(id: String) = Setup.items(ctx).first { it.id == id }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return cond()
    }

    @Test
    fun checklistFollowsRuntimePermissions() {
        // Revoking a runtime permission kills the app (and this instrumentation), so only grants
        // are exercised here; the driver installs with every runtime permission granted (-g).
        ui.grantRuntimePermission(pkg, Manifest.permission.READ_CONTACTS)
        assertTrue(row("contacts").ok)
        // Every row has a label, a reason and a way to fix it.
        for (i in Setup.items(ctx)) {
            assertTrue(i.id, i.label.isNotBlank() && i.why.isNotBlank())
            assertNotNull(i.id, i.fix)
        }
        assertTrue(Setup.items(ctx).any { it.required })
        assertEquals(Setup.items(ctx).size, Setup.perms(ctx).size)
    }

    @Test
    fun fileBrowserNeedsAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = PhoneFiles(ctx)
        // Toggling MANAGE_EXTERNAL_STORAGE kills the app process, so the driver grants it before this
        // instrumentation starts (scripts/emulator-tests.sh). Without it, the browser must refuse.
        if (!Setup.allFilesAccess(ctx)) {
            assertFalse(row("allfiles").ok)
            assertEquals(PhoneFiles.NEEDS_ALL_FILES, files.list("r1", FsRoots.DOWNLOADS, "", 0, 50).needs)
            throw AssertionError("All files access was not granted by the driver")
        }
        assertTrue(row("allfiles").ok)
        // Written from this (app) process: UiAutomation shell commands don't support redirection.
        File(dl, "TetherTest").apply { mkdirs() }.resolve("note.txt").writeText("hello\n")
        val root = files.list("r2", FsRoots.DOWNLOADS, "", 0, 500)
        assertEquals(null, root.needs)
        assertTrue(root.entries.any { it.name == "TetherTest" && it.dir })
        val sub = files.list("r3", FsRoots.DOWNLOADS, "TetherTest", 0, 50)
        val note = sub.entries.single { it.name == "note.txt" }
        assertEquals(6L, note.size)
        assertEquals("text/plain", note.mime)
        // Escaping the root is refused.
        assertTrue(files.list("r4", FsRoots.DOWNLOADS, "../..", 0, 50).needs!!.startsWith("error:"))
        val (uri, needs) = files.uriFor(FsRoots.DOWNLOADS, "TetherTest/note.txt")
        assertEquals(null, needs)
        assertEquals("hello\n", ctx.contentResolver.openInputStream(uri!!)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        sh("rm -rf ${dl.path}/TetherTest")
    }

    @Test
    fun photosBrowserListsAndThumbnailsNewImages() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val files = PhoneFiles(ctx)
        ui.grantRuntimePermission(pkg, perm)
        assertTrue(Setup.photosAccess(ctx))
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "tether-perm-test.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TetherTest")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        try {
            val page = files.photos("p1", 20, null)
            val item = page.items.firstOrNull { it.name == "tether-perm-test.jpg" }
            assertNotNull("new photo listed: ${page.items.map { it.name }}", item)
            val (thumb, reason) = files.thumbnail(FsRoots.PHOTOS, item!!.id, 128, 48 * 1024)
            assertNotNull("thumbnail ($reason)", thumb)
            assertTrue(thumb!!.size in 100..(48 * 1024))
            assertEquals(0xFF.toByte(), thumb[0]) // JPEG SOI
        } finally {
            ctx.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun specialAccessRowsReflectAppOps() {
        // Changing these app ops kills the app process (Android restarts apps when "Install
        // unknown apps" / "Display over other apps" change), so the driver (emulator-tests.sh
        // perm_tests) allows both before this process starts; here we check the rows see them.
        assertTrue(waitFor(5_000) { row("overlay").ok })
        assertTrue(waitFor(5_000) { row("install").ok })
        // DND access is granted with cmd notification.
        sh("cmd notification allow_dnd $pkg")
        assertTrue(waitFor(5_000) { row("dnd").ok })
        sh("cmd notification disallow_dnd $pkg")
        assertTrue(waitFor(5_000) { !row("dnd").ok })
    }

    @Test
    fun otpDetectorOnDevice() {
        // The same detector the SMS and notification paths use, on the device's regex engine (ICU).
        assertEquals("482913", OtpDetector.find("OTP for txn of Rs 2,500.00 on card XX1234 is 482913. -HDFC Bank"))
        assertEquals("551234", OtpDetector.find("G-551234 is your Google verification code."))
        assertEquals(null, OtpDetector.find("Rs. 500.00 debited from A/c XX5678. UPI Ref 627384950112"))
        File(ctx.cacheDir, "x").delete()
    }
}
