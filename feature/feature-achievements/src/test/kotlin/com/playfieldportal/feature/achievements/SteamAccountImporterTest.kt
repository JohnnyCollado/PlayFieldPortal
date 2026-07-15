package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.SteamOwnedGamesDao
import com.playfieldportal.core.data.database.entity.SteamOwnedGameEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.steam.SteamOwnedEntry
import com.playfieldportal.feature.achievements.provider.steam.SteamOwnedGamesResult
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SteamAccountImporterTest {

    private val steamSource = mockk<SteamRemoteDataSource>()
    private val ownedDao = mockk<SteamOwnedGamesDao>(relaxed = true)
    private val setDao = mockk<AccountAchievementSetDao>(relaxed = true)
    private val coinDao = mockk<AccountAchievementDao>(relaxed = true)
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val repository = mockk<AchievementRepository>()

    private val importer = SteamAccountImporter(steamSource, ownedDao, setDao, coinDao, linkDao, repository)

    private fun owned(appid: String, playtime: Long = 60, synced: Long? = null) = SteamOwnedGameEntity(
        appid = appid, name = "Game $appid",
        playtimeForeverMinutes = playtime, syncedPlaytimeMinutes = synced, fetchedAt = 0L,
    )

    private fun coin(earned: Boolean) = SyncedCoin(
        "c", "c", "", ShibaTier.BRONZE, 10.0, null,
        isHidden = false, isEarned = earned, earnedHardcore = earned, earnedAt = null,
    )

    private fun success(vararg earned: Boolean) =
        ProviderSyncResult.Success("x", earned.map { coin(it) })

    @Test
    fun `first import walks every non-memoized owned game and tallies outcomes`() = runTest {
        coEvery { steamSource.ownedGames() } returns SteamOwnedGamesResult.Success(
            listOf(SteamOwnedEntry("10", "Game 10", 60)),
        )
        coEvery { ownedDao.noAchievementAppids() } returns listOf("40") // memo'd from a past run
        coEvery { ownedDao.getAll() } returns listOf(
            owned("10"), owned("20"), owned("30"), owned("40"),
        )
        coEvery { repository.syncAccountEntry(any(), "10", any()) } returns success(true, false)
        coEvery { repository.syncAccountEntry(any(), "20", any()) } returns ProviderSyncResult.NotFound
        coEvery { repository.syncAccountEntry(any(), "30", any()) } returns ProviderSyncResult.Failed("network error")

        val result = importer.import()

        assertEquals(3, result.total) // the memo'd game was never a candidate
        assertEquals(1, result.imported)
        assertEquals(1, result.noCoins)
        assertEquals(1, result.failed)
        coVerify { ownedDao.rememberNoAchievements(match { it.appid == "20" }) }
        coVerify { ownedDao.markSynced("10") }
        coVerify { ownedDao.markSynced("20") }
        coVerify(exactly = 0) { repository.syncAccountEntry(any(), "40", any()) }
    }

    @Test
    fun `a re-run touches only games played since the last import`() = runTest {
        coEvery { steamSource.ownedGames() } returns SteamOwnedGamesResult.Success(emptyList())
        coEvery { ownedDao.noAchievementAppids() } returns emptyList()
        coEvery { ownedDao.getAll() } returns listOf(
            owned("10", playtime = 60, synced = 60),   // unchanged -> skipped
            owned("20", playtime = 90, synced = 60),   // played since -> re-synced
        )
        coEvery { repository.syncAccountEntry(any(), "20", any()) } returns success(true)

        val result = importer.import()

        assertEquals(1, result.total)
        coVerify(exactly = 0) { repository.syncAccountEntry(any(), "10", any()) }
    }

    @Test
    fun `a zero-earned account-only set is discarded and counted as no progress`() = runTest {
        coEvery { steamSource.ownedGames() } returns SteamOwnedGamesResult.Success(emptyList())
        coEvery { ownedDao.noAchievementAppids() } returns emptyList()
        coEvery { ownedDao.getAll() } returns listOf(owned("10"))
        coEvery { repository.syncAccountEntry(any(), "10", any()) } returns success(false, false)
        coEvery { linkDao.linkExistsFor("STEAM", "10") } returns false

        val result = importer.import()

        assertEquals(1, result.noProgress)
        assertEquals(0, result.imported)
        coVerify { setDao.deleteSet("STEAM", "10") }
        coVerify { coinDao.deleteForSet("STEAM", "10") }
        coVerify { ownedDao.markSynced("10") } // not re-probed until played
    }

    @Test
    fun `a zero-earned set linked to a library game is kept`() = runTest {
        coEvery { steamSource.ownedGames() } returns SteamOwnedGamesResult.Success(emptyList())
        coEvery { ownedDao.noAchievementAppids() } returns emptyList()
        coEvery { ownedDao.getAll() } returns listOf(owned("10"))
        coEvery { repository.syncAccountEntry(any(), "10", any()) } returns success(false)
        coEvery { linkDao.linkExistsFor("STEAM", "10") } returns true

        importer.import()

        coVerify(exactly = 0) { setDao.deleteSet(any(), any()) }
    }

    @Test
    fun `a hidden-details profile stops before any sync`() = runTest {
        coEvery { steamSource.ownedGames() } returns SteamOwnedGamesResult.ProfileNotPublic

        val result = importer.import()

        assertTrue(result.profileNotPublic)
        coVerify(exactly = 0) { repository.syncAccountEntry(any(), any(), any()) }
    }

    @Test
    fun `profile turning private mid-run aborts the remaining candidates`() = runTest {
        coEvery { steamSource.ownedGames() } returns SteamOwnedGamesResult.Success(emptyList())
        coEvery { ownedDao.noAchievementAppids() } returns emptyList()
        coEvery { ownedDao.getAll() } returns listOf(owned("10"), owned("20"))
        coEvery { repository.syncAccountEntry(any(), "10", any()) } returns ProviderSyncResult.ProfileNotPublic

        val result = importer.import()

        assertTrue(result.profileNotPublic)
        coVerify(exactly = 0) { repository.syncAccountEntry(any(), "20", any()) }
    }

    @Test
    fun `owned cache refresh preserves sync bookmarks through replaceOwned`() = runTest {
        coEvery { steamSource.ownedGames() } returns SteamOwnedGamesResult.Success(
            listOf(SteamOwnedEntry("10", "Game 10", 120)),
        )
        coEvery { ownedDao.noAchievementAppids() } returns emptyList()
        coEvery { ownedDao.getAll() } returns emptyList()

        importer.import()

        coVerify {
            ownedDao.replaceOwned(match { it.single().appid == "10" && it.single().playtimeForeverMinutes == 120L })
        }
        coVerify(exactly = 0) { repository.syncAccountEntry(AchievementProvider.STEAM, any(), any()) }
    }
}
