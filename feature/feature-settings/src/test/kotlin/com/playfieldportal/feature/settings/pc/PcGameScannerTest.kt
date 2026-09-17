package com.playfieldportal.feature.settings.pc

import android.content.Context
import android.content.pm.PackageManager
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.repository.WindowsSetupState
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.provider.localsteam.EmuGameImportResult
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGameImporter
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.launcher.PcShortcutImporter
import com.playfieldportal.feature.library.scanner.PcExportFile
import com.playfieldportal.feature.library.scanner.RomScanner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * C21 task 1.2 — a `.pfpgame` Fill must never REPLACE-upsert the matched game (D4): it writes only
 * the columns [PcGameImportPlanner.fill] actually changed, through the repository's targeted
 * methods.
 */
class PcGameScannerTest {

    private val context = mockk<Context>(relaxed = true)
    private val windowsLibrarySetup = mockk<WindowsLibrarySetup>(relaxed = true)
    private val pcShortcutImporter = mockk<PcShortcutImporter>(relaxed = true)
    private val emuGameImporter = mockk<LocalSteamGameImporter>(relaxed = true)
    private val romScanner = mockk<RomScanner>(relaxed = true)
    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val artworkImportManager = mockk<ArtworkImportManager>(relaxed = true)
    private val artworkRecordDao = mockk<ArtworkRecordDao>(relaxed = true)

    private val scanner = PcGameScanner(
        context = context,
        windowsLibrarySetup = windowsLibrarySetup,
        pcShortcutImporter = pcShortcutImporter,
        emuGameImporter = emuGameImporter,
        romScanner = romScanner,
        gameRepository = gameRepository,
        artworkImportManager = artworkImportManager,
        artworkRecordDao = artworkRecordDao,
    )

    @Before
    fun setUp() {
        every { context.packageManager } returns mockk<PackageManager>(relaxed = true)
        coEvery { windowsLibrarySetup.ensure() } returns WindowsSetupState.Ready(null)
        coEvery { windowsLibrarySetup.importFolders() } returns listOf("tree" to "importDocId")
        coEvery { pcShortcutImporter.reconcilePinnedShortcuts() } returns 0
        coEvery { emuGameImporter.import() } returns EmuGameImportResult(0, 0)
    }

    // A pin entry needs no launch-intent check, so it exercises the Fill path without the
    // PackageManager plumbing checkLaunch would otherwise need.
    private fun pinExportFile(export: PcGameExport) = PcExportFile(
        title = export.title,
        extension = PcGameExportCodec.EXTENSION,
        idContent = PcGameExportCodec.encode(export),
        rawPath = null,
        uri = "content://x/${export.title}.pfpgame",
    )

    @Test
    fun `a fill writes only the columns the export actually changed, never an upsert`() = runTest {
        val existing = Game(
            id = 7L,
            title = "Portal 2",
            platformId = "windows",
            packageName = "banner.hub",
            shortcutId = "game_620",
            ssId = 111L, // already confirmed — must not be touched
        )
        val export = PcGameExport(
            title = "Portal 2",
            launcherPackage = "banner.hub",
            shortcutId = "game_620",
            ssId = 425726L, // ignored: existing.ssId already set
            tgdbId = 66L,   // missing on existing: must be filled
            userTitleOverride = "Portal 2 (Co-op)", // missing on existing: must be filled
            storefront = "STEAM",
            storefrontGameId = "620",
        )

        coEvery { romScanner.scanPcFolder("tree", "importDocId") } returns listOf(pinExportFile(export))
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(existing)

        scanner.scan()

        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify(exactly = 1) { gameRepository.updateUserTitleOverride(7L, "Portal 2 (Co-op)") }
        coVerify(exactly = 1) { gameRepository.updateStorefrontIdentity(7L, "STEAM", "620") }
        coVerify(exactly = 1) { gameRepository.updateProviderMatch(7L, "THEGAMESDB", 66L) }
        // Already-set columns are left alone.
        coVerify(exactly = 0) { gameRepository.updateScrapedTitle(any(), any()) }
        coVerify(exactly = 0) { gameRepository.updateProviderMatch(7L, "SCREENSCRAPER", any()) }
    }

    @Test
    fun `a fill that changes nothing writes no column at all`() = runTest {
        val existing = Game(
            id = 3L,
            title = "Half-Life",
            platformId = "windows",
            packageName = "banner.hub",
            shortcutId = "game_10",
            ssId = 999L,
            userTitleOverride = "HL",
        )
        val export = PcGameExport(
            title = "Half-Life",
            launcherPackage = "banner.hub",
            shortcutId = "game_10",
            ssId = 999L,
            userTitleOverride = "HL",
        )

        coEvery { romScanner.scanPcFolder("tree", "importDocId") } returns listOf(pinExportFile(export))
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(existing)

        scanner.scan()

        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify(exactly = 0) { gameRepository.updateScrapedTitle(any(), any()) }
        coVerify(exactly = 0) { gameRepository.updateUserTitleOverride(any(), any()) }
        coVerify(exactly = 0) { gameRepository.updateStorefrontIdentity(any(), any(), any()) }
        coVerify(exactly = 0) { gameRepository.updateProviderMatch(any(), any(), any()) }
    }
}
