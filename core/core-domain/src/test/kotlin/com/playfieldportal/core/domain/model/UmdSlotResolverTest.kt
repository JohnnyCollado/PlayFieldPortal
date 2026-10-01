package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which game a gaming column's UMD slot shows: the one the user inserted while it is still in the
 * column, otherwise the column's most recently played game, otherwise nothing.
 */
class UmdSlotResolverTest {

    private fun game(id: Long, lastPlayedAt: Long? = null, discSetKey: String? = null) = Game(
        id = id,
        title = "Game $id",
        platformId = "psx",
        romPath = "/roms/psx/$id.cue",
        lastPlayedAt = lastPlayedAt,
        discSetKey = discSetKey,
    )

    @Test
    fun `the inserted game is shown while it is in the column`() {
        val column = listOf(game(1, lastPlayedAt = 900), game(2))

        assertEquals(2L, UmdSlotResolver.resolve(inserted = game(2), columnGames = column)?.id)
    }

    @Test
    fun `an inserted disc resolves to its set's row in the column`() {
        // Disc 2 was inserted; the column lists the set once, as its primary (id 10).
        val column = listOf(game(10, discSetKey = "ff7"), game(3, lastPlayedAt = 500))

        assertEquals(
            10L,
            UmdSlotResolver.resolve(inserted = game(11, discSetKey = "ff7"), columnGames = column)?.id,
        )
    }

    @Test
    fun `an inserted game that left the column falls back to last played`() {
        val column = listOf(game(1, lastPlayedAt = 100), game(2, lastPlayedAt = 300))

        assertEquals(2L, UmdSlotResolver.resolve(inserted = game(9), columnGames = column)?.id)
    }

    @Test
    fun `nothing inserted shows the most recently played game`() {
        val column = listOf(game(1, lastPlayedAt = 100), game(2), game(3, lastPlayedAt = 300))

        assertEquals(3L, UmdSlotResolver.resolve(inserted = null, columnGames = column)?.id)
    }

    // ── Ejected: empty until the next game played ─────────────────────────────

    @Test
    fun `an ejected slot stays empty while nothing was played since`() {
        val column = listOf(game(1, lastPlayedAt = 100), game(2, lastPlayedAt = 300))

        assertNull(UmdSlotResolver.resolve(inserted = null, columnGames = column, ejectedAt = 400))
    }

    @Test
    fun `the next game played after an eject fills the slot`() {
        val column = listOf(game(1, lastPlayedAt = 100), game(2, lastPlayedAt = 500))

        assertEquals(2L, UmdSlotResolver.resolve(inserted = null, columnGames = column, ejectedAt = 400)?.id)
    }

    @Test
    fun `an inserted game fills an ejected slot`() {
        // Insert clears the eject in storage; the resolver still lets an insert win outright.
        val column = listOf(game(1, lastPlayedAt = 100), game(2))

        assertEquals(2L, UmdSlotResolver.resolve(inserted = game(2), columnGames = column, ejectedAt = 400)?.id)
    }

    @Test
    fun `nothing inserted and nothing played leaves the slot empty`() {
        assertNull(UmdSlotResolver.resolve(inserted = null, columnGames = listOf(game(1), game(2))))
        assertNull(UmdSlotResolver.resolve(inserted = null, columnGames = emptyList()))
    }
}
