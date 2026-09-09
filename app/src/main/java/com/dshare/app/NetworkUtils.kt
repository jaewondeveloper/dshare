package com.dshare.app

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** Finds the device's LAN IPv4 address (Wi-Fi/hotspot), ignoring loopback/VPN interfaces. */
    fun findLocalIPv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<String>()

        for (iface in interfaces.toList()) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            val name = iface.name.lowercase()
            if (name.contains("rmnet") || name.contains("tun") || name.contains("ppp")) continue

            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (name.contains("wlan") || name.contains("ap") || name.contains("swlan")) {
                        return ip
                    }
                    candidates.add(ip)
                }
            }
        }
        return candidates.firstOrNull()
    }
}
