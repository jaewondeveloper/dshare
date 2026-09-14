package com.dshare.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Owns MediaProjection capture + the WebRTC sender PeerConnection as a foreground
 * service, independent of MainActivity's lifecycle - screen sharing has to keep running
 * while the user is looking at whatever app they're sharing, not this one.
 *
 * The MediaProjection lifecycle is the single most crash-prone part of this feature on
 * modern Android:
 *  - Android 14 (and Android 12+ more loosely) requires the app to already be a
 *    foreground service of type mediaProjection *before* the MediaProjection is
 *    obtained/used, or it throws SecurityException. So startForeground() is called
 *    first thing in onStartCommand(), before touching WebRtcSender/ScreenCapturerAndroid
 *    at all.
 *  - A MediaProjection.Callback must be registered (ScreenCapturerAndroid takes one in
 *    its constructor and wires it up) so a system-initiated stop (the "Stop sharing"
 *    chip in the status bar) is handled instead of leaving the capturer in a dead state
 *    that then crashes on the next frame callback.
 *  - Every teardown path (explicit stop, projection revoked by the system, connection
 *    failure) funnels through stopSelfAndCleanup() exactly once, guarded by
 *    isStopping, so a double-teardown race can't double-dispose native WebRTC objects.
 */
class ScreenShareService : Service(), WebRtcSender.Callbacks {

    companion object {
        const val ACTION_START = "com.dshare.sender.action.START"
        const val ACTION_STOP = "com.dshare.sender.action.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_CODE = "code"

        private const val CHANNEL_ID = "dshare_sender_sharing"
        private const val NOTIFICATION_ID = 42

        var listener: StateListener? = null
    }

    interface StateListener {
        fun onSharingStarted()
        fun onSharingStopped(errorMessage: String?)
    }

    private var webRtcSender: WebRtcSender? = null
    private var signalingClient: SignalingClient? = null
    private var isStopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: getString(R.string.app_name)
        val code = intent?.getStringExtra(EXTRA_CODE)

        if (resultCode != android.app.Activity.RESULT_OK ||
            resultData == null || host.isNullOrEmpty() || port == 0 || code.isNullOrEmpty()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(deviceName)

        try {
            beginCapture(resultCode, resultData, host, port, code)
        } catch (t: Throwable) {
            // Never let a MediaProjection/WebRTC setup failure crash the process -
            // report it and shut down cleanly instead.
            android.util.Log.e("DShareSender", "beginCapture failed", t)
            stopSharing("화면 공유를 시작하지 못했습니다: ${t.message}")
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(deviceName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, deviceName))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_share_stop), stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCapture(resultCode: Int, resultData: Intent, host: String, port: Int, code: String) {
        val sender = WebRtcSender(applicationContext, this)
        webRtcSender = sender

        val listener = object : SignalingClient.Listener {
            override fun onJoined() {
                // Only relevant when this service had to open a fresh connection below
                // (the reused-session path is already past "joined" by definition).
            }

            override fun onJoinError(message: String) {
                stopSharing(message)
            }

            override fun onAnswer(sdp: String) {
                webRtcSender?.setRemoteAnswer(sdp)
            }

            override fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
                webRtcSender?.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }

            override fun onBye() {
                stopSharing()
            }

            override fun onSocketClosed() {
                stopSharing()
            }
        }

        // Reuse the already-joined connection handed off by MainActivity's pairing-code
        // validation whenever it matches this request, instead of opening a second signaling
        // session for the same code - the receiver only accepts one at a time, and racing a
        // fresh join against the still-closing validation socket is what used to break this.
        val reused = ActiveSession.client
        val signaling: SignalingClient
        val needsJoin: Boolean
        if (reused != null && ActiveSession.host == host && ActiveSession.port == port && ActiveSession.code == code) {
            signaling = reused
            signaling.setListener(listener)
            needsJoin = false
            android.util.Log.i("DShareSender", "beginCapture: reusing already-joined signaling connection")
        } else {
            signaling = SignalingClient(host, port, listener)
            needsJoin = true
            android.util.Log.i("DShareSender", "beginCapture: opening a fresh signaling connection (no matching ActiveSession)")
        }
        ActiveSession.clear()
        signalingClient = signaling

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                // The system (or the user via the "Stop sharing" chip) revoked the
                // projection - must not touch it again after this.
                stopSharing()
            }
        }

        // MediaProjection is obtained internally by ScreenCapturerAndroid right here,
        // which is why startForegroundCompat() above had to run first.
        sender.startCapture(resultData, projectionCallback)

        if (needsJoin) {
            signaling.connectAndJoin(code)
        }
    }

    override fun onLocalIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        signalingClient?.sendIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onOfferCreated(sdp: String) {
        signalingClient?.sendOffer(sdp)
    }

    override fun onConnected() {
        listener?.onSharingStarted()
    }

    override fun onCaptureStopped() {
        stopSharing()
    }

    override fun onError(message: String) {
        stopSharing(message)
    }

    private fun stopSharing(errorMessage: String? = null) {
        if (isStopping) return
        isStopping = true
        signalingClient?.sendStop()
        signalingClient?.close()
        signalingClient = null
        webRtcSender?.release()
        webRtcSender = null
        listener?.onSharingStopped(errorMessage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isStopping) {
            signalingClient?.close()
            webRtcSender?.release()
        }
    }
}
