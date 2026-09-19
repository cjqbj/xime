package com.kingzcheung.xime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.R

class BackgroundCaptureService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val useWakeLock = intent?.getBooleanExtra(EXTRA_WAKE_LOCK, false) == true
        startAsForeground()
        if (useWakeLock) acquireWakeLock() else releaseWakeLock()
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("后台相机、麦克风和 RPC 服务正在运行")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:BackgroundCapture"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台采集服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用户开启后保持 RPC、相机和麦克风服务运行"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "background_capture"
        private const val NOTIFICATION_ID = 1144
        private const val ACTION_START = "com.kingzcheung.xime.action.START_BACKGROUND_CAPTURE"
        private const val ACTION_STOP = "com.kingzcheung.xime.action.STOP_BACKGROUND_CAPTURE"
        private const val EXTRA_WAKE_LOCK = "wake_lock"

        fun setEnabled(context: Context, enabled: Boolean, wakeLock: Boolean) {
            val intent = Intent(context, BackgroundCaptureService::class.java).apply {
                action = if (enabled) ACTION_START else ACTION_STOP
                putExtra(EXTRA_WAKE_LOCK, wakeLock)
            }
            if (enabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
