package com.example.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
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

    fun detectLocalNetwork(context: Context? = null): NetworkInfo {
        val candidates = mutableListOf<Candidate>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
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
        }

        val best = candidates.maxByOrNull { it.priority }
        if (best == null) {
            return NetworkInfo(
                ipAddress = null,
                interfaceLabel = "No Wi-Fi / LAN",
                isConnected = false,
            )
        }

        val (linkSpeed, freq) = if (best.label == "Wi-Fi" && context != null) {
            readWifiRadioMetrics(context)
        } else {
            null to null
        }

        return NetworkInfo(
            ipAddress = best.ip,
            interfaceLabel = best.label,
            isConnected = true,
            linkSpeedMbps = linkSpeed,
            frequencyMhz = freq,
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifiRadioMetrics(context: Context): Pair<Int?, Int?> {
        return try {
            var wifiInfo: WifiInfo? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                wifiInfo = caps?.transportInfo as? WifiInfo
            }
            if (wifiInfo == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiInfo = wm?.connectionInfo
            }
            val speed = wifiInfo?.linkSpeed?.takeIf { it > 0 }
            val freq = wifiInfo?.frequency?.takeIf { it > 0 }
            speed to freq
        } catch (_: Exception) {
            null to null
        }
    }
}
