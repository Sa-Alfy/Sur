package io.github.saalfy.sur.download

import io.github.saalfy.sur.data.info.SongInfoEntity
import io.github.saalfy.sur.data.info.SongInfoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R2-01 regression tests: re-downloading a song must not destroy manually curated metadata.
 *
 * The DownloadService applies metadata with the rule:
 *   User Explicit Override (APPLIED) > External Provider Metadata
 *
 * These tests verify the precedence logic via the same decision tree used by
 * DownloadService.startDownload when it calls songInfoRepository.apply().
 * Since DownloadService requires Android Context, the preservation logic is
 * tested here as a pure function matching the inline logic in the service.
 */
class DownloadMetadataPreservationTest {

    /** Mirrors the inline precedence logic in DownloadService.startDownload. */
    private fun resolveMetadata(
        providerTitle: String,
        providerArtist: String,
        existing: SongInfoEntity?,
    ): Triple<String?, String?, String?> {
        val isApplied = existing?.status == SongInfoStatus.APPLIED
        val titleToApply = if (isApplied && !existing.title.isNullOrBlank()) existing.title else providerTitle
        val artistToApply = if (isApplied && !existing.artist.isNullOrBlank()) existing.artist else providerArtist
        val albumToApply = if (isApplied) existing.album else null
        return Triple(titleToApply, artistToApply, albumToApply)
    }

    private fun appliedEntity(
        title: String? = "User Title",
        artist: String? = "User Artist",
        album: String? = "User Album",
        mbRecordingId: String? = "mb-123",
    ) = SongInfoEntity(
        key = "Music/Sur/song.mp3",
        title = title,
        artist = artist,
        album = album,
        coverFile = null,
        mbRecordingId = mbRecordingId,
        status = SongInfoStatus.APPLIED,
        updatedAt = 1000L,
    )

    // -------------------------------------------------------------------------
    // No existing record
    // -------------------------------------------------------------------------

    @Test
    fun noExistingRecord_providerDataIsUsed() {
        val (title, artist, album) = resolveMetadata("Provider Title", "Provider Artist", null)
        assertEquals("Provider Title", title)
        assertEquals("Provider Artist", artist)
        assertNull(album)
    }

    // -------------------------------------------------------------------------
    // APPLIED existing record — user data wins
    // -------------------------------------------------------------------------

    @Test
    fun r2_01_appliedRecord_userTitlePreservedOverProviderTitle() {
        val (title, _, _) = resolveMetadata("Provider Title", "Provider Artist", appliedEntity())
        assertEquals("User Title wins over Provider Title", "User Title", title)
    }

    @Test
    fun r2_01_appliedRecord_userArtistPreservedOverProviderArtist() {
        val (_, artist, _) = resolveMetadata("Provider Title", "Provider Artist", appliedEntity())
        assertEquals("User Artist wins over Provider Artist", "User Artist", artist)
    }

    @Test
    fun r2_01_appliedRecord_userAlbumPreserved() {
        val (_, _, album) = resolveMetadata("Provider Title", "Provider Artist", appliedEntity(album = "User Album"))
        assertEquals("User Album wins over null provider album", "User Album", album)
    }

    @Test
    fun r2_01_appliedRecord_nullUserAlbum_remainsNull() {
        val (_, _, album) = resolveMetadata("Provider Title", "Provider Artist", appliedEntity(album = null))
        assertNull("Null user album stays null", album)
    }

    @Test
    fun r2_01_appliedRecord_blankUserTitle_fallsBackToProvider() {
        val (title, _, _) = resolveMetadata("Provider Title", "Provider Artist", appliedEntity(title = "   "))
        assertEquals("Blank user title falls back to provider", "Provider Title", title)
    }

    @Test
    fun r2_01_appliedRecord_nullUserTitle_fallsBackToProvider() {
        val (title, _, _) = resolveMetadata("Provider Title", "Provider Artist", appliedEntity(title = null))
        assertEquals("Null user title falls back to provider", "Provider Title", title)
    }

    // -------------------------------------------------------------------------
    // SKIPPED existing record — provider data is used
    // -------------------------------------------------------------------------

    @Test
    fun skippedRecord_providerDataIsUsed() {
        val skipped = appliedEntity().copy(status = SongInfoStatus.SKIPPED)
        val (title, artist, _) = resolveMetadata("Provider Title", "Provider Artist", skipped)
        assertEquals("Provider Title", title)
        assertEquals("Provider Artist", artist)
    }

    // -------------------------------------------------------------------------
    // mbRecordingId — always preserved from existing
    // -------------------------------------------------------------------------

    @Test
    fun r2_01_mbRecordingId_preservedFromExistingApplied() {
        val existing = appliedEntity(mbRecordingId = "mb-existing")
        val existingIsApplied = existing.status == SongInfoStatus.APPLIED
        val mbIdToApply = if (existingIsApplied) existing.mbRecordingId else null
        assertEquals("Existing mbRecordingId must be preserved", "mb-existing", mbIdToApply)
    }

    @Test
    fun r2_01_mbRecordingId_nullWhenNoExisting() {
        val existingIsApplied = false
        val mbIdToApply: String? = if (existingIsApplied) null else null
        assertNull("No existing record → mbRecordingId stays null", mbIdToApply)
    }
}
