package io.github.saalfy.sur.ui.player

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.saalfy.sur.ui.theme.DarkOnPrimary
import io.github.saalfy.sur.ui.theme.DarkOnSurface
import io.github.saalfy.sur.ui.theme.DarkOnSurfaceVariant
import io.github.saalfy.sur.ui.theme.DarkPrimary
import io.github.saalfy.sur.ui.theme.DarkSurface
import io.github.saalfy.sur.ui.theme.LightOnPrimary
import io.github.saalfy.sur.ui.theme.LightOnSurface
import io.github.saalfy.sur.ui.theme.LightOnSurfaceVariant
import io.github.saalfy.sur.ui.theme.LightPrimary
import io.github.saalfy.sur.ui.theme.LightSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtAccentTest {

    private val light = lightColorScheme(
        primary = LightPrimary, onPrimary = LightOnPrimary,
        surface = LightSurface, onSurface = LightOnSurface, onSurfaceVariant = LightOnSurfaceVariant,
    )
    private val dark = darkColorScheme(
        primary = DarkPrimary, onPrimary = DarkOnPrimary,
        surface = DarkSurface, onSurface = DarkOnSurface, onSurfaceVariant = DarkOnSurfaceVariant,
    )

    // Typical album-art seeds: saturated, very light, very dark, mid tones.
    private val seeds = listOf(
        Color(0xFFE53935), Color(0xFFFFEB3B), Color(0xFF1A237E), Color(0xFF00E676),
        Color(0xFFFF9800), Color(0xFF6A1B9A), Color(0xFFF8BBD0), Color(0xFF263238),
        Color(0xFF00BCD4), Color(0xFF8D6E63), Color(0xFFFFFFF0), Color(0xFF0D0D10),
    )

    @Test
    fun noArtFallsBackToThemePrimary() {
        assertEquals(LightPrimary, accentColorsFor(null, light).accent)
        assertEquals(DarkPrimary, accentColorsFor(null, dark).accent)
    }

    @Test
    fun greyArtFallsBackToThemePrimary() {
        assertEquals(LightPrimary, accentColorsFor(Color(0xFF808080), light).accent)
        assertEquals(DarkPrimary, accentColorsFor(Color(0xFF7A7C7E), dark).accent)
    }

    @Test
    fun everySeedStaysReadable() {
        for (scheme in listOf(light, dark)) {
            for (seed in seeds + null) {
                val c = accentColorsFor(seed, scheme)
                val label = "seed=$seed dark=${scheme === dark}"
                assertTrue("$label onSurface/tint", contrast(scheme.onSurface, c.backgroundTint) >= 4.5f)
                assertTrue("$label onSurfaceVariant/tint", contrast(scheme.onSurfaceVariant, c.backgroundTint) >= 4.5f)
                assertTrue("$label accent/surface", contrast(c.accent, scheme.surface) >= 3f)
                assertTrue("$label accent/tint", contrast(c.accent, c.backgroundTint) >= 3f)
                assertTrue("$label onAccent/accent", contrast(c.onAccent, c.accent) >= 4.5f)
            }
        }
    }

    @Test
    fun contrastMatchesWcagExtremes() {
        assertEquals(21f, contrast(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrast(LightSurface, LightSurface), 0.001f)
    }
}
