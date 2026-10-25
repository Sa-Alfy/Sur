package io.github.saalfy.sur.data.info

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SongInfoStatus {
    APPLIED,
    SKIPPED,
}

@Entity(tableName = "song_info")
data class SongInfoEntity(
    @PrimaryKey val key: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val coverFile: String?,
    val mbRecordingId: String?,
    val status: SongInfoStatus,
    val updatedAt: Long,
)

object SongInfoKey {
    /**
     * Resolves the song key: relative path + display name resolved the same way
     * the M3U matcher resolves it, falling back to display name only if there is no relative path.
     */
    fun buildKey(relativePath: String?, displayName: String?): String {
        val name = displayName?.trim().orEmpty()
        val rel = relativePath?.trim()?.replace('\\', '/')
        if (rel.isNullOrEmpty()) {
            return name
        }
        return if (name.isEmpty() || rel.endsWith("/$name") || rel == name) {
            rel
        } else {
            "${rel.trimEnd('/')}/$name"
        }
    }
}
