package com.playfieldportal.core.data.database.seeder

import com.playfieldportal.core.data.database.entity.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merging duplicate Windows rows onto one survivor.
 *
 * The bug this pins: with three duplicates, each loser was merged onto the survivor as it was read
 * BEFORE the pass — so the second merge rebuilt the row from that stale copy and wrote it over
 * everything the first merge had just brought across. The losers were deleted either way.
 */
class LibraryConsolidationMergeTest {

    private fun row(
        id: Long,
        artworkUri: String? = null,
        playTime: Long = 0L,
        favorite: Boolean = false,
        note: String? = null,
        shortcutId: String? = null,
    ) = GameEntity(
        id = id, title = "Hades", platformId = "windows", romPath = null,
        packageName = "banner.hub", emulatorPackage = null, artworkUri = artworkUri, heroUri = null, logoUri = null,
        description = null, developer = null, publisher = null, releaseYear = null,
        genre = null, steamGridDbId = null,
        totalPlayTimeMillis = playTime, isFavorite = favorite, userNote = note, launchShortcutId = shortcutId,
    )

    private val hour = 3_600_000L

    @Test
    fun `a loser's attributes fill what the survivor lacks`() {
        val survivor = row(1, shortcutId = "sc")
        val loser = row(2, artworkUri = "file:///art/hades.png", playTime = 10 * hour, favorite = true, note = "GOTY")

        val merged = mergedSurvivor(survivor, loser)

        assertEquals(1L, merged.id)
        assertEquals("sc", merged.launchShortcutId)
        assertEquals("file:///art/hades.png", merged.artworkUri)
        assertEquals(10 * hour, merged.totalPlayTimeMillis)
        assertTrue(merged.isFavorite)
        assertEquals("GOTY", merged.userNote)
    }

    @Test
    fun `a third duplicate adds to what the second brought, it does not replace it`() {
        val survivor = row(1, shortcutId = "sc")
        val first = row(2, artworkUri = "file:///art/hades.png", playTime = 10 * hour, favorite = true)
        val second = row(3, playTime = 1 * hour)

        // Each merge onto the result of the one before — the carry the consolidation pass makes.
        val merged = listOf(first, second).fold(survivor, ::mergedSurvivor)

        assertEquals("file:///art/hades.png", merged.artworkUri)
        assertEquals(11 * hour, merged.totalPlayTimeMillis)
        assertTrue(merged.isFavorite)
    }

    @Test
    fun `the survivor's own values are never overwritten by a loser's`() {
        val survivor = row(1, artworkUri = "file:///art/mine.png", note = "mine")
        val loser = row(2, artworkUri = "file:///art/theirs.png", note = "theirs")

        val merged = mergedSurvivor(survivor, loser)

        assertEquals("file:///art/mine.png", merged.artworkUri)
        assertEquals("mine", merged.userNote)
    }
}
