package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.db.PlaylistEntryEntity
import io.github.saalfy.sur.data.info.SongIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistReconcileTest {

    private val nothing = emptyList<ReconcileAction>()

    private fun entry(
        id: Long,
        playlistId: Long,
        songId: Long,
        position: Int,
        songKey: String? = null,
        volume: String? = null,
        duration: Long? = if (songKey != null) 200_000L else null,
    ) = PlaylistEntryEntity(
        id = id, playlistId = playlistId, songId = songId, position = position,
        songKey = songKey, songVolume = volume, songDurationMs = duration,
    )

    private fun song(key: String, volume: String? = "external_primary", duration: Long? = 200_000L) =
        SongIdentity(key, volume, duration)

    /** Applies a plan the way PlaylistDao.reconcile does, for idempotence checks. */
    private fun apply(entries: List<PlaylistEntryEntity>, plan: List<ReconcileAction>): List<PlaylistEntryEntity> {
        val dropped = plan.filterIsInstance<ReconcileAction.DropDuplicate>().map { it.entryId }.toSet()
        return entries.filter { it.id !in dropped }.map { e ->
            when (val a = plan.firstOrNull { (it as? ReconcileAction.SetIdentity)?.entryId == e.id || (it as? ReconcileAction.Relink)?.entryId == e.id }) {
                is ReconcileAction.SetIdentity -> e.copy(songKey = a.identity.key, songVolume = a.identity.volume, songDurationMs = a.identity.durationMs)
                is ReconcileAction.Relink -> e.copy(
                    songId = a.newSongId, songKey = a.identity.key, songVolume = a.identity.volume,
                    songDurationMs = a.identity.durationMs, relinkedFromSongId = e.relinkedFromSongId ?: a.fromSongId,
                )
                else -> e
            }
        }
    }

    @Test
    fun backfillsFullIdentity() {
        val plan = planReconcile(listOf(entry(1, 1, 10, 0)), mapOf(10L to song("k10")))
        assertEquals(listOf(ReconcileAction.SetIdentity(1, song("k10"))), plan)
    }

    @Test
    fun refreshesChangedKey() {
        val plan = planReconcile(listOf(entry(1, 1, 10, 0, "old", "external_primary")), mapOf(10L to song("new")))
        assertEquals(listOf(ReconcileAction.SetIdentity(1, song("new"))), plan)
    }

    @Test
    fun relinksDeadIdWhenKeyVolumeAndDurationMatch() {
        val plan = planReconcile(listOf(entry(1, 1, 99, 0, "k10", "external_primary")), mapOf(10L to song("k10", duration = 200_400L)))
        assertEquals(listOf(ReconcileAction.Relink(1, 10, song("k10", duration = 200_400L), fromSongId = 99)), plan)
    }

    @Test
    fun r1_03_reusedPathWithDifferentSong_staysUnresolved() {
        // Same file name later used for another song (different length).
        val plan = planReconcile(listOf(entry(1, 1, 99, 0, "Music/a.mp3", "external_primary", 200_000)), mapOf(10L to song("Music/a.mp3", duration = 185_000)))
        assertEquals(nothing, plan)
    }

    @Test
    fun r1_03_noStoredDuration_staysUnresolved() {
        val plan = planReconcile(listOf(entry(1, 1, 99, 0, "k10", duration = null)), mapOf(10L to song("k10")))
        assertEquals(nothing, plan)
    }

    @Test
    fun r1_03_candidateWithUnknownDuration_isNotLinked() {
        val plan = planReconcile(listOf(entry(1, 1, 99, 0, "k10")), mapOf(10L to song("k10", duration = null)))
        assertEquals(nothing, plan)
    }

    @Test
    fun r1_02_sameRelativePathOnOtherVolume_isNotLinked() {
        val plan = planReconcile(listOf(entry(1, 1, 99, 0, "Music/a.mp3", "1234-abcd")), mapOf(10L to song("Music/a.mp3", "external_primary")))
        assertEquals(nothing, plan)
    }

    @Test
    fun r1_02_volumePicksTheRightOneOfTwoSameKeySongs() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "Music/a.mp3", "1234-abcd")),
            mapOf(10L to song("Music/a.mp3", "external_primary"), 20L to song("Music/a.mp3", "1234-abcd")),
        )
        assertEquals(listOf(ReconcileAction.Relink(1, 20, song("Music/a.mp3", "1234-abcd"), 99)), plan)
    }

    @Test
    fun r1_02_unknownStoredVolumeWithTwoVolumes_isAmbiguous() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "Music/a.mp3", volume = null)),
            mapOf(10L to song("Music/a.mp3", "external_primary"), 20L to song("Music/a.mp3", "1234-abcd")),
        )
        assertEquals(nothing, plan)
    }

    @Test
    fun noRelinkWhenKeyUnknown() {
        assertEquals(nothing, planReconcile(listOf(entry(1, 1, 99, 0, "kX")), mapOf(10L to song("k10"))))
    }

    @Test
    fun deadEntryWithNullKeyUntouched() {
        assertEquals(nothing, planReconcile(listOf(entry(1, 1, 99, 0)), mapOf(10L to song("k10"))))
    }

    @Test
    fun dropDuplicateWhenTargetAlreadyInPlaylist() {
        // The dead entry comes first: the live entry later in the list must still block the re-link.
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "k10", "external_primary"), entry(2, 1, 10, 1, "k10", "external_primary")),
            mapOf(10L to song("k10")),
        )
        assertEquals(listOf(ReconcileAction.DropDuplicate(1)), plan)
    }

    @Test
    fun ambiguousKeyRelinksNothing() {
        val plan = planReconcile(listOf(entry(1, 1, 99, 0, "k", "external_primary")), mapOf(10L to song("k"), 20L to song("k")))
        assertEquals(nothing, plan)
    }

    @Test
    fun emptyIdentityMapPlansNothing() {
        assertEquals(nothing, planReconcile(listOf(entry(1, 1, 10, 0), entry(2, 1, 99, 1, "k10")), emptyMap()))
    }

    @Test
    fun twoPlaylistsWithSameDeadSongBothRelink() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "k10", "external_primary"), entry(2, 2, 99, 0, "k10", "external_primary")),
            mapOf(10L to song("k10")),
        )
        assertEquals(listOf(ReconcileAction.Relink(1, 10, song("k10"), 99), ReconcileAction.Relink(2, 10, song("k10"), 99)), plan)
    }

    @Test
    fun deterministicAndIdempotent() {
        val entries = listOf(
            entry(1, 1, 10, 0),
            entry(2, 1, 99, 1, "k20", "external_primary"),
            entry(3, 1, 98, 2, "k10", "external_primary"),
            entry(4, 2, 97, 0, "k30", "1234-abcd"),
        )
        val songs = mapOf(10L to song("k10"), 20L to song("k20"), 30L to song("k30", "external_primary"))
        val plan = planReconcile(entries, songs)
        assertEquals(plan, planReconcile(entries, songs))
        val after = apply(entries, plan)
        assertEquals(nothing, planReconcile(after, songs))
        // Positions of surviving entries are untouched by the planner; entry 4 (other volume) stays dead.
        assertTrue(after.any { it.id == 4L && it.songId == 97L })
        assertEquals(99L, after.single { it.id == 2L }.relinkedFromSongId)
    }
}
