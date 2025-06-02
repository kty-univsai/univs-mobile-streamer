package ai.univs.univsmobilestreamer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class StreamingService : Service() {

    private lateinit var webRTCManager: WebRTCManager



    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d("StreamingService", "✅ onCreate() 호출됨")
        createNotificationChannel()
        startForeground(1, buildNotification())

        webRTCManager = WebRTCManager(context = this, signalingUrl = "ws://192.168.0.36:7700")
        webRTCManager.start()


    }

    override fun onDestroy() {
        webRTCManager.stop()
        super.onDestroy()
    }


    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, "streaming_channel")
            .setContentTitle("WebRTC 스트리밍 중")
            .setContentText("카메라 영상이 서버로 전송되고 있습니다.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "streaming_channel", "Streaming", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "WebRTC Streaming Service" }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
