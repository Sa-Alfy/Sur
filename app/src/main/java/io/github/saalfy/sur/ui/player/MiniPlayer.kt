package io.github.saalfy.sur.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.saalfy.sur.R
import io.github.saalfy.sur.playback.PlayerUiState
import io.github.saalfy.sur.playback.PositionTicker
import io.github.saalfy.sur.playback.songId
import io.github.saalfy.sur.ui.artwork.Artwork

private val MiniArtSize = 44.dp

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val item = state.currentItem ?: return
    PositionTicker(state, intervalMs = 1_000)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    songId = item.songId,
                    artworkUri = item.mediaMetadata.artworkUri,
                    size = MiniArtSize,
                    modifier = Modifier.size(MiniArtSize),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = item.mediaMetadata.title?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.mediaMetadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = state::playPause) {
                    Icon(
                        painter = painterResource(if (state.showPlayButton) R.drawable.ic_play else R.drawable.ic_pause),
                        contentDescription = stringResource(if (state.showPlayButton) R.string.play else R.string.pause),
                    )
                }
            }
        }
    }
}
