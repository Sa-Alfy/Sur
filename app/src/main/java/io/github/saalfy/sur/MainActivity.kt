package io.github.saalfy.sur

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.saalfy.sur.playback.PlayerConnection
import io.github.saalfy.sur.ui.library.LibraryScreen
import io.github.saalfy.sur.ui.theme.SurTheme

class MainActivity : ComponentActivity() {

    private val playerConnection by lazy { PlayerConnection(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(playerConnection)
        setContent {
            SurTheme {
                LibraryScreen(onPlay = playerConnection::playAll)
            }
        }
    }
}
