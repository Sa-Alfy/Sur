package io.github.saalfy.sur.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.playlist.Playlist
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import io.github.saalfy.sur.ui.common.CenteredMessage
import io.github.saalfy.sur.ui.common.ConfirmDialog
import io.github.saalfy.sur.ui.common.NameDialog
import io.github.saalfy.sur.ui.common.SearchableTopBar
import androidx.compose.runtime.saveable.rememberSaveable

private sealed interface PlaylistsDialog {
    data object Create : PlaylistsDialog
    data class Rename(val playlist: Playlist) : PlaylistsDialog
    data class Delete(val playlist: Playlist) : PlaylistsDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onOpen: (playlistId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = viewModel(factory = PlaylistsViewModel.Factory),
) {
    val playlists by viewModel.visiblePlaylists.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<PlaylistsDialog?>(null) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SearchableTopBar(
                title = stringResource(R.string.tab_playlists),
                query = query,
                onQueryChange = viewModel::onQueryChange,
                searching = searching,
                onSearchingChange = { searching = it },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = PlaylistsDialog.Create }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.new_playlist))
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val list = playlists
            when {
                list == null -> Unit
                list.isEmpty() && query.isNotBlank() -> CenteredMessage(stringResource(R.string.no_results, query.trim()))
                list.isEmpty() -> CenteredMessage(stringResource(R.string.no_playlists))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.playlist.id }) { summary ->
                        PlaylistRow(
                            summary = summary,
                            onClick = { onOpen(summary.playlist.id) },
                            onRename = { dialog = PlaylistsDialog.Rename(summary.playlist) },
                            onDelete = { dialog = PlaylistsDialog.Delete(summary.playlist) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        PlaylistsDialog.Create -> NameDialog(
            title = stringResource(R.string.new_playlist),
            confirmLabel = stringResource(R.string.create),
            onConfirm = { viewModel.create(it); dialog = null },
            onDismiss = { dialog = null },
        )
        is PlaylistsDialog.Rename -> NameDialog(
            title = stringResource(R.string.rename_playlist),
            confirmLabel = stringResource(R.string.rename),
            initialName = d.playlist.name,
            onConfirm = { viewModel.rename(d.playlist.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is PlaylistsDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.delete_playlist),
            message = stringResource(R.string.delete_playlist_message, d.playlist.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { viewModel.delete(d.playlist.id); dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun PlaylistRow(
    summary: PlaylistSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(summary.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.song_count, summary.songCount, summary.songCount))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
    )
}
