package com.playfieldportal.feature.achievements.provider.x360

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The X360_ACHIEVEMENT provider: a title's merged GPD achievements become Shiba Coins. */
class X360AchievementSourceTest {

    private val discovery = mockk<X360AchievementDiscovery>()
    private val source = X360AchievementSource(discovery)

    private fun ach(
        id: Int,
        gamerscore: Int = 10,
        secret: Boolean = false,
        earnedAt: Long? = null,
        unlocked: Boolean = earnedAt != null,
    ) = X360AchievementDiscovery.Achievement(
        id = id,
        title = "Achievement $id",
        unlockedDescription = "Did $id",
        lockedDescription = "Do $id",
        gamerscore = gamerscore,
        secret = secret,
        unlocked = unlocked,
        unlockedAtEpochMillis = earnedAt,
        iconUri = if (unlocked) "file:///icons/$id.png" else null,
    )

    @Test
    fun `maps every achievement to a coin keyed by its achievement id`() = runTest {
        coEvery { discovery.load("544307D1") } returns X360AchievementDiscovery.Load.Found(
            titleId = "544307D1",
            titleName = "DEAD OR ALIVE 4",
            achievements = listOf(ach(6, gamerscore = 10), ach(8, gamerscore = 50, earnedAt = 1_790_000_000_000L)),
        )

        val result = assertIs<ProviderSyncResult.Success>(source.fetch("544307D1"))

        assertEquals(listOf("6", "8"), result.coins.map { it.providerAchievementId })
        val earned = result.coins.first { it.providerAchievementId == "8" }
        assertEquals(ShibaTier.GOLD, earned.tier)
        assertTrue(earned.isEarned)
        assertTrue(earned.earnedHardcore)
        assertEquals(1_790_000_000_000L, earned.earnedAt)
        assertEquals("file:///icons/8.png", earned.iconUrl)
        assertEquals(SyncedCoin.RARITY_UNAVAILABLE, earned.globalRarity)
    }

    @Test
    fun `an earned coin shows the unlocked text, a locked one the hint`() = runTest {
        coEvery { discovery.load(any()) } returns X360AchievementDiscovery.Load.Found(
            "544307D1", null, listOf(ach(1), ach(2, earnedAt = 1L)),
        )

        val coins = assertIs<ProviderSyncResult.Success>(source.fetch("544307D1")).coins

        assertEquals("Do 1", coins[0].description)
        assertEquals("Did 2", coins[1].description)
        assertNull(coins[0].iconUrl)
    }

    @Test
    fun `secret achievements are hidden coins`() = runTest {
        coEvery { discovery.load(any()) } returns X360AchievementDiscovery.Load.Found(
            "544307D1", null, listOf(ach(26, secret = true)),
        )
        assertTrue(assertIs<ProviderSyncResult.Success>(source.fetch("544307D1")).coins.single().isHidden)
    }

    @Test
    fun `no linked data folder is a failure that says where to set it`() = runTest {
        coEvery { discovery.load(any()) } returns X360AchievementDiscovery.Load.NotConfigured
        val failed = assertIs<ProviderSyncResult.Failed>(source.fetch("544307D1"))
        assertTrue("Xbox 360" in failed.reason, failed.reason)
    }

    @Test
    fun `a folder with no Xenia profile says so`() = runTest {
        coEvery { discovery.load(any()) } returns X360AchievementDiscovery.Load.NoProfile
        val failed = assertIs<ProviderSyncResult.Failed>(source.fetch("544307D1"))
        assertTrue("profile" in failed.reason, failed.reason)
    }

    @Test
    fun `a title never played yet asks for one run`() = runTest {
        coEvery { discovery.load(any()) } returns X360AchievementDiscovery.Load.NotPlayed
        val failed = assertIs<ProviderSyncResult.Failed>(source.fetch("544307D1"))
        assertTrue("once" in failed.reason, failed.reason)
    }

    @Test
    fun `a title with no achievements at all is not found`() = runTest {
        coEvery { discovery.load(any()) } returns X360AchievementDiscovery.Load.Found("FFED0000", "Demo", emptyList())
        assertEquals(ProviderSyncResult.NotFound, source.fetch("FFED0000"))
    }
}
