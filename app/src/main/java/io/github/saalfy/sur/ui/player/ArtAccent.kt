package io.github.saalfy.sur.ui.player

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import io.github.saalfy.sur.ui.artwork.ArtworkLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Colors Now Playing derives from the current song's art (or the theme when there is none). */
data class AccentColors(
    /** Seek bar, play button, active toggles. At least 3:1 against [backgroundTint] and the surface. */
    val accent: Color,
    /** Icon color on [accent]. */
    val onAccent: Color,
    /** Top of the background gradient. onSurface and onSurfaceVariant keep 4.5:1 on it. */
    val backgroundTint: Color,
)

private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_GRAPHIC_CONTRAST = 3f
private const val MIN_SATURATION = 0.15f
private const val MAX_TINT_FRACTION = 0.30f

/** Sentinel for "art has no usable color". Palette swatches are opaque, so 0 never collides. */
private const val NO_SEED = 0

/** Per-song seed colors (ARGB ints). Ints only, so 128 entries cost a few KB. */
private val seedCache = LruCache<Long, Int>(128)

/** WCAG 2.x contrast ratio between two opaque colors. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * Turns a seed color from the art into accents that are readable on [scheme]. Grey or missing
 * seeds fall back to the theme's primary. Pure function: covered by ArtAccentTest.
 */
fun accentColorsFor(seed: Color?, scheme: ColorScheme): AccentColors {
    val surface = scheme.surface
    val dark = surface.luminance() < 0.5f
    val base = seed?.takeIf { saturation(it) >= MIN_SATURATION }

    val accent = if (base == null) {
        scheme.primary
    } else {
        val (h, s, l) = toHsl(base)
        // Push lightness away from the surface until the accent stands out as a control color.
        var lightness = if (dark) max(l, 0.62f) else min(l, 0.42f)
        var candidate = Color.hsl(h, s, lightness)
        var steps = 0
        while (contrast(candidate, surface) < MIN_GRAPHIC_CONTRAST && steps < 20) {
            lightness = (lightness + if (dark) 0.03f else -0.03f).coerceIn(0f, 1f)
            candidate = Color.hsl(h, s, lightness)
            steps++
        }
        candidate
    }
    val onAccent = if (contrast(Color.White, accent) >= contrast(Color.Black, accent)) Color.White else Color.Black

    // Strongest tint that keeps text and the accent readable on it.
    var fraction = MAX_TINT_FRACTION
    var tint = lerp(surface, accent, fraction)
    while (fraction > 0f &&
        (contrast(scheme.onSurface, tint) < MIN_TEXT_CONTRAST ||
            contrast(scheme.onSurfaceVariant, tint) < MIN_TEXT_CONTRAST ||
            contrast(accent, tint) < MIN_GRAPHIC_CONTRAST)
    ) {
        fraction = (fraction - 0.04f).coerceAtLeast(0f)
        tint = lerp(surface, accent, fraction)
    }
    return AccentColors(accent, onAccent, tint)
}

/**
 * Seed color for [songId]'s art, or null. Reuses the artwork cache entry at [thumbnailPx] (the
 * size the mini player and lists already loaded), runs Palette off the main thread on a
 * downscaled copy, and caches the result per song.
 */
@Composable
fun rememberArtSeed(songId: Long?, artworkUri: Uri?, loader: ArtworkLoader, thumbnailPx: Int): State<Color?> =
    produceState<Color?>(initialValue = songId?.let { seedCache.get(it) }?.toSeed(), songId) {
        if (songId == null) {
            value = null
            return@produceState
        }
        val cached = seedCache.get(songId)
        if (cached != null) {
            value = cached.toSeed()
            return@produceState
        }
        val bitmap = loader.load(songId, artworkUri, thumbnailPx)
        // A Palette failure must never reach composition; it just means "use the theme".
        val rgb = if (bitmap == null) {
            NO_SEED
        } else {
            withContext(Dispatchers.Default) { runCatching { extractSeed(bitmap) }.getOrDefault(NO_SEED) }
        }
        seedCache.put(songId, rgb)
        value = rgb.toSeed()
    }

private fun extractSeed(bitmap: Bitmap): Int {
    val palette = Palette.from(bitmap)
        .maximumColorCount(12)
        .resizeBitmapArea(64 * 64) // Palette works on its own scaled copy; the cached bitmap is untouched.
        .generate()
    val swatch = palette.vibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.dominantSwatch
    return swatch?.rgb ?: NO_SEED
}

private fun Int.toSeed(): Color? = if (this == NO_SEED) null else Color(this)

private fun saturation(color: Color): Float = toHsl(color).second

/** RGB → (hue 0-360, saturation 0-1, lightness 0-1). */
private fun toHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) / 2f
    val d = maxC - minC
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when (maxC) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
}
