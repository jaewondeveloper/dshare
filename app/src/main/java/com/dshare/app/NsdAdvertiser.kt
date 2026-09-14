package com.dshare.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Advertises this receiver on the LAN via NSD/mDNS so the DShare sender app can list
 *  it by name instead of requiring the user to type an address manually. */
class NsdAdvertiser(context: Context) {

    companion object {
        const val SERVICE_TYPE = "_dshare._tcp."
        const val TXT_REDIRECT_PORT = "redirectPort"
    }

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(deviceName: String, httpsPort: Int, redirectPort: Int) {
        unregister()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            port = httpsPort
            setAttribute(TXT_REDIRECT_PORT, redirectPort.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i("DShare", "NSD: registered as '${info.serviceName}'")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e("DShare", "NSD: registration failed, error=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i("DShare", "NSD: unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e("DShare", "NSD: registerService threw", e)
        }
    }

    fun unregister() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            // Already unregistered or never succeeded - fine to ignore.
        }
    }
}
