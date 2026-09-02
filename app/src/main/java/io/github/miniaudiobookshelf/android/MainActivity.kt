package io.github.miniaudiobookshelf.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.miniaudiobookshelf.android.ui.AppRoot
import java.io.File

class MainActivity : ComponentActivity() {
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
