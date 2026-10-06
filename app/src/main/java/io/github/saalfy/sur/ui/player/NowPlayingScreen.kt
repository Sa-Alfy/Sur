package io.github.saalfy.sur.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.saalfy.sur.R
import io.github.saalfy.sur.playback.PlayerUiState
import io.github.saalfy.sur.playback.PositionTicker
import io.github.saalfy.sur.playback.songId
import io.github.saalfy.sur.ui.artwork.Artwork
import io.github.saalfy.sur.ui.common.formatDuration

private val LargeArtSize = 360.dp

/** Full-screen overlay. [state] is null while the controller (re)connects, e.g. right after rotation. */
@Composable
fun NowPlayingScreen(state: PlayerUiState?, onClose: () -> Unit) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (showQueue) showQueue = false else onClose() }

    // Surface also blocks touches from reaching the screen underneath.
    Surface(Modifier.fillMaxSize()) {
        if (state == null || state.currentItem == null) return@Surface
        PositionTicker(state, intervalMs = 500)

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_expand_more), contentDescription = stringResource(R.string.close_player))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = !showQueue }) {
                    Icon(
                        painterResource(R.drawable.ic_queue_music),
                        contentDescription = stringResource(R.string.queue),
                        tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val landscape = maxWidth > maxHeight
                val main: @Composable (Modifier) -> Unit = { modifier ->
                    if (showQueue) QueueList(state, modifier) else ArtPane(state, modifier)
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        main(Modifier.weight(1f).fillMaxSize())
                        PlaybackPanel(state, Modifier.weight(1f).fillMaxSize())
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                        main(Modifier.weight(1f).fillMaxWidth())
                        PlaybackPanel(state, Modifier.fillMaxWidth().padding(bottom = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtPane(state: PlayerUiState, modifier: Modifier) {
    val item = state.currentItem ?: return
    Box(modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Artwork(
            songId = item.songId,
            artworkUri = item.mediaMetadata.artworkUri,
            size = LargeArtSize,
            modifier = Modifier.widthIn(max = LargeArtSize).fillMaxWidth().aspectRatio(1f),
        )
    }
}

@Composable
private fun PlaybackPanel(state: PlayerUiState, modifier: Modifier) {
    val item = state.currentItem ?: return
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(
            text = item.mediaMetadata.title?.toString().orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = item.mediaMetadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        )
        SeekBar(state)
        Controls(state)
    }
}

@Composable
private fun SeekBar(state: PlayerUiState) {
    // While dragging, show the thumb position instead of the polled position (no jitter).
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: state.positionMs

    Slider(
        value = dragFraction ?: state.progress,
        onValueChange = { dragFraction = it },
        onValueChangeFinished = {
            dragFraction?.let { state.seekTo((it * duration).toLong()) }
            dragFraction = null
        },
        enabled = duration > 0,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatDuration(shownPosition), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(duration), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Controls(state: PlayerUiState) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = state::toggleShuffle) {
            Icon(
                painterResource(R.drawable.ic_shuffle),
                contentDescription = stringResource(if (state.shuffleEnabled) R.string.shuffle_on else R.string.shuffle_off),
                tint = if (state.shuffleEnabled) active else inactive,
            )
        }
        IconButton(onClick = state::previous) {
            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = stringResource(R.string.previous))
        }
        FilledIconButton(onClick = state::playPause, modifier = Modifier.size(64.dp)) {
            Icon(
                painterResource(if (state.showPlayButton) R.drawable.ic_play else R.drawable.ic_pause),
                contentDescription = stringResource(if (state.showPlayButton) R.string.play else R.string.pause),
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = state::next, enabled = state.hasNext) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.next))
        }
        IconButton(onClick = state::cycleRepeatMode) {
            val (icon, label) = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one to R.string.repeat_one
                Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat to R.string.repeat_all
                else -> R.drawable.ic_repeat to R.string.repeat_off
            }
            Icon(
                painterResource(icon),
                contentDescription = stringResource(label),
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) inactive else active,
            )
        }
    }
}

@Composable
private fun QueueList(state: PlayerUiState, modifier: Modifier) {
    val queue = state.queue
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = queue.indexOfFirst { it.windowIndex == state.currentIndex }.coerceAtLeast(0),
    )
    val unknownArtist = stringResource(R.string.unknown_artist)
    LazyColumn(state = listState, modifier = modifier) {
        items(queue, key = { it.windowIndex }) { entry ->
            val current = entry.windowIndex == state.currentIndex
            ListItem(
                modifier = Modifier.clickable { state.jumpTo(entry.windowIndex) },
                colors = ListItemDefaults.colors(
                    containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                ),
                headlineContent = {
                    Text(
                        entry.mediaItem.mediaMetadata.title?.toString().orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        entry.mediaItem.mediaMetadata.artist?.toString() ?: unknownArtist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
