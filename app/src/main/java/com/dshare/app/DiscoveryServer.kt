package com.dshare.app

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Tiny, fixed-port HTTP endpoint whose only job is answering "is DShare here, and what's
 * the real address?" for an externally-hosted landing page (e.g. dshare.com) trying to
 * auto-redirect a visitor to whichever DShare receiver is on their current LAN. Unlike
 * LocalShareServer's HTTPS port (which is per-device and can change), this always tries
 * the same well-known port so an outside page can probe likely LAN IPs for it directly,
 * without needing to already know the real port.
 *
 * A collision on this fixed port (another app already using it) is non-fatal - the
 * discovery feature just silently doesn't work on that device; QR/manual address entry
 * still work exactly as before.
 */
class DiscoveryServer(
    private val redirectUrlProvider: () -> String?,
    private val pairingCodeProvider: () -> String?,
    private val deviceNameProvider: () -> String?
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 47990
    }

    override fun serve(session: IHTTPSession): Response {
        val url = redirectUrlProvider()
        val response = if (url == null) {
            newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                JSONObject().put("app", "dshare").put("ready", false).toString()
            )
        } else {
            val json = JSONObject()
                .put("app", "dshare")
                .put("ready", true)
                .put("url", url)
                .put("code", pairingCodeProvider())
                .put("name", deviceNameProvider())
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }
        // The probing page runs on a different origin (the hosted landing page), and
        // needs to actually read this JSON body (not just detect reachability), so a
        // real CORS allow is required rather than relying on no-cors/opaque responses.
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-store")
        return response
    }
}
