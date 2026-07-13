package com.playfieldportal.core.domain.achievement

import org.junit.Assert.assertEquals
import org.junit.Test

class ShibaTierTest {

    @Test
    fun `coin values follow the PSN ratio`() {
        assertEquals(15, ShibaTier.BRONZE.coinValue)
        assertEquals(30, ShibaTier.SILVER.coinValue)
        assertEquals(90, ShibaTier.GOLD.coinValue)
        assertEquals(300, ShibaTier.PLATINUM.coinValue)
    }

    @Test
    fun `rarity below 5 percent is Gold`() {
        assertEquals(ShibaTier.GOLD, ShibaTier.forRarity(0.5))
        assertEquals(ShibaTier.GOLD, ShibaTier.forRarity(4.99))
    }

    @Test
    fun `rarity between 5 and 20 percent is Silver`() {
        assertEquals(ShibaTier.SILVER, ShibaTier.forRarity(5.0))
        assertEquals(ShibaTier.SILVER, ShibaTier.forRarity(19.99))
    }

    @Test
    fun `rarity at or above 20 percent is Bronze`() {
        assertEquals(ShibaTier.BRONZE, ShibaTier.forRarity(20.0))
        assertEquals(ShibaTier.BRONZE, ShibaTier.forRarity(93.7))
    }

    @Test
    fun `non-positive rarity is treated as ultra-rare Gold`() {
        assertEquals(ShibaTier.GOLD, ShibaTier.forRarity(0.0))
        assertEquals(ShibaTier.GOLD, ShibaTier.forRarity(-1.0))
    }

    @Test
    fun `forRarity never returns Platinum`() {
        val tiers = (0..100).map { ShibaTier.forRarity(it.toDouble()) }
        assertEquals(false, tiers.contains(ShibaTier.PLATINUM))
    }
}
