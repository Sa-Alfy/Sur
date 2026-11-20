package io.github.saalfy.sur.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.saalfy.sur.R
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.ui.AboutDialog
import io.github.saalfy.sur.update.ApkInstaller
import io.github.saalfy.sur.update.CheckError
import io.github.saalfy.sur.update.DownloadProblem
import io.github.saalfy.sur.update.InstallStep
import io.github.saalfy.sur.update.InstallerOutcome
import io.github.saalfy.sur.update.ManifestRejectedException
import io.github.saalfy.sur.update.NetworkFailure
import io.github.saalfy.sur.update.UpdateManager
import io.github.saalfy.sur.update.UpdateManifest
import io.github.saalfy.sur.update.UpdateStatus
import kotlinx.coroutines.launch
import java.util.Locale

private const val RELEASES_URL = "https://github.com/Sa-Alfy/Sur/releases"

@Composable
fun rememberUpdateManager(): UpdateManager {
    val context = LocalContext.current
    return remember { (context.applicationContext as SurApplication).container.updateManager }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var showAbout by rememberSaveable { mutableStateOf(false) }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    Scaffold(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.update_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            UpdateSection()
            HorizontalDivider()
            TextButton(onClick = { showAbout = true }) { Text(stringResource(R.string.about)) }
        }
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val manager = rememberUpdateManager()
    val status by manager.status.collectAsStateWithLifecycle()
    val checkError by manager.checkError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { manager.recheckInstallPermission() }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        manager.onInstallerResult(ApkInstaller.outcome(result.resultCode))
    }
    fun install() {
        scope.launch {
            val step = manager.prepareInstall()
            if (step is InstallStep.Launch) {
                try {
                    installer.launch(ApkInstaller.installIntent(context, step.file))
                } catch (_: ActivityNotFoundException) {
                    manager.onInstallerResult(InstallerOutcome.Failed)
                } catch (_: IllegalArgumentException) {
                    manager.onInstallerResult(InstallerOutcome.Failed)
                }
            }
        }
    }

    Text(stringResource(R.string.update_installed_version, manager.installedVersionName))

    when (val s = status) {
        UpdateStatus.Idle -> Unit
        UpdateStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.update_checking))
        }
        UpdateStatus.UpToDate -> Text(stringResource(R.string.update_up_to_date))
        is UpdateStatus.Available -> {
            ReleaseInfo(s.manifest)
            Button(onClick = { manager.startDownload() }) { Text(stringResource(R.string.update_download)) }
        }
        is UpdateStatus.InstalledTooOld -> {
            ReleaseInfo(s.manifest)
            Message(stringResource(R.string.update_too_old))
            OutlinedButton(onClick = { context.openUrl(RELEASES_URL) }) { Text(stringResource(R.string.update_open_releases)) }
        }
        is UpdateStatus.DeviceUnsupported -> {
            ReleaseInfo(s.manifest)
            Message(stringResource(R.string.update_device_unsupported, s.manifest.minSdk))
        }
        is UpdateStatus.Downloading -> {
            ReleaseInfo(s.manifest, showNotes = false)
            val fraction = if (s.total > 0) (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_downloading, mb(s.bytes), mb(s.total)))
            OutlinedButton(onClick = { manager.cancelDownload() }) { Text(stringResource(R.string.cancel)) }
        }
        is UpdateStatus.DownloadInterrupted -> {
            ReleaseInfo(s.manifest, showNotes = false)
            Message(stringResource(R.string.update_download_interrupted, networkText(s.failure)), error = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { manager.startDownload() }) { Text(stringResource(R.string.update_resume)) }
                OutlinedButton(onClick = { manager.cancelDownload() }) { Text(stringResource(R.string.cancel)) }
            }
        }
        is UpdateStatus.DownloadFailed -> {
            ReleaseInfo(s.manifest, showNotes = false)
            Message(problemText(s.problem), error = true)
            Button(onClick = { manager.startDownload() }) { Text(stringResource(R.string.retry)) }
        }
        is UpdateStatus.ReadyToInstall -> {
            ReleaseInfo(s.manifest)
            Message(stringResource(R.string.update_verified))
            Button(onClick = ::install) { Text(stringResource(R.string.update_install)) }
        }
        is UpdateStatus.InstallPermissionRequired -> {
            ReleaseInfo(s.manifest, showNotes = false)
            Message(stringResource(R.string.update_permission_explain))
            Button(onClick = { context.openInstallPermissionSettings() }) { Text(stringResource(R.string.open_settings)) }
        }
        is UpdateStatus.InstallCancelled -> {
            ReleaseInfo(s.manifest, showNotes = false)
            Message(stringResource(R.string.update_install_cancelled))
            Button(onClick = ::install) { Text(stringResource(R.string.update_install)) }
        }
        is UpdateStatus.InstallFailed -> {
            ReleaseInfo(s.manifest, showNotes = false)
            Message(stringResource(R.string.update_install_failed), error = true)
            Button(onClick = { manager.startDownload() }) { Text(stringResource(R.string.retry)) }
        }
    }

    checkError?.let { Message(checkErrorText(it), error = true) }

    val busy = status is UpdateStatus.Checking || status is UpdateStatus.Downloading
    OutlinedButton(onClick = { manager.checkNow() }, enabled = !busy) {
        Text(stringResource(R.string.update_check))
    }

    val autoCheck by manager.autoCheck.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.update_auto_check))
            Text(
                stringResource(R.string.update_auto_check_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = autoCheck, onCheckedChange = manager::setAutoCheck)
    }
}

@Composable
private fun ReleaseInfo(m: UpdateManifest, showNotes: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(if (m.mandatory) R.string.update_available_required else R.string.update_available, m.versionName),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.update_release_meta, m.releaseDate.toString(), mb(m.apkSize)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showNotes && m.releaseNotes.isNotBlank()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Text(
                m.releaseNotes.trim(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.update_notes_less else R.string.update_notes_more))
            }
        }
    }
}

@Composable
private fun Message(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun networkText(f: NetworkFailure): String = when (f) {
    NetworkFailure.Offline -> stringResource(R.string.update_err_offline)
    NetworkFailure.Dns -> stringResource(R.string.update_err_dns)
    NetworkFailure.Timeout -> stringResource(R.string.update_err_timeout)
    NetworkFailure.Tls, NetworkFailure.Insecure -> stringResource(R.string.update_err_insecure)
    NetworkFailure.Connection -> stringResource(R.string.update_err_connection)
    is NetworkFailure.Http -> if (f.code == 404) {
        stringResource(R.string.update_err_not_published)
    } else {
        stringResource(R.string.update_err_http, f.code)
    }
}

@Composable
private fun checkErrorText(e: CheckError): String {
    e.network?.let { return stringResource(R.string.update_check_failed, networkText(it)) }
    return when (e.rejected) {
        ManifestRejectedException.Reason.BAD_SIGNATURE, ManifestRejectedException.Reason.UNKNOWN_KEY ->
            stringResource(R.string.update_err_signature)
        else -> stringResource(R.string.update_err_invalid_manifest)
    }
}

@Composable
private fun problemText(p: DownloadProblem): String = when (p) {
    DownloadProblem.CHECKSUM_MISMATCH, DownloadProblem.SIZE_MISMATCH -> stringResource(R.string.update_err_checksum)
    DownloadProblem.WRONG_PACKAGE -> stringResource(R.string.update_err_wrong_package)
    DownloadProblem.WRONG_SIGNER -> stringResource(R.string.update_err_wrong_signer)
    DownloadProblem.NOT_ENOUGH_SPACE -> stringResource(R.string.update_err_space)
    DownloadProblem.STORAGE -> stringResource(R.string.update_err_storage)
}

private fun mb(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))

private fun Context.openInstallPermissionSettings() {
    try {
        startActivity(ApkInstaller.permissionSettingsIntent(this))
    } catch (_: ActivityNotFoundException) {
        // Some ROMs lack the per-app screen; fall back to the app's details page.
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        } catch (_: ActivityNotFoundException) {
        }
    }
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
