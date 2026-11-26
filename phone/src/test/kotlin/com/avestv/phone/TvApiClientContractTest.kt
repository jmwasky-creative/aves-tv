package com.avestv.phone

import com.avestv.protocol.AlbumInfo
import com.avestv.protocol.AlbumStore
import com.avestv.protocol.DiscoveryProtocol
import com.avestv.protocol.PhotoEntry
import com.avestv.protocol.StreamingHttpServer
import com.avestv.protocol.TvDeviceInfo
import com.avestv.protocol.UploadMode
import com.avestv.protocol.UploadResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TvApiClientContractTest {

    private class ScriptedStore : AlbumStore {
        var deviceId: String = "tv-contract"
        var failSavesWith: String? = null
        var failLists: Boolean = false
        val lastTakenMs = AtomicReference<Long?>(null)
        private val seq = AtomicInteger(1)
        private val items = ConcurrentHashMap<String, Pair<PhotoEntry, ByteArray>>()

        override fun deviceInfo(httpPort: Int): TvDeviceInfo = TvDeviceInfo(
            deviceId = deviceId,
            deviceName = "合约电视",
            httpPort = httpPort,
            freeBytes = 99L,
            albumCount = listAlbums().size,
            totalPhotos = items.size,
        )

        override fun listAlbums(): List<AlbumInfo> {
            if (failLists) throw IllegalStateException("album index offline")
            return items.values.groupBy { it.first.album }.map { (name, rows) ->
                AlbumInfo(name, rows.size, rows.maxOf { it.first.dateModifiedSec }, rows.first().first.id)
            }
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
            failSavesWith?.let { throw IllegalArgumentException(it) }
            lastTakenMs.set(takenTimeMs)
            var cleared = 0
            if (mode == UploadMode.REPLACE) cleared = deleteAlbum(album)
            val dup = items.values.firstOrNull { it.first.album == album && it.first.name == fileName }
            if (dup != null) items.remove(dup.first.id)
            val id = "ph ${seq.getAndIncrement()}"
            val entry = PhotoEntry(
                id = id,
                name = fileName,
                album = album,
                sizeBytes = bytes.size.toLong(),
                dateModifiedSec = (takenTimeMs ?: 1_700_000_000_000L) / 1000L,
                mimeType = mimeType,
            )
            items[id] = entry to bytes
            return UploadResult(entry, replaced = dup != null, clearedAlbumCount = cleared)
        }

        override fun deletePhoto(photoId: String): Boolean = items.remove(photoId) != null

        override fun deleteAlbum(album: String): Int {
            val ids = items.values.filter { it.first.album == album }.map { it.first.id }
            ids.forEach { items.remove(it) }
            return ids.size
        }
    }

    private val client = TvApiClient()

    @Test
    fun clientCoversReplaceDeleteChunkedUploadAndUnicodeNames() {
        val store = ScriptedStore()
        val server = StreamingHttpServer(port = 0, store = store)
        server.start()
        try {
            val info = client.probeInfo("127.0.0.1", server.boundPort)
            val tv = DiscoveredTv("127.0.0.1", info!!, "test")

            val first = client.uploadPhoto(tv, "家庭 相册", photo("海边 01.jpg", "one".toByteArray(), 1_710_000_000_000L), UploadMode.APPEND)
            assertEquals("家庭 相册", first.photo.album)
            assertEquals("海边 01.jpg", first.photo.name)
            assertEquals(1_710_000_000L, first.photo.dateModifiedSec)

            val replaced = client.uploadPhoto(
                tv,
                "家庭 相册",
                photo("cover.jpg", "NEW".toByteArray(), takenTimeMs = 0L),
                UploadMode.REPLACE,
            )
            assertEquals(1, replaced.clearedAlbumCount)
            assertNull(store.lastTakenMs.get())
            assertEquals(listOf("cover.jpg"), client.listPhotos(tv, "家庭 相册").map { it.name })

            val chunked = photo("raw.bin", ByteArray(70_000) { (it % 251).toByte() }, takenTimeMs = null, length = -1L)
            val uploaded = client.uploadPhoto(tv, "家庭 相册", chunked, UploadMode.APPEND)
            assertNull(store.lastTakenMs.get())
            assertArrayEquals(ByteArray(70_000) { (it % 251).toByte() }, client.fetchThumbnailBytes(tv, uploaded.photo.id))

            assertEquals(2, client.deleteAlbum(tv, "家庭 相册"))
            assertTrue(client.listPhotos(tv, "家庭 相册").isEmpty())
            assertEquals(0, client.deleteAlbum(tv, "不存在的相册"))
            assertEquals(false, client.deletePhoto(tv, "missing id"))
            assertNull(client.fetchThumbnailBytes(tv, "missing id"))
        } finally {
            server.close()
        }
    }

    @Test
    fun probeAndErrorsStayExplicit() {
        val store = ScriptedStore()
        val server = StreamingHttpServer(port = 0, store = store)
        server.start()
        try {
            store.deviceId = ""
            assertNull(client.probeInfo("127.0.0.1", server.boundPort))
            store.deviceId = "tv-contract"

            store.failLists = true
            val tv = DiscoveredTv(
                "127.0.0.1",
                TvDeviceInfo(deviceId = "tv-contract", deviceName = "合约电视", httpPort = server.boundPort),
                "test",
            )
            val listError = runCatching { client.listAlbums(tv) }.exceptionOrNull()
            assertTrue(listError is IOException)
            assertTrue(listError!!.message!!.contains("500"))

            store.failLists = false
            store.failSavesWith = "拒绝空文件"
            val uploadError = runCatching {
                client.uploadPhoto(tv, "相册", photo("a.jpg", byteArrayOf(1), null), UploadMode.APPEND)
            }.exceptionOrNull()
            assertTrue(uploadError is IOException)
            assertTrue(uploadError!!.message!!.contains("a.jpg"))
            assertTrue(uploadError.message!!.contains("拒绝空文件"))
        } finally {
            server.close()
        }
    }

    @Test
    fun probeInfoReturnsNullWhenTvIsUnreachableOrMalformed() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertNull(client.probeInfo("127.0.0.1", closedPort))

        ServerSocket(0).use { listener ->
            val accepted = Thread {
                listener.accept().use { socket ->
                    socket.soTimeout = 1_000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(1024)
                    runCatching { input.read(buffer) }
                    val body = "not-json"
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            ).toByteArray(StandardCharsets.US_ASCII)
                    )
                    socket.getOutputStream().flush()
                }
            }
            accepted.start()
            assertNull(client.probeInfo("127.0.0.1", listener.localPort))
            accepted.join(2_000)
        }

        ServerSocket(0).use { listener ->
            val accepted = Thread {
                listener.accept().use { socket ->
                    drainRequest(socket)
                    val body = """{"error":"busy"}"""
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            ).toByteArray(StandardCharsets.US_ASCII)
                    )
                }
            }
            accepted.start()
            assertNull(client.probeInfo("127.0.0.1", listener.localPort))
            accepted.join(2_000)
        }

        assertTrue(DiscoveryProtocol.isPrivateLanIpv4("192.168.1.1"))
    }

    private fun drainRequest(socket: Socket) {
        socket.soTimeout = 1_000
        val input = socket.getInputStream()
        val buffer = ByteArray(2048)
        runCatching { input.read(buffer) }
    }

    private fun photo(name: String, bytes: ByteArray, takenTimeMs: Long?, length: Long = bytes.size.toLong()) =
        PreparedPhoto(
            fileName = name,
            mimeType = "image/jpeg",
            contentLength = length,
            takenTimeMs = takenTimeMs,
            tempFile = null,
            streamOpener = { ByteArrayInputStream(bytes) },
        )
}
