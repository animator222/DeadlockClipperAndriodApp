package com.deadlockclipper.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.deadlockclipper.app.MainActivity
import com.deadlockclipper.app.R
import com.deadlockclipper.app.util.AppLog

/**
 * Keeps the app running while a scan, export or download is in progress (they can take many
 * minutes), shows its progress in a notification and keeps the CPU awake.
 */
class WorkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeadlockClipper:work")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = build(this, current.first, current.second)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(ID, notification, type)
            } else {
                startForeground(ID, notification)
            }
        } catch (e: Exception) {
            AppLog.w("service", "Could not start in the foreground: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits how long these may run; the work itself keeps going while the app is open.
        AppLog.w("service", "Foreground time limit reached")
        stopSelf()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "work"
        private var current = "Deadlock Clipper" to 0.0
        private var running = false
        private var lastUpdate = 0L

        private fun build(context: Context, title: String, fraction: Double): Notification {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scans and exports", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_work)
                .setContentTitle(title)
                .setContentText("${(fraction * 100).toInt()}%")
                .setProgress(1000, (fraction * 1000).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }

        fun start(context: Context, title: String) {
            current = title to 0.0
            running = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java))
            } catch (e: Exception) {
                AppLog.w("service", "Could not start background service: $e")
            }
        }

        fun update(context: Context, title: String, fraction: Double) {
            current = title to fraction
            val now = System.currentTimeMillis()
            if (!running || now - lastUpdate < 1000) return
            lastUpdate = now
            try {
                val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, build(context, title, fraction))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, WorkService::class.java))
        }
    }
}
