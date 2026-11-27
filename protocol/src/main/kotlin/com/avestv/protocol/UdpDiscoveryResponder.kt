package com.avestv.protocol

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens on UDP [port] for LAN discovery probes and replies directly (unicast)
 * to the sender with current [TvDeviceInfo].
 */
class UdpDiscoveryResponder(
    private val port: Int = DiscoveryProtocol.UDP_DISCOVERY_PORT,
    private val logStore: DebugLogStore? = null,
    private val infoProvider: () -> TvDeviceInfo,
) : Closeable {

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    val boundPort: Int
        get() = socket?.localPort ?: port

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val listenPort = this.port
        val ds = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(listenPort))
        }
        socket = ds
        logStore?.info("UDP", "UDP discovery responder listening on $listenPort")
        thread = Thread({
            val buf = ByteArray(1024)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    ds.receive(packet)
                    if (DiscoveryProtocol.isDiscoveryRequest(packet.data, packet.length)) {
                        val responseBytes = DiscoveryProtocol.encodeDiscoveryResponse(infoProvider())
                        val reply = DatagramPacket(
                            responseBytes,
                            responseBytes.size,
                            packet.address,
                            packet.port,
                        )
                        ds.send(reply)
                        logStore?.info("UDP", "Discovery probe from ${packet.address.hostAddress}:${packet.port} -> replied")
                    }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "avestv-udp-discovery").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        thread = null
    }
}
