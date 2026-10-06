package io.github.saalfy.sur.data

import android.content.Context
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sur_prefs", Context.MODE_PRIVATE)

    var batteryPromptDone: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_BATTERY_PROMPT_DONE, value) }

    private companion object {
        const val KEY_BATTERY_PROMPT_DONE = "battery_prompt_done"
    }
}
