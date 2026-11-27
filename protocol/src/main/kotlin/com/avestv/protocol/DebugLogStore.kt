package com.avestv.protocol

import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe ring-buffer + rolling JSON-lines file logger shared across TV and Phone.
 * Keeps up to [maxEntries] in memory and rotates [logFile] when it exceeds [maxFileBytes].
 */
class DebugLogStore(
    private val logFile: File? = null,
    private val maxEntries: Int = 500,
    private val maxFileBytes: Long = 512L * 1024L,
    private val onLog: ((LogEntry) -> Unit)? = null,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(maxEntries.coerceAtLeast(16))
    private val nextId = AtomicLong(1L)

    init {
        loadExistingFromFile()
    }

    fun log(level: String, tag: String, message: String): LogEntry {
        val entry = LogEntry(
            id = nextId.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = level.uppercase(),
            tag = tag,
            message = message,
        )
        synchronized(lock) {
            if (buffer.size >= maxEntries) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            appendToFileLocked(entry)
        }
        runCatching { onLog?.invoke(entry) }
        return entry
    }

    fun info(tag: String, message: String): LogEntry = log("INFO", tag, message)

    fun warn(tag: String, message: String): LogEntry = log("WARN", tag, message)

    fun error(tag: String, message: String, error: Throwable? = null): LogEntry {
        val detail = if (error != null) {
            ": ${error.javaClass.simpleName}: ${error.message ?: "unknown"}"
        } else {
            ""
        }
        return log("ERROR", tag, "$message$detail")
    }

    fun entries(limit: Int = 200, sinceId: Long = 0L): List<LogEntry> {
        val capped = limit.coerceIn(1, maxEntries)
        val filtered = synchronized(lock) {
            if (sinceId <= 0L) {
                buffer.toList()
            } else {
                buffer.filter { it.id > sinceId }
            }
        }
        return if (filtered.size <= capped) filtered else filtered.subList(filtered.size - capped, filtered.size)
    }

    fun entriesJson(limit: Int = 200, sinceId: Long = 0L): String =
        DiscoveryProtocol.json.encodeToString(entries(limit, sinceId))

    fun clear(): Int {
        val removed: Int
        synchronized(lock) {
            removed = buffer.size
            buffer.clear()
            val file = logFile
            if (file != null) {
                runCatching {
                    if (file.exists()) file.delete()
                    val rotated = File(file.parentFile, "${file.name}.1")
                    if (rotated.exists()) rotated.delete()
                }
            }
        }
        return removed
    }

    private fun loadExistingFromFile() {
        val file = logFile ?: return
        if (!file.isFile) return
        val loaded = mutableListOf<LogEntry>()
        runCatching {
            file.forEachLine(StandardCharsets.UTF_8) { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return@forEachLine
                runCatching { DiscoveryProtocol.json.decodeFromString<LogEntry>(trimmed) }
                    .getOrNull()
                    ?.let { loaded.add(it) }
            }
        }
        if (loaded.isEmpty()) return
        val tail = if (loaded.size <= maxEntries) loaded else loaded.subList(loaded.size - maxEntries, loaded.size)
        synchronized(lock) {
            buffer.addAll(tail)
            val maxLoadedId = tail.maxOfOrNull { it.id } ?: 0L
            if (maxLoadedId >= nextId.get()) {
                nextId.set(maxLoadedId + 1L)
            }
        }
    }

    private fun appendToFileLocked(entry: LogEntry) {
        val file = logFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() >= maxFileBytes) {
                val backup = File(file.parentFile, "${file.name}.1")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            val line = DiscoveryProtocol.json.encodeToString(entry) + "\n"
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }
}
