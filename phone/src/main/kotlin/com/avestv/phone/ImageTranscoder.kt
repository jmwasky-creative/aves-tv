package com.avestv.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.avestv.protocol.DiscoveryProtocol
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class PreparedPhoto(
    val fileName: String,
    val mimeType: String,
    val contentLength: Long,
    val takenTimeMs: Long?,
    private val tempFile: File?,
    private val streamOpener: () -> InputStream,
) : Closeable {
    fun openStream(): InputStream = streamOpener()

    override fun close() {
        runCatching { tempFile?.delete() }
    }
}

/**
 * Prepares phone images for Android TV playback:
 * - Reads EXIF DateTimeOriginal so Aves sorts photos by capture date.
 * - When [optimizeFor4k] is enabled, converts HEIC/non-JPEG/rotated/oversized photos
 *   into upright <= 4K (3840px long edge) JPEG so older TVs decode reliably without filling storage.
 * - Normal upright JPEGs already <= 4K pass through directly with zero re-encoding loss.
 */
class ImageTranscoder(private val context: Context) {

    private val resolver = context.applicationContext.contentResolver

    fun prepare(uri: Uri, optimizeFor4k: Boolean): PreparedPhoto {
        val rawName = queryDisplayName(uri) ?: "photo_${System.currentTimeMillis()}.jpg"
        val rawMime = resolver.getType(uri) ?: guessMimeFromName(rawName)
        val rawSize = queryFileSize(uri)
        val exifMeta = readExifMetadata(uri)

        if (!optimizeFor4k) {
            val safeName = DiscoveryProtocol.sanitizeFileName(rawName, ImagePrep.extFromMime(rawMime))
            return PreparedPhoto(
                fileName = safeName,
                mimeType = rawMime,
                contentLength = rawSize,
                takenTimeMs = exifMeta.takenTimeMs,
                tempFile = null,
                streamOpener = {
                    resolver.openInputStream(uri) ?: throw IOException("无法读取所选图片")
                },
            )
        }

        val bounds = decodeBounds(uri)
        val isJpeg = rawMime.equals("image/jpeg", ignoreCase = true) ||
            rawName.endsWith(".jpg", ignoreCase = true) ||
            rawName.endsWith(".jpeg", ignoreCase = true)
        val maxSide = maxOf(bounds.first, bounds.second)
        val needsRotation = exifMeta.orientation != ExifInterface.ORIENTATION_NORMAL &&
            exifMeta.orientation != ExifInterface.ORIENTATION_UNDEFINED

        // Fast passthrough when already upright JPEG <= 4K
        if (isJpeg && !needsRotation && maxSide in 1..ImagePrep.MAX_4K_EDGE && rawSize > 0) {
            val safeName = DiscoveryProtocol.sanitizeFileName(rawName, "jpg")
            return PreparedPhoto(
                fileName = safeName,
                mimeType = "image/jpeg",
                contentLength = rawSize,
                takenTimeMs = exifMeta.takenTimeMs,
                tempFile = null,
                streamOpener = {
                    resolver.openInputStream(uri) ?: throw IOException("无法读取所选图片")
                },
            )
        }

        val decoded = decodeSampledBitmap(uri, bounds.first, bounds.second, ImagePrep.MAX_4K_EDGE)
            ?: return PreparedPhoto(
                fileName = DiscoveryProtocol.sanitizeFileName(rawName, ImagePrep.extFromMime(rawMime)),
                mimeType = rawMime,
                contentLength = rawSize,
                takenTimeMs = exifMeta.takenTimeMs,
                tempFile = null,
                streamOpener = {
                    resolver.openInputStream(uri) ?: throw IOException("无法读取所选图片")
                },
            )

        val transformed = applyOrientationAndScale(decoded, exifMeta.orientation, ImagePrep.MAX_4K_EDGE)
        if (transformed !== decoded) {
            decoded.recycle()
        }

        val cacheFile = File.createTempFile("tvpush_", ".jpg", context.cacheDir)
        try {
            FileOutputStream(cacheFile).use { out ->
                if (!transformed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IOException("JPEG 编码失败")
                }
                out.flush()
            }
        } finally {
            transformed.recycle()
        }

        val stem = rawName.substringAfterLast('/').substringBeforeLast('.', rawName)
        val jpegName = DiscoveryProtocol.sanitizeFileName("$stem.jpg", "jpg")
        return PreparedPhoto(
            fileName = jpegName,
            mimeType = "image/jpeg",
            contentLength = cacheFile.length(),
            takenTimeMs = exifMeta.takenTimeMs,
            tempFile = cacheFile,
            streamOpener = { FileInputStream(cacheFile) },
        )
    }

    private data class ExifMeta(
        val orientation: Int,
        val takenTimeMs: Long?,
    )

    private fun readExifMetadata(uri: Uri): ExifMeta = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val takenMs = ImagePrep.parseExifDate(rawDate)
            ExifMeta(orientation, takenMs)
        } ?: ExifMeta(ExifInterface.ORIENTATION_NORMAL, null)
    }.getOrDefault(ExifMeta(ExifInterface.ORIENTATION_NORMAL, null))

    private fun decodeBounds(uri: Uri): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        return opts.outWidth to opts.outHeight
    }

    private fun decodeSampledBitmap(uri: Uri, width: Int, height: Int, maxEdge: Int): Bitmap? {
        val sample = ImagePrep.inSampleSizeForLongEdge(width, height, maxEdge)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun applyOrientationAndScale(src: Bitmap, orientation: Int, maxEdge: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        val currentMax = maxOf(src.width, src.height)
        if (currentMax > maxEdge) {
            val scale = maxEdge.toFloat() / currentMax.toFloat()
            matrix.postScale(scale, scale)
        }
        if (matrix.isIdentity) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun queryFileSize(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun guessMimeFromName(name: String): String = ImagePrep.guessMimeFromName(name)

    companion object {
        private const val JPEG_QUALITY = 90
    }
}
