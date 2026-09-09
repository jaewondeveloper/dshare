package com.dshare.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import javax.net.ssl.KeyManagerFactory

class LocalShareServer(
    private val context: Context,
    ipAddress: String,
    private val listener: Listener
) : NanoWSD(0) {

    interface Listener {
        fun onClientJoined()
        fun onJoinRejected(reason: String)
        fun onOffer(sdp: String)
        fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onClientStopped()
        fun onClientDisconnected()
    }

    @Volatile
    var pairingCode: String = PairingCode.generate()
        private set

    @Volatile
    private var activeSocket: SignalingSocket? = null

    init {
        val keyStore = CertUtil.buildKeyStore(ipAddress)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CertUtil.KEYSTORE_PASSWORD)
        }
        makeSecure(NanoHTTPD.makeSSLSocketFactory(keyStore, kmf), null)
    }

    fun regenerateCode(): String {
        pairingCode = PairingCode.generate()
        activeSocket?.close(WebSocketFrame.CloseCode.NormalClosure, "code regenerated", false)
        activeSocket = null
        return pairingCode
    }

    fun sendAnswer(sdp: String) {
        activeSocket?.sendJson(JSONObject().put("type", "answer").put("sdp", sdp))
    }

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        activeSocket?.sendJson(
            JSONObject()
                .put("type", "ice")
                .put("sdpMid", sdpMid)
                .put("sdpMLineIndex", sdpMLineIndex)
                .put("candidate", candidate)
        )
    }

    fun sendBye() {
        activeSocket?.sendJson(JSONObject().put("type", "bye"))
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return SignalingSocket(handshake)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/").ifEmpty { "index.html" }
        val assetPath = "web/$path"
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            newFixedLengthResponse(
                Response.Status.OK,
                mimeTypeFor(path),
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun mimeTypeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private inner class SignalingSocket(handshake: IHTTPSession) : WebSocket(handshake) {

        private var joined = false

        fun sendJson(json: JSONObject) {
            try {
                send(json.toString())
            } catch (_: Exception) {
            }
        }

        override fun onOpen() {
            // Wait for the client's "join" message before accepting it as active.
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            if (activeSocket == this) {
                activeSocket = null
                listener.onClientDisconnected()
            }
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }

            when (json.optString("type")) {
                "join" -> handleJoin(json.optString("code"))
                "offer" -> if (joined) listener.onOffer(json.optString("sdp"))
                "ice" -> if (joined) listener.onRemoteIceCandidate(
                    json.optString("sdpMid", null),
                    json.optInt("sdpMLineIndex", 0),
                    json.optString("candidate")
                )
                "stop" -> if (joined) listener.onClientStopped()
            }
        }

        private fun handleJoin(code: String) {
            synchronized(this@LocalShareServer) {
                when {
                    code != pairingCode -> {
                        sendJson(JSONObject().put("type", "error").put("message", "코드가 올바르지 않습니다."))
                    }
                    activeSocket != null && activeSocket !== this -> {
                        sendJson(JSONObject().put("type", "error").put("message", "이미 다른 기기가 연결되어 있습니다."))
                    }
                    else -> {
                        joined = true
                        activeSocket = this
                        sendJson(JSONObject().put("type", "joined"))
                        listener.onClientJoined()
                    }
                }
            }
        }

        override fun onPong(pong: WebSocketFrame) {}

        override fun onException(exception: java.io.IOException) {
            if (activeSocket == this) {
                activeSocket = null
            }
        }
    }
}
