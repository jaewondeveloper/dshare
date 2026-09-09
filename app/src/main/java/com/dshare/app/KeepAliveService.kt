package com.dshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the process alive at foreground priority while DShare is running, so
 * OEM battery managers (Samsung in particular) don't kill the embedded server
 * the moment the screen locks or the app leaves the foreground.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "dshare_keepalive"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DShare 서버",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "화면 공유 서버가 실행 중일 때 표시됩니다" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DShare 실행 중")
            .setContentText("같은 Wi-Fi에서 화면 공유 연결을 기다리고 있습니다")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
