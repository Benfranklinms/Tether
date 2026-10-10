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
import dev.benfranklin.tether.core.features.AlbumAccumulator
import dev.benfranklin.tether.core.features.FsPaths
import dev.benfranklin.tether.core.proto.AlbumKinds
import dev.benfranklin.tether.core.proto.Albums
import dev.benfranklin.tether.core.proto.FsEntry
import dev.benfranklin.tether.core.proto.FsList
import dev.benfranklin.tether.core.proto.FsRoots
import dev.benfranklin.tether.core.proto.PhotoFilters
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

    /**
     * Newest photos and videos first, [limit] per page, older than [before] (ms) when paging.
     * 0.5.1: one query on the MediaStore Files table, narrowed to an [album] and a [filter].
     */
    fun photos(ref: String, limit: Int, before: Long?, album: String? = null, filter: String? = null): Photos {
        if (!Setup.photosAccess(context)) return Photos(ref, emptyList(), needs = "photos", before = before)
        val n = limit.coerceIn(1, MAX_PHOTOS)
        val items = try {
            queryMedia(n + 1, before, album, filter)
        } catch (e: SecurityException) {
            return Photos(ref, emptyList(), needs = "photos", before = before)
        } catch (e: IllegalArgumentException) {
            Log.w("photos.query_failed", "reason" to e.message)
            return Photos(ref, emptyList(), needs = "error:query", before = before)
        }
        Log.i("photos.page", "album" to album, "filter" to filter, "n" to items.size)
        return Photos(ref, items.take(n), more = items.size > n, before = before)
    }

    private val filesUri: Uri get() = MediaStore.Files.getContentUri("external")
    private val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private val pathCol get() = if (q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA

    private fun mediaTypes(): String {
        val video = Setup.videoAccess(context)
        val img = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val vid = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        return "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" + (if (video) "$img,$vid" else "$img") + ")"
    }

    /** Selection for an album id / filter (pure string building; args appended to [args]). */
    private fun albumSelection(album: String?, filter: String?, args: MutableList<String>): String {
        val parts = mutableListOf(mediaTypes())
        val img = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val vid = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        fun like(pattern: String) {
            parts += "$pathCol LIKE ?"
            args += pattern
        }
        when {
            album == null || album == AlbumKinds.ALL -> Unit
            album == AlbumKinds.CAMERA -> like("%DCIM/Camera%")
            album == AlbumKinds.SCREENSHOTS -> like("%Screenshots%")
            album == AlbumKinds.WHATSAPP -> like("%WhatsApp Images%")
            album == AlbumKinds.DOWNLOADS -> like("%Download%")
            album == AlbumKinds.VIDEOS -> parts += "${MediaStore.Files.FileColumns.MEDIA_TYPE} = $vid"
            album == AlbumKinds.FAVORITES && r -> parts += "${MediaStore.MediaColumns.IS_FAVORITE} = 1"
            album.startsWith(BUCKET_PREFIX) && q -> {
                parts += "${MediaStore.MediaColumns.BUCKET_ID} = ?"
                args += album.removePrefix(BUCKET_PREFIX)
            }
        }
        when (filter) {
            PhotoFilters.PHOTOS -> parts += "${MediaStore.Files.FileColumns.MEDIA_TYPE} = $img"
            PhotoFilters.VIDEOS -> parts += "${MediaStore.Files.FileColumns.MEDIA_TYPE} = $vid"
            PhotoFilters.SCREENSHOTS -> like("%Screenshots%")
        }
        return parts.joinToString(" AND ")
    }

    private fun queryMedia(limit: Int, before: Long?, album: String?, filter: String?): List<PhotoItem> {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        if (q) cols += listOf(MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.BUCKET_ID)
        if (r) cols += MediaStore.MediaColumns.IS_FAVORITE
        val args = mutableListOf<String>()
        var sel = albumSelection(album, filter, args)
        if (before != null) {
            sel += " AND ${MediaStore.MediaColumns.DATE_ADDED} < ?"
            args += (before / MS).toString()
        }
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val out = ArrayList<PhotoItem>()
        val bundle = if (q) {
            android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
            }
        } else {
            null
        }
        val cursor = if (bundle != null) {
            context.contentResolver.query(filesUri, cols.toTypedArray(), bundle, null)
        } else {
            context.contentResolver.query(filesUri, cols.toTypedArray(), sel, args.toTypedArray(), order)
        }
        cursor?.use { c ->
            val iDur = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
            val iFav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val video = c.getInt(7) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                out += PhotoItem(
                    id = id.toString(), name = c.getString(1) ?: "photo_$id", size = c.getLong(2), date = c.getLong(3) * MS,
                    w = c.getInt(4), h = c.getInt(5), mime = c.getString(6) ?: if (video) "video/mp4" else "image/jpeg", video = video,
                    dur = if (iDur >= 0) c.getLong(iDur) else 0, bucket = if (iBucket >= 0) c.getString(iBucket) else null,
                    fav = iFav >= 0 && c.getInt(iFav) == 1,
                )
            }
        }
        return out
    }

    /** Albums (0.5.1): built-in ones with counts and covers, then every other bucket, biggest first. */
    fun albums(ref: String): Albums {
        if (!Setup.photosAccess(context)) return Albums(ref, emptyList(), needs = "photos")
        val cols = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, pathCol)
        if (q) cols += listOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        if (r) cols += MediaStore.MediaColumns.IS_FAVORITE
        val acc = AlbumAccumulator()
        try {
            context.contentResolver.query(filesUri, cols.toTypedArray(), mediaTypes(), null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                ?.use { c ->
                    val iB = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                    val iN = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val iF = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
                    var rows = 0
                    while (c.moveToNext() && rows++ < MAX_ALBUM_SCAN) {
                        acc.add(
                            id = c.getLong(0).toString(),
                            video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                            path = c.getString(2).orEmpty(),
                            bucket = if (iB >= 0) c.getString(iB) else null,
                            bucketName = if (iN >= 0) c.getString(iN) else null,
                            fav = iF >= 0 && c.getInt(iF) == 1,
                        )
                    }
                }
        } catch (e: SecurityException) {
            return Albums(ref, emptyList(), needs = "photos")
        } catch (e: IllegalArgumentException) {
            Log.w("albums.query_failed", "reason" to e.message)
            return Albums(ref, emptyList(), needs = "error:query")
        }
        val list = acc.albums(favoritesSupported = r)
        Log.i("albums.list", "n" to list.size)
        return Albums(ref, list)
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
        private const val MAX_THUMB = 1024
        private const val MAX_ALBUM_SCAN = 50_000
        const val BUCKET_PREFIX = AlbumAccumulator.BUCKET_PREFIX
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
