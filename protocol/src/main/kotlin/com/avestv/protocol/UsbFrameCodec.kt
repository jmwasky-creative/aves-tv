package com.avestv.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class UsbFrame(
    val type: Byte,
    val streamId: Int,
    val payload: ByteArray = EMPTY_PAYLOAD,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UsbFrame) return false
        return type == other.type && streamId == other.streamId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.toInt()
        result = 31 * result + streamId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}

/**
 * 12-byte header + payload frame codec for multiplexed TCP-over-USB streams:
 * `[0..1] magic ('A','V')`, `[2] version (1)`, `[3] type`, `[4..7] streamId`, `[8..11] length`, `[12..] payload`.
 * Total frame size stays within [UsbAoaProtocol.MAX_USB_PACKET] (16,384 bytes) so each frame fits a single bulk write.
 */
object UsbFrameCodec {
    const val HEADER_SIZE = 12
    const val MAX_PAYLOAD_BYTES = 16_000

    private const val MAGIC_0: Byte = 0x41 // 'A'
    private const val MAGIC_1: Byte = 0x56 // 'V'
    private const val PROTOCOL_VERSION: Byte = 0x01

    const val TYPE_OPEN: Byte = 1
    const val TYPE_DATA: Byte = 2
    const val TYPE_FIN: Byte = 3
    const val TYPE_RST: Byte = 4
    const val TYPE_PING: Byte = 5
    const val TYPE_PONG: Byte = 6

    fun writeFrame(output: OutputStream, frame: UsbFrame) {
        val len = frame.payload.size
        require(len <= MAX_PAYLOAD_BYTES) { "Frame payload $len exceeds $MAX_PAYLOAD_BYTES" }
        val packet = ByteArray(HEADER_SIZE + len)
        packet[0] = MAGIC_0
        packet[1] = MAGIC_1
        packet[2] = PROTOCOL_VERSION
        packet[3] = frame.type
        putInt(packet, 4, frame.streamId)
        putInt(packet, 8, len)
        if (len > 0) {
            System.arraycopy(frame.payload, 0, packet, HEADER_SIZE, len)
        }
        output.write(packet)
        output.flush()
    }

    fun readFrame(input: InputStream): UsbFrame? {
        val header = ByteArray(HEADER_SIZE)
        val first = input.read()
        if (first == -1) return null
        header[0] = first.toByte()
        readFully(input, header, 1, HEADER_SIZE - 1)

        if (header[0] != MAGIC_0 || header[1] != MAGIC_1) {
            throw IOException("Invalid USB frame magic")
        }
        if (header[2] != PROTOCOL_VERSION) {
            throw IOException("Unsupported USB frame version: ${header[2]}")
        }
        val type = header[3]
        val streamId = getInt(header, 4)
        val length = getInt(header, 8)
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw IOException("Invalid USB frame length: $length")
        }
        val payload = if (length == 0) {
            UsbFrame.EMPTY_PAYLOAD
        } else {
            ByteArray(length).also { readFully(input, it, 0, length) }
        }
        return UsbFrame(type = type, streamId = streamId, payload = payload)
    }

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buf, offset + read, length - read)
            if (n == -1) throw EOFException("Unexpected EOF reading USB frame")
            read += n
        }
    }

    private fun putInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun getInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
}
