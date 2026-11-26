package com.avestv.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamingHttpServerTest {

    private class MemoryStore : AlbumStore {
        var saveError: RuntimeException? = null
        private val seq = AtomicInteger(1)
        private val photos = ConcurrentHashMap<String, Pair<PhotoEntry, ByteArray>>()

        override fun deviceInfo(httpPort: Int): TvDeviceInfo {
            val albums = listAlbums()
            return TvDeviceInfo(
                deviceId = "tv-http",
                deviceName = "协议测试电视",
                httpPort = httpPort,
                freeBytes = 1L,
                albumCount = albums.size,
                totalPhotos = photos.size,
            )
        }

        override fun listAlbums(): List<AlbumInfo> = photos.values
            .groupBy { it.first.album }
            .map { (name, items) ->
                AlbumInfo(name, items.size, items.maxOf { it.first.dateModifiedSec }, items.first().first.id)
            }

        override fun listPhotos(album: String): List<PhotoEntry> =
            photos.values.filter { it.first.album == album }.map { it.first }

        override fun readThumbnail(photoId: String): ByteArray? = photos[photoId]?.second

        override fun savePhoto(
            album: String,
            fileName: String,
            mimeType: String,
            takenTimeMs: Long?,
            mode: UploadMode,
            inputStream: InputStream,
        ): UploadResult {
            val data = inputStream.readBytes()
            saveError?.let { throw it }
            var cleared = 0
            if (mode == UploadMode.REPLACE) cleared = deleteAlbum(album)
            val existing = photos.values.firstOrNull { it.first.album == album && it.first.name == fileName }
            if (existing != null) photos.remove(existing.first.id)
            val id = "id/${seq.getAndIncrement()}="
            val entry = PhotoEntry(
                id = id,
                name = fileName,
                album = album,
                sizeBytes = data.size.toLong(),
                dateModifiedSec = (takenTimeMs ?: 1_700_000_000_000L) / 1000L,
                mimeType = mimeType,
            )
            photos[id] = entry to data
            return UploadResult(entry, replaced = existing != null, clearedAlbumCount = cleared)
        }

        override fun deletePhoto(photoId: String): Boolean = photos.remove(photoId) != null

        override fun deleteAlbum(album: String): Int {
            val ids = photos.values.filter { it.first.album == album }.map { it.first.id }
            ids.forEach { photos.remove(it) }
            return ids.size
        }
    }

    private lateinit var store: MemoryStore
    private lateinit var server: StreamingHttpServer
    private val mutations = AtomicInteger()

    @Before
    fun setUp() {
        store = MemoryStore()
        mutations.set(0)
        server = StreamingHttpServer(port = 0, store = store, onMutation = { mutations.incrementAndGet() })
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        server.close()
    }

    @Test
    fun rejectsBadRequestsUnknownRoutesAndWrongMethodsWithoutMutating() {
        assertEquals(400, statusOf(sendRaw("GET\r\n\r\n".toByteArray())))
        assertEquals(400, statusOf(http("POST", "/api/v1/albums/trip/upload")))
        assertEquals(404, statusOf(http("GET", "/nope")))
        assertEquals(404, statusOf(http("GET", "/api/v1/missing")))
        assertEquals(405, statusOf(http("POST", "/api/v1/info")))
        assertEquals(405, statusOf(http("PUT", "/api/v1/albums")))
        assertEquals(405, statusOf(http("GET", "/api/v1/albums/trip/upload")))
        assertEquals(405, statusOf(http("POST", "/api/v1/photos/1")))

        val missingThumb = http("GET", "/api/v1/photos/missing/thumb")
        assertEquals(404, statusOf(missingThumb))
        assertTrue(bodyOf(missingThumb).contains("Thumbnail Not Found"))

        val missingDelete = http("DELETE", "/api/v1/photos/missing")
        assertEquals(404, statusOf(missingDelete))
        assertEquals(0, mutations.get())

        val tooLong = buildString {
            append("GET /api/v1/info HTTP/1.1\r\nX-Padding: ")
            append("a".repeat(20_000))
            append("\r\n\r\n")
        }
        assertEquals(400, statusOf(sendRaw(tooLong.toByteArray(StandardCharsets.US_ASCII))))
    }

    @Test
    fun uploadHonorsSanitizedNamesMimeHeadersChunkingAndIsolation() {
        val traversed = http(
            method = "POST",
            path = "/api/v1/albums/..%2F..%2FWindows/upload?name=..%2F..%2Fetc%2Fpasswd&mode=RePlAcE&takenMs=1710000000000",
            body = "abcd".toByteArray(StandardCharsets.US_ASCII),
            contentType = "image/png; charset=binary",
        )
        assertEquals(200, statusOf(traversed))
        val saved = DiscoveryProtocol.json.decodeFromString<UploadResult>(bodyOf(traversed))
        assertEquals("Windows", saved.photo.album)
        assertEquals("passwd.png", saved.photo.name)
        assertEquals("image/png", saved.photo.mimeType)
        assertEquals(1_710_000_000L, saved.photo.dateModifiedSec)
        assertEquals("abcd", String(store.readThumbnail(saved.photo.id)!!))

        val limitedHeader = (
            "POST /api/v1/albums/Windows/upload?name=short.jpg HTTP/1.1\r\n" +
                "Host: t\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\nConnection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII) + "ABCDEFGH".toByteArray(StandardCharsets.US_ASCII)
        val shortSaved = DiscoveryProtocol.json.decodeFromString<UploadResult>(bodyOf(sendRaw(limitedHeader)))
        assertEquals("ABCD", String(store.readThumbnail(shortSaved.photo.id)!!))

        val chunked = (
            "POST /api/v1/albums/Windows/upload?name=chunk.bin&mime=application/octet-stream HTTP/1.1\r\n" +
                "Host: t\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\nConnection: close\r\n\r\n" +
                "4\r\nA\r\nB\r\n3;note=x\r\nhey\r\n0\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        val chunkSaved = DiscoveryProtocol.json.decodeFromString<UploadResult>(bodyOf(sendRaw(chunked)))
        assertEquals("A\r\nBhey", String(store.readThumbnail(chunkSaved.photo.id)!!, StandardCharsets.ISO_8859_1))

        val headerName = (
            "POST /api/v1/albums/%E5%AE%B6%E5%BA%AD/upload HTTP/1.1\r\nHost: t\r\nContent-Length: 0\r\n" +
                "X-File-Name: %E6%B5%B7%E8%BE%B9%2001.jpg\r\nContent-Type: image/jpeg\r\nConnection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        val named = DiscoveryProtocol.json.decodeFromString<UploadResult>(bodyOf(sendRaw(headerName)))
        assertEquals("家庭", named.photo.album)
        assertEquals("海边 01.jpg", named.photo.name)
        assertEquals(0L, named.photo.sizeBytes)

        assertEquals(200, statusOf(http("POST", "/api/v1/albums/other/upload?name=keep.jpg", body = byteArrayOf(1))))
        val replaced = http("POST", "/api/v1/albums/other/upload?name=fresh.jpg&mode=replace", body = byteArrayOf(2, 3))
        val replaceResult = DiscoveryProtocol.json.decodeFromString<UploadResult>(bodyOf(replaced))
        assertEquals(1, replaceResult.clearedAlbumCount)
        val otherPhotos = DiscoveryProtocol.json.decodeFromString<List<PhotoEntry>>(
            bodyOf(http("GET", "/api/v1/albums/other/photos"))
        )
        assertEquals(listOf("fresh.jpg"), otherPhotos.map { it.name })
        assertFalse(store.listPhotos("Windows").isEmpty())

        val wiped = DiscoveryProtocol.json.decodeFromString<DeleteResult>(
            bodyOf(http("DELETE", "/api/v1/albums/other"))
        )
        assertEquals(1, wiped.deletedCount)
        assertTrue(store.listPhotos("other").isEmpty())
        assertFalse(store.listPhotos("Windows").isEmpty())

        val encodedId = java.net.URLEncoder.encode(chunkSaved.photo.id, "UTF-8").replace("+", "%20")
        val thumb = http("GET", "/api/v1/photos/$encodedId/thumb")
        assertEquals(200, statusOf(thumb))
        assertTrue(thumb.contains("Content-Type: image/jpeg"))
        assertEquals("A\r\nBhey", bodyOf(thumb))

        val deleted = http("DELETE", "/api/v1/photos/$encodedId")
        assertEquals(1, DiscoveryProtocol.json.decodeFromString<DeleteResult>(bodyOf(deleted)).deletedCount)
        assertNull(store.readThumbnail(chunkSaved.photo.id))

        val info = DiscoveryProtocol.json.decodeFromString<TvDeviceInfo>(bodyOf(http("GET", "/api/v1/info")))
        assertEquals("协议测试电视", info.deviceName)
        assertEquals(server.boundPort, info.httpPort)
        assertTrue(info.totalPhotos >= 1)
        assertTrue(mutations.get() >= 6)
    }

    @Test
    fun expectContinueAndStoreFailure() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2_000)
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            out.write(
                (
                    "POST /api/v1/albums/cont/upload?name=c.jpg HTTP/1.1\r\n" +
                        "Host: t\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
            )
            out.flush()
            val prelude = readUntil(socket.getInputStream(), "\r\n\r\n")
            assertTrue(prelude.startsWith("HTTP/1.1 100"))
            out.write("JPEG".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val rest = readEof(socket.getInputStream())
            assertTrue(rest.contains("HTTP/1.1 200"))
            assertTrue(rest.contains("c.jpg"))
        }
        assertEquals("JPEG", String(store.listPhotos("cont").let { store.readThumbnail(it.single().id)!! }))

        val before = mutations.get()
        store.saveError = IllegalStateException("disk full")
        val failed = http("POST", "/api/v1/albums/cont/upload?name=boom.jpg", body = byteArrayOf(9))
        assertEquals(500, statusOf(failed))
        assertEquals("disk full", DiscoveryProtocol.json.decodeFromString<ErrorResponse>(bodyOf(failed)).error)
        assertEquals(before, mutations.get())
        assertTrue(store.listPhotos("cont").none { it.name == "boom.jpg" })
    }

    @Test
    fun concurrentUploadsStayIsolated() {
        val pool = Executors.newFixedThreadPool(12)
        val ready = CountDownLatch(12)
        val done = CountDownLatch(12)
        val failures = AtomicInteger()
        try {
            repeat(12) { index ->
                pool.execute {
                    ready.countDown()
                    assertTrue(ready.await(3, TimeUnit.SECONDS))
                    val response = http(
                        "POST",
                        "/api/v1/albums/batch/upload?name=p$index.jpg",
                        body = ByteArray(32_000) { index.toByte() },
                    )
                    if (statusOf(response) != 200) failures.incrementAndGet()
                    done.countDown()
                }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, failures.get())
        val photos = store.listPhotos("batch")
        assertEquals(12, photos.map { it.name }.toSet().size)
        photos.forEach { photo ->
            val bytes = store.readThumbnail(photo.id)
            assertNotNull(bytes)
            assertEquals(32_000, bytes!!.size)
            assertTrue(bytes.all { it == bytes[0] })
        }
    }

    @Test
    fun doubleStartKeepsTheSamePort() {
        val port = server.boundPort
        server.start()
        assertEquals(port, server.boundPort)
        assertEquals(200, statusOf(http("GET", "/api/v1/info")))
    }

    @Test
    fun udpResponderIgnoresGarbageAndAnswersTrimmedProbes() {
        val udp = UdpDiscoveryResponder(port = 0) {
            TvDeviceInfo(deviceId = "udp-1", deviceName = "发现电视", httpPort = server.boundPort)
        }
        udp.start()
        udp.start()
        try {
            DatagramSocket().use { client ->
                client.soTimeout = 250
                val noise = "NOPE".toByteArray()
                client.send(DatagramPacket(noise, noise.size, InetAddress.getByName("127.0.0.1"), udp.boundPort))
                val missed = runCatching {
                    val buf = ByteArray(256)
                    client.receive(DatagramPacket(buf, buf.size))
                }
                assertTrue(missed.exceptionOrNull() is SocketTimeoutException)

                client.soTimeout = 2_000
                val probe = "  ${DiscoveryProtocol.REQUEST_MAGIC}\n".toByteArray()
                client.send(DatagramPacket(probe, probe.size, InetAddress.getByName("127.0.0.1"), udp.boundPort))
                val buf = ByteArray(4096)
                val reply = DatagramPacket(buf, buf.size)
                client.receive(reply)
                val info = DiscoveryProtocol.decodeDiscoveryResponse(reply.data, reply.length)
                assertEquals("发现电视", info?.deviceName)
                assertEquals(server.boundPort, info?.httpPort)
            }
        } finally {
            udp.close()
            udp.close()
        }
    }

    @Test
    fun servesPreviewPagesAndAllowsBrowserPreflight() {
        val dir = kotlin.io.path.createTempDirectory("avestv-web").toFile()
        val local = StreamingHttpServer(port = 0, store = store, staticRoot = dir)
        try {
            File(dir, "phone.html").writeText("<!doctype html><title>phone-page</title>")
            local.start()
            val page = request(local.boundPort, "GET", "/phone.html")
            assertEquals(200, statusOf(page))
            assertTrue(page.contains("phone-page"))
            assertTrue(page.contains("Access-Control-Allow-Origin: *"))
            val info = request(local.boundPort, "GET", "/api/v1/info")
            assertEquals(200, statusOf(info))
            assertTrue(info.contains("Access-Control-Allow-Origin: *"))
            val preflight = request(local.boundPort, "OPTIONS", "/api/v1/albums/家庭/upload")
            assertEquals(204, statusOf(preflight))
            assertTrue(preflight.contains("Access-Control-Allow-Private-Network: true"))
            assertEquals(404, statusOf(request(local.boundPort, "GET", "/../phone.html")))
            assertEquals(404, statusOf(request(local.boundPort, "GET", "/missing.html")))
        } finally {
            local.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun closedServerStopsAccepting() {
        val local = StreamingHttpServer(port = 0, store = store)
        local.start()
        val port = local.boundPort
        local.close()
        val failed = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 400)
            }
        }
        assertTrue(failed.isFailure)
    }

    private fun request(port: Int, method: String, path: String): String {
        val header = "$method $path HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n"
        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write(header.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            readEof(socket.getInputStream())
        }
    }

    private fun http(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "image/jpeg",
    ): String {
        val header = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: t\r\n")
            if (body != null) {
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        return sendRaw(header + (body ?: ByteArray(0)))
    }

    private fun sendRaw(bytes: ByteArray): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", server.boundPort), 2_000)
        socket.soTimeout = 3_000
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
        readEof(socket.getInputStream())
    }

    private fun statusOf(response: String): Int =
        response.substringAfter(' ').substringBefore(' ').toInt()

    private fun bodyOf(response: String): String = response.substringAfter("\r\n\r\n")

    private fun readUntil(input: InputStream, marker: String): String {
        val out = ByteArrayOutputStream()
        while (!out.toString(StandardCharsets.ISO_8859_1.name()).contains(marker)) {
            val b = input.read()
            if (b == -1) break
            out.write(b)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun readEof(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }
}
