package io.github.saalfy.sur.ui.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.ui.AboutDialog
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import io.github.saalfy.sur.data.MediaStoreUris
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.ui.artwork.Artwork
import io.github.saalfy.sur.ui.artwork.ArtworkLoader
import io.github.saalfy.sur.ui.artwork.ThumbnailSize
import io.github.saalfy.sur.ui.artwork.rememberArtworkLoader
import io.github.saalfy.sur.data.playlist.Playlist
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import io.github.saalfy.sur.ui.common.CenteredMessage
import io.github.saalfy.sur.ui.common.ConfirmDialog
import io.github.saalfy.sur.ui.common.NameDialog
import io.github.saalfy.sur.ui.common.SearchableTopBar

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
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val loader = rememberArtworkLoader()
    val thumbnailPx = with(LocalDensity.current) { ThumbnailSize.roundToPx() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<PlaylistsDialog?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    val m3uMimeTypes = remember {
        arrayOf(
            "audio/x-mpegurl",
            "audio/mpegurl",
            "application/vnd.apple.mpegurl",
            "text/plain",
            "*/*",
        )
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importFromUri(uri) }

    val currentImportResult = importResult
    if (currentImportResult != null) {
        val message = when (currentImportResult) {
            is ImportResult.Success -> stringResource(
                R.string.import_m3u_success,
                currentImportResult.name,
                currentImportResult.added,
                currentImportResult.notFound,
            )
            ImportResult.NothingMatched -> stringResource(R.string.import_m3u_nothing_matched)
            ImportResult.FileTooLarge -> stringResource(R.string.import_m3u_file_too_large)
        }
        AlertDialog(
            onDismissRequest = viewModel::clearImportResult,
            confirmButton = {
                TextButton(onClick = viewModel::clearImportResult) {
                    Text(stringResource(R.string.ok))
                }
            },
            text = { Text(message) },
        )
    }

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
                actions = {
                    IconButton(onClick = { showAbout = true }) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.about))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val list = playlists
            when {
                list == null -> Unit
                list.isEmpty() && query.isNotBlank() -> CenteredMessage(stringResource(R.string.no_results, query.trim()))
                list.isEmpty() -> EmptyPlaylists(
                    onCreate = { dialog = PlaylistsDialog.Create },
                    onImport = { importLauncher.launch(m3uMimeTypes) },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { dialog = PlaylistsDialog.Create },
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_add),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.new_playlist))
                            }
                            FilledTonalButton(
                                onClick = { importLauncher.launch(m3uMimeTypes) },
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_download),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.import_playlist))
                            }
                        }
                    }
                    items(list, key = { it.playlist.id }, contentType = { "playlist" }) { summary ->
                        PlaylistCard(
                            summary = summary,
                            loader = loader,
                            thumbnailPx = thumbnailPx,
                            modifier = Modifier.animateItem(),
                            onClick = { onOpen(summary.playlist.id) },
                            onRename = { dialog = PlaylistsDialog.Rename(summary.playlist) },
                            onDelete = { dialog = PlaylistsDialog.Delete(summary.playlist) },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
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
private fun EmptyPlaylists(onCreate: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_queue_music),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(stringResource(R.string.no_playlists), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.no_playlists_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Button(onClick = onCreate) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.create_playlist))
            }
            FilledTonalButton(onClick = onImport) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_playlist))
            }
        }
    }
}

private val CoverSize = 56.dp

@Composable
private fun PlaylistCard(
    summary: PlaylistSummary,
    loader: ArtworkLoader,
    thumbnailPx: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverMosaic(summary.coverSongs, loader, thumbnailPx)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    text = summary.playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.song_count, summary.songCount, summary.songCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
        }
    }
}

/**
 * Cover built from the playlist's first songs: none → icon tile, one → that song's art,
 * two or more → 2x2 grid with plain tiles for empty slots. Reuses the shared thumbnail cache.
 */
@Composable
private fun CoverMosaic(songs: List<Song>, loader: ArtworkLoader, thumbnailPx: Int) {
    val shape = RoundedCornerShape(12.dp)
    val modifier = Modifier.size(CoverSize)
    when (songs.size) {
        0 -> Box(
            modifier.clip(shape).background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_queue_music),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        1 -> MosaicTile(songs[0], loader, thumbnailPx, shape, modifier)
        else -> Column(modifier.clip(shape)) {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (column in 0 until 2) {
                        val tile = Modifier.weight(1f).fillMaxHeight()
                        val song = songs.getOrNull(row * 2 + column)
                        if (song != null) {
                            MosaicTile(song, loader, thumbnailPx, RectangleShape, tile)
                        } else {
                            Box(tile.background(MaterialTheme.colorScheme.surfaceContainerHighest))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MosaicTile(song: Song, loader: ArtworkLoader, thumbnailPx: Int, shape: Shape, modifier: Modifier) {
    Artwork(
        songId = song.id,
        artworkUri = MediaStoreUris.albumArt(song.albumId),
        size = ThumbnailSize,
        shape = shape,
        loader = loader,
        sizePx = thumbnailPx,
        modifier = modifier,
    )
}
