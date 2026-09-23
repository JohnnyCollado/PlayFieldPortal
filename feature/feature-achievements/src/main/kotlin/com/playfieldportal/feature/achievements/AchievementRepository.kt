package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao
import com.playfieldportal.core.data.database.dao.AchievementTrackingDao
import com.playfieldportal.core.data.database.dao.AwaitingSyncRow
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.AccountSetRow
import com.playfieldportal.core.data.database.dao.EarnedCoinRow
import com.playfieldportal.core.data.database.dao.RecentCoinRow
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.AwaitingSyncGame
import com.playfieldportal.core.domain.achievement.TrackedIdentityStatus
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import com.playfieldportal.core.domain.achievement.CoinCounts
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.EarnedCoinRef
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.RecentCoin
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.achievement.UntrackedGame
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.match.RaConsole
import com.playfieldportal.feature.achievements.sync.AchievementClock
import com.playfieldportal.feature.achievements.sync.AchievementIdentity
import com.playfieldportal.feature.achievements.sync.AchievementSyncCoordinator
import com.playfieldportal.feature.achievements.sync.AchievementUpdateSummary
import com.playfieldportal.feature.achievements.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for the coin system: offline-first reads straight from Room, plus the
 * link commands. Every refresh — whole-library, per-game, stale-on-open — is delegated to the
 * [AchievementSyncCoordinator], which scopes it to present, confirmed games
 * (docs/plans/PFP_Local_Achievements_Selective_Sync_Implementation_Plan.md). The wallet is
 * derived reactively from the confirmed set summaries, so it updates itself whenever a sync lands.
 */
@Singleton
class AchievementRepository @Inject constructor(
    private val credentials: AchievementCredentialsProvider,
    private val setDao: AccountAchievementSetDao,
    private val coinDao: AccountAchievementDao,
    private val linkDao: ProviderGameLinkDao,
    private val matchNoteDao: AchievementMatchNoteDao,
    private val trackingDao: AchievementTrackingDao,
    private val steamResolver: SteamAppListResolver,
    private val gameRepository: GameRepository,
    private val coordinator: AchievementSyncCoordinator,
    private val clock: AchievementClock,
) : AchievementController {
    /** This game's provider link (which provider + id it syncs from), or null if unlinked. */
    override fun observeLink(gameId: Long): Flow<ProviderGameLinkEntity?> = linkDao.observeForGame(gameId)
    /** This game's coin summary (progress, tally, mastery), or null if never synced. */
    override fun observeGameCoins(gameId: Long): Flow<GameCoins?> =
        setDao.observeForGame(gameId).map { it?.toGameCoins() }

    /** The raw per-coin rows for a game's dedicated coins screen. */
    override fun observeCoins(gameId: Long): Flow<List<AccountAchievementEntity>> =
        coinDao.observeForGame(gameId)

    /** An account entry's coin summary keyed by provider identity — no library game required. */
    override fun observeAccountGameCoins(provider: AchievementProvider, providerGameId: String): Flow<GameCoins?> =
        setDao.observeSet(provider.name, providerGameId).map { it?.toGameCoins() }

    /** An account entry's set row (title, provider art), for the provider-keyed coins screen. */
    override fun observeAccountSet(provider: AchievementProvider, providerGameId: String): Flow<AccountAchievementSetEntity?> =
        setDao.observeSet(provider.name, providerGameId)

    /** An account entry's per-coin rows keyed by provider identity. */
    override fun observeAccountCoins(provider: AchievementProvider, providerGameId: String): Flow<List<AccountAchievementEntity>> =
        coinDao.observeForSet(provider.name, providerGameId)

    /** The account-wide Shiba wallet (total coins -> level + rank), from every confirmed set. */
    override fun observeWallet(): Flow<CoinWallet> =
        setDao.observeWalletCoins().map { CoinWallet(it) }

    /**
     * The whole-library standing for the Shiba Coins hub: the wallet, every confirmed game's
     * standing (removed games included, marked not installed), matched games still awaiting their
     * first sync, and the [rarestLimit] rarest earned coins. Reads only cached rows — no network —
     * so the hub is offline-first.
     */
    override fun observeLibraryStanding(rarestLimit: Int): Flow<LibraryStanding> =
        combine(
            combine(observeWallet(), setDao.observeAccountSets(), coinDao.observeRarestEarned(rarestLimit)) {
                wallet, sets, rarest -> Triple(wallet, sets, rarest)
            },
            gameRepository.observeGamesOnly(),
            linkDao.observeLinkedGameIds(),
            matchNoteDao.observeAll(),
            setDao.observeAwaitingSync(),
        ) { (wallet, sets, rarest), games, linkedIds, notes, awaiting ->
            val linked = linkedIds.toHashSet()
            val noteByGame = notes.associate { it.gameId to it.reason }
            LibraryStanding(
                wallet = wallet,
                tracked = sets.mapNotNull { it.toGameStanding() },
                rarestEarned = rarest.mapNotNull { it.toEarnedCoinRef() },
                // Untracked lists present games only: a missing ROM is not something to match.
                // Android games can never have achievements, so they are never "untracked".
                untracked = games
                    .filterNot { it.id in linked || it.isMissing || it.platformId == "android" }
                    .map { it.toUntrackedGame(noteByGame[it.id]) },
                awaitingSync = awaiting.mapNotNull { it.toAwaitingSyncGame() },
            )
        }

    /** The most recently earned coins across the account (newest first), for the status view's feed. */
    override fun observeRecentCoins(limit: Int): Flow<List<RecentCoin>> =
        coinDao.observeRecentEarned(limit).map { rows -> rows.mapNotNull { it.toRecentCoin() } }

    /** Explicit per-game refresh of [gameId]'s provider identity (the page's Refresh). */
    override suspend fun syncGame(
        gameId: Long,
        provider: AchievementProvider,
        providerGameId: String,
    ): ProviderSyncResult = coordinator.refreshGame(gameId)

    /**
     * Explicit refresh of a provider-keyed entry (a Local Steam folder with no library game).
     * Only a present, confirmed entry refreshes; a removed game keeps its cached coins.
     */
    override suspend fun syncAccountEntry(
        provider: AchievementProvider,
        providerGameId: String,
        title: String,
    ): ProviderSyncResult = coordinator.refreshIdentity(AchievementIdentity(provider, providerGameId), title)

    /**
     * Links a game to a provider id — by hand, or from an auto-match. A match for a game present on
     * this device is confirmed into the tracked ledger right away, before any coin fetch.
     */
    override suspend fun linkManually(gameId: Long, provider: AchievementProvider, providerGameId: String) {
        val id = providerGameId.trim()
        linkDao.upsert(
            ProviderGameLinkEntity(
                gameId = gameId,
                provider = provider.name,
                providerGameId = id,
                source = "MANUAL",
                resolvedAt = clock.now(),
            ),
        )
        matchNoteDao.deleteForGame(gameId) // it's linked now — drop any "untracked" note
        confirmIfPresent(gameId, provider, id)
    }

    private suspend fun confirmIfPresent(gameId: Long, provider: AchievementProvider, providerGameId: String) {
        val game = gameRepository.getById(gameId) ?: return
        if (game.isMissing) return
        trackingDao.confirm(provider.name, providerGameId, game.displayTitle, clock.now())
    }

    /**
     * Tries to auto-link a game to Steam by matching its [title] against the Steam app list. Stores
     * and returns the resolved appid, or null when there is no match.
     */
    override suspend fun resolveSteamLink(gameId: Long, title: String): String? {
        val appId = steamResolver.resolveAppId(title) ?: return null
        linkDao.upsert(
            ProviderGameLinkEntity(
                gameId = gameId,
                provider = AchievementProvider.STEAM.name,
                providerGameId = appId,
                source = "STEAM_TITLE",
                resolvedAt = clock.now(),
            ),
        )
        matchNoteDao.deleteForGame(gameId)
        confirmIfPresent(gameId, AchievementProvider.STEAM, appId)
        return appId
    }

    /**
     * Resolves a game to Steam by title, trying its full title, scraped title, and display override
     * (in that order) so a shortened override doesn't hide the full store name. Links + returns the
     * appid, or null. Used by the coins screen's "Match by title".
     */
    override suspend fun resolveSteamByGame(gameId: Long): String? {
        val game = gameRepository.getById(gameId) ?: return null
        val titles = listOfNotNull(game.title, game.scrapedTitle, game.displayTitle)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (title in titles) resolveSteamLink(gameId, title)?.let { return it }
        return null
    }

    /** Steam candidates whose name matches [query], for the manual "Find on Steam" picker. */
    override suspend fun searchSteam(query: String): List<com.playfieldportal.feature.achievements.provider.steam.SteamCandidate> =
        steamResolver.search(query)

    /**
     * Removes a game's provider link so it can be re-matched (Change Match: the user is saying the
     * match was wrong). The identity leaves the tracked ledger unless another link still points at
     * it, so it stops counting and stops syncing; its cached set stays stored, untouched, until a
     * real match brings it back.
     */
    override suspend fun unlink(gameId: Long) {
        val removed = trackingDao.linkPresenceForGame(gameId)
        linkDao.deleteForGame(gameId)
        removed.forEach { link ->
            if (!linkDao.linkExistsFor(link.provider, link.providerGameId)) {
                trackingDao.deleteIdentity(link.provider, link.providerGameId)
            }
        }
    }

    /** Explicit refresh from the game's stored link; [ProviderSyncResult.NotLinked] if it has none. */
    override suspend fun syncGameById(gameId: Long): ProviderSyncResult = coordinator.refreshGame(gameId)

    override suspend fun updateInstalledAchievements(
        onProgress: (done: Int, total: Int) -> Unit,
    ): AchievementUpdateSummary = coordinator.updateInstalled(SyncTrigger.MANUAL, onProgress)

    override fun cancelUpdate() = coordinator.cancelUpdate()

    override suspend fun refreshGameIfStale(gameId: Long): ProviderSyncResult? =
        coordinator.refreshGameIfStale(gameId)

    override suspend fun refreshAccountEntryIfStale(provider: AchievementProvider, providerGameId: String): ProviderSyncResult? =
        coordinator.refreshIfStale(AchievementIdentity(provider, providerGameId), "")

    override fun observeIdentityStatus(provider: AchievementProvider, providerGameId: String): Flow<TrackedIdentityStatus?> =
        trackingDao.observeIdentity(provider.name, providerGameId).map { row ->
            row?.let { TrackedIdentityStatus(it.isPresent, it.lastCheckedAt, it.lastDetailAt) }
        }

    override fun observeAutoUpdatesPaused(): Flow<Boolean> = credentials.autoUpdatesPausedFlow

    override suspend fun clearAllTrackedAchievements(): Boolean = coordinator.clearAll()

}

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun AccountAchievementSetEntity.toGameCoins(): GameCoins? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return GameCoins(
        provider = p,
        earned = CoinCounts(bronzeEarned, silverEarned, goldEarned),
        total = CoinCounts(bronzeTotal, silverTotal, goldTotal),
        isMastered = mastered,
        lastSyncedAt = lastSyncedAt,
    )
}

private fun AccountSetRow.toGameStanding(): GameStanding? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return GameStanding(
        providerGameId = providerGameId,
        libraryGameId = libraryGameId,
        title = title,
        iconUrl = iconUrl,
        coins = GameCoins(
            provider = p,
            earned = CoinCounts(bronzeEarned, silverEarned, goldEarned),
            total = CoinCounts(bronzeTotal, silverTotal, goldTotal),
            isMastered = mastered,
            lastSyncedAt = lastSyncedAt,
        ),
        isInstalled = isPresent,
    )
}

private fun AwaitingSyncRow.toAwaitingSyncGame(): AwaitingSyncGame? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return AwaitingSyncGame(gameId = gameId, provider = p, providerGameId = providerGameId, title = title)
}

// Why a game has no achievement link. Prefers the specific reason the last auto-match recorded
// (e.g. "Couldn't find the disc's boot executable"); otherwise falls back to a platform guess for
// games not yet auto-matched.
private fun Game.toUntrackedGame(persistedReason: String?) = UntrackedGame(
    gameId = id,
    title = displayTitle,
    platformId = platformId,
    reason = persistedReason ?: when {
        platformId == "windows" -> "Not found on Steam"
        RaConsole.idFor(platformId) == null -> "System not supported by RetroAchievements"
        else -> "Not matched yet — run Auto-match for details"
    },
)

private fun EarnedCoinRow.toEarnedCoinRef(): EarnedCoinRef? {
    val t = runCatching { ShibaTier.valueOf(tier) }.getOrNull() ?: return null
    return EarnedCoinRef(
        libraryGameId = libraryGameId,
        gameTitle = gameTitle,
        coinTitle = title,
        tier = t,
        globalRarity = globalRarity,
        iconUrl = iconUrl,
    )
}

private fun RecentCoinRow.toRecentCoin(): RecentCoin? {
    val t = runCatching { ShibaTier.valueOf(tier) }.getOrNull() ?: return null
    return RecentCoin(
        libraryGameId = libraryGameId,
        gameTitle = gameTitle,
        coinTitle = title,
        tier = t,
        iconUrl = iconUrl,
        earnedAt = earnedAt,
        globalRarity = globalRarity,
    )
}
