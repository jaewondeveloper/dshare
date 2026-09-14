package com.dshare.sender

/**
 * Holds the one validated [SignalingClient] connection across the MainActivity -> ScreenShareService
 * handoff. The receiver only allows a single active socket per session, so the connection opened
 * (and joined) while the user was entering the pairing code must be the SAME connection the service
 * later sends the offer on - reconnecting from scratch races the receiver's cleanup of the first
 * socket and gets rejected as "another device is already connected".
 */
object ActiveSession {
    var client: SignalingClient? = null
    var host: String? = null
    var port: Int = 0
    var code: String? = null
    var deviceName: String? = null

    fun clear() {
        client = null
        host = null
        port = 0
        code = null
        deviceName = null
    }
}
