package io.github.saalfy.sur.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.saalfy.sur.R
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.playback.PlayerUiState
import io.github.saalfy.sur.playback.PositionTicker
import io.github.saalfy.sur.playback.songId
import io.github.saalfy.sur.ui.artwork.Artwork
import io.github.saalfy.sur.ui.artwork.ArtworkPlaceholderIcon
import io.github.saalfy.sur.ui.artwork.ThumbnailSize
import io.github.saalfy.sur.ui.artwork.rememberArtworkLoader
import io.github.saalfy.sur.ui.common.formatDuration
import io.github.saalfy.sur.ui.info.FindInfoHost
import io.github.saalfy.sur.ui.info.findSongById
import kotlinx.coroutines.launch

private val LargeArtSize = 360.dp
private val ArtShape = RoundedCornerShape(24.dp)
private const val ACCENT_ANIM_MS = 450

/** Full-screen overlay. [state] is null while the controller (re)connects, e.g. right after rotation. */
@Composable
fun NowPlayingScreen(state: PlayerUiState?, onClose: () -> Unit) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var findInfoSong by remember { mutableStateOf<Song?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onFindInfo: (Long) -> Unit = { songId ->
        scope.launch {
            val repository = (context.applicationContext as SurApplication).container.songRepository
            findInfoSong = findSongById(repository, songId)
        }
    }
    BackHandler { if (showQueue) showQueue = false else onClose() }

    // Art-driven accent. The animated States are passed down and only read by the few elements
    // they color (background draw, seek bar, play button, active toggles), not by this screen.
    val item = state?.currentItem
    val scheme = MaterialTheme.colorScheme
    val loader = rememberArtworkLoader()
    val thumbnailPx = with(LocalDensity.current) { ThumbnailSize.roundToPx() }
    val seed by rememberArtSeed(item?.songId, item?.mediaMetadata?.artworkUri, loader, thumbnailPx)
    val target = remember(seed, scheme) { accentColorsFor(seed, scheme) }
    val accent = animateColorAsState(target.accent, tween(ACCENT_ANIM_MS), label = "accent")
    val onAccent = animateColorAsState(target.onAccent, tween(ACCENT_ANIM_MS), label = "onAccent")
    val tint = animateColorAsState(target.backgroundTint, tween(ACCENT_ANIM_MS), label = "tint")

    // Surface also blocks touches from reaching the screen underneath.
    Surface(Modifier.fillMaxSize()) {
        if (state == null || item == null) return@Surface
        PositionTicker(state, intervalMs = 500)
        val surface = scheme.surface

        Column(
            Modifier
                .fillMaxSize()
                // Draw-phase read: color animation never recomposes the screen. No blur (API 31+).
                .drawWithCache {
                    val brush = Brush.verticalGradient(0f to tint.value, 0.7f to surface)
                    onDrawBehind { drawRect(brush) }
                }
                .safeDrawingPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    Crossfade(targetState = showQueue, modifier = modifier, label = "artOrQueue") { queue ->
                        if (queue) QueueList(state, Modifier.fillMaxSize()) else ArtPane(state, Modifier.fillMaxSize(), onFindInfo)
                    }
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        main(Modifier.weight(1f).fillMaxSize())
                        PlaybackPanel(
                            state, accent, onAccent,
                            Modifier.weight(1f).fillMaxSize().padding(start = 16.dp),
                            onOpenQueue = null,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                        main(Modifier.weight(1f).fillMaxWidth())
                        PlaybackPanel(
                            state, accent, onAccent,
                            Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            onOpenQueue = { showQueue = true },
                        )
                    }
                }
            }
        }
        FindInfoHost(song = findInfoSong, onFinished = { findInfoSong = null })
    }
}

@Composable
private fun ArtPane(state: PlayerUiState, modifier: Modifier, onFindInfo: (Long) -> Unit) {
    val item = state.currentItem ?: return
    BoxWithConstraints(modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight, LargeArtSize)
        if (side > 0.dp) {
            // Soft elevation shadow (RenderNode, fine on API 26); artwork crossfades on song change.
            Box(Modifier.size(side).shadow(16.dp, ArtShape)) {
                Crossfade(targetState = item.songId to item.mediaMetadata.artworkUri, label = "artwork") { (id, uri) ->
                    Artwork(
                        songId = id,
                        artworkUri = uri,
                        size = side,
                        shape = ArtShape,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = {
                            // No art: offer Find cover & details right on the placeholder
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp),
                            ) {
                                ArtworkPlaceholderIcon()
                                if (id != null) {
                                    FilledTonalButton(
                                        onClick = { onFindInfo(id) },
                                        modifier = Modifier.padding(top = 16.dp),
                                    ) {
                                        Text(stringResource(R.string.find_info))
                                    }
                                }
                            }
                        },
                        loadingPlaceholder = { ArtworkPlaceholderIcon() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackPanel(
    state: PlayerUiState,
    accent: State<Color>,
    onAccent: State<Color>,
    modifier: Modifier,
    /** Non-null shows the "Up next" card, which opens the queue. */
    onOpenQueue: (() -> Unit)?,
) {
    val item = state.currentItem ?: return
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(
            text = item.mediaMetadata.title?.toString().orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.mediaMetadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        SeekBar(state, accent)
        Controls(state, accent, onAccent)

        if (onOpenQueue != null) {
            UpNextCard(state, onOpenQueue)
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, accent: State<Color>) {
    // While dragging, show the thumb position instead of the polled position (no jitter).
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: state.positionMs
    val color = accent.value

    Slider(
        value = dragFraction ?: state.progress,
        onValueChange = { dragFraction = it },
        onValueChangeFinished = {
            dragFraction?.let { state.seekTo((it * duration).toLong()) }
            dragFraction = null
        },
        enabled = duration > 0,
        colors = SliderDefaults.colors(
            thumbColor = color,
            activeTrackColor = color,
            inactiveTrackColor = color.copy(alpha = 0.24f),
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(formatDuration(shownPosition), style = MaterialTheme.typography.labelMedium, color = timeColor)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(duration), style = MaterialTheme.typography.labelMedium, color = timeColor)
    }
}

@Composable
private fun Controls(state: PlayerUiState, accent: State<Color>, onAccent: State<Color>) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val main = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = state::toggleShuffle) {
            Icon(
                painterResource(R.drawable.ic_shuffle),
                contentDescription = stringResource(if (state.shuffleEnabled) R.string.shuffle_on else R.string.shuffle_off),
                tint = if (state.shuffleEnabled) accent.value else quiet,
            )
        }
        IconButton(onClick = state::previous, modifier = Modifier.size(56.dp)) {
            Icon(
                painterResource(R.drawable.ic_skip_previous),
                contentDescription = stringResource(R.string.previous),
                tint = main,
                modifier = Modifier.size(32.dp),
            )
        }
        PlayPauseButton(state, accent, onAccent)
        IconButton(onClick = state::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(
                painterResource(R.drawable.ic_skip_next),
                contentDescription = stringResource(R.string.next),
                tint = if (state.hasNext) main else main.copy(alpha = 0.38f),
                modifier = Modifier.size(32.dp),
            )
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
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) quiet else accent.value,
            )
        }
    }
}

@Composable
private fun PlayPauseButton(state: PlayerUiState, accent: State<Color>, onAccent: State<Color>) {
    FilledIconButton(
        onClick = state::playPause,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = accent.value,
            contentColor = onAccent.value,
        ),
    ) {
        AnimatedContent(
            targetState = state.showPlayButton,
            transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.7f)) togetherWith fadeOut(tween(90))
            },
            label = "playPause",
        ) { showPlay ->
            Icon(
                painterResource(if (showPlay) R.drawable.ic_play else R.drawable.ic_pause),
                contentDescription = stringResource(if (showPlay) R.string.play else R.string.pause),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** Next song in play order (follows shuffle). Tapping opens the queue. */
@Composable
private fun UpNextCard(state: PlayerUiState, onOpenQueue: () -> Unit) {
    val queue = state.queue
    val position = queue.indexOfFirst { it.windowIndex == state.currentIndex }
    if (position < 0) return
    val next = queue.getOrNull(position + 1)?.mediaItem ?: return

    Surface(
        onClick = onOpenQueue,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                songId = next.songId,
                artworkUri = next.mediaMetadata.artworkUri,
                size = ThumbnailSize,
                modifier = Modifier.size(40.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(R.string.up_next),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = next.mediaMetadata.title?.toString().orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = next.mediaMetadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    BoxWithConstraints(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
        ) {
            items(queue, key = { it.windowIndex }, contentType = { "queue" }) { entry ->
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
}
