package dev.benfranklin.tether

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.util.Hex
import dev.benfranklin.tether.media.MediaKinds
import dev.benfranklin.tether.media.MediaWatcher
import dev.benfranklin.tether.notify.NotificationActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What a messaging app's reply receiver would get. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()
        received.offer(text ?: "<none>")
    }

    companion object {
        const val KEY = "key_text_reply"
        const val ACTION = "dev.benfranklin.tether.test.REPLY"
        val received = LinkedBlockingQueue<String>()
    }
}

/**
 * On a real Android runtime (CI emulator): the reply path fires an app's RemoteInput
 * PendingIntent exactly like the notification shade, and the MediaStore watcher picks up a
 * new screenshot, hashes it and queues it once.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(MediaWatcher.permission())

    // Instrumented tests run in the app's process, so the "messaging app" is a receiver
    // registered here for a private action, reached through PendingIntents like a real app's.
    private val testCtx: Context = ctx

    private fun replyIntent() = Intent(ReplyReceiver.ACTION).setPackage(ctx.packageName)

    private fun messagingNotification(): Notification {
        val pi = PendingIntent.getBroadcast(
            testCtx, 1, replyIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val read = PendingIntent.getBroadcast(
            testCtx, 2, replyIntent().putExtra("read", true), PendingIntent.FLAG_IMMUTABLE,
        )
        val input = RemoteInput.Builder(ReplyReceiver.KEY).setLabel("Reply").build()
        val reply = Notification.Action.Builder(null, "Reply", pi).addRemoteInput(input).build()
        val markRead = Notification.Action.Builder(null, "Mark as read", read).build()
        return Notification.Builder(testCtx, "alerts").setContentTitle("Asha").setContentText("Lunch?")
            .setSmallIcon(android.R.drawable.ic_dialog_info).addAction(markRead).addAction(reply).build()
    }

    @Test
    fun replyFiresRemoteInputWithText() {
        ReplyReceiver.received.clear()
        val receiver = ReplyReceiver()
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, receiver, android.content.IntentFilter(ReplyReceiver.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            assertReplyPath()
        } finally {
            ctx.unregisterReceiver(receiver)
        }
    }

    private fun assertReplyPath() {
        val actions = NotificationActions.extract(messagingNotification())
        assertEquals(2, actions.size)
        assertTrue("reply first", actions[0].reply)
        assertEquals("Mark as read", actions[1].title)
        NotificationActions.register("k1", actions)
        val r = NotificationActions.perform(ctx, "k1", actions[0].id, "On my way 🚲")
        assertTrue(r.reason ?: "", r.ok)
        assertEquals("On my way 🚲", ReplyReceiver.received.poll(BROADCAST_S, TimeUnit.SECONDS))
        // Plain button fires too; empty replies and unknown keys are refused.
        assertTrue(NotificationActions.perform(ctx, "k1", actions[1].id, null).ok)
        assertNotNull(ReplyReceiver.received.poll(BROADCAST_S, TimeUnit.SECONDS))
        assertFalse(NotificationActions.perform(ctx, "k1", actions[0].id, "  ").ok)
        assertEquals("gone", NotificationActions.perform(ctx, "nope", "reply0", "x").reason)
    }

    @Test
    fun mediaWatcherQueuesNewScreenshotOnce() {
        File(ctx.filesDir, "media_ledger.json").delete()
        val queued = LinkedBlockingQueue<TransferRecord>()
        val watcher = MediaWatcher(ctx) { queued.offer(it) }
        watcher.configure(MediaWatcher.Prefs(screenshots = true, photos = false))
        // DATE_ADDED has 1 s resolution and the start second counts as "old".
        Thread.sleep(1_500)
        val png = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(9, 9, 10)) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_tether_test_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(png) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val rec = queued.poll(30, TimeUnit.SECONDS)
            assertNotNull("screenshot was not queued", rec)
            assertEquals(MediaKinds.SCREENSHOT, rec!!.kind)
            assertFalse(rec.wifiOnly)
            assertEquals(Hex.encode(MessageDigest.getInstance("SHA-256").digest(png)), rec.sha256)
            // A re-scan doesn't queue it again.
            watcher.poke()
            Thread.sleep(4_000)
            assertEquals(null, queued.poll())
        } finally {
            ctx.contentResolver.delete(uri, null, null)
            watcher.configure(MediaWatcher.Prefs(screenshots = false, photos = false))
        }
    }

    private companion object {
        /** Broadcast delivery lags on a busy CI emulator; on a phone it's milliseconds. */
        const val BROADCAST_S = 30L
    }
}
