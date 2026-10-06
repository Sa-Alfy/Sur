package io.github.saalfy.sur.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.saalfy.sur.R

private val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private enum class AudioAccess { Unchecked, Granted, Denied, PermanentlyDenied }

/** Shows [content] only once audio access is granted; otherwise explains and offers a fix. */
@Composable
fun AudioPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var access by rememberSaveable { mutableStateOf(AudioAccess.Unchecked) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = when {
            granted -> AudioAccess.Granted
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, AUDIO_PERMISSION) ->
                AudioAccess.PermanentlyDenied
            else -> AudioAccess.Denied
        }
    }

    // Re-check on every resume so returning from Settings picks up the change.
    LifecycleResumeEffect(Unit) {
        access = when {
            context.hasPermission(AUDIO_PERMISSION) -> AudioAccess.Granted
            access == AudioAccess.PermanentlyDenied -> AudioAccess.PermanentlyDenied
            else -> AudioAccess.Denied
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(access) {
        if (access == AudioAccess.Denied && !askedOnce) {
            askedOnce = true
            launcher.launch(AUDIO_PERMISSION)
        }
    }

    when (access) {
        AudioAccess.Unchecked -> Surface(Modifier.fillMaxSize()) { }
        AudioAccess.Granted -> content()
        AudioAccess.Denied, AudioAccess.PermanentlyDenied -> {
            val permanent = access == AudioAccess.PermanentlyDenied
            Surface(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(
                            if (permanent) R.string.permission_permanently_denied else R.string.permission_rationale,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (permanent) {
                        Button(onClick = { context.openAppSettings() }) { Text(stringResource(R.string.open_settings)) }
                    } else {
                        Button(onClick = { launcher.launch(AUDIO_PERMISSION) }) { Text(stringResource(R.string.grant_access)) }
                    }
                }
            }
        }
    }
}

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
