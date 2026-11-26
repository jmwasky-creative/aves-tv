package com.avestv.phone

import com.avestv.protocol.DiscoveryProtocol
import com.avestv.protocol.UploadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale

class PhonePureLogicTest {

    @Test
    fun exifDateMimeAndSampleSize() {
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val expected = fmt.parse("2020:01:02 03:04:05")!!.time
        assertEquals(expected, ImagePrep.parseExifDate("2020:01:02 03:04:05"))
        assertEquals(expected, ImagePrep.parseExifDate("  2020:01:02 03:04:05  "))
        assertNull(ImagePrep.parseExifDate(null))
        assertNull(ImagePrep.parseExifDate(""))
        assertNull(ImagePrep.parseExifDate("   "))
        assertNull(ImagePrep.parseExifDate("2020-01-02 03:04:05"))
        assertNull(ImagePrep.parseExifDate("not-a-date"))

        assertEquals("image/png", ImagePrep.guessMimeFromName("Trip.PNG"))
        assertEquals("image/webp", ImagePrep.guessMimeFromName("a.webp"))
        assertEquals("image/heic", ImagePrep.guessMimeFromName("IMG.HEIC"))
        assertEquals("image/heif", ImagePrep.guessMimeFromName("IMG.heif"))
        assertEquals("image/gif", ImagePrep.guessMimeFromName("anim.gif"))
        assertEquals("image/jpeg", ImagePrep.guessMimeFromName("a.jpg"))
        assertEquals("image/jpeg", ImagePrep.guessMimeFromName("noext"))

        assertEquals("png", ImagePrep.extFromMime("IMAGE/PNG"))
        assertEquals("webp", ImagePrep.extFromMime("image/webp"))
        assertEquals("heic", ImagePrep.extFromMime("image/heic"))
        assertEquals("heif", ImagePrep.extFromMime("image/heif; charset=binary"))
        assertEquals("gif", ImagePrep.extFromMime("image/gif"))
        assertEquals("jpg", ImagePrep.extFromMime("image/jpeg"))
        assertEquals("jpg", ImagePrep.extFromMime("application/octet-stream"))

        assertEquals(1, ImagePrep.inSampleSizeForLongEdge(0, 100, ImagePrep.MAX_4K_EDGE))
        assertEquals(1, ImagePrep.inSampleSizeForLongEdge(3840, 2160, ImagePrep.MAX_4K_EDGE))
        assertEquals(1, ImagePrep.inSampleSizeForLongEdge(3841, 2160, ImagePrep.MAX_4K_EDGE))
        assertEquals(2, ImagePrep.inSampleSizeForLongEdge(7680, 4320, ImagePrep.MAX_4K_EDGE))
        assertEquals(2, ImagePrep.inSampleSizeForLongEdge(8000, 1000, ImagePrep.MAX_4K_EDGE))
        assertEquals(2, ImagePrep.inSampleSizeForLongEdge(100, 8000, ImagePrep.MAX_4K_EDGE))
        assertEquals(4, ImagePrep.inSampleSizeForLongEdge(20_000, 20_000, ImagePrep.MAX_4K_EDGE))
        assertEquals(1, ImagePrep.inSampleSizeForLongEdge(100, 100, 0))
        val huge = ImagePrep.inSampleSizeForLongEdge(Int.MAX_VALUE, Int.MAX_VALUE, ImagePrep.MAX_4K_EDGE)
        assertTrue(huge >= 1)
        assertTrue(huge <= 1 shl 16)
        assertEquals(0, huge and (huge - 1))
    }

    @Test
    fun preparedPhotoClosesItsTempFile() {
        val file = File.createTempFile("tvpush_", ".jpg")
        file.writeText("jpeg-bytes")
        val prepared = PreparedPhoto(
            fileName = "a.jpg",
            mimeType = "image/jpeg",
            contentLength = file.length(),
            takenTimeMs = 5L,
            tempFile = file,
            streamOpener = { FileInputStream(file) },
        )
        assertEquals("jpeg-bytes", prepared.openStream().use { it.readBytes() }.decodeToString())
        prepared.close()
        assertFalse(file.exists())
        prepared.close()
    }

    @Test
    fun uploadModeClearsOnlyTheFirstPhotoOfAReplaceBatch() {
        assertEquals(UploadMode.APPEND, UploadPlanner.modeFor(replaceWholeAlbum = false, index = 0))
        assertEquals(UploadMode.APPEND, UploadPlanner.modeFor(replaceWholeAlbum = false, index = 3))
        assertEquals(UploadMode.REPLACE, UploadPlanner.modeFor(replaceWholeAlbum = true, index = 0))
        assertEquals(UploadMode.APPEND, UploadPlanner.modeFor(replaceWholeAlbum = true, index = 1))
        assertEquals(UploadMode.APPEND, UploadPlanner.modeFor(replaceWholeAlbum = true, index = 40))
    }

    @Test
    fun lanScanPlanSkipsPublicCacheAndDedupesSubnetCandidates() {
        assertFalse(LanScanPlan.shouldProbeCachedIp(null))
        assertFalse(LanScanPlan.shouldProbeCachedIp(""))
        assertFalse(LanScanPlan.shouldProbeCachedIp("   "))
        assertFalse(LanScanPlan.shouldProbeCachedIp("8.8.8.8"))
        assertFalse(LanScanPlan.shouldProbeCachedIp("127.0.0.1"))
        assertFalse(LanScanPlan.shouldProbeCachedIp("169.254.1.1"))
        assertFalse(LanScanPlan.shouldProbeCachedIp("100.64.0.1"))
        assertTrue(LanScanPlan.shouldProbeCachedIp("10.1.1.1"))
        assertTrue(LanScanPlan.shouldProbeCachedIp("172.16.5.5"))
        assertTrue(LanScanPlan.shouldProbeCachedIp("172.31.0.1"))
        assertTrue(LanScanPlan.shouldProbeCachedIp("192.168.0.2"))

        assertTrue(LanScanPlan.shouldScanWholeSubnet(forceFullSubnetScan = true, discoveredCount = 2))
        assertTrue(LanScanPlan.shouldScanWholeSubnet(forceFullSubnetScan = false, discoveredCount = 0))
        assertFalse(LanScanPlan.shouldScanWholeSubnet(forceFullSubnetScan = false, discoveredCount = 1))

        val candidates = LanScanPlan.subnetCandidates(
            localIpv4s = listOf("192.168.1.10", "192.168.1.10", "10.0.0.8", "8.8.8.8"),
            alreadyFoundIps = setOf("192.168.1.20", "10.0.0.9"),
        )
        assertEquals(253 + 253 - 2, candidates.size)
        assertFalse(candidates.contains("192.168.1.10"))
        assertFalse(candidates.contains("192.168.1.20"))
        assertFalse(candidates.contains("10.0.0.8"))
        assertFalse(candidates.contains("10.0.0.9"))
        assertTrue(candidates.contains("192.168.1.1"))
        assertTrue(candidates.contains("10.0.0.1"))
        assertEquals(candidates.size, candidates.toSet().size)
        assertTrue(candidates.all { DiscoveryProtocol.isPrivateLanIpv4(it) })
    }
}
