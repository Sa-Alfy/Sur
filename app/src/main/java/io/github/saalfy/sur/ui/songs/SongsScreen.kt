package io.github.saalfy.sur.ui.songs

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.ui.common.AddToPlaylistDialog
import io.github.saalfy.sur.ui.common.CenteredMessage
import io.github.saalfy.sur.ui.common.SearchableTopBar
import io.github.saalfy.sur.ui.common.formatDuration
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SongsViewModel = viewModel(factory = SongsViewModel.Factory),
) {
    val context = LocalContext.current
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val visibleSongs by viewModel.visibleSongs.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }

    var selected by remember { mutableStateOf(emptySet<Long>()) }
    // Song ids waiting for a playlist choice; non-null shows the picker.
    var pendingAdd by remember { mutableStateOf<List<Long>?>(null) }
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = selecting) { selected = emptySet() }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            // Keep library order rather than tap order.
                            pendingAdd = songs.orEmpty().map(Song::id).filter { it in selected }
                        }) {
                            Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = stringResource(R.string.add_to_playlist))
                        }
                    },
                )
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
                    itemsIndexed(list, key = { _, song -> song.id }) { index, song ->
                        val isSelected = song.id in selected
                        SongRow(
                            song = song,
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
}

@Composable
private fun SongRow(
    song: Song,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${song.artist ?: stringResource(R.string.unknown_artist)} · ${formatDuration(song.durationMs)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                    }
                }
            }
        },
    )
}
