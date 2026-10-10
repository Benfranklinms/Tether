package dev.benfranklin.tether.call

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.benfranklin.tether.core.features.CallLogTypeMap
import dev.benfranklin.tether.core.proto.CallLogEntry
import dev.benfranklin.tether.core.proto.ContactInfo
import dev.benfranklin.tether.core.proto.ContactNumber
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.sms.NameLookup
import dev.benfranklin.tether.sms.SmsStore
import java.util.Locale

/**
 * Reads the phone's call log (READ_CALL_LOG) and searches contacts (READ_CONTACTS) for the
 * Mac. Reads are bounded (never more rows than asked for + 1) and tolerate missing columns,
 * so a vendor's odd call-log provider degrades to fewer details instead of failing.
 * Call from a background thread.
 */
class CallLogStore(private val context: Context, private val names: NameLookup) {
    fun canRead() = SmsStore.granted(context, Manifest.permission.READ_CALL_LOG)
    fun canSearchContacts() = SmsStore.granted(context, Manifest.permission.READ_CONTACTS)

    /** Newest [limit] calls older than [before]; the flag says whether more exist. */
    fun recent(limit: Int, before: Long?): Pair<List<CallLogEntry>, Boolean> {
        val n = limit.coerceIn(1, MAX_LIMIT)
        val sel = before?.let { "${CallLog.Calls.DATE} < ?" }
        val args = before?.let { arrayOf(it.toString()) }
        val subs = subscriptionIds()
        val iso = countryIso()
        val out = ArrayList<CallLogEntry>()
        var more = false
        query(CallLog.Calls.CONTENT_URI, PROJECTION, sel, args, "${CallLog.Calls.DATE} DESC")?.use { c ->
            val col = PROJECTION.associateWith { c.getColumnIndex(it) }
            while (c.moveToNext()) {
                if (out.size == n) {
                    more = true
                    break
                }
                row(c, col, subs, iso)?.let(out::add)
            }
        }
        return out to more
    }

    private fun row(c: Cursor, col: Map<String, Int>, subs: Map<String, Int>, iso: String): CallLogEntry? = try {
        fun str(k: String) = col[k]?.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
        fun long(k: String) = col[k]?.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
        val number = str(CallLog.Calls.NUMBER).orEmpty()
        val cached = str(CallLog.Calls.CACHED_NAME)?.takeIf { it.isNotBlank() }
        CallLogEntry(
            id = long(CallLog.Calls._ID) ?: 0,
            // The call log stores numbers bare (+919847012345); contacts keep them spaced as
            // typed. Recents show them spaced the same way (matching and dialing use digits only).
            number = displayNumber(number, iso),
            name = cached ?: names.lookup(number.ifBlank { null }),
            type = CallLogTypeMap.of(long(CallLog.Calls.TYPE)?.toInt() ?: 0),
            date = long(CallLog.Calls.DATE) ?: 0,
            dur = long(CallLog.Calls.DURATION) ?: 0,
            sub = str(CallLog.Calls.PHONE_ACCOUNT_ID)?.let { acct -> subs[acct] ?: acct.toIntOrNull()?.takeIf { it in subs.values } },
            isNew = long(CallLog.Calls.NEW) == 1L,
        )
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        Log.w("calllog.row_failed", "err" to e.javaClass.simpleName)
        null
    }

    /** Contacts whose name or number matches [query] (the contacts provider's own filter). */
    fun searchContacts(query: String, limit: Int): List<ContactInfo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val byId = LinkedHashMap<Long, Pair<String, MutableList<ContactNumber>>>()
        val max = limit.coerceIn(1, MAX_LIMIT)
        query(uri, proj, null, null, null)?.use { c ->
            var rows = 0
            while (c.moveToNext() && rows++ < MAX_CONTACT_ROWS) addContactRow(c, byId, max)
        }
        return byId.values.map { (name, nums) -> ContactInfo(name, nums) }
    }

    private fun addContactRow(c: Cursor, byId: MutableMap<Long, Pair<String, MutableList<ContactNumber>>>, max: Int) {
        val id = c.getLong(COL_ID)
        val name = c.getString(COL_NAME)
        val number = c.getString(COL_NUMBER)?.takeIf { it.isNotBlank() }
        if (name == null || number == null || id !in byId && byId.size >= max) return
        val label = if (c.isNull(COL_TYPE)) {
            null
        } else {
            ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(COL_TYPE), c.getString(COL_LABEL)).toString()
        }
        val list = byId.getOrPut(id) { name to mutableListOf() }.second
        if (list.none { it.n.filter(Char::isDigit) == number.filter(Char::isDigit) }) list += ContactNumber(number, label)
    }

    private fun query(uri: Uri, proj: Array<String>, sel: String?, args: Array<String>?, order: String?): Cursor? = try {
        context.contentResolver.query(uri, proj, sel, args, order)
    } catch (e: SecurityException) {
        Log.w("calllog.denied", "uri" to uri.authority, "err" to e.message)
        null
    } catch (e: IllegalArgumentException) {
        // A column this provider doesn't have: retry with the columns every provider has.
        Log.w("calllog.projection_rejected", "err" to e.message)
        if (proj === PROJECTION) query(uri, BASIC, sel, args, order) else null
    }

    /** The phone's country for number formatting: network, then SIM, then the locale. */
    private fun countryIso(): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val fromPhone = try {
            tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
        return (fromPhone ?: Locale.getDefault().country).uppercase(Locale.ROOT)
    }

    /** PHONE_ACCOUNT_ID -> subscription id: the account id is the subId or the ICCID, by vendor. */
    @Suppress("MissingPermission")
    private fun subscriptionIds(): Map<String, Int> = try {
        if (!SmsStore.granted(context, Manifest.permission.READ_PHONE_STATE)) {
            emptyMap()
        } else {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
                .flatMap { s ->
                    val iccid = s.iccId?.takeIf { it.isNotBlank() }
                    listOfNotNull(s.subscriptionId.toString() to s.subscriptionId, iccid?.let { it to s.subscriptionId })
                }
                .toMap()
        }
    } catch (e: SecurityException) {
        Log.w("calllog.subs_denied", "err" to e.message)
        emptyMap()
    }

    companion object {
        const val MAX_LIMIT = 100

        /**
         * [raw] grouped the way contacts show numbers ("+91 98470 12345", "(650) 253-0000"), using
         * the phone's own formatter for [countryIso]. Already-formatted numbers, short codes,
         * alphanumeric senders and anything the formatter can't parse come back unchanged.
         */
        fun displayNumber(raw: String, countryIso: String): String {
            val n = raw.trim()
            val bare = n.all { it.isDigit() || it == '+' }
            if (!bare || n.count(Char::isDigit) < MIN_FORMAT_DIGITS) return n
            return try {
                PhoneNumberUtils.formatNumber(n, countryIso.ifBlank { "US" })?.takeIf { it.isNotBlank() } ?: n
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException) {
                n
            }
        }

        private const val MIN_FORMAT_DIGITS = 7
        private const val MAX_CONTACT_ROWS = 400
        private const val COL_ID = 0
        private const val COL_NAME = 1
        private const val COL_NUMBER = 2
        private const val COL_TYPE = 3
        private const val COL_LABEL = 4
        private val PROJECTION = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.NEW,
        )
        private val BASIC = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
    }
}
