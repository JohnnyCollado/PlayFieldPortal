package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.RecentCoin
import com.playfieldportal.core.domain.achievement.TrackedIdentityStatus
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamCandidate
import com.playfieldportal.feature.achievements.sync.AchievementUpdateSummary
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

    /** An account entry's coin summary keyed by provider identity — no library game required. */
    fun observeAccountGameCoins(provider: AchievementProvider, providerGameId: String): Flow<GameCoins?>

    /** An account entry's set row (title, provider art), for the provider-keyed coins screen. */
    fun observeAccountSet(provider: AchievementProvider, providerGameId: String): Flow<AccountAchievementSetEntity?>

    /** An account entry's per-coin rows keyed by provider identity. */
    fun observeAccountCoins(provider: AchievementProvider, providerGameId: String): Flow<List<AccountAchievementEntity>>

    /** Explicitly refreshes a present entry keyed by provider identity; removed games are refused. */
    suspend fun syncAccountEntry(provider: AchievementProvider, providerGameId: String, title: String): ProviderSyncResult

    /** The account-wide Shiba wallet (total coins -> level + rank), from every confirmed set. */
    fun observeWallet(): Flow<CoinWallet>

    /** The whole-library standing for the Shiba Coins hub, with the [rarestLimit] rarest earned coins. */
    fun observeLibraryStanding(rarestLimit: Int = 15): Flow<LibraryStanding>

    /** The [limit] most recently earned coins across the account, newest first — the status view's feed. */
    fun observeRecentCoins(limit: Int = 8): Flow<List<RecentCoin>>

    /** Explicit "Refresh this game" for [gameId], or returns why it couldn't. */
    suspend fun syncGame(gameId: Long, provider: AchievementProvider, providerGameId: String): ProviderSyncResult

    /** Explicit refresh from the game's stored link; [ProviderSyncResult.NotLinked] if it has none. */
    suspend fun syncGameById(gameId: Long): ProviderSyncResult

    /**
     * "Update installed achievements": checks every present, matched game selectively (never
     * removed games, never the whole account); [onProgress] reports (done, total). Resumes
     * scheduled updates if a clear had paused them.
     */
    suspend fun updateInstalledAchievements(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): AchievementUpdateSummary

    /** Stops a running update; saved progress is kept. */
    fun cancelUpdate()

    /** Stale-on-open: refreshes [gameId] only when its detail is over a day old; null if not needed. */
    suspend fun refreshGameIfStale(gameId: Long): ProviderSyncResult?

    /** Stale-on-open for a provider-keyed entry; null if not needed (or not installed). */
    suspend fun refreshAccountEntryIfStale(provider: AchievementProvider, providerGameId: String): ProviderSyncResult?

    /** Installed / last-checked status of a confirmed identity, or null when it isn't tracked. */
    fun observeIdentityStatus(provider: AchievementProvider, providerGameId: String): Flow<TrackedIdentityStatus?>

    /** True after Clear all tracked achievements until the user explicitly resyncs. */
    fun observeAutoUpdatesPaused(): Flow<Boolean>

    /** Removes every achievement record PFP stores and pauses scheduled updates; false if it failed. */
    suspend fun clearAllTrackedAchievements(): Boolean

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
