package com.avestv.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.avestv.protocol.UsbAoaProtocol
import com.avestv.protocol.UsbTunnelClientBridge
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages phone-side Android Open Accessory (AOA 2.0) USB connection to the TV receiver.
 * When connected, exposes a local TCP bridge (`127.0.0.1:<localPort>`) so [TvApiClient]
 * and the WebView talk to the TV over USB using the exact same HTTP API.
 */
class UsbAoaAccessoryManager(
    context: Context,
    private val onBridgeStateChanged: (localPort: Int?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val actionPermission = "${appContext.packageName}.USB_ACCESSORY_PERMISSION"
    private val started = AtomicBoolean(false)
    private val lock = Any()

    private var activeAccessory: UsbAccessory? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var clientBridge: UsbTunnelClientBridge? = null
    private var permissionRequestedFor: String? = null

    val activeBridgePort: Int?
        get() = synchronized(lock) { clientBridge?.localPort }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != actionPermission) return
            val accessory = extractAccessory(intent) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                connectAccessory(accessory)
            }
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED) return
            disconnect()
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            IntentFilter(actionPermission),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        checkConnectedAccessories()
    }

    fun handleIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val accessory = extractAccessory(intent) ?: return
        connectAccessory(accessory)
    }

    fun checkConnectedAccessories() {
        val manager = usbManager ?: return
        val accessories = runCatching { manager.accessoryList }.getOrNull() ?: return
        val match = accessories.firstOrNull {
            it.manufacturer == UsbAoaProtocol.MANUFACTURER && it.model == UsbAoaProtocol.MODEL
        } ?: accessories.firstOrNull() ?: return
        connectAccessory(match)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        runCatching { appContext.unregisterReceiver(detachReceiver) }
        disconnect()
    }

    private fun connectAccessory(accessory: UsbAccessory) {
        val manager = usbManager ?: return
        synchronized(lock) {
            if (clientBridge != null && activeAccessory == accessory) return
        }
        if (!manager.hasPermission(accessory)) {
            val key = "${accessory.manufacturer}:${accessory.model}:${accessory.serial}"
            synchronized(lock) {
                if (permissionRequestedFor == key) return
                permissionRequestedFor = key
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permIntent = Intent(actionPermission).setPackage(appContext.packageName)
            val pi = PendingIntent.getBroadcast(appContext, 0, permIntent, flags)
            runCatching { manager.requestPermission(accessory, pi) }
            return
        }

        val pfd = runCatching { manager.openAccessory(accessory) }.getOrNull() ?: return
        val usbIn = AoaAccessoryInputStream(FileInputStream(pfd.fileDescriptor))
        val usbOut = ChunkedUsbOutputStream(FileOutputStream(pfd.fileDescriptor))

        val bridge = UsbTunnelClientBridge(
            usbIn = usbIn,
            usbOut = usbOut,
            onClosed = { disconnect() },
        )

        synchronized(lock) {
            closeLocked()
            activeAccessory = accessory
            fileDescriptor = pfd
            clientBridge = bridge
            permissionRequestedFor = null
        }
        bridge.start()
        onBridgeStateChanged(bridge.localPort)
    }

    private fun disconnect() {
        val hadBridge: Boolean
        synchronized(lock) {
            hadBridge = clientBridge != null || fileDescriptor != null
            closeLocked()
        }
        if (hadBridge) {
            onBridgeStateChanged(null)
        }
    }

    private fun closeLocked() {
        runCatching { clientBridge?.close() }
        clientBridge = null
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
        activeAccessory = null
        permissionRequestedFor = null
    }

    private fun extractAccessory(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

    /**
     * Android's `/dev/usb_accessory` kernel driver expects reads in 16,384-byte chunks;
     * smaller reads can fail or drop tail bytes.
     */
    private class AoaAccessoryInputStream(
        private val delegate: FileInputStream,
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
            val n = delegate.read(packetBuf, 0, packetBuf.size)
            if (n <= 0) return false
            pos = 0
            limit = n
            return true
        }

        override fun close() {
            delegate.close()
        }
    }

    private class ChunkedUsbOutputStream(
        private val delegate: FileOutputStream,
    ) : OutputStream() {
        override fun write(b: Int) {
            delegate.write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val chunk = minOf(remaining, UsbAoaProtocol.MAX_USB_PACKET)
                delegate.write(b, offset, chunk)
                offset += chunk
                remaining -= chunk
            }
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
