package dev.martin.cubicai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps CubicAI running while a reply is being written or something is made on the PC, so switching to another
 * app doesn't cut the connection: Android freezes or kills a background app's process within minutes otherwise.
 * Closing the app from recent apps still stops it (the chat's work belongs to the open app).
 */
class WorkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopTapped?.invoke()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Working"
        ServiceCompat.startForeground(this, WORKING_ID, working(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // With the screen off the CPU would otherwise sleep between network packets and the reply would stall.
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CubicAI:work")
            ?.apply { acquire(30 * 60_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun working(text: String): Notification {
        channels(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WorkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_WORK = "work"
        private const val CHANNEL_DONE = "done"
        private const val WORKING_ID = 1
        private const val DONE_ID = 2
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "dev.martin.cubicai.STOP"

        /** Called when Stop is tapped in the notification. */
        @Volatile var onStopTapped: (() -> Unit)? = null

        /** Shows [text] ("Writing a reply") while there's work, or stops when it's null. */
        fun update(context: Context, text: String?) {
            if (text == null) {
                context.stopService(Intent(context, WorkService::class.java))
                return
            }
            // Refused when the app is already in the background (Android 12+); the work then goes on while it can.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java).putExtra(EXTRA_TEXT, text)) }
        }

        /** Tells the user a reply or creation finished while they were in another app. */
        fun done(context: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            channels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(DONE_ID, notification)
        }

        /** The finished-reply notice is stale once the app is open again. */
        fun clearDone(context: Context) = context.getSystemService(NotificationManager::class.java)?.cancel(DONE_ID)

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_WORK, "Working", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a reply is written or something is made on your PC"
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a reply or creation is ready while you're in another app"
            })
        }
    }
}
