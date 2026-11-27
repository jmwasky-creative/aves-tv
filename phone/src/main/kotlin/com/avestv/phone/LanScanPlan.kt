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

    fun prioritizeInterfaces(entries: List<Pair<String, String>>): List<String> =
        entries
            .sortedBy { (iface, _) ->
                val lower = iface.lowercase()
                when {
                    lower.startsWith("usb") || lower.startsWith("rndis") || lower.startsWith("ncm") -> 0
                    else -> 1
                }
            }
            .map { it.second }
            .distinct()

    fun shouldPreferOverExisting(existing: DiscoveredTv?, incoming: DiscoveredTv): Boolean {
        if (existing == null) return true
        if (existing.ip == "127.0.0.1" && incoming.ip != "127.0.0.1") return false
        return true
    }
}
