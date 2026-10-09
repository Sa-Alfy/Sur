package io.github.saalfy.sur.ui.songs

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.ui.common.AddToPlaylistDialog
import io.github.saalfy.sur.ui.common.CenteredMessage
import io.github.saalfy.sur.ui.common.SearchableTopBar
import io.github.saalfy.sur.ui.common.formatDuration

private const val TAB_ALL_SONGS = 0
private const val TAB_QUEUE = 1

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
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_ALL_SONGS) }

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
                            Text(
                                text = stringResource(R.string.batch_queue_edit),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
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
                Column {
                    SearchableTopBar(
                        title = stringResource(R.string.tab_songs),
                        query = query,
                        onQueryChange = viewModel::onQueryChange,
                        searching = searching,
                        onSearchingChange = { searching = it },
                    )
                    // Segmented tab bar: All Songs / Current Queue
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == TAB_ALL_SONGS,
                            onClick = { selectedTab = TAB_ALL_SONGS },
                            text = {
                                val count = songs?.size ?: 0
                                Text(stringResource(R.string.tab_all_songs_count, count))
                            },
                        )
                        Tab(
                            selected = selectedTab == TAB_QUEUE,
                            onClick = { selectedTab = TAB_QUEUE },
                            text = { Text(stringResource(R.string.tab_queue)) },
                        )
                    }
                }
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
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = formatDuration(song.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                )
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
                    }
                }
            }
        },
    )
}
