package io.github.saalfy.sur.data.info.lookup

data class LookupQuery(
    val title: String,
    val artist: String? = null,
    val durationMs: Long? = null,
)

data class Candidate(
    val recordingId: String,
    val title: String,
    val artist: String,
    val releaseTitle: String?,
    val year: String?,
    val durationMs: Long?,
    val score: Int,
    val releaseIds: List<String>,
)

interface SongInfoLookup {
    suspend fun search(query: LookupQuery, limit: Int = 5): List<Candidate>
    suspend fun fetchCover(candidate: Candidate): ByteArray?
}
