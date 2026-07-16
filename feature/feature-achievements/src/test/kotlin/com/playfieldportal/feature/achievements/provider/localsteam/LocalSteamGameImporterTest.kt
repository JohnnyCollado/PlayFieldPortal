package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSteamGameImporterTest {

    private val discovery = mockk<LocalSteamDiscovery>()
    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val achievements = mockk<AchievementController>(relaxed = true)
    private val ownership = mockk<LocalSteamOwnership>(relaxed = true)
    private val steamNames = mockk<SteamAppListResolver>(relaxed = true)
    private val importer = LocalSteamGameImporter(discovery, gameRepository, achievements, ownership, steamNames)

    private val marvel = LocalSteamGame(
        folderName = "MARVEL Cosmic Invasion",
        folderDocId = "primary:Games/MARVEL Cosmic Invasion",
        appId = "2753970",
        achievementsUri = null,
    )

    private fun unknownOwnership() {
        coEvery { ownership.classify(any(), any()) } returns null
        coEvery { steamNames.officialNameOf(any()) } returns null
    }

    @Test
    fun `a new emu folder becomes a windows game linked to LOCAL_STEAM`() = runTest {
        unknownOwnership()
        coEvery { discovery.scan() } returns listOf(marvel)
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        val gameSlot = slot<Game>()
        coEvery { gameRepository.upsert(capture(gameSlot)) } returns 7L

        val result = importer.import()

        assertEquals(EmuGameImportResult(discovered = 1, added = 1), result)
        assertEquals("MARVEL Cosmic Invasion", gameSlot.captured.title)
        assertEquals("windows", gameSlot.captured.platformId)
        assertEquals("/storage/emulated/0/Games/MARVEL Cosmic Invasion", gameSlot.captured.romPath)
        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "2753970") }
        coVerify { ownership.classify(7L, "2753970") }
        // Unknown ownership never invents the STEAM link.
        coVerify(exactly = 0) { achievements.linkManually(7L, AchievementProvider.STEAM, any()) }
    }

    @Test
    fun `a re-scan converges on the existing game and just refreshes the link`() = runTest {
        unknownOwnership()
        coEvery { discovery.scan() } returns listOf(marvel)
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(
            Game(id = 7L, title = "Marvel Cosmic Invasion!", platformId = "windows"),
        )

        val result = importer.import()

        assertEquals(EmuGameImportResult(discovered = 1, added = 0), result)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "2753970") }
    }

    @Test
    fun `the steam-name bridge maps a renamed folder onto its shortcut-imported game`() = runTest {
        // Live pair: folder "FINAL FANTASY FFX-FFX-2 HD Remaster" (appid 359870) vs the GameHub
        // shortcut label "FINAL FANTASY X/X-2 HD Remaster" — title match fails, the bridge wins.
        coEvery { ownership.classify(any(), any()) } returns null
        val folder = LocalSteamGame(
            folderName = "FINAL FANTASY FFX-FFX-2 HD Remaster",
            folderDocId = "408C-3861:Roms/windows/FINAL FANTASY FFX-FFX-2 HD Remaster",
            appId = "359870",
            achievementsUri = null,
        )
        coEvery { discovery.scan() } returns listOf(folder)
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(
            Game(id = 4L, title = "FINAL FANTASY X/X-2 HD Remaster", platformId = "windows"),
        )
        coEvery { steamNames.officialNameOf("359870") } returns "FINAL FANTASY X/X-2 HD Remaster"

        val result = importer.import()

        assertEquals(EmuGameImportResult(discovered = 1, added = 0), result)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify { achievements.linkManually(4L, AchievementProvider.LOCAL_STEAM, "359870") }
    }

    @Test
    fun `an unmappable folder becomes its own game — the join never guesses`() = runTest {
        // FFXIV-style: an existing similar entry plus a folder whose store name doesn't rescue
        // it — a NEW game is created rather than fuzzy-matched onto the wrong one.
        unknownOwnership()
        val folder = LocalSteamGame(
            folderName = "Some Game - Definitive Edition",
            folderDocId = "primary:Roms/windows/Some Game - Definitive Edition",
            appId = "999999",
            achievementsUri = null,
        )
        coEvery { discovery.scan() } returns listOf(folder)
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(
            Game(id = 6L, title = "Some Game", platformId = "windows"),
        )
        coEvery { steamNames.officialNameOf("999999") } returns "Some Game: The Sequel"
        coEvery { gameRepository.upsert(any()) } returns 20L

        val result = importer.import()

        assertEquals(EmuGameImportResult(discovered = 1, added = 1), result)
        coVerify { achievements.linkManually(20L, AchievementProvider.LOCAL_STEAM, "999999") }
        coVerify(exactly = 0) { achievements.linkManually(6L, any(), any()) }
    }

    @Test
    fun `an owned emu copy gets BOTH provider links`() = runTest {
        coEvery { steamNames.officialNameOf(any()) } returns null
        coEvery { discovery.scan() } returns listOf(marvel)
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        coEvery { gameRepository.upsert(any()) } returns 7L
        coEvery { ownership.classify(7L, "2753970") } returns LocalCopyOwnership.OWNED

        importer.import()

        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "2753970") }
        coVerify { achievements.linkManually(7L, AchievementProvider.STEAM, "2753970") }
    }

    @Test
    fun `no discovered folders is a quiet no-op`() = runTest {
        coEvery { discovery.scan() } returns emptyList()

        assertEquals(EmuGameImportResult(0, 0), importer.import())
        coVerify(exactly = 0) { gameRepository.getByPlatform(any()) }
    }
}
