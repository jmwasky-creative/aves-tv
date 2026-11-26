package com.avestv.protocol

import java.io.InputStream

enum class UploadMode {
    APPEND,
    REPLACE,
}

interface AlbumStore {
    fun deviceInfo(httpPort: Int): TvDeviceInfo
    fun listAlbums(): List<AlbumInfo>
    fun listPhotos(album: String): List<PhotoEntry>
    fun readThumbnail(photoId: String): ByteArray?
    fun savePhoto(
        album: String,
        fileName: String,
        mimeType: String,
        takenTimeMs: Long?,
        mode: UploadMode,
        inputStream: InputStream,
    ): UploadResult
    fun deletePhoto(photoId: String): Boolean
    fun deleteAlbum(album: String): Int
}
