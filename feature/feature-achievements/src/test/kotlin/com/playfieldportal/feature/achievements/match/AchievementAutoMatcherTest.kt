package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.retro.RaHashResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementAutoMatcherTest {

    private val gameRepository = mockk<GameRepository>()
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val matchNoteDao = mockk<com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao>(relaxed = true)
    private val raHashResolver = mockk<RaHashResolver>()
    private val repository = mockk<AchievementController>(relaxed = true)
    private val romReader = mockk<RomBytesReader>()
    private val discOpener = mockk<DiscImageOpener>(relaxed = true)
    private val steamGridDb = mockk<com.playfieldportal.feature.artwork.api.SteamGridDbApi>(relaxed = true)

    private val localSteamDiscovery =
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery> {
            coEvery { scan() } returns emptyList()
        }
    private val localSteamOwnership =
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership>(relaxed = true)
    private val steamNames =
        mockk<com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver> {
            coEvery { officialNameOf(any()) } returns null
        }

    private val matcher = AchievementAutoMatcher(
        gameRepository, linkDao, matchNoteDao, raHashResolver, repository, romReader, discOpener,
        steamGridDb, localSteamDiscovery, localSteamOwnership, steamNames,
    )

    private fun game(id: Long, platform: String, title: String = "Game $id") =
        Game(id = id, title = title, platformId = platform)

    private fun stubGames(vararg games: Game) {
        every { gameRepository.observeGamesOnly() } returns flowOf(games.toList())
        games.forEach { coEvery { linkDao.getForGame(it.id) } returns null }
    }

    @Test
    fun `links a cartridge game by its rom hash`() = runTest {
        val g = game(1, "snes")
        stubGames(g)
        coEvery { romReader.read(g) } returns byteArrayOf(1, 2, 3, 4)
        coEvery { raHashResolver.lookup(3, any()) } returns
            com.playfieldportal.feature.achievements.provider.retro.RaHashLookup.Found("999")

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        assertTrue(report.unmatched.isEmpty())
        coVerify { repository.linkManually(1, AchievementProvider.RETRO_ACHIEVEMENTS, "999") }
    }

    @Test
    fun `reports a cartridge game whose hash is not registered (RA is hash-only)`() = runTest {
        val g = game(1, "gba")
        stubGames(g)
        coEvery { romReader.read(g) } returns byteArrayOf(9, 9, 9)
        coEvery { raHashResolver.lookup(5, any()) } returns
            com.playfieldportal.feature.achievements.provider.retro.RaHashLookup.NotRegistered

        val report = matcher.matchUnlinked()

        assertEquals(0, report.matched)
        assertEquals(1, report.unmatched.size)
        assertEquals("ROM hash isn't registered on RetroAchievements", report.unmatched.first().reason)
    }

    @Test
    fun `a failed hash-list fetch is reported as unavailable, not as an unregistered hash`() = runTest {
        val g = game(1, "gba")
        stubGames(g)
        coEvery { romReader.read(g) } returns byteArrayOf(9, 9, 9)
        coEvery { raHashResolver.lookup(5, any()) } returns
            com.playfieldportal.feature.achievements.provider.retro.RaHashLookup.Unavailable

        val report = matcher.matchUnlinked()

        assertEquals(0, report.matched)
        assertTrue(report.unmatched.first().reason.startsWith("Couldn't load the RetroAchievements game list"))
        coVerify(exactly = 0) { repository.linkManually(any(), any(), any()) }
    }

    @Test
    fun `reports unsupported systems without reading the rom`() = runTest {
        val g = game(1, "ps3") // RA has no PS3 achievements — no console id at all
        stubGames(g)

        val report = matcher.matchUnlinked()

        assertEquals(1, report.unmatched.size)
        assertEquals("RetroAchievements has no achievements for this system (ps3)", report.unmatched.first().reason)
        coVerify(exactly = 0) { romReader.read(any()) }
        coVerify(exactly = 0) { discOpener.open(any()) }
    }

    @Test
    fun `an unreadable disc image is reported (RA is hash-only, no title fallback)`() = runTest {
        val g = game(1, "ps2", title = "Grand Theft Auto: San Andreas")
        stubGames(g)
        coEvery { discOpener.open(g) } returns null // image unreadable / SAF .cue / NKit

        val report = matcher.matchUnlinked()

        assertEquals(0, report.matched)
        assertEquals(1, report.unmatched.size)
        coVerify { discOpener.open(g) }
        coVerify(exactly = 0) { romReader.read(any()) } // never full-loads a disc image
        assertTrue(report.unmatched.first().reason.startsWith("Unsupported disc image"))
    }

    @Test
    fun `records a persisted note naming why each unmatched game failed`() = runTest {
        val g = game(1, "dreamcast", title = "Crazy Taxi")
        stubGames(g)
        coEvery { discOpener.openGdi(g) } returns null // GDI unopenable (bad/compressed/SAF)

        matcher.matchUnlinked()

        coVerify { matchNoteDao.clear() } // rewritten from scratch each run
        coVerify {
            matchNoteDao.upsert(
                match { it.gameId == 1L && it.reason.startsWith("Unsupported disc image") },
            )
        }
    }

    @Test
    fun `windows games are folder-first — an emu folder links LOCAL_STEAM before any steam guess`() = runTest {
        val g = game(1, "windows", title = "MARVEL Cosmic Invasion")
        stubGames(g)
        coEvery { localSteamDiscovery.scan() } returns listOf(
            com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGame(
                folderName = "MARVELCosmicInvasion",   // normalized-title match despite the squash
                folderDocId = "primary:Roms/windows/MARVELCosmicInvasion",
                appId = "2753970",
                achievementsUri = null,
            ),
        )

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.linkManually(1, AchievementProvider.LOCAL_STEAM, "2753970") }
        coVerify { localSteamOwnership.classify(1, "2753970") }
        coVerify(exactly = 0) { repository.resolveSteamLink(any(), any()) }
    }

    @Test
    fun `matches steam pc games by title`() = runTest {
        val g = game(1, "windows", title = "Half-Life 2")
        stubGames(g)
        coEvery { repository.resolveSteamLink(1, "Half-Life 2") } returns "220"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
    }

    @Test
    fun `links a steam game by the appid embedded in its shortcut, skipping the title guess`() = runTest {
        val g = Game(
            id = 1, title = "The Adventures of Elliot", platformId = "windows",
            launchIntentUri = "intent:#Intent;action=app.gamenative.LAUNCH_GAME;i.app_id=3483510;S.game_source=STEAM;end",
        )
        stubGames(g)

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.linkManually(1, AchievementProvider.STEAM, "3483510") }
        coVerify(exactly = 0) { repository.resolveSteamLink(any(), any()) }
    }

    @Test
    fun `steam title match uses the full title even when the display override is shortened`() = runTest {
        val full = "RESONANCE OF FATE™/END OF ETERNITY™ 4K/HD EDITION"
        val g = Game(id = 1, title = full, platformId = "windows", userTitleOverride = "RESONANCE OF FATE")
        stubGames(g)
        coEvery { repository.resolveSteamLink(1, "RESONANCE OF FATE") } returns null // the truncated override misses
        coEvery { repository.resolveSteamLink(1, full) } returns "645730"            // the full title hits

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.resolveSteamLink(1, full) }
    }

    @Test
    fun `links a steam game via steamgriddb platform data when there is no embedded appid`() = runTest {
        val g = Game(id = 1, title = "Some Game", platformId = "windows", steamGridDbId = 5247L)
        stubGames(g)
        coEvery { steamGridDb.getSteamAppId(5247L) } returns "220"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.linkManually(1, AchievementProvider.STEAM, "220") }
        coVerify(exactly = 0) { repository.resolveSteamLink(any(), any()) } // never reached the title guess
    }

    // ── Explicit per-game Local Steam match ───────────────────────────────────

    private fun emuFolder(name: String, appId: String) =
        com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGame(
            folderName = name, folderDocId = "doc:$name", appId = appId, achievementsUri = null,
        )

    @Test
    fun `single local steam match links a unique containment folder`() = runTest {
        val g = Game(id = 7, title = "Resonance of Fate", platformId = "windows")
        coEvery { gameRepository.getById(7) } returns g
        coEvery { localSteamDiscovery.scan() } returns
            listOf(emuFolder("RESONANCE OF FATE 4K_HD EDITION", "293540"))

        val result = matcher.matchSingleAsLocalSteam(7)

        assertEquals(AchievementAutoMatcher.LocalSteamMatchResult.Matched, result)
        coVerify { repository.linkManually(7, AchievementProvider.LOCAL_STEAM, "293540") }
    }

    @Test
    fun `single local steam match reports missing folders and ambiguous names distinctly`() = runTest {
        val g = Game(id = 7, title = "Fate", platformId = "windows")
        coEvery { gameRepository.getById(7) } returns g

        coEvery { localSteamDiscovery.scan() } returns emptyList()
        assertEquals(
            AchievementAutoMatcher.LocalSteamMatchResult.NoEmuFolders,
            matcher.matchSingleAsLocalSteam(7),
        )

        // Two folders both contain "Fate" — ambiguous, so no guess is made.
        coEvery { localSteamDiscovery.scan() } returns
            listOf(emuFolder("RESONANCE OF FATE", "1"), emuFolder("FATE EXTELLA", "2"))
        val result = matcher.matchSingleAsLocalSteam(7)
        assertTrue(result is AchievementAutoMatcher.LocalSteamMatchResult.NoNameMatch)
        coVerify(exactly = 0) { repository.linkManually(7, AchievementProvider.LOCAL_STEAM, any()) }
    }
}
