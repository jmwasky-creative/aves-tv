package com.avestv.protocol

import java.nio.charset.StandardCharsets

/**
 * Shared constants and helpers for Android Open Accessory (AOA 2.0) USB communication
 * between the Android TV receiver (USB Host) and the phone sender (USB Accessory).
 */
object UsbAoaProtocol {
    const val GOOGLE_VENDOR_ID = 0x18D1
    val ACCESSORY_PRODUCT_IDS: Set<Int> = setOf(0x2D00, 0x2D01, 0x2D04, 0x2D05)

    const val CMD_GET_PROTOCOL = 51
    const val CMD_SEND_STRING = 52
    const val CMD_START = 53

    const val STR_MANUFACTURER = 0
    const val STR_MODEL = 1
    const val STR_DESCRIPTION = 2
    const val STR_VERSION = 3
    const val STR_URI = 4
    const val STR_SERIAL = 5

    const val MANUFACTURER = "AvesTV"
    const val MODEL = "AvesTVReceiver"
    const val DESCRIPTION = "Aves TV Album USB Bridge"
    const val VERSION = "1.0"
    const val URI = "https://github.com/jmwasky-creative/aves-tv"
    const val SERIAL = "AVESTV-USB-01"

    const val MAX_USB_PACKET = 16_384

    fun isAccessoryDevice(vendorId: Int, productId: Int): Boolean =
        vendorId == GOOGLE_VENDOR_ID && productId in ACCESSORY_PRODUCT_IDS

    fun nullTerminatedUtf8(text: String): ByteArray {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        return ByteArray(utf8.size + 1).also { out ->
            System.arraycopy(utf8, 0, out, 0, utf8.size)
            out[utf8.size] = 0
        }
    }
}
