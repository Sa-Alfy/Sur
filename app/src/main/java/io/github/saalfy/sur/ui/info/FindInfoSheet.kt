package io.github.saalfy.sur.ui.info

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.saalfy.sur.R
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.info.cleaner.CandidateRanker
import io.github.saalfy.sur.data.info.lookup.Candidate

private const val MAX_OTHER_MATCHES = 5
private val MatchCoverSize = 96.dp

/** Single vertical column; every button is full width and stacked, so large fonts never squash a row. */
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
    var showOthers by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startAutoSearch()
    }

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

            val searchField: @Composable () -> Unit = {
                SearchYourselfField(
                    text = uiState.freeTextInput,
                    onTextChange = viewModel::onFreeTextChanged,
                    onSearch = {
                        showOthers = false
                        viewModel.searchFreeText()
                    },
                    enabled = uiState.searchState !is SearchState.Searching,
                )
            }

            when (val search = uiState.searchState) {
                SearchState.Idle, SearchState.Searching -> Searching()

                SearchState.Empty -> {
                    Text(
                        text = stringResource(R.string.find_info_not_found),
                        style = MaterialTheme.typography.titleMedium,
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
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.find_info_check_title_spelling),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    searchField()
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
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    searchField()
                }

                SearchState.Success -> {
                    val match = uiState.selectedCandidate ?: uiState.candidates.firstOrNull()
                    if (match != null) {
                        MatchCard(
                            candidate = match,
                            coverState = uiState.coverState,
                            songDurationMs = song.durationMs,
                        )
                        Text(
                            text = if (song.artist.isNullOrBlank()) {
                                stringResource(R.string.find_info_now_title_only, song.title)
                            } else {
                                stringResource(R.string.find_info_now, song.title, song.artist)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )

                        Button(
                            onClick = { viewModel.apply(onDismiss) },
                            enabled = uiState.coverState !is CoverState.Loading && !uiState.isApplying,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (uiState.isApplying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(stringResource(R.string.find_info_use_match))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.applyCoverOnly(onDismiss) },
                            enabled = uiState.coverState is CoverState.Success && !uiState.isApplying,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.find_info_cover_only))
                        }
                        if (uiState.coverState is CoverState.NotFound) {
                            Text(
                                text = stringResource(R.string.find_info_no_cover_for_match),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        TextButton(
                            onClick = { showOthers = !showOthers },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.find_info_not_this_one))
                        }

                        if (showOthers) {
                            val others = uiState.candidates
                                .filter { it.recordingId != match.recordingId }
                                .take(MAX_OTHER_MATCHES)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                others.forEach { candidate ->
                                    OtherMatchRow(
                                        candidate = candidate,
                                        onClick = { viewModel.selectCandidate(candidate) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            searchField()
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (uiState.hasExistingApplied) {
                TextButton(
                    onClick = { viewModel.removeFoundInfo(onDismiss) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.find_info_remove))
                }
            }

            TextButton(
                onClick = {
                    viewModel.cancelWork()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun Searching() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Text(
            text = stringResource(R.string.find_info_searching),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun MatchCard(
    candidate: Candidate,
    coverState: CoverState,
    songDurationMs: Long,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            MatchCover(coverState)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidate.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                albumYear(candidate)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CandidateRanker.durationNote(candidate.durationMs, songDurationMs)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchCover(coverState: CoverState) {
    Box(
        modifier = Modifier
            .size(MatchCoverSize)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = (coverState as? CoverState.Success)?.let { success ->
            remember(success.bytes) {
                try {
                    BitmapFactory.decodeByteArray(success.bytes, 0, success.bytes.size)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        }
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.find_info_cover_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            coverState is CoverState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OtherMatchRow(candidate: Candidate, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            albumYear(candidate)?.let {
                Text(
                    text = it,
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
private fun SearchYourselfField(
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    enabled: Boolean,
) {
    val canSearch = enabled && text.any { it.isLetterOrDigit() }
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(stringResource(R.string.find_info_search_yourself)) },
        placeholder = { Text(stringResource(R.string.find_info_search_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (canSearch) onSearch() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onSearch,
        enabled = canSearch,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.search))
    }
}

/** "album · year", either part alone, or null if both are missing. */
private fun albumYear(candidate: Candidate): String? =
    listOfNotNull(
        candidate.releaseTitle?.takeIf { it.isNotBlank() },
        candidate.year?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifEmpty { null }
