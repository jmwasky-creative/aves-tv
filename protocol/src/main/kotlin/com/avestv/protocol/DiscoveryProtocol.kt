package com.avestv.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets

object DiscoveryProtocol {
    const val PROTOCOL_VERSION = 1
    const val UDP_DISCOVERY_PORT = 53318
    const val HTTP_DEFAULT_PORT = 53319
    const val NSD_SERVICE_TYPE = "_avestv._tcp."
    const val REQUEST_MAGIC = "AVESTV_DISCOVER_V1"
    const val RESPONSE_MAGIC = "AVESTV_HERE_V1"
    const val DEFAULT_ALBUM_NAME = "家庭相册"
    const val MAX_NAME_LENGTH = 80

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val ILLEGAL_FS_CHARS = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")
    private val MULTI_SPACE = Regex("\\s+")

    fun encodeDiscoveryRequest(): ByteArray =
        REQUEST_MAGIC.toByteArray(StandardCharsets.UTF_8)

    fun isDiscoveryRequest(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length < REQUEST_MAGIC.length || length > 128) return false
        val text = String(bytes, 0, length, StandardCharsets.UTF_8).trim()
        return text == REQUEST_MAGIC
    }

    fun encodeDiscoveryResponse(info: TvDeviceInfo): ByteArray {
        val payload = DiscoveryProbeResponse(
            deviceId = info.deviceId,
            deviceName = info.deviceName,
            httpPort = info.httpPort,
            freeBytes = info.freeBytes,
            albumCount = info.albumCount,
            totalPhotos = info.totalPhotos,
            protocolVersion = info.protocolVersion,
        )
        return json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeDiscoveryResponse(bytes: ByteArray, length: Int = bytes.size): TvDeviceInfo? {
        if (length <= 0 || length > 4096) return null
        val text = String(bytes, 0, length, StandardCharsets.UTF_8).trim()
        if (!text.startsWith("{")) return null
        return runCatching {
            val parsed = json.decodeFromString<DiscoveryProbeResponse>(text)
            if (parsed.magic == RESPONSE_MAGIC && parsed.deviceId.isNotBlank() && parsed.httpPort in 1..65535) {
                parsed.toDeviceInfo()
            } else {
                null
            }
        }.getOrNull()
    }

    fun sanitizeAlbumName(raw: String?): String {
        val cleaned = (raw ?: "")
            .replace("..", "")
            .replace(ILLEGAL_FS_CHARS, " ")
            .replace(MULTI_SPACE, " ")
            .trim()
            .trim('.')
            .take(MAX_NAME_LENGTH)
            .trim()
            .trim('.')
        return cleaned.ifEmpty { DEFAULT_ALBUM_NAME }
    }

    fun sanitizeFileName(raw: String?, fallbackExt: String = "jpg"): String {
        val base = (raw ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "")
            .replace(ILLEGAL_FS_CHARS, "_")
            .trim()
            .trim('.')
        if (base.isEmpty()) {
            return "photo.${fallbackExt.trimStart('.')}"
        }
        val dotIndex = base.lastIndexOf('.')
        val (stem, ext) = if (dotIndex > 0 && dotIndex < base.length - 1) {
            base.substring(0, dotIndex) to base.substring(dotIndex + 1)
        } else {
            base to fallbackExt.trimStart('.')
        }
        val safeExt = ext.filter { it.isLetterOrDigit() }.lowercase().ifEmpty { fallbackExt.trimStart('.') }
        val maxStemLen = (MAX_NAME_LENGTH - safeExt.length - 1).coerceAtLeast(8)
        val safeStem = stem.trim().trim('.').take(maxStemLen).ifEmpty { "photo" }
        return "$safeStem.$safeExt"
    }

    fun isPrivateLanIpv4(ip: String): Boolean {
        val parts = parseIpv4(ip) ?: return false
        val a = parts[0]
        val b = parts[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }

    /**
     * Generates all sibling host IPs in the same /24 LAN subnet, excluding [localIpv4] itself.
     * Returns an empty list for loopback, link-local, or non-private addresses.
     */
    fun buildSubnetTargets(localIpv4: String): List<String> {
        val parts = parseIpv4(localIpv4) ?: return emptyList()
        if (!isPrivateLanIpv4(localIpv4)) return emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val selfHost = parts[3]
        return buildList(253) {
            for (host in 1..254) {
                if (host != selfHost) {
                    add("$prefix.$host")
                }
            }
        }
    }

    /**
     * Computes directed IPv4 broadcast address (e.g. 192.168.1.255 for 192.168.1.42/24).
     */
    fun computeDirectedBroadcast(localIpv4: String, prefixLength: Int): String? {
        val parts = parseIpv4(localIpv4) ?: return null
        if (prefixLength !in 8..30) return null
        val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = (-1 shl (32 - prefixLength))
        val broadcast = ipInt or mask.inv()
        return "${(broadcast ushr 24) and 0xFF}.${(broadcast ushr 16) and 0xFF}.${(broadcast ushr 8) and 0xFF}.${broadcast and 0xFF}"
    }

    fun isValidIpv4(ip: String): Boolean = parseIpv4(ip) != null

    fun toInet4Address(ip: String): Inet4Address? {
        if (!isValidIpv4(ip)) return null
        return runCatching { InetAddress.getByName(ip) as? Inet4Address }.getOrNull()
    }

    private fun parseIpv4(ip: String): IntArray? {
        val segments = ip.trim().split('.')
        if (segments.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val s = segments[i]
            if (s.isEmpty() || s.length > 3 || !s.all { it.isDigit() }) return null
            val v = s.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }
}
