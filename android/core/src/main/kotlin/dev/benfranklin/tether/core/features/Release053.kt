@file:Suppress("MagicNumber", "TooManyFunctions")

package dev.benfranklin.tether.core.features

import dev.benfranklin.tether.core.proto.ShelfFile
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.proto.ShelfKinds
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.core.transfer.TransferRecord
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// 0.5.3: pure rules behind Ben's 0.5.2 device report. Mirrored rule for rule in Swift
// (mac/TetherCore/Sources/TetherCore/Release053.swift); shared vectors in protocol/vectors/.

// ---------------- Friendly file kinds (Shelf cards, transfers) ----------------

/** "Word document", "PDF", "JPEG image" instead of raw MIME types. */
object FileKinds {
    private val byExt = mapOf(
        "pdf" to "PDF",
        "doc" to "Word document", "docx" to "Word document", "dot" to "Word template", "dotx" to "Word template",
        "xls" to "Excel spreadsheet", "xlsx" to "Excel spreadsheet", "csv" to "CSV spreadsheet",
        "ppt" to "PowerPoint presentation", "pptx" to "PowerPoint presentation",
        "pages" to "Pages document", "numbers" to "Numbers spreadsheet", "key" to "Keynote presentation",
        "odt" to "OpenDocument text", "ods" to "OpenDocument spreadsheet", "odp" to "OpenDocument presentation",
        "rtf" to "Rich text document", "txt" to "Plain text", "md" to "Markdown text", "json" to "JSON file",
        "xml" to "XML file", "html" to "Web page", "htm" to "Web page", "epub" to "eBook",
        "jpg" to "JPEG image", "jpeg" to "JPEG image", "png" to "PNG image", "heic" to "HEIC image", "heif" to "HEIF image",
        "gif" to "GIF image", "webp" to "WebP image", "bmp" to "BMP image", "tif" to "TIFF image", "tiff" to "TIFF image",
        "svg" to "SVG image", "dng" to "RAW image",
        "mp4" to "MP4 video", "m4v" to "MP4 video", "mov" to "QuickTime video", "mkv" to "MKV video", "webm" to "WebM video",
        "3gp" to "3GP video", "avi" to "AVI video",
        "mp3" to "MP3 audio", "m4a" to "M4A audio", "aac" to "AAC audio", "wav" to "WAV audio", "flac" to "FLAC audio",
        "ogg" to "Ogg audio", "opus" to "Opus audio",
        "zip" to "ZIP archive", "rar" to "RAR archive", "7z" to "7-Zip archive", "gz" to "GZip archive", "tar" to "TAR archive",
        "apk" to "Android app", "dmg" to "Disk image", "iso" to "Disk image", "pkg" to "Installer package",
        "ics" to "Calendar event", "vcf" to "Contact card",
        "kt" to "Kotlin source", "swift" to "Swift source", "py" to "Python script", "js" to "JavaScript file",
        "java" to "Java source", "c" to "C source", "cpp" to "C++ source", "sh" to "Shell script",
    )

    private val byMime = mapOf(
        "application/pdf" to "PDF",
        "application/msword" to "Word document",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "Word document",
        "application/vnd.ms-excel" to "Excel spreadsheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "Excel spreadsheet",
        "application/vnd.ms-powerpoint" to "PowerPoint presentation",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "PowerPoint presentation",
        "application/zip" to "ZIP archive", "application/x-zip-compressed" to "ZIP archive",
        "application/vnd.android.package-archive" to "Android app", "application/json" to "JSON file",
        "application/rtf" to "Rich text document", "application/epub+zip" to "eBook",
        "text/plain" to "Plain text", "text/csv" to "CSV spreadsheet", "text/html" to "Web page", "text/markdown" to "Markdown text",
        "image/jpeg" to "JPEG image", "image/png" to "PNG image", "image/heic" to "HEIC image", "image/heif" to "HEIF image",
        "image/gif" to "GIF image", "image/webp" to "WebP image", "image/svg+xml" to "SVG image",
        "video/mp4" to "MP4 video", "video/quicktime" to "QuickTime video", "audio/mpeg" to "MP3 audio", "audio/mp4" to "M4A audio",
    )

    private val imageExts = setOf("jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp", "tif", "tiff", "dng")

    fun extension(name: String?): String {
        val n = name?.substringAfterLast('/').orEmpty()
        val dot = n.lastIndexOf('.')
        return if (dot > 0 && dot < n.length - 1) n.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    /** A short human kind. The extension wins (MIME is often application/octet-stream), then MIME, then a family. */
    fun describe(mime: String?, name: String?): String {
        val ext = extension(name)
        byExt[ext]?.let { return it }
        val m = mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim().orEmpty()
        byMime[m]?.let { return it }
        return when {
            m.startsWith("image/") -> "Image"
            m.startsWith("video/") -> "Video"
            m.startsWith("audio/") -> "Audio"
            m.startsWith("text/") -> "Text document"
            ext.isNotEmpty() && ext.length <= 5 -> ext.uppercase(Locale.ROOT) + " file"
            else -> "File"
        }
    }

    /** Images we can thumbnail / put on the clipboard as a picture (SVG excluded: it isn't a bitmap). */
    fun isImage(mime: String?, name: String?): Boolean {
        val ext = extension(name)
        if (ext in imageExts) return true
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        return m.startsWith("image/") && m != "image/svg+xml"
    }
}

// ---------------- Shelf search ----------------

object ShelfSearch {
    /** Lower-case, accents folded, whitespace collapsed. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()

    /** Words: runs of letters and digits ("forms.gle/x7" → forms, gle, x7). */
    fun words(s: String): List<String> = fold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Every word of [query] must start a word of the title, content, file name, MIME or type
     * (never the Wi-Fi password): "word" finds a Word document but not "password".
     */
    fun matches(item: ShelfItem, query: String): Boolean {
        val wanted = words(query)
        if (wanted.isEmpty()) return true
        val f = item.file
        val have = words(
            listOfNotNull(
                item.title,
                item.body.takeIf { item.kind != ShelfKinds.WIFI && item.kind != ShelfKinds.FILE },
                f?.name, f?.mime, f?.let { FileKinds.describe(it.mime, it.name) },
                item.kind, kindWord(item.kind),
            ).joinToString(" "),
        )
        return wanted.all { w -> have.any { it.startsWith(w) } }
    }

    fun filter(items: List<ShelfItem>, query: String): List<ShelfItem> = items.filter { matches(it, query) }

    private fun kindWord(kind: String) = when (kind) {
        ShelfKinds.WIFI -> "wi-fi wifi network password"
        ShelfKinds.TEXT -> "note text"
        ShelfKinds.SNIPPET -> "snippet code"
        else -> kind
    }
}

// ---------------- Shelf rename ----------------

object ShelfRename {
    const val MAX = ShelfDetect.TITLE

    /**
     * The renamed item, or null when [raw] is blank or nothing changes. A FILE item's file name
     * follows the new title and keeps its extension ("Resume" on "cv.pdf" → "Resume.pdf"), so
     * opening and copying use it. [updated] moves so the rename wins the merge on both devices.
     */
    fun rename(item: ShelfItem, raw: String, now: Long): ShelfItem? {
        val title = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX)
        if (title.isEmpty()) return null
        val f = item.file
        val file = f?.copy(name = fileName(title, f.name))
        if (title == item.title && file == f) return null
        return item.copy(title = title, file = file, updated = maxOf(now, item.updated + 1))
    }

    fun fileName(title: String, original: String): String {
        val ext = FileKinds.extension(original)
        val base = TransferEngine.safeFileName(title.replace('/', '_').replace('\\', '_'))
        if (ext.isEmpty() || FileKinds.extension(base) == ext) return base
        return TransferEngine.safeFileName("$base.$ext")
    }
}

// ---------------- Shelf card text ----------------

/**
 * Title and preview for a text-like Shelf card without saying the same thing twice. A snippet
 * whose title was taken from its own first line ("git rebase -i" for "git rebase -i HEAD~3")
 * shows that whole first line as the title and only the lines after it as the preview. A
 * renamed item (title not at the start of the body) is left alone.
 */
object ShelfCardText {
    data class Parts(val title: String, val preview: String)

    fun split(title: String, body: String): Parts {
        val t = title.trim()
        val lines = body.trim().lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        if (t.isEmpty() || first.isEmpty() || !first.startsWith(t)) return Parts(title, body.trim())
        val rest = lines.drop(1).joinToString("\n").trim()
        return Parts(if (first.length > t.length) first else title, rest)
    }
}

// ---------------- Shelf file cache ----------------

object ShelfCache {
    /** Files up to this size are copied to both devices. */
    const val MAX_BYTES = 100L * 1024 * 1024
    /** Over Bluetooth only small files are fetched in the background; the rest wait for Wi-Fi. */
    const val BLE_MAX = 2L * 1024 * 1024

    enum class State { LOCAL, FETCHING, REMOTE, NOT_DOWNLOADED }

    fun shouldFetch(file: ShelfFile, haveLocal: Boolean, inFlight: Boolean, connected: Boolean, wifi: Boolean): Boolean =
        !haveLocal && !inFlight && connected && file.size in 0..MAX_BYTES && (wifi || file.size <= BLE_MAX)

    /** "Not downloaded yet" shows only when the copy is missing AND the other device isn't connected. */
    fun state(haveLocal: Boolean, fetching: Boolean, connected: Boolean): State = when {
        haveLocal -> State.LOCAL
        fetching -> State.FETCHING
        connected -> State.REMOTE
        else -> State.NOT_DOWNLOADED
    }
}

/**
 * Streaming AES-256-GCM for Shelf files at rest (up to 100 MB, so no whole-file buffers).
 * Format: "TSF1" + 8-byte random nonce prefix, then chunks of ≤64 KiB plaintext, each sealed with
 * nonce = prefix ‖ chunk index (4 bytes BE) and AAD = 1 for the final chunk, 0 otherwise (+16-byte
 * tag). The chunk with nothing after it is the final one (an empty plaintext is one empty final
 * chunk), so truncation, reordering or appended data always fails. Same format in Swift (ChunkSeal).
 */
object ChunkSeal {
    const val CHUNK = 64 * 1024
    private const val TAG = 16
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    private val rng = SecureRandom()

    @Throws(IOException::class, GeneralSecurityException::class)
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, prefix: ByteArray = ByteArray(8).also { rng.nextBytes(it) }) {
        require(key.size == 32 && prefix.size == 8)
        output.write(MAGIC)
        output.write(prefix)
        var index = 0
        var cur = readChunk(input)
        while (true) {
            // Look one chunk ahead: the chunk with nothing after it is sealed as the final one.
            val next = readChunk(input)
            val final = next.isEmpty()
            output.write(seal(key, prefix, index, cur, final))
            index++
            if (final) break
            cur = next
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray) {
        val head = ByteArray(12)
        readFully(input, head)
        if (!head.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("not a sealed shelf file")
        val prefix = head.copyOfRange(4, 12)
        val buf = ByteArray(CHUNK + TAG)
        var index = 0
        var pending = readUpTo(input, buf)
        while (true) {
            if (pending < TAG) throw IOException("truncated")
            val sealed = buf.copyOf(pending)
            val more = ByteArray(CHUNK + TAG)
            val n = if (pending == CHUNK + TAG) readUpTo(input, more) else 0
            val final = n == 0
            output.write(open(key, prefix, index, sealed, final))
            index++
            if (final) {
                if (input.read() >= 0) throw IOException("trailing data")
                break
            }
            System.arraycopy(more, 0, buf, 0, n)
            pending = n
        }
    }

    fun encryptBytes(plain: ByteArray, key: ByteArray): ByteArray =
        ByteArrayOutputStream().also { encrypt(plain.inputStream(), it, key) }.toByteArray()

    fun decryptBytes(blob: ByteArray, key: ByteArray): ByteArray =
        ByteArrayOutputStream().also { decrypt(blob.inputStream(), it, key) }.toByteArray()

    private fun nonce(prefix: ByteArray, index: Int) =
        prefix + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())

    private fun seal(key: ByteArray, prefix: ByteArray, index: Int, plain: ByteArray, final: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce(prefix, index)))
        c.updateAAD(byteArrayOf(if (final) 1 else 0))
        return c.doFinal(plain)
    }

    private fun open(key: ByteArray, prefix: ByteArray, index: Int, sealed: ByteArray, final: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce(prefix, index)))
        c.updateAAD(byteArrayOf(if (final) 1 else 0))
        return c.doFinal(sealed)
    }

    private fun readChunk(input: InputStream): ByteArray {
        val b = ByteArray(CHUNK)
        val n = readUpTo(input, b)
        return if (n == CHUNK) b else b.copyOf(n)
    }

    private fun readUpTo(input: InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val r = input.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        if (readUpTo(input, b) != b.size) throw EOFException("short header")
    }
}

// ---------------- Insert from Phone: launching the camera from the background ----------------

/**
 * How the phone gets CaptureActivity on screen when the Mac asks. Android blocks background
 * activity starts; the exemptions Tether can use are: the app is in front (DIRECT), its
 * accessibility service is bound by the system (ACCESSIBILITY: the binding carries the
 * background-start privilege), or it holds "Display over other apps" — which on Android 15+
 * only counts while the app shows an overlay window (OVERLAY_BRIDGE: a 1-px overlay is shown for
 * the launch). Every attempt is confirmed by the activity's onCreate within [CONFIRM_MS]; when
 * all fail, a high-priority / full-screen notification asks the user to tap (NOTIFICATION).
 */
object CapturePolicy {
    enum class Launch { DIRECT, ACCESSIBILITY, OVERLAY_BRIDGE, NOTIFICATION }

    const val CONFIRM_MS = 2_500L
    /** An open camera / scanner is "busy" for at most this long; older sessions are taken over. */
    const val STALE_MS = 3 * 60_000L
    const val ANDROID_15 = 35

    fun plan(foreground: Boolean, accessibility: Boolean, canOverlay: Boolean, sdk: Int): List<Launch> = buildList {
        if (foreground) add(Launch.DIRECT)
        if (accessibility) add(Launch.ACCESSIBILITY)
        if (canOverlay) add(if (sdk >= ANDROID_15) Launch.OVERLAY_BRIDGE else Launch.DIRECT)
        add(Launch.NOTIFICATION)
    }.distinct()

    /** A pending capture blocks a new one only while its screen is really open and recent. */
    fun busy(openedAt: Long?, now: Long): Boolean = openedAt != null && now - openedAt < STALE_MS
}

// ---------------- What's new after an update ----------------

object WhatsNewPolicy {
    enum class Action { SHOW, RECORD, NONE }

    /**
     * [seen] = the version whose What's new was shown (or recorded). Builds before 0.5.2 never
     * saved one, so a missing value on an app that was clearly used before ([usedBefore]: paired,
     * onboarding done) is an update, not a fresh install.
     */
    fun decide(current: String, seen: String?, usedBefore: Boolean): Action = when {
        seen == null -> if (usedBefore) Action.SHOW else Action.RECORD
        Versions.compare(base(current), base(seen)) > 0 -> Action.SHOW
        else -> Action.NONE
    }

    /** "0.5.3-dev" → "0.5.3". */
    fun base(v: String): String = v.substringBefore('-').substringBefore('+')

    /** The `## <version>` section of CHANGELOG.md (bundled in both apps), without its heading. */
    fun section(changelog: String, version: String): String? {
        val want = base(version)
        val lines = changelog.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().substringBefore(' ') == want }
        if (start < 0) return null
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("## ") || it.startsWith("# ") }.let { if (it < 0) rest.size else it }
        return rest.take(end).joinToString("\n").trim().ifEmpty { null }
    }
}

// ---------------- Transfers list ----------------

object TransferList {
    const val HOME_MAX = 3
    const val COLLAPSE_MS = 24 * 3600_000L

    enum class Filter { ALL, SENT, RECEIVED }

    data class Section(val title: String, val items: List<TransferRecord>, val collapsed: Boolean)

    private fun newestFirst(list: List<TransferRecord>) = list.sortedByDescending { maxOf(it.updatedAt, it.createdAt) }

    /** Finished more than 24 h ago: folded away (not on Home, collapsed in the full list). */
    fun stale(t: TransferRecord, now: Long): Boolean = t.status.isFinal && now - maxOf(t.updatedAt, t.createdAt) > COLLAPSE_MS

    /** Home: running transfers first, then the newest; at most [HOME_MAX]; nothing finished over 24 h ago. */
    fun home(list: List<TransferRecord>, now: Long): List<TransferRecord> {
        val live = list.filterNot { stale(it, now) }
        return (newestFirst(live.filter { !it.status.isFinal }) + newestFirst(live.filter { it.status.isFinal })).take(HOME_MAX)
    }

    fun filter(list: List<TransferRecord>, f: Filter): List<TransferRecord> = when (f) {
        Filter.ALL -> list
        Filter.SENT -> list.filter { it.direction == Direction.OUT }
        Filter.RECEIVED -> list.filter { it.direction == Direction.IN }
    }

    /** Today (since local midnight [startOfToday]) and Earlier; Earlier is collapsed when everything in it is stale. */
    fun sections(list: List<TransferRecord>, f: Filter, now: Long, startOfToday: Long): List<Section> {
        val all = newestFirst(filter(list, f))
        val today = all.filter { maxOf(it.updatedAt, it.createdAt) >= startOfToday || !it.status.isFinal }
        val earlier = all - today.toSet()
        return buildList {
            if (today.isNotEmpty()) add(Section("Today", today, collapsed = false))
            if (earlier.isNotEmpty()) add(Section("Earlier", earlier, collapsed = earlier.all { stale(it, now) }))
        }
    }
}

// ---------------- Continue on Mac: new photos ----------------

object MediaReady {
    /** A new photo is ready to announce once it's published (not pending), non-empty and its size held still between two looks. */
    fun stable(pending: Boolean, size: Long, previousSize: Long?): Boolean = !pending && size > 0 && size == previousSize

    /**
     * A thumbnail that is one flat dark colour (a frame captured before the file was written, or
     * a FLAG_SECURE screen) carries no picture: the Mac shows a placeholder instead.
     * [luma] = 0..255 samples spread over the image.
     */
    fun isBlank(luma: IntArray): Boolean {
        if (luma.isEmpty()) return true
        val mean = luma.average()
        val spread = (luma.maxOrNull() ?: 0) - (luma.minOrNull() ?: 0)
        return mean < BLANK_MEAN && spread <= BLANK_SPREAD
    }

    private const val BLANK_MEAN = 24.0
    private const val BLANK_SPREAD = 12
}
