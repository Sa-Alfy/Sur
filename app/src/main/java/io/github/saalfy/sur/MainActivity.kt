package io.github.saalfy.sur

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.saalfy.sur.playback.PlayerConnection
import io.github.saalfy.sur.ui.SurApp
import io.github.saalfy.sur.ui.theme.SurTheme

class MainActivity : ComponentActivity() {

    private val playerConnection by lazy { PlayerConnection(this) }
    private var sharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(playerConnection)
        handleIntent(intent)
        setContent {
            SurTheme {
                SurApp(playerConnection, sharedUrl = sharedUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}
