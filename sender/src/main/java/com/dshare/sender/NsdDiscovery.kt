package com.dshare.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

data class DiscoveredDevice(val name: String, val host: String, val port: Int)

/** Finds DShare receivers on the LAN via NSD/mDNS instead of requiring a typed address. */
class NsdDiscovery(
    context: Context,
    private val onDevicesChanged: (List<DiscoveredDevice>) -> Unit
) {
    companion object {
        const val SERVICE_TYPE = "_dshare._tcp."
        private const val TAG = "DShareSender"
    }

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val devices = linkedMapOf<String, DiscoveredDevice>()

    fun start() {
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "NSD discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                devices.remove(service.serviceName)
                onDevicesChanged(devices.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices threw", e)
        }
    }

    private fun resolve(service: NsdServiceInfo) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            @Suppress("DEPRECATION")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                devices[serviceInfo.serviceName] = DiscoveredDevice(serviceInfo.serviceName, host, serviceInfo.port)
                onDevicesChanged(devices.values.toList())
            }
        })
    }

    fun stop() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            // Not currently discovering - fine to ignore.
        }
    }
}
