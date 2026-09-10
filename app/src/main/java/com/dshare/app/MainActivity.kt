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
    private var webRtc: WebRtcReceiver? = null
    private var addressUrl: String = ""

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
        val btnRegenerate = findViewById<View>(R.id.btnRegenerate)
        val btnStop = findViewById<View>(R.id.btnStop)

        listOf(btnCopy, btnRegenerate, btnStop).forEach { it.applyPressScale() }

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DShare address", addressUrl))
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }

        btnRegenerate.setOnClickListener {
            val newCode = server?.regenerateCode() ?: return@setOnClickListener
            codeText.text = newCode
        }

        btnStop.setOnClickListener {
            server?.sendBye()
            stopStreamingAndReturnToWaiting()
        }
    }

    private fun startServerAsync() {
        android.util.Log.i("DShare", "startServerAsync: spawning thread")
        thread {
            android.util.Log.i("DShare", "startServerAsync: thread running")
            try {
                val ip = NetworkUtils.findLocalIPv4(applicationContext) ?: "0.0.0.0"
                android.util.Log.i("DShare", "startServerAsync: resolved ip=$ip")
                val srv = LocalShareServer(applicationContext, ip, this)
                android.util.Log.i("DShare", "startServerAsync: LocalShareServer constructed")
                srv.start(30_000, false)
                android.util.Log.i("DShare", "startServerAsync: server started, port=${srv.listeningPort}")
                server = srv
                val url = "https://$ip:${srv.listeningPort}"
                addressUrl = url
                mainHandler.post {
                    addressText.text = url
                    codeText.text = srv.pairingCode
                    qrImage.setImageBitmap(generateQrBitmap(url))
                }
            } catch (e: Throwable) {
                android.util.Log.e("DShare", "Server start failed", e)
                mainHandler.post { Toast.makeText(this, "서버 시작 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

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
            statusText.text = "코드 확인됨 · 브라우저에서 화면 공유를 시작하세요"
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
        mainHandler.post { stopStreamingAndReturnToWaiting() }
    }

    override fun onClientDisconnected() {
        mainHandler.post { stopStreamingAndReturnToWaiting() }
    }

    // ---------------- WebRtcReceiver.Callbacks (called on main thread already) ----------------

    override fun onLocalIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        server?.sendIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onAnswerCreated(sdp: String) {
        server?.sendAnswer(sdp)
    }

    override fun onFirstFrameRendered() {
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
        streamingContainer.visibility = View.GONE
        starfield.setWarpMultiplier(StarfieldView.WARP_MULTIPLIER, 900)
    }

    private fun showSuccessThenStream() {
        ConnectAnimator.crossFade(connectingOverlay, successOverlay)
        successCheck.post {
            ConnectAnimator.playJellyCheck(successCheck, successText) {
                mainHandler.postDelayed({
                    streamingContainer.alpha = 0f
                    streamingContainer.visibility = View.VISIBLE
                    streamingContainer.animate().alpha(1f).setDuration(320).withEndAction {
                        successOverlay.visibility = View.GONE
                    }.start()
                    statusDot.setBackgroundResource(R.drawable.shape_status_dot)
                    statusDot.background.setTint(getColorCompat(R.color.status_live))
                    statusText.text = getString(R.string.status_live)
                    starfield.setWarpMultiplier(StarfieldView.CONNECTED_MULTIPLIER, 1200)
                }, 1000)
            }
        }
    }

    private fun stopStreamingAndReturnToWaiting() {
        starfield.setWarpMultiplier(StarfieldView.IDLE_MULTIPLIER, 800)
        webRtc?.close()
        connectingOverlay.visibility = View.GONE
        successOverlay.visibility = View.GONE
        streamingContainer.visibility = View.GONE
        waitingScroll.visibility = View.VISIBLE
        waitingScroll.alpha = 0f
        waitingScroll.animate().alpha(1f).setDuration(280).start()
        statusDot.background.setTint(getColorCompat(R.color.status_waiting))
        statusText.text = getString(R.string.status_waiting)
    }

    private fun getColorCompat(resId: Int) = androidx.core.content.ContextCompat.getColor(this, resId)

    override fun onDestroy() {
        super.onDestroy()
        webRtc?.release()
        server?.stop()
        stopService(Intent(this, KeepAliveService::class.java))
    }
}
