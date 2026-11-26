package com.avestv.protocol

import java.util.Base64

/**
 * Path, id, and thumbnail rules shared by the TV MediaStore repository.
 * Kept free of Android APIs so the rules can be tested on the JVM.
 */
object AlbumLayout {
    const val ROOT_RELATIVE_PATH = "Pictures/TVAlbums/"

    fun albumRelativePath(safeAlbum: String): String = "$ROOT_RELATIVE_PATH$safeAlbum/"

    fun extractAlbumName(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/')
        if (!normalized.startsWith(ROOT_RELATIVE_PATH)) return null
        val sub = normalized.removePrefix(ROOT_RELATIVE_PATH).trim('/')
        if (sub.isEmpty()) return null
        return sub.substringBefore('/')
    }

    fun encodeLegacyPhotoId(album: String, name: String): String =
        Base64.getUrlEncoder().encodeToString("$album/$name".toByteArray(Charsets.UTF_8))

    /**
     * Decodes a legacy photo id and re-sanitizes both parts so a crafted id
     * cannot escape `Pictures/TVAlbums/<album>/<file>`.
     */
    fun decodeLegacyPhotoId(id: String): Pair<String, String>? {
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(id), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val slash = decoded.indexOf('/')
        if (slash <= 0 || slash >= decoded.length - 1) return null
        val album = DiscoveryProtocol.sanitizeAlbumName(decoded.substring(0, slash))
        val name = DiscoveryProtocol.sanitizeFileName(decoded.substring(slash + 1))
        return album to name
    }

    fun guessImageMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> "image/jpeg"
    }

    /**
     * Largest power-of-two sample size that keeps BOTH edges at or above [target].
     * Stops before the sample size can overflow a 32-bit int.
     */
    fun thumbnailSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (sample < SAMPLE_CAP &&
            width / (sample * 2) >= target &&
            height / (sample * 2) >= target
        ) {
            sample *= 2
        }
        return sample
    }

    private const val SAMPLE_CAP = 1 shl 16
}

/**
 * Folds MediaStore rows into album summaries.
 * The cover is the first photo that holds the newest timestamp, independent of query order.
 */
class AlbumIndex {
    private val counts = LinkedHashMap<String, Int>()
    private val latest = HashMap<String, Long>()
    private val covers = HashMap<String, String>()

    fun add(album: String, photoId: String, modifiedSec: Long) {
        counts[album] = (counts[album] ?: 0) + 1
        val known = latest[album]
        if (known == null || modifiedSec > known) {
            latest[album] = modifiedSec
            covers[album] = photoId
        }
    }

    fun toAlbumInfos(): List<AlbumInfo> =
        counts.map { (name, count) ->
            AlbumInfo(
                name = name,
                photoCount = count,
                latestModifiedSec = latest[name] ?: 0L,
                coverPhotoId = covers[name],
            )
        }.sortedByDescending { it.latestModifiedSec }
}
