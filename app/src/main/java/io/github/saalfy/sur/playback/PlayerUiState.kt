package io.github.saalfy.sur.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.delay

data class QueueEntry(val windowIndex: Int, val mediaItem: MediaItem)

/**
 * Compose-observable mirror of a [Player] (our MediaController). Holds no state of its own:
 * every field is re-read from the player on its events, and actions go straight to the player.
 */
@Stable
class PlayerUiState internal constructor(private val player: Player) {
    var currentItem by mutableStateOf<MediaItem?>(null); private set
    var currentIndex by mutableIntStateOf(C.INDEX_UNSET); private set
    var isPlaying by mutableStateOf(false); private set
    var showPlayButton by mutableStateOf(true); private set
    var hasNext by mutableStateOf(false); private set
    var shuffleEnabled by mutableStateOf(false); private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF); private set
    var durationMs by mutableLongStateOf(0L); private set
    var positionMs by mutableLongStateOf(0L); private set

    /** Queue in play order (follows shuffle). */
    var queue by mutableStateOf<List<QueueEntry>>(emptyList()); private set

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    internal fun refresh(rebuildQueue: Boolean) {
        currentItem = player.currentMediaItem
        currentIndex = player.currentMediaItemIndex
        isPlaying = player.isPlaying
        showPlayButton = !player.playWhenReady ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        hasNext = player.hasNextMediaItem()
        shuffleEnabled = player.shuffleModeEnabled
        repeatMode = player.repeatMode
        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L
        updatePosition()
        if (rebuildQueue) queue = buildQueue()
    }

    fun updatePosition() {
        positionMs = player.currentPosition.coerceAtLeast(0)
    }

    fun playPause() {
        if (showPlayButton) {
            when (player.playbackState) {
                Player.STATE_IDLE -> player.prepare()
                Player.STATE_ENDED -> player.seekToDefaultPosition()
            }
            player.play()
        } else {
            player.pause()
        }
    }

    fun next() = player.seekToNext()

    fun previous() = player.seekToPrevious()

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        this.positionMs = positionMs
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun jumpTo(windowIndex: Int) {
        player.seekToDefaultPosition(windowIndex)
        player.play()
    }

    private fun buildQueue(): List<QueueEntry> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        val window = Timeline.Window()
        val entries = ArrayList<QueueEntry>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET) {
            entries += QueueEntry(index, timeline.getWindow(index, window).mediaItem)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return entries
    }
}

@Composable
fun rememberPlayerUiState(player: Player): PlayerUiState {
    val state = remember(player) { PlayerUiState(player).also { it.refresh(rebuildQueue = true) } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state.refresh(
                    rebuildQueue = events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    ),
                )
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return state
}

/** Polls the position only while this is composed (screen visible) and the player is playing. */
@Composable
fun PositionTicker(state: PlayerUiState, intervalMs: Long) {
    LaunchedEffect(state, state.isPlaying) {
        while (state.isPlaying) {
            state.updatePosition()
            delay(intervalMs)
        }
    }
}
