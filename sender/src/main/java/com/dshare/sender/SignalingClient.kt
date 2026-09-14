package com.dshare.sender

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Talks the same join/offer/ice/stop JSON-over-WebSocket protocol the browser sender
 * page uses, so the existing receiver side needs no changes to support this native app.
 *
 * The receiver's cert is a fresh self-signed one generated per app launch - there's no
 * stable identity to pin, and there's no browser UI here to let a human click through a
 * warning. Trusting it outright is the LAN equivalent of that "proceed anyway" click:
 * the pairing code is still the actual gate that decides who gets to connect.
 */
class SignalingClient(
    private val host: String,
    private val port: Int,
    private var listener: Listener
) {
    fun setListener(newListener: Listener) {
        listener = newListener
    }

    interface Listener {
        fun onJoined()
        fun onJoinError(message: String)
        fun onAnswer(sdp: String)
        fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
        fun onBye()
        fun onSocketClosed()
    }

    private var webSocket: WebSocket? = null
    private val client: OkHttpClient = buildTrustAllClient()

    private fun buildTrustAllClient(): OkHttpClient {
        val trustAllCerts = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllCerts), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived WebSocket, no read timeout
            .build()
    }

    fun connectAndJoin(code: String) {
        val request = Request.Builder().url("wss://$host:$port/ws").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.i("DShareSender", "signaling socket open, sending join")
                webSocket.send(JSONObject().put("type", "join").put("code", code).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.i("DShareSender", "signaling socket closed: code=$code reason=$reason")
                listener.onSocketClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("DShareSender", "signaling socket failure", t)
                listener.onSocketClosed()
            }
        })
    }

    private fun handleMessage(text: String) {
        android.util.Log.i("DShareSender", "signaling <- $text")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (json.optString("type")) {
            "joined" -> listener.onJoined()
            "error" -> listener.onJoinError(json.optString("message"))
            "answer" -> listener.onAnswer(json.optString("sdp"))
            "ice" -> listener.onRemoteIce(
                if (json.isNull("sdpMid")) null else json.optString("sdpMid"),
                json.optInt("sdpMLineIndex", 0),
                json.optString("candidate")
            )
            "bye" -> listener.onBye()
        }
    }

    fun sendOffer(sdp: String) {
        val ok = webSocket?.send(JSONObject().put("type", "offer").put("sdp", sdp).toString())
        android.util.Log.i("DShareSender", "sendOffer -> sent=$ok (webSocket null=${webSocket == null})")
    }

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val ok = webSocket?.send(
            JSONObject()
                .put("type", "ice")
                .put("sdpMid", sdpMid)
                .put("sdpMLineIndex", sdpMLineIndex)
                .put("candidate", candidate)
                .toString()
        )
        android.util.Log.i("DShareSender", "sendIceCandidate -> sent=$ok (webSocket null=${webSocket == null})")
    }

    fun sendStop() {
        webSocket?.send(JSONObject().put("type", "stop").toString())
    }

    fun close() {
        webSocket?.close(1000, "client closing")
        webSocket = null
    }
}
