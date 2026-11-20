package com.example.server

import com.example.model.NetworkInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    private data class Candidate(
        val ip: String,
        val label: String,
        val priority: Int,
    )

    fun detectLocalNetwork(): NetworkInfo {
        val candidates = mutableListOf<Candidate>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                // Skip cellular, VPN, and dummy interfaces
                if (
                    name.startsWith("rmnet") ||
                    name.startsWith("ccmni") ||
                    name.startsWith("tun") ||
                    name.startsWith("tap") ||
                    name.startsWith("ppp") ||
                    name.startsWith("dummy")
                ) {
                    continue
                }

                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val (label, basePriority) = when {
                            name.startsWith("wlan") -> "Wi-Fi" to 100
                            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ->
                                "Hotspot" to 90
                            name.startsWith("eth") -> "Ethernet" to 80
                            name.startsWith("rndis") || name.startsWith("usb") -> "USB LAN" to 70
                            else -> "Local Network" to 50
                        }
                        val siteLocalBonus = if (addr.isSiteLocalAddress) 20 else 0
                        candidates.add(Candidate(ip, label, basePriority + siteLocalBonus))
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to offline state
        }

        val best = candidates.maxByOrNull { it.priority }
        return if (best != null) {
            NetworkInfo(
                ipAddress = best.ip,
                interfaceLabel = best.label,
                isConnected = true,
            )
        } else {
            NetworkInfo(
                ipAddress = null,
                interfaceLabel = "No Wi-Fi / LAN",
                isConnected = false,
            )
        }
    }
}
