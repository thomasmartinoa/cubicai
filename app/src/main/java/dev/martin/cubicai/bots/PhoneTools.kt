package dev.martin.cubicai.bots

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.martin.cubicai.MainActivity
import dev.martin.cubicai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * What the PC's Bots do with this phone (the gateway's phone_* tools). Deliveries (a reminder, a
 * notification, text to copy, a link) arrive by the run's events while the app follows the Bot, or by the
 * feed otherwise; each is carried out once. Questions (where the phone is, its battery) are answered
 * only while the app is open.
 */
object PhoneTools {
    private const val CHANNEL = "bot_phone"
    private const val REMINDERS = "bot_reminders"
    private const val PREFS = "bot_phone"
    const val ACTION_REMIND = "dev.martin.cubicai.BOT_REMIND"
    const val ACTION_COPY = "dev.martin.cubicai.BOT_COPY"

    fun isQuestion(p: JSONObject) = p.optString("do") in setOf("location", "status")

    fun hasLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Marks [id] done; false if it was already (a delivery comes by the run and by the feed). */
    @Synchronized
    private fun firstTime(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = runCatching { JSONArray(prefs.getString("done", "[]")) }.getOrDefault(JSONArray())
        val list = (0 until done.length()).map { done.optString(it) }
        if (id in list) return false
        prefs.edit { putString("done", JSONArray((list + id).takeLast(200)).toString()) }
        return true
    }

    /** Carries out a delivery from Bot [name] once. [visible]: the app is on screen. */
    fun deliver(context: Context, bot: String, name: String, color: String, p: JSONObject, visible: Boolean) {
        val id = p.optString("id")
        if (id.isEmpty() || isQuestion(p) || !firstTime(context, id)) return
        val text = p.optString("text")
        val url = p.optString("url")
        when (p.optString("do")) {
            "remind" -> {
                val at = p.optLong("at")
                // One set over a day ago is no use any more.
                if (at < System.currentTimeMillis() - 86_400_000L) return
                val r = JSONObject().put("id", id).put("at", at).put("text", text).put("bot", bot).put("name", name).put("color", color)
                saveReminder(context, r)
                schedule(context, r)
            }
            "notify" -> notify(context, id, bot, name, color, text, url.ifBlank { null }?.let { viewIntent(it) })
            "copy" -> if (visible) main { copy(context, name, text) } else
                notify(context, id, bot, name, color, "Tap to copy: $text", null, broadcast = Intent(context, PhoneReceiver::class.java).setAction(ACTION_COPY).putExtra("text", text).putExtra("name", name))
            "open" -> if (visible) main { runCatching { context.startActivity(viewIntent(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } else
                notify(context, id, bot, name, color, text.ifBlank { "Sent you a link" } + "\n" + url, viewIntent(url))
        }
    }

    private fun main(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    internal fun copy(context: Context, name: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(name, text))
        // Android 13+ shows its own "copied" note.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied from $name", Toast.LENGTH_SHORT).show()
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(
        context: Context, id: String, bot: String, name: String, color: String, text: String,
        view: Intent?, broadcast: Intent? = null, reminder: Boolean = false,
    ) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "From your Bots", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders and messages your Bots send to this phone"
        })
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tap = when {
            broadcast != null -> PendingIntent.getBroadcast(context, id.hashCode(), broadcast, flags)
            view != null -> PendingIntent.getActivity(context, id.hashCode(), view, flags)
            else -> PendingIntent.getActivity(
                context, id.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_BOT, bot), flags,
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(runCatching { android.graphics.Color.parseColor(color) }.getOrDefault(0xFF2F6BFF.toInt()))
            .setContentTitle(if (reminder) "Reminder · $name" else name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (reminder) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify("botphone:$id", 0, n)
    }

    // --- Reminders ---------------------------------------------------------------------------------

    private fun reminders(context: Context): List<JSONObject> {
        val raw = context.getSharedPreferences(REMINDERS, Context.MODE_PRIVATE).getString("all", "[]")
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    @Synchronized
    private fun saveAll(context: Context, all: List<JSONObject>) =
        context.getSharedPreferences(REMINDERS, Context.MODE_PRIVATE).edit { putString("all", JSONArray(all).toString()) }

    private fun saveReminder(context: Context, r: JSONObject) = saveAll(context, reminders(context).filter { it.optString("id") != r.optString("id") } + r)

    private fun alarmIntent(context: Context, id: String) = PendingIntent.getBroadcast(
        context, id.hashCode(), Intent(context, PhoneReceiver::class.java).setAction(ACTION_REMIND).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM are declared; checked below
    private fun schedule(context: Context, r: JSONObject) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = maxOf(r.optLong("at"), System.currentTimeMillis() + 1_000)
        val pi = alarmIntent(context, r.optString("id"))
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** The alarm went off: the reminder shows (and is forgotten). */
    internal fun fire(context: Context, id: String) {
        val r = reminders(context).firstOrNull { it.optString("id") == id } ?: return
        saveAll(context, reminders(context).filter { it.optString("id") != id })
        notify(context, id, r.optString("bot"), r.optString("name"), r.optString("color"), r.optString("text"), null, reminder = true)
    }

    /** After a restart alarms are gone: set them again (one missed while off shows now). */
    internal fun rescheduleAll(context: Context) = reminders(context).forEach { schedule(context, it) }

    // --- Questions ---------------------------------------------------------------------------------

    /** The answer to a question from a Bot ({error} when there isn't one). */
    suspend fun answer(context: Context, p: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        runCatching { if (p.optString("do") == "location") location(context) else status(context) }
            .getOrElse { JSONObject().put("error", it.message ?: "The phone couldn't tell.") }
    }

    private fun status(context: Context): JSONObject {
        val o = JSONObject()
        context.getSystemService(BatteryManager::class.java)?.let {
            o.put("battery", it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", it.isCharging)
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        o.put(
            "network", when {
                caps == null -> "no network"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                else -> "a network"
            },
        )
        context.getSystemService(AudioManager::class.java)?.let { o.put("silent", it.ringerMode != AudioManager.RINGER_MODE_NORMAL) }
        o.put("timezone", TimeZone.getDefault().id)
        return o
    }

    @SuppressLint("MissingPermission") // checked by hasLocation
    private suspend fun location(context: Context): JSONObject {
        if (!hasLocation(context)) return JSONObject().put("error", "The user hasn't let CubicAI see the phone's location.")
        val lm = context.getSystemService(LocationManager::class.java) ?: return JSONObject().put("error", "This phone has no location service.")
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return JSONObject().put("error", "Location is switched off on the phone.")
        var loc: Location? = null
        if (Build.VERSION.SDK_INT >= 30) {
            loc = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { cont ->
                    val cancel = android.os.CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(providers.first(), cancel, context.mainExecutor) { cont.resume(it) }
                }
            }
        }
        if (loc == null) loc = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        loc ?: return JSONObject().put("error", "The phone couldn't find where it is just now.")
        val o = JSONObject().put("lat", loc.latitude).put("lon", loc.longitude)
        if (loc.hasAccuracy()) o.put("accuracy", loc.accuracy.toDouble())
        o.put("age_s", (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L)
        place(context, loc)?.let { o.put("place", it) }
        return o
    }

    /** "MG Road, Bengaluru, Karnataka, India", when the phone's geocoder knows. */
    @Suppress("DEPRECATION")
    private fun place(context: Context, loc: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() ?: return null
        listOfNotNull(a.thoroughfare ?: a.subLocality, a.locality, a.adminArea, a.countryName).distinct().joinToString(", ").ifBlank { null }
    }.getOrNull()
}

/** Reminder alarms, "tap to copy", and setting reminders again after a restart. */
class PhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PhoneTools.ACTION_REMIND -> PhoneTools.fire(context, intent.getStringExtra("id").orEmpty())
            PhoneTools.ACTION_COPY -> PhoneTools.copy(context, intent.getStringExtra("name").orEmpty(), intent.getStringExtra("text").orEmpty())
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PhoneTools.rescheduleAll(context)
        }
    }
}
