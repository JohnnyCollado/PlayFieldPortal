package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSources
import com.playfieldportal.feature.achievements.provider.retro.RetroAchievementsSource
import com.playfieldportal.feature.achievements.provider.steam.SteamAchievementsSource
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

    private val retroSource = mockk<RetroAchievementsSource>()
    private val steamSource = mockk<SteamAchievementsSource>()
    private val remoteSources = RemoteAchievementSources(retroSource, steamSource)
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val setDao = mockk<AccountAchievementSetDao>(relaxed = true)
    private val coinDao = mockk<AccountAchievementDao>(relaxed = true)
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val steamResolver = mockk<SteamAppListResolver>(relaxed = true)
    private val gameRepository = mockk<com.playfieldportal.core.domain.repository.GameRepository>(relaxed = true)
    private val matchNoteDao = mockk<com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao>(relaxed = true)

    private val repo = AchievementRepository(remoteSources, credentials, setDao, coinDao, linkDao, matchNoteDao, steamResolver, gameRepository)

    init {
        // Tier stability reads the previous summary + coin rows; default to "never synced".
        coEvery { setDao.getSet(any(), any()) } returns null
        coEvery { coinDao.getForSet(any(), any()) } returns emptyList()
        coEvery { gameRepository.getById(any()) } returns null
    }

    private fun coin(id: String, tier: ShibaTier, earned: Boolean) =
        SyncedCoin(
            id, id, "", tier, 0.0, null,
            isHidden = false, isEarned = earned, earnedHardcore = earned,
            earnedAt = if (earned) 1L else null,
        )

    private fun setEntity(
        provider: String,
        providerGameId: String,
        bronzeEarned: Int = 0, silverEarned: Int = 0, goldEarned: Int = 0,
        bronzeTotal: Int = 0, silverTotal: Int = 0, goldTotal: Int = 0,
        lastSyncedAt: Long? = null,
    ) = AccountAchievementSetEntity(
        provider = provider, providerGameId = providerGameId, title = "Some Game",
        bronzeTotal = bronzeTotal, silverTotal = silverTotal, goldTotal = goldTotal,
        bronzeEarned = bronzeEarned, silverEarned = silverEarned, goldEarned = goldEarned,
        lastSyncedAt = lastSyncedAt,
    )

    @Test
    fun `observeGameCoins maps the stored summary to domain`() = runTest {
        every { setDao.observeForGame(1L) } returns flowOf(
            setEntity(
                provider = "RETRO_ACHIEVEMENTS", providerGameId = "14402",
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
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(
                coin("g1", ShibaTier.GOLD, earned = true),
                coin("b1", ShibaTier.BRONZE, earned = false),
                coin("b2", ShibaTier.BRONZE, earned = true),
            ),
        )
        val setSlot = slot<AccountAchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        val result = repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertTrue(result is ProviderSyncResult.Success)
        coVerify { coinDao.deleteForSet("STEAM", "440") }
        coVerify { coinDao.upsertAll(match { it.size == 3 }) }
        coVerify { credentials.setLastSyncedAt(any()) }

        val summary = setSlot.captured
        assertEquals("440", summary.providerGameId)
        assertEquals(2, summary.bronzeTotal)
        assertEquals(1, summary.bronzeEarned)
        assertEquals(1, summary.goldTotal)
        assertEquals(1, summary.goldEarned)
        assertFalse(summary.mastered) // not every coin earned
    }

    @Test
    fun `syncGame names the account row after its library game`() = runTest {
        coEvery { gameRepository.getById(1L) } returns
            com.playfieldportal.core.domain.model.Game(id = 1, title = "Team Fortress 2", platformId = "windows")
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(coin("b1", ShibaTier.BRONZE, earned = true)),
        )
        val setSlot = slot<AccountAchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertEquals("Team Fortress 2", setSlot.captured.title)
    }

    @Test
    fun `syncAccountEntry stores the provider title and keeps a stub's icon`() = runTest {
        coEvery { setDao.getSet("RETRO_ACHIEVEMENTS", "319") } returns AccountAchievementSetEntity(
            provider = "RETRO_ACHIEVEMENTS", providerGameId = "319",
            title = "Chrono Trigger", iconUrl = "https://media.retroachievements.org/Images/3.png",
        )
        coEvery { retroSource.fetch("319") } returns ProviderSyncResult.Success(
            "319",
            listOf(coin("b1", ShibaTier.BRONZE, earned = true)),
        )
        val setSlot = slot<AccountAchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        repo.syncAccountEntry(AchievementProvider.RETRO_ACHIEVEMENTS, "319", "Chrono Trigger")

        assertEquals("Chrono Trigger", setSlot.captured.title)
        assertEquals("https://media.retroachievements.org/Images/3.png", setSlot.captured.iconUrl)
    }

    @Test
    fun `a blank sync title falls back to the stored set's title`() = runTest {
        coEvery { setDao.getSet("STEAM", "440") } returns setEntity(provider = "STEAM", providerGameId = "440")
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(coin("b1", ShibaTier.BRONZE, earned = true)),
        )
        val setSlot = slot<AccountAchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        repo.syncGame(1L, AchievementProvider.STEAM, "440") // no library game -> blank title

        assertEquals("Some Game", setSlot.captured.title)
    }

    @Test
    fun `syncGame marks mastered when every coin is earned`() = runTest {
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(coin("g1", ShibaTier.GOLD, earned = true), coin("b1", ShibaTier.BRONZE, earned = true)),
        )
        val setSlot = slot<AccountAchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertTrue(setSlot.captured.mastered)
    }

    @Test
    fun `a provider Platinum coin is the crown and stays out of the tier tallies`() = runTest {
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(
                coin("p1", ShibaTier.PLATINUM, earned = true),
                coin("b1", ShibaTier.BRONZE, earned = false), // an unearned coin: crown still lights
            ),
        )
        val setSlot = slot<AccountAchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        repo.syncGame(1L, AchievementProvider.STEAM, "440")

        val summary = setSlot.captured
        assertTrue(summary.mastered) // the platinum coin IS the crown
        assertEquals(1, summary.bronzeTotal) // platinum never counted as B/S/G
        assertEquals(0, summary.goldTotal)
    }

    @Test
    fun `an unearned provider Platinum keeps the crown dark even at 100 percent of the rest`() = runTest {
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(
                coin("p1", ShibaTier.PLATINUM, earned = false),
                coin("b1", ShibaTier.BRONZE, earned = true),
            ),
        )
        val setSlot = slot<AccountAchievementSetEntity>()
        coEvery { setDao.upsert(capture(setSlot)) } just Runs

        repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertFalse(setSlot.captured.mastered)
    }

    @Test
    fun `a Steam re-sync within seven days keeps each coin's stored tier`() = runTest {
        // Rarity drifted from 9 to 11 percent (Gold -> Silver), but the last sync was recent.
        coEvery { setDao.getSet("STEAM", "440") } returns setEntity(
            provider = "STEAM", providerGameId = "440",
            lastSyncedAt = System.currentTimeMillis() - 60_000,
        )
        coEvery { coinDao.getForSet("STEAM", "440") } returns listOf(
            AccountAchievementEntity(
                provider = "STEAM", providerGameId = "440", providerAchievementId = "g1",
                title = "g1", description = "", tier = "GOLD", globalRarity = 9.0,
            ),
        )
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(coin("g1", ShibaTier.SILVER, earned = false)),
        )
        val coinsSlot = slot<List<AccountAchievementEntity>>()
        coEvery { coinDao.upsertAll(capture(coinsSlot)) } just Runs

        repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertEquals("GOLD", coinsSlot.captured.single().tier) // stored tier survives the window
    }

    @Test
    fun `a non-success result leaves the database untouched`() = runTest {
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.ProfileNotPublic

        val result = repo.syncGame(1L, AchievementProvider.STEAM, "440")

        assertEquals(ProviderSyncResult.ProfileNotPublic, result)
        coVerify(exactly = 0) { coinDao.upsertAll(any()) }
        coVerify(exactly = 0) { setDao.upsert(any()) }
    }

    @Test
    fun `syncGameById syncs from the stored link`() = runTest {
        coEvery { linkDao.getForGame(1L) } returns ProviderGameLinkEntity(1L, "STEAM", "440", "MANUAL", 0L)
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success(
            "440",
            listOf(coin("b1", ShibaTier.BRONZE, earned = true)),
        )

        val result = repo.syncGameById(1L)

        assertTrue(result is ProviderSyncResult.Success)
        coVerify { steamSource.fetch("440") }
    }

    @Test
    fun `syncGameById returns NotLinked when the game has no link`() = runTest {
        coEvery { linkDao.getForGame(1L) } returns null
        assertEquals(ProviderSyncResult.NotLinked, repo.syncGameById(1L))
    }

    @Test
    fun `untracked reason prefers the persisted match note, else a platform fallback`() = runTest {
        every { setDao.observeWalletCoins() } returns flowOf(0)
        every { setDao.observeAccountSets() } returns flowOf(emptyList())
        every { coinDao.observeRarestEarned(any()) } returns flowOf(emptyList())
        every { gameRepository.observeGamesOnly() } returns flowOf(
            listOf(
                com.playfieldportal.core.domain.model.Game(id = 1, title = "Dead or Alive 4", platformId = "x360"),
                com.playfieldportal.core.domain.model.Game(id = 2, title = "Some PC Game", platformId = "windows"),
                com.playfieldportal.core.domain.model.Game(id = 3, title = "Emerald Crest", platformId = "gba"),
                com.playfieldportal.core.domain.model.Game(id = 4, title = "Linked Game", platformId = "snes"),
            ),
        )
        every { linkDao.observeLinkedGameIds() } returns flowOf(listOf(4L)) // only the SNES game is linked
        // A recorded note for the GBA hack; the others have none and fall back to a platform guess.
        every { matchNoteDao.observeAll() } returns flowOf(
            listOf(com.playfieldportal.core.data.database.entity.AchievementMatchNoteEntity(3L, "Couldn't read the ROM file, and no title match", 0L)),
        )

        val untracked = repo.observeLibraryStanding().first().untracked.associateBy { it.gameId }

        assertEquals(3, untracked.size)
        assertEquals("Couldn't read the ROM file, and no title match", untracked.getValue(3L).reason) // persisted note wins
        assertEquals("System not supported by RetroAchievements", untracked.getValue(1L).reason)      // x360 fallback
        assertEquals("Not found on Steam", untracked.getValue(2L).reason)                              // windows fallback
    }

    @Test
    fun `syncAllLinked syncs every link and tallies the outcomes`() = runTest {
        coEvery { linkDao.getAll() } returns listOf(
            ProviderGameLinkEntity(1L, "STEAM", "440", "MANUAL", 0L),
            ProviderGameLinkEntity(2L, "RETRO_ACHIEVEMENTS", "14402", "MANUAL", 0L),
            ProviderGameLinkEntity(3L, "STEAM", "999", "MANUAL", 0L),
        )
        coEvery { steamSource.fetch("440") } returns ProviderSyncResult.Success("440", listOf(coin("b1", ShibaTier.BRONZE, earned = true)))
        coEvery { retroSource.fetch("14402") } returns ProviderSyncResult.NotFound
        coEvery { steamSource.fetch("999") } returns ProviderSyncResult.Failed("network error")

        val progress = mutableListOf<Pair<Int, Int>>()
        val result = repo.syncAllLinked { done, total -> progress += done to total }

        assertEquals(3, result.total)
        assertEquals(1, result.synced)
        assertEquals(1, result.noCoins)
        assertEquals(1, result.failed)
        assertFalse(result.missingCredentials)
        assertEquals(3 to 3, progress.last())
        coVerify { steamSource.fetch("440") }
        coVerify { retroSource.fetch("14402") }
    }

    @Test
    fun `syncAllLinked flags missing credentials`() = runTest {
        coEvery { linkDao.getAll() } returns listOf(
            ProviderGameLinkEntity(1L, "RETRO_ACHIEVEMENTS", "14402", "MANUAL", 0L),
        )
        coEvery { retroSource.fetch("14402") } returns ProviderSyncResult.MissingCredentials

        val result = repo.syncAllLinked()

        assertTrue(result.missingCredentials)
        assertEquals(0, result.synced)
    }

    @Test
    fun `resolveSteamByGame tries the full title before the shortened display override`() = runTest {
        val full = "RESONANCE OF FATE™/END OF ETERNITY™ 4K/HD EDITION"
        coEvery { gameRepository.getById(1L) } returns
            com.playfieldportal.core.domain.model.Game(id = 1, title = full, platformId = "windows", userTitleOverride = "RESONANCE OF FATE")
        coEvery { steamResolver.resolveAppId("RESONANCE OF FATE") } returns null
        coEvery { steamResolver.resolveAppId(full) } returns "645730"

        assertEquals("645730", repo.resolveSteamByGame(1L))
    }

    @Test
    fun `resolveSteamLink stores a link when the title matches`() = runTest {
        coEvery { steamResolver.resolveAppId("Half-Life 2") } returns "220"
        val slot = slot<ProviderGameLinkEntity>()
        coEvery { linkDao.upsert(capture(slot)) } just Runs

        val appId = repo.resolveSteamLink(1L, "Half-Life 2")

        assertEquals("220", appId)
        assertEquals("220", slot.captured.providerGameId)
        assertEquals(AchievementProvider.STEAM.name, slot.captured.provider)
    }
}
