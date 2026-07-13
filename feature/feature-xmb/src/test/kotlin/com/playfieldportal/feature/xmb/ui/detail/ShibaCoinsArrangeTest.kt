package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.ShibaTier
import org.junit.Test
import kotlin.test.assertEquals

class ShibaCoinsArrangeTest {

    private fun row(id: String, tier: ShibaTier, rarity: Double, earned: Boolean, earnedAt: Long? = null) =
        CoinRow(id, tier, id, "", rarity, null, isHidden = false, isEarned = earned, earnedAt = earnedAt)

    private val coins = listOf(
        row("bronze", ShibaTier.BRONZE, 60.0, earned = true, earnedAt = 100),
        row("gold", ShibaTier.GOLD, 2.0, earned = false),
        row("silver", ShibaTier.SILVER, 15.0, earned = true, earnedAt = 300),
    )

    @Test
    fun `rarest sorts by ascending rarity`() {
        val ids = coins.arrange(CoinSort.RAREST, CoinFilter.ALL).map { it.id }
        assertEquals(listOf("gold", "silver", "bronze"), ids)
    }

    @Test
    fun `tier sort puts gold before silver before bronze`() {
        val ids = coins.arrange(CoinSort.TIER, CoinFilter.ALL).map { it.id }
        assertEquals(listOf("gold", "silver", "bronze"), ids)
    }

    @Test
    fun `earned sort lists earned first, newest first`() {
        val ids = coins.arrange(CoinSort.EARNED, CoinFilter.ALL).map { it.id }
        assertEquals(listOf("silver", "bronze", "gold"), ids)
    }

    @Test
    fun `filters earned and locked`() {
        assertEquals(listOf("bronze", "silver"), coins.arrange(CoinSort.TIER, CoinFilter.EARNED).map { it.id }.sorted())
        assertEquals(listOf("gold"), coins.arrange(CoinSort.TIER, CoinFilter.LOCKED).map { it.id })
    }
}
