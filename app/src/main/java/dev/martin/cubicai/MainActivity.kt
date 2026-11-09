package dev.martin.cubicai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ui.BotChatScreen
import dev.martin.cubicai.ui.BotsScreen
import dev.martin.cubicai.ui.CallScreen
import dev.martin.cubicai.ui.ChatScreen
import dev.martin.cubicai.ui.CubicTheme
import dev.martin.cubicai.ui.isDark
import dev.martin.cubicai.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    /** A Bot to open, from its notification. */
    private var openBot by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleShare(intent)
            openBot = intent?.getStringExtra(EXTRA_BOT)
        }
        askForNotifications()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = isDark(state.settings.theme)
            // Status and navigation bar icons follow the app's theme, which may differ from the phone's.
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            CubicTheme(dark) {
                LaunchedEffect(state.settings.privacyScreen) {
                    if (state.settings.privacyScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                // chat, settings, bots (the list) or bot (the open Bot's chat).
                var screen by rememberSaveable { mutableStateOf("chat") }
                val botsState by vm.bots.state.collectAsStateWithLifecycle()
                // The open Bot doesn't survive the process; back to the list then.
                if (screen == "bot" && botsState.open == null) screen = "bots"
                LaunchedEffect(openBot) {
                    val id = openBot ?: return@LaunchedEffect
                    openBot = null
                    vm.bots.open(id)
                    dev.martin.cubicai.bots.BotFeed.clear(this@MainActivity, id)
                    screen = "bot"
                }
                fun depth(s: String) = when (s) { "chat" -> 0; "bot" -> 2; else -> 1 }
                BackHandler(enabled = screen != "chat") {
                    screen = when (screen) {
                        "bot" -> { vm.bots.close(); "bots" }
                        else -> "chat"
                    }
                }
                val call by vm.call.state.collectAsStateWithLifecycle()
                Box {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val dir = if (depth(targetState) > depth(initialState)) 1 else -1
                        (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 10 * dir }) togetherWith
                            (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { -it / 10 * dir })
                    },
                    label = "screen",
                ) { shown ->
                    when (shown) {
                        "settings" -> SettingsScreen(vm, onBack = { screen = "chat" })
                        "bots" -> BotsScreen(
                            vm,
                            onBack = { screen = "chat" },
                            onOpenBot = { vm.bots.open(it); screen = "bot" },
                            onOpenSettings = { screen = "settings" },
                        )
                        "bot" -> BotChatScreen(vm, onBack = { vm.bots.close(); screen = "bots" }, onOpenSettings = { screen = "settings" })
                        else -> ChatScreen(vm, onOpenSettings = { screen = "settings" }, onOpenBots = { screen = "bots" })
                    }
                }
                // A call covers the app; pulled down, the chat shows with the call in a bar on top.
                AnimatedVisibility(
                    visible = call.active && !call.minimized,
                    enter = slideInVertically(tween(240)) { it / 8 } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(200)) { it / 8 } + fadeOut(tween(160)),
                ) { CallScreen(vm) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.setVisible(true)
    }

    override fun onStop() {
        vm.setVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        intent.getStringExtra(EXTRA_BOT)?.let { openBot = it }
    }

    /**
     * Once: a reply or creation that finishes while you're in another app says so in a notification, and the
     * "working" notice shows its progress. Without the permission the work still goes on.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** Content shared into CubicAI from other apps lands in the composer of a fresh chat. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Only content from another app's provider: a file:// URI or our own provider could point at our private files.
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.takeIf { it.scheme == "content" && it.authority != "$packageName.files" }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (stream == null && text.isNullOrBlank()) return
        vm.newChat()
        stream?.let { vm.addAttachments(listOf(it)) }
        text?.let { vm.setDraft(it) }
    }

    companion object {
        const val EXTRA_BOT = "dev.martin.cubicai.BOT"
    }
}
