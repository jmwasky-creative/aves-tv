package com.avestv.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.avestv.protocol.DiscoveryProtocol
import com.avestv.protocol.UdpDiscoveryResponder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReceiverRuntimeState(
    val running: Boolean = false,
    val deviceName: String = "客厅电视",
    val localIps: List<String> = emptyList(),
    val httpPort: Int = DiscoveryProtocol.HTTP_DEFAULT_PORT,
    val albumCount: Int = 0,
    val totalPhotos: Int = 0,
    val freeBytes: Long = 0L,
    val lastEvent: String = "等待手机推送",
    val error: String? = null,
    val usbConnected: Boolean = false,
    val usbStatusText: String = "等待手机通过 USB 接入",
)

class ReceiverForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: TvPreferences
    private lateinit var repository: MediaStoreAlbumRepository

    private var httpServer: TvHttpServer? = null
    private var udpResponder: UdpDiscoveryResponder? = null
    private var usbHostManager: UsbAoaHostManager? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        prefs = TvPreferences(this)
        repository = MediaStoreAlbumRepository(this, prefs)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                serviceScope.launch {
                    reregisterNsd()
                    refreshState("已更新电视名称：${prefs.deviceName}")
                }
            }
            else -> {
                startServersIfNeeded()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServers()
        serviceScope.cancel()
        _state.value = _state.value.copy(running = false, lastEvent = "服务已停止")
        super.onDestroy()
    }

    private fun startAsForeground() {
        val openIntent = Intent(this, TvMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun startServersIfNeeded() {
        if (httpServer != null) {
            serviceScope.launch { refreshState(null) }
            return
        }
        serviceScope.launch {
            try {
                acquireLocks()
                val server = TvHttpServer(
                    port = DiscoveryProtocol.HTTP_DEFAULT_PORT,
                    store = repository,
                    onMutation = {
                        serviceScope.launch {
                            val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            refreshState("刚收到手机相册更新 ($now)")
                        }
                    },
                )
                server.start()
                httpServer = server

                val udp = UdpDiscoveryResponder(
                    port = DiscoveryProtocol.UDP_DISCOVERY_PORT,
                    infoProvider = { repository.deviceInfo(server.boundPort) },
                )
                runCatching { udp.start() }
                udpResponder = udp

                registerNsd(server.boundPort)

                val usbMgr = UsbAoaHostManager(
                    context = this@ReceiverForegroundService,
                    targetHttpPortProvider = { server.boundPort },
                    onStateChanged = { connected, statusText ->
                        val prev = _state.value
                        val event = when {
                            connected && !prev.usbConnected -> "手机已通过 USB 直连"
                            !connected && prev.usbConnected -> "USB 连接已断开"
                            else -> prev.lastEvent
                        }
                        _state.value = prev.copy(
                            usbConnected = connected,
                            usbStatusText = statusText,
                            lastEvent = event,
                        )
                    },
                )
                runCatching { usbMgr.start() }
                usbHostManager = usbMgr

                refreshState("服务已就绪，手机打开即可自动搜索连接")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    error = "启动失败：${e.message}",
                    lastEvent = "启动失败：${e.message}",
                )
            }
        }
    }

    private fun stopServers() {
        unregisterNsd()
        runCatching { usbHostManager?.stop() }
        usbHostManager = null
        runCatching { udpResponder?.close() }
        udpResponder = null
        runCatching { httpServer?.close() }
        httpServer = null
        releaseLocks()
    }

    private fun registerNsd(port: Int) {
        unregisterNsd()
        runCatching {
            val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = manager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AvesTV-${prefs.deviceId.take(6)}"
                serviceType = DiscoveryProtocol.NSD_SERVICE_TYPE
                setPort(port)
                setAttribute("deviceId", prefs.deviceId)
                setAttribute("deviceName", prefs.deviceName)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            nsdRegistrationListener = listener
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun reregisterNsd() {
        val port = httpServer?.boundPort ?: DiscoveryProtocol.HTTP_DEFAULT_PORT
        registerNsd(port)
    }

    private fun unregisterNsd() {
        val listener = nsdRegistrationListener
        if (listener != null) {
            runCatching { nsdManager?.unregisterService(listener) }
        }
        nsdRegistrationListener = null
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("avestv-multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifi.createWifiLock(mode, "avestv-wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun refreshState(eventText: String?) {
        val port = httpServer?.boundPort ?: DiscoveryProtocol.HTTP_DEFAULT_PORT
        val info = repository.deviceInfo(port)
        val ips = detectLocalLanIpv4s()
        _state.value = _state.value.copy(
            running = httpServer != null,
            deviceName = info.deviceName,
            localIps = ips,
            httpPort = port,
            albumCount = info.albumCount,
            totalPhotos = info.totalPhotos,
            freeBytes = info.freeBytes,
            lastEvent = eventText ?: _state.value.lastEvent,
            error = null,
        )
    }

    private fun detectLocalLanIpv4s(): List<String> = runCatching {
        val result = mutableListOf<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (iface in ifaces.toList()) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                val ip = (addr as? Inet4Address)?.hostAddress ?: continue
                if (!addr.isLoopbackAddress && DiscoveryProtocol.isPrivateLanIpv4(ip)) {
                    result.add(ip)
                }
            }
        }
        result.distinct()
    }.getOrDefault(emptyList())

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "avestv_receiver_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.avestv.tv.ACTION_START"
        const val ACTION_REFRESH = "com.avestv.tv.ACTION_REFRESH"
        const val ACTION_STOP = "com.avestv.tv.ACTION_STOP"

        private val _state = MutableStateFlow(ReceiverRuntimeState())
        val state: StateFlow<ReceiverRuntimeState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun refresh(context: Context) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_REFRESH
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
