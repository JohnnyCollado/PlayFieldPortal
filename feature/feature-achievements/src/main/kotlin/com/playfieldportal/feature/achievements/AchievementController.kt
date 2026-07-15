package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamCandidate
import kotlinx.coroutines.flow.Flow

/**
 * The single entry point the UI layer uses for the Shiba Coins system: offline-first query Flows
 * that read straight from Room, plus the commands that link a game to a provider and sync its coin
 * data. Provider choice (RetroAchievements vs Steam) is resolved beneath this seam, never by callers.
 *
 * ViewModels depend on this interface, not on the [AchievementRepository] implementation — it is the
 * Controller in the coin system's MVC layering. See docs/shiba-coins-achievements-plan.md.
 */
interface AchievementController {

    /** This game's provider link (which provider + id it syncs from), or null if unlinked. */
    fun observeLink(gameId: Long): Flow<ProviderGameLinkEntity?>

    /** This game's coin summary (progress, tally, mastery), or null if never synced. */
    fun observeGameCoins(gameId: Long): Flow<GameCoins?>

    /** The raw per-coin rows for a game's dedicated coins screen. */
    fun observeCoins(gameId: Long): Flow<List<AccountAchievementEntity>>

    /** The account-wide Shiba wallet (total coins -> level + rank), derived from every set. */
    fun observeWallet(): Flow<CoinWallet>

    /** The whole-library standing for the Shiba Coins hub, with the [rarestLimit] rarest earned coins. */
    fun observeLibraryStanding(rarestLimit: Int = 15): Flow<LibraryStanding>

    /** Fetches [providerGameId] from [provider] and persists the result, or returns why it couldn't. */
    suspend fun syncGame(gameId: Long, provider: AchievementProvider, providerGameId: String): ProviderSyncResult

    /** Syncs a game from its stored link; [ProviderSyncResult.NotLinked] if it has none yet. */
    suspend fun syncGameById(gameId: Long): ProviderSyncResult

    /** Syncs every linked game in one pass; [onProgress] reports (done, total). */
    suspend fun syncAllLinked(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): BatchSyncResult

    /** Links a game to a provider id by hand — the always-works path. */
    suspend fun linkManually(gameId: Long, provider: AchievementProvider, providerGameId: String)

    /** Auto-links a game to Steam by matching [title] against the Steam app list; returns the appid or null. */
    suspend fun resolveSteamLink(gameId: Long, title: String): String?

    /** Resolves a game to Steam by its title variants, links it, and returns the appid or null. */
    suspend fun resolveSteamByGame(gameId: Long): String?

    /** Steam candidates whose name matches [query], for the manual "Find on Steam" picker. */
    suspend fun searchSteam(query: String): List<SteamCandidate>

    /** Removes a game's provider link so it can be re-matched. */
    suspend fun unlink(gameId: Long)
}
