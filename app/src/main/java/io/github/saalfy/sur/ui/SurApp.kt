package io.github.saalfy.sur.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.ui.playlist.PlaylistDetailScreen
import io.github.saalfy.sur.ui.playlists.PlaylistsScreen
import io.github.saalfy.sur.ui.songs.SongsScreen

private const val TAB_PLAYLISTS = 0
private const val TAB_SONGS = 1

@Composable
fun SurApp(onPlay: (songs: List<Song>, startIndex: Int) -> Unit) {
    AudioPermissionGate {
        val play = rememberPlayAction(onPlay)
        var tab by rememberSaveable { mutableIntStateOf(TAB_PLAYLISTS) }
        var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }

        val playlistId = openPlaylistId
        if (playlistId != null) {
            BackHandler { openPlaylistId = null }
            PlaylistDetailScreen(playlistId = playlistId, onBack = { openPlaylistId = null }, onPlay = play)
            return@AudioPermissionGate
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == TAB_PLAYLISTS,
                        onClick = { tab = TAB_PLAYLISTS },
                        icon = { Icon(painterResource(R.drawable.ic_queue_music), contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_playlists)) },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_SONGS,
                        onClick = { tab = TAB_SONGS },
                        icon = { Icon(painterResource(R.drawable.ic_music_note), contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_songs)) },
                    )
                }
            },
        ) { innerPadding ->
            val modifier = Modifier.padding(innerPadding)
            when (tab) {
                TAB_PLAYLISTS -> PlaylistsScreen(onOpen = { openPlaylistId = it }, modifier = modifier)
                else -> SongsScreen(onPlay = play, modifier = modifier)
            }
        }
    }
}

/** Wraps [onPlay] to ask once for notification permission on 13+. Playback never waits on the answer. */
@Composable
private fun rememberPlayAction(onPlay: (List<Song>, Int) -> Unit): (List<Song>, Int) -> Unit {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { songs, index ->
        onPlay(songs, index)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
