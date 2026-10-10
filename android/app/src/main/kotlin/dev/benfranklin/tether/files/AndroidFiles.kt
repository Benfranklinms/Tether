package dev.benfranklin.tether.files

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.IncomingFiles
import dev.benfranklin.tether.core.transfer.IntegrityException
import dev.benfranklin.tether.core.transfer.OutgoingFiles
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.util.Hex
import dev.benfranklin.tether.data.SettingsRepository
import dev.benfranklin.tether.log.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Outgoing files are copied into app storage first (share-sheet grants are temporary), so a
 * transfer can resume after a reconnect or an app restart. Incoming files land in a private
 * partial file and are published to Downloads/Tether (or the user's chosen folder) only after
 * their SHA-256 matches.
 */
class AndroidFiles(private val context: Context, private val settings: SettingsRepository) : OutgoingFiles, IncomingFiles {
    private val outDir = File(context.filesDir, "outgoing").apply { mkdirs() }
    private val inDir = File(context.filesDir, "incoming").apply { mkdirs() }
    private val readers = HashMap<String, RandomAccessFile>()

    // ---------- outgoing ----------

    /** Copy [uri] into staging and build a QUEUED outgoing record. Runs on an IO thread. */
    @Throws(IOException::class)
    fun stage(uri: Uri, now: Long): TransferRecord {
        val resolver = context.contentResolver
        var name = "file"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val target = File(outDir, id)
        val md = MessageDigest.getInstance("SHA-256")
        val size = (resolver.openInputStream(uri) ?: throw IOException("can't open")).use { input ->
            target.outputStream().use { out -> copyHashing(input, out, md) }
        }
        return TransferRecord(
            id = id, direction = Direction.OUT, name = TransferEngine.safeFileName(name), size = size,
            sha256 = Hex.encode(md.digest()), mime = mime, status = TransferStatus.QUEUED, createdAt = now,
            ref = target.absolutePath,
        )
    }

    override fun read(record: TransferRecord, offset: Long, length: Int): ByteArray {
        val path = record.ref ?: throw IOException("no staged copy")
        val raf = readers.getOrPut(record.id) { RandomAccessFile(path, "r") }
        if (raf.length() != record.size) throw IOException("staged copy changed")
        val buf = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    override fun release(record: TransferRecord) {
        readers.remove(record.id)?.close()
        record.ref?.let { File(it) }?.takeIf { it.parentFile == outDir }?.delete()
    }

    // ---------- incoming ----------

    private fun partial(id: String) = File(inDir, "$id.part")

    override fun partialLength(id: String): Long = partial(id).length()

    override fun begin(record: TransferRecord) {
        val free = inDir.usableSpace
        if (free < record.size + SPACE_MARGIN) throw IOException("not enough space")
        partial(record.id).apply {
            delete()
            createNewFile()
        }
    }

    override fun append(id: String, data: ByteArray) {
        java.io.FileOutputStream(partial(id), true).use { it.write(data) }
    }

    override fun finish(record: TransferRecord): String {
        val p = partial(record.id)
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(p).use { input -> copyHashing(input, NullOut, md) }
        val actual = Hex.encode(md.digest())
        if (actual != record.sha256) throw IntegrityException("checksum mismatch")
        if (record.kind == dev.benfranklin.tether.core.proto.FileKinds5.CLIP) {
            // A clipboard image from the Mac: kept privately, never published to Downloads.
            val dir = File(context.filesDir, "clips").apply { mkdirs() }
            val dst = File(dir, "in-" + record.id)
            if (!p.renameTo(dst)) {
                p.copyTo(dst, overwrite = true)
                p.delete()
            }
            return dst.absolutePath
        }
        val where = publish(p, record)
        p.delete()
        Log.i("file.saved", "size" to record.size)
        return where
    }

    override fun discard(id: String) {
        partial(id).delete()
    }

    /** Remove partial files no transfer refers to any more (left by a peer that gave up). */
    fun sweep(liveIds: Set<String>) {
        val cutoff = System.currentTimeMillis() - STALE_MS
        inDir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".part")
            if (id !in liveIds && f.lastModified() < cutoff) f.delete()
        }
        outDir.listFiles()?.forEach { f -> if (f.name !in liveIds && f.lastModified() < cutoff) f.delete() }
    }

    private fun publish(src: File, record: TransferRecord): String {
        settings.current.downloadTreeUri?.let { tree ->
            try {
                return publishToTree(Uri.parse(tree), src, record)
            } catch (e: IOException) {
                Log.w("file.tree_failed", "reason" to e.message)
            } catch (e: SecurityException) {
                Log.w("file.tree_revoked", "reason" to e.message)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishMediaStore(src, record) else publishLegacy(src, record)
    }

    private fun publishToTree(tree: Uri, src: File, record: TransferRecord): String {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, record.mime, record.name)
            ?: throw IOException("couldn't create document")
        (resolver.openOutputStream(doc) ?: throw IOException("couldn't open document")).use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        return doc.toString()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun publishMediaStore(src: File, record: TransferRecord): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, record.name)
            put(MediaStore.Downloads.MIME_TYPE, record.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tether")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("couldn't open download")).use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(src: File, record: TransferRecord): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tether").apply { mkdirs() }
        val target = uniqueName(dir, record.name)
        src.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(record.mime), null)
        return Uri.fromFile(target).toString()
    }

    private fun uniqueName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        while (f.exists()) {
            f = File(dir, "${name.substring(0, dot)} ($n)${name.substring(dot)}")
            n++
        }
        return f
    }

    private object NullOut : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        private const val SPACE_MARGIN = 50L * 1024 * 1024
        private const val STALE_MS = 7L * 24 * 3600 * 1000
        private const val BUFFER = 64 * 1024

        fun copyHashing(input: InputStream, out: OutputStream, md: MessageDigest): Long {
            val buf = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                out.write(buf, 0, n)
                total += n
            }
            return total
        }
    }
}
