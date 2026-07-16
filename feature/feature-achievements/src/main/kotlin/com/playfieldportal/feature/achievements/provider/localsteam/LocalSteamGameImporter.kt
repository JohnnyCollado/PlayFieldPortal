package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
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
 * The shortcut-to-folder mapping join runs here (docs/windows-library-refactor-plan.md Phase 5):
 * a folder converges on an existing game by normalized title first, then by the STEAM-NAME
 * BRIDGE — the appid's official store name matched the same way — which survives renamed
 * folders (live proof: `FINAL FANTASY FFX-FFX-2 HD Remaster` -> shortcut "FINAL FANTASY X/X-2
 * HD Remaster"). No fuzzy matching: an unmapped folder simply becomes its own game.
 *
 * Each link is classified against the owned-games cache ([LocalSteamOwnership]); an OWNED copy
 * also gets the STEAM link with the same appid — the two sets coexist by design.
 */
@Singleton
class LocalSteamGameImporter @Inject constructor(
    private val discovery: LocalSteamDiscovery,
    private val gameRepository: GameRepository,
    private val achievements: AchievementController,
    private val ownership: LocalSteamOwnership,
    private val steamNames: SteamAppListResolver,
) {
    suspend fun import(): EmuGameImportResult {
        val found = discovery.scan()
        if (found.isEmpty()) return EmuGameImportResult(0, 0)

        val existing = gameRepository.getByPlatform(WINDOWS_PLATFORM_ID)
        val byTitle = existing.associateBy { normalizeTitle(it.displayTitle) }.toMutableMap()
        var added = 0
        for (folder in found) {
            val match = byTitle[normalizeTitle(folder.folderName)] ?: steamNameBridge(folder, byTitle)
            val gameId = match?.id ?: gameRepository.upsert(newGame(folder)).also {
                added++
                byTitle[normalizeTitle(folder.folderName)] = newGame(folder).copy(id = it)
            }
            achievements.linkManually(gameId, AchievementProvider.LOCAL_STEAM, folder.appId)
            val owned = ownership.classify(gameId, folder.appId)
            // An owned copy played offline holds BOTH sets — appid equality beats any title ladder.
            if (owned == LocalCopyOwnership.OWNED) {
                achievements.linkManually(gameId, AchievementProvider.STEAM, folder.appId)
            }
        }
        Timber.i("Emu game import — ${found.size} folder(s), $added new game(s)")
        return EmuGameImportResult(discovered = found.size, added = added)
    }

    // The bridge: folder appid -> official Steam name -> exact normalized match against the
    // existing windows games (their shortcut labels come from Steam metadata too). Null when the
    // store has no name or nothing matches — never guesses.
    private suspend fun steamNameBridge(folder: LocalSteamGame, byTitle: Map<String, Game>): Game? {
        val official = steamNames.officialNameOf(folder.appId) ?: return null
        return byTitle[normalizeTitle(official)]
            ?.also { Timber.i("Steam-name bridge: \"${folder.folderName}\" -> \"${it.displayTitle}\" (${folder.appId})") }
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
