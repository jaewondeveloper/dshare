package com.dshare.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Finds this device's IPv4 address on its currently active Wi-Fi connection.
     *
     * Asks ConnectivityManager for the network actually carrying TRANSPORT_WIFI and
     * reads its address directly, instead of guessing by interface name — a device can
     * have a leftover Wi-Fi-hotspot/AP interface (e.g. "ap0") sitting on a stale address
     * like 192.168.43.1 even while its real Wi-Fi (wlan0) is on a completely different
     * subnet, which a name-based heuristic can pick by mistake.
     */
    fun findLocalIPv4(context: Context): String? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val linkProperties = cm.getLinkProperties(network) ?: continue
                for (linkAddress in linkProperties.linkAddresses) {
                    val addr = linkAddress.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        }
        return fallbackInterfaceScan()
    }

    /** Best-effort fallback if ConnectivityManager reports no Wi-Fi transport network. */
    private fun fallbackInterfaceScan(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<String>()

        for (iface in interfaces.toList()) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            val name = iface.name.lowercase()
            if (name.contains("rmnet") || name.contains("tun") || name.contains("ppp")) continue

            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (name == "wlan0") return ip
                    candidates.add(ip)
                }
            }
        }
        return candidates.firstOrNull()
    }
}
