package dev.benfranklin.tether.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.features.PhoneNumbers
import dev.benfranklin.tether.core.proto.SmsAttachment
import dev.benfranklin.tether.core.proto.SmsMessage
import dev.benfranklin.tether.core.proto.SmsThread
import dev.benfranklin.tether.log.Log

/** One content-provider query; tests swap in fakes that throw, stall or return odd columns. */
fun interface ProviderQuery {
    fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor?
}

/** Contact name for a phone number, or null (no permission, no match, lookup failed). */
fun interface NameLookup {
    fun lookup(number: String?): String?
}

/** The store failed in a way the Mac should hear about ("denied", "SQLiteException", "timeout"...). */
class StoreException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/** A page of conversations plus how it was read, for diagnostics. */
data class ThreadPage(
    val threads: List<SmsThread>,
    val more: Boolean,
    /** Set when the page is empty or partial because the provider failed. */
    val error: String? = null,
    /** "conversations", "scan" or "none". */
    val path: String = "none",
)

/**
 * Reads conversations from the phone's SMS/MMS provider (needs READ_SMS; contact names need
 * READ_CONTACTS and fall back to numbers). Tether is not the default SMS app, so it never
 * writes to the provider: the system records messages Tether sends.
 *
 * Every read is bounded (rows, MMS work, contact lookups and wall time) and survives OEM
 * providers that reject `LIMIT` in the sort order, lack columns, or throw while reading.
 * All calls do I/O; run them off the main thread.
 */
@Suppress("TooManyFunctions")
class SmsStore(
    private val provider: ProviderQuery,
    private val canReadSms: () -> Boolean,
    private val names: NameLookup,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(context: Context) : this(
        { u, p, s, a, o -> context.contentResolver.query(u, p, s, a, o) },
        { granted(context, Manifest.permission.READ_SMS) },
        Contacts(context),
    )

    /** Flips to false the first time a provider rejects `LIMIT` in the sort order. */
    @Volatile var limitInOrder = true
        private set

    fun canRead(): Boolean = canReadSms()

    /**
     * Newest conversations first; [before] pages by date, [query] filters by name, number or
     * snippet. Tries the threads table first (one query), then a bounded scan of the messages.
     * Never throws: failures come back as [ThreadPage.error] with whatever was read.
     */
    fun threads(limit: Int, before: Long?, query: String?): ThreadPage {
        if (!canRead()) return ThreadPage(emptyList(), false, "sms", "none")
        val want = limit.coerceIn(1, MAX_THREADS)
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val deadline = clock() + THREADS_BUDGET_MS
        val failures = ArrayList<String>()
        for ((path, read) in listOf<Pair<String, () -> ThreadPage>>(
            "conversations" to { conversations(want, before, q, deadline) },
            "scan" to { scan(want, before, q, deadline) },
        )) {
            try {
                return read().copy(path = path)
            } catch (e: StoreException) {
                failures += "$path:${e.reason}"
                Log.w("sms.threads_path_failed", "path" to path, "reason" to e.reason, "cause" to e.cause?.javaClass?.simpleName)
                if (e.reason == DENIED) break
            }
        }
        val reason = if (failures.any { it.endsWith(":$DENIED") }) "sms" else "error:" + failures.last().substringAfter(':')
        return ThreadPage(emptyList(), false, reason, "none")
    }

    /** One page of a conversation, oldest first, ending before [before]. Throws [StoreException]. */
    fun messages(thread: Long, limit: Int, before: Long?): Pair<List<SmsMessage>, Boolean> {
        if (!canRead()) throw StoreException("sms")
        val n = limit.coerceIn(1, MAX_MESSAGES)
        val deadline = clock() + MESSAGES_BUDGET_MS
        val sms = smsRows("${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()), before, n + 1, deadline)
        val mms = try {
            mmsRows(thread, before, n + 1, deadline, MAX_MMS_DETAILS)
        } catch (e: StoreException) {
            if (e.reason == DENIED) throw e
            Log.w("sms.mms_failed", "reason" to e.reason)
            emptyList()
        }
        val rows = (sms + mms).sortedByDescending { it.date }
        val page = rows.take(n)
        return page.reversed().map { it.toMessage(names) } to (rows.size > n)
    }

    /** Messages whose text contains [query], newest first. Throws [StoreException]. */
    fun search(query: String, limit: Int): List<SmsMessage> {
        if (!canRead()) throw StoreException("sms")
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val deadline = clock() + MESSAGES_BUDGET_MS
        return smsRows("${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'", arrayOf(like), null, limit.coerceIn(1, MAX_MESSAGES), deadline)
            .map { it.toMessage(names) }
    }

    /** SMS rows changed since [sinceId] (new messages, or ours whose status moved), oldest first. */
    fun since(sinceId: Long, limit: Int = 50): List<SmsMessage> = quietly {
        smsRows("${Telephony.Sms._ID} > ?", arrayOf(sinceId.toString()), null, limit, clock() + MESSAGES_BUDGET_MS)
            .reversed().map { it.toMessage(names) }
    }.orEmpty()

    /** The newest SMS row id, so a watcher can report only what arrives later. */
    fun latestSmsId(): Long = quietly {
        rows(Telephony.Sms.CONTENT_URI, listOf(arrayOf(Telephony.Sms._ID)), null, null, "${Telephony.Sms._ID} DESC", 1,
            clock() + MESSAGES_BUDGET_MS) { it.long(Telephony.Sms._ID) }.firstOrNull()
    } ?: 0L

    /** Current state of our outgoing messages that were still pending. */
    fun byIds(ids: Collection<Long>): List<SmsMessage> {
        if (ids.isEmpty()) return emptyList()
        val sel = "${Telephony.Sms._ID} IN (${ids.joinToString(",") { "?" }})"
        return quietly {
            smsRows(sel, ids.map { it.toString() }.toTypedArray(), null, ids.size, clock() + MESSAGES_BUDGET_MS).map { it.toMessage(names) }
        }.orEmpty()
    }

    fun nameFor(number: String): String? = names.lookup(number)

    // ---------------- conversations (threads table) ----------------

    private fun conversations(want: Int, before: Long?, q: String?, deadline: Long): ThreadPage {
        val cap = if (q == null) want + 1 else MAX_FILTER_THREADS
        val heads = rows(
            CONVERSATIONS, listOf(CONV_FULL, CONV_MIN),
            before?.let { "${Telephony.Threads.DATE} < ?" }, before?.let { arrayOf(it.toString()) },
            "${Telephony.Threads.DATE} DESC", cap, deadline,
        ) { c ->
            val id = c.long(Telephony.Threads._ID) ?: return@rows null
            if (c.long(Telephony.Threads.MESSAGE_COUNT) == 0L) return@rows null // empty thread (deleted / draft only)
            ConvRow(
                id, c.long(Telephony.Threads.DATE) ?: 0L, c.str(Telephony.Threads.RECIPIENT_IDS).orEmpty(),
                c.str(Telephony.Threads.SNIPPET).orEmpty(), c.long(Telephony.Threads.HAS_ATTACHMENT) == 1L,
                c.long(Telephony.Threads.READ)?.let { it != 0L },
            )
        }
        if (heads.isEmpty()) {
            // Some OEM providers keep the threads table empty: let the message scan decide.
            if (before == null) throw StoreException("no_threads")
            return ThreadPage(emptyList(), false)
        }
        val addrs = canonicalAddresses(heads, deadline)
        val stats = latestStats(deadline)
        var more = q == null && heads.size > want
        var threads = heads.take(if (q == null) want else heads.size).map { h ->
            val a = h.recipients.split(' ').mapNotNull { it.trim().toLongOrNull()?.let(addrs::get) }.distinctNumbers()
            val st = stats[h.id]
            val unread = st?.unread ?: if (h.read == false) 1 else 0
            val snippet = h.snippet.ifEmpty { if (h.attachment) "Attachment" else "" }
            SmsThread(h.id, a, a.map { lookupName(it, deadline) }, snippet.take(SNIPPET), h.date, unread, st?.outLast ?: false)
        }
        if (q != null) {
            val all = threads.filter { matches(it, q) }
            more = all.size > want
            threads = all.take(want)
        }
        return ThreadPage(threads, more)
    }

    private data class ConvRow(
        val id: Long,
        val date: Long,
        val recipients: String,
        val snippet: String,
        val attachment: Boolean,
        val read: Boolean?,
    )

    /** canonical_addresses id -> number, for the recipients of [heads] (one query). */
    private fun canonicalAddresses(heads: List<ConvRow>, deadline: Long): Map<Long, String> {
        val wanted = heads.flatMap { h -> h.recipients.split(' ').mapNotNull { it.trim().toLongOrNull() } }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        rows(CANONICAL, listOf(arrayOf("_id", "address")), null, null, null, MAX_CANONICAL, deadline) { c ->
            val id = c.long("_id") ?: return@rows null
            if (id in wanted) c.str("address")?.takeIf { it.isNotBlank() }?.let { out[id] = it }
            Unit
        }
        if (out.isEmpty()) throw StoreException("no_addresses")
        return out
    }

    private class Stats(var unread: Int = 0, var outLast: Boolean = false, var seen: Boolean = false)

    /** Unread counts and who sent last, from the newest SMS rows (optional: failures are ignored). */
    private fun latestStats(deadline: Long): Map<Long, Stats> {
        val out = HashMap<Long, Stats>()
        quietly {
            rows(
                Telephony.Sms.CONTENT_URI,
                listOf(arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.TYPE, Telephony.Sms.READ)),
                null, null, "${Telephony.Sms.DATE} DESC", STATS_ROWS, deadline,
            ) { c ->
                val t = c.long(Telephony.Sms.THREAD_ID) ?: return@rows null
                val type = c.int(Telephony.Sms.TYPE) ?: Telephony.Sms.MESSAGE_TYPE_INBOX
                val st = out.getOrPut(t) { Stats() }
                if (!st.seen) {
                    st.seen = true
                    st.outLast = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                }
                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && c.int(Telephony.Sms.READ) == 0) st.unread++
                Unit
            }
        }
        return out
    }

    // ---------------- scan (fallback) ----------------

    private fun scan(want: Int, before: Long?, q: String?, deadline: Long): ThreadPage {
        val sms = smsRows(null, null, before, SCAN_ROWS, deadline)
        val mmsHeads = try {
            mmsRows(null, before, SCAN_MMS_ROWS, deadline, details = 0)
        } catch (e: StoreException) {
            if (e.reason == DENIED || sms.isEmpty()) throw e
            emptyList()
        }
        val byThread = LinkedHashMap<Long, ThreadAcc>()
        var more = false
        for (r in (sms + mmsHeads).sortedByDescending { it.date }) {
            val acc = byThread[r.thread]
            if (acc == null) {
                if (byThread.size >= want && q == null) {
                    more = true
                    break
                }
                byThread[r.thread] = ThreadAcc(r)
            } else {
                acc.add(r)
            }
        }
        // Only the newest MMS of each conversation needs its text and addresses (bounded).
        var details = 0
        for (acc in byThread.values) {
            if (acc.first.id.startsWith("m") && details < MAX_MMS_DETAILS && clock() < deadline) {
                details++
                quietly { acc.fill(mmsParts(acc.first.rawId, deadline), mmsAddrs(acc.first.rawId, acc.first.box, deadline)) }
            }
        }
        var threads = byThread.values.map { it.toThread { a -> lookupName(a, deadline) } }
        if (q != null) {
            threads = threads.filter { matches(it, q) }
            more = threads.size > want
            threads = threads.take(want)
        }
        return ThreadPage(threads, more)
    }

    private fun matches(t: SmsThread, q: String): Boolean =
        t.snippet.lowercase().contains(q) || t.addrs.any { it.lowercase().contains(q) } ||
            t.names.any { it?.lowercase()?.contains(q) == true }

    private fun lookupName(number: String, deadline: Long): String? =
        if (clock() < deadline) quietly { names.lookup(number) } else null

    // ---------------- rows ----------------

    private data class Row(
        val id: String,
        val rawId: Long,
        val thread: Long,
        val addrs: List<String>,
        val body: String,
        val date: Long,
        val out: Boolean,
        val status: String,
        val read: Boolean,
        val sub: Int?,
        val att: List<SmsAttachment> = emptyList(),
        val box: Int = 0,
    ) {
        fun toMessage(names: NameLookup): SmsMessage {
            val addr = addrs.firstOrNull().orEmpty()
            val name = quietly { names.lookup(addr) }
            return SmsMessage(id, thread, addr, name, body, date, out, status, sub, att, read)
        }
    }

    private class ThreadAcc(var first: Row) {
        val addrs = LinkedHashSet(first.addrs)
        var unread = if (!first.read && !first.out) 1 else 0

        fun add(r: Row) {
            addrs.addAll(r.addrs)
            if (!r.read && !r.out) unread++
        }

        fun fill(parts: Pair<String, List<SmsAttachment>>, from: List<String>) {
            first = first.copy(body = parts.first, att = parts.second, addrs = from)
            addrs.addAll(from)
        }

        fun toThread(name: (String) -> String?): SmsThread {
            val a = addrs.toList().distinctNumbers()
            val fallback = if (first.id.startsWith("m")) "Attachment" else ""
            val snippet = first.body.ifEmpty { first.att.firstOrNull()?.let { label(it.mime) } ?: fallback }
            return SmsThread(first.thread, a, a.map(name), snippet.take(SNIPPET), first.date, unread, first.out)
        }
    }

    private fun smsRows(selection: String?, args: Array<String>?, before: Long?, limit: Int, deadline: Long): List<Row> {
        val sel = listOfNotNull(selection, before?.let { "${Telephony.Sms.DATE} < $it" }).joinToString(" AND ").ifEmpty { null }
        return rows(Telephony.Sms.CONTENT_URI, listOf(SMS_FULL, SMS_MIN), sel, args, "${Telephony.Sms.DATE} DESC", limit, deadline) { c ->
            val id = c.long(Telephony.Sms._ID) ?: return@rows null
            val type = c.int(Telephony.Sms.TYPE) ?: Telephony.Sms.MESSAGE_TYPE_INBOX
            Row(
                "s$id", id, c.long(Telephony.Sms.THREAD_ID) ?: -1L, listOf(c.str(Telephony.Sms.ADDRESS).orEmpty()),
                c.str(Telephony.Sms.BODY).orEmpty(), c.long(Telephony.Sms.DATE) ?: 0L, type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                smsStatus(type), (c.int(Telephony.Sms.READ) ?: 1) != 0, c.int(Telephony.Sms.SUBSCRIPTION_ID)?.takeIf { it >= 0 },
            )
        }
    }

    /** MMS rows; text and addresses are read for at most [details] of them (each costs two queries). */
    private fun mmsRows(thread: Long?, before: Long?, limit: Int, deadline: Long, details: Int): List<Row> {
        val conds = listOfNotNull(
            thread?.let { "${Telephony.Mms.THREAD_ID} = $it" },
            before?.let { "${Telephony.Mms.DATE} < ${it / MS}" },
        )
        val heads = rows(
            Telephony.Mms.CONTENT_URI, listOf(MMS_FULL, MMS_MIN), conds.joinToString(" AND ").ifEmpty { null }, null,
            "${Telephony.Mms.DATE} DESC", limit, deadline,
        ) { c ->
            val id = c.long(Telephony.Mms._ID) ?: return@rows null
            val box = c.int(Telephony.Mms.MESSAGE_BOX) ?: Telephony.Mms.MESSAGE_BOX_INBOX
            Row(
                "m$id", id, c.long(Telephony.Mms.THREAD_ID) ?: -1L, emptyList(), "", (c.long(Telephony.Mms.DATE) ?: 0L) * MS,
                box != Telephony.Mms.MESSAGE_BOX_INBOX, mmsStatus(box), (c.int(Telephony.Mms.READ) ?: 1) != 0,
                c.int(Telephony.Mms.SUBSCRIPTION_ID)?.takeIf { it >= 0 }, box = box,
            )
        }
        return heads.mapIndexed { i, h ->
            if (i >= details || clock() >= deadline) return@mapIndexed h
            quietly {
                val (text, att) = mmsParts(h.rawId, deadline)
                h.copy(body = text, att = att, addrs = mmsAddrs(h.rawId, h.box, deadline))
            } ?: h
        }
    }

    private fun mmsParts(id: Long, deadline: Long): Pair<String, List<SmsAttachment>> {
        val text = StringBuilder()
        val att = ArrayList<SmsAttachment>()
        rows(MMS_PART, listOf(arrayOf("_id", "ct", "text")), "mid = ?", arrayOf(id.toString()), null, MAX_PARTS, deadline) { c ->
            val ct = c.str("ct").orEmpty()
            when {
                ct == "text/plain" -> c.str("text")?.let {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(it)
                }
                ct.isNotEmpty() && ct != "application/smil" -> att += SmsAttachment("p${c.long("_id") ?: 0L}", ct)
            }
            Unit
        }
        return text.toString() to att
    }

    private fun mmsAddrs(id: Long, box: Int, deadline: Long): List<String> {
        val from = ArrayList<String>()
        val to = ArrayList<String>()
        rows(Uri.parse("content://mms/$id/addr"), listOf(arrayOf("address", "type")), null, null, null, MAX_PARTS, deadline) { c ->
            val a = c.str("address")
            if (!a.isNullOrBlank() && a != "insert-address-token") {
                if (c.int("type") == PDU_FROM) from += a else to += a
            }
            Unit
        }
        // For a message we received, the sender first; the other recipients are the group.
        return if (box == Telephony.Mms.MESSAGE_BOX_INBOX) from + to else to
    }

    // ---------------- query plumbing ----------------

    /**
     * Runs a query and maps up to [limit] rows with [read] (null skips a row). Tries `LIMIT`
     * in the sort order first and drops it for good if the provider refuses; tries each
     * projection in [projections] in turn (some OEMs lack columns such as sub_id); stops
     * reading at [deadline]. A row that can't be read is skipped. Throws [StoreException]
     * ("denied" for a SecurityException) when every attempt fails.
     */
    @Suppress("LongParameterList") // mirrors ContentResolver.query plus the read bounds
    private fun <T : Any> rows(
        uri: Uri,
        projections: List<Array<String>>,
        sel: String?,
        args: Array<String>?,
        order: String?,
        limit: Int,
        deadline: Long,
        read: (Cols) -> T?,
    ): List<T> = rows(Query(uri, projections, sel, args, order), limit, deadline, read)

    /** What to ask the provider (grouped so [rows] stays readable). */
    private class Query(
        val uri: Uri,
        val projections: List<Array<String>>,
        val sel: String?,
        val args: Array<String>?,
        val order: String?,
    )

    private fun <T : Any> rows(query: Query, limit: Int, deadline: Long, read: (Cols) -> T?): List<T> {
        val uri = query.uri
        val sel = query.sel
        val args = query.args
        val order = query.order
        if (clock() >= deadline) throw StoreException(TIMEOUT)
        var last: Throwable? = null
        for (proj in query.projections) {
            val orders = if (order != null && limitInOrder) listOf("$order LIMIT $limit", order) else listOf(order)
            for ((i, o) in orders.withIndex()) {
                val cursor = try {
                    provider.query(uri, proj, sel, args, o)
                } catch (e: SecurityException) {
                    throw StoreException(DENIED, e)
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    // IllegalArgumentException / SQLiteException / UnsupportedOperationException...
                    last = e
                    if (i == 0 && orders.size > 1) {
                        limitInOrder = false
                        Log.w("sms.limit_rejected", "uri" to uri.authority, "err" to e.javaClass.simpleName)
                    }
                    continue
                }
                cursor ?: return emptyList() // provider unavailable: treat as empty, like Android does
                return cursor.use { c -> readAll(c, limit, deadline, read) }
            }
        }
        throw StoreException(last?.javaClass?.simpleName ?: "query_failed", last)
    }

    private fun <T : Any> readAll(c: Cursor, limit: Int, deadline: Long, read: (Cols) -> T?): List<T> {
        val cols = Cols(c)
        val out = ArrayList<T>(minOf(limit, ROW_CAPACITY))
        var bad = 0
        try {
            while (out.size < limit && c.moveToNext()) {
                try {
                    read(cols)?.let { out += it }
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    if (bad++ == 0) Log.w("sms.bad_row", "err" to e.javaClass.simpleName)
                }
                if (clock() >= deadline) {
                    Log.w("sms.read_budget", "rows" to out.size)
                    break
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // The cursor itself broke mid-way (window fill failed): keep what was read.
            if (out.isEmpty()) throw StoreException(e.javaClass.simpleName, e)
            Log.w("sms.cursor_failed", "rows" to out.size, "err" to e.javaClass.simpleName)
        }
        return out
    }

    /** Null-safe, name-based column access (missing column or SQL NULL -> null). */
    class Cols(private val c: Cursor) {
        private val index = HashMap<String, Int>()

        private fun at(name: String): Int? {
            val i = index.getOrPut(name) { c.getColumnIndex(name) }
            return if (i < 0 || c.isNull(i)) null else i
        }

        fun long(name: String): Long? = at(name)?.let { c.getLong(it) }
        fun int(name: String): Int? = at(name)?.let { c.getInt(it) }
        fun str(name: String): String? = at(name)?.let { c.getString(it) }
    }

    companion object {
        const val DENIED = "denied"
        const val TIMEOUT = "timeout"
        private const val SCAN_ROWS = 800
        private const val SCAN_MMS_ROWS = 150
        private const val STATS_ROWS = 400
        private const val MAX_THREADS = 200
        private const val MAX_FILTER_THREADS = 500
        private const val MAX_CANONICAL = 5000
        private const val MAX_MESSAGES = 200
        private const val MAX_MMS_DETAILS = 40
        private const val MAX_PARTS = 40
        private const val ROW_CAPACITY = 256
        private const val SNIPPET = 160
        private const val DIGITS = 10
        private const val MS = 1000L
        private const val PDU_FROM = 137
        /** Wall-time budgets; the Mac gives up after 15 s, so answer well before that. */
        const val THREADS_BUDGET_MS = 6_000L
        const val MESSAGES_BUDGET_MS = 6_000L

        private val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        private val CANONICAL: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        private val MMS_PART: Uri = Uri.parse("content://mms/part")
        private val CONV_FULL = arrayOf(
            Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.RECIPIENT_IDS, Telephony.Threads.SNIPPET,
            Telephony.Threads.READ, Telephony.Threads.MESSAGE_COUNT, Telephony.Threads.HAS_ATTACHMENT,
        )
        private val CONV_MIN = arrayOf(
            Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.RECIPIENT_IDS, Telephony.Threads.SNIPPET,
        )
        private val SMS_FULL = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID,
        )
        private val SMS_MIN = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
        )
        private val MMS_FULL = arrayOf(
            Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ,
            Telephony.Mms.SUBSCRIPTION_ID,
        )
        private val MMS_MIN = arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)

        fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

        fun smsStatus(type: Int): String = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
            Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
            else -> "pending" // outbox, queued, draft
        }

        fun mmsStatus(box: Int): String = when (box) {
            Telephony.Mms.MESSAGE_BOX_INBOX -> "received"
            Telephony.Mms.MESSAGE_BOX_SENT -> "sent"
            Telephony.Mms.MESSAGE_BOX_FAILED -> "failed"
            else -> "pending"
        }

        fun label(mime: String): String = when {
            mime.startsWith("image/") -> "Photo"
            mime.startsWith("video/") -> "Video"
            mime.startsWith("audio/") -> "Audio"
            else -> "Attachment"
        }

        /** Same number written differently (+91 98…, 098…) counts once; blanks are dropped. */
        fun List<String>.distinctNumbers(): List<String> =
            filter { it.isNotBlank() }.distinctBy { PhoneNumbers.digits(it).takeLast(DIGITS).ifEmpty { it } }

        /** Runs [block], logging and swallowing any failure (for optional reads). */
        internal inline fun <T> quietly(block: () -> T): T? = try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w("sms.optional_failed", "err" to e.javaClass.simpleName, "reason" to (e as? StoreException)?.reason)
            null
        }
    }
}

/** Contact names by number with a small cache (READ_CONTACTS optional; denied -> numbers only). */
class Contacts(private val context: Context) : NameLookup {
    private val cache = object : LinkedHashMap<String, String>(CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE
    }

    override fun lookup(number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (!SmsStore.granted(context, Manifest.permission.READ_CONTACTS)) return null
        synchronized(cache) { cache[number]?.let { return it.ifEmpty { null } } }
        val name = try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        } catch (e: SecurityException) {
            Log.w("contacts.denied", "err" to e.message)
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Log.w("contacts.failed", "err" to e.javaClass.simpleName)
            null
        }
        synchronized(cache) { cache[number] = name.orEmpty() }
        return name
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private companion object {
        const val CACHE = 512
    }
}
