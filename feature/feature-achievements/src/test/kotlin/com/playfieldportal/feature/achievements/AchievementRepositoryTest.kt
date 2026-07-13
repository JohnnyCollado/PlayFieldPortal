package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AchievementDao
import com.playfieldportal.core.data.database.dao.AchievementSetDao
import com.playfieldportal.core.data.database.entity.AchievementSetEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RetroAchievementsApi
import com.playfieldportal.feature.achievements.api.SteamAchievementsApi
import com.playfieldportal.feature.achievements.api.SyncedCoin
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AchievementRepositoryTest {

    private val steamApi = mockk<SteamAchievementsApi>()
    private val retroApi = mockk<RetroAchievementsApi>()
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val setDao = mockk<AchievementSetDao>(relaxed = true)
    private val coinDao = mockk<AchievementDao>(relaxed = true)

    private val repo = AchievementRepository(steamApi, retroApi, credentials, setDao, coinDao)

    private fun coin(id: String, tier: ShibaTier, earned: Boolean) =
        SyncedCoin(id, id, "", tier, 0.0, null, isHidden = false, isEarned = earned, earnedAt = if (earned) 1L else null)

    @Test
    fun `observeGameCoins maps the stored summary to domain`() = runTest {
        every { setDao.observeForGame(1L) } returns flowOf(
            AchievementSetEntity(
                gameId = 1L, provider = "RETRO_ACHIEVEMENTS", providerGameId = "14402",
                bronzeTotal = 24, silverTotal = 16, goldTotal = 7,
                bronzeEarned = 23, silverEarned = 15, goldEarned = 6,
            ),
        )

        val coins = repo.observeGameCoins(1L).first()!!
        assertEquals(AchievementProvider.RETRO_ACHIEVEMENTS, coins.provider)
        assertEquals(23 * 15 + 15 * 30 + 6 * 90, coins.earnedCoinValue)
        assertFalse(coins.isMastered)
    }

    @Test
    fun `syncGame writes coins and a summary with per-tier counts`() = runTest {
        coEvery { steamApi.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(
                coin("g1", ShibaTier.GOLD, earned = true),
                coin("b1", ShibaTier.BRONZE, earned = false),
                coin("b2", ShibaTier.BRONZE, earned = true),
            ),
        )
        val setSlot = slot<AchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        val result = repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertTrue(result is ProviderSyncResult.Success)
        coVerify { coinDao.deleteForGame(1L) }
        coVerify { coinDao.upsertAll(match { it.size == 3 }) }
        coVerify { credentials.setLastSyncedAt(any()) }

        val summary = setSlot.captured
        assertEquals(2, summary.bronzeTotal)
        assertEquals(1, summary.bronzeEarned)
        assertEquals(1, summary.goldTotal)
        assertEquals(1, summary.goldEarned)
        assertFalse(summary.mastered) // not every coin earned
    }

    @Test
    fun `syncGame marks mastered when every coin is earned`() = runTest {
        coEvery { steamApi.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(coin("g1", ShibaTier.GOLD, earned = true), coin("b1", ShibaTier.BRONZE, earned = true)),
        )
        val setSlot = slot<AchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertTrue(setSlot.captured.mastered)
    }

    @Test
    fun `a non-success result leaves the database untouched`() = runTest {
        coEvery { steamApi.fetch("440") } returns ProviderSyncResult.ProfileNotPublic

        val result = repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertEquals(ProviderSyncResult.ProfileNotPublic, result)
        coVerify(exactly = 0) { coinDao.upsertAll(any()) }
        coVerify(exactly = 0) { setDao.upsert(any()) }
    }
}
