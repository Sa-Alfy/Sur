package io.github.saalfy.sur.downloader.mp4

import java.io.File

/**
 * True if [file] looks like an MP4/M4A: at least 12 bytes long, with "ftyp"
 * at bytes 4..7. Returns false for missing, short, unreadable, or non-MP4
 * files (for example an HTML error page saved with an .m4a name).
 */
fun looksLikeMp4(file: File): Boolean {
    if (!file.isFile) return false
    if (file.length() < 12) return false
    return try {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            var read = 0
            while (read < 12) {
                val n = input.read(header, read, 12 - read)
                if (n < 0) return false
                read += n
            }
            String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
        }
    } catch (_: Exception) {
        false
    }
}
