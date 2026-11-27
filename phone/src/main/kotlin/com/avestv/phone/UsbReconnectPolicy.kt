package com.avestv.phone

/**
 * Pure-Kotlin policy for USB disconnect cleanup, stale-loopback detection,
 * and USB reconnect retry scheduling without Android framework dependencies.
 */
internal object UsbReconnectPolicy {
    const val LOOPBACK_IP = "127.0.0.1"
    const val RETRY_INTERVAL_MS = 2_000L
    const val MAX_RETRY_ATTEMPTS = 10

    enum class UsbStateAction {
        NONE,
        START_RECONNECT,
        CHECK_ACCESSORIES_ONLY,
        HANDLE_DISCONNECT,
    }

    data class DisconnectResult(
        val remainingTvs: List<DiscoveredTv>,
        val selectedTv: DiscoveredTv?,
        val clearedLoopback: Boolean,
    )

    fun isLoopbackTv(tv: DiscoveredTv?): Boolean = tv?.ip == LOOPBACK_IP

    fun actionForUsbState(
        previousConnected: Boolean?,
        connected: Boolean,
        configured: Boolean,
        isRetrying: Boolean,
    ): UsbStateAction = when {
        !connected && previousConnected == true -> UsbStateAction.HANDLE_DISCONNECT
        !connected -> UsbStateAction.NONE
        previousConnected == false -> UsbStateAction.START_RECONNECT
        configured && !isRetrying -> UsbStateAction.START_RECONNECT
        configured && isRetrying -> UsbStateAction.CHECK_ACCESSORIES_ONLY
        previousConnected == null && !isRetrying -> UsbStateAction.START_RECONNECT
        else -> UsbStateAction.NONE
    }

    fun clearLoopbackOnDisconnect(
        discovered: List<DiscoveredTv>,
        selected: DiscoveredTv?,
    ): DisconnectResult {
        val remaining = discovered.filterNot { isLoopbackTv(it) }
        val nextSelected = if (isLoopbackTv(selected)) null else selected
        val clearedLoopback = remaining.size != discovered.size || isLoopbackTv(selected)
        return DisconnectResult(
            remainingTvs = remaining,
            selectedTv = nextSelected,
            clearedLoopback = clearedLoopback,
        )
    }

    fun isStaleLoopbackSelection(
        selected: DiscoveredTv?,
        usbProbeResults: List<DiscoveredTv>,
    ): Boolean = isLoopbackTv(selected) && usbProbeResults.none { isLoopbackTv(it) }

    fun shouldScheduleNextRetry(
        nextAttempt: Int,
        foundUsbTvs: List<DiscoveredTv>,
        usbPhysicallyConnected: Boolean,
        hasAoaBridge: Boolean,
        selectedTv: DiscoveredTv? = null,
    ): Boolean {
        if (foundUsbTvs.any { isLoopbackTv(it) }) return false
        if (!usbPhysicallyConnected && !hasAoaBridge) return false
        return nextAttempt < MAX_RETRY_ATTEMPTS
    }

    fun mergeDiscovered(
        existing: Map<String, DiscoveredTv>,
        incoming: List<DiscoveredTv>,
    ): LinkedHashMap<String, DiscoveredTv> {
        val merged = LinkedHashMap(existing)
        for (tv in incoming) {
            val prev = merged[tv.info.deviceId]
            if (LanScanPlan.shouldPreferOverExisting(prev, tv)) {
                merged[tv.info.deviceId] = tv
            }
        }
        val sorted = merged.values.sortedWith(
            compareBy<DiscoveredTv> { if (isLoopbackTv(it)) 0 else 1 }
                .thenBy { it.info.deviceName },
        )
        return LinkedHashMap<String, DiscoveredTv>(sorted.size).apply {
            for (tv in sorted) {
                put(tv.info.deviceId, tv)
            }
        }
    }

    fun resolveSelection(
        discovered: List<DiscoveredTv>,
        currentSelected: DiscoveredTv?,
        preferredDeviceId: String?,
    ): DiscoveredTv? {
        if (discovered.isEmpty()) return null
        val usbTvs = discovered.filter { isLoopbackTv(it) }
        if (usbTvs.isNotEmpty()) {
            if (currentSelected != null && isLoopbackTv(currentSelected)) {
                return usbTvs.firstOrNull {
                    it.info.deviceId == currentSelected.info.deviceId && it.port == currentSelected.port
                } ?: usbTvs.first()
            }
            return usbTvs.first()
        }
        if (currentSelected != null) {
            val matchingLan = discovered.firstOrNull { it.info.deviceId == currentSelected.info.deviceId }
            if (matchingLan != null) return matchingLan
        }
        return discovered.firstOrNull { it.info.deviceId == preferredDeviceId } ?: discovered.first()
    }
}
