package io.github.saalfy.sur.downloader.newpipe

import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * SPIKE ONLY - never run in CI. Gate: -Psur.networkTests=true.
 *
 * Classes used (confirmed from jar inspection of NewPipeExtractor-v0.26.5.jar):
 *   org.schabi.newpipe.extractor.ServiceList
 *   org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
 *     constant: YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
 *   org.schabi.newpipe.extractor.search.SearchExtractor   (returned by getSearchExtractor)
 *   org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage  (returned by getInitialPage)
 *   org.schabi.newpipe.extractor.stream.StreamInfoItem         (items in the page)
 *   org.schabi.newpipe.extractor.Image                         (thumbnails)
 */
class MusicSearchSpikeTest {

    @Test
    fun musicSongSearch_burryAFriendAndLaaluFakir() {
        assumeTrue(System.getProperty("sur.networkTests") == "true")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        NewPipe.init(OkHttpNewPipeClient(client), Localization("en", "US"))

        val queries = listOf(
            "bury a friend billie eilish",
            "Laalu Fakir kodom",
        )

        var firstResultBestUrl: String? = null

        for (query in queries) {
            println("\n========== QUERY: \"$query\" ==========")

            val extractor = ServiceList.YouTube.getSearchExtractor(
                query,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                "",
            )
            extractor.fetchPage()
            val items = extractor.initialPage.items

            val top5 = items.take(5)
            top5.forEachIndexed { idx, item ->
                println("--- Result ${idx + 1} ---")
                println("  name        : ${item.name}")

                if (item is StreamInfoItem) {
                    println("  uploader    : ${item.uploaderName}")
                    println("  duration(s) : ${item.duration}")
                    // StreamInfoItem has no album field in v0.26.5 - confirmed by javap
                    println("  album       : <no album field in StreamInfoItem v0.26.5>")
                    println("  thumbnails  :")
                    item.thumbnails.forEachIndexed { ti, img ->
                        println("    [$ti] url=${img.url}  w=${img.width}  h=${img.height}  level=${img.estimatedResolutionLevel}")
                    }
                    if (idx == 0 && query == queries[0]) {
                        // pick best thumbnail for step 3
                        val best = item.thumbnails.maxWithOrNull(
                            compareBy(
                                { it.estimatedResolutionLevel.ordinal },
                                { if (it.height > 0) it.height else 0 },
                            )
                        )
                        firstResultBestUrl = best?.url
                    }
                } else {
                    println("  (item is ${item::class.java.simpleName}, not a StreamInfoItem)")
                }
            }
        }

        // Step 3: download original + rewritten thumbnail and read pixel dimensions
        println("\n========== THUMBNAIL DIMENSION PROBE ==========")
        val url = firstResultBestUrl
        if (url == null) {
            println("No best URL found - skipping probe")
            return
        }
        println("Best thumbnail URL: $url")

        // URLs look like: =w120-h120-l90-rj  (the w/h part may be followed by more params)
        val sizePattern = Regex("(=w)(\\d+)(-h)(\\d+)")

        fun fetchImageDimensions(imageUrl: String, label: String) {
            println("\n-- $label --")
            println("  url: $imageUrl")
            val req = okhttp3.Request.Builder().url(imageUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val bytes = try {
                client.newCall(req).execute().use { resp ->
                    println("  http: ${resp.code}")
                    if (!resp.isSuccessful) {
                        println("  FAILED - non-2xx")
                        return
                    }
                    resp.body.bytes()
                }
            } catch (e: Exception) {
                println("  EXCEPTION: $e")
                return
            }
            println("  bytes: ${bytes.size}")
            val img = ImageIO.read(ByteArrayInputStream(bytes))
            if (img == null) {
                println("  ImageIO.read returned null - cannot determine dimensions")
            } else {
                println("  pixel width : ${img.width}")
                println("  pixel height: ${img.height}")
            }
        }

        fetchImageDimensions(url, "ORIGINAL URL")

        if (sizePattern.containsMatchIn(url)) {
            val rewritten = url.replace(sizePattern) { mr -> "${mr.groupValues[1]}600${mr.groupValues[3]}600" }
            fetchImageDimensions(rewritten, "REWRITTEN =w600-h600")
        } else {
            println("URL does not contain =wN-hN pattern - no rewrite attempted")
        }
    }
}
