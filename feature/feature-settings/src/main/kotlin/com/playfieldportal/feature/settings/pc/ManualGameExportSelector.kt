package com.playfieldportal.feature.settings.pc

import com.playfieldportal.core.data.model.IntentUriExtras
import com.playfieldportal.core.data.model.StorefrontIdentity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.launcher.PcLauncherAdapters
import com.playfieldportal.feature.library.scanner.PcExportFile

/** The Windows games Export Manual Games writes a `.pfpgame` file for, and how many it left out. */
data class ManualGameExportSelection(
    val exported: List<Game>,
    val skipped: Int,
)

/**
 * Decides which Windows games get a `.pfpgame` file (C18 task X.2): the ones a fresh install could
 * not bring back on its own, plus pins, whose artwork names are needed even though pin reconcile
 * brings the game back.
 *
 * A game from a launcher export file and a game added by id look the same in the database
 * (`isManualEntry` plus a launch intent), so "comes back on its own" is decided against the launcher
 * export files actually in the import folders, not read from a column.
 */
object ManualGameExportSelector {

    private const val WINDOWS_PLATFORM_ID = "windows"

    /**
     * @param windowsGames the library's Windows games.
     * @param gamesWithArtwork ids of the games that have at least one artwork record.
     * @param launcherFiles the GameNative/Winlator export files currently in the import folders.
     * @param reproducedIntentUris the launch intent URIs the scan would build from [launcherFiles];
     *   computed by the caller, which has the `PackageManager` that building them needs.
     */
    fun select(
        windowsGames: List<Game>,
        gamesWithArtwork: Set<Long>,
        launcherFiles: List<PcExportFile>,
        reproducedIntentUris: Set<String>,
    ): ManualGameExportSelection {
        val filePairs = launcherFiles.mapNotNull(::storefrontPairOf).toSet()
        val desktopPaths = launcherFiles.filter { it.extension == "desktop" }.mapNotNull { it.rawPath }.toSet()

        val exported = windowsGames.filter { game ->
            when {
                game.platformId != WINDOWS_PLATFORM_ID -> false
                // A pin comes back through pin reconcile; its file only carries artwork names, so a
                // pin with no artwork has nothing to export.
                game.shortcutId != null -> game.id in gamesWithArtwork
                else -> {
                    val intentUri = game.launchIntentUri ?: return@filter false
                    val recreatedByFile = intentUri in reproducedIntentUris ||
                        WindowsGameKeys.storefrontPairOf(game)?.let { it in filePairs } == true ||
                        IntentUriExtras.stringExtra(intentUri, "shortcut_path")?.let { it in desktopPaths } == true
                    !recreatedByFile
                }
            }
        }
        return ManualGameExportSelection(exported, skipped = windowsGames.size - exported.size)
    }

    /** The (store, id) pair a launcher export file names, the same way `PcGameScanner` reads it. */
    private fun storefrontPairOf(file: PcExportFile): Pair<String, String>? {
        val store = StorefrontIdentity.normalizeStore(PcLauncherAdapters.gameSourceForExtension(file.extension))
            ?: return null
        val id = file.idContent?.trim()?.takeIf { StorefrontIdentity.isPlausibleAppId(it) } ?: return null
        return store to id
    }
}
