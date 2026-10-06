package io.github.saalfy.sur.ui.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.Song

private val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, AUDIO_PERMISSION)
        viewModel.onPermissionResult(granted, permanentlyDenied)
    }
    // Notifications are optional: playback starts regardless of the answer.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LifecycleResumeEffect(Unit) {
        viewModel.onPermissionChecked(context.hasPermission(AUDIO_PERMISSION))
        onPauseOrDispose { }
    }

    var askedForAudio by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is LibraryUiState.NeedsPermission && !state.permanentlyDenied && !askedForAudio) {
            askedForAudio = true
            audioPermissionLauncher.launch(AUDIO_PERMISSION)
        }
    }

    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val playFrom: (List<Song>, Int) -> Unit = { songs, index ->
        onPlay(songs, index)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedForNotifications &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            askedForNotifications = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                LibraryUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                LibraryUiState.Empty -> Message(stringResource(R.string.library_empty))
                is LibraryUiState.NeedsPermission -> PermissionMessage(
                    permanentlyDenied = state.permanentlyDenied,
                    onGrant = { audioPermissionLauncher.launch(AUDIO_PERMISSION) },
                    onOpenSettings = { context.openAppSettings() },
                )
                is LibraryUiState.Songs -> SongList(state.songs, playFrom)
            }
        }
    }
}

@Composable
private fun SongList(songs: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    val unknownArtist = stringResource(R.string.unknown_artist)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            ListItem(
                modifier = Modifier.clickable { onPlay(songs, index) },
                headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(song.artist ?: unknownArtist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { Text(formatDuration(song.durationMs)) },
            )
        }
    }
}

@Composable
private fun PermissionMessage(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (permanentlyDenied) R.string.permission_permanently_denied else R.string.permission_rationale,
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
        } else {
            Button(onClick = onGrant) { Text(stringResource(R.string.grant_access)) }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
