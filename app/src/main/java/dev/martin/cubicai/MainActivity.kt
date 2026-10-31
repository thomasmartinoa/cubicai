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
import dev.martin.cubicai.ui.ChatScreen
import dev.martin.cubicai.ui.CubicTheme
import dev.martin.cubicai.ui.isDark
import dev.martin.cubicai.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
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
                var showSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }
                AnimatedContent(
                    targetState = showSettings,
                    transitionSpec = {
                        val dir = if (targetState) 1 else -1
                        (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 10 * dir }) togetherWith
                            (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { -it / 10 * dir })
                    },
                    label = "screen",
                ) { settings ->
                    if (settings) SettingsScreen(vm, onBack = { showSettings = false })
                    else ChatScreen(vm, onOpenSettings = { showSettings = true })
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
}
