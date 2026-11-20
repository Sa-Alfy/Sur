package io.github.saalfy.sur.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.playback.PlayerConnection
import io.github.saalfy.sur.playback.rememberPlayerUiState
import io.github.saalfy.sur.playback.songId
import io.github.saalfy.sur.ui.download.DownloadScreen
import io.github.saalfy.sur.ui.player.MiniPlayer
import io.github.saalfy.sur.ui.player.NowPlayingScreen
import io.github.saalfy.sur.ui.playlist.PlaylistDetailScreen
import io.github.saalfy.sur.ui.playlists.PlaylistsScreen
import io.github.saalfy.sur.ui.settings.SettingsScreen
import io.github.saalfy.sur.ui.settings.rememberUpdateManager
import io.github.saalfy.sur.ui.songs.SongsScreen

private const val TAB_PLAYLISTS = 0
private const val TAB_SONGS = 1
private const val TAB_DOWNLOAD = 2

@Composable
fun SurApp(
    playerConnection: PlayerConnection,
    sharedUrl: String? = null,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    UpdateStartupCheck()
    AudioPermissionGate {
        BatteryOptimizationPrompt()

        val controller by playerConnection.controller.collectAsStateWithLifecycle()
        val playerState = controller?.let { rememberPlayerUiState(it) }
        val hasMedia = playerState?.currentItem != null
        val play = rememberPlayAction(playerConnection::playAll)

        var tab by rememberSaveable { mutableIntStateOf(TAB_PLAYLISTS) }
        var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
        var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(sharedUrl) {
            if (!sharedUrl.isNullOrBlank()) {
                tab = TAB_DOWNLOAD
            }
        }

        // Queue emptied while open: close. A null controller (reconnecting after rotation) is not "empty".
        LaunchedEffect(controller, hasMedia) {
            if (controller != null && !hasMedia) nowPlayingOpen = false
        }
        val snackbar = remember { SnackbarHostState() }
        UpdateNotices(snackbar, onView = { settingsOpen = true })
        BackHandler(enabled = openPlaylistId != null) { openPlaylistId = null }
        BackHandler(enabled = openPlaylistId == null && tab != TAB_PLAYLISTS) { tab = TAB_PLAYLISTS }

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        if (playerState != null && hasMedia) {
                            MiniPlayer(playerState, onOpen = { nowPlayingOpen = true })
                        }
                        if (openPlaylistId == null) {
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
                                NavigationBarItem(
                                    selected = tab == TAB_DOWNLOAD,
                                    onClick = { tab = TAB_DOWNLOAD },
                                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                                    label = { Text(stringResource(R.string.tab_download)) },
                                )
                            }
                        } else {
                            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                        }
                    }
                },
            ) { innerPadding ->
                // Short fade between screens; nothing else animates here.
                AnimatedContent(
                    targetState = openPlaylistId to tab,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                    label = "screen",
                ) { (playlistId, shownTab) ->
                    when {
                        playlistId != null -> PlaylistDetailScreen(
                            playlistId = playlistId,
                            onBack = { openPlaylistId = null },
                            onPlay = play,
                        )
                        shownTab == TAB_PLAYLISTS -> PlaylistsScreen(
                            onOpen = { openPlaylistId = it },
                            onOpenSettings = { settingsOpen = true },
                        )
                        shownTab == TAB_SONGS -> SongsScreen(
                            onPlay = play,
                            currentSongId = { playerState?.currentItem?.songId },
                            removeFromQueue = { songId ->
                                controller?.let { player ->
                                    for (index in player.mediaItemCount - 1 downTo 0) {
                                        if (player.getMediaItemAt(index).mediaId == songId.toString()) {
                                            player.removeMediaItem(index)
                                        }
                                    }
                                }
                            },
                        )
                        else -> DownloadScreen(onPlay = play, initialUrl = sharedUrl)
                    }
                }
            }

            // Drawn on top so the screen underneath keeps its scroll/search state.
            if (nowPlayingOpen) {
                NowPlayingScreen(state = playerState, onClose = { nowPlayingOpen = false })
            }
            if (settingsOpen) {
                SettingsScreen(onClose = { settingsOpen = false })
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

/** Restores cached update info and checks the server if the cooldown passed. Never blocks the UI. */
@Composable
private fun UpdateStartupCheck() {
    val manager = rememberUpdateManager()
    LaunchedEffect(Unit) {
        manager.restore().join()
        manager.checkIfDue()
    }
}

/** Non-blocking update notices: a snackbar, or a dismissible dialog for a required update. */
@Composable
private fun UpdateNotices(snackbar: SnackbarHostState, onView: () -> Unit) {
    val manager = rememberUpdateManager()
    val resources = LocalResources.current
    val notice by manager.notice.collectAsStateWithLifecycle()
    val currentOnView by rememberUpdatedState(onView)

    LaunchedEffect(manager) {
        manager.justUpdated.collect { updated ->
            if (updated) {
                manager.clearJustUpdated()
                snackbar.showSnackbar(resources.getString(R.string.update_done, manager.installedVersionName))
            }
        }
    }
    // Collected here (not keyed on the value) so marking the notice as shown doesn't cancel the snackbar.
    LaunchedEffect(manager) {
        manager.notice.collect { m ->
            if (m != null && !m.mandatory) {
                manager.noticeShown()
                val result = snackbar.showSnackbar(
                    resources.getString(R.string.update_notice, m.versionName),
                    actionLabel = resources.getString(R.string.update_notice_view),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) currentOnView()
            }
        }
    }
    val required = notice?.takeIf { it.mandatory } ?: return
    AlertDialog(
        onDismissRequest = manager::noticeShown,
        title = { Text(stringResource(R.string.update_required_title)) },
        text = { Text(stringResource(R.string.update_required_body, required.versionName)) },
        confirmButton = {
            TextButton(onClick = {
                manager.noticeShown()
                onView()
            }) { Text(stringResource(R.string.update_notice_view)) }
        },
        dismissButton = {
            TextButton(onClick = manager::noticeShown) { Text(stringResource(R.string.update_notice_later)) }
        },
    )
}
