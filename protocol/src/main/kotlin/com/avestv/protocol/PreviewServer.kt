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
    val logFile = File("protocol/build/preview-tv.log")
    val pidFile = File("protocol/build/preview-server.pid")
    val currentPid = ProcessHandle.current().pid()
    stopPreviousPreviewInstance(pidFile, currentPid)
    writeOwnerPid(pidFile, currentPid)

    val logStore = DebugLogStore(logFile = logFile)
    var usbWatcher: AdbUsbWatcher? = null
    val server = try {
        StreamingHttpServer(
            port = preferred,
            store = store,
            staticRoot = web,
            usbStatusProvider = { usbWatcher?.status ?: UsbLinkStatus() },
            logStore = logStore,
        ).also { it.start() }
    } catch (_: Exception) {
        StreamingHttpServer(
            port = 0,
            store = store,
            staticRoot = web,
            usbStatusProvider = { usbWatcher?.status ?: UsbLinkStatus() },
            logStore = logStore,
        ).also { it.start() }
    }
    val port = server.boundPort
    var udp: UdpDiscoveryResponder? = null
    val shutdownNow = {
        runCatching { usbWatcher?.close() }
        runCatching { udp?.close() }
        runCatching { server.close() }
        if (readOwnerPid(pidFile) == currentPid) {
            runCatching { pidFile.delete() }
        }
    }
    usbWatcher = AdbUsbWatcher(
        targetPort = port,
        logStore = logStore,
        isOwner = { readOwnerPid(pidFile) == currentPid },
        onSuperseded = {
            logStore.warn("PREVIEW", "Superseded by newer PreviewServer instance; exiting pid=$currentPid")
            shutdownNow()
            System.exit(0)
        },
    ).also { it.start() }
    udp = runCatching {
        UdpDiscoveryResponder(
            port = DiscoveryProtocol.UDP_DISCOVERY_PORT,
            logStore = logStore,
            infoProvider = { store.deviceInfo(port) },
        ).also { it.start() }
    }.getOrNull()
    val lanIps = detectPrivateLanIps()
    logStore.info("PREVIEW", "Preview server started on port $port (pid=$currentPid, LAN: ${lanIps.joinToString(", ").ifEmpty { "none" }})")
    println("AvesTV preview")
    println("  phone  http://127.0.0.1:$port/phone.html")
    println("  tv     http://127.0.0.1:$port/tv.html")
    if (lanIps.isNotEmpty()) {
        println("  lan    ${lanIps.joinToString(", ") { "http://$it:$port" }}")
    }
    println("  udp    ${if (udp != null) "listening on ${udp.boundPort}" else "unavailable"}")
    println("  usb    ${usbWatcher.status.statusText}")
    println("  log    ${logFile.canonicalPath}")
    println("  web    ${web.path}")
    Runtime.getRuntime().addShutdownHook(Thread { shutdownNow() })
    Thread.currentThread().join()
}

private fun readOwnerPid(pidFile: File): Long? = runCatching {
    if (!pidFile.isFile) null else pidFile.readText().trim().toLongOrNull()
}.getOrNull()

private fun writeOwnerPid(pidFile: File, pid: Long) {
    runCatching {
        pidFile.parentFile?.mkdirs()
        pidFile.writeText(pid.toString())
    }
}

private fun stopPreviousPreviewInstance(pidFile: File, currentPid: Long) {
    val oldPid = readOwnerPid(pidFile) ?: return
    if (oldPid == currentPid) return
    val handle = ProcessHandle.of(oldPid).orElse(null) ?: return
    if (!handle.isAlive) return
    val cmd = handle.info().commandLine().orElse("")
    if (!cmd.contains("PreviewServer", ignoreCase = true) && !cmd.contains("java", ignoreCase = true)) return
    runCatching {
        handle.destroy()
        val deadline = System.currentTimeMillis() + 1_500L
        while (handle.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (handle.isAlive) {
            handle.destroyForcibly()
        }
    }
}

internal class AdbUsbWatcher(
    private val targetPort: Int,
    private val logStore: DebugLogStore? = null,
    private val isOwner: () -> Boolean = { true },
    private val onSuperseded: (() -> Unit)? = null,
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
        if (!isOwner()) {
            running.set(false)
            onSuperseded?.invoke()
            return
        }
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
                    val msg = "已自动为手机 $model ($serial) 打通 127.0.0.1:${DiscoveryProtocol.HTTP_DEFAULT_PORT} -> $targetPort"
                    println("[USB 隧道] $msg")
                    logStore?.info("USB", msg)
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
