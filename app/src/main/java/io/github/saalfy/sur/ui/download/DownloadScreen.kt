package io.github.saalfy.sur.ui.download

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.saalfy.sur.R
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.download.DownloadEvent
import io.github.saalfy.sur.download.DownloadManager
import io.github.saalfy.sur.download.DownloadService
import io.github.saalfy.sur.download.DownloadState
import io.github.saalfy.sur.ui.hasPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long "Play" waits for MediaStore to index a just-saved file before giving up. */
private const val LIBRARY_WAIT_MS = 3_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    initialUrl: String? = null,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val songRepository = remember { (context.applicationContext as SurApplication).container.songRepository }

    var urlInput by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val link = urlInput.trim()
        if (granted && link.isNotBlank()) DownloadService.start(context, link)
    }

    // Shared by "Download" and "Try again". Android 8-9 needs WRITE_EXTERNAL_STORAGE to save.
    val startDownload: () -> Unit = {
        val link = urlInput.trim()
        if (link.isNotBlank()) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ) {
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                DownloadService.start(context, link)
            }
        }
    }

    // The saved file reaches the library through the MediaStore observer, which can lag briefly.
    val notInLibraryYet = stringResource(R.string.download_not_in_library_yet)
    val playDownloaded: (Long) -> Unit = { songId ->
        scope.launch {
            val song = withTimeoutOrNull(LIBRARY_WAIT_MS) {
                songRepository.songs.first { songs -> songs.any { it.id == songId } }.first { it.id == songId }
            }
            if (song != null) onPlay(listOf(song), 0) else snackbarHostState.showSnackbar(notInLibraryYet)
        }
    }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) urlInput = initialUrl
    }

    val downloadState by DownloadManager.state.collectAsStateWithLifecycle()
    val isBusy = downloadState is DownloadState.Resolving ||
        downloadState is DownloadState.Downloading ||
        downloadState is DownloadState.Saving

    val busyText = stringResource(R.string.download_busy)
    LaunchedEffect(Unit) {
        DownloadManager.events.collect { event ->
            when (event) {
                DownloadEvent.Busy -> scope.launch { snackbarHostState.showSnackbar(busyText) }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.download_title), style = MaterialTheme.typography.headlineMedium) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.download_description_extended),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.download_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (urlInput.isNotBlank()) {
                        IconButton(onClick = { urlInput = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.download_clear))
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            )

            // Stacked full-width buttons: no side-by-side squeeze at large font scales
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val clip = clipboardManager.getText()?.text
                        if (!clip.isNullOrBlank()) urlInput = clip.trim()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.download_paste))
                }
                Button(
                    onClick = startDownload,
                    enabled = urlInput.isNotBlank() && !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.download_action))
                }
            }

            // Animates only when the kind of state changes; progress updates just recompose the card.
            AnimatedContent(
                targetState = downloadState,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 }) togetherWith fadeOut(tween(120))
                },
                label = "downloadState",
            ) { state ->
                when (state) {
                    DownloadState.Idle -> Spacer(Modifier.fillMaxWidth())
                    DownloadState.Resolving -> StatusCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                            Text(
                                text = stringResource(R.string.download_resolving),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                            )
                            TextButton(onClick = { DownloadService.cancel(context) }) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                    is DownloadState.Downloading -> DownloadingCard(state, onCancel = { DownloadService.cancel(context) })
                    DownloadState.Saving -> StatusCard {
                        Text(stringResource(R.string.download_saving), style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is DownloadState.Done -> StatusCard(container = MaterialTheme.colorScheme.primaryContainer) {
                        val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = onContainer)
                            Text(stringResource(R.string.download_done), style = MaterialTheme.typography.labelLarge, color = onContainer)
                        }
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = onContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Button(onClick = { playDownloaded(state.songId) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.play))
                        }
                        TextButton(onClick = { DownloadManager.reset() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.download_dismiss))
                        }
                    }
                    is DownloadState.Error -> StatusCard(container = MaterialTheme.colorScheme.errorContainer) {
                        val onContainer = MaterialTheme.colorScheme.onErrorContainer
                        Text(stringResource(R.string.download_failed_title), style = MaterialTheme.typography.titleMedium, color = onContainer)
                        Text(state.error.userMessage(), style = MaterialTheme.typography.bodyMedium, color = onContainer)
                        if (urlInput.isNotBlank()) {
                            Button(onClick = { DownloadManager.reset(); startDownload() }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.download_try_again))
                            }
                        }
                        TextButton(onClick = { DownloadManager.reset() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.download_dismiss), color = onContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingCard(state: DownloadState.Downloading, onCancel: () -> Unit) {
    val progress by animateFloatAsState(state.progress, tween(250), label = "downloadProgress")
    StatusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = state.title ?: stringResource(R.string.downloading),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                state.artist?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.downloading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = container, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
