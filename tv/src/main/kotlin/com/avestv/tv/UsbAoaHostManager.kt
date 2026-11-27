package com.avestv.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.avestv.protocol.DebugLogStore
import com.avestv.protocol.UsbAoaProtocol
import com.avestv.protocol.UsbTunnelHostBridge
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages TV-side USB Host (AOA 2.0) discovery and connection.
 * When a phone is plugged into the TV's USB port, performs the AOA handshake
 * to switch the phone into Accessory mode and bridges USB traffic to the local HTTP server.
 */
class UsbAoaHostManager(
    context: Context,
    private val targetHttpPortProvider: () -> Int,
    private val logStore: DebugLogStore? = null,
    private val onStateChanged: (connected: Boolean, statusText: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val actionPermission = "${appContext.packageName}.USB_HOST_PERMISSION"
    private val started = AtomicBoolean(false)
    private val lock = Any()

    private var executor: ScheduledExecutorService? = null
    private var activeDeviceId: Int? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    private var hostBridge: UsbTunnelHostBridge? = null
    private val permissionRequestedIds = HashSet<Int>()
    private val lastHandshakeAttemptMs = HashMap<Int, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = extractDevice(intent)
            when (intent.action) {
                actionPermission -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        synchronized(lock) { permissionRequestedIds.remove(device.deviceId) }
                        if (granted) {
                            logStore?.info("USB", "USB host permission granted for ${device.deviceName}")
                            dispatchInspect(device)
                        } else {
                            logStore?.warn("USB", "USB host permission denied for ${device.deviceName}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null) {
                        logStore?.info("USB", "USB device attached: ${device.deviceName} (vid=${device.vendorId}, pid=${device.productId})")
                        dispatchInspect(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null) {
                        synchronized(lock) { permissionRequestedIds.remove(device.deviceId) }
                        if (synchronized(lock) { activeDeviceId == device.deviceId }) {
                            logStore?.info("USB", "USB device detached: ${device.deviceName}")
                            closeSession("等待手机通过 USB 接入")
                        }
                    }
                }
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "avestv-usb-host-mgr").apply { isDaemon = true }
        }
        executor = scheduler
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(actionPermission),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            attachFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        onStateChanged(false, "等待手机通过 USB 接入")
        scanExistingDevices()
        scheduler.scheduleWithFixedDelay(
            {
                if (started.get() && synchronized(lock) { hostBridge == null }) {
                    runCatching { scanExistingDevices() }
                }
            },
            3,
            3,
            TimeUnit.SECONDS,
        )
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { appContext.unregisterReceiver(receiver) }
        closeSession("等待手机通过 USB 接入")
        executor?.shutdownNow()
        executor = null
    }

    private fun scanExistingDevices() {
        val manager = usbManager ?: return
        val devices = runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())
        devices.forEach { dispatchInspect(it) }
    }

    private fun dispatchInspect(device: UsbDevice) {
        if (!started.get()) return
        if (!isCandidatePhone(device)) return
        executor?.execute {
            runCatching { inspectDevice(device) }
        }
    }

    private fun isCandidatePhone(device: UsbDevice): Boolean {
        if (UsbAoaProtocol.isAccessoryDevice(device.vendorId, device.productId)) return true
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return false
        return device.deviceClass == UsbConstants.USB_CLASS_PER_INTERFACE ||
            device.deviceClass == UsbConstants.USB_CLASS_VENDOR_SPEC ||
            device.deviceClass == UsbConstants.USB_CLASS_MISC ||
            device.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE
    }

    private fun inspectDevice(device: UsbDevice) {
        val manager = usbManager ?: return
        synchronized(lock) {
            if (hostBridge != null && activeDeviceId == device.deviceId) return
        }
        if (!manager.hasPermission(device)) {
            synchronized(lock) {
                if (!permissionRequestedIds.add(device.deviceId)) return
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permIntent = Intent(actionPermission).setPackage(appContext.packageName)
            val pi = PendingIntent.getBroadcast(appContext, device.deviceId, permIntent, flags)
            runCatching { manager.requestPermission(device, pi) }
            return
        }

        if (UsbAoaProtocol.isAccessoryDevice(device.vendorId, device.productId)) {
            openAccessorySession(device)
        } else {
            val now = System.currentTimeMillis()
            val shouldAttempt = synchronized(lock) {
                val prev = lastHandshakeAttemptMs[device.deviceId] ?: 0L
                if (now - prev < 5_000L) false else {
                    lastHandshakeAttemptMs[device.deviceId] = now
                    true
                }
            }
            if (shouldAttempt) {
                trySwitchToAccessoryMode(device)
            }
        }
    }

    private fun trySwitchToAccessoryMode(device: UsbDevice) {
        val manager = usbManager ?: return
        val conn = runCatching { manager.openDevice(device) }.getOrNull() ?: return
        try {
            val protoBuf = ByteArray(2)
            val readLen = conn.controlTransfer(
                0xC0,
                UsbAoaProtocol.CMD_GET_PROTOCOL,
                0,
                0,
                protoBuf,
                2,
                1_500,
            )
            if (readLen < 2) return
            val protocol = (protoBuf[0].toInt() and 0xFF) or ((protoBuf[1].toInt() and 0xFF) shl 8)
            if (protocol < 1) return

            sendString(conn, UsbAoaProtocol.STR_MANUFACTURER, UsbAoaProtocol.MANUFACTURER)
            sendString(conn, UsbAoaProtocol.STR_MODEL, UsbAoaProtocol.MODEL)
            sendString(conn, UsbAoaProtocol.STR_DESCRIPTION, UsbAoaProtocol.DESCRIPTION)
            sendString(conn, UsbAoaProtocol.STR_VERSION, UsbAoaProtocol.VERSION)
            sendString(conn, UsbAoaProtocol.STR_URI, UsbAoaProtocol.URI)
            sendString(conn, UsbAoaProtocol.STR_SERIAL, UsbAoaProtocol.SERIAL)

            val startedRes = conn.controlTransfer(
                0x40,
                UsbAoaProtocol.CMD_START,
                0,
                0,
                null,
                0,
                1_500,
            )
            if (startedRes >= 0) {
                logStore?.info("USB", "AOA handshake sent (proto=$protocol), waiting for phone re-enumeration")
                onStateChanged(false, "正在建立 USB 直连...")
            } else {
                logStore?.warn("USB", "AOA CMD_START failed ($startedRes)")
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun sendString(conn: UsbDeviceConnection, index: Int, value: String) {
        val bytes = UsbAoaProtocol.nullTerminatedUtf8(value)
        conn.controlTransfer(0x40, UsbAoaProtocol.CMD_SEND_STRING, 0, index, bytes, bytes.size, 1_500)
    }

    private fun openAccessorySession(device: UsbDevice) {
        val manager = usbManager ?: return
        val conn = runCatching { manager.openDevice(device) }.getOrNull() ?: return

        var chosenIface: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var foundIn: UsbEndpoint? = null
            var foundOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) foundOut = ep
                }
            }
            if (foundIn != null && foundOut != null) {
                chosenIface = iface
                inEp = foundIn
                outEp = foundOut
                break
            }
        }

        if (chosenIface == null || inEp == null || outEp == null || !conn.claimInterface(chosenIface, true)) {
            runCatching { conn.close() }
            return
        }

        val sessionOpen = AtomicBoolean(true)
        val usbIn = UsbBulkInputStream(
            conn = conn,
            endpoint = inEp,
            isAlive = {
                started.get() && sessionOpen.get() &&
                    runCatching { manager.deviceList.values.any { it.deviceId == device.deviceId } }.getOrDefault(false)
            },
        )
        val usbOut = UsbBulkOutputStream(conn, outEp, sessionOpen)

        val bridge = UsbTunnelHostBridge(
            usbIn = usbIn,
            usbOut = usbOut,
            targetPort = targetHttpPortProvider(),
            onClosed = {
                sessionOpen.set(false)
                closeSession("等待手机通过 USB 接入")
            },
        )

        synchronized(lock) {
            closeLocked()
            activeDeviceId = device.deviceId
            activeConnection = conn
            activeInterface = chosenIface
            hostBridge = bridge
        }

        bridge.start()
        logStore?.info("USB", "AOA host bridge started for ${device.deviceName} -> 127.0.0.1:${targetHttpPortProvider()}")
        onStateChanged(true, "已通过 USB 直连手机")
    }

    private fun closeSession(statusText: String) {
        val changed: Boolean
        synchronized(lock) {
            changed = hostBridge != null || activeConnection != null
            closeLocked()
        }
        if (changed) {
            onStateChanged(false, statusText)
        }
    }

    private fun closeLocked() {
        runCatching { hostBridge?.close() }
        hostBridge = null
        val iface = activeInterface
        val conn = activeConnection
        if (iface != null && conn != null) {
            runCatching { conn.releaseInterface(iface) }
        }
        runCatching { conn?.close() }
        activeInterface = null
        activeConnection = null
        activeDeviceId = null
    }

    private fun extractDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private class UsbBulkInputStream(
        private val conn: UsbDeviceConnection,
        private val endpoint: UsbEndpoint,
        private val isAlive: () -> Boolean,
    ) : InputStream() {
        private val packetBuf = ByteArray(UsbAoaProtocol.MAX_USB_PACKET)
        private var pos = 0
        private var limit = 0

        override fun read(): Int {
            if (!ensureBuffered()) return -1
            return packetBuf[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensureBuffered()) return -1
            val count = minOf(len, limit - pos)
            System.arraycopy(packetBuf, pos, b, off, count)
            pos += count
            return count
        }

        private fun ensureBuffered(): Boolean {
            if (pos < limit) return true
            while (isAlive()) {
                val n = conn.bulkTransfer(endpoint, packetBuf, packetBuf.size, 2_000)
                if (n > 0) {
                    pos = 0
                    limit = n
                    return true
                }
            }
            return false
        }
    }

    private class UsbBulkOutputStream(
        private val conn: UsbDeviceConnection,
        private val endpoint: UsbEndpoint,
        private val sessionOpen: AtomicBoolean,
    ) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (!sessionOpen.get()) throw IOException("USB session closed")
                val chunk = minOf(remaining, UsbAoaProtocol.MAX_USB_PACKET)
                val payload = if (offset == 0 && chunk == b.size) b else b.copyOfRange(offset, offset + chunk)
                val sent = conn.bulkTransfer(endpoint, payload, chunk, 5_000)
                if (sent < 0) throw IOException("USB bulk write failed ($sent)")
                offset += sent
                remaining -= sent
            }
        }
    }
}
