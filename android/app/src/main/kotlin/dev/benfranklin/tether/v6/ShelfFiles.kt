@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package dev.benfranklin.tether.v6

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import dev.benfranklin.tether.core.features.ChunkSeal
import dev.benfranklin.tether.core.features.FileKinds
import dev.benfranklin.tether.core.proto.ShelfItem
import dev.benfranklin.tether.core.transfer.TransferEngine
import dev.benfranklin.tether.log.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * The phone's copy of every Shelf file (0.5.3), encrypted at rest like the rest of the Shelf:
 * `files/shelf-store/<item id>.tsf`, streamed through [ChunkSeal] (AES-256-GCM) with a random
 * 256-bit file key that is itself sealed by the Android Keystore ([Vault], prefs
 * "tether_secure" › "shelf_file_key"). Plain bytes only ever exist briefly in
 * `cache/shelf-open/<id>/<display name>` while a file is opened, copied or shared (the
 * FileProvider serves that folder), and that folder is emptied on every start.
 */
class ShelfFiles(private val app: Context) {
    private val store = File(app.filesDir, "shelf-store").apply { mkdirs() }
    private val openDir = File(app.cacheDir, "shelf-open")
    /** 0.5.4: small sealed JPEG thumbnails, kept when the copy itself is dropped (an evicted photo keeps its picture). */
    private val thumbDir = File(app.filesDir, "shelf-thumbs").apply { mkdirs() }
    private val thumbs = LruCache<String, Bitmap>(96)

    @Volatile private var keyBytes: ByteArray? = null

    @Synchronized private fun key(): ByteArray? {
        keyBytes?.let { return it }
        val existing = Vault.getString(app, KEY_PREF)?.let { Base64.decode(it, Base64.NO_WRAP) }?.takeIf { it.size == 32 }
        if (existing != null) {
            keyBytes = existing
            return existing
        }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        Vault.putString(app, KEY_PREF, Base64.encodeToString(k, Base64.NO_WRAP))
        if (Vault.getString(app, KEY_PREF) == null) {
            Log.w("shelf.key_unavailable")
            return null
        }
        keyBytes = k
        return k
    }

    fun blob(id: String) = File(store, "$id.tsf")

    fun has(id: String): Boolean = blob(id).let { it.exists() && it.length() > 0 }

    /** Encrypts [plain] into the store under [id]; [plain] is deleted afterwards when [move] is set. */
    @Throws(IOException::class)
    fun ingest(id: String, plain: File, move: Boolean) {
        val k = key() ?: throw IOException("no key")
        val tmp = File(store, "$id.tsf.tmp")
        try {
            plain.inputStream().use { input -> tmp.outputStream().use { out -> ChunkSeal.encrypt(input, out, k) } }
        } catch (e: GeneralSecurityException) {
            tmp.delete()
            throw IOException("encrypt: ${e.javaClass.simpleName}", e)
        }
        if (!tmp.renameTo(blob(id))) {
            tmp.delete()
            throw IOException("store rename failed")
        }
        if (move) plain.delete()
        thumbs.remove(id)
    }

    /**
     * The plain file for opening / copying / sharing, named as the item is now called
     * (`cache/shelf-open/<id>/<file name>`), or null when there is no local copy.
     */
    fun materialize(item: ShelfItem): File? {
        val f = item.file ?: return null
        if (!has(item.id)) return null
        val k = key() ?: return null
        val dir = File(openDir, item.id).apply {
            deleteRecursively()
            mkdirs()
        }
        val out = File(dir, TransferEngine.safeFileName(f.name))
        return try {
            blob(item.id).inputStream().use { input -> out.outputStream().use { ChunkSeal.decrypt(input, it, k) } }
            out
        } catch (e: Exception) {
            Log.w("shelf.open_failed", "error" to e.javaClass.simpleName)
            out.delete()
            null
        }
    }

    private fun thumbFile(id: String) = File(thumbDir, "$id.tsf")

    /**
     * A small picture for an image item: memory cache, then the sealed thumbnail on disk (which
     * survives eviction of the copy), then decoded from the local copy and saved. Off the main thread only.
     */
    fun thumbnail(item: ShelfItem, px: Int = 160): Bitmap? {
        val f = item.file ?: return null
        if (!FileKinds.isImage(f.mime, f.name)) return null
        val key = item.id + "|" + f.sha256
        thumbs.get(key)?.let { return it }
        val k = key() ?: return null
        savedThumb(item.id, k)?.let {
            thumbs.put(key, it)
            return it
        }
        if (!has(item.id)) return null
        val tmp = File(app.cacheDir, "shelf-thumb-${item.id}")
        return try {
            blob(item.id).inputStream().use { input -> tmp.outputStream().use { ChunkSeal.decrypt(input, it, k) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also {
                thumbs.put(key, it)
                saveThumb(item.id, it, k)
            }
        } catch (e: Exception) {
            Log.w("shelf.thumb_failed", "error" to e.javaClass.simpleName)
            null
        } finally {
            tmp.delete()
        }
    }

    private fun savedThumb(id: String, k: ByteArray): Bitmap? {
        val t = thumbFile(id)
        if (!t.exists()) return null
        return try {
            val out = java.io.ByteArrayOutputStream()
            t.inputStream().use { ChunkSeal.decrypt(it, out, k) }
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w("shelf.thumb_saved_bad", "error" to e.javaClass.simpleName)
            t.delete()
            null
        }
    }

    private fun saveThumb(id: String, bmp: Bitmap, k: ByteArray) {
        try {
            val jpeg = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, jpeg)
            val tmp = File(thumbDir, "$id.tmp")
            tmp.outputStream().use { ChunkSeal.encrypt(jpeg.toByteArray().inputStream(), it, k) }
            tmp.renameTo(thumbFile(id))
        } catch (e: Exception) {
            Log.w("shelf.thumb_save_failed", "error" to e.javaClass.simpleName)
        }
    }

    /** Bytes held by offline copies (sealed blobs; thumbnails are tiny and not counted). */
    fun usedBytes(): Long = store.listFiles()?.filter { it.name.endsWith(".tsf") }?.sumOf { it.length() } ?: 0L

    /** Size of one local copy (0 when absent). */
    fun size(id: String): Long = blob(id).length()

    /**
     * Drops the offline copy but keeps the item's thumbnail (eviction / "Remove offline copies").
     * A plain copy another app may still be reading (opened / shared) stays until the next launch.
     */
    fun evict(id: String) {
        blob(id).delete()
    }

    fun remove(id: String) {
        blob(id).delete()
        thumbFile(id).delete()
        File(openDir, id).deleteRecursively()
        thumbs.evictAll()
    }

    /** Drop copies of items that no longer exist; empty the plain-text open folder. */
    fun prune(live: Set<String>) {
        store.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".tmp").removeSuffix(".tsf")
            if (id !in live || f.name.endsWith(".tmp")) f.delete()
        }
        thumbDir.listFiles()?.forEach { f ->
            if (f.name.removeSuffix(".tmp").removeSuffix(".tsf") !in live || f.name.endsWith(".tmp")) f.delete()
        }
    }

    fun clearOpened() {
        openDir.deleteRecursively()
    }

    /**
     * 0.5.2 kept Shelf files in plain text under `files/shelf/<id>/<name>`. Encrypt each into the
     * store and delete the plain copy.
     */
    fun migrate() {
        val old = File(app.filesDir, "shelf")
        old.listFiles()?.forEach { dir ->
            val plain = dir.listFiles()?.firstOrNull { it.isFile }
            if (plain != null && !has(dir.name)) {
                try {
                    ingest(dir.name, plain, move = true)
                    Log.i("shelf.migrated")
                } catch (e: IOException) {
                    Log.w("shelf.migrate_failed", "error" to e.message)
                    return
                }
            }
            dir.deleteRecursively()
        }
        old.delete()
    }

    companion object {
        private const val KEY_PREF = "shelf_file_key"
        private const val THUMB_QUALITY = 80
    }
}
