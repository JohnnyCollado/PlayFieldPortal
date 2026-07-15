package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.SyncedCoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCoinMapperTest {

    private fun schema(
        name: String,
        displayName: String? = name,
        description: String? = "",
        hidden: Int = 0,
    ) = SteamSchemaAchievement(name = name, displayName = displayName, description = description, hidden = hidden)

    private fun earned(name: String, unlockAt: Long = 1_700_000_000) =
        SteamPlayerAchievement(apiname = name, achieved = 1, unlocktime = unlockAt)

    private fun map(
        vararg achievements: SteamSchemaAchievement,
        percents: Map<String, Double> = emptyMap(),
        earned: Map<String, SteamPlayerAchievement> = emptyMap(),
    ) = SteamCoinMapper.map("440", achievements.toList(), percents, earned)

    @Test
    fun `tiers by rarity - gold under 10, silver 10 to 25, bronze at 25 and above`() {
        val coins = map(
            schema("rare"), schema("mid"), schema("common"),
            percents = mapOf("rare" to 9.9, "mid" to 24.99, "common" to 25.0),
        ).associateBy { it.providerAchievementId }

        assertEquals(ShibaTier.GOLD, coins.getValue("rare").tier)
        assertEquals(ShibaTier.SILVER, coins.getValue("mid").tier)
        assertEquals(ShibaTier.BRONZE, coins.getValue("common").tier)
    }

    @Test
    fun `hidden achievements tier by rarity like any other - hidden does not mean rare`() {
        val coins = map(
            schema("secret", hidden = 1),
            percents = mapOf("secret" to 55.0),
        )
        assertEquals(ShibaTier.BRONZE, coins.single().tier)
        assertTrue(coins.single().isHidden)
    }

    @Test
    fun `missing rarity is Bronze with the unavailable sentinel, exact zero is Gold`() {
        val coins = map(
            schema("nodata"), schema("zero"),
            percents = mapOf("zero" to 0.0),
        ).associateBy { it.providerAchievementId }

        val noData = coins.getValue("nodata")
        assertEquals(ShibaTier.BRONZE, noData.tier)
        assertEquals(SyncedCoin.RARITY_UNAVAILABLE, noData.globalRarity, 1e-9)

        assertEquals(ShibaTier.GOLD, coins.getValue("zero").tier)
    }

    @Test
    fun `a completion description is Platinum and overrides rarity`() {
        val coins = map(
            schema("all1", displayName = "Perfectionist", description = "Unlock every other achievement."),
            schema("all2", displayName = "Master", description = "Earn all other achievements in the game"),
            schema("all3", displayName = "Done", description = "Complete all achievements"),
            percents = mapOf("all1" to 1.8, "all2" to 80.0, "all3" to 40.0),
        )
        assertTrue(coins.all { it.tier == ShibaTier.PLATINUM })
    }

    @Test
    fun `names alone never make a Platinum - only the description counts`() {
        val coins = map(
            schema("p1", displayName = "Platinum", description = "Win 10 ranked matches"),
            schema("p2", displayName = "Completionist", description = "Finish the story"),
            schema("p3", displayName = "Unlock Every Achievement", description = "Reach level 100"),
            percents = mapOf("p1" to 3.0, "p2" to 30.0, "p3" to 15.0),
        ).associateBy { it.providerAchievementId }

        assertEquals(ShibaTier.GOLD, coins.getValue("p1").tier)
        assertEquals(ShibaTier.BRONZE, coins.getValue("p2").tier)
        assertEquals(ShibaTier.SILVER, coins.getValue("p3").tier)
    }

    @Test
    fun `earned state and unlock time map through`() {
        val coins = map(
            schema("a"), schema("b"),
            earned = mapOf("a" to earned("a", unlockAt = 1_700_000_000)),
        ).associateBy { it.providerAchievementId }

        val a = coins.getValue("a")
        assertTrue(a.isEarned)
        assertTrue(a.earnedHardcore) // Steam mirrors isEarned
        assertEquals(1_700_000_000_000L, a.earnedAt)

        val b = coins.getValue("b")
        assertFalse(b.isEarned)
        assertNull(b.earnedAt)
    }
}
