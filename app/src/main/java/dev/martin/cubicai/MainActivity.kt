package dev.martin.cubicai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.martin.cubicai.ui.ChatScreen
import dev.martin.cubicai.ui.CubicTheme
import dev.martin.cubicai.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            CubicTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.settings.privacyScreen) {
                    if (state.settings.privacyScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(vm, onBack = { showSettings = false })
                } else {
                    ChatScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
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
