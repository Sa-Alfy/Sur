package io.github.saalfy.sur.ui.info

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.info.cleaner.CandidateRanker
import io.github.saalfy.sur.data.info.lookup.Candidate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindInfoSheet(
    song: Song,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FindInfoViewModel = viewModel(
        key = "FindInfo_${song.id}",
        factory = FindInfoViewModel.factory(song, LocalContext.current),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelWork()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.cancelWork()
            onDismiss()
        },
        sheetState = sheetState,
        modifier = modifier,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding(),
        ) {
            Text(
                text = stringResource(R.string.find_info),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Query fields
            OutlinedTextField(
                value = uiState.titleInput,
                onValueChange = viewModel::onTitleChanged,
                label = { Text(stringResource(R.string.find_info_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.artistInput,
                onValueChange = viewModel::onArtistChanged,
                label = { Text(stringResource(R.string.find_info_artist)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))

            // "Try another query" left, Search button right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (uiState.variants.size > 1) {
                    TextButton(onClick = viewModel::nextVariant) {
                        Text(stringResource(R.string.find_info_try_another_query))
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                Button(
                    onClick = viewModel::search,
                    enabled = uiState.searchState !is SearchState.Searching && uiState.titleInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.search))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Status / Results Area
            when (val search = uiState.searchState) {
                SearchState.Idle -> {
                    // Waiting for user to tap Search
                }

                SearchState.Searching -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.find_info_searching),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                SearchState.Empty -> {
                    Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        Text(
                            text = stringResource(R.string.find_info_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                if (uiState.searchTriedArtist) {
                                    R.string.find_info_tried_title_artist_then_title
                                } else {
                                    R.string.find_info_tried_title_only
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.find_info_check_title_spelling),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is SearchState.Error -> {
                    val message = when (search) {
                        SearchState.Error.Busy -> stringResource(R.string.find_info_err_busy)
                        SearchState.Error.Network -> stringResource(R.string.find_info_err_network)
                        is SearchState.Error.Http -> stringResource(R.string.find_info_err_http, search.code)
                        is SearchState.Error.Generic -> search.message ?: stringResource(R.string.find_info_err_generic)
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                SearchState.Success -> {
                    Text(
                        text = stringResource(R.string.find_info_match_count, uiState.candidates.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // Plain Column — no nested scroll conflict with the outer verticalScroll
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.candidates.forEach { candidate ->
                            CandidateItem(
                                candidate = candidate,
                                songDurationMs = song.durationMs,
                                isSelected = uiState.selectedCandidate?.recordingId == candidate.recordingId,
                                onClick = { viewModel.selectCandidate(candidate) },
                            )
                        }
                    }
                }
            }

            // Cover Preview Section (shown when candidate selected)
            if (uiState.selectedCandidate != null) {
                Spacer(modifier = Modifier.height(12.dp))
                CoverPreviewSection(
                    coverState = uiState.coverState,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.cancelWork()
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.close))
                }

                if (uiState.hasExistingApplied) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { viewModel.removeFoundInfo(onDismiss) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.find_info_remove))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = { viewModel.skip(onDismiss) },
                ) {
                    Text(stringResource(R.string.find_info_skip))
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { viewModel.apply(onDismiss) },
                    enabled = uiState.selectedCandidate != null &&
                        uiState.coverState !is CoverState.Loading &&
                        !uiState.isApplying,
                ) {
                    if (uiState.isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.find_info_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateItem(
    candidate: Candidate,
    songDurationMs: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!candidate.year.isNullOrBlank()) {
                    Text(
                        text = candidate.year,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Text(
                text = candidate.artist,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (!candidate.releaseTitle.isNullOrBlank()) {
                Text(
                    text = candidate.releaseTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val durationNote = CandidateRanker.durationNote(candidate.durationMs, songDurationMs)
            if (durationNote != null) {
                Text(
                    text = durationNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CoverPreviewSection(
    coverState: CoverState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (coverState) {
                CoverState.Idle -> {
                    // Not started
                }

                CoverState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.find_info_loading_cover),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is CoverState.Success -> {
                    val bitmap = remember(coverState.bytes) {
                        try {
                            BitmapFactory.decodeByteArray(coverState.bytes, 0, coverState.bytes.size)
                                ?.asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.find_info_cover_preview),
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.find_info_cover_found),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.find_info_cover_saved_locally),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.find_info_no_cover),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CoverState.NotFound -> {
                    Text(
                        text = stringResource(R.string.find_info_no_cover),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
