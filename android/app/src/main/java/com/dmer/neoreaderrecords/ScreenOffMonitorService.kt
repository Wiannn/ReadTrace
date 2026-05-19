package com.dmer.neoreaderrecords

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
import androidx.core.app.NotificationCompat

class ScreenOffMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        AutoRefreshLog.i(this, "ScreenOffMonitorService.onCreate")
        ensureChannel()
        startForeground(17731, buildNotification())
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                AutoRefreshLog.i(this@ScreenOffMonitorService, "ScreenOffMonitorService receiver action=${intent?.action}")
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    AutoRefreshWorker.enqueue(this@ScreenOffMonitorService, "screen_off")
                } else if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                    // Pre-generate while screen is on/unlocked, so next lock can use latest wallpaper.
                    AutoRefreshWorker.enqueue(this@ScreenOffMonitorService, "screen_on_prewarm")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(r, filter)
        receiver = r
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AutoRefreshLog.i(this, "ScreenOffMonitorService.onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        AutoRefreshLog.i(this, "ScreenOffMonitorService.onDestroy")
        receiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val c = NotificationChannel(
            "neoreader_auto_refresh",
            "NeoReader 自动刷新",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "用于熄屏自动刷新监听（较耗电）"
            setShowBadge(false)
        }
        nm.createNotificationChannel(c)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "neoreader_auto_refresh")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("NeoReader 熄屏监听中")
            .setContentText("已启用熄屏自动刷新（较耗电）")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
