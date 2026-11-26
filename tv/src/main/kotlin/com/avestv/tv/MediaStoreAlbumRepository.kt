package com.avestv.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.RequiresApi
import com.avestv.protocol.AlbumIndex
import com.avestv.protocol.AlbumInfo
import com.avestv.protocol.AlbumLayout
import com.avestv.protocol.AlbumStore
import com.avestv.protocol.DiscoveryProtocol
import com.avestv.protocol.PhotoEntry
import com.avestv.protocol.TvDeviceInfo
import com.avestv.protocol.UploadMode
import com.avestv.protocol.UploadResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Stores received photos under Pictures/TVAlbums/<album>/ via Android MediaStore so that:
 * - Android 10+ requires zero MANAGE_EXTERNAL_STORAGE permission or SAF picker.
 * - Writes (IS_PENDING 1 -> 0) and deletes atomically notify system MediaStore and Aves.
 * - Same-name uploads overwrite cleanly without creating "(1)" duplicates.
 */
class MediaStoreAlbumRepository(
    private val context: Context,
    private val prefs: TvPreferences,
) : AlbumStore {

    private val resolver = context.applicationContext.contentResolver
    private val writeLock = Any()

    override fun deviceInfo(httpPort: Int): TvDeviceInfo {
        val albums = listAlbums()
        val total = albums.sumOf { it.photoCount }
        return TvDeviceInfo(
            deviceId = prefs.deviceId,
            deviceName = prefs.deviceName,
            httpPort = httpPort,
            freeBytes = availableStorageBytes(),
            albumCount = albums.size,
            totalPhotos = total,
        )
    }

    override fun listAlbums(): List<AlbumInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listAlbumsQ()
        } else {
            listAlbumsLegacy()
        }

    override fun listPhotos(album: String): List<PhotoEntry> {
        val safeAlbum = DiscoveryProtocol.sanitizeAlbumName(album)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listPhotosQ(safeAlbum)
        } else {
            listPhotosLegacy(safeAlbum)
        }
    }

    override fun readThumbnail(photoId: String): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            readThumbnailQ(photoId)
        } else {
            readThumbnailLegacy(photoId)
        }

    override fun savePhoto(
        album: String,
        fileName: String,
        mimeType: String,
        takenTimeMs: Long?,
        mode: UploadMode,
        inputStream: InputStream,
    ): UploadResult {
        val safeAlbum = DiscoveryProtocol.sanitizeAlbumName(album)
        val safeName = DiscoveryProtocol.sanitizeFileName(fileName)
        return synchronized(writeLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                savePhotoQ(safeAlbum, safeName, mimeType, takenTimeMs, mode, inputStream)
            } else {
                savePhotoLegacy(safeAlbum, safeName, mimeType, takenTimeMs, mode, inputStream)
            }
        }
    }

    override fun deletePhoto(photoId: String): Boolean = synchronized(writeLock) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deletePhotoQ(photoId)
        } else {
            deletePhotoLegacy(photoId)
        }
    }

    override fun deleteAlbum(album: String): Int {
        val safeAlbum = DiscoveryProtocol.sanitizeAlbumName(album)
        return synchronized(writeLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deleteAlbumQ(safeAlbum)
            } else {
                deleteAlbumLegacy(safeAlbum)
            }
        }
    }

    // ---- Android 10+ (API 29+) MediaStore implementation ----

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaCollection(): Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun albumRelPath(safeAlbum: String): String = AlbumLayout.albumRelativePath(safeAlbum)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listAlbumsQ(): List<AlbumInfo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${AlbumLayout.ROOT_RELATIVE_PATH}%")
        val sort = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val index = AlbumIndex()
        resolver.query(mediaCollection(), projection, selection, args, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val modIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val rel = cursor.getString(pathIdx) ?: continue
                val album = AlbumLayout.extractAlbumName(rel) ?: continue
                val id = cursor.getLong(idIdx).toString()
                val mod = cursor.getLong(modIdx)
                index.add(album, id, mod)
            }
        }
        return index.toAlbumInfos()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listPhotosQ(safeAlbum: String): List<PhotoEntry> {
        val pathWithSlash = albumRelPath(safeAlbum)
        val pathNoSlash = pathWithSlash.trimEnd('/')
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(pathWithSlash, pathNoSlash)
        val sort = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val out = mutableListOf<PhotoEntry>()
        resolver.query(mediaCollection(), projection, selection, args, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                out.add(
                    PhotoEntry(
                        id = cursor.getLong(idIdx).toString(),
                        name = cursor.getString(nameIdx) ?: "photo.jpg",
                        album = safeAlbum,
                        sizeBytes = cursor.getLong(sizeIdx),
                        dateModifiedSec = cursor.getLong(modIdx),
                        mimeType = cursor.getString(mimeIdx) ?: "image/jpeg",
                    )
                )
            }
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun savePhotoQ(
        safeAlbum: String,
        safeName: String,
        mimeType: String,
        takenTimeMs: Long?,
        mode: UploadMode,
        inputStream: InputStream,
    ): UploadResult {
        var cleared = 0
        if (mode == UploadMode.REPLACE) {
            cleared = deleteAlbumQ(safeAlbum)
        }

        val pathWithSlash = albumRelPath(safeAlbum)
        val pathNoSlash = pathWithSlash.trimEnd('/')
        var replaced = false

        // Delete existing same-name entry first so MediaStore overwrites cleanly instead of renaming to "(1)"
        val existingSelection =
            "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?) AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val existingArgs = arrayOf(pathWithSlash, pathNoSlash, safeName)
        val deletedExisting = resolver.delete(mediaCollection(), existingSelection, existingArgs)
        if (deletedExisting > 0) {
            replaced = true
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, pathWithSlash)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (takenTimeMs != null && takenTimeMs > 0L) {
                put(MediaStore.Images.Media.DATE_TAKEN, takenTimeMs)
            }
        }

        val uri = resolver.insert(mediaCollection(), values)
            ?: throw IOException("MediaStore 写入失败，无法创建媒体记录")

        var totalBytes = 0L
        try {
            val outStream = resolver.openOutputStream(uri, "w")
                ?: throw IOException("无法打开 MediaStore 输出流")
            outStream.use { out ->
                val buf = ByteArray(65_536)
                while (true) {
                    val read = inputStream.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    totalBytes += read
                }
                out.flush()
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        val id = ContentUris.parseId(uri).toString()
        val nowSec = (takenTimeMs ?: System.currentTimeMillis()) / 1000L
        return UploadResult(
            photo = PhotoEntry(
                id = id,
                name = safeName,
                album = safeAlbum,
                sizeBytes = totalBytes,
                dateModifiedSec = nowSec,
                mimeType = mimeType,
            ),
            replaced = replaced,
            clearedAlbumCount = cleared,
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deletePhotoQ(photoId: String): Boolean {
        val numericId = photoId.toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(mediaCollection(), numericId)
        return resolver.delete(uri, null, null) > 0
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteAlbumQ(safeAlbum: String): Int {
        val pathWithSlash = albumRelPath(safeAlbum)
        val pathNoSlash = pathWithSlash.trimEnd('/')
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        return resolver.delete(mediaCollection(), selection, arrayOf(pathWithSlash, pathNoSlash))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun readThumbnailQ(photoId: String): ByteArray? {
        val numericId = photoId.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(mediaCollection(), numericId)
        val bitmap = runCatching {
            resolver.loadThumbnail(uri, Size(320, 320), null)
        }.getOrNull() ?: decodeScaledFromUri(uri, 320) ?: return null
        return try {
            bitmapToJpegBytes(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    // ---- Android 8/9 (API 26-28) legacy filesystem + MediaScanner fallback ----

    @Suppress("DEPRECATION")
    private fun legacyRootDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TVAlbums")

    private fun listAlbumsLegacy(): List<AlbumInfo> {
        val root = legacyRootDir()
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val imgs = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
                if (imgs.isEmpty()) return@mapNotNull null
                val newest = imgs.maxBy { it.lastModified() }
                AlbumInfo(
                    name = dir.name,
                    photoCount = imgs.size,
                    latestModifiedSec = newest.lastModified() / 1000L,
                    coverPhotoId = AlbumLayout.encodeLegacyPhotoId(dir.name, newest.name),
                )
            }
            ?.sortedByDescending { it.latestModifiedSec }
            .orEmpty()
    }

    private fun listPhotosLegacy(safeAlbum: String): List<PhotoEntry> {
        val dir = File(legacyRootDir(), safeAlbum)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                PhotoEntry(
                    id = AlbumLayout.encodeLegacyPhotoId(safeAlbum, file.name),
                    name = file.name,
                    album = safeAlbum,
                    sizeBytes = file.length(),
                    dateModifiedSec = file.lastModified() / 1000L,
                    mimeType = AlbumLayout.guessImageMime(file.name),
                )
            }
            .orEmpty()
    }

    private fun savePhotoLegacy(
        safeAlbum: String,
        safeName: String,
        mimeType: String,
        takenTimeMs: Long?,
        mode: UploadMode,
        inputStream: InputStream,
    ): UploadResult {
        var cleared = 0
        if (mode == UploadMode.REPLACE) {
            cleared = deleteAlbumLegacy(safeAlbum)
        }
        val dir = File(legacyRootDir(), safeAlbum)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建相册目录：${dir.absolutePath}")
        }
        val target = File(dir, safeName)
        val replaced = target.exists()
        val temp = File(dir, ".tmp_${System.nanoTime()}_$safeName")
        var totalBytes = 0L
        try {
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(65_536)
                while (true) {
                    val read = inputStream.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    totalBytes += read
                }
                out.flush()
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (takenTimeMs != null && takenTimeMs > 0L) {
                target.setLastModified(takenTimeMs)
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }

        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null,
        )

        return UploadResult(
            photo = PhotoEntry(
                id = AlbumLayout.encodeLegacyPhotoId(safeAlbum, safeName),
                name = safeName,
                album = safeAlbum,
                sizeBytes = totalBytes,
                dateModifiedSec = target.lastModified() / 1000L,
                mimeType = mimeType,
            ),
            replaced = replaced,
            clearedAlbumCount = cleared,
        )
    }

    @Suppress("DEPRECATION")
    private fun deletePhotoLegacy(photoId: String): Boolean {
        val (album, name) = AlbumLayout.decodeLegacyPhotoId(photoId) ?: return false
        val file = File(File(legacyRootDir(), album), name)
        val deleted = file.delete()
        if (deleted) {
            resolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DATA} = ?",
                arrayOf(file.absolutePath),
            )
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                null,
                null,
            )
        }
        return deleted
    }

    @Suppress("DEPRECATION")
    private fun deleteAlbumLegacy(safeAlbum: String): Int {
        val dir = File(legacyRootDir(), safeAlbum)
        if (!dir.isDirectory) return 0
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        var count = 0
        for (file in files) {
            if (file.delete()) {
                count++
                resolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Images.Media.DATA} = ?",
                    arrayOf(file.absolutePath),
                )
            }
        }
        dir.delete()
        return count
    }

    private fun readThumbnailLegacy(photoId: String): ByteArray? {
        val (album, name) = AlbumLayout.decodeLegacyPhotoId(photoId) ?: return null
        val file = File(File(legacyRootDir(), album), name)
        if (!file.isFile) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.inSampleSize = AlbumLayout.thumbnailSampleSize(opts.outWidth, opts.outHeight, 320)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return try {
            bitmapToJpegBytes(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun decodeScaledFromUri(uri: Uri, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.inSampleSize = AlbumLayout.thumbnailSampleSize(opts.outWidth, opts.outHeight, maxDim)
        opts.inJustDecodeBounds = false
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun availableStorageBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(0L)

}
