package com.avestv.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class UsbHttpTunnelTest {

    @Test
    fun aoaProtocolHelpersMatchExpectedIdsAndStrings() {
        assertTrue(UsbAoaProtocol.isAccessoryDevice(0x18D1, 0x2D00))
        assertTrue(UsbAoaProtocol.isAccessoryDevice(0x18D1, 0x2D01))
        assertTrue(UsbAoaProtocol.isAccessoryDevice(0x18D1, 0x2D04))
        assertTrue(UsbAoaProtocol.isAccessoryDevice(0x18D1, 0x2D05))
        assertFalse(UsbAoaProtocol.isAccessoryDevice(0x18D1, 0x4EE7))
        assertFalse(UsbAoaProtocol.isAccessoryDevice(0x04E8, 0x2D00))

        val encoded = UsbAoaProtocol.nullTerminatedUtf8("AvesTV")
        assertEquals(7, encoded.size)
        assertEquals(0.toByte(), encoded.last())
        assertEquals("AvesTV", String(encoded, 0, encoded.size - 1, Charsets.UTF_8))
    }

    @Test
    fun frameCodecRoundTripsAllTypesAndRejectsCorruptFrames() {
        val maxPayload = ByteArray(UsbFrameCodec.MAX_PAYLOAD_BYTES) { (it * 13 % 256).toByte() }
        val frames = listOf(
            UsbFrame(UsbFrameCodec.TYPE_OPEN, 1),
            UsbFrame(UsbFrameCodec.TYPE_DATA, 1, "hello-usb".toByteArray()),
            UsbFrame(UsbFrameCodec.TYPE_DATA, 2, maxPayload),
            UsbFrame(UsbFrameCodec.TYPE_FIN, 1),
            UsbFrame(UsbFrameCodec.TYPE_RST, 2),
            UsbFrame(UsbFrameCodec.TYPE_PING, 0),
            UsbFrame(UsbFrameCodec.TYPE_PONG, 0),
        )

        val baos = ByteArrayOutputStream()
        frames.forEach { UsbFrameCodec.writeFrame(baos, it) }

        val bais = ByteArrayInputStream(baos.toByteArray())
        val decoded = mutableListOf<UsbFrame>()
        while (true) {
            val next = UsbFrameCodec.readFrame(bais) ?: break
            decoded.add(next)
        }
        assertEquals(frames, decoded)
        assertNull(UsbFrameCodec.readFrame(bais))

        val badMagic = byteArrayOf(0x00, 0x00, 0x01, 0x01, 0, 0, 0, 1, 0, 0, 0, 0)
        var threw = false
        try {
            UsbFrameCodec.readFrame(ByteArrayInputStream(badMagic))
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun multiplexedHttpRequestsAndLargeUploadsWorkOverUsbTunnel() {
        val store = PreviewAlbumStore()
        val server = StreamingHttpServer(port = 0, store = store)
        server.start()

        val c2hOut = PipedOutputStream()
        val c2hIn = PipedInputStream(c2hOut, 128 * 1024)
        val h2cOut = PipedOutputStream()
        val h2cIn = PipedInputStream(h2cOut, 128 * 1024)

        val hostBridge = UsbTunnelHostBridge(
            usbIn = c2hIn,
            usbOut = h2cOut,
            targetPort = server.boundPort,
        )
        val clientBridge = UsbTunnelClientBridge(
            usbIn = h2cIn,
            usbOut = c2hOut,
        )
        hostBridge.start()
        clientBridge.start()

        val base = "http://127.0.0.1:${clientBridge.localPort}"
        try {
            // 1. Probe /api/v1/info
            val infoConn = (URL("$base/api/v1/info").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            assertEquals(200, infoConn.responseCode)
            val infoJson = infoConn.inputStream.bufferedReader().use { it.readText() }
            val info = DiscoveryProtocol.json.decodeFromString<TvDeviceInfo>(infoJson)
            assertEquals("preview-tv", info.deviceId)

            // 2. Upload > 200KB photo across multiple USB frames
            val payload = ByteArray(220 * 1024) { (it * 31 % 256).toByte() }
            val uploadConn = (URL("$base/api/v1/albums/USB%E7%9B%B8%E5%86%8C/upload?name=usb_photo.jpg&mime=image%2Fjpeg&mode=append")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "image/jpeg")
                connectTimeout = 5_000
                readTimeout = 10_000
            }
            uploadConn.outputStream.use { it.write(payload) }
            assertEquals(200, uploadConn.responseCode)
            val uploadJson = uploadConn.inputStream.bufferedReader().use { it.readText() }
            val uploadResult = DiscoveryProtocol.json.decodeFromString<UploadResult>(uploadJson)
            assertEquals("USB相册", uploadResult.photo.album)
            assertEquals("usb_photo.jpg", uploadResult.photo.name)

            // 3. Concurrent GET requests (albums + thumbnail) over multiplexed tunnel
            val pool = Executors.newFixedThreadPool(2)
            try {
                val albumsFuture = pool.submit(Callable {
                    val conn = URL("$base/api/v1/albums").openConnection() as HttpURLConnection
                    assertEquals(200, conn.responseCode)
                    conn.inputStream.bufferedReader().use { it.readText() }
                })
                val thumbFuture = pool.submit(Callable {
                    val conn = URL("$base/api/v1/photos/${uploadResult.photo.id}/thumb").openConnection() as HttpURLConnection
                    assertEquals(200, conn.responseCode)
                    conn.inputStream.use { it.readBytes() }
                })

                val albums = DiscoveryProtocol.json.decodeFromString<List<AlbumInfo>>(albumsFuture.get())
                assertEquals(1, albums.size)
                assertEquals(1, albums[0].photoCount)

                val thumbBytes = thumbFuture.get()
                assertNotNull(thumbBytes)
                assertArrayEquals(payload, thumbBytes)
            } finally {
                pool.shutdownNow()
            }

            // 4. Delete photo over tunnel
            val delConn = (URL("$base/api/v1/photos/${uploadResult.photo.id}").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
            }
            assertEquals(200, delConn.responseCode)
            assertTrue(store.listPhotos("USB相册").isEmpty())
        } finally {
            clientBridge.close()
            hostBridge.close()
            server.close()
        }
    }
}
