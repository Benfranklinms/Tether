package dev.benfranklin.tether.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.features.PhoneNumbers
import dev.benfranklin.tether.core.proto.SmsAttachment
import dev.benfranklin.tether.core.proto.SmsMessage
import dev.benfranklin.tether.core.proto.SmsThread
import dev.benfranklin.tether.log.Log

/**
 * Reads conversations from the phone's SMS/MMS provider (needs READ_SMS; contact names need
 * READ_CONTACTS and fall back to numbers). Tether is not the default SMS app, so it never
 * writes to the provider: the system records messages Tether sends.
 *
 * All calls do I/O; run them off the main thread.
 */
class SmsStore(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val names = Contacts(context)

    fun canRead(): Boolean = granted(context, Manifest.permission.READ_SMS)

    /** Newest conversations first; [before] pages by date, [query] filters by name, number or snippet. */
    fun threads(limit: Int, before: Long?, query: String?): Pair<List<SmsThread>, Boolean> {
        val byThread = LinkedHashMap<Long, ThreadAcc>()
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val want = limit.coerceIn(1, MAX_THREADS)
        val rows = (smsRows(null, null, before, SCAN_ROWS) + mmsRows(null, before, SCAN_MMS_ROWS))
            .sortedByDescending { it.date }
        var more = false
        for (r in rows) {
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
        var threads = byThread.values.map { it.toThread(names) }
        if (q != null) {
            threads = threads.filter { t ->
                t.snippet.lowercase().contains(q) || t.addrs.any { it.lowercase().contains(q) } ||
                    t.names.any { it?.lowercase()?.contains(q) == true }
            }
            more = threads.size > want
            threads = threads.take(want)
        }
        return threads to more
    }

    /** One page of a conversation, oldest first, ending before [before]. */
    fun messages(thread: Long, limit: Int, before: Long?): Pair<List<SmsMessage>, Boolean> {
        val n = limit.coerceIn(1, MAX_MESSAGES)
        val rows = (smsRows("${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()), before, n + 1) +
            mmsRows(thread, before, n + 1)).sortedByDescending { it.date }
        val page = rows.take(n)
        return page.reversed().map { it.toMessage(names) } to (rows.size > n)
    }

    /** Messages whose text contains [query], newest first. */
    fun search(query: String, limit: Int): List<SmsMessage> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return smsRows("${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'", arrayOf(like), null, limit.coerceIn(1, MAX_MESSAGES))
            .map { it.toMessage(names) }
    }

    /** SMS rows changed since [sinceId] (new messages, or ours whose status moved), oldest first. */
    fun since(sinceId: Long, limit: Int = 50): List<SmsMessage> =
        smsRows("${Telephony.Sms._ID} > ?", arrayOf(sinceId.toString()), null, limit).reversed().map { it.toMessage(names) }

    /** The newest SMS row id, so a watcher can report only what arrives later. */
    fun latestSmsId(): Long =
        query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, "${Telephony.Sms._ID} DESC LIMIT 1") {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L

    /** Current state of our outgoing messages that were still pending. */
    fun byIds(ids: Collection<Long>): List<SmsMessage> {
        if (ids.isEmpty()) return emptyList()
        val sel = "${Telephony.Sms._ID} IN (${ids.joinToString(",") { "?" }})"
        return smsRows(sel, ids.map { it.toString() }.toTypedArray(), null, ids.size).map { it.toMessage(names) }
    }

    fun nameFor(number: String): String? = names.lookup(number)

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
    ) {
        fun toMessage(names: Contacts): SmsMessage {
            val addr = addrs.firstOrNull().orEmpty()
            return SmsMessage(
                id, thread, addr, names.lookup(addr), body, date, out, status, sub, att, read,
            )
        }
    }

    private class ThreadAcc(first: Row) {
        val id = first.thread
        val addrs = LinkedHashSet(first.addrs)
        val snippet = first.body.ifEmpty { first.att.firstOrNull()?.let { label(it.mime) } ?: "" }
        val date = first.date
        val outLast = first.out
        var unread = if (!first.read && !first.out) 1 else 0

        fun add(r: Row) {
            addrs.addAll(r.addrs)
            if (!r.read && !r.out) unread++
        }

        fun toThread(names: Contacts): SmsThread {
            val a = addrs.filter { it.isNotBlank() }.distinctBy { PhoneNumbers.digits(it).takeLast(DIGITS).ifEmpty { it } }
            return SmsThread(id, a, a.map { names.lookup(it) }, snippet.take(SNIPPET), date, unread, outLast)
        }
    }

    // Cursor column indices below follow each query's projection.
    @Suppress("MagicNumber")
    private fun smsRows(selection: String?, args: Array<String>?, before: Long?, limit: Int): List<Row> {
        if (!canRead()) return emptyList()
        val sel = listOfNotNull(selection, before?.let { "${Telephony.Sms.DATE} < $it" }).joinToString(" AND ").ifEmpty { null }
        val proj = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID,
        )
        return query(Telephony.Sms.CONTENT_URI, proj, sel, args, "${Telephony.Sms.DATE} DESC LIMIT $limit") { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) {
                val type = c.getInt(5)
                val id = c.getLong(0)
                out += Row(
                    "s$id", id, c.getLong(1), listOf(c.getString(2).orEmpty()), c.getString(3).orEmpty(), c.getLong(4),
                    type != Telephony.Sms.MESSAGE_TYPE_INBOX, smsStatus(type), c.getInt(6) != 0, c.getInt(7).takeIf { it >= 0 },
                )
            }
            out
        } ?: emptyList()
    }

    @Suppress("MagicNumber")
    private fun mmsRows(thread: Long?, before: Long?, limit: Int): List<Row> {
        if (!canRead()) return emptyList()
        val conds = listOfNotNull(
            thread?.let { "${Telephony.Mms.THREAD_ID} = $it" },
            before?.let { "${Telephony.Mms.DATE} < ${it / MS}" },
        )
        val proj = arrayOf(
            Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ,
            Telephony.Mms.SUBSCRIPTION_ID,
        )
        val heads = query(
            Telephony.Mms.CONTENT_URI, proj, conds.joinToString(" AND ").ifEmpty { null }, null,
            "${Telephony.Mms.DATE} DESC LIMIT $limit",
        ) { c ->
            val out = ArrayList<LongArray>(c.count)
            while (c.moveToNext()) {
                out += longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))
            }
            out
        } ?: return emptyList()
        return heads.map { h ->
            val id = h[0]
            val box = h[3].toInt()
            val (text, att) = mmsParts(id)
            Row(
                "m$id", id, h[1], mmsAddrs(id, box), text, h[2] * MS, box != Telephony.Mms.MESSAGE_BOX_INBOX,
                when (box) {
                    Telephony.Mms.MESSAGE_BOX_INBOX -> "received"
                    Telephony.Mms.MESSAGE_BOX_SENT -> "sent"
                    Telephony.Mms.MESSAGE_BOX_FAILED -> "failed"
                    else -> "pending"
                },
                h[4] != 0L, h[5].toInt().takeIf { it >= 0 }, att,
            )
        }
    }

    @Suppress("MagicNumber")
    private fun mmsParts(id: Long): Pair<String, List<SmsAttachment>> {
        val text = StringBuilder()
        val att = ArrayList<SmsAttachment>()
        query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(id.toString()), null) { c ->
            while (c.moveToNext()) {
                val ct = c.getString(1).orEmpty()
                when {
                    ct == "text/plain" -> c.getString(2)?.let {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(it)
                    }
                    ct != "application/smil" -> att += SmsAttachment("p${c.getLong(0)}", ct)
                }
            }
        }
        return text.toString() to att
    }

    private fun mmsAddrs(id: Long, box: Int): List<String> {
        val from = ArrayList<String>()
        val to = ArrayList<String>()
        query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null) { c ->
            while (c.moveToNext()) {
                val a = c.getString(0)
                if (a.isNullOrEmpty() || a == "insert-address-token") continue
                if (c.getInt(1) == PDU_FROM) from += a else to += a
            }
        }
        // For a message we received, the sender first; the other recipients are the group.
        return if (box == Telephony.Mms.MESSAGE_BOX_INBOX) from + to else to
    }

    private fun <T> query(
        uri: Uri,
        proj: Array<String>,
        sel: String?,
        args: Array<String>?,
        order: String?,
        read: (Cursor) -> T,
    ): T? = try {
        resolver.query(uri, proj, sel, args, order)?.use(read)
    } catch (e: SecurityException) {
        Log.w("sms.denied", "err" to e.message)
        null
    } catch (e: IllegalArgumentException) {
        // Some OEM providers reject LIMIT in the sort order or unknown columns.
        Log.w("sms.query_failed", "uri" to uri.toString(), "err" to e.message)
        null
    }

    companion object {
        private const val SCAN_ROWS = 1500
        private const val SCAN_MMS_ROWS = 400
        private const val MAX_THREADS = 200
        private const val MAX_MESSAGES = 200
        private const val SNIPPET = 160
        private const val DIGITS = 10
        private const val MS = 1000L
        private const val PDU_FROM = 137

        fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

        fun smsStatus(type: Int): String = when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
            Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
            else -> "pending" // outbox, queued, draft
        }

        fun label(mime: String): String = when {
            mime.startsWith("image/") -> "Photo"
            mime.startsWith("video/") -> "Video"
            mime.startsWith("audio/") -> "Audio"
            else -> "Attachment"
        }
    }
}

/** Contact names by number with a small cache (READ_CONTACTS optional). */
class Contacts(private val context: Context) {
    private val cache = object : LinkedHashMap<String, String>(CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE
    }

    fun lookup(number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (!SmsStore.granted(context, Manifest.permission.READ_CONTACTS)) return null
        synchronized(cache) { cache[number]?.let { return it.ifEmpty { null } } }
        val name = try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: SecurityException) {
            Log.w("contacts.denied", "err" to e.message)
            null
        } catch (e: IllegalArgumentException) {
            Log.w("contacts.failed", "err" to e.message)
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
