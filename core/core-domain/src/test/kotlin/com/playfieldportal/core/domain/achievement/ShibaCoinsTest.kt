package com.playfieldportal.core.domain.achievement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShibaCoinsTest {

    @Test
    fun `coin counts weight by tier`() {
        val counts = CoinCounts(bronze = 2, silver = 1, gold = 1)
        assertEquals(4, counts.total)
        assertEquals(2 * 15 + 30 + 90, counts.coinValue)
    }

    @Test
    fun `withCoin increments the tier and ignores platinum`() {
        val base = CoinCounts()
        assertEquals(CoinCounts(bronze = 1), base.withCoin(ShibaTier.BRONZE))
        assertEquals(CoinCounts(gold = 1), base.withCoin(ShibaTier.GOLD))
        assertEquals(base, base.withCoin(ShibaTier.PLATINUM))
    }

    @Test
    fun `progress is coin-weighted and excludes the platinum`() {
        // 23 bronze, 15 silver, 6 gold earned of 24 / 16 / 7 available.
        val game = GameCoins(
            provider = AchievementProvider.RETRO_ACHIEVEMENTS,
            earned = CoinCounts(23, 15, 6),
            total = CoinCounts(24, 16, 7),
        )
        val earned = 23 * 15 + 15 * 30 + 6 * 90       // 1335
        val possible = 24 * 15 + 16 * 30 + 7 * 90     // 1470
        assertEquals(earned.toFloat() / possible, game.progress, 0.0001f)
        assertFalse(game.isMastered)
    }

    @Test
    fun `mastery requires every individual coin`() {
        val counts = CoinCounts(3, 2, 1)
        val mastered = GameCoins(AchievementProvider.STEAM, earned = counts, total = counts)
        assertTrue(mastered.isMastered)
        assertEquals(1f, mastered.progress, 0.0001f)
    }

    @Test
    fun `empty set is never mastered and reports zero progress`() {
        val game = GameCoins(AchievementProvider.STEAM, CoinCounts.EMPTY, CoinCounts.EMPTY)
        assertFalse(game.isMastered)
        assertEquals(0f, game.progress, 0.0001f)
    }

    @Test
    fun `wallet contribution banks earned coins plus platinum only on mastery`() {
        val partial = GameCoins(
            AchievementProvider.RETRO_ACHIEVEMENTS,
            earned = CoinCounts(23, 15, 6),
            total = CoinCounts(24, 16, 7),
        )
        assertEquals(1_335, partial.walletContribution)

        val counts = CoinCounts(24, 16, 7)
        val mastered = GameCoins(AchievementProvider.RETRO_ACHIEVEMENTS, counts, counts)
        assertEquals(counts.coinValue + 300, mastered.walletContribution)
    }

    @Test
    fun `wallet aggregates games and resolves a level`() {
        val counts = CoinCounts(24, 16, 7) // 1470 individual + 300 platinum = 1770 mastered
        val games = listOf(
            GameCoins(AchievementProvider.RETRO_ACHIEVEMENTS, counts, counts),
            GameCoins(AchievementProvider.STEAM, CoinCounts(10, 0, 0), CoinCounts(20, 0, 0)),
        )
        val wallet = CoinWallet.of(games)
        assertEquals(1_770 + 150, wallet.totalCoins)
        assertEquals(ShibaLevel.levelForCoins(wallet.totalCoins), wallet.level)
        assertEquals(ShibaLevel.rankFor(wallet.level), wallet.rank)
    }
}
