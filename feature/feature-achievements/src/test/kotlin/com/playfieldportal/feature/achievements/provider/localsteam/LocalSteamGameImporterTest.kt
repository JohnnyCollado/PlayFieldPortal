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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The emu-folder reconcile pass: folders LINK to existing library games (never create entities —
 * user decision 2026-07-16); unmapped folders stay tracked-only for the Shiba Coins sync;
 * unlaunchable folder-import rows from the pre-rework scan are removed.
 *
 * It is HANDED its folders now rather than scanning for them — the batch matcher inspects the parent
 * folder the user picked and passes the ready ones in — so there is no discovery to stub.
 */
class LocalSteamGameImporterTest {

    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val achievements = mockk<AchievementController>(relaxed = true)
    private val ownership = mockk<LocalSteamOwnership>(relaxed = true)
    private val steamNames = mockk<SteamAppListResolver>(relaxed = true)
    private val importer = LocalSteamGameImporter(gameRepository, achievements, ownership, steamNames)

    private val marvel = LocalSteamGame(
        folderName = "MARVEL Cosmic Invasion",
        folderDocId = "primary:Games/MARVEL Cosmic Invasion",
        appId = "2753970",
        achievementsUri = null,
    )

    private fun pinnedGame(id: Long, title: String) = Game(
        id = id, title = title, platformId = "windows",
        packageName = "com.ludashi.aibench", shortcutId = title, isManualEntry = true,
    )

    private fun quiet() {
        coEvery { ownership.classify(any(), any()) } returns null
        coEvery { steamNames.officialNameOf(any()) } returns null
    }

    @Test
    fun `a folder mapping to a library game links LOCAL_STEAM — no entity is ever created`() = runTest {
        quiet()
        coEvery { gameRepository.getByPlatform("windows") } returns
            listOf(pinnedGame(7L, "MARVELCosmicInvasion"))

        val result = importer.reconcile(listOf(marvel))

        assertEquals(EmuGameImportResult(discovered = 1, linked = 1, linkedGameIds = listOf(7L)), result)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "2753970") }
        coVerify { ownership.classify(7L, "2753970") }
    }

    @Test
    fun `an unmapped folder stays tracked-only — no entity, no link`() = runTest {
        quiet()
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()

        val result = importer.reconcile(listOf(marvel))

        assertEquals(EmuGameImportResult(discovered = 1, linked = 0), result)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
    }

    @Test
    fun `the steam-name bridge maps a renamed folder onto its shortcut-imported game`() = runTest {
        coEvery { ownership.classify(any(), any()) } returns null
        val folder = LocalSteamGame(
            folderName = "FINAL FANTASY FFX-FFX-2 HD Remaster",
            folderDocId = "408C-3861:Games/FINAL FANTASY FFX-FFX-2 HD Remaster",
            appId = "359870",
            achievementsUri = null,
        )
        coEvery { gameRepository.getByPlatform("windows") } returns
            listOf(pinnedGame(4L, "FINAL FANTASY X/X-2 HD Remaster"))
        coEvery { steamNames.officialNameOf("359870") } returns "FINAL FANTASY X/X-2 HD Remaster"

        val result = importer.reconcile(listOf(folder))

        assertEquals(1, result.linked)
        assertEquals(listOf(4L), result.linkedGameIds)
        coVerify { achievements.linkManually(4L, AchievementProvider.LOCAL_STEAM, "359870") }
    }

    @Test
    fun `an owned mapped copy gets BOTH provider links`() = runTest {
        coEvery { steamNames.officialNameOf(any()) } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns
            listOf(pinnedGame(7L, "MARVEL Cosmic Invasion"))
        coEvery { ownership.classify(7L, "2753970") } returns LocalCopyOwnership.OWNED

        importer.reconcile(listOf(marvel))

        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "2753970") }
        coVerify { achievements.linkManually(7L, AchievementProvider.STEAM, "2753970") }
    }

    @Test
    fun `unlaunchable folder-import rows from the pre-rework scan are removed`() = runTest {
        quiet()
        val dead = Game(
            id = 12L, title = "FINAL FANTASY V", platformId = "windows",
            isManualEntry = true, romPath = "/storage/emulated/0/Roms/windows/FINAL FANTASY V",
        )
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(dead)

        importer.reconcile(listOf(marvel))

        coVerify { gameRepository.delete(12L) }
        // The dead row must not soak up the folder mapping either.
        coVerify(exactly = 0) { achievements.linkManually(12L, any(), any()) }
    }

    @Test
    fun `no folders is a quiet no-op — the library is not even read`() = runTest {
        assertEquals(EmuGameImportResult(0, 0), importer.reconcile(emptyList()))
        coVerify(exactly = 0) { gameRepository.getByPlatform(any()) }
    }
}
