package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of scanning emu game folders into the windows card, for the settings message. */
data class EmuGameImportResult(val discovered: Int, val added: Int)

/**
 * Turns the emu game folders [LocalSteamDiscovery] finds into Windows-card library games, linked
 * to LOCAL_STEAM on the spot — the appid comes from the folder itself, so there is nothing for
 * auto-match to guess (docs/local-steam-achievements-plan.md Phase 2).
 *
 * Re-scans are idempotent: an already-imported folder converges on its existing game by
 * normalized title (the same dedupe rule every other PC import uses) and just refrees its link,
 * which also re-points a link whose folder changed appid. A game that additionally owns a STEAM
 * link keeps it — the two providers coexist by design (one link per provider).
 */
@Singleton
class LocalSteamGameImporter @Inject constructor(
    private val discovery: LocalSteamDiscovery,
    private val gameRepository: GameRepository,
    private val achievements: AchievementController,
) {
    suspend fun import(): EmuGameImportResult {
        val found = discovery.scan()
        if (found.isEmpty()) return EmuGameImportResult(0, 0)

        val existingByTitle = gameRepository.getByPlatform(WINDOWS_PLATFORM_ID)
            .associateBy { normalizeTitle(it.displayTitle) }
        var added = 0
        for (folder in found) {
            val gameId = existingByTitle[normalizeTitle(folder.folderName)]?.id
                ?: gameRepository.upsert(newGame(folder)).also { added++ }
            achievements.linkManually(gameId, AchievementProvider.LOCAL_STEAM, folder.appId)
        }
        Timber.i("Emu game import — ${found.size} folder(s), $added new game(s)")
        return EmuGameImportResult(discovered = found.size, added = added)
    }

    private fun newGame(folder: LocalSteamGame) = Game(
        title = folder.folderName,
        platformId = WINDOWS_PLATFORM_ID,
        romPath = RomRootRepository.docIdToRawPath(folder.folderDocId),
        isManualEntry = true,
        contentType = GameContentType.GAME,
    )

    // Mirrors the Windows-card dedupe rule (normalizePcTitle): imports with different launch
    // handles must converge on one game, and folder names count as titles.
    private fun normalizeTitle(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
