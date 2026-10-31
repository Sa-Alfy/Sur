package io.github.saalfy.sur.ui.songs

import android.app.Activity
import android.Manifest
import android.content.IntentSender
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.MediaStoreUris
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.ArtworkEvictor
import io.github.saalfy.sur.data.DeleteAttempt
import io.github.saalfy.sur.data.MediaStoreSongDeleter
import io.github.saalfy.sur.data.QueueSongRemover
import io.github.saalfy.sur.data.SongDeleteStart
import io.github.saalfy.sur.data.SongDeletionCoordinator
import io.github.saalfy.sur.ui.artwork.Artwork
import io.github.saalfy.sur.ui.artwork.ArtworkLoader
import io.github.saalfy.sur.ui.artwork.ThumbnailSize
import io.github.saalfy.sur.ui.artwork.rememberArtworkLoader
import androidx.compose.ui.platform.LocalDensity
import io.github.saalfy.sur.ui.common.AddToPlaylistDialog
import io.github.saalfy.sur.ui.common.CenteredMessage
import io.github.saalfy.sur.ui.common.ConfirmDialog
import io.github.saalfy.sur.ui.common.SearchableTopBar
import io.github.saalfy.sur.ui.common.formatDuration
import io.github.saalfy.sur.ui.info.FindInfoHost
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    /** Read inside each row, so a song change recomposes only the visible rows. */
    currentSongId: () -> Long?,
    removeFromQueue: (songId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SongsViewModel = viewModel(factory = SongsViewModel.Factory),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val application = context.applicationContext as SurApplication
    // Resolved once here instead of per lazy item.
    val loader = rememberArtworkLoader()
    val thumbnailPx = with(LocalDensity.current) { ThumbnailSize.roundToPx() }
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val visibleSongs by viewModel.visibleSongs.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }

    var selected by remember { mutableStateOf(emptySet<Long>()) }
    // Song ids waiting for a playlist choice; non-null shows the picker.
    var pendingAdd by remember { mutableStateOf<List<Long>?>(null) }
    var findInfoSong by remember { mutableStateOf<Song?>(null) }
    var deleteSong by remember { mutableStateOf<Song?>(null) }
    var pendingDelete by remember { mutableStateOf<SongDeleteStart.Confirmation?>(null) }
    var permissionSongId by remember { mutableStateOf<Long?>(null) }
    val selecting = selected.isNotEmpty()

    val deletionCoordinator = remember {
        SongDeletionCoordinator(
            deleter = MediaStoreSongDeleter(context),
            keyResolver = application.container.songKeyResolver,
            queueSongRemover = object : QueueSongRemover {
                override suspend fun remove(songId: Long) = removeFromQueue(songId)
            },
            playlistRepository = application.container.playlistRepository,
            infoRepository = application.container.songInfoRepository,
            coverStore = application.container.coverStore,
            artworkEvictor = object : ArtworkEvictor {
                override fun evict(songId: Long) = loader.evict(songId)
            },
        )
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val songId = permissionSongId
        permissionSongId = null
        if (songId != null) {
            if (granted) {
                scope.launch {
                    when (val start = deletionCoordinator.begin(songId)) {
                        SongDeleteStart.Deleted -> Toast.makeText(context, R.string.deleted, Toast.LENGTH_SHORT).show()
                        SongDeleteStart.Failed -> Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        SongDeleteStart.PermissionRequired -> Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        is SongDeleteStart.Confirmation -> pendingDelete = start
                    }
                }
            } else {
                Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val intentSenderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val confirmation = pendingDelete
        pendingDelete = null
        if (confirmation != null && result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                val deleted = deletionCoordinator.finish(confirmation, confirmed = true)
                Toast.makeText(
                    context,
                    if (deleted) R.string.deleted else R.string.delete_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(pendingDelete) {
        pendingDelete?.let { confirmation ->
            intentSenderLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(confirmation.intentSender).build(),
            )
        }
    }

    BackHandler(enabled = selecting) { selected = emptySet() }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selecting) {
                // Batch selection action bar - matches stitch design
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.clear_selection),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.selected_count, selected.size),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        IconButton(onClick = {
                            pendingAdd = songs.orEmpty().map(Song::id).filter { it in selected }
                        }) {
                            Icon(
                                painterResource(R.drawable.ic_playlist_add),
                                contentDescription = stringResource(R.string.add_to_playlist),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        // Select all button
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.select_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .combinedClickable(onClick = {
                                        selected = visibleSongs.orEmpty().map { it.id }.toSet()
                                    })
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            } else {
                SearchableTopBar(
                    title = stringResource(R.string.tab_songs),
                    query = query,
                    onQueryChange = viewModel::onQueryChange,
                    searching = searching,
                    onSearchingChange = { searching = it },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val list = visibleSongs
            when {
                list == null -> Unit
                list.isEmpty() && query.isNotBlank() -> CenteredMessage(stringResource(R.string.no_results, query.trim()))
                list.isEmpty() -> CenteredMessage(stringResource(R.string.library_empty))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { _, song -> song.id }, contentType = { _, _ -> "song" }) { index, song ->
                        val isSelected = song.id in selected
                        SongRow(
                            song = song,
                            isCurrent = { currentSongId() == song.id },
                            loader = loader,
                            thumbnailPx = thumbnailPx,
                            modifier = Modifier.animateItem(),
                            selecting = selecting,
                            isSelected = isSelected,
                            onClick = {
                                if (selecting) {
                                    selected = if (isSelected) selected - song.id else selected + song.id
                                } else {
                                    onPlay(list, index)
                                }
                            },
                            onLongClick = { selected = selected + song.id },
                            onAddToPlaylist = { pendingAdd = listOf(song.id) },
                            onFindInfo = { findInfoSong = song },
                            onDelete = { deleteSong = song },
                        )
                    }
                }
            }
        }
    }

    pendingAdd?.let { songIds ->
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { id, name ->
                viewModel.addToPlaylist(id, songIds)
                Toast.makeText(context, context.getString(R.string.added_to, name), Toast.LENGTH_SHORT).show()
                pendingAdd = null
                selected = emptySet()
            },
            onCreate = { name ->
                viewModel.addToNewPlaylist(name, songIds)
                Toast.makeText(context, context.getString(R.string.added_to, name), Toast.LENGTH_SHORT).show()
                pendingAdd = null
                selected = emptySet()
            },
            onDismiss = { pendingAdd = null },
        )
    }

    FindInfoHost(song = findInfoSong, onFinished = { findInfoSong = null })

    deleteSong?.let { song ->
        ConfirmDialog(
            title = stringResource(R.string.delete_song),
            message = stringResource(R.string.delete_song_message, song.title),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                deleteSong = null
                scope.launch {
                    when (val start = deletionCoordinator.begin(song.id)) {
                        SongDeleteStart.Deleted -> Toast.makeText(context, R.string.deleted, Toast.LENGTH_SHORT).show()
                        SongDeleteStart.PermissionRequired -> permissionSongId = song.id
                        SongDeleteStart.Failed -> Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        is SongDeleteStart.Confirmation -> pendingDelete = start
                    }
                }
            },
            onDismiss = { deleteSong = null },
        )
    }
}

@Composable
private fun SongRow(
    song: Song,
    /** Read here, in the leaf, so a song change only recomposes affected rows. */
    isCurrent: () -> Boolean,
    loader: ArtworkLoader,
    thumbnailPx: Int,
    modifier: Modifier,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFindInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val current = isCurrent()
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = {
            Artwork(
                songId = song.id,
                artworkUri = MediaStoreUris.albumArt(song.albumId),
                size = ThumbnailSize,
                shape = RoundedCornerShape(10.dp),
                loader = loader,
                sizePx = thumbnailPx,
                modifier = Modifier.size(ThumbnailSize),
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current) {
                    Icon(
                        painterResource(R.drawable.ic_now_playing),
                        contentDescription = stringResource(R.string.now_playing_indicator),
                        tint = accent,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                    )
                }
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (current) accent else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = song.artist ?: stringResource(R.string.unknown_artist),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("·")
                Text(text = formatDuration(song.durationMs))
            }
        },
        trailingContent = {
            if (selecting) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_to_playlist)) },
                            onClick = { menuOpen = false; onAddToPlaylist() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.find_info)) },
                            onClick = { menuOpen = false; onFindInfo() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_song_action)) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        },
    )
}
