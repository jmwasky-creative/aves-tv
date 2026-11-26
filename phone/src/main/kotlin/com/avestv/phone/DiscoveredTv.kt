package com.avestv.phone

import com.avestv.protocol.TvDeviceInfo

data class DiscoveredTv(
    val ip: String,
    val info: TvDeviceInfo,
    val discoverySource: String,
) {
    val baseUrl: String
        get() = "http://$ip:${info.httpPort}"
}
