@file:Suppress("MagicNumber", "SwallowedException") // cursor column indexes; expected platform exceptions become protocol replies

package dev.benfranklin.tether.v5

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import dev.benfranklin.tether.core.features.FsPaths
import dev.benfranklin.tether.core.proto.FsEntry
import dev.benfranklin.tether.core.proto.FsList
import dev.benfranklin.tether.core.proto.FsRoots
import dev.benfranklin.tether.core.proto.PhotoItem
import dev.benfranklin.tether.core.proto.Photos
import dev.benfranklin.tether.log.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * The phone side of the Mac's file and photo browsers. Folders come from shared storage
 * (Downloads, Documents, WhatsApp media, DCIM, Pictures) and need "All files access"
 * (MANAGE_EXTERNAL_STORAGE) on Android 11+; photos come from MediaStore and need the photos
 * permission. Every path is resolved inside its root (no "..", no symlink escapes).
 */
class PhoneFiles(private val context: Context) {
    sealed interface Resolved {
        data class Ok(val file: File) : Resolved
        data class Error(val needs: String) : Resolved
    }

    fun rootDir(root: String): File? {
        val ext = Environment.getExternalStorageDirectory()
        return when (root) {
            FsRoots.DOWNLOADS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            FsRoots.DOCUMENTS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            FsRoots.DCIM -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            FsRoots.PICTURES -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            FsRoots.WHATSAPP -> listOf(
                File(ext, "Android/media/com.whatsapp/WhatsApp/Media"),
                File(ext, "WhatsApp/Media"), // before WhatsApp moved to scoped storage (2021)
            ).firstOrNull { it.isDirectory } ?: File(ext, "Android/media/com.whatsapp/WhatsApp/Media")
            else -> null
        }
    }

    fun resolve(root: String, path: String): Resolved {
        if (!Setup.allFilesAccess(context)) return Resolved.Error(NEEDS_ALL_FILES)
        val base = rootDir(root) ?: return Resolved.Error("error:unknown_root")
        val clean = FsPaths.clean(path) ?: return Resolved.Error("error:bad_path")
        val f = if (clean.isEmpty()) base else File(base, clean)
        return try {
            val canonBase = base.canonicalFile
            val canon = f.canonicalFile
            if (canon != canonBase && !canon.path.startsWith(canonBase.path + File.separator)) {
                Resolved.Error("error:outside_root")
            } else if (!canon.exists()) {
                Resolved.Error(NEEDS_MISSING)
            } else {
                Resolved.Ok(canon)
            }
        } catch (e: IOException) {
            Resolved.Error("error:" + (e.message ?: "io"))
        }
    }

    fun list(ref: String, root: String, path: String, offset: Int, limit: Int): FsList {
        val dir = when (val r = resolve(root, path)) {
            is Resolved.Error -> return FsList(ref, root, path, emptyList(), needs = r.needs)
            is Resolved.Ok -> r.file
        }
        if (!dir.isDirectory) return FsList(ref, root, path, emptyList(), needs = "error:not_a_folder")
        val all = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val sorted = FsPaths.sorted(all, { it.isDirectory }, { it.name })
        val page = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE))
        val entries = page.map { f ->
            FsEntry(
                name = f.name, dir = f.isDirectory, size = if (f.isDirectory) 0 else f.length(), mtime = f.lastModified(),
                mime = if (f.isDirectory) null else mimeOf(f.name),
            )
        }
        return FsList(ref, root, FsPaths.clean(path).orEmpty(), entries, total = sorted.size, more = offset + page.size < sorted.size)
    }

    /** A JPEG preview (≤ [maxBytes]) of an image or video file, or of a MediaStore photo id. */
    fun thumbnail(root: String, path: String, size: Int, maxBytes: Int): Pair<ByteArray?, String?> {
        val px = size.coerceIn(MIN_THUMB, MAX_THUMB)
        return try {
            val bmp = if (root == FsRoots.PHOTOS) {
                mediaThumb(path.toLongOrNull() ?: return null to "error:bad_id", px)
            } else {
                when (val r = resolve(root, path)) {
                    is Resolved.Error -> return null to r.needs
                    is Resolved.Ok -> fileThumb(r.file, px)
                }
            } ?: return null to "unsupported"
            jpeg(bmp, maxBytes) to null
        } catch (e: IOException) {
            null to "error:" + (e.message ?: "io")
        } catch (e: SecurityException) {
            null to "photos"
        } catch (e: OutOfMemoryError) {
            Log.w("thumb.oom")
            null to "error:memory"
        }
    }

    private fun fileThumb(f: File, px: Int): Bitmap? {
        val mime = mimeOf(f.name).orEmpty()
        return when {
            mime.startsWith("video/") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null)
            mime.startsWith("image/") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ThumbnailUtils.createImageThumbnail(f, Size(px, px), null)
            mime.startsWith("image/") -> decodeSampled(f, px)
            else -> null
        }
    }

    private fun decodeSampled(f: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun mediaUri(id: Long, video: Boolean = false): Uri = ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
    )

    private fun mediaThumb(id: Long, px: Int): Bitmap? {
        val video = isVideo(id)
        val uri = mediaUri(id, video)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } else {
            @Suppress("DEPRECATION")
            if (video) {
                MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            }
        }
    }

    private fun isVideo(id: Long): Boolean = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), arrayOf(MediaStore.MediaColumns._ID),
            null, null, null,
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** Newest photos and videos first, [limit] per page, older than [before] (ms) when paging. */
    fun photos(ref: String, limit: Int, before: Long?): Photos {
        if (!Setup.photosAccess(context)) return Photos(ref, emptyList(), needs = "photos", before = before)
        val n = limit.coerceIn(1, MAX_PHOTOS)
        val items = ArrayList<PhotoItem>()
        try {
            items += query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, n + 1, before)
            if (Setup.videoAccess(context)) items += query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, n + 1, before)
        } catch (e: SecurityException) {
            return Photos(ref, emptyList(), needs = "photos", before = before)
        } catch (e: IllegalArgumentException) {
            Log.w("photos.query_failed", "reason" to e.message)
            return Photos(ref, emptyList(), needs = "error:query", before = before)
        }
        val sorted = items.sortedByDescending { it.date }
        return Photos(ref, sorted.take(n), more = sorted.size > n, before = before)
    }

    private fun query(uri: Uri, video: Boolean, limit: Int, before: Long?): List<PhotoItem> {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val sel = before?.let { "${MediaStore.MediaColumns.DATE_ADDED} < ?" }
        val args = before?.let { arrayOf((it / MS).toString()) }
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val out = ArrayList<PhotoItem>()
        context.contentResolver.query(uri, cols, sel, args, order)?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                out += PhotoItem(
                    id = id.toString(), name = c.getString(1) ?: "photo_$id", size = c.getLong(2), date = c.getLong(3) * MS,
                    w = c.getInt(4), h = c.getInt(5), mime = c.getString(6) ?: if (video) "video/mp4" else "image/jpeg", video = video,
                )
            }
        }
        return out
    }

    /** Uri to stage for FS_GET: a file under a root, or a MediaStore photo/video. */
    fun uriFor(root: String, path: String): Pair<Uri?, String?> {
        if (root == FsRoots.PHOTOS) {
            if (!Setup.photosAccess(context)) return null to "photos"
            val id = path.toLongOrNull() ?: return null to "error:bad_id"
            return mediaUri(id, isVideo(id)) to null
        }
        return when (val r = resolve(root, path)) {
            is Resolved.Error -> null to r.needs
            is Resolved.Ok -> if (r.file.isDirectory) null to "error:is_folder" else Uri.fromFile(r.file) to null
        }
    }

    companion object {
        const val NEEDS_ALL_FILES = "allfiles"
        const val NEEDS_MISSING = "missing"
        private const val MAX_PAGE = 500
        private const val MAX_PHOTOS = 200
        private const val MIN_THUMB = 64
        private const val MAX_THUMB = 512
        private const val MS = 1000L
        private const val START_QUALITY = 82
        private const val MIN_QUALITY = 40
        private const val QUALITY_STEP = 12

        fun mimeOf(name: String): String? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                "opus" -> "audio/ogg"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                else -> null
            }
        }

        /** JPEG under [maxBytes]: lowers quality, then halves the size, until it fits. */
        fun jpeg(src: Bitmap, maxBytes: Int): ByteArray {
            var bmp = src
            var q = START_QUALITY
            while (true) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= maxBytes || bmp.width < MIN_THUMB) return out.toByteArray()
                if (q > MIN_QUALITY) {
                    q -= QUALITY_STEP
                } else {
                    bmp = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                    q = START_QUALITY
                }
            }
        }
    }
}
