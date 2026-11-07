package dev.martin.cubicai.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.martin.cubicai.MainActivity
import dev.martin.cubicai.R

/**
 * Keeps a voice call going while CubicAI isn't on screen: the microphone stays open (Android only allows that to
 * a foreground service of the microphone type), and while the screen is shared it holds the screen capture.
 * The notification shows what the call is doing, with Mute and End.
 */
class CallService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var title = "On a call"
    private var text = ""
    private var muted = false
    private var sharing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE, ACTION_END -> {
                onAction?.invoke(intent.action!!)
                return START_NOT_STICKY
            }
            ACTION_SCREEN -> {
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                sharing = true
                foreground() // the media projection type must be in place before the projection is taken
                val projection = data?.let { getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code, it) }
                if (projection == null) {
                    sharing = false
                    foreground()
                    onScreenEnded?.invoke()
                } else {
                    screen?.stop()
                    screen = ScreenGrab(this, projection) {
                        screen = null
                        sharing = false
                        runCatching { foreground() }
                        onScreenEnded?.invoke()
                    }
                }
            }
            ACTION_SCREEN_OFF -> {
                screen?.stop()
                screen = null
                sharing = false
                foreground()
            }
            else -> {
                intent?.getStringExtra(EXTRA_TITLE)?.let { title = it }
                intent?.getStringExtra(EXTRA_TEXT)?.let { text = it }
                muted = intent?.getBooleanExtra(EXTRA_MUTED, muted) ?: muted
                foreground()
            }
        }
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CubicAI:call")
            ?.apply { acquire(3 * 60 * 60_000L) }
        return START_NOT_STICKY
    }

    private fun foreground() {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (sharing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    override fun onDestroy() {
        screen?.stop()
        screen = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.deleteNotificationChannel("call") // the first, low-importance channel
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Voice calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
            description = "Shown while you're on a voice call"
        })
        fun action(name: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, CallService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        // Call style: Android keeps it at the top of the shade (and shows the call in the status bar) like a phone call.
        val caller = Person.Builder().setName(title).setImportant(true).build()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (muted) "Muted" else text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, action(ACTION_END, 3)))
            .addAction(0, if (muted) "Unmute" else "Mute", action(ACTION_MUTE, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "voice_call"
        private const val NOTIFICATION_ID = 3
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_MUTED = "muted"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        const val ACTION_MUTE = "dev.martin.cubicai.call.MUTE"
        const val ACTION_END = "dev.martin.cubicai.call.END"
        private const val ACTION_SCREEN = "dev.martin.cubicai.call.SCREEN"
        private const val ACTION_SCREEN_OFF = "dev.martin.cubicai.call.SCREEN_OFF"

        @Volatile var onAction: ((String) -> Unit)? = null
        /** The user stopped sharing from the system (or the capture couldn't start). */
        @Volatile var onScreenEnded: (() -> Unit)? = null
        /** The shared screen while it's shared. */
        @Volatile var screen: ScreenGrab? = null
            private set
        @Volatile private var startedAt = 0L

        fun start(context: Context, title: String, text: String) {
            startedAt = System.currentTimeMillis()
            update(context, title, text, muted = false)
        }

        fun update(context: Context, title: String, text: String, muted: Boolean) {
            val intent = Intent(context, CallService::class.java)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_MUTED, muted)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Starts sharing the screen with the consent the user just gave ([code] and [data] from the system dialog). */
        fun shareScreen(context: Context, code: Int, data: Intent) {
            val intent = Intent(context, CallService::class.java).setAction(ACTION_SCREEN).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopSharing(context: Context) {
            if (screen == null) return
            runCatching { context.startService(Intent(context, CallService::class.java).setAction(ACTION_SCREEN_OFF)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/** The screen as a picture, kept fresh about twice a second while it's shared. */
class ScreenGrab(context: Context, private val projection: MediaProjection, onEnded: () -> Unit) {
    private val thread = HandlerThread("call-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader: ImageReader
    private val display: VirtualDisplay?
    @Volatile private var latest: Bitmap? = null
    @Volatile private var keptAt = 0L

    init {
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        // Small enough to copy quickly, big enough to read text on it.
        val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
        val width = (metrics.widthPixels * scale).toInt() / 2 * 2
        val height = (metrics.heightPixels * scale).toInt() / 2 * 2
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
                onEnded()
            }
        }, handler)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            image.use {
                val now = System.currentTimeMillis()
                if (now - keptAt < 500) return@use
                keptAt = now
                val plane = it.planes[0]
                val rowPixels = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(rowPixels, it.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                latest = if (rowPixels == it.width) padded else Bitmap.createBitmap(padded, 0, 0, it.width, it.height)
            }
        }, handler)
        display = runCatching {
            projection.createVirtualDisplay(
                "CubicAI call", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
            )
        }.getOrNull()
    }

    /** What the screen showed a moment ago, or null before the first frame. */
    fun frame(): Bitmap? = latest

    fun stop() {
        runCatching { display?.release() }
        runCatching { projection.stop() }
        runCatching { reader.close() }
        thread.quitSafely()
    }
}

/** Set by the call screen while the camera is on: the frame it's showing (called on the main thread). */
object CameraFrames {
    @Volatile var grab: (() -> Bitmap?)? = null
}
