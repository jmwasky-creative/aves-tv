package com.avestv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class PreviewAlbumStoreTest {

    @Test
    fun defaultPreviewStoreReportsRealDiskFreeSpaceNotFake8GiB() {
        val store = PreviewAlbumStore()
        val info = store.deviceInfo(53319)
        val fakeEightGiB = 8L * 1024L * 1024L * 1024L
        val cwd = File(System.getProperty("user.dir", ".")).absoluteFile
        val expectedUsable = runCatching { Files.getFileStore(cwd.toPath()).usableSpace }
            .getOrElse { cwd.usableSpace }

        assertTrue("Expected positive freeBytes, got ${info.freeBytes}", info.freeBytes > 0L)
        assertNotEquals(fakeEightGiB, info.freeBytes)
        val toleranceBytes = 1024L * 1024L * 1024L
        assertTrue(
            "Expected freeBytes (${info.freeBytes}) near filesystem usableSpace ($expectedUsable)",
            abs(info.freeBytes - expectedUsable) <= toleranceBytes,
        )
    }

    @Test
    fun injectedFreeBytesProviderIsUsedDynamicallyAndClamped() {
        val freeSpace = AtomicLong(79_507_906_560L)
        val store = PreviewAlbumStore(freeBytesProvider = { freeSpace.get() })

        assertEquals(79_507_906_560L, store.deviceInfo(53319).freeBytes)

        freeSpace.set(12_345_678L)
        assertEquals(12_345_678L, store.deviceInfo(53319).freeBytes)

        freeSpace.set(-42L)
        assertEquals(0L, store.deviceInfo(53319).freeBytes)
    }

    @Test
    fun usableSpaceBytesResolvesMissingChildPathsToExistingParent() {
        val tempDir = Files.createTempDirectory("preview-space-test").toFile()
        try {
            val nestedMissing = File(tempDir, "does/not/exist/yet")
            val usable = PreviewAlbumStore.usableSpaceBytes(nestedMissing)
            assertTrue(usable > 0L)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
