package com.avestv.phone

import com.avestv.protocol.AlbumInfo
import com.avestv.protocol.AlbumStore
import com.avestv.protocol.PhotoEntry
import com.avestv.protocol.StreamingHttpServer
import com.avestv.protocol.TvDeviceInfo
import com.avestv.protocol.UploadMode
import com.avestv.protocol.UploadResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TvApiClientEndToEndTest {

    private class FakeTvStore : AlbumStore {
        private val seq = AtomicInteger(1)
        private val items = ConcurrentHashMap<String, Pair<PhotoEntry, ByteArray>>()

        override fun deviceInfo(httpPort: Int): TvDeviceInfo {
            val albums = listAlbums()
            return TvDeviceInfo(
                deviceId = "tv-demo",
                deviceName = "卧室电视",
                httpPort = httpPort,
                freeBytes = 10_000_000_000L,
                albumCount = albums.size,
                totalPhotos = items.size,
            )
        }

        override fun listAlbums(): List<AlbumInfo> =
            items.values.groupBy { it.first.album }.map { (name, list) ->
                val newest = list.maxBy { it.first.dateModifiedSec }
                AlbumInfo(name, list.size, newest.first.dateModifiedSec, newest.first.id)
            }

        override fun listPhotos(album: String): List<PhotoEntry> =
            items.values.filter { it.first.album == album }.map { it.first }

        override fun readThumbnail(photoId: String): ByteArray? = items[photoId]?.second

        override fun savePhoto(
            album: String,
            fileName: String,
            mimeType: String,
            takenTimeMs: Long?,
            mode: UploadMode,
            inputStream: InputStream,
        ): UploadResult {
            val bytes = inputStream.readBytes()
            var cleared = 0
            if (mode == UploadMode.REPLACE) {
                cleared = deleteAlbum(album)
            }
            val dup = items.values.firstOrNull { it.first.album == album && it.first.name == fileName }
            var replaced = false
            if (dup != null) {
                items.remove(dup.first.id)
                replaced = true
            }
            val id = seq.getAndIncrement().toString()
            val entry = PhotoEntry(
                id = id,
                name = fileName,
                album = album,
                sizeBytes = bytes.size.toLong(),
                dateModifiedSec = (takenTimeMs ?: 1_700_000_000_000L) / 1000L,
                mimeType = mimeType,
            )
            items[id] = entry to bytes
            return UploadResult(entry, replaced, cleared)
        }

        override fun deletePhoto(photoId: String): Boolean = items.remove(photoId) != null

        override fun deleteAlbum(album: String): Int {
            val ids = items.values.filter { it.first.album == album }.map { it.first.id }
            ids.forEach { items.remove(it) }
            return ids.size
        }
    }

    @Test
    fun okHttpClientTalksToStreamingServerForFullAlbumFlow() {
        val store = FakeTvStore()
        val server = StreamingHttpServer(port = 0, store = store)
        server.start()
        try {
            val client = TvApiClient()
            val info = client.probeInfo("127.0.0.1", server.boundPort)
            assertNotNull(info)
            assertEquals("卧室电视", info!!.deviceName)

            val tv = DiscoveredTv("127.0.0.1", info, "test")
            val sampleBytes = ByteArray(100_000) { (it * 7 % 256).toByte() }
            val prepared = PreparedPhoto(
                fileName = "海边_01.jpg",
                mimeType = "image/jpeg",
                contentLength = sampleBytes.size.toLong(),
                takenTimeMs = 1_710_000_000_000L,
                tempFile = null,
                streamOpener = { ByteArrayInputStream(sampleBytes) },
            )
            val upload = client.uploadPhoto(tv, "家庭旅行", prepared, UploadMode.APPEND)
            assertEquals("家庭旅行", upload.photo.album)
            assertEquals("海边_01.jpg", upload.photo.name)

            val albums = client.listAlbums(tv)
            assertEquals(1, albums.size)
            assertEquals("家庭旅行", albums[0].name)

            val thumb = client.fetchThumbnailBytes(tv, upload.photo.id)
            assertArrayEquals(sampleBytes, thumb)

            assertTrue(client.deletePhoto(tv, upload.photo.id))
            assertTrue(client.listPhotos(tv, "家庭旅行").isEmpty())
        } finally {
            server.close()
        }
    }
}
