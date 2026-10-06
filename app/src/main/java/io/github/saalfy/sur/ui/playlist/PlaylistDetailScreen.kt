package io.github.saalfy.sur.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.playlist.PlaylistSong
import io.github.saalfy.sur.data.playlist.moveItem
import io.github.saalfy.sur.ui.common.CenteredMessage
import io.github.saalfy.sur.ui.common.formatDuration
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    viewModel: PlaylistDetailViewModel = viewModel(
        key = "playlist-$playlistId",
        factory = PlaylistDetailViewModel.factory(playlistId),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState

    if (state is PlaylistDetailUiState.Missing) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = (state as? PlaylistDetailUiState.Loaded)?.playlist?.name.orEmpty()
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (state is PlaylistDetailUiState.Loaded) {
                if (state.songs.isEmpty()) {
                    CenteredMessage(stringResource(R.string.playlist_empty))
                } else {
                    ReorderableSongList(
                        songs = state.songs,
                        onPlay = onPlay,
                        onRemove = viewModel::remove,
                        onMove = viewModel::move,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReorderableSongList(
    songs: List<PlaylistSong>,
    onPlay: (List<Song>, Int) -> Unit,
    onRemove: (entryId: Long) -> Unit,
    onMove: (fromEntryId: Long, toEntryId: Long) -> Unit,
) {
    // Local order while dragging; dropped once the database emits the persisted order.
    var localOrder by remember { mutableStateOf<List<PlaylistSong>?>(null) }
    LaunchedEffect(songs) { localOrder = null }
    val items = localOrder ?: songs

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        localOrder = moveItem(localOrder ?: songs, from.index, to.index)
    }
    val unknownArtist = stringResource(R.string.unknown_artist)

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { _, item -> item.entryId }) { index, item ->
            ReorderableItem(reorderState, key = item.entryId) { isDragging ->
                ListItem(
                    modifier = Modifier.clickable { onPlay(items.map(PlaylistSong::song), index) },
                    colors = if (isDragging) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        ListItemDefaults.colors()
                    },
                    headlineContent = { Text(item.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${item.song.artist ?: unknownArtist} · ${formatDuration(item.song.durationMs)}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onRemove(item.entryId) }) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.remove_from_playlist),
                                )
                            }
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = {
                                        val newIndex = items.indexOfFirst { it.entryId == item.entryId }
                                        val target = songs.getOrNull(newIndex)
                                        if (target == null || target.entryId == item.entryId) {
                                            localOrder = null
                                        } else {
                                            onMove(item.entryId, target.entryId)
                                        }
                                    },
                                ),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_drag_handle),
                                    contentDescription = stringResource(R.string.reorder),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
