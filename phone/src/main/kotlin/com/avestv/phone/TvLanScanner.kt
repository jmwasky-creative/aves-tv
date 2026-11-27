package com.avestv.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.avestv.protocol.DebugLogStore
import com.avestv.protocol.DiscoveryProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Three-layer automatic LAN TV discovery (zero manual IP entry):
 * 1. Instant verification of last-connected TV IP (< 50ms).
 * 2. Concurrent UDP directed/global broadcast + NsdManager (mDNS) lookup (~50-300ms).
 * 3. Automatic /24 subnet HTTP probe fallback when routers block broadcast/multicast (< 1s).
 */
class TvLanScanner(
    private val context: Context,
    private val apiClient: TvApiClient,
    private val logStore: DebugLogStore? = null,
) {
    private val prefs = context.applicationContext.getSharedPreferences("avestv_phone_prefs", Context.MODE_PRIVATE)

    fun rememberTv(tv: DiscoveredTv) {
        val editor = prefs.edit().putString(KEY_LAST_ID, tv.info.deviceId)
        if (tv.ip != "127.0.0.1") {
            editor
                .putString(KEY_LAST_IP, tv.ip)
                .putInt(KEY_LAST_PORT, tv.port)
        }
        editor.apply()
    }

    fun lastConnectedDeviceId(): String? = prefs.getString(KEY_LAST_ID, null)

    fun localLanIps(): List<String> = collectLocalPrivateIpv4s()

    suspend fun connectByIp(rawInput: String): DiscoveredTv? = withContext(Dispatchers.IO) {
        val cleaned = rawInput.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()
        if (cleaned.isEmpty()) return@withContext null

        val colon = cleaned.lastIndexOf(':')
        val (host, port) = if (colon > 0 && colon < cleaned.length - 1) {
            val h = cleaned.substring(0, colon).trim()
            val p = cleaned.substring(colon + 1).trim().toIntOrNull() ?: return@withContext null
            h to p
        } else {
            cleaned to DiscoveryProtocol.HTTP_DEFAULT_PORT
        }
        if (host.isBlank() || port !in 1..65535) return@withContext null

        val info = apiClient.probeInfo(host, port, logFailure = true) ?: return@withContext null
        val tv = DiscoveredTv(host, info, "手动 IP 连接", port = port)
        rememberTv(tv)
        logStore?.info("SCAN", "Manual connect succeeded: ${tv.info.deviceName} at $host:$port")
        tv
    }

    suspend fun probeUsb(usbBridgePort: Int? = null): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DiscoveredTv>()
        if (usbBridgePort != null && usbBridgePort in 1..65535) {
            val info = apiClient.probeInfo(UsbReconnectPolicy.LOOPBACK_IP, usbBridgePort)
            if (info != null) {
                out.add(DiscoveredTv(UsbReconnectPolicy.LOOPBACK_IP, info, "USB 直连（AOA）", port = usbBridgePort))
            }
        }
        if (usbBridgePort != DiscoveryProtocol.HTTP_DEFAULT_PORT) {
            val info = apiClient.probeInfo(UsbReconnectPolicy.LOOPBACK_IP, DiscoveryProtocol.HTTP_DEFAULT_PORT)
            if (info != null) {
                out.add(
                    DiscoveredTv(
                        ip = UsbReconnectPolicy.LOOPBACK_IP,
                        info = info,
                        discoverySource = "USB 隧道直连",
                        port = DiscoveryProtocol.HTTP_DEFAULT_PORT,
                    ),
                )
            }
        }
        out
    }

    suspend fun scan(
        forceFullSubnetScan: Boolean = false,
        usbBridgePort: Int? = null,
        onFound: (DiscoveredTv) -> Unit,
    ): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredTv>()
        val localIps = collectLocalPrivateIpv4s()
        logStore?.info("SCAN", "Start scan (force=$forceFullSubnetScan, usbPort=$usbBridgePort, phoneIps=$localIps)")

        fun register(tv: DiscoveredTv) {
            val prev = found[tv.info.deviceId]
            if (!LanScanPlan.shouldPreferOverExisting(prev, tv)) return
            found[tv.info.deviceId] = tv
            if (prev == null || prev.ip != tv.ip || prev.port != tv.port || prev.info.deviceName != tv.info.deviceName) {
                logStore?.info("SCAN", "Found TV: ${tv.info.deviceName} (${tv.ip}:${tv.port}) via ${tv.discoverySource}")
                onFound(tv)
            }
        }

        val multicastLock = acquireMulticastLock()
        try {
            coroutineScope {
                // Layer 0: Direct USB tunnel (AOA bridge or ADB reverse 127.0.0.1:53319)
                val usbJob = launch {
                    probeUsb(usbBridgePort).forEach { register(it) }
                }

                // Layer 1: Instant probe of previously connected TV IP
                val cachedJob = launch {
                    val lastIp = prefs.getString(KEY_LAST_IP, null)
                    val lastPort = prefs.getInt(KEY_LAST_PORT, DiscoveryProtocol.HTTP_DEFAULT_PORT)
                    if (LanScanPlan.shouldProbeCachedIp(lastIp)) {
                        val cachedIp = lastIp.orEmpty()
                        val info = apiClient.probeInfo(cachedIp, lastPort)
                        if (info != null) {
                            register(DiscoveredTv(cachedIp, info, "历史缓存秒连"))
                        }
                    }
                }

                // Layer 2a: UDP subnet broadcast + global broadcast discovery
                val udpJob = launch {
                    runUdpDiscovery { tv -> register(tv) }
                }

                // Layer 2b: Android NsdManager (mDNS) discovery
                val nsdJob = launch {
                    runNsdDiscovery { tv -> register(tv) }
                }

                // Layer 3: Fallback /24 subnet HTTP scan if broadcast/mDNS didn't respond within 320ms
                val subnetJob = launch {
                    if (!forceFullSubnetScan) {
                        delay(320)
                    }
                    if (LanScanPlan.shouldScanWholeSubnet(forceFullSubnetScan, found.size)) {
                        runSubnetHttpScan(alreadyFoundIps = { found.values.map { it.ip }.toSet() }) { tv ->
                            register(tv)
                        }
                    }
                }

                usbJob.join()
                cachedJob.join()
                udpJob.join()
                nsdJob.join()
                subnetJob.join()
            }
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) multicastLock.release()
            }
        }

        val sorted = found.values.sortedWith(
            compareBy<DiscoveredTv> { if (it.ip == "127.0.0.1") 0 else 1 }
                .thenBy { it.info.deviceName },
        )
        logStore?.info("SCAN", "Scan finished: ${sorted.size} TV(s)")
        sorted
    }

    private fun runUdpDiscovery(onFound: (DiscoveredTv) -> Unit) {
        val targets = collectBroadcastAddresses()
        val reqBytes = DiscoveryProtocol.encodeDiscoveryRequest()

        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250

                fun sendProbes() {
                    for (addr in targets) {
                        runCatching {
                            val packet = DatagramPacket(
                                reqBytes,
                                reqBytes.size,
                                addr,
                                DiscoveryProtocol.UDP_DISCOVERY_PORT,
                            )
                            socket.send(packet)
                        }
                    }
                }

                sendProbes()
                val start = System.currentTimeMillis()
                var resent = false
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() - start < 850) {
                    if (!resent && System.currentTimeMillis() - start >= 240) {
                        resent = true
                        sendProbes()
                    }
                    try {
                        val reply = DatagramPacket(buf, buf.size)
                        socket.receive(reply)
                        val info = DiscoveryProtocol.decodeDiscoveryResponse(reply.data, reply.length)
                        val ip = reply.address?.hostAddress
                        if (info != null && ip != null) {
                            onFound(DiscoveredTv(ip, info, "局域网广播发现"))
                        }
                    } catch (_: SocketTimeoutException) {
                        // continue until window expires
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun runNsdDiscovery(onFound: (DiscoveredTv) -> Unit) {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            val port = resolved.port.takeIf { it in 1..65535 }
                                ?: DiscoveryProtocol.HTTP_DEFAULT_PORT
                            val info = apiClient.probeInfo(host, port) ?: return
                            onFound(DiscoveredTv(host, info, "mDNS 发现"))
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        val started = runCatching {
            manager.discoverServices(DiscoveryProtocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        }.getOrDefault(false)

        if (started) {
            try {
                delay(900)
            } finally {
                runCatching { manager.stopServiceDiscovery(listener) }
            }
        }
    }

    private suspend fun runSubnetHttpScan(
        alreadyFoundIps: () -> Set<String>,
        onFound: (DiscoveredTv) -> Unit,
    ) = coroutineScope {
        val candidateIps = LanScanPlan.subnetCandidates(collectLocalPrivateIpv4s(), alreadyFoundIps())

        val semaphore = Semaphore(48)
        candidateIps.map { ip ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (ip in alreadyFoundIps()) return@withPermit
                    val info = apiClient.probeInfo(ip, DiscoveryProtocol.HTTP_DEFAULT_PORT)
                    if (info != null) {
                        onFound(DiscoveredTv(ip, info, "局域网网段自动发现"))
                    }
                }
            }
        }.awaitAll()
    }

    private fun collectLocalPrivateIpv4s(): List<String> = runCatching {
        val pairs = mutableListOf<Pair<String, String>>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (iface in ifaces.toList()) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                val ip = (addr as? Inet4Address)?.hostAddress ?: continue
                if (!addr.isLoopbackAddress && DiscoveryProtocol.isPrivateLanIpv4(ip)) {
                    pairs.add(iface.name to ip)
                }
            }
        }
        LanScanPlan.prioritizeInterfaces(pairs)
    }.getOrDefault(emptyList())

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (iface in ifaces.toList()) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (ifAddr in iface.interfaceAddresses) {
                        val ipv4 = ifAddr.address as? Inet4Address ?: continue
                        val ip = ipv4.hostAddress ?: continue
                        if (!DiscoveryProtocol.isPrivateLanIpv4(ip)) continue
                        val bcast = ifAddr.broadcast
                        if (bcast != null) {
                            out.add(bcast)
                        } else {
                            val computed = DiscoveryProtocol.computeDirectedBroadcast(
                                ip,
                                ifAddr.networkPrefixLength.toInt(),
                            )
                            if (computed != null) {
                                DiscoveryProtocol.toInet4Address(computed)?.let { out.add(it) }
                            }
                        }
                    }
                }
            }
        }
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out.toList()
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("avestv-phone-scan")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    companion object {
        private const val KEY_LAST_IP = "last_tv_ip"
        private const val KEY_LAST_PORT = "last_tv_port"
        private const val KEY_LAST_ID = "last_tv_id"
    }
}
