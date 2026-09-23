package com.playfieldportal.feature.achievements.preview

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSources
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSource
import com.playfieldportal.feature.achievements.provider.retro.RaCatalogGame
import com.playfieldportal.feature.achievements.provider.retro.RaHashResolver
import com.playfieldportal.feature.achievements.provider.retro.RaRemoteDataSource
import com.playfieldportal.feature.achievements.provider.retro.RetroAchievementsSource
import com.playfieldportal.feature.achievements.provider.steam.SteamAchievementsSource
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import com.playfieldportal.feature.achievements.provider.steam.SteamCandidate
import com.playfieldportal.feature.achievements.provider.vita.VitaTrophySource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Selective sync, Task 8: an explicit provider search lets the user look at a game that is not on
 * this device. The preview is read-only by construction — the repository has no DAO, ledger or
 * sync dependency to write through — and is held only in memory until the user closes it. RA
 * titles come from the per-console catalog the hash matcher already caches, so typing never
 * downloads a console list per keystroke.
 */
class AchievementPreviewRepositoryTest {

    private val retro = mockk<RetroAchievementsSource>()
    private val steam = mockk<SteamAchievementsSource>()
    private val localSteam = mockk<LocalSteamSource>()
    private val vita = mockk<VitaTrophySource>()
    private val sources = RemoteAchievementSources(retro, steam, localSteam, vita)
    private val steamResolver = mockk<SteamAppListResolver>()
    private val raRemote = mockk<RaRemoteDataSource>()
    private val raCatalog = RaHashResolver(raRemote)
    private val repository = AchievementPreviewRepository(sources, steamResolver, raCatalog)

    private val coins = listOf(SyncedCoin("1", "First", "", ShibaTier.BRONZE, 40.0, null, false, true, true, 5L))

    init {
        coEvery { steamResolver.search(any(), any()) } returns listOf(SteamCandidate("440", "Team Fortress 2"))
        coEvery { raRemote.gameCatalog(3) } returns listOf(
            RaCatalogGame("228", "Super Mario World", "SNES", null, listOf("abc")),
            RaCatalogGame("355", "Chrono Trigger", "SNES", null, emptyList()),
        )
        coEvery { steam.fetch("440") } returns ProviderSyncResult.Success("440", coins)
    }

    @Test
    fun `a steam search needs at least two characters`() = runTest {
        assertTrue(repository.searchSteam("t").isEmpty())
        coVerify(exactly = 0) { steamResolver.search(any(), any()) }

        val found = repository.searchSteam("team")
        assertEquals(listOf(PreviewCandidate(AchievementProvider.STEAM, "440", "Team Fortress 2", "Steam")), found)
    }

    @Test
    fun `RA search filters the cached console catalog without refetching per keystroke`() = runTest {
        val first = assertIs<PreviewSearch.Results>(repository.searchRetroAchievements(3, "chr"))
        val second = assertIs<PreviewSearch.Results>(repository.searchRetroAchievements(3, "chrono"))

        assertEquals(listOf("Chrono Trigger"), first.candidates.map { it.title })
        assertEquals(listOf("355"), second.candidates.map { it.providerGameId })
        coVerify(exactly = 1) { raRemote.gameCatalog(3) }
    }

    @Test
    fun `an unavailable RA catalog says so rather than showing no results`() = runTest {
        coEvery { raRemote.gameCatalog(5) } returns null

        assertEquals(PreviewSearch.Unavailable, repository.searchRetroAchievements(5, "zelda"))
    }

    @Test
    fun `opening a preview reads the provider once and reuses it until closed`() = runTest {
        val candidate = PreviewCandidate(AchievementProvider.STEAM, "440", "Team Fortress 2", "Steam")

        assertIs<ProviderSyncResult.Success>(repository.open(candidate))
        assertIs<ProviderSyncResult.Success>(repository.open(candidate))
        coVerify(exactly = 1) { steam.fetch("440") }

        repository.close(candidate)
        repository.open(candidate)
        coVerify(exactly = 2) { steam.fetch("440") }
    }

    @Test
    fun `search, open and close touch only read-only provider calls`() = runTest {
        val candidate = repository.searchSteam("team").single()
        repository.open(candidate)
        repository.close(candidate)

        coVerify { steamResolver.search("team", any()) }
        coVerify { steam.fetch("440") }
        confirmVerified(steam, retro, localSteam, vita)
    }

    @Test
    fun `a failed preview is not cached`() = runTest {
        coEvery { steam.fetch("440") } returns ProviderSyncResult.Failed("network error")
        val candidate = PreviewCandidate(AchievementProvider.STEAM, "440", "Team Fortress 2", "Steam")

        repository.open(candidate)
        repository.open(candidate)

        coVerify(exactly = 2) { steam.fetch("440") }
    }
}
