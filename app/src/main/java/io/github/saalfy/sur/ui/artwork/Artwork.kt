package io.github.saalfy.sur.ui.artwork

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.saalfy.sur.R
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.MediaStoreUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small on purpose: the test device has 2GB RAM. */
private const val ARTWORK_CACHE_BYTES = 6 * 1024 * 1024

/** Loads downsampled album art from MediaStore with a small in-memory cache. */
class ArtworkLoader(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private val cache = object : LruCache<String, Bitmap>(ARTWORK_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Keys known to have no art, so missing art isn't re-queried on every recomposition. */
    private val missing = LruCache<String, Boolean>(512)

    fun cached(songId: Long, sizePx: Int): Bitmap? = cache.get(key(songId, sizePx))

    suspend fun load(songId: Long, artworkUri: Uri?, sizePx: Int): Bitmap? {
        val key = key(songId, sizePx)
        cache.get(key)?.let { return it }
        if (missing.get(key) != null) return null
        val bitmap = withContext(Dispatchers.IO) { runCatching { decode(songId, artworkUri, sizePx) }.getOrNull() }
        if (bitmap != null) cache.put(key, bitmap) else missing.put(key, true)
        return bitmap
    }

    private fun key(songId: Long, sizePx: Int) = "$songId@$sizePx"

    private fun decode(songId: Long, artworkUri: Uri?, sizePx: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(MediaStoreUris.song(songId), Size(sizePx, sizePx), null)
        } else {
            artworkUri?.let { decodeSampled(it, sizePx) }
        }

    private fun decodeSampled(uri: Uri, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}

/** Album art for a song, or a music-note placeholder. [size] is the largest size it will be drawn at. */
@Composable
fun Artwork(
    songId: Long?,
    artworkUri: Uri?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    val context = LocalContext.current
    val loader = remember { (context.applicationContext as SurApplication).container.artworkLoader }
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(
        initialValue = songId?.let { loader.cached(it, sizePx) },
        songId,
        sizePx,
    ) {
        value = songId?.let { loader.load(it, artworkUri, sizePx) }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
