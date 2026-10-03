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

    // ── Display ▸ UMD Slot ───────────────────────────────────────────────────

    @Test
    fun `Off never fills the slot, inserted or not`() {
        val column = listOf(game(1, lastPlayedAt = 900), game(2))
        assertNull(UmdSlotResolver.resolve(inserted = game(2), columnGames = column, mode = UmdSlotMode.OFF))
        assertNull(UmdSlotResolver.resolve(inserted = null, columnGames = column, mode = UmdSlotMode.OFF))
    }

    @Test
    fun `Inserted shows only an inserted game - no last-played fallback`() {
        val column = listOf(game(1, lastPlayedAt = 900), game(2))
        assertEquals(2L, UmdSlotResolver.resolve(inserted = game(2), columnGames = column, mode = UmdSlotMode.INSERTED)?.id)
        assertNull(UmdSlotResolver.resolve(inserted = null, columnGames = column, mode = UmdSlotMode.INSERTED))
        // An inserted game that left the column empties the slot rather than falling back.
        assertNull(UmdSlotResolver.resolve(inserted = game(7), columnGames = column, mode = UmdSlotMode.INSERTED))
    }

    @Test
    fun `Inserted & Recent is today's slot and the default`() {
        val column = listOf(game(1, lastPlayedAt = 900), game(2))
        assertEquals(UmdSlotMode.INSERTED_AND_RECENT, UmdSlotMode.DEFAULT)
        assertEquals(1L, UmdSlotResolver.resolve(inserted = null, columnGames = column)?.id)
    }

    @Test
    fun `the setting's labels and stored names`() {
        assertEquals(listOf("Off", "Inserted", "Inserted & Recent"), UmdSlotMode.entries.map { it.label })
        for (mode in UmdSlotMode.entries) assertEquals(mode, UmdSlotMode.fromName(mode.name))
        assertEquals(UmdSlotMode.DEFAULT, UmdSlotMode.fromName("SOMETHING_ELSE"))
        assertEquals(UmdSlotMode.DEFAULT, UmdSlotMode.fromName(null))
    }
}
