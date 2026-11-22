package app.freeclip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive (foreground service + a notification) while clips are downloaded, analysed or exported,
 * so work continues when the app is minimised or the screen is off. The CPU wake lock is held only while it runs.
 */
class ClipService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, build(this, "ClipForge AI", "Working on your clips...", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wl == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipforge:job").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // safety timeout, released earlier in onDestroy
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wl = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CH = "clipforge_jobs"
        private const val CH_DONE = "clipforge_done"
        private const val NOTIF_ID = 4201
        private const val DONE_ID = 4202
        private var lastNotify = 0L

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH, "Clipping in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Clips ready", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun build(ctx: Context, title: String, text: String, pct: Int): Notification =
            NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct.coerceIn(0, 100), pct < 0)
                .build()

        /** True only while the service should be showing its notification; late progress updates are ignored after that. */
        @Volatile
        var running = false
        @Volatile
        private var instance: ClipService? = null
        private val handler = Handler(Looper.getMainLooper())

        fun start(ctx: Context) {
            running = true
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ClipService::class.java))
            } catch (_: Exception) {
            }
        }

        /** Stops the service and removes its notification. Safe to call right after start (waits until it is really up). */
        fun stop(ctx: Context) {
            running = false
            val inst = instance
            if (inst != null) {
                try { inst.stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                try { inst.stopSelf() } catch (_: Exception) {}
            } else {
                // Not created yet: try again shortly so it does not start and stay forever.
                handler.postDelayed({
                    if (!running) {
                        instance?.let {
                            try { it.stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                            try { it.stopSelf() } catch (_: Exception) {}
                        }
                        try { ctx.stopService(Intent(ctx, ClipService::class.java)) } catch (_: Exception) {}
                    }
                }, 1500)
            }
            cancelProgress(ctx)
        }

        fun cancelProgress(ctx: Context) {
            try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
        }

        /** Removes both notifications (used when the app starts or is opened: nothing stale may stay). */
        fun clearAll(ctx: Context) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!running) nm.cancel(NOTIF_ID)
                nm.cancel(DONE_ID)
            } catch (_: Exception) {
            }
        }

        /** Updates the ongoing notification (at most once a second, and only while a job is really running). */
        fun update(ctx: Context, text: String, pct: Int) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotify < 1000) return
            lastNotify = now
            try {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, build(ctx, "ClipForge AI is working", text, pct))
            } catch (_: Exception) {
            }
        }

        /** A normal notification when a job finishes or fails. */
        fun done(ctx: Context, title: String, text: String) {
            try {
                ensureChannel(ctx)
                val n = NotificationCompat.Builder(ctx, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open(ctx))
                    .setAutoCancel(true)
                    .build()
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(DONE_ID, n)
            } catch (_: Exception) {
            }
        }
    }
}
