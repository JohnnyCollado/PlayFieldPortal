package com.playfieldportal.core.domain.achievement

import org.junit.Assert.assertEquals
import org.junit.Test

class ShibaLevelTest {

    // Cumulative coins to reach the start of each band boundary — the plan's calibration table.
    @Test
    fun `coinsForLevel matches the banded curve`() {
        assertEquals(0, ShibaLevel.coinsForLevel(1))
        assertEquals(100, ShibaLevel.coinsForLevel(2))
        assertEquals(900, ShibaLevel.coinsForLevel(10))
        assertEquals(4_650, ShibaLevel.coinsForLevel(25))
        assertEquals(19_650, ShibaLevel.coinsForLevel(50))
        assertEquals(49_650, ShibaLevel.coinsForLevel(75))
        assertEquals(99_650, ShibaLevel.coinsForLevel(100))
    }

    @Test
    fun `coinsForLevel and levelForCoins are inverse at boundaries`() {
        for (level in intArrayOf(1, 2, 10, 25, 50, 75, 100, 150)) {
            val coins = ShibaLevel.coinsForLevel(level)
            assertEquals("level $level", level, ShibaLevel.levelForCoins(coins))
        }
    }

    @Test
    fun `levelForCoins floors between boundaries`() {
        assertEquals(1, ShibaLevel.levelForCoins(0))
        assertEquals(1, ShibaLevel.levelForCoins(99))
        assertEquals(2, ShibaLevel.levelForCoins(100))
        assertEquals(9, ShibaLevel.levelForCoins(899))
        assertEquals(10, ShibaLevel.levelForCoins(900))
    }

    @Test
    fun `non-positive totals are level 1`() {
        assertEquals(1, ShibaLevel.levelForCoins(0))
        assertEquals(1, ShibaLevel.levelForCoins(-500))
    }

    @Test
    fun `open-ended tail keeps costing 3000 per level`() {
        assertEquals(101, ShibaLevel.levelForCoins(99_650 + 3_000))
        assertEquals(110, ShibaLevel.levelForCoins(99_650 + 10 * 3_000))
    }

    @Test
    fun `progress reports position within the current level`() {
        val p = ShibaLevel.progress(50)
        assertEquals(1, p.level)
        assertEquals(50, p.coinsIntoLevel)
        assertEquals(100, p.coinsForNextLevel)
        assertEquals(0.5f, p.fraction, 0.0001f)
    }

    @Test
    fun `bones mint once per completed 100-level cycle`() {
        assertEquals(0, ShibaLevel.bonesForLevel(1))
        assertEquals(0, ShibaLevel.bonesForLevel(100))  // cycle not completed until the NEXT level
        assertEquals(1, ShibaLevel.bonesForLevel(101))  // 100 levels earned
        assertEquals(1, ShibaLevel.bonesForLevel(200))
        assertEquals(2, ShibaLevel.bonesForLevel(201))
        assertEquals(9, ShibaLevel.bonesForLevel(999))
    }

    @Test
    fun `cycle level rolls over like an odometer`() {
        assertEquals(1, ShibaLevel.cycleLevelFor(1))
        assertEquals(100, ShibaLevel.cycleLevelFor(100))
        assertEquals(1, ShibaLevel.cycleLevelFor(101))
        assertEquals(43, ShibaLevel.cycleLevelFor(143)) // 100 completed + 43 into the next cycle
        assertEquals(100, ShibaLevel.cycleLevelFor(200))
    }

    @Test
    fun `rankFor maps levels to the five evenly-spread bands`() {
        assertEquals(ShibaRank.PUP, ShibaLevel.rankFor(1))
        assertEquals(ShibaRank.PUP, ShibaLevel.rankFor(24))
        assertEquals(ShibaRank.RUFFIAN, ShibaLevel.rankFor(25))
        assertEquals(ShibaRank.LONE_RON_INU, ShibaLevel.rankFor(50))
        assertEquals(ShibaRank.INU_MASTER, ShibaLevel.rankFor(75))
        assertEquals(ShibaRank.LEGENDARY_HACHIKO, ShibaLevel.rankFor(100))
        assertEquals(ShibaRank.LEGENDARY_HACHIKO, ShibaLevel.rankFor(999)) // earned once, kept forever
    }
}
