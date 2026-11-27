package com.avestv.protocol

import kotlinx.serialization.Serializable

@Serializable
data class TvDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val httpPort: Int = DiscoveryProtocol.HTTP_DEFAULT_PORT,
    val freeBytes: Long = 0L,
    val albumCount: Int = 0,
    val totalPhotos: Int = 0,
    val protocolVersion: Int = DiscoveryProtocol.PROTOCOL_VERSION,
)

@Serializable
data class AlbumInfo(
    val name: String,
    val photoCount: Int,
    val latestModifiedSec: Long = 0L,
    val coverPhotoId: String? = null,
)

@Serializable
data class PhotoEntry(
    val id: String,
    val name: String,
    val album: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val mimeType: String = "image/jpeg",
)

@Serializable
data class UploadResult(
    val photo: PhotoEntry,
    val replaced: Boolean,
    val clearedAlbumCount: Int = 0,
)

@Serializable
data class DeleteResult(
    val deletedCount: Int,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class DiscoveryProbeResponse(
    val magic: String = DiscoveryProtocol.RESPONSE_MAGIC,
    val protocolVersion: Int = DiscoveryProtocol.PROTOCOL_VERSION,
    val deviceId: String,
    val deviceName: String,
    val httpPort: Int = DiscoveryProtocol.HTTP_DEFAULT_PORT,
    val freeBytes: Long = 0L,
    val albumCount: Int = 0,
    val totalPhotos: Int = 0,
) {
    fun toDeviceInfo(): TvDeviceInfo = TvDeviceInfo(
        deviceId = deviceId,
        deviceName = deviceName,
        httpPort = httpPort,
        freeBytes = freeBytes,
        albumCount = albumCount,
        totalPhotos = totalPhotos,
        protocolVersion = protocolVersion,
    )
}

@Serializable
data class UsbLinkStatus(
    val connected: Boolean = false,
    val statusText: String = "等待手机通过 USB 接入",
)

@Serializable
data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String,
)
