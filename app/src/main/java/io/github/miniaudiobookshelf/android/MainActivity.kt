package io.github.miniaudiobookshelf.android

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.miniaudiobookshelf.android.player.PlaybackService
import io.github.miniaudiobookshelf.android.ui.AppRoot
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as MiniAbsApplication
        if (BuildConfig.DEBUG) {
            applyDebugExtras(app)
        }
        setContent {
            AppRoot(session = app.session, playback = app.playback)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    app.playback.state.collect { player ->
                        if (player.itemId.isNotEmpty()) {
                            connectController(app)
                        } else {
                            disconnectController(app)
                        }
                    }
                } finally {
                    disconnectController(app)
                }
            }
        }
    }

    private fun connectController(app: MiniAbsApplication) {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (future.isCancelled) return@addListener
                try {
                    app.playback.controller = future.get()
                } catch (_: Exception) {
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun disconnectController(app: MiniAbsApplication) {
        app.playback.controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    private fun applyDebugExtras(app: MiniAbsApplication) {
        val url = intent.getStringExtra("MINI_ABS_URL").orEmpty()
        val token = intent.getStringExtra("MINI_ABS_TOKEN").orEmpty()
        val item = intent.getStringExtra("MINI_ABS_ITEM").orEmpty()
        if (url.isEmpty() || token.isEmpty() || item.isEmpty()) return
        val pin = readPin(intent.getStringExtra("MINI_ABS_PIN"))
        app.session.applyDebugLaunch(url, token, item, pin)
    }

    private fun readPin(extra: String?): String? {
        if (extra.isNullOrEmpty()) return null
        if (extra.contains("BEGIN CERTIFICATE")) return extra
        return try {
            File(extra).readText()
        } catch (_: Exception) {
            null
        }
    }
}
