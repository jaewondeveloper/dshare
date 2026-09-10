package com.dshare.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.dshare.app.util.ConnectAnimator
import com.dshare.app.util.applyPressScale
import com.dshare.app.view.StarfieldView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.webrtc.SurfaceViewRenderer
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), LocalShareServer.Listener, WebRtcReceiver.Callbacks {

    private lateinit var addressText: TextView
    private lateinit var codeText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var qrImage: ImageView
    private lateinit var codeLoadingSpinner: View
    private lateinit var qrLoadingSpinner: View

    private lateinit var waitingScroll: View
    private lateinit var connectingOverlay: View
    private lateinit var successOverlay: View
    private lateinit var streamingContainer: View
    private lateinit var successCheck: View
    private lateinit var successText: View
    private lateinit var surfaceRenderer: SurfaceViewRenderer
    private lateinit var starfield: StarfieldView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: LocalShareServer? = null
    private var redirectServer: RedirectServer? = null
    private var webRtc: WebRtcReceiver? = null
    private var addressUrl: String = ""
    private var clientJoined = false
    private lateinit var btnRegenerate: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("DShare", "onCreate: start")
        setContentView(R.layout.activity_main)
        android.util.Log.i("DShare", "onCreate: setContentView done")
        bindViews()
        android.util.Log.i("DShare", "onCreate: bindViews done")
        wireButtons()
        android.util.Log.i("DShare", "onCreate: wireButtons done")
        startKeepAliveService()
        android.util.Log.i("DShare", "onCreate: keepalive service started")
        startServerAsync()
        android.util.Log.i("DShare", "onCreate: startServerAsync() called (thread launch requested)")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindViews() {
        addressText = findViewById(R.id.addressText)
        codeText = findViewById(R.id.codeText)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        qrImage = findViewById(R.id.qrImage)
        codeLoadingSpinner = findViewById(R.id.codeLoadingSpinner)
        qrLoadingSpinner = findViewById(R.id.qrLoadingSpinner)
        waitingScroll = findViewById(R.id.waitingScroll)
        connectingOverlay = findViewById(R.id.connectingOverlay)
        successOverlay = findViewById(R.id.successOverlay)
        streamingContainer = findViewById(R.id.streamingContainer)
        successCheck = findViewById(R.id.successCheck)
        successText = findViewById(R.id.successText)
        surfaceRenderer = findViewById(R.id.surfaceRenderer)
        starfield = findViewById(R.id.starfield)
    }

    private fun wireButtons() {
        val btnCopy = findViewById<View>(R.id.btnCopy)
        btnRegenerate = findViewById(R.id.btnRegenerate)

        listOf(btnCopy, btnRegenerate).forEach { it.applyPressScale() }

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DShare address", addressUrl))
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }

        btnRegenerate.setOnClickListener {
            val newCode = server?.regenerateCode() ?: return@setOnClickListener
            codeText.text = newCode
            qrImage.setImageBitmap(generateQrBitmap(buildQrUrl(addressUrl, newCode)))
            AppPrefs.saveCode(applicationContext, newCode)
        }
    }

    private fun startServerAsync() {
        android.util.Log.i("DShare", "startServerAsync: spawning thread")
        thread {
            android.util.Log.i("DShare", "startServerAsync: thread running")
            try {
                val ip = NetworkUtils.findLocalIPv4(applicationContext) ?: "0.0.0.0"
                android.util.Log.i("DShare", "startServerAsync: resolved ip=$ip")

                val savedCode = AppPrefs.getSavedCode(applicationContext)
                val savedHttpsPort = AppPrefs.getSavedHttpsPort(applicationContext)
                val savedRedirectPort = AppPrefs.getSavedRedirectPort(applicationContext)

                val srv = startHttpsServerWithFallback(ip, savedHttpsPort, savedCode)
                android.util.Log.i("DShare", "startServerAsync: server started, port=${srv.listeningPort}")
                server = srv

                // Plain-HTTP listener that just 302s to the HTTPS server: the HTTPS
                // socket can't itself answer a plain HTTP request (TLS owns the whole
                // socket), so typing the address without "https://" - which browsers
                // resolve to http:// by default - needs this to land anywhere at all.
                val redirect = startRedirectServerWithFallback(srv.listeningPort, savedRedirectPort)
                redirectServer = redirect
                android.util.Log.i("DShare", "startServerAsync: redirect server started, port=${redirect.listeningPort}")

                AppPrefs.saveCode(applicationContext, srv.pairingCode)
                AppPrefs.savePorts(applicationContext, srv.listeningPort, redirect.listeningPort)

                val url = "http://$ip:${redirect.listeningPort}"
                addressUrl = url
                mainHandler.post {
                    addressText.text = url
                    codeText.text = srv.pairingCode
                    qrImage.setImageBitmap(generateQrBitmap(buildQrUrl(url, srv.pairingCode)))
                    codeText.visibility = View.VISIBLE
                    qrImage.visibility = View.VISIBLE
                    codeLoadingSpinner.visibility = View.GONE
                    qrLoadingSpinner.visibility = View.GONE
                }
            } catch (e: Throwable) {
                android.util.Log.e("DShare", "Server start failed", e)
                mainHandler.post { Toast.makeText(this, "서버 시작 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Tries to reuse the port this device used last time (so a bookmarked/QR link
     *  keeps working across app restarts); falls back to a fresh ephemeral port if
     *  that one is no longer available. */
    private fun startHttpsServerWithFallback(ip: String, preferredPort: Int, savedCode: String?): LocalShareServer {
        if (preferredPort != 0) {
            try {
                val srv = LocalShareServer(applicationContext, ip, this, preferredPort, savedCode)
                srv.start(30_000, false)
                return srv
            } catch (e: Exception) {
                android.util.Log.i("DShare", "Preferred HTTPS port $preferredPort unavailable, falling back: ${e.message}")
            }
        }
        val srv = LocalShareServer(applicationContext, ip, this, 0, savedCode)
        srv.start(30_000, false)
        return srv
    }

    private fun startRedirectServerWithFallback(httpsPort: Int, preferredPort: Int): RedirectServer {
        if (preferredPort != 0) {
            try {
                val redirect = RedirectServer(httpsPort, preferredPort)
                redirect.start(30_000, false)
                return redirect
            } catch (e: Exception) {
                android.util.Log.i("DShare", "Preferred redirect port $preferredPort unavailable, falling back: ${e.message}")
            }
        }
        val redirect = RedirectServer(httpsPort, 0)
        redirect.start(30_000, false)
        return redirect
    }

    private fun buildQrUrl(baseUrl: String, code: String) = "$baseUrl/?code=$code"

    private fun generateQrBitmap(text: String): Bitmap {
        val size = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    // ---------------- LocalShareServer.Listener (called on server worker thread) ----------------

    override fun onClientJoined() {
        mainHandler.post {
            clientJoined = true
            btnRegenerate.isEnabled = false
            btnRegenerate.alpha = 0.5f
            statusDot.background.setTint(getColorCompat(R.color.status_connecting))
            statusText.text = getString(R.string.status_joined)
        }
    }

    override fun onJoinRejected(reason: String) {}

    override fun onOffer(sdp: String) {
        mainHandler.post {
            showConnecting()
            ensureWebRtc().handleOffer(sdp)
        }
    }

    override fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        mainHandler.post {
            webRtc?.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
        }
    }

    override fun onClientStopped() {
        // Still joined (same websocket session) - just stopped sharing video, so leave
        // the regenerate button disabled and the "connected" status as-is.
        mainHandler.post { stopStreamingAndReturnToWaiting() }
    }

    override fun onClientDisconnected() {
        mainHandler.post {
            clientJoined = false
            btnRegenerate.isEnabled = true
            btnRegenerate.alpha = 1f
            stopStreamingAndReturnToWaiting()
        }
    }

    // ---------------- WebRtcReceiver.Callbacks (called on main thread already) ----------------

    override fun onLocalIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        server?.sendIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onAnswerCreated(sdp: String) {
        server?.sendAnswer(sdp)
    }

    override fun onRemoteConnected() {
        mainHandler.post { showSuccessThenStream() }
    }

    override fun onConnectionClosed() {
        mainHandler.post { stopStreamingAndReturnToWaiting() }
    }

    // ---------------- UI state transitions ----------------

    private fun ensureWebRtc(): WebRtcReceiver {
        return webRtc ?: WebRtcReceiver(applicationContext, surfaceRenderer, this).also { webRtc = it }
    }

    private fun showConnecting() {
        statusText.text = getString(R.string.status_connecting)
        waitingScroll.visibility = View.GONE
        connectingOverlay.alpha = 1f
        connectingOverlay.visibility = View.VISIBLE
        successOverlay.visibility = View.GONE
        streamingContainer.visibility = View.INVISIBLE
        starfield.setWarpMultiplier(StarfieldView.WARP_MULTIPLIER, 1300)
    }

    private fun showSuccessThenStream() {
        ConnectAnimator.crossFade(connectingOverlay, successOverlay, duration = 180)
        successCheck.post {
            ConnectAnimator.playJellyCheck(successCheck, successText) {
                // The peer connection already reached CONNECTED and frames are decoding; only
                // hold briefly so the checkmark registers, then reveal it immediately -
                // no artificial delay on top of an already-live stream.
                mainHandler.postDelayed({
                    streamingContainer.alpha = 0f
                    streamingContainer.scaleX = 0.82f
                    streamingContainer.scaleY = 0.82f
                    streamingContainer.visibility = View.VISIBLE
                    streamingContainer.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                        .setDuration(550)
                        .withEndAction {
                            successOverlay.visibility = View.GONE
                            // Fully hidden behind the video now - stop redrawing it so it
                            // doesn't compete with the decoder/renderer for CPU/GPU.
                            starfield.pauseAnimation()
                        }.start()
                    statusDot.setBackgroundResource(R.drawable.shape_status_dot)
                    statusDot.background.setTint(getColorCompat(R.color.status_live))
                    statusText.text = getString(R.string.status_live)
                }, 150)
            }
        }
    }

    private fun stopStreamingAndReturnToWaiting() {
        starfield.resumeAnimation()
        starfield.setWarpMultiplier(StarfieldView.IDLE_MULTIPLIER, 800)
        webRtc?.close()
        streamingContainer.animate().cancel()
        streamingContainer.scaleX = 1f
        streamingContainer.scaleY = 1f
        connectingOverlay.visibility = View.GONE
        successOverlay.visibility = View.GONE
        streamingContainer.visibility = View.INVISIBLE
        waitingScroll.visibility = View.VISIBLE
        waitingScroll.alpha = 0f
        waitingScroll.animate().alpha(1f).setDuration(280).start()
        if (clientJoined) {
            statusDot.background.setTint(getColorCompat(R.color.status_connecting))
            statusText.text = getString(R.string.status_joined)
        } else {
            statusDot.background.setTint(getColorCompat(R.color.status_waiting))
            statusText.text = getString(R.string.status_waiting)
        }
    }

    private fun getColorCompat(resId: Int) = androidx.core.content.ContextCompat.getColor(this, resId)

    override fun onDestroy() {
        super.onDestroy()
        webRtc?.release()
        server?.stop()
        redirectServer?.stop()
        stopService(Intent(this, KeepAliveService::class.java))
    }
}
