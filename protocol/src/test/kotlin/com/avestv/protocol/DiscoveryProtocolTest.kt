package com.avestv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryProtocolTest {

    @Test
    fun discoveryRequestAndResponseRoundTrip() {
        val req = DiscoveryProtocol.encodeDiscoveryRequest()
        assertTrue(DiscoveryProtocol.isDiscoveryRequest(req))
        assertFalse(DiscoveryProtocol.isDiscoveryRequest("RANDOM_PACKET".toByteArray()))

        val original = TvDeviceInfo(
            deviceId = "tv-001",
            deviceName = "客厅电视",
            httpPort = 53319,
            freeBytes = 8_500_000_000L,
            albumCount = 3,
            totalPhotos = 42,
        )
        val encoded = DiscoveryProtocol.encodeDiscoveryResponse(original)
        val decoded = DiscoveryProtocol.decodeDiscoveryResponse(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decodeRejectsMalformedOrForeignResponse() {
        assertNull(DiscoveryProtocol.decodeDiscoveryResponse("not json".toByteArray()))
        assertNull(DiscoveryProtocol.decodeDiscoveryResponse("""{"magic":"OTHER","deviceId":"1"}""".toByteArray()))
        assertNull(DiscoveryProtocol.decodeDiscoveryResponse("""{"magic":"AVESTV_HERE_V1","deviceId":""}""".toByteArray()))
    }

    @Test
    fun sanitizeAlbumNameRemovesUnsafeCharacters() {
        assertEquals("妈妈的夏天", DiscoveryProtocol.sanitizeAlbumName("  妈妈的夏天  "))
        assertEquals("杭州 西湖", DiscoveryProtocol.sanitizeAlbumName("../杭州:西湖*?"))
        assertEquals(DiscoveryProtocol.DEFAULT_ALBUM_NAME, DiscoveryProtocol.sanitizeAlbumName("   ...///   "))
        assertEquals(DiscoveryProtocol.DEFAULT_ALBUM_NAME, DiscoveryProtocol.sanitizeAlbumName(null))
        assertTrue(DiscoveryProtocol.sanitizeAlbumName("a".repeat(200)).length <= DiscoveryProtocol.MAX_NAME_LENGTH)
    }

    @Test
    fun sanitizeFileNameNormalizesPathsAndExtensions() {
        assertEquals("IMG_0001.jpg", DiscoveryProtocol.sanitizeFileName("/DCIM/Camera/IMG_0001.JPG"))
        assertEquals("风景_1.jpg", DiscoveryProtocol.sanitizeFileName("风景:1", fallbackExt = "jpg"))
        assertEquals("photo.png", DiscoveryProtocol.sanitizeFileName("..", fallbackExt = "png"))
    }

    @Test
    fun subnetTargetsExcludeSelfAndOnlyAllowPrivateLan() {
        val targets = DiscoveryProtocol.buildSubnetTargets("192.168.1.42")
        assertEquals(253, targets.size)
        assertFalse(targets.contains("192.168.1.42"))
        assertTrue(targets.contains("192.168.1.1"))
        assertTrue(targets.contains("192.168.1.254"))

        assertTrue(DiscoveryProtocol.buildSubnetTargets("127.0.0.1").isEmpty())
        assertTrue(DiscoveryProtocol.buildSubnetTargets("8.8.8.8").isEmpty())
        assertTrue(DiscoveryProtocol.buildSubnetTargets("not-an-ip").isEmpty())
    }

    @Test
    fun directedBroadcastCalculation() {
        assertEquals("192.168.1.255", DiscoveryProtocol.computeDirectedBroadcast("192.168.1.42", 24))
        assertEquals("10.0.255.255", DiscoveryProtocol.computeDirectedBroadcast("10.0.5.9", 16))
        assertEquals("172.16.15.255", DiscoveryProtocol.computeDirectedBroadcast("172.16.0.10", 20))
        assertEquals("10.255.255.255", DiscoveryProtocol.computeDirectedBroadcast("10.1.2.3", 8))
        assertEquals("192.168.1.43", DiscoveryProtocol.computeDirectedBroadcast("192.168.1.42", 30))
        assertNull(DiscoveryProtocol.computeDirectedBroadcast("192.168.1.1", 32))
        assertNull(DiscoveryProtocol.computeDirectedBroadcast("192.168.1.1", 31))
        assertNull(DiscoveryProtocol.computeDirectedBroadcast("192.168.1.1", 7))
        assertNull(DiscoveryProtocol.computeDirectedBroadcast("192.168.1.1", 0))
        assertNull(DiscoveryProtocol.computeDirectedBroadcast("not-an-ip", 24))
        assertNull(DiscoveryProtocol.computeDirectedBroadcast("192.168.1.256", 24))
    }

    @Test
    fun discoveryPacketEdgesAndJsonTolerance() {
        val magic = DiscoveryProtocol.encodeDiscoveryRequest()
        assertTrue(DiscoveryProtocol.isDiscoveryRequest("$magicText   ".toByteArray()))
        assertTrue(DiscoveryProtocol.isDiscoveryRequest("$magicText\n".toByteArray()))
        assertFalse(DiscoveryProtocol.isDiscoveryRequest(ByteArray(0)))
        assertFalse(DiscoveryProtocol.isDiscoveryRequest("AVESTV".toByteArray()))
        assertFalse(DiscoveryProtocol.isDiscoveryRequest("avestv_discover_v1".toByteArray()))
        assertFalse(DiscoveryProtocol.isDiscoveryRequest(ByteArray(200) { 'A'.code.toByte() }, length = 200))
        assertTrue(DiscoveryProtocol.isDiscoveryRequest(magic + "ignored".toByteArray(), length = magic.size))

        val fancy = TvDeviceInfo(
            deviceId = "tv-quote",
            deviceName = "客厅\"电视\"\nA",
            httpPort = 65535,
            freeBytes = Long.MAX_VALUE,
            albumCount = 0,
            totalPhotos = 0,
        )
        assertEquals(fancy, DiscoveryProtocol.decodeDiscoveryResponse(DiscoveryProtocol.encodeDiscoveryResponse(fancy)))

        val withUnknown = """{"magic":"AVESTV_HERE_V1","deviceId":"abc","deviceName":"n","httpPort":80,"future":true}"""
        val parsed = DiscoveryProtocol.decodeDiscoveryResponse(withUnknown.toByteArray())
        assertEquals("abc", parsed?.deviceId)
        assertEquals(80, parsed?.httpPort)

        assertNull(DiscoveryProtocol.decodeDiscoveryResponse(ByteArray(0)))
        assertNull(DiscoveryProtocol.decodeDiscoveryResponse("""{"magic":"AVESTV_HERE_V1","deviceId":"abc","httpPort":0}""".toByteArray()))
        assertNull(DiscoveryProtocol.decodeDiscoveryResponse("""{"magic":"AVESTV_HERE_V1","deviceId":"abc","httpPort":65536}""".toByteArray()))
        assertNull(DiscoveryProtocol.decodeDiscoveryResponse("""{"magic":"AVESTV_HERE_V1","deviceId":"abc","httpPort":"80"}""".toByteArray()))
        assertNull(DiscoveryProtocol.decodeDiscoveryResponse(ByteArray(5000) { '{'.code.toByte() }, length = 5000))
    }

    @Test
    fun privateLanAndSubnetEdges() {
        assertTrue(DiscoveryProtocol.isPrivateLanIpv4("10.0.0.1"))
        assertTrue(DiscoveryProtocol.isPrivateLanIpv4("10.255.255.254"))
        assertTrue(DiscoveryProtocol.isPrivateLanIpv4("172.16.0.1"))
        assertTrue(DiscoveryProtocol.isPrivateLanIpv4("172.31.255.255"))
        assertTrue(DiscoveryProtocol.isPrivateLanIpv4("192.168.0.1"))
        assertTrue(DiscoveryProtocol.isPrivateLanIpv4(" 192.168.1.01 "))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("172.15.255.255"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("172.32.0.1"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("192.167.1.1"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("127.0.0.1"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("169.254.1.1"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("100.64.0.1"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("8.8.8.8"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4(""))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("192.168.1"))
        assertFalse(DiscoveryProtocol.isPrivateLanIpv4("192.168.1.1.1"))
        assertFalse(DiscoveryProtocol.isValidIpv4("192.168.1.256"))
        assertNotNull(DiscoveryProtocol.toInet4Address("192.168.1.255"))
        assertNull(DiscoveryProtocol.toInet4Address("192.168.1.256"))
        assertNull(DiscoveryProtocol.toInet4Address("::1"))

        val gateway = DiscoveryProtocol.buildSubnetTargets("192.168.1.1")
        assertEquals(253, gateway.size)
        assertEquals("192.168.1.2", gateway.first())
        assertEquals("192.168.1.254", gateway.last())
        assertFalse(gateway.contains("192.168.1.0"))
        assertFalse(gateway.contains("192.168.1.1"))
        assertFalse(gateway.contains("192.168.1.255"))

        val networkAddress = DiscoveryProtocol.buildSubnetTargets("10.0.0.0")
        assertEquals(254, networkAddress.size)
        assertTrue(networkAddress.contains("10.0.0.1"))
        assertTrue(networkAddress.contains("10.0.0.254"))
        assertTrue(DiscoveryProtocol.buildSubnetTargets("172.15.1.1").isEmpty())
        assertTrue(DiscoveryProtocol.buildSubnetTargets("169.254.1.1").isEmpty())
        assertTrue(DiscoveryProtocol.buildSubnetTargets("100.64.1.1").isEmpty())
    }

    @Test
    fun sanitizeRejectsTraversalControlCharsAndLongNames() {
        assertEquals("Windows", DiscoveryProtocol.sanitizeAlbumName("..\\..\\Windows"))
        assertEquals("a b", DiscoveryProtocol.sanitizeAlbumName("a\u0000b\n\t"))
        assertEquals("ab", DiscoveryProtocol.sanitizeAlbumName("a..b"))
        assertEquals("妈妈", DiscoveryProtocol.sanitizeAlbumName("妈妈."))
        val maxed = DiscoveryProtocol.sanitizeAlbumName("测".repeat(200))
        assertTrue(maxed.length <= DiscoveryProtocol.MAX_NAME_LENGTH)
        assertTrue(maxed.isNotEmpty())

        assertEquals("passwd.jpg", DiscoveryProtocol.sanitizeFileName("../../etc/passwd"))
        assertEquals("bar.png", DiscoveryProtocol.sanitizeFileName("foo/bar.png"))
        assertEquals("a.jpg", DiscoveryProtocol.sanitizeFileName("C:\\photos\\a.jpg"))
        assertEquals("photo.jpg", DiscoveryProtocol.sanitizeFileName(null))
        assertEquals("photo.heic", DiscoveryProtocol.sanitizeFileName("   ", fallbackExt = ".heic"))
        assertEquals("file.jpg", DiscoveryProtocol.sanitizeFileName("file."))
        assertEquals("jpg.jpg", DiscoveryProtocol.sanitizeFileName(".jpg"))
        assertEquals("photo.tar.gz", DiscoveryProtocol.sanitizeFileName("photo.tar.gz"))
        assertEquals("CON.jpg", DiscoveryProtocol.sanitizeFileName("CON.jpg"))
        assertEquals("file.jp", DiscoveryProtocol.sanitizeFileName("file.j" + '$' + "p"))
        val longName = DiscoveryProtocol.sanitizeFileName("a".repeat(200) + ".png")
        assertTrue(longName.endsWith(".png"))
        assertTrue(longName.length <= DiscoveryProtocol.MAX_NAME_LENGTH)
        assertEquals("海边 01.jpg", DiscoveryProtocol.sanitizeFileName("海边 01.jpg"))
    }

    private val magicText: String
        get() = DiscoveryProtocol.REQUEST_MAGIC
}
