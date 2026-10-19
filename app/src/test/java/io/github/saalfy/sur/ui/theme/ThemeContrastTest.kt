package io.github.saalfy.sur.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.saalfy.sur.ui.player.contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA checks for the Android 8-11 fallback palette: 4.5:1 for text, 3:1 for icons/controls. */
class ThemeContrastTest {

    private fun assertContrast(label: String, fg: Color, bg: Color, min: Float) {
        val ratio = contrast(fg, bg)
        println("%-44s %5.2f".format(label, ratio))
        assertTrue("$label: $ratio < $min", ratio >= min)
    }

    @Test
    fun lightPaletteMeetsAa() {
        assertContrast("light onSurface/surface", LightOnSurface, LightSurface, 4.5f)
        assertContrast("light onSurfaceVariant/surface", LightOnSurfaceVariant, LightSurface, 4.5f)
        assertContrast("light primary/surface", LightPrimary, LightSurface, 4.5f)
        assertContrast("light onPrimary/primary", LightOnPrimary, LightPrimary, 4.5f)
        assertContrast("light onSurface/surfaceContainerHigh", LightOnSurface, LightSurfaceContainerHigh, 4.5f)
        assertContrast("light onSurfaceVariant/surfaceContainerHigh", LightOnSurfaceVariant, LightSurfaceContainerHigh, 4.5f)
        assertContrast("light onSurfaceVariant/surfaceContainerHighest", LightOnSurfaceVariant, LightSurfaceContainerHighest, 4.5f)
        assertContrast("light primary/surfaceContainerHigh", LightPrimary, LightSurfaceContainerHigh, 3f)
        assertContrast("light onPrimaryContainer/primaryContainer", LightOnPrimaryContainer, LightPrimaryContainer, 4.5f)
        assertContrast("light onTertiaryContainer/tertiaryContainer", LightOnTertiaryContainer, LightTertiaryContainer, 4.5f)
    }

    @Test
    fun darkPaletteMeetsAa() {
        assertContrast("dark onSurface/surface", DarkOnSurface, DarkSurface, 4.5f)
        assertContrast("dark onSurfaceVariant/surface", DarkOnSurfaceVariant, DarkSurface, 4.5f)
        assertContrast("dark primary/surface", DarkPrimary, DarkSurface, 4.5f)
        assertContrast("dark onPrimary/primary", DarkOnPrimary, DarkPrimary, 4.5f)
        assertContrast("dark onSurface/surfaceContainerHigh", DarkOnSurface, DarkSurfaceContainerHigh, 4.5f)
        assertContrast("dark onSurfaceVariant/surfaceContainerHigh", DarkOnSurfaceVariant, DarkSurfaceContainerHigh, 4.5f)
        assertContrast("dark onSurfaceVariant/surfaceContainerHighest", DarkOnSurfaceVariant, DarkSurfaceContainerHighest, 4.5f)
        assertContrast("dark primary/surfaceContainerHigh", DarkPrimary, DarkSurfaceContainerHigh, 3f)
        assertContrast("dark onPrimaryContainer/primaryContainer", DarkOnPrimaryContainer, DarkPrimaryContainer, 4.5f)
        assertContrast("dark onTertiaryContainer/tertiaryContainer", DarkOnTertiaryContainer, DarkTertiaryContainer, 4.5f)
    }

    @Test
    fun surfaceContainersAreDistinctLevels() {
        val light = listOf(LightSurfaceContainerLow, LightSurfaceContainer, LightSurfaceContainerHigh, LightSurfaceContainerHighest)
        val dark = listOf(DarkSurfaceContainerLow, DarkSurfaceContainer, DarkSurfaceContainerHigh, DarkSurfaceContainerHighest)
        for (levels in listOf(light, dark)) {
            levels.zipWithNext().forEach { (a, b) -> assertTrue("$a vs $b too close", contrast(a, b) >= 1.04f) }
        }
    }
}
