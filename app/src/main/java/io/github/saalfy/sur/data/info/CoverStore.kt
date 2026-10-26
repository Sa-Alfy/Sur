package io.github.saalfy.sur.data.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

const val MAX_COVER_DIMENSION = 600
const val COVER_JPEG_QUALITY = 85

/**
 * Calculates inSampleSize using power-of-two downsampling such that the longest side
 * when subsampled remains >= target (or 1 if already <= target).
 */
fun sampleSize(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0 || target <= 0) return 1
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= target) {
        sample *= 2
    }
    return sample
}

/**
 * Calculates target dimensions preserving aspect ratio so that the longest side is <= max.
 * Does not upscale if both dimensions are already <= max.
 */
fun scaledSize(width: Int, height: Int, max: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || max <= 0) return Pair(maxOf(1, width), maxOf(1, height))
    val longest = maxOf(width, height)
    if (longest <= max) return Pair(width, height)
    val scaledWidth = maxOf(1, (width.toLong() * max / longest).toInt())
    val scaledHeight = maxOf(1, (height.toLong() * max / longest).toInt())
    return Pair(scaledWidth, scaledHeight)
}

interface CoverStore {
    fun save(bytes: ByteArray): String
    fun file(name: String): File?
    fun delete(name: String): Boolean

    companion object {
        operator fun invoke(context: Context): CoverStore = FileCoverStore(context)
    }
}

class FileCoverStore(
    context: Context,
) : CoverStore {

    private val coversDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    override fun save(bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Bytes cannot be decoded as an image")
        }

        val sample = sampleSize(bounds.outWidth, bounds.outHeight, MAX_COVER_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: throw IllegalArgumentException("Failed to decode image bytes")

        val (targetW, targetH) = scaledSize(decoded.width, decoded.height, MAX_COVER_DIMENSION)
        val finalBitmap = if (decoded.width != targetW || decoded.height != targetH) {
            val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            if (scaled != decoded) {
                decoded.recycle()
            }
            scaled
        } else {
            decoded
        }

        val fileName = "${UUID.randomUUID()}.jpg"
        val tempFile = File.createTempFile("cover_", ".tmp", coversDir)
        try {
            FileOutputStream(tempFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, out)
            }
            val destFile = File(coversDir, fileName)
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
            return fileName
        } finally {
            finalBitmap.recycle()
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    override fun file(name: String): File? {
        if (name.isBlank()) return null
        val file = File(coversDir, name)
        return if (file.exists() && file.isFile) file else null
    }

    override fun delete(name: String): Boolean {
        if (name.isBlank()) return false
        val file = File(coversDir, name)
        return if (file.exists()) file.delete() else false
    }
}
