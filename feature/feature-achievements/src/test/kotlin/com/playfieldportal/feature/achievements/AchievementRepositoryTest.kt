package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.dao.AchievementTrackingDao
import com.playfieldportal.core.data.database.dao.LinkPresenceRow
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import com.playfieldportal.feature.achievements.sync.AchievementIdentity
import com.playfieldportal.feature.achievements.sync.AchievementSyncCoordinator
import com.playfieldportal.feature.achievements.sync.SyncTrigger
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
import kotlin.test.assertNull

class AchievementRepositoryTest {

    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val setDao = mockk<AccountAchievementSetDao>(relaxed = true)
    private val coinDao = mockk<AccountAchievementDao>(relaxed = true)
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val trackingDao = mockk<AchievementTrackingDao>(relaxed = true)
    private val steamResolver = mockk<SteamAppListResolver>(relaxed = true)
    private val gameRepository = mockk<com.playfieldportal.core.domain.repository.GameRepository>(relaxed = true)
    private val matchNoteDao = mockk<com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao>(relaxed = true)
    private val coordinator = mockk<AchievementSyncCoordinator>(relaxed = true)

    private val repo = AchievementRepository(
        credentials, setDao, coinDao, linkDao, matchNoteDao, trackingDao, steamResolver, gameRepository,
        coordinator, clock = { 1_000L },
    )

    init {
        coEvery { gameRepository.getById(any()) } returns null
    }

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
    fun `observeGameCoins carries the last sync time`() = runTest {
        every { setDao.observeForGame(1L) } returns flowOf(
            setEntity(provider = "RETRO_ACHIEVEMENTS", providerGameId = "14402", lastSyncedAt = 1_700_000_000_000L),
        )

        assertEquals(1_700_000_000_000L, repo.observeGameCoins(1L).first()!!.lastSyncedAt)
    }

    @Test
    fun `observeGameCoins reports a never-synced set as null`() = runTest {
        every { setDao.observeForGame(1L) } returns flowOf(
            setEntity(provider = "RETRO_ACHIEVEMENTS", providerGameId = "14402"),
        )

        assertNull(repo.observeGameCoins(1L).first()!!.lastSyncedAt)
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
                // Android games can never have achievements — never listed as untracked.
                com.playfieldportal.core.domain.model.Game(id = 5, title = "Some Android App", platformId = "android"),
                // A missing ROM is not something to match: never listed as untracked.
                com.playfieldportal.core.domain.model.Game(id = 6, title = "Gone", platformId = "snes", isMissing = true),
            ),
        )
        every { linkDao.observeLinkedGameIds() } returns flowOf(listOf(4L)) // only the SNES game is linked
        every { setDao.observeAwaitingSync() } returns flowOf(emptyList())
        // A recorded note for the GBA hack; the others have none and fall back to a platform guess.
        every { matchNoteDao.observeAll() } returns flowOf(
            listOf(com.playfieldportal.core.data.database.entity.AchievementMatchNoteEntity(3L, "Couldn't read the ROM file, and no title match", 0L)),
        )

        val untracked = repo.observeLibraryStanding().first().untracked.associateBy { it.gameId }

        assertEquals(3, untracked.size)   // the android game is excluded
        assertEquals(false, untracked.containsKey(5L))
        assertEquals("Couldn't read the ROM file, and no title match", untracked.getValue(3L).reason) // persisted note wins
        assertEquals("System not supported by RetroAchievements", untracked.getValue(1L).reason)      // x360 fallback
        assertEquals("Not found on Steam", untracked.getValue(2L).reason)                              // windows fallback
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

    @Test
    fun `a match for a present game is confirmed into the ledger right away`() = runTest {
        coEvery { gameRepository.getById(1L) } returns Game(id = 1, title = "Chrono Trigger", platformId = "snes")

        repo.linkManually(1L, AchievementProvider.RETRO_ACHIEVEMENTS, " 319 ")

        coVerify { linkDao.upsert(match { it.providerGameId == "319" }) }
        coVerify { trackingDao.confirm("RETRO_ACHIEVEMENTS", "319", "Chrono Trigger", 1_000L) }
    }

    @Test
    fun `a link for a missing game is stored but not confirmed`() = runTest {
        coEvery { gameRepository.getById(1L) } returns Game(id = 1, title = "Gone", platformId = "snes", isMissing = true)

        repo.linkManually(1L, AchievementProvider.RETRO_ACHIEVEMENTS, "319")

        coVerify { linkDao.upsert(any()) }
        coVerify(exactly = 0) { trackingDao.confirm(any(), any(), any(), any()) }
    }

    @Test
    fun `unlink drops the identity from the ledger unless another copy still links to it`() = runTest {
        coEvery { trackingDao.linkPresenceForGame(1L) } returns listOf(
            LinkPresenceRow(1L, "STEAM", "440", "TF2", isMissing = false),
            LinkPresenceRow(1L, "LOCAL_STEAM", "440", "TF2", isMissing = false),
        )
        coEvery { linkDao.linkExistsFor("STEAM", "440") } returns false
        coEvery { linkDao.linkExistsFor("LOCAL_STEAM", "440") } returns true

        repo.unlink(1L)

        coVerify { linkDao.deleteForGame(1L) }
        coVerify { trackingDao.deleteIdentity("STEAM", "440") }
        coVerify(exactly = 0) { trackingDao.deleteIdentity("LOCAL_STEAM", any()) }
        // The cached set is left alone.
        coVerify(exactly = 0) { setDao.deleteSet(any(), any()) }
    }

    @Test
    fun `every refresh goes through the selective coordinator`() = runTest {
        coEvery { coordinator.refreshGame(1L) } returns ProviderSyncResult.NotLinked

        assertEquals(ProviderSyncResult.NotLinked, repo.syncGameById(1L))
        repo.syncAccountEntry(AchievementProvider.LOCAL_STEAM, "2000", "Folder")
        repo.updateInstalledAchievements()
        repo.refreshGameIfStale(1L)

        coVerify { coordinator.refreshGame(1L) }
        coVerify { coordinator.refreshIdentity(AchievementIdentity(AchievementProvider.LOCAL_STEAM, "2000"), "Folder") }
        coVerify { coordinator.updateInstalled(SyncTrigger.MANUAL, any()) }
        coVerify { coordinator.refreshGameIfStale(1L) }
    }

    @Test
    fun `a removed game's standing is marked not installed`() = runTest {
        every { setDao.observeWalletCoins() } returns flowOf(15)
        every { setDao.observeAccountSets() } returns flowOf(
            listOf(
                com.playfieldportal.core.data.database.dao.AccountSetRow(
                    provider = "STEAM", providerGameId = "220", libraryGameId = null, title = "Half-Life 2",
                    iconUrl = null, bronzeTotal = 1, silverTotal = 0, goldTotal = 0,
                    bronzeEarned = 1, silverEarned = 0, goldEarned = 0, mastered = false, isPresent = false,
                ),
            ),
        )
        every { coinDao.observeRarestEarned(any()) } returns flowOf(emptyList())
        every { gameRepository.observeGamesOnly() } returns flowOf(emptyList())
        every { linkDao.observeLinkedGameIds() } returns flowOf(emptyList())
        every { matchNoteDao.observeAll() } returns flowOf(emptyList())
        every { setDao.observeAwaitingSync() } returns flowOf(emptyList())

        val standing = repo.observeLibraryStanding().first().tracked.single()

        assertFalse(standing.isInstalled)
    }
}
