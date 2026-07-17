package com.playfieldportal.core.domain.achievement

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryStandingTest {

    private fun standing(id: Long, bronzeEarned: Int, bronzeTotal: Int, mastered: Boolean = false) =
        GameStanding(
            providerGameId = id.toString(),
            libraryGameId = id,
            title = "Game $id",
            iconUrl = null,
            coins = GameCoins(
                provider = AchievementProvider.RETRO_ACHIEVEMENTS,
                earned = CoinCounts(bronze = if (mastered) bronzeTotal else bronzeEarned),
                total = CoinCounts(bronze = bronzeTotal),
                isMastered = mastered,
            ),
        )

    @Test
    fun `counts derive from the tracked list`() {
        val lib = LibraryStanding(
            tracked = listOf(
                standing(1, 10, 10, mastered = true),
                standing(2, 3, 10),
                standing(3, 0, 10),
            ),
        )
        assertEquals(3, lib.gamesTracked)
        assertEquals(1, lib.gamesMastered)
    }

    @Test
    fun `closest to mastery excludes mastered and untouched games, most complete first`() {
        val lib = LibraryStanding(
            tracked = listOf(
                standing(1, 10, 10, mastered = true), // 100% mastered -> excluded
                standing(2, 2, 10),                   // 20%
                standing(3, 8, 10),                   // 80%
                standing(4, 0, 10),                   // 0% untouched -> excluded
            ),
        )
        val closest = lib.closestToMastery()
        assertEquals(listOf(3L, 2L), closest.map { it.libraryGameId })
    }

    @Test
    fun `closest to mastery honors the limit`() {
        val lib = LibraryStanding(
            tracked = (1L..5L).map { standing(it, it.toInt(), 10) },
        )
        assertEquals(2, lib.closestToMastery(limit = 2).size)
    }

    @Test
    fun `all by standing puts mastered games first`() {
        val lib = LibraryStanding(
            tracked = listOf(
                standing(1, 5, 10),
                standing(2, 10, 10, mastered = true),
            ),
        )
        assertEquals(listOf(2L, 1L), lib.allByStanding.map { it.libraryGameId })
    }

    @Test
    fun `walletCounts sums earned coins across every tracked set`() {
        val lib = LibraryStanding(
            tracked = listOf(
                GameStanding("a", 1L, "A", null, GameCoins(AchievementProvider.STEAM, CoinCounts(3, 2, 1), CoinCounts(5, 4, 2), isMastered = false)),
                GameStanding("b", 2L, "B", null, GameCoins(AchievementProvider.RETRO_ACHIEVEMENTS, CoinCounts(10, 1, 0), CoinCounts(10, 1, 0), isMastered = true)),
            ),
        )
        // Platinum is never an individual coin — the mastered set contributes only its Bronze/Silver.
        assertEquals(CoinCounts(bronze = 13, silver = 3, gold = 1), lib.walletCounts)
    }

    @Test
    fun `an empty standing has zero wallet counts`() {
        assertEquals(CoinCounts.EMPTY, LibraryStanding().walletCounts)
    }
}
