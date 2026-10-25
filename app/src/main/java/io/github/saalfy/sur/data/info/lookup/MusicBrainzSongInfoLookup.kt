package io.github.saalfy.sur.data.info.lookup

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class MusicBrainzSongInfoLookup(
    private val versionName: String,
    private val client: OkHttpClient = defaultClient(),
    private val rateLimiter: RateLimiter = RateLimiter(),
) : SongInfoLookup {

    private val userAgent = "Sur/$versionName ( https://github.com/Sa-Alfy/Sur )"

    override suspend fun search(query: LookupQuery): List<Candidate> {
        val luceneQuery = buildLuceneQuery(query.title, query.artist)
        val encodedQuery = URLEncoder.encode(luceneQuery, StandardCharsets.UTF_8.name())
        val url = "https://musicbrainz.org/ws/2/recording?query=$encodedQuery&fmt=json&limit=5"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

        val response = rateLimiter.execute { client.newCall(request) }
        val bodyString = response.use { resp ->
            if (!resp.isSuccessful) {
                throw LookupHttpException(resp.code)
            }
            resp.body.string()
        }
        return parseCandidates(bodyString)
    }

    override suspend fun fetchCover(candidate: Candidate): ByteArray? {
        val releases = candidate.releaseIds.take(3)
        for (releaseId in releases) {
            val url = "https://coverartarchive.org/release/$releaseId/front-500"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            val response = try {
                rateLimiter.execute { client.newCall(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LookupBusyException) {
                throw e
            } catch (e: Exception) {
                continue
            }

            if (response.code == 404) {
                response.close()
                continue
            }

            if (!response.isSuccessful) {
                response.close()
                continue
            }

            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength > MAX_COVER_BYTES) {
                response.close()
                continue
            }

            val bytes = try {
                body.bytes()
            } catch (e: CancellationException) {
                response.close()
                throw e
            } catch (e: Exception) {
                response.close()
                continue
            }

            if (bytes.size > MAX_COVER_BYTES) {
                continue
            }

            if (isJpegOrPng(bytes)) {
                return bytes
            }
        }
        return null
    }

    companion object {
        const val MAX_COVER_BYTES = 2 * 1024 * 1024 // 2 MB

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

        fun escapeLucene(input: String): String {
            val sb = StringBuilder()
            for (ch in input) {
                when (ch) {
                    '\\', '"', ':', '(', ')', '+', '-', '!', '^', '[', ']', '{', '}', '~', '*', '?', '|', '&', '/' -> {
                        sb.append('\\').append(ch)
                    }
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun buildLuceneQuery(title: String, artist: String?): String {
            val escapedTitle = escapeLucene(title.trim())
            val escapedArtist = artist?.trim()?.takeIf { it.isNotEmpty() }?.let { escapeLucene(it) }
            return if (escapedArtist != null) {
                "recording:\"$escapedTitle\" AND artist:\"$escapedArtist\""
            } else {
                "recording:\"$escapedTitle\""
            }
        }

        fun parseCandidates(jsonString: String): List<Candidate> {
            val root = try {
                JsonParser.`object`().from(jsonString)
            } catch (e: Exception) {
                throw LookupParseException("Malformed JSON response", e)
            }
            val recordingsArray = root.getArray("recordings")
                ?: throw LookupParseException("Missing recordings field in response")
            val result = mutableListOf<Candidate>()

            for (i in 0 until recordingsArray.size) {
                val rec = recordingsArray.getObject(i) ?: continue
                val id = rec.getString("id") ?: continue
                val title = rec.getString("title", "")
                val score = when (val s = rec.get("score")) {
                    is Number -> s.toInt()
                    is String -> s.toIntOrNull() ?: 0
                    else -> 0
                }
                val durationMs = rec.getNumber("length")?.toLong()

                val artistCreditArray = rec.getArray("artist-credit")
                val artist = artistCreditArray?.joinToString("") { elem ->
                    when (elem) {
                        is JsonObject -> elem.getString("name", "")
                        else -> ""
                    }
                }?.ifBlank { null } ?: "Unknown"

                val firstReleaseDate = rec.getString("first-release-date")
                val releasesArray = rec.getArray("releases")
                val releaseIds = mutableListOf<String>()
                var releaseTitle: String? = null
                var firstReleaseDateFallback: String? = null

                if (releasesArray != null) {
                    for (r in 0 until releasesArray.size) {
                        val relObj = releasesArray.getObject(r) ?: continue
                        val relId = relObj.getString("id")
                        if (!relId.isNullOrBlank()) {
                            releaseIds.add(relId)
                        }
                        if (releaseTitle == null) {
                            releaseTitle = relObj.getString("title")
                        }
                        if (firstReleaseDateFallback == null) {
                            firstReleaseDateFallback = relObj.getString("date")
                        }
                    }
                }

                val dateStr = firstReleaseDate ?: firstReleaseDateFallback
                val year = dateStr?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }

                result.add(
                    Candidate(
                        recordingId = id,
                        title = title,
                        artist = artist,
                        releaseTitle = releaseTitle,
                        year = year,
                        durationMs = durationMs,
                        score = score,
                        releaseIds = releaseIds,
                    )
                )
            }
            return result
        }

        fun isJpegOrPng(bytes: ByteArray): Boolean {
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) {
                return true
            }
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() && // 'P'
                bytes[2] == 0x4E.toByte() && // 'N'
                bytes[3] == 0x47.toByte() && // 'G'
                bytes[4] == 0x0D.toByte() && // '\r'
                bytes[5] == 0x0A.toByte() && // '\n'
                bytes[6] == 0x1A.toByte() && // '\x1a'
                bytes[7] == 0x0A.toByte()    // '\n'
            ) {
                return true
            }
            return false
        }
    }
}
