package com.avestv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class DebugLogStoreTest {

    @Test
    fun ringBufferPersistenceRotationSinceIdAndClear() {
        val dir = Files.createTempDirectory("avestv_log_test").toFile()
        try {
            val logFile = File(dir, "tv.log")
            val store = DebugLogStore(logFile = logFile, maxEntries = 3, maxFileBytes = 250L)

            val e1 = store.info("HTTP", "first entry")
            val e2 = store.warn("USB", "second entry with enough payload to rotate")
            val e3 = store.error("HTTP", "third entry with enough payload to rotate", IllegalStateException("boom"))
            val e4 = store.info("TV", "fourth entry after eviction")

            assertEquals(listOf(e2.id, e3.id, e4.id), store.entries(10).map { it.id })
            assertEquals(listOf(e3.id, e4.id), store.entries(10, sinceId = e2.id).map { it.id })
            assertTrue(e3.message.contains("IllegalStateException: boom"))
            assertTrue(logFile.exists())
            assertTrue(File(dir, "tv.log.1").exists())

            // Reload from disk into a fresh store instance
            val reloaded = DebugLogStore(logFile = logFile, maxEntries = 3, maxFileBytes = 250L)
            assertFalse(reloaded.entries(10).isEmpty())
            val next = reloaded.info("TV", "after reload")
            assertTrue(next.id > e4.id)
            assertFalse(e1.id == next.id)

            val cleared = reloaded.clear()
            assertTrue(cleared > 0)
            assertTrue(reloaded.entries(10).isEmpty())
            assertFalse(logFile.exists())
            assertFalse(File(dir, "tv.log.1").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun httpServerRecordsRequestsAndExposesLogsEndpoint() {
        val logStore = DebugLogStore(maxEntries = 100)
        val server = StreamingHttpServer(
            port = 0,
            store = PreviewAlbumStore(),
            logStore = logStore,
        )
        server.start()
        try {
            val base = "http://127.0.0.1:${server.boundPort}"

            // Normal API request -> should be logged
            val infoConn = URL("$base/api/v1/info").openConnection() as HttpURLConnection
            assertEquals(200, infoConn.responseCode)
            infoConn.inputStream.close()

            // Background poll (?poll=1) -> should NOT be logged
            val pollConn = URL("$base/api/v1/info?poll=1").openConnection() as HttpURLConnection
            assertEquals(200, pollConn.responseCode)
            pollConn.inputStream.close()

            // 404 request -> should be logged as WARN
            val missingConn = URL("$base/api/v1/does-not-exist").openConnection() as HttpURLConnection
            assertEquals(404, missingConn.responseCode)
            missingConn.errorStream?.close()

            // Fetch logs via GET /api/v1/logs (itself should NOT be logged)
            var logs = emptyList<LogEntry>()
            val deadline = System.currentTimeMillis() + 1_000L
            while (System.currentTimeMillis() < deadline) {
                val logsConn = URL("$base/api/v1/logs?limit=50").openConnection() as HttpURLConnection
                assertEquals(200, logsConn.responseCode)
                val logsJson = logsConn.inputStream.bufferedReader().use { it.readText() }
                logs = DiscoveryProtocol.json.decodeFromString<List<LogEntry>>(logsJson)
                if (logs.size >= 2) break
                Thread.sleep(20)
            }

            assertEquals(2, logs.size)
            assertTrue(logs[0].level == "INFO" && logs[0].message.contains("GET /api/v1/info -> 200"))
            assertTrue(logs[1].level == "WARN" && logs[1].message.contains("GET /api/v1/does-not-exist -> 404"))

            // Clear logs via DELETE /api/v1/logs
            val delConn = (URL("$base/api/v1/logs").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
            }
            assertEquals(200, delConn.responseCode)
            val delJson = delConn.inputStream.bufferedReader().use { it.readText() }
            val delRes = DiscoveryProtocol.json.decodeFromString<DeleteResult>(delJson)
            assertEquals(2, delRes.deletedCount)
            assertTrue(logStore.entries().isEmpty())
        } finally {
            server.close()
        }
    }
}
