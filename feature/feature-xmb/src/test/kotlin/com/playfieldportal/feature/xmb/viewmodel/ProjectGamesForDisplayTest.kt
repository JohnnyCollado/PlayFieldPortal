package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card subtitles must count exactly the rows the card's list shows. Both read the DAO's projection
 * (present singles, plus the primary of every set that still has a present disc), so a set whose
 * primary is itself missing is still one game — the list shows it, and the count has to agree.
 */
class ProjectGamesForDisplayTest {

    private fun game(
        title: String,
        setKey: String? = null,
        disc: Int? = null,
        primary: Boolean = false,
        missing: Boolean = false,
        favorite: Boolean = false,
    ) = Game(
        title = title,
        platformId = "psx",
        romPath = "/roms/psx/$title.cue",
        discSetKey = setKey,
        discNumber = disc,
        isDiscPrimary = primary,
        isMissing = missing,
        isFavorite = favorite,
    )

    @Test
    fun `a set whose primary is missing still counts while the list shows it`() {
        // What observeAll emits when disc 1 is gone but disc 2 is present: the primary row only.
        val snapshot = listOf(
            game("Dino Crisis"),
            game("Parasite Eve II (Disc 1)", setKey = "pe2", disc = 1, primary = true, missing = true),
        )

        assertEquals(2, snapshot.projectGamesForDisplay().size)
    }

    @Test
    fun `a missing single is not counted`() {
        val snapshot = listOf(game("Dino Crisis"), game("Gone", missing = true))

        assertEquals(listOf("Dino Crisis"), snapshot.projectGamesForDisplay().map { it.title })
    }

    @Test
    fun `a set counts once and shows its primary`() {
        val snapshot = listOf(
            game("Final Fantasy VII (Disc 2)", setKey = "ff7", disc = 2),
            game("Final Fantasy VII (Disc 1)", setKey = "ff7", disc = 1, primary = true),
        )

        assertEquals(listOf("Final Fantasy VII (Disc 1)"), snapshot.projectGamesForDisplay().map { it.title })
    }

    @Test
    fun `a favorite on any disc makes the set's row a favorite`() {
        val snapshot = listOf(
            game("Final Fantasy VII (Disc 1)", setKey = "ff7", disc = 1, primary = true),
            game("Final Fantasy VII (Disc 2)", setKey = "ff7", disc = 2, favorite = true),
        )

        assertTrue(snapshot.projectGamesForDisplay().single().isFavorite)
    }

    @Test
    fun `a set with no primary row falls back to a present disc, and is dropped without one`() {
        val snapshot = listOf(
            game("A (Disc 1)", setKey = "a", disc = 1, missing = true),
            game("A (Disc 2)", setKey = "a", disc = 2),
            game("B (Disc 1)", setKey = "b", disc = 1, missing = true),
        )

        assertEquals(listOf("A (Disc 2)"), snapshot.projectGamesForDisplay().map { it.title })
    }
}
