package com.avestv.phone

import com.avestv.protocol.AlbumInfo
import com.avestv.protocol.DeleteResult
import com.avestv.protocol.DiscoveryProtocol
import com.avestv.protocol.PhotoEntry
import com.avestv.protocol.TvDeviceInfo
import com.avestv.protocol.UploadMode
import com.avestv.protocol.UploadResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TvApiClient {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(450, TimeUnit.MILLISECONDS)
        .writeTimeout(450, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val transferClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun probeInfo(ip: String, port: Int = DiscoveryProtocol.HTTP_DEFAULT_PORT): TvDeviceInfo? {
        val req = Request.Builder()
            .url("http://$ip:$port/api/v1/info")
            .get()
            .build()
        return runCatching {
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val info = DiscoveryProtocol.json.decodeFromString<TvDeviceInfo>(body)
                if (info.deviceId.isNotBlank()) info else null
            }
        }.getOrNull()
    }

    fun listAlbums(tv: DiscoveredTv): List<AlbumInfo> {
        val req = Request.Builder()
            .url("${tv.baseUrl}/api/v1/albums")
            .get()
            .build()
        transferClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("读取相册列表失败 (${resp.code})")
            return DiscoveryProtocol.json.decodeFromString(body)
        }
    }

    fun listPhotos(tv: DiscoveredTv, album: String): List<PhotoEntry> {
        val req = Request.Builder()
            .url("${tv.baseUrl}/api/v1/albums/${encodePath(album)}/photos")
            .get()
            .build()
        transferClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("读取照片列表失败 (${resp.code})")
            return DiscoveryProtocol.json.decodeFromString(body)
        }
    }

    fun fetchThumbnailBytes(tv: DiscoveredTv, photoId: String): ByteArray? {
        val req = Request.Builder()
            .url("${tv.baseUrl}/api/v1/photos/${encodePath(photoId)}/thumb")
            .get()
            .build()
        return runCatching {
            transferClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        }.getOrNull()
    }

    fun uploadPhoto(
        tv: DiscoveredTv,
        album: String,
        prepared: PreparedPhoto,
        mode: UploadMode,
    ): UploadResult {
        val modeStr = if (mode == UploadMode.REPLACE) "replace" else "append"
        val query = buildString {
            append("name=").append(encodeQuery(prepared.fileName))
            append("&mime=").append(encodeQuery(prepared.mimeType))
            append("&mode=").append(modeStr)
            if (prepared.takenTimeMs != null && prepared.takenTimeMs > 0L) {
                append("&takenMs=").append(prepared.takenTimeMs)
            }
        }
        val mediaType = prepared.mimeType.toMediaType()
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength(): Long = prepared.contentLength
            override fun writeTo(sink: BufferedSink) {
                prepared.openStream().use { input ->
                    val buf = ByteArray(65_536)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("${tv.baseUrl}/api/v1/albums/${encodePath(album)}/upload?$query")
            .post(body)
            .build()
        transferClient.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("上传 ${prepared.fileName} 失败 (${resp.code}): $respBody")
            }
            return DiscoveryProtocol.json.decodeFromString(respBody)
        }
    }

    fun deletePhoto(tv: DiscoveredTv, photoId: String): Boolean {
        val req = Request.Builder()
            .url("${tv.baseUrl}/api/v1/photos/${encodePath(photoId)}")
            .delete()
            .build()
        transferClient.newCall(req).execute().use { resp ->
            return resp.isSuccessful
        }
    }

    fun deleteAlbum(tv: DiscoveredTv, album: String): Int {
        val req = Request.Builder()
            .url("${tv.baseUrl}/api/v1/albums/${encodePath(album)}")
            .delete()
            .build()
        transferClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("清空相册失败 (${resp.code})")
            return DiscoveryProtocol.json.decodeFromString<DeleteResult>(body).deletedCount
        }
    }

    private fun encodePath(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
