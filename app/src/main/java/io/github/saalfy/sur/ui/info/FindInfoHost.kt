package io.github.saalfy.sur.ui.info

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.saalfy.sur.R
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.SongRepository
import kotlinx.coroutines.flow.first

/** What the Find info host shows for a request. */
enum class FindInfoStep { None, Consent, Sheet }

/** The sheet (and so the network) is only reachable once consent was given. */
fun findInfoStep(requested: Boolean, consentGiven: Boolean): FindInfoStep = when {
    !requested -> FindInfoStep.None
    consentGiven -> FindInfoStep.Sheet
    else -> FindInfoStep.Consent
}

/** Looks up the library entry (with any applied override) for a playing item. */
suspend fun findSongById(repository: SongRepository, songId: Long): Song? =
    repository.songs.first().firstOrNull { it.id == songId }

/**
 * Shared Find info entry: shows the one-time consent dialog, then the sheet, for [song].
 * Callers keep the requested song in their own state and clear it in [onFinished].
 */
@Composable
fun FindInfoHost(song: Song?, onFinished: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { (context.applicationContext as SurApplication).container.preferences }
    // Re-read per request: consent may have been given from another screen
    var consentGiven by remember(song) { mutableStateOf(preferences.findInfoConsent) }

    when (findInfoStep(requested = song != null, consentGiven = consentGiven)) {
        FindInfoStep.None -> Unit
        FindInfoStep.Consent -> AlertDialog(
            onDismissRequest = onFinished,
            title = { Text(stringResource(R.string.find_info)) },
            text = { Text(stringResource(R.string.find_info_consent_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        preferences.findInfoConsent = true
                        consentGiven = true
                    },
                ) {
                    Text(stringResource(R.string.allow))
                }
            },
            dismissButton = {
                TextButton(onClick = onFinished) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        FindInfoStep.Sheet -> if (song != null) {
            FindInfoSheet(song = song, onDismiss = onFinished)
        }
    }
}
