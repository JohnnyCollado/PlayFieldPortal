package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.retro.RaProgressEntry
import com.playfieldportal.feature.achievements.provider.retro.RaProgressResult
import com.playfieldportal.feature.achievements.provider.retro.RaRemoteDataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaAccountImporterTest {

    private val raSource = mockk<RaRemoteDataSource>()
    private val setDao = mockk<AccountAchievementSetDao>(relaxed = true)
    private val repository = mockk<AchievementRepository>()

    private val importer = RaAccountImporter(raSource, setDao, repository)

    private fun entry(gameId: String, earned: Long) = RaProgressEntry(
        gameId = gameId,
        title = "Game $gameId",
        iconUrl = "https://media.retroachievements.org/Images/$gameId.png",
        earned = earned,
        totalAchievements = 30,
    )

    private fun stub(gameId: String) = AccountAchievementSetEntity(
        provider = "RETRO_ACHIEVEMENTS",
        providerGameId = gameId,
        title = "Game $gameId",
    )

    @Test
    fun `stubs land for played games only, then each stub syncs full detail`() = runTest {
        coEvery { raSource.userCompletionProgress() } returns RaProgressResult.Success(
            listOf(entry("319", earned = 12), entry("14402", earned = 1), entry("777", earned = 0)),
        )
        coEvery { setDao.getUnsyncedSets("RETRO_ACHIEVEMENTS") } returns listOf(stub("319"), stub("14402"))
        coEvery { repository.syncAccountEntry(any(), any(), any()) } returns
            ProviderSyncResult.Success("319", emptyList())

        val result = importer.import()

        // The zero-progress game never becomes a stub.
        coVerify(exactly = 0) { setDao.insertIfAbsent(match { it.providerGameId == "777" }) }
        coVerify { setDao.insertIfAbsent(match { it.providerGameId == "319" && it.title == "Game 319" }) }
        coVerify { setDao.backfillIcon("RETRO_ACHIEVEMENTS", "319", any()) }
        coVerify { repository.syncAccountEntry(AchievementProvider.RETRO_ACHIEVEMENTS, "319", "Game 319") }
        coVerify { repository.syncAccountEntry(AchievementProvider.RETRO_ACHIEVEMENTS, "14402", "Game 14402") }
        assertEquals(2, result.total)
        assertEquals(2, result.imported)
    }

    @Test
    fun `progress reports each stub and finishes at total`() = runTest {
        coEvery { raSource.userCompletionProgress() } returns RaProgressResult.Success(
            listOf(entry("1", earned = 1), entry("2", earned = 1)),
        )
        coEvery { setDao.getUnsyncedSets(any()) } returns listOf(stub("1"), stub("2"))
        coEvery { repository.syncAccountEntry(any(), any(), any()) } returns
            ProviderSyncResult.Success("1", emptyList())

        val progress = mutableListOf<Pair<Int, Int>>()
        importer.import { done, total -> progress += done to total }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
    }

    @Test
    fun `outcomes tally imported, noCoins and failed`() = runTest {
        coEvery { raSource.userCompletionProgress() } returns RaProgressResult.Success(emptyList())
        coEvery { setDao.getUnsyncedSets(any()) } returns listOf(stub("1"), stub("2"), stub("3"))
        coEvery { repository.syncAccountEntry(any(), "1", any()) } returns ProviderSyncResult.Success("1", emptyList())
        coEvery { repository.syncAccountEntry(any(), "2", any()) } returns ProviderSyncResult.NotFound
        coEvery { repository.syncAccountEntry(any(), "3", any()) } returns ProviderSyncResult.Failed("network error")

        val result = importer.import()

        assertEquals(1, result.imported)
        assertEquals(1, result.noCoins)
        assertEquals(1, result.failed)
    }

    @Test
    fun `missing credentials on the walk stops before any writes`() = runTest {
        coEvery { raSource.userCompletionProgress() } returns RaProgressResult.MissingCredentials

        val result = importer.import()

        assertTrue(result.missingCredentials)
        coVerify(exactly = 0) { setDao.insertIfAbsent(any()) }
    }

    @Test
    fun `missing credentials mid-run aborts the remaining stubs`() = runTest {
        coEvery { raSource.userCompletionProgress() } returns RaProgressResult.Success(emptyList())
        coEvery { setDao.getUnsyncedSets(any()) } returns listOf(stub("1"), stub("2"))
        coEvery { repository.syncAccountEntry(any(), "1", any()) } returns ProviderSyncResult.MissingCredentials

        val result = importer.import()

        assertTrue(result.missingCredentials)
        coVerify(exactly = 0) { repository.syncAccountEntry(any(), "2", any()) }
    }
}
