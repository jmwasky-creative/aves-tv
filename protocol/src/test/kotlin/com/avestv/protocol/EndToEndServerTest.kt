package com.avestv.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class EndToEndServerTest {

    private class InMemoryAlbumStore : AlbumStore {
        private data class Stored(
            val entry: PhotoEntry,
            val bytes: ByteArray,
        )

        private val lock = Any()
        private val seq = AtomicInteger(1)
        private val photos = ConcurrentHashMap<String, Stored>()

        override fun deviceInfo(httpPort: Int): TvDeviceInfo {
            val albums = listAlbums()
            return TvDeviceInfo(
                deviceId = "test-tv-id",
                deviceName = "测试客厅电视",
                httpPort = httpPort,
                freeBytes = 4_000_000_000L,
                albumCount = albums.size,
                totalPhotos = photos.size,
            )
        }

        override fun listAlbums(): List<AlbumInfo> = synchronized(lock) {
            photos.values.groupBy { it.entry.album }.map { (album, items) ->
                val newest = items.maxBy { it.entry.dateModifiedSec }
                AlbumInfo(
                    name = album,
                    photoCount = items.size,
                    latestModifiedSec = newest.entry.dateModifiedSec,
                    coverPhotoId = newest.entry.id,
                )
            }.sortedByDescending { it.latestModifiedSec }
        }

        override fun listPhotos(album: String): List<PhotoEntry> = synchronized(lock) {
            photos.values
                .filter { it.entry.album == album }
                .map { it.entry }
                .sortedByDescending { it.dateModifiedSec }
        }

        override fun readThumbnail(photoId: String): ByteArray? = photos[photoId]?.bytes

        override fun savePhoto(
            album: String,
            fileName: String,
            mimeType: String,
            takenTimeMs: Long?,
            mode: UploadMode,
            inputStream: InputStream,
        ): UploadResult {
            val data = inputStream.readBytes()
            return synchronized(lock) {
                var cleared = 0
                if (mode == UploadMode.REPLACE) {
                    val toRemove = photos.values.filter { it.entry.album == album }.map { it.entry.id }
                    toRemove.forEach { photos.remove(it) }
                    cleared = toRemove.size
                }
                val existing = photos.values.firstOrNull {
                    it.entry.album == album && it.entry.name == fileName
                }
                var replaced = false
                if (existing != null) {
                    photos.remove(existing.entry.id)
                    replaced = true
                }
                val id = seq.getAndIncrement().toString()
                val modSec = (takenTimeMs ?: System.currentTimeMillis()) / 1000L
                val entry = PhotoEntry(
                    id = id,
                    name = fileName,
                    album = album,
                    sizeBytes = data.size.toLong(),
                    dateModifiedSec = modSec,
                    mimeType = mimeType,
                )
                photos[id] = Stored(entry, data)
                UploadResult(photo = entry, replaced = replaced, clearedAlbumCount = cleared)
            }
        }

        override fun deletePhoto(photoId: String): Boolean = synchronized(lock) {
            photos.remove(photoId) != null
        }

        override fun deleteAlbum(album: String): Int = synchronized(lock) {
            val ids = photos.values.filter { it.entry.album == album }.map { it.entry.id }
            ids.forEach { photos.remove(it) }
            ids.size
        }
    }

    @Test
    fun udpDiscoveryAndHttpAlbumLifecycleWorkEndToEnd() {
        val store = InMemoryAlbumStore()
        val mutations = AtomicInteger(0)
        val server = StreamingHttpServer(port = 0, store = store, onMutation = { mutations.incrementAndGet() })
        server.start()
        val udp = UdpDiscoveryResponder(port = 0) { store.deviceInfo(server.boundPort) }
        udp.start()

        try {
            // 1. Auto-discover TV over UDP
            val discovered = DatagramSocket().use { client ->
                client.soTimeout = 2_000
                val req = DiscoveryProtocol.encodeDiscoveryRequest()
                client.send(DatagramPacket(req, req.size, InetAddress.getByName("127.0.0.1"), udp.boundPort))
                val buf = ByteArray(4096)
                val reply = DatagramPacket(buf, buf.size)
                client.receive(reply)
                DiscoveryProtocol.decodeDiscoveryResponse(reply.data, reply.length)
            }
            assertNotNull(discovered)
            assertEquals("测试客厅电视", discovered!!.deviceName)
            assertEquals(server.boundPort, discovered.httpPort)

            val base = "http://127.0.0.1:${discovered.httpPort}"
            val albumEncoded = URLEncoder.encode("妈妈的夏天", "UTF-8").replace("+", "%20")

            // 2. Upload first photo with Content-Length
            val payload1 = ByteArray(128_000) { (it % 251).toByte() }
            val up1Json = httpRequest(
                method = "POST",
                url = "$base/api/v1/albums/$albumEncoded/upload?name=IMG_0001.jpg&mode=append&takenMs=1700000000000",
                body = payload1,
                chunked = false,
            )
            val up1 = DiscoveryProtocol.json.decodeFromString<UploadResult>(String(up1Json, StandardCharsets.UTF_8))
            assertEquals("妈妈的夏天", up1.photo.album)
            assertEquals("IMG_0001.jpg", up1.photo.name)
            assertEquals(payload1.size.toLong(), up1.photo.sizeBytes)
            assertEquals(false, up1.replaced)

            // 3. Re-upload same filename with Transfer-Encoding: chunked -> idempotent overwrite
            val payload1Updated = "updated-jpeg-bytes".toByteArray()
            val up1AgainJson = httpRequest(
                method = "POST",
                url = "$base/api/v1/albums/$albumEncoded/upload?name=IMG_0001.jpg&mode=append",
                body = payload1Updated,
                chunked = true,
            )
            val up1Again = DiscoveryProtocol.json.decodeFromString<UploadResult>(String(up1AgainJson, StandardCharsets.UTF_8))
            assertTrue(up1Again.replaced)

            // 4. Verify thumbnail returns exact updated bytes and album has 1 photo (no duplicate!)
            val thumbBytes = httpRequest("GET", "$base/api/v1/photos/${up1Again.photo.id}/thumb")
            assertArrayEquals(payload1Updated, thumbBytes)

            val photosJson = httpRequest("GET", "$base/api/v1/albums/$albumEncoded/photos")
            val photos = DiscoveryProtocol.json.decodeFromString<List<PhotoEntry>>(String(photosJson, StandardCharsets.UTF_8))
            assertEquals(1, photos.size)

            // 5. Upload second photo with mode=replace -> clears previous photo cleanly
            val payload2 = "fresh-batch-photo".toByteArray()
            val up2Json = httpRequest(
                method = "POST",
                url = "$base/api/v1/albums/$albumEncoded/upload?name=IMG_0002.jpg&mode=replace",
                body = payload2,
                chunked = false,
            )
            val up2 = DiscoveryProtocol.json.decodeFromString<UploadResult>(String(up2Json, StandardCharsets.UTF_8))
            assertEquals(1, up2.clearedAlbumCount)

            val afterReplace = DiscoveryProtocol.json.decodeFromString<List<PhotoEntry>>(
                String(httpRequest("GET", "$base/api/v1/albums/$albumEncoded/photos"), StandardCharsets.UTF_8)
            )
            assertEquals(1, afterReplace.size)
            assertEquals("IMG_0002.jpg", afterReplace[0].name)

            // 6. Delete single photo, then verify album disappears
            val delJson = httpRequest("DELETE", "$base/api/v1/photos/${up2.photo.id}")
            val del = DiscoveryProtocol.json.decodeFromString<DeleteResult>(String(delJson, StandardCharsets.UTF_8))
            assertEquals(1, del.deletedCount)

            val albumsAfter = DiscoveryProtocol.json.decodeFromString<List<AlbumInfo>>(
                String(httpRequest("GET", "$base/api/v1/albums"), StandardCharsets.UTF_8)
            )
            assertTrue(albumsAfter.isEmpty())
            assertTrue(mutations.get() >= 4)
        } finally {
            udp.close()
            server.close()
        }
    }

    private fun httpRequest(
        method: String,
        url: String,
        body: ByteArray? = null,
        chunked: Boolean = false,
    ): ByteArray {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 3_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "image/jpeg")
                if (chunked) {
                    setChunkedStreamingMode(4096)
                } else {
                    setFixedLengthStreamingMode(body.size)
                }
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            assertEquals("HTTP $method $url failed: ${out.toString("UTF-8")}", 200, code)
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
