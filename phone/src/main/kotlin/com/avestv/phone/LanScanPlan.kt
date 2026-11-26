package com.avestv.phone

import com.avestv.protocol.DiscoveryProtocol

/**
 * Decisions for the three-layer LAN scan that do not need Android network services.
 */
internal object LanScanPlan {
    fun shouldProbeCachedIp(ip: String?): Boolean =
        !ip.isNullOrBlank() && DiscoveryProtocol.isPrivateLanIpv4(ip)

    fun shouldScanWholeSubnet(forceFullSubnetScan: Boolean, discoveredCount: Int): Boolean =
        forceFullSubnetScan || discoveredCount == 0

    fun subnetCandidates(localIpv4s: List<String>, alreadyFoundIps: Set<String>): List<String> =
        localIpv4s
            .flatMap { DiscoveryProtocol.buildSubnetTargets(it) }
            .distinct()
            .filterNot { it in alreadyFoundIps }
}
