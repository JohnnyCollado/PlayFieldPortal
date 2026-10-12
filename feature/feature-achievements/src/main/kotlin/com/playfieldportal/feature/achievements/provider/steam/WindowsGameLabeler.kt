package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.WindowsGameLabel
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamFolderLinker
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores a Windows game's [WindowsGameLabel] and brings its links in line with it, once.
 *
 * Links are the one thing sync, the wallet and the Shiba Coins page read, so a label is applied by
 * removing the links that contradict it rather than by gating them. Only the STEAM link is ever
 * removed; a LOCAL_STEAM link is the folder the user picked and survives every change. Cached sets
 * and coins stay, so switching back restores progress on the next link.
 */
@Singleton
class WindowsGameLabeler @Inject constructor(
    private val games: GameRepository,
    private val achievements: AchievementController,
    private val matcher: AchievementAutoMatcher,
    private val folderLinker: LocalSteamFolderLinker,
    private val discovery: LocalSteamDiscovery,
) {

    /** What changing a label did to the game's links, beyond saving the label. */
    sealed interface LabelOutcome {
        data object Saved : LabelOutcome

        /** Labelled Steam and the game was linked to Steam (and synced). */
        data object SteamLinked : LabelOutcome

        /** Labelled Steam but no Steam match was found; the label is saved all the same. */
        data object SteamNotFound : LabelOutcome

        /** Labelled Local and a folder the registry already held was linked (and synced). */
        data class FolderLinked(val folderName: String) : LabelOutcome
    }

    /**
     * A game's linked game folder. [folderName] and [folderDocId] are null when the LOCAL_STEAM link
     * outlived its registry row: still linked, but with no path to show.
     */
    data class LinkedFolder(val appId: String, val folderName: String?, val folderDocId: String?)

    /** Saves [label] (null = Auto) for [gameId] and reconciles its links; the label is stored first. */
    suspend fun setLabel(gameId: Long, label: WindowsGameLabel?): LabelOutcome {
        games.setWindowsLabel(gameId, label)
        return when (label) {
            WindowsGameLabel.STEAM -> linkSteamIfMissing(gameId)
            WindowsGameLabel.LOCAL -> {
                achievements.unlinkProvider(gameId, AchievementProvider.STEAM)
                linkRegisteredFolderIfMissing(gameId)
            }
            WindowsGameLabel.NON_STEAM -> {
                achievements.unlinkProvider(gameId, AchievementProvider.STEAM)
                LabelOutcome.Saved
            }
            null -> LabelOutcome.Saved
        }
    }

    /** Game id to its folder, for each Windows game holding a LOCAL_STEAM link. */
    suspend fun linkedFolders(): Map<Long, LinkedFolder> {
        val windowsIds = games.getByPlatform(WINDOWS_PLATFORM_ID).map { it.id }.toSet()
        val registry = discovery.registeredFolders().associateBy { it.appId }
        return achievements.linksByProvider(AchievementProvider.LOCAL_STEAM)
            .filter { it.gameId in windowsIds }
            .associate { link ->
                val row = registry[link.providerGameId]
                link.gameId to LinkedFolder(link.providerGameId, row?.folderName, row?.folderDocId)
            }
    }

    private suspend fun linkSteamIfMissing(gameId: Long): LabelOutcome {
        if (hasLink(gameId, AchievementProvider.STEAM)) return LabelOutcome.Saved
        if (!matcher.matchSingleAsSteam(gameId)) return LabelOutcome.SteamNotFound
        achievements.syncGameById(gameId)
        return LabelOutcome.SteamLinked
    }

    private suspend fun linkRegisteredFolderIfMissing(gameId: Long): LabelOutcome {
        if (hasLink(gameId, AchievementProvider.LOCAL_STEAM)) return LabelOutcome.Saved
        val folder = folderLinker.registeredFolderFor(gameId) ?: return LabelOutcome.Saved
        val linked = folderLinker.linkRegistered(gameId, folder) as? LocalSteamFolderLinker.LinkOutcome.Linked
            ?: return LabelOutcome.Saved
        achievements.syncGameById(gameId)
        return LabelOutcome.FolderLinked(linked.folderName)
    }

    private suspend fun hasLink(gameId: Long, provider: AchievementProvider): Boolean =
        achievements.observeLinks(gameId).first().any { it.provider == provider.name }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
