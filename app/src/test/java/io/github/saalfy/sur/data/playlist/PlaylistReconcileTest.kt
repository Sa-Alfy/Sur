package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.db.PlaylistEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistReconcileTest {

    private fun entry(id: Long, playlistId: Long, songId: Long, position: Int, songKey: String? = null) =
        PlaylistEntryEntity(id = id, playlistId = playlistId, songId = songId, position = position, songKey = songKey)

    @Test
    fun backfillNullKey() {
        val plan = planReconcile(
            listOf(entry(1, 1, 10, 0)),
            mapOf(10L to "k10"),
        )
        assertEquals(listOf(ReconcileAction.SetKey(1, "k10")), plan)
    }

    @Test
    fun refreshChangedKey() {
        val plan = planReconcile(
            listOf(entry(1, 1, 10, 0, "old")),
            mapOf(10L to "new"),
        )
        assertEquals(listOf(ReconcileAction.SetKey(1, "new")), plan)
    }

    @Test
    fun relinkDeadIdByKey() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "k10")),
            mapOf(10L to "k10"),
        )
        assertEquals(listOf(ReconcileAction.Relink(1, 10, "k10")), plan)
    }

    @Test
    fun noRelinkWhenKeyUnknown() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "kX")),
            mapOf(10L to "k10"),
        )
        assertEquals(emptyList<ReconcileAction>(), plan)
    }

    @Test
    fun deadEntryWithNullKeyUntouched() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0)),
            mapOf(10L to "k10"),
        )
        assertEquals(emptyList<ReconcileAction>(), plan)
    }

    @Test
    fun dropDuplicateWhenTargetAlreadyInPlaylist() {
        // The dead entry comes first: the live entry later in the list must
        // still block the re-link, or the relink would violate the unique index.
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "k10"), entry(2, 1, 10, 1, "k10")),
            mapOf(10L to "k10"),
        )
        assertEquals(listOf(ReconcileAction.DropDuplicate(1)), plan)
    }

    @Test
    fun ambiguousKeyRelinksNothing() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "k")),
            mapOf(10L to "k", 20L to "k"),
        )
        assertEquals(emptyList<ReconcileAction>(), plan)
    }

    @Test
    fun emptyKeyMapPlansNothing() {
        val plan = planReconcile(
            listOf(entry(1, 1, 10, 0), entry(2, 1, 99, 1, "k10")),
            emptyMap(),
        )
        assertEquals(emptyList<ReconcileAction>(), plan)
    }

    @Test
    fun twoPlaylistsWithSameDeadSongBothRelink() {
        val plan = planReconcile(
            listOf(entry(1, 1, 99, 0, "k10"), entry(2, 2, 99, 0, "k10")),
            mapOf(10L to "k10"),
        )
        assertEquals(
            listOf(ReconcileAction.Relink(1, 10, "k10"), ReconcileAction.Relink(2, 10, "k10")),
            plan,
        )
    }
}
