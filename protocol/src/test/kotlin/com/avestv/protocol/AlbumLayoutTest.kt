package com.avestv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumLayoutTest {

    @Test
    fun relativePathAndAlbumExtraction() {
        assertEquals("Pictures/TVAlbums/妈妈的夏天/", AlbumLayout.albumRelativePath("妈妈的夏天"))
        assertEquals("妈妈的夏天", AlbumLayout.extractAlbumName("Pictures/TVAlbums/妈妈的夏天/"))
        assertEquals("妈妈的夏天", AlbumLayout.extractAlbumName("Pictures\\TVAlbums\\妈妈的夏天\\nested\\"))
        assertEquals("妈妈的夏天", AlbumLayout.extractAlbumName("Pictures/TVAlbums/妈妈的夏天"))
        assertNull(AlbumLayout.extractAlbumName("Pictures/TVAlbums/"))
        assertNull(AlbumLayout.extractAlbumName("Pictures/TVAlbums"))
        assertNull(AlbumLayout.extractAlbumName("Download/TVAlbums/其他/"))
        assertNull(AlbumLayout.extractAlbumName("Pictures/Camera/"))
    }

    @Test
    fun legacyPhotoIdRoundTripsAndCannotEscapeAlbumRoot() {
        val id = AlbumLayout.encodeLegacyPhotoId("家庭相册", "海边 01.jpg")
        assertEquals("家庭相册" to "海边 01.jpg", AlbumLayout.decodeLegacyPhotoId(id))

        val traversal = java.util.Base64.getUrlEncoder().encodeToString(
            "../../secret/evil.jpg".toByteArray(Charsets.UTF_8)
        )
        val decoded = AlbumLayout.decodeLegacyPhotoId(traversal)
        assertEquals(DiscoveryProtocol.DEFAULT_ALBUM_NAME, decoded?.first)
        assertEquals("evil.jpg", decoded?.second)
        assertTrue(decoded!!.second.contains('/').not())

        assertNull(AlbumLayout.decodeLegacyPhotoId(""))
        assertNull(AlbumLayout.decodeLegacyPhotoId("@@@"))
        assertNull(AlbumLayout.decodeLegacyPhotoId(java.util.Base64.getUrlEncoder().encodeToString("noslash".toByteArray())))
        assertNull(AlbumLayout.decodeLegacyPhotoId(java.util.Base64.getUrlEncoder().encodeToString("/onlyname".toByteArray())))
        assertNull(AlbumLayout.decodeLegacyPhotoId(java.util.Base64.getUrlEncoder().encodeToString("album/".toByteArray())))
    }

    @Test
    fun mimeAndThumbnailSampleSize() {
        assertEquals("image/png", AlbumLayout.guessImageMime("A.PNG"))
        assertEquals("image/webp", AlbumLayout.guessImageMime("a.webp"))
        assertEquals("image/gif", AlbumLayout.guessImageMime("a.gif"))
        assertEquals("image/heic", AlbumLayout.guessImageMime("a.HEIC"))
        assertEquals("image/heif", AlbumLayout.guessImageMime("a.heif"))
        assertEquals("image/jpeg", AlbumLayout.guessImageMime("a.jpg"))
        assertEquals("image/jpeg", AlbumLayout.guessImageMime("noext"))
        assertEquals("image/jpeg", AlbumLayout.guessImageMime("tar.gz.jpg"))

        assertEquals(1, AlbumLayout.thumbnailSampleSize(0, 100, 320))
        assertEquals(1, AlbumLayout.thumbnailSampleSize(100, 100, 320))
        assertEquals(1, AlbumLayout.thumbnailSampleSize(100, 100, 0))
        assertEquals(2, AlbumLayout.thumbnailSampleSize(640, 640, 320))
        assertEquals(8, AlbumLayout.thumbnailSampleSize(4000, 3000, 320))
        assertEquals(1, AlbumLayout.thumbnailSampleSize(8000, 100, 320))
        val huge = AlbumLayout.thumbnailSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 320)
        assertTrue(huge > 1)
        assertTrue(huge <= 1 shl 16)
        assertEquals(0, huge and (huge - 1))
    }

    @Test
    fun albumIndexPicksNewestCoverIndependentOfOrder() {
        val ascending = AlbumIndex().apply {
            add("旧相册", "old-1", 10)
            add("新相册", "new-early", 50)
            add("新相册", "new-latest", 80)
            add("新相册", "new-tie", 80)
            add("旧相册", "old-2", 20)
        }.toAlbumInfos()

        assertEquals(listOf("新相册", "旧相册"), ascending.map { it.name })
        assertEquals(3, ascending[0].photoCount)
        assertEquals(80L, ascending[0].latestModifiedSec)
        assertEquals("new-latest", ascending[0].coverPhotoId)
        assertEquals(2, ascending[1].photoCount)
        assertEquals("old-2", ascending[1].coverPhotoId)

        val descending = AlbumIndex().apply {
            add("新相册", "new-latest", 80)
            add("新相册", "new-tie", 80)
            add("新相册", "new-early", 50)
            add("旧相册", "old-2", 20)
            add("旧相册", "old-1", 10)
        }.toAlbumInfos()
        assertEquals("new-latest", descending[0].coverPhotoId)
        assertEquals("old-2", descending[1].coverPhotoId)
        assertTrue(AlbumIndex().toAlbumInfos().isEmpty())
    }
}
