package io.github.saalfy.sur.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.saalfy.sur.R

private const val DEVELOPER_URL = "https://github.com/Sa-Alfy"
private const val SOURCE_URL = "https://github.com/Sa-Alfy/Sur"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/** App name, version, developer, source and license. Links open in the browser only when tapped. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title, version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)

                Text(
                    stringResource(R.string.about_developer_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier,
                )
                Text(stringResource(R.string.about_developer_name), style = MaterialTheme.typography.titleMedium)
                LinkButton(stringResource(R.string.about_developer_github)) { context.openUrl(DEVELOPER_URL) }

                LinkButton(stringResource(R.string.about_source)) { context.openUrl(SOURCE_URL) }
                LinkButton(stringResource(R.string.about_license)) { context.openUrl(LICENSE_URL) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing to open.
    }
}
