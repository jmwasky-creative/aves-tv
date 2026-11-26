package com.avestv.protocol

import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight streaming HTTP/1.1 server with zero external dependencies.
 * Reads request headers directly from a [BufferedInputStream] and passes a bounded/dechunked
 * [InputStream] straight into [AlbumStore.savePhoto] so large photos never buffer in RAM.
 */
class StreamingHttpServer(
    private val port: Int,
    private val store: AlbumStore,
    private val onMutation: (() -> Unit)? = null,
    private val staticRoot: File? = null,
) : Closeable {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ExecutorService? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
        serverSocket = ss
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "avestv-http-worker").apply { isDaemon = true }
        }
        executor = pool
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val socket = ss.accept()
                    pool.execute { handleClient(socket) }
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    if (!running.get()) break
                }
            }
        }, "avestv-http-accept").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream(), 65_536)
            val output = s.getOutputStream()
            try {
                val requestLine = readAsciiLine(input) ?: return
                if (requestLine.isEmpty()) return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeJsonError(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val rawTarget = parts[1]

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                    }
                }

                if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }

                val qIndex = rawTarget.indexOf('?')
                val rawPath = if (qIndex >= 0) rawTarget.substring(0, qIndex) else rawTarget
                val query = if (qIndex >= 0) parseQuery(rawTarget.substring(qIndex + 1)) else emptyMap()
                val segments = rawPath.split('/').filter { it.isNotEmpty() }.map { decodePathSegment(it) }

                route(method, segments, query, headers, input, output)
            } catch (e: IllegalArgumentException) {
                runCatching { writeJsonError(output, 400, e.message ?: "Bad Request") }
            } catch (e: Exception) {
                runCatching { writeJsonError(output, 500, e.message ?: "Internal Server Error") }
            }
        }
    }

    private fun route(
        method: String,
        segments: List<String>,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: BufferedInputStream,
        output: OutputStream,
    ) {
        if (method == "OPTIONS") {
            writeBinary(output, 204, "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        if (method == "GET" && serveStatic(segments, output)) return

        // /api/v1/...
        if (segments.size < 3 || segments[0] != "api" || segments[1] != "v1") {
            writeJsonError(output, 404, "Not Found")
            return
        }

        when {
            // GET /api/v1/info
            segments.size == 3 && segments[2] == "info" -> {
                if (method != "GET") {
                    writeJsonError(output, 405, "Method Not Allowed")
                    return
                }
                val info = store.deviceInfo(boundPort)
                writeJson(output, 200, DiscoveryProtocol.json.encodeToString(info))
            }

            // GET /api/v1/albums
            segments.size == 3 && segments[2] == "albums" -> {
                if (method != "GET") {
                    writeJsonError(output, 405, "Method Not Allowed")
                    return
                }
                val albums = store.listAlbums()
                writeJson(output, 200, DiscoveryProtocol.json.encodeToString(albums))
            }

            // DELETE /api/v1/albums/{album}
            segments.size == 4 && segments[2] == "albums" -> {
                if (method != "DELETE") {
                    writeJsonError(output, 405, "Method Not Allowed")
                    return
                }
                val album = DiscoveryProtocol.sanitizeAlbumName(segments[3])
                val deleted = store.deleteAlbum(album)
                onMutation?.invoke()
                writeJson(output, 200, DiscoveryProtocol.json.encodeToString(DeleteResult(deleted)))
            }

            // GET /api/v1/albums/{album}/photos
            segments.size == 5 && segments[2] == "albums" && segments[4] == "photos" -> {
                if (method != "GET") {
                    writeJsonError(output, 405, "Method Not Allowed")
                    return
                }
                val album = DiscoveryProtocol.sanitizeAlbumName(segments[3])
                val photos = store.listPhotos(album)
                writeJson(output, 200, DiscoveryProtocol.json.encodeToString(photos))
            }

            // POST /api/v1/albums/{album}/upload
            segments.size == 5 && segments[2] == "albums" && segments[4] == "upload" -> {
                if (method != "POST") {
                    writeJsonError(output, 405, "Method Not Allowed")
                    return
                }
                val album = DiscoveryProtocol.sanitizeAlbumName(segments[3])
                val rawName = query["name"] ?: headers["x-file-name"]?.let { decodePathSegment(it) }
                val mimeType = (query["mime"] ?: headers["content-type"] ?: "image/jpeg")
                    .substringBefore(';')
                    .trim()
                    .ifEmpty { "image/jpeg" }
                val fallbackExt = when {
                    mimeType.contains("png", ignoreCase = true) -> "png"
                    mimeType.contains("webp", ignoreCase = true) -> "webp"
                    mimeType.contains("heic", ignoreCase = true) -> "heic"
                    mimeType.contains("heif", ignoreCase = true) -> "heif"
                    mimeType.contains("gif", ignoreCase = true) -> "gif"
                    else -> "jpg"
                }
                val fileName = DiscoveryProtocol.sanitizeFileName(rawName, fallbackExt)
                val mode = if (query["mode"]?.equals("replace", ignoreCase = true) == true) {
                    UploadMode.REPLACE
                } else {
                    UploadMode.APPEND
                }
                val takenMs = query["takenMs"]?.toLongOrNull()

                val bodyStream = createBodyStream(headers, input)
                if (bodyStream == null) {
                    writeJsonError(output, 400, "Missing Content-Length or Transfer-Encoding: chunked")
                    return
                }
                val result = store.savePhoto(
                    album = album,
                    fileName = fileName,
                    mimeType = mimeType,
                    takenTimeMs = takenMs,
                    mode = mode,
                    inputStream = bodyStream,
                )
                // Drain any unread bytes cleanly if store returned early
                drainQuietly(bodyStream)
                onMutation?.invoke()
                writeJson(output, 200, DiscoveryProtocol.json.encodeToString(result))
            }

            // GET /api/v1/photos/{id}/thumb
            segments.size == 5 && segments[2] == "photos" && segments[4] == "thumb" -> {
                if (method != "GET") {
                    writeJsonError(output, 405, "Method Not Allowed")
                    return
                }
                val thumb = store.readThumbnail(segments[3])
                if (thumb == null) {
                    writeJsonError(output, 404, "Thumbnail Not Found")
                    return
                }
                writeBinary(output, 200, "image/jpeg", thumb)
            }

            // DELETE /api/v1/photos/{id}
            segments.size == 4 && segments[2] == "photos" -> {
                if (method != "DELETE") {
                    writeJsonError(output, 405, "Method Not Allowed")
                    return
                }
                val deleted = store.deletePhoto(segments[3])
                if (!deleted) {
                    writeJsonError(output, 404, "Photo Not Found")
                    return
                }
                onMutation?.invoke()
                writeJson(output, 200, DiscoveryProtocol.json.encodeToString(DeleteResult(1)))
            }

            else -> writeJsonError(output, 404, "Not Found")
        }
    }

    private fun createBodyStream(headers: Map<String, String>, input: BufferedInputStream): InputStream? {
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        if (transferEncoding != null && transferEncoding.contains("chunked")) {
            return ChunkedInputStream(input)
        }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: return null
        if (contentLength < 0) return null
        return LimitedInputStream(input, contentLength)
    }

    private fun drainQuietly(stream: InputStream) {
        val buf = ByteArray(8192)
        runCatching {
            while (stream.read(buf) > 0) {
                // discard remaining bytes
            }
        }
    }

    private fun writeJsonError(output: OutputStream, status: Int, message: String) {
        writeJson(output, status, DiscoveryProtocol.json.encodeToString(ErrorResponse(message)))
    }

    private fun writeJson(output: OutputStream, status: Int, jsonBody: String) {
        writeBinary(output, status, "application/json; charset=utf-8", jsonBody.toByteArray(StandardCharsets.UTF_8))
    }

    private fun serveStatic(segments: List<String>, output: OutputStream): Boolean {
        val root = staticRoot ?: return false
        if (segments.any { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) return false
        val rel = if (segments.isEmpty()) "index.html" else segments.joinToString("/")
        val rootCanon = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val file = runCatching { File(rootCanon, rel).canonicalFile }.getOrNull() ?: return false
        val rootPath = rootCanon.path + File.separator
        if (!file.path.startsWith(rootPath) || !file.isFile) return false
        val type = when (file.extension.lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        writeBinary(output, 200, type, file.readBytes())
        return true
    }

    private fun writeBinary(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-File-Name\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    companion object {
        internal fun readAsciiLine(input: InputStream): String? {
            val out = ByteArrayOutputStream(128)
            var readAny = false
            while (true) {
                val b = input.read()
                if (b == -1) {
                    return if (readAny) out.toString(StandardCharsets.UTF_8.name()) else null
                }
                readAny = true
                if (b == '\n'.code) {
                    val bytes = out.toByteArray()
                    val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                    return String(bytes, 0, end, StandardCharsets.UTF_8)
                }
                out.write(b)
                if (out.size() > 16_384) throw IllegalArgumentException("Header line too long")
            }
        }

        internal fun decodePathSegment(raw: String): String =
            URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())

        internal fun parseQuery(rawQuery: String): Map<String, String> {
            if (rawQuery.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    map[URLDecoder.decode(pair, StandardCharsets.UTF_8.name())] = ""
                } else {
                    val k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name())
                    val v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name())
                    map[k] = v
                }
            }
            return map
        }
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val count = delegate.read(b, off, toRead)
            if (count > 0) remaining -= count
            return count
        }
    }

    private class ChunkedInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        private var chunkRemaining = 0L
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (finished) return -1
            if (len == 0) return 0
            while (chunkRemaining == 0L) {
                val sizeLine = readAsciiLine(delegate) ?: throw EOFException("Unexpected EOF in chunked stream")
                val clean = sizeLine.substringBefore(';').trim()
                val nextSize = clean.toLongOrNull(16) ?: throw IOException("Invalid chunk size: $sizeLine")
                if (nextSize == 0L) {
                    // Consume trailing headers until empty line
                    while (true) {
                        val trailer = readAsciiLine(delegate) ?: break
                        if (trailer.isEmpty()) break
                    }
                    finished = true
                    return -1
                }
                chunkRemaining = nextSize
            }
            val toRead = minOf(len.toLong(), chunkRemaining).toInt()
            val count = delegate.read(b, off, toRead)
            if (count == -1) throw EOFException("Unexpected EOF inside chunk")
            chunkRemaining -= count
            if (chunkRemaining == 0L) {
                // Consume CRLF after chunk payload
                readAsciiLine(delegate)
            }
            return count
        }
    }
}
