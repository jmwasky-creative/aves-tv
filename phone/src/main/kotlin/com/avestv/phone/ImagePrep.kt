package com.avestv.phone

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure image-prep rules used by [ImageTranscoder]. No Android types, so JVM tests can lock them.
 */
internal object ImagePrep {
    const val MAX_4K_EDGE = 3840

    fun parseExifDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            fmt.parse(raw.trim())?.time
        }.getOrNull()
    }

    /**
     * Power-of-two [android.graphics.BitmapFactory.Options.inSampleSize] that keeps the long
     * edge from dropping below [maxEdge] during the coarse decode. A later exact scale pass
     * finishes the resize.
     */
    fun inSampleSizeForLongEdge(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        if (width > 0 && height > 0 && maxEdge > 0) {
            while (sample < SAMPLE_CAP &&
                maxOf(width / (sample * 2), height / (sample * 2)) >= maxEdge
            ) {
                sample *= 2
            }
        }
        return sample
    }

    fun guessMimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    fun extFromMime(mime: String): String = when {
        mime.contains("png", ignoreCase = true) -> "png"
        mime.contains("webp", ignoreCase = true) -> "webp"
        mime.contains("heic", ignoreCase = true) -> "heic"
        mime.contains("heif", ignoreCase = true) -> "heif"
        mime.contains("gif", ignoreCase = true) -> "gif"
        else -> "jpg"
    }

    private const val SAMPLE_CAP = 1 shl 16
}
