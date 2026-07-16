package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of reconciling emu game folders with the library, for the settings message. */
data class EmuGameImportResult(val discovered: Int, val linked: Int)

/**
 * Reconciles the emu game folders [LocalSteamDiscovery] finds with the LIBRARY — it never
 * creates game entities (user decision 2026-07-16): a game enters the Windows card only through
 * a launchable handle (pin / export / Add by ID). A folder that maps onto an existing game
 * links LOCAL_STEAM with the folder's appid and gets its ownership classified; an unmapped
 * folder simply stays a TRACKED local game, synced into Shiba Coins as an account-style entry
 * by the hub's Sync All (see `AchievementRepository.syncAllLinked`).
 *
 * Mapping is the shortcut-to-folder join (docs/windows-library-refactor-plan.md Phase 5):
 * normalized title first, then the STEAM-NAME BRIDGE — the appid's official store name matched
 * the same way — which survives renamed folders. No fuzzy matching, ever.
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

        // One-time hygiene for rows the pre-rework scan created: a windows GAME with no launch
        // handle at all (no package, no shortcut, no intent) is a folder import that can never
        // launch — removed here, and excluded from mapping. Links cascade with the row.
        val (dead, live) = gameRepository.getByPlatform(WINDOWS_PLATFORM_ID).partition {
            it.isManualEntry && it.packageName == null &&
                it.shortcutId == null && it.launchIntentUri == null
        }
        dead.forEach {
            Timber.i("Removing unlaunchable folder-import entity \"${it.displayTitle}\"")
            gameRepository.delete(it.id)
        }
        val byTitle = live.associateBy { normalizeTitle(it.displayTitle) }
        var linked = 0
        for (folder in found) {
            val match = byTitle[normalizeTitle(folder.folderName)] ?: steamNameBridge(folder, byTitle)
                ?: continue   // tracked, library-less — Shiba Coins picks it up on sync
            achievements.linkManually(match.id, AchievementProvider.LOCAL_STEAM, folder.appId)
            linked++
            val owned = ownership.classify(match.id, folder.appId)
            // An owned copy played offline holds BOTH sets — appid equality beats any title ladder.
            if (owned == LocalCopyOwnership.OWNED) {
                achievements.linkManually(match.id, AchievementProvider.STEAM, folder.appId)
            }
        }
        Timber.i("Emu folder reconcile — ${found.size} folder(s), $linked linked to library games")
        return EmuGameImportResult(discovered = found.size, linked = linked)
    }

    // The bridge: folder appid -> official Steam name -> exact normalized match against the
    // existing windows games (their shortcut labels come from Steam metadata too). Null when the
    // store has no name or nothing matches — never guesses.
    private suspend fun steamNameBridge(folder: LocalSteamGame, byTitle: Map<String, Game>): Game? {
        val official = steamNames.officialNameOf(folder.appId) ?: return null
        return byTitle[normalizeTitle(official)]
            ?.also { Timber.i("Steam-name bridge: \"${folder.folderName}\" -> \"${it.displayTitle}\" (${folder.appId})") }
    }

    // Mirrors the Windows-card dedupe rule (normalizePcTitle): imports with different launch
    // handles must converge on one game, and folder names count as titles.
    private fun normalizeTitle(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
