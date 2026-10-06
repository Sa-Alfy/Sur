package io.github.saalfy.sur.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.saalfy.sur.R
import io.github.saalfy.sur.SurApplication

/**
 * One-time explanation that aggressive battery managers can stop background music.
 * Uses the permission-free optimisation settings list; no vendor deep links.
 */
@Composable
fun BatteryOptimizationPrompt() {
    val context = LocalContext.current
    val preferences = remember { (context.applicationContext as SurApplication).container.preferences }
    var visible by rememberSaveable {
        mutableStateOf(!preferences.batteryPromptDone && !context.isIgnoringBatteryOptimizations())
    }
    if (!visible) return

    fun finish() {
        preferences.batteryPromptDone = true
        visible = false
    }

    AlertDialog(
        // Tapping outside hides it for now; it returns next launch until a button is chosen.
        onDismissRequest = { visible = false },
        title = { Text(stringResource(R.string.battery_title)) },
        text = { Text(stringResource(R.string.battery_message)) },
        confirmButton = {
            TextButton(onClick = {
                finish()
                context.openBatteryOptimizationSettings()
            }) { Text(stringResource(R.string.open_settings)) }
        },
        dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.dont_show_again)) } },
    )
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

private fun Context.openBatteryOptimizationSettings() {
    try {
        startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        openAppSettings()
    }
}
