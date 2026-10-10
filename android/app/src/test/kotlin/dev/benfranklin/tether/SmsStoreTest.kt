@file:Suppress("UseRequire", "UseCheckOrError") // fakes throw exactly what OEM providers throw

package dev.benfranklin.tether

import android.app.Application
import android.database.Cursor
import android.database.CursorWrapper
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.benfranklin.tether.sms.NameLookup
import dev.benfranklin.tether.sms.ProviderQuery
import dev.benfranklin.tether.sms.SmsStore
import dev.benfranklin.tether.sms.StoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SmsStore against hostile providers: OEM providers that throw, refuse LIMIT, lack columns,
 * return NULLs, break mid-cursor or stall. The store must always answer (0.3.1).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SmsStoreTest {
    private var now = 0L
    private val calls = ArrayList<String>()

    private val names = NameLookup { n -> if (n == "+919847012345") "Asha" else null }

    private fun store(canRead: Boolean = true, lookup: NameLookup = names, q: (Uri, Array<String>?, String?) -> Cursor?): SmsStore =
        SmsStore(
            ProviderQuery { uri, proj, _, _, order ->
                calls += "$uri | ${order.orEmpty()}"
                q(uri, proj, order)
            },
            { canRead }, lookup, { now },
        )

    private fun threadsCursor(): Cursor = MatrixCursor(
        arrayOf("_id", "date", "recipient_ids", "snippet", "read", "message_count", "has_attachment"),
    ).apply {
        addRow(arrayOf<Any?>(7L, 3_000L, "1", "See you at 6 🚲", 0, 4, 0))
        addRow(arrayOf<Any?>(8L, 2_000L, "2 3", null, 1, 2, 1)) // group thread, MMS-only snippet
        addRow(arrayOf<Any?>(9L, 1_000L, "4", "deleted", 1, 0, 0)) // empty thread: skipped
    }

    private fun canonicalCursor(): Cursor = MatrixCursor(arrayOf("_id", "address")).apply {
        addRow(arrayOf<Any?>(1L, "+919847012345"))
        addRow(arrayOf<Any?>(2L, "AX-HDFCBK"))
        addRow(arrayOf<Any?>(3L, null))
    }

    private fun smsCursor(rows: Int = 3): Cursor = MatrixCursor(
        arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id"),
    ).apply {
        for (i in 0 until rows) {
            val type = if (i % 2 == 0) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT
            addRow(arrayOf<Any?>(100L + i, 7L + i % 2, "+919847012345", "msg $i", 3_000L - i, type, if (i == 0) 0 else 1, -1))
        }
    }

    private fun empty(vararg cols: String): Cursor = MatrixCursor(cols)

    private fun route(uri: Uri): String = uri.toString()

    @Test
    fun readsTheThreadsTableInOneQuery() {
        val s = store { uri, _, _ ->
            when {
                route(uri).startsWith("content://mms-sms/conversations") -> threadsCursor()
                route(uri).startsWith("content://mms-sms/canonical-addresses") -> canonicalCursor()
                route(uri).startsWith("content://sms") -> smsCursor()
                else -> null
            }
        }
        val page = s.threads(40, null, null)
        assertNull(page.error)
        assertEquals("conversations", page.path)
        assertEquals(listOf(7L, 8L), page.threads.map { it.id })
        val t = page.threads[0]
        assertEquals(listOf("+919847012345"), t.addrs)
        assertEquals(listOf("Asha"), t.names)
        assertEquals("See you at 6 🚲", t.snippet)
        assertEquals(1, t.unread)
        assertEquals(listOf("AX-HDFCBK"), page.threads[1].addrs) // id 3 had a NULL address
        assertEquals("Attachment", page.threads[1].snippet)
    }

    @Test
    fun fallsBackToScanningWhenTheThreadsTableThrows() {
        val s = store { uri, _, _ ->
            when {
                route(uri).startsWith("content://mms-sms") -> throw SQLiteException("no such table: threads")
                route(uri).startsWith("content://sms") -> smsCursor(6)
                route(uri).startsWith("content://mms") -> throw UnsupportedOperationException("mms provider missing")
                else -> null
            }
        }
        val page = s.threads(40, null, null)
        assertEquals("scan", page.path)
        assertNull(page.error)
        assertEquals(setOf(7L, 8L), page.threads.map { it.id }.toSet())
    }

    @Test
    fun survivesProvidersThatRejectLimitInTheSortOrder() {
        val s = store { uri, _, order ->
            if (order?.contains("LIMIT") == true) throw IllegalArgumentException("Invalid token LIMIT")
            when {
                route(uri).startsWith("content://mms-sms/conversations") -> threadsCursor()
                route(uri).startsWith("content://mms-sms/canonical-addresses") -> canonicalCursor()
                route(uri).startsWith("content://sms") -> smsCursor()
                else -> null
            }
        }
        val page = s.threads(1, null, null)
        assertNull(page.error)
        assertEquals(1, page.threads.size) // capped in code instead
        assertTrue(page.more)
        assertFalse(s.limitInOrder)
        // Once known, LIMIT isn't tried again.
        calls.clear()
        s.threads(1, null, null)
        assertTrue(calls.none { it.contains("LIMIT") })
    }

    @Test
    fun survivesMissingColumns() {
        val s = store { uri, proj, _ ->
            if (proj?.contains("sub_id") == true || proj?.contains("message_count") == true) {
                throw IllegalArgumentException("no such column")
            }
            when {
                route(uri).startsWith("content://mms-sms/conversations") ->
                    MatrixCursor(arrayOf("_id", "date", "recipient_ids", "snippet")).apply { addRow(arrayOf<Any?>(7L, 5L, "1", "hi")) }
                route(uri).startsWith("content://mms-sms/canonical-addresses") -> canonicalCursor()
                route(uri).startsWith("content://sms") -> empty("thread_id", "type", "read")
                else -> null
            }
        }
        val page = s.threads(40, null, null)
        assertNull(page.error)
        assertEquals(1, page.threads.size)
        assertEquals("hi", page.threads[0].snippet)
    }

    @Test
    fun nullsAndJunkRowsDontCrash() {
        val s = store { uri, _, _ ->
            when {
                route(uri).startsWith("content://mms-sms") -> throw SQLiteException("nope")
                route(uri).startsWith("content://sms") -> MatrixCursor(
                    arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id"),
                ).apply {
                    addRow(arrayOf<Any?>(null, 1L, "x", "no id: skipped", 1L, 1, 1, 0))
                    addRow(arrayOf<Any?>(5L, null, null, null, null, null, null, null))
                    addRow(arrayOf<Any?>(6L, 2L, "", "", 9L, 2, 1, -1))
                }
                else -> empty("_id")
            }
        }
        val page = s.threads(40, null, null)
        assertNull(page.error)
        assertEquals(2, page.threads.size)
        assertTrue(page.threads.all { t -> t.addrs.none { it.isBlank() } })
    }

    @Test
    fun everyFailureBecomesAnAnswerNotAnException() {
        val broken = store { _, _, _ -> throw SQLiteException("disk I/O error") }
        val page = broken.threads(40, null, null)
        assertTrue(page.threads.isEmpty())
        assertEquals("error:SQLiteException", page.error)

        val denied = store { _, _, _ -> throw SecurityException("Permission Denial: reading SmsProvider") }
        assertEquals("sms", denied.threads(40, null, null).error)

        assertEquals("sms", store(canRead = false) { _, _, _ -> fail("no query without permission"); null }.threads(40, null, null).error)

        try {
            broken.messages(7, 40, null)
            fail("expected StoreException")
        } catch (e: StoreException) {
            assertEquals("SQLiteException", e.reason)
        }
    }

    @Test
    fun aCursorThatBreaksMidWayKeepsWhatWasRead() {
        val s = store { uri, _, _ ->
            when {
                route(uri).startsWith("content://mms-sms") -> throw SQLiteException("nope")
                route(uri).startsWith("content://sms") -> object : CursorWrapper(smsCursor(6)) {
                    override fun moveToNext(): Boolean {
                        if (position >= 2) throw IllegalStateException("Couldn't read row 3, col 0 from CursorWindow")
                        return super.moveToNext()
                    }
                }
                else -> null
            }
        }
        val page = s.threads(40, null, null)
        assertNull(page.error)
        assertTrue(page.threads.isNotEmpty())
    }

    @Test
    fun aStallingProviderIsCutOffByTheBudget() {
        val s = store { uri, _, _ ->
            now += 4_000 // every query takes 4 s
            when {
                route(uri).startsWith("content://mms-sms/conversations") -> threadsCursor()
                route(uri).startsWith("content://mms-sms/canonical-addresses") -> canonicalCursor()
                route(uri).startsWith("content://sms") -> smsCursor()
                else -> null
            }
        }
        val started = now
        val page = s.threads(40, null, null)
        // Either a partial list or a timeout error, never a hang: at most a few queries ran.
        assertTrue(page.threads.isNotEmpty() || page.error?.startsWith("error:") == true)
        assertTrue("took ${now - started} ms", now - started <= SmsStore.THREADS_BUDGET_MS + 3 * 4_000)
    }

    @Test
    fun contactLookupFailuresFallBackToNumbers() {
        val s = store(lookup = { throw IllegalStateException("contacts provider died") }) { uri, _, _ ->
            when {
                route(uri).startsWith("content://mms-sms/conversations") -> threadsCursor()
                route(uri).startsWith("content://mms-sms/canonical-addresses") -> canonicalCursor()
                else -> null
            }
        }
        val page = s.threads(40, null, null)
        assertNull(page.error)
        assertEquals(listOf<String?>(null), page.threads[0].names)
    }

    @Test
    fun conversationSurvivesBrokenMms() {
        val s = store { uri, _, _ ->
            when {
                route(uri).startsWith("content://sms") -> smsCursor(4)
                route(uri).startsWith("content://mms") -> throw NullPointerException("OEM bug")
                else -> null
            }
        }
        val (msgs, _) = s.messages(7, 40, null)
        assertEquals(4, msgs.size)
        assertEquals(msgs.sortedBy { it.date }, msgs) // oldest first
        assertNull(msgs.first().sub) // -1 sub id means none
    }
}
