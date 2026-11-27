package com.avestv.protocol

import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * In-memory album store for the desktop page preview. The TV APK keeps writing
 * through MediaStore; this store only exists so a browser can exercise the same HTTP API.
 */
class PreviewAlbumStore(
    private val freeBytesProvider: () -> Long = { usableSpaceBytes(defaultStoragePath()) },
) : AlbumStore {
    private val lock = Any()
    private val photos = LinkedHashMap<String, Pair<PhotoEntry, ByteArray>>()
    private var seq = 1

    override fun deviceInfo(httpPort: Int): TvDeviceInfo = synchronized(lock) {
        TvDeviceInfo(
            deviceId = "preview-tv",
            deviceName = "本机预览电视",
            httpPort = httpPort,
            freeBytes = freeBytesProvider().coerceAtLeast(0L),
            albumCount = albumsLocked().size,
            totalPhotos = photos.size,
        )
    }

    override fun listAlbums(): List<AlbumInfo> = synchronized(lock) { albumsLocked() }

    override fun listPhotos(album: String): List<PhotoEntry> = synchronized(lock) {
        photos.values.filter { it.first.album == album }.map { it.first }
    }

    override fun readThumbnail(photoId: String): ByteArray? = synchronized(lock) {
        photos[photoId]?.second
    }

    override fun savePhoto(
        album: String,
        fileName: String,
        mimeType: String,
        takenTimeMs: Long?,
        mode: UploadMode,
        inputStream: InputStream,
    ): UploadResult = synchronized(lock) {
        val data = inputStream.readBytes()
        var cleared = 0
        if (mode == UploadMode.REPLACE) cleared = deleteAlbumLocked(album)
        val existing = photos.values.firstOrNull { it.first.album == album && it.first.name == fileName }
        if (existing != null) photos.remove(existing.first.id)
        val id = "preview-${seq++}"
        val entry = PhotoEntry(
            id = id,
            name = fileName,
            album = album,
            sizeBytes = data.size.toLong(),
            dateModifiedSec = (takenTimeMs ?: System.currentTimeMillis()) / 1000L,
            mimeType = mimeType,
        )
        photos[id] = entry to data
        UploadResult(entry, replaced = existing != null, clearedAlbumCount = cleared)
    }

    override fun deletePhoto(photoId: String): Boolean = synchronized(lock) {
        photos.remove(photoId) != null
    }

    override fun deleteAlbum(album: String): Int = synchronized(lock) { deleteAlbumLocked(album) }

    private fun albumsLocked(): List<AlbumInfo> = photos.values
        .groupBy { it.first.album }
        .map { (name, items) ->
            val latest = items.maxBy { it.first.dateModifiedSec }
            AlbumInfo(name, items.size, latest.first.dateModifiedSec, latest.first.id)
        }
        .sortedBy { it.name }

    private fun deleteAlbumLocked(album: String): Int {
        val ids = photos.values.filter { it.first.album == album }.map { it.first.id }
        ids.forEach { photos.remove(it) }
        return ids.size
    }

    companion object {
        fun defaultStoragePath(): File = File(System.getProperty("user.dir", ".")).absoluteFile

        fun usableSpaceBytes(path: File = defaultStoragePath()): Long {
            var target: File? = path.absoluteFile
            while (target != null && !target.exists()) {
                target = target.parentFile
            }
            val resolved = target ?: return 0L
            val storeSpace = runCatching { Files.getFileStore(resolved.toPath()).usableSpace }.getOrNull()
            return (storeSpace ?: resolved.usableSpace).coerceAtLeast(0L)
        }
    }
}
