package com.avestv.phone

import com.avestv.protocol.TvDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbReconnectPolicyTest {

    private fun info(id: String = "tv-1", name: String = "客厅电视", port: Int = 53319) = TvDeviceInfo(
        deviceId = id,
        deviceName = name,
        httpPort = port,
        freeBytes = 10_000L,
        albumCount = 1,
        totalPhotos = 2,
    )

    @Test
    fun unplugClearsLoopbackSelectionAndEntriesForBothAdbAndAoaWhileKeepingLan() {
        val adbUsb = DiscoveredTv("127.0.0.1", info("preview-tv", "本机预览电视"), "USB 隧道直连", port = 53319)
        val aoaUsb = DiscoveredTv("127.0.0.1", info("aoa-tv", "电视 AOA"), "USB 直连（AOA）", port = 45678)
        val lanTv = DiscoveredTv("192.168.1.25", info("lan-tv", "卧室电视"), "局域网广播发现", port = 53319)

        val adbCleared = UsbReconnectPolicy.clearLoopbackOnDisconnect(listOf(adbUsb, lanTv), adbUsb)
        assertTrue(adbCleared.clearedLoopback)
        assertNull(adbCleared.selectedTv)
        assertEquals(listOf(lanTv), adbCleared.remainingTvs)

        val aoaCleared = UsbReconnectPolicy.clearLoopbackOnDisconnect(listOf(aoaUsb), aoaUsb)
        assertTrue(aoaCleared.clearedLoopback)
        assertNull(aoaCleared.selectedTv)
        assertTrue(aoaCleared.remainingTvs.isEmpty())

        val lanOnly = UsbReconnectPolicy.clearLoopbackOnDisconnect(listOf(lanTv), lanTv)
        assertFalse(lanOnly.clearedLoopback)
        assertEquals(lanTv, lanOnly.selectedTv)
        assertEquals(listOf(lanTv), lanOnly.remainingTvs)
    }

    @Test
    fun staleLoopbackAndStateTransitionsTriggerReconnectRegardlessOfSelectedTv() {
        val adbUsb = DiscoveredTv("127.0.0.1", info("preview-tv"), "USB 隧道直连", port = 53319)
        val aoaUsb = DiscoveredTv("127.0.0.1", info("aoa-tv"), "USB 直连（AOA）", port = 45678)
        val lanTv = DiscoveredTv("192.168.1.25", info("lan-tv"), "局域网广播发现", port = 53319)

        assertTrue(UsbReconnectPolicy.isStaleLoopbackSelection(adbUsb, emptyList()))
        assertTrue(UsbReconnectPolicy.isStaleLoopbackSelection(aoaUsb, emptyList()))
        assertFalse(UsbReconnectPolicy.isStaleLoopbackSelection(adbUsb, listOf(adbUsb)))
        assertFalse(UsbReconnectPolicy.isStaleLoopbackSelection(lanTv, emptyList()))

        assertEquals(
            UsbReconnectPolicy.UsbStateAction.HANDLE_DISCONNECT,
            UsbReconnectPolicy.actionForUsbState(previousConnected = true, connected = false, configured = false, isRetrying = true),
        )
        assertEquals(
            UsbReconnectPolicy.UsbStateAction.START_RECONNECT,
            UsbReconnectPolicy.actionForUsbState(previousConnected = false, connected = true, configured = false, isRetrying = false),
        )
        assertEquals(
            UsbReconnectPolicy.UsbStateAction.START_RECONNECT,
            UsbReconnectPolicy.actionForUsbState(previousConnected = false, connected = true, configured = true, isRetrying = true),
        )
        assertEquals(
            UsbReconnectPolicy.UsbStateAction.START_RECONNECT,
            UsbReconnectPolicy.actionForUsbState(previousConnected = true, connected = true, configured = true, isRetrying = false),
        )
        assertEquals(
            UsbReconnectPolicy.UsbStateAction.CHECK_ACCESSORIES_ONLY,
            UsbReconnectPolicy.actionForUsbState(previousConnected = true, connected = true, configured = true, isRetrying = true),
        )
        assertEquals(
            UsbReconnectPolicy.UsbStateAction.NONE,
            UsbReconnectPolicy.actionForUsbState(previousConnected = null, connected = false, configured = false, isRetrying = false),
        )
    }

    @Test
    fun retriesContinueWithStaleOrLanSelectionUntilUsbRespondsOrTimesOut() {
        val staleUsb = DiscoveredTv("127.0.0.1", info("preview-tv"), "USB 隧道直连", port = 53319)
        val aoaUsb = DiscoveredTv("127.0.0.1", info("aoa-tv"), "USB 直连（AOA）", port = 45678)
        val lanTv = DiscoveredTv("192.168.1.25", info("lan-tv"), "局域网广播发现", port = 53319)

        // Stale selectedTv or healthy LAN selectedTv must NOT prevent retries
        assertTrue(
            UsbReconnectPolicy.shouldScheduleNextRetry(
                nextAttempt = 1,
                foundUsbTvs = emptyList(),
                usbPhysicallyConnected = true,
                hasAoaBridge = false,
                selectedTv = staleUsb,
            ),
        )
        assertTrue(
            UsbReconnectPolicy.shouldScheduleNextRetry(
                nextAttempt = 1,
                foundUsbTvs = emptyList(),
                usbPhysicallyConnected = true,
                hasAoaBridge = false,
                selectedTv = lanTv,
            ),
        )
        assertTrue(
            UsbReconnectPolicy.shouldScheduleNextRetry(
                nextAttempt = UsbReconnectPolicy.MAX_RETRY_ATTEMPTS - 1,
                foundUsbTvs = emptyList(),
                usbPhysicallyConnected = false,
                hasAoaBridge = true,
                selectedTv = null,
            ),
        )

        // Stops at timeout
        assertFalse(
            UsbReconnectPolicy.shouldScheduleNextRetry(
                nextAttempt = UsbReconnectPolicy.MAX_RETRY_ATTEMPTS,
                foundUsbTvs = emptyList(),
                usbPhysicallyConnected = true,
                hasAoaBridge = false,
                selectedTv = null,
            ),
        )
        // Stops when disconnected from both USB and AOA
        assertFalse(
            UsbReconnectPolicy.shouldScheduleNextRetry(
                nextAttempt = 1,
                foundUsbTvs = emptyList(),
                usbPhysicallyConnected = false,
                hasAoaBridge = false,
                selectedTv = null,
            ),
        )
        // Stops immediately when ADB or AOA loopback responds
        assertFalse(
            UsbReconnectPolicy.shouldScheduleNextRetry(
                nextAttempt = 1,
                foundUsbTvs = listOf(staleUsb),
                usbPhysicallyConnected = true,
                hasAoaBridge = false,
                selectedTv = null,
            ),
        )
        assertFalse(
            UsbReconnectPolicy.shouldScheduleNextRetry(
                nextAttempt = 1,
                foundUsbTvs = listOf(aoaUsb),
                usbPhysicallyConnected = true,
                hasAoaBridge = true,
                selectedTv = null,
            ),
        )
    }

    @Test
    fun mergeAndSelectionPreferUsbOverLanWhilePreservingLanWhenUsbAbsent() {
        val lanSameId = DiscoveredTv("192.168.1.20", info("tv-1", "客厅电视"), "局域网广播发现", port = 53319)
        val usbSameId = DiscoveredTv("127.0.0.1", info("tv-1", "客厅电视"), "USB 隧道直连", port = 53319)
        val lanOther = DiscoveredTv("192.168.1.30", info("tv-2", "卧室电视"), "局域网广播发现", port = 53319)
        val usbPreview = DiscoveredTv("127.0.0.1", info("preview-tv", "本机预览电视"), "USB 隧道直连", port = 53319)

        // Merging LAN then USB keeps USB for same deviceId; merging USB then LAN does not downgrade
        val lanThenUsb = UsbReconnectPolicy.mergeDiscovered(mapOf(lanSameId.info.deviceId to lanSameId), listOf(usbSameId))
        assertEquals("127.0.0.1", lanThenUsb["tv-1"]?.ip)

        val usbThenLan = UsbReconnectPolicy.mergeDiscovered(lanThenUsb, listOf(lanSameId))
        assertEquals("127.0.0.1", usbThenLan["tv-1"]?.ip)

        // USB is preferred even if a healthy LAN TV is already selected
        val mergedMixed = UsbReconnectPolicy.mergeDiscovered(mapOf(lanOther.info.deviceId to lanOther), listOf(usbPreview))
        val selectedOverLan = UsbReconnectPolicy.resolveSelection(
            discovered = mergedMixed.values.toList(),
            currentSelected = lanOther,
            preferredDeviceId = lanOther.info.deviceId,
        )
        assertEquals(usbPreview, selectedOverLan)

        // When USB is absent, existing LAN selection is preserved
        val lanSelected = UsbReconnectPolicy.resolveSelection(
            discovered = listOf(lanSameId, lanOther),
            currentSelected = lanOther,
            preferredDeviceId = lanSameId.info.deviceId,
        )
        assertEquals(lanOther, lanSelected)
    }
}
