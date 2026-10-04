package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.retro.RaHashResolver
import com.playfieldportal.feature.achievements.provider.x360.X360TitleMatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Xbox 360 games link to X360_ACHIEVEMENT by title ID, never through RetroAchievements. */
class AchievementAutoMatcherX360Test {

    private val gameRepository = mockk<GameRepository>()
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val matchNoteDao = mockk<com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao>(relaxed = true)
    private val repository = mockk<AchievementController>(relaxed = true)
    private val x360 = mockk<X360TitleMatcher>()

    private val matcher = AchievementAutoMatcher(
        gameRepository, linkDao, matchNoteDao, mockk<RaHashResolver>(), repository,
        mockk<RomBytesReader>(), mockk<DiscImageOpener>(relaxed = true),
        mockk<com.playfieldportal.feature.artwork.api.SteamGridDbApi>(relaxed = true),
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery> {
            coEvery { scan() } returns emptyList()
        },
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership>(relaxed = true),
        mockk<com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver> {
            coEvery { officialNameOf(any()) } returns null
        },
        mockk(), mockk(),
        mockk<com.playfieldportal.feature.achievements.provider.steam.WindowsSteamGate>(),
        x360,
    )

    private val game = Game(id = 9, title = "Dead or Alive 4", platformId = "x360", romPath = "/roms/x360/doa4.iso")

    private fun stub() {
        every { gameRepository.observeGamesOnly() } returns flowOf(listOf(game))
        coEvery { linkDao.getForGame(game.id) } returns null
        coEvery { gameRepository.getById(game.id) } returns game
    }

    @Test
    fun `links the title id the matcher finds`() = runTest {
        stub()
        coEvery { x360.match(game) } returns X360TitleMatcher.Result.Matched("544307D1")

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.linkManually(9, AchievementProvider.X360_ACHIEVEMENT, "544307D1") }
    }

    @Test
    fun `reports the matcher's own reason when nothing links`() = runTest {
        stub()
        coEvery { x360.match(game) } returns X360TitleMatcher.Result.Unmatched("no title id")

        val report = matcher.matchUnlinked()

        assertEquals(listOf("no title id"), report.unmatched.map { it.reason })
        coVerify(exactly = 0) { repository.linkManually(any(), any(), any()) }
    }
}
