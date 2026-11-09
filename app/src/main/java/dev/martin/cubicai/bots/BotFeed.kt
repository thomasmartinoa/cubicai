package dev.martin.cubicai.bots

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.martin.cubicai.MainActivity
import dev.martin.cubicai.R
import dev.martin.cubicai.data.Providers
import dev.martin.cubicai.data.SettingsStore
import dev.martin.cubicai.net.BotFeedItem
import dev.martin.cubicai.net.LlmClient
import dev.martin.cubicai.net.botFeed
import dev.martin.cubicai.net.listBots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Notifications from the PC's Bots. Android gives an app no push without Google's servers, so the phone
 * asks the PC's feed (what ended, what waits for approval) every 15 minutes (the shortest periodic job),
 * and every 30 s for a while after the app leaves the screen with a Bot still working, so an answer
 * you're waiting for arrives soon after it's written. Nothing is shown for what happened while the app
 * was on screen, or for a routine that found nothing new.
 */
object BotFeed {
    private const val PERIODIC = 41
    private const val SOON = 42
    private const val CHANNEL = "bots"
    /** Follow-up checks after leaving with a Bot at work: 30 s apart, for up to 15 minutes. */
    private const val SOON_MS = 30_000L
    private const val SOON_CHECKS = 30

    /** Set by the app: nothing is notified while it's on screen. */
    @Volatile var appVisible = false

    private val lock = Mutex()

    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (jobs.getPendingJob(PERIODIC) != null) return
        jobs.schedule(
            JobInfo.Builder(PERIODIC, ComponentName(context, BotFeedJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build(),
        )
    }

    /** The app left the screen: what happened so far was seen; check again soon if a Bot is still at work. */
    fun leaving(context: Context, botWorking: Boolean, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { check(context, notify = false) }
            if (botWorking) soon(context, SOON_CHECKS)
        }
    }

    private fun soon(context: Context, left: Int) {
        if (left <= 0) return
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        jobs.schedule(
            JobInfo.Builder(SOON, ComponentName(context, BotFeedJob::class.java))
                .setMinimumLatency(SOON_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(android.os.PersistableBundle().apply { putInt("left", left) })
                .build(),
        )
    }

    /**
     * Asks the PC what happened since the last check and notifies (unless [notify] is false or the app
     * shows). Returns whether a Bot is still working.
     */
    suspend fun check(context: Context, notify: Boolean = true): Boolean = lock.withLock {
        val settings = SettingsStore(context).load()
        if (!settings.isPaired) return false
        val urls = settings.baseUrls(Providers.LOCAL)
        val token = settings.key(Providers.LOCAL)
        val pin = settings.localFingerprint
        val client = LlmClient()
        val prefs = context.getSharedPreferences("bot_feed", Context.MODE_PRIVATE)
        val after = prefs.getLong("after", 0)
        val (items, upto) = client.botFeed(urls, token, pin, after)
        // An item stamped in the very millisecond of the last check can come twice.
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        val fresh = items.filter { it.id !in seen }
        // The first check ever only sets where to start from.
        // What a Bot sent this phone is carried out whether or not the app shows (each only once).
        if (after > 0) fresh.filter { it.kind == "phone" && it.phone != null }.forEach { PhoneTools.deliver(context, it.bot, it.name, it.color, it.phone!!, appVisible) }
        if (notify && !appVisible && after > 0) fresh.filter { !it.quiet && it.kind != "phone" }.forEach { show(context, it) }
        prefs.edit {
            putLong("after", upto)
            putStringSet("seen", (items.map { it.id }).takeLast(50).toSet())
        }
        runCatching { client.listBots(urls, token, pin).any { it.running } }.getOrDefault(false)
    }

    private fun show(context: Context, item: BotFeedItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Bots", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a Bot finishes something or asks you to approve a routine"
        })
        val title = item.name + (item.routine?.let { " · $it" } ?: "")
        val text = when (item.kind) {
            "approval" -> "Wants to " + item.text.removePrefix("wants to ")
            "error" -> "Couldn't finish: " + item.text
            else -> item.text.ifBlank { "Done." }
        }
        val open = PendingIntent.getActivity(
            context, item.bot.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_BOT, item.bot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(runCatching { android.graphics.Color.parseColor(item.color) }.getOrDefault(0xFF2F6BFF.toInt()))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(item.at)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One per Bot: its newest news replaces the last.
        manager.notify("bot:" + item.bot, 0, n)
    }

    /** Opening a Bot clears its notification. */
    fun clear(context: Context, botId: String) =
        context.getSystemService(NotificationManager::class.java)?.cancel("bot:$botId", 0)

    internal fun next(context: Context, params: JobParameters, stillWorking: Boolean) {
        if (params.jobId == SOON && stillWorking) soon(context, params.extras.getInt("left") - 1)
    }
}

/** Runs [BotFeed.check] for the periodic and follow-up jobs. */
class BotFeedJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val working = runCatching { BotFeed.check(applicationContext) }.getOrDefault(true)
            BotFeed.next(applicationContext, params, working)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}
