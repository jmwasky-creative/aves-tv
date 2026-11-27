package com.avestv.protocol

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private sealed interface QueueItem {
    class Data(val bytes: ByteArray) : QueueItem
    data object Fin : QueueItem
    data object Rst : QueueItem
}

private class TunnelSession(
    val streamId: Int,
) {
    val queue = LinkedBlockingQueue<QueueItem>()
    val localReadEof = AtomicBoolean(false)
    val remoteFin = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    @Volatile
    var socket: Socket? = null
}

/**
 * Shared multiplexed stream engine over a bidirectional USB byte stream.
 */
private class MultiplexTunnelCore(
    private val usbIn: InputStream,
    private val usbOut: OutputStream,
    private val threadPrefix: String,
    private val onRemoteOpen: (streamId: Int) -> Unit,
    private val onClosed: (() -> Unit)?,
) : Closeable {

    val running = AtomicBoolean(false)
    val sessions = ConcurrentHashMap<Int, TunnelSession>()
    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "$threadPrefix-worker").apply { isDaemon = true }
    }

    private val writeLock = Any()
    private val closeNotified = AtomicBoolean(false)
    private var readerThread: Thread? = null

    fun startReader() {
        if (!running.compareAndSet(false, true)) return
        readerThread = Thread({
            try {
                while (running.get()) {
                    val frame = UsbFrameCodec.readFrame(usbIn) ?: break
                    dispatchFrame(frame)
                }
            } catch (_: Exception) {
                // USB stream closed or reset
            } finally {
                close()
            }
        }, "$threadPrefix-usb-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun sendFrame(type: Byte, streamId: Int, payload: ByteArray = UsbFrame.EMPTY_PAYLOAD): Boolean {
        if (!running.get()) return false
        return try {
            synchronized(writeLock) {
                if (!running.get()) return false
                UsbFrameCodec.writeFrame(usbOut, UsbFrame(type, streamId, payload))
            }
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    fun attachAndPumpSocket(session: TunnelSession, socket: Socket) {
        if (session.closed.get() || !running.get()) {
            runCatching { socket.close() }
            return
        }
        session.socket = socket
        executor.execute { pumpSocketReads(session, socket) }
        executor.execute { pumpSocketWrites(session, socket) }
    }

    fun resetSession(streamId: Int, notifyRemote: Boolean) {
        val session = sessions.remove(streamId)
        if (session != null) {
            closeSessionLocally(session)
        }
        if (notifyRemote) {
            sendFrame(UsbFrameCodec.TYPE_RST, streamId)
        }
    }

    private fun dispatchFrame(frame: UsbFrame) {
        when (frame.type) {
            UsbFrameCodec.TYPE_OPEN -> onRemoteOpen(frame.streamId)
            UsbFrameCodec.TYPE_DATA -> sessions[frame.streamId]?.queue?.offer(QueueItem.Data(frame.payload))
            UsbFrameCodec.TYPE_FIN -> sessions[frame.streamId]?.queue?.offer(QueueItem.Fin)
            UsbFrameCodec.TYPE_RST -> resetSession(frame.streamId, notifyRemote = false)
            UsbFrameCodec.TYPE_PING -> sendFrame(UsbFrameCodec.TYPE_PONG, 0)
            UsbFrameCodec.TYPE_PONG -> Unit
        }
    }

    private fun pumpSocketReads(session: TunnelSession, socket: Socket) {
        val buf = ByteArray(UsbFrameCodec.MAX_PAYLOAD_BYTES)
        try {
            val input = socket.getInputStream()
            while (running.get() && !session.closed.get()) {
                val n = input.read(buf)
                if (n == -1) {
                    sendFrame(UsbFrameCodec.TYPE_FIN, session.streamId)
                    session.localReadEof.set(true)
                    checkBothDirectionsDone(session)
                    return
                }
                if (n > 0) {
                    val sent = sendFrame(UsbFrameCodec.TYPE_DATA, session.streamId, buf.copyOf(n))
                    if (!sent) return
                }
            }
        } catch (_: IOException) {
            if (!session.closed.get() && running.get()) {
                resetSession(session.streamId, notifyRemote = true)
            }
        }
    }

    private fun pumpSocketWrites(session: TunnelSession, socket: Socket) {
        try {
            val output = socket.getOutputStream()
            while (running.get() && !session.closed.get()) {
                when (val item = session.queue.take()) {
                    is QueueItem.Data -> {
                        output.write(item.bytes)
                        if (session.queue.isEmpty()) output.flush()
                    }
                    QueueItem.Fin -> {
                        output.flush()
                        runCatching { socket.shutdownOutput() }
                        session.remoteFin.set(true)
                        checkBothDirectionsDone(session)
                        return
                    }
                    QueueItem.Rst -> return
                }
            }
        } catch (_: InterruptedException) {
            // shutting down
        } catch (_: IOException) {
            if (!session.closed.get() && running.get()) {
                resetSession(session.streamId, notifyRemote = true)
            }
        }
    }

    private fun checkBothDirectionsDone(session: TunnelSession) {
        if (session.localReadEof.get() && session.remoteFin.get()) {
            sessions.remove(session.streamId)
            closeSessionLocally(session)
        }
    }

    private fun closeSessionLocally(session: TunnelSession) {
        if (!session.closed.compareAndSet(false, true)) return
        session.queue.clear()
        session.queue.offer(QueueItem.Rst)
        runCatching { session.socket?.close() }
        session.socket = null
    }

    override fun close() {
        running.set(false)
        val all = sessions.keys.toList()
        for (id in all) {
            sessions.remove(id)?.let { closeSessionLocally(it) }
        }
        runCatching { usbIn.close() }
        runCatching { usbOut.close() }
        executor.shutdownNow()
        if (closeNotified.compareAndSet(false, true)) {
            onClosed?.invoke()
        }
    }
}

/**
 * Phone-side local HTTP proxy that tunnels TCP connections to `127.0.0.1:[localPort]`
 * across a USB AOA stream to the TV's [UsbTunnelHostBridge].
 */
class UsbTunnelClientBridge(
    usbIn: InputStream,
    usbOut: OutputStream,
    bindPort: Int = 0,
    onClosed: (() -> Unit)? = null,
) : Closeable {

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", bindPort))
    }

    val localPort: Int
        get() = serverSocket.localPort

    private val nextStreamId = AtomicInteger(1)
    private var acceptThread: Thread? = null
    private var pingThread: Thread? = null

    private val core = MultiplexTunnelCore(
        usbIn = usbIn,
        usbOut = usbOut,
        threadPrefix = "avestv-usb-client",
        onRemoteOpen = { streamId -> resetUnknownOpen(streamId) },
        onClosed = {
            runCatching { serverSocket.close() }
            onClosed?.invoke()
        },
    )

    private fun resetUnknownOpen(streamId: Int) {
        core.sendFrame(UsbFrameCodec.TYPE_RST, streamId)
    }

    fun start() {
        core.startReader()
        acceptThread = Thread({
            while (core.running.get()) {
                try {
                    val socket = serverSocket.accept()
                    socket.soTimeout = 60_000
                    val streamId = nextStreamId.getAndIncrement()
                    val session = TunnelSession(streamId)
                    core.sessions[streamId] = session
                    if (!core.sendFrame(UsbFrameCodec.TYPE_OPEN, streamId)) {
                        core.resetSession(streamId, notifyRemote = false)
                        runCatching { socket.close() }
                        break
                    }
                    core.attachAndPumpSocket(session, socket)
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    if (!core.running.get()) break
                }
            }
        }, "avestv-usb-client-accept").apply {
            isDaemon = true
            start()
        }
        pingThread = Thread({
            while (core.running.get()) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!core.running.get()) break
                core.sendFrame(UsbFrameCodec.TYPE_PING, 0)
            }
        }, "avestv-usb-client-ping").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        pingThread?.interrupt()
        core.close()
    }
}

/**
 * TV-side tunnel endpoint that receives multiplexed streams from USB AOA
 * and forwards each stream to the local `TvHttpServer` at `[targetHost]:[targetPort]`.
 */
class UsbTunnelHostBridge(
    usbIn: InputStream,
    usbOut: OutputStream,
    private val targetPort: Int,
    private val targetHost: String = "127.0.0.1",
    onClosed: (() -> Unit)? = null,
) : Closeable {

    private lateinit var core: MultiplexTunnelCore

    init {
        core = MultiplexTunnelCore(
            usbIn = usbIn,
            usbOut = usbOut,
            threadPrefix = "avestv-usb-host",
            onRemoteOpen = { streamId -> handleOpen(streamId) },
            onClosed = onClosed,
        )
    }

    fun start() {
        core.startReader()
    }

    private fun handleOpen(streamId: Int) {
        val session = TunnelSession(streamId)
        core.sessions[streamId] = session
        core.executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(targetHost, targetPort), 3_000)
                socket.soTimeout = 60_000
                core.attachAndPumpSocket(session, socket)
            } catch (_: Exception) {
                core.resetSession(streamId, notifyRemote = true)
            }
        }
    }

    override fun close() {
        core.close()
    }
}
