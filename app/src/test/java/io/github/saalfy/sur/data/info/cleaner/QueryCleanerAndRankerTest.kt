package io.github.saalfy.sur.data.info.cleaner

import io.github.saalfy.sur.data.info.lookup.Candidate
import io.github.saalfy.sur.data.info.lookup.LookupQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryCleanerAndRankerTest {

    // --- C.2 Real library names ---

    @Test
    fun clean_arRahmanBarso() {
        val queries = QueryCleaner.clean(displayName = "A._R._Rahman-_Barso...")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "Barso...", artist = "A. R. Rahman"), queries[0])
        assertEquals(LookupQuery(title = "A. R. Rahman", artist = "Barso..."), queries[1])
        assertEquals(LookupQuery(title = "A. R. Rahman - Barso...", artist = null), queries[2])
    }

    @Test
    fun clean_monerManush() {
        val queries = QueryCleaner.clean(displayName = "_Moner_Manush__-_A...")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "A...", artist = "Moner Manush"), queries[0])
        assertEquals(LookupQuery(title = "Moner Manush", artist = "A..."), queries[1])
        assertEquals(LookupQuery(title = "Moner Manush - A...", artist = null), queries[2])
    }

    @Test
    fun clean_tomareDekhiloPipeMovieSong() {
        val queries = QueryCleaner.clean(displayName = "Tomare Dekhilo By Habib & Nancy | Movie Song | Reyaz &...")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "Tomare Dekhilo", artist = "Habib & Nancy"), queries[0])
        assertEquals(LookupQuery(title = "Tomare Dekhilo", artist = null), queries[1])
        assertEquals(LookupQuery(title = "Tomare Dekhilo By Habib & Nancy", artist = null), queries[2])
    }

    @Test
    fun clean_aajSeTeriLyrical() {
        val queries = QueryCleaner.clean(displayName = "Aaj Se Teri - Lyrical")
        assertEquals(1, queries.size)
        assertEquals(LookupQuery(title = "Aaj Se Teri", artist = null), queries[0])
    }

    @Test
    fun clean_tumhiHoBandhuFullVideo() {
        val queries = QueryCleaner.clean(displayName = "Tumhi Ho Bandhu - Full Video")
        assertEquals(1, queries.size)
        assertEquals(LookupQuery(title = "Tumhi Ho Bandhu", artist = null), queries[0])
    }

    @Test
    fun clean_tomarJonnoMehreenLyric() {
        val queries = QueryCleaner.clean(displayName = "Tomar jonno - Mehreen - Lyric")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "Mehreen", artist = "Tomar jonno"), queries[0])
        assertEquals(LookupQuery(title = "Tomar jonno", artist = "Mehreen"), queries[1])
        assertEquals(LookupQuery(title = "Tomar jonno - Mehreen", artist = null), queries[2])
    }

    @Test
    fun clean_tumiHinaFuadFeat() {
        val queries = QueryCleaner.clean(displayName = "TUMI HINA - FUAD FEAT. FA...")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "TUMI HINA", artist = "FUAD FEAT. FA..."), queries[0])
        assertEquals(LookupQuery(title = "TUMI HINA", artist = null), queries[1])
        assertEquals(LookupQuery(title = "TUMI HINA - FUAD FEAT. FA...", artist = null), queries[2])
    }

    @Test
    fun clean_bengaliWithBy() {
        val queries = QueryCleaner.clean(displayName = "22.12.2022 বইশ by S...")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "22.12.2022 বইশ", artist = "S..."), queries[0])
        assertEquals(LookupQuery(title = "22.12.2022 বইশ", artist = null), queries[1])
        assertEquals(LookupQuery(title = "22.12.2022 বইশ by S...", artist = null), queries[2])
    }

    @Test
    fun clean_16YearOldKills() {
        val queries = QueryCleaner.clean(displayName = "16 YEAR OLD KILLS P...")
        assertEquals(1, queries.size)
        assertEquals(LookupQuery(title = "16 YEAR OLD KILLS P...", artist = null), queries[0])
    }

    @Test
    fun clean_onlyHyphensAndUnderscores_givesEmptyList() {
        val queries1 = QueryCleaner.clean(displayName = "--__--__")
        assertTrue(queries1.isEmpty())

        val queries2 = QueryCleaner.clean(displayName = "   _ - - _   ")
        assertTrue(queries2.isEmpty())
    }

    // --- Step 1: hyphens inside words ---

    @Test
    fun clean_hyphenInsideWords_jayZEmpireStateOfMind() {
        val queries = QueryCleaner.clean(displayName = "Jay-Z - Empire State of Mind")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "Empire State of Mind", artist = "Jay-Z"), queries[0])
        assertEquals(LookupQuery(title = "Jay-Z", artist = "Empire State of Mind"), queries[1])
        assertEquals(LookupQuery(title = "Jay-Z - Empire State of Mind", artist = null), queries[2])
    }

    @Test
    fun clean_hyphenInsideWords_spiderMan() {
        val queries = QueryCleaner.clean(displayName = "Spider-Man")
        assertEquals(1, queries.size)
        assertEquals(LookupQuery(title = "Spider-Man", artist = null), queries[0])
    }

    @Test
    fun clean_hyphenInsideWords_antiHero() {
        val queries = QueryCleaner.clean(displayName = "Anti-Hero")
        assertEquals(1, queries.size)
        assertEquals(LookupQuery(title = "Anti-Hero", artist = null), queries[0])
    }

    // --- Step 2: noise only when whole segment is noise ---

    @Test
    fun clean_wholeSegmentNoise_bugglesVideoKilledTheRadioStar() {
        val queries = QueryCleaner.clean(displayName = "Buggles - Video Killed the Radio Star")
        assertEquals(3, queries.size)
        assertEquals(LookupQuery(title = "Video Killed the Radio Star", artist = "Buggles"), queries[0])
        assertEquals(LookupQuery(title = "Buggles", artist = "Video Killed the Radio Star"), queries[1])
        assertEquals(LookupQuery(title = "Buggles - Video Killed the Radio Star", artist = null), queries[2])
    }

    @Test
    fun clean_wholeSegmentNoise_fullCircleAerosmith() {
        val queries = QueryCleaner.clean(displayName = "Full Circle | Aerosmith")
        assertEquals("Full Circle", queries[0].title)
    }

    @Test
    fun clean_wholeSegmentNoise_artistTitleOfficialVideoInParens() {
        val queries = QueryCleaner.clean(displayName = "Artist - Title (Official Video)")
        assertEquals(LookupQuery(title = "Title", artist = "Artist"), queries[0])
    }

    @Test
    fun clean_wholeSegmentNoise_artistTitleOfficialVideoWithHyphen() {
        val queries = QueryCleaner.clean(displayName = "Artist - Title - Official Video")
        assertEquals(LookupQuery(title = "Title", artist = "Artist"), queries[0])
    }

    // --- 9-3A-fix: "Artist - Title" tag title, misspelled channel artist ---

    @Test
    fun clean_buryAFriend_tagTitleSplitComesFirst() {
        val queries = QueryCleaner.clean(
            tagTitle = "Billie Eilish - bury a friend",
            tagArtist = "Bille Eilish",
            displayName = "Bille Eilish - Billie Eilish - bury a friend.m4a",
        )
        assertEquals(LookupQuery(title = "bury a friend", artist = "Billie Eilish"), queries[0])
        assertTrue(queries.size <= 3)
    }

    @Test
    fun clean_buryAFriend_displayNameDropsDuplicateChannel() {
        val queries = QueryCleaner.clean(displayName = "Bille Eilish - Billie Eilish - bury a friend.m4a")
        assertEquals(LookupQuery(title = "bury a friend", artist = "Billie Eilish"), queries[0])
        assertEquals(LookupQuery(title = "Billie Eilish - bury a friend", artist = null), queries[2])
    }

    @Test
    fun artistSimilarity_typoIsClose_unrelatedIsFar() {
        assertTrue(artistSimilarity("Bille Eilish", "Billie Eilish") >= 0.8)
        assertTrue(artistSimilarity("Bille Eilish", "Arijit Singh") < 0.5)
        assertEquals(0.0, artistSimilarity(null, "Billie Eilish"), 0.0)
        assertEquals(0.0, artistSimilarity("  ", "Billie Eilish"), 0.0)
    }

    @Test
    fun ranking_wantedArtistBeatsHigherScore() {
        val cover = Candidate("1", "bury a friend", "Some Cover Band", null, null, null, 100, emptyList())
        val real = Candidate("2", "bury a friend", "Billie Eilish", null, null, null, 90, emptyList())

        val ranked = CandidateRanker.rank(listOf(cover, real), null, wantedArtist = "Bille Eilish")
        assertEquals(listOf(real, cover), ranked)
    }

    // --- C.4 Duration note boundaries and ranking ---

    @Test
    fun durationNote_exactBoundariesAt5sAnd6s() {
        val target = 180_000L // 3:00

        // Exact 5s difference
        assertEquals("Duration matches", CandidateRanker.durationNote(185_000L, target))
        assertEquals("Duration matches", CandidateRanker.durationNote(175_000L, target))

        // Exact 6s difference
        assertEquals("Duration differs by 0:06", CandidateRanker.durationNote(186_000L, target))
        assertEquals("Duration differs by 0:06", CandidateRanker.durationNote(174_000L, target))

        // Multi-minute difference (65 seconds = 1:05)
        assertEquals("Duration differs by 1:05", CandidateRanker.durationNote(115_000L, target))
    }

    @Test
    fun durationNote_nullDuration_returnsNull() {
        assertNull(CandidateRanker.durationNote(null, 180_000L))
        assertNull(CandidateRanker.durationNote(180_000L, null))
        assertNull(CandidateRanker.durationNote(null, null))
    }

    @Test
    fun ranking_orderByScoreThenDurationCloseness() {
        val target = 200_000L
        val candA = Candidate("1", "A", "Art", null, null, 199_000L, 90, emptyList()) // diff 1s, score 90
        val candB = Candidate("2", "B", "Art", null, null, 190_000L, 100, emptyList()) // diff 10s, score 100
        val candC = Candidate("3", "C", "Art", null, null, 198_000L, 100, emptyList()) // diff 2s, score 100

        val ranked = CandidateRanker.rank(listOf(candA, candB, candC), target)
        assertEquals(listOf(candC, candB, candA), ranked)
    }
}
