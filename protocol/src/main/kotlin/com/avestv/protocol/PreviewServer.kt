package com.avestv.protocol

import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts only the album HTTP server, UDP discovery responder, ADB USB watcher, and the shared web pages.
 * Phone discovery, photo picking, 4K transcoding, and the TV boot service stay in the APKs.
 */
fun main(args: Array<String>) {
    val web = locateWebRoot()
    val preferred = args.firstOrNull()?.toIntOrNull() ?: DiscoveryProtocol.HTTP_DEFAULT_PORT
    val store = PreviewAlbumStore()
    var usbWatcher: AdbUsbWatcher? = null
    val server = try {
        StreamingHttpServer(
            port = preferred,
            store = store,
            staticRoot = web,
            usbStatusProvider = { usbWatcher?.status ?: UsbLinkStatus() },
        ).also { it.start() }
    } catch (_: Exception) {
        StreamingHttpServer(
            port = 0,
            store = store,
            staticRoot = web,
            usbStatusProvider = { usbWatcher?.status ?: UsbLinkStatus() },
        ).also { it.start() }
    }
    val port = server.boundPort
    usbWatcher = AdbUsbWatcher(port).also { it.start() }
    val udp = runCatching {
        UdpDiscoveryResponder(
            port = DiscoveryProtocol.UDP_DISCOVERY_PORT,
            infoProvider = { store.deviceInfo(port) },
        ).also { it.start() }
    }.getOrNull()
    val lanIps = detectPrivateLanIps()
    println("AvesTV preview")
    println("  phone  http://127.0.0.1:$port/phone.html")
    println("  tv     http://127.0.0.1:$port/tv.html")
    if (lanIps.isNotEmpty()) {
        println("  lan    ${lanIps.joinToString(", ") { "http://$it:$port" }}")
    }
    println("  udp    ${if (udp != null) "listening on ${udp.boundPort}" else "unavailable"}")
    println("  usb    ${usbWatcher.status.statusText}")
    println("  web    ${web.path}")
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { usbWatcher.close() }
        runCatching { udp?.close() }
        runCatching { server.close() }
    })
    Thread.currentThread().join()
}

internal class AdbUsbWatcher(
    private val targetPort: Int,
) : Closeable {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val mappedSerials = HashSet<String>()

    @Volatile
    var status: UsbLinkStatus = UsbLinkStatus(false, "等待手机通过 USB 接入（插线后自动打通）")
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pollOnce()
        thread = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(1_500)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                pollOnce()
            }
        }, "avestv-adb-usb-watcher").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun pollOnce() {
        val adb = findAdbExecutable()
        if (adb == null) {
            status = UsbLinkStatus(false, "未找到 adb（请安装 Android SDK platform-tools）")
            return
        }
        val output = runCmd(listOf(adb, "devices", "-l")) ?: return
        val online = mutableListOf<Pair<String, String>>()
        var unauthorizedCount = 0

        for (line in output.lineSequence().drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val serial = parts[0]
            val state = parts[1]
            when (state) {
                "device" -> {
                    val model = parts.firstOrNull { it.startsWith("model:") }
                        ?.removePrefix("model:")
                        ?.takeIf { it.isNotBlank() }
                        ?: serial
                    online.add(serial to model)
                }
                "unauthorized" -> unauthorizedCount++
            }
        }

        val currentSerials = online.map { it.first }.toSet()
        mappedSerials.retainAll(currentSerials)

        val readyLabels = mutableListOf<String>()
        for ((serial, model) in online) {
            val existingReverse = runCmd(listOf(adb, "-s", serial, "reverse", "--list")).orEmpty()
            val expectedRule = "tcp:${DiscoveryProtocol.HTTP_DEFAULT_PORT} tcp:$targetPort"
            val mapped = if (existingReverse.contains(expectedRule)) {
                true
            } else {
                val res = runCmd(
                    listOf(
                        adb,
                        "-s",
                        serial,
                        "reverse",
                        "tcp:${DiscoveryProtocol.HTTP_DEFAULT_PORT}",
                        "tcp:$targetPort",
                    ),
                )
                if (res != null && mappedSerials.add(serial)) {
                    println("[USB 隧道] 已自动为手机 $model ($serial) 打通 127.0.0.1:${DiscoveryProtocol.HTTP_DEFAULT_PORT} -> $targetPort")
                }
                res != null
            }
            if (mapped) {
                readyLabels.add(model)
            }
        }

        status = when {
            readyLabels.isNotEmpty() -> UsbLinkStatus(
                connected = true,
                statusText = "已通过 USB 连接手机（${readyLabels.joinToString("、")}）",
            )
            unauthorizedCount > 0 -> UsbLinkStatus(
                connected = false,
                statusText = "检测到未授权 USB 手机，请在手机上点击「允许 USB 调试」",
            )
            else -> UsbLinkStatus(
                connected = false,
                statusText = "等待手机通过 USB 接入（插线后自动打通）",
            )
        }
    }

    private fun findAdbExecutable(): String? {
        val candidates = listOfNotNull(
            System.getenv("ADB")?.takeIf { it.isNotBlank() },
            System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { "$it/platform-tools/adb" },
            System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { "$it/platform-tools/adb" },
            "/Users/a1/Library/Android/sdk/platform-tools/adb",
            "${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb",
        )
        for (path in candidates) {
            val file = File(path)
            if (file.isFile && file.canExecute()) return file.absolutePath
        }
        return runCmd(listOf("which", "adb"))?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun runCmd(cmd: List<String>): String? = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        if (proc.waitFor(3, TimeUnit.SECONDS) && proc.exitValue() == 0) out else null
    }.getOrNull()
}

private fun detectPrivateLanIps(): List<String> = runCatching {
    val out = mutableListOf<String>()
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    for (iface in ifaces.toList()) {
        if (!iface.isUp || iface.isLoopback) continue
        for (addr in iface.inetAddresses.toList()) {
            val ip = (addr as? Inet4Address)?.hostAddress ?: continue
            if (!addr.isLoopbackAddress && DiscoveryProtocol.isPrivateLanIpv4(ip)) {
                out.add(ip)
            }
        }
    }
    out.distinct()
}.getOrDefault(emptyList())

internal fun locateWebRoot(): File {
    val env = System.getenv("AVESTV_WEB")
    val candidates = listOfNotNull(
        env?.takeIf { it.isNotBlank() }?.let { File(it) },
        File("web"),
        File("../web"),
        File("../../web"),
    )
    return candidates.firstOrNull { File(it, "phone.html").isFile }?.canonicalFile
        ?: File("web").canonicalFile
}
