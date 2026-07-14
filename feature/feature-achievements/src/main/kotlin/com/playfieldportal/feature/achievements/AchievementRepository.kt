package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AchievementDao
import com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao
import com.playfieldportal.core.data.database.dao.AchievementSetDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.EarnedCoinRow
import com.playfieldportal.core.data.database.dao.GameSetRow
import com.playfieldportal.core.data.database.entity.AchievementEntity
import com.playfieldportal.core.data.database.entity.AchievementSetEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import com.playfieldportal.core.domain.achievement.CoinCounts
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.EarnedCoinRef
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.achievement.UntrackedGame
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSources
import com.playfieldportal.feature.achievements.match.RaConsole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for the coin system: offline-first reads straight from Room, and a
 * network sync that maps a provider fetch into the persisted set + per-coin rows. The wallet is
 * derived reactively from the set summaries, so it updates itself whenever a sync lands — nothing
 * to recompute by hand. See docs/shiba-coins-achievements-plan.md.
 */
@Singleton
class AchievementRepository @Inject constructor(
    private val remoteSources: RemoteAchievementSources,
    private val credentials: AchievementCredentialsProvider,
    private val setDao: AchievementSetDao,
    private val coinDao: AchievementDao,
    private val linkDao: ProviderGameLinkDao,
    private val matchNoteDao: AchievementMatchNoteDao,
    private val steamResolver: SteamAppListResolver,
    private val gameRepository: GameRepository,
) : AchievementController {
    /** This game's provider link (which provider + id it syncs from), or null if unlinked. */
    override fun observeLink(gameId: Long): Flow<ProviderGameLinkEntity?> = linkDao.observeForGame(gameId)
    /** This game's coin summary (progress, tally, mastery), or null if never synced. */
    override fun observeGameCoins(gameId: Long): Flow<GameCoins?> =
        setDao.observeForGame(gameId).map { it?.toGameCoins() }

    /** The raw per-coin rows for a game's dedicated coins screen. */
    override fun observeCoins(gameId: Long): Flow<List<AchievementEntity>> =
        coinDao.observeForGame(gameId)

    /** The account-wide Shiba wallet (total coins -> level + rank), derived from every set. */
    override fun observeWallet(): Flow<CoinWallet> =
        setDao.observeWalletCoins().map { CoinWallet(it) }

    /**
     * The whole-library standing for the Shiba Coins hub: the wallet, every tracked game's standing,
     * and the [rarestLimit] rarest earned coins. Counts and the mastery lens derive from the tracked
     * list. Reads only cached rows — no network — so the hub is offline-first.
     */
    override fun observeLibraryStanding(rarestLimit: Int): Flow<LibraryStanding> =
        combine(
            combine(observeWallet(), setDao.observeGameSets(), coinDao.observeRarestEarned(rarestLimit)) {
                wallet, sets, rarest -> Triple(wallet, sets, rarest)
            },
            gameRepository.observeGamesOnly(),
            linkDao.observeLinkedGameIds(),
            matchNoteDao.observeAll(),
        ) { (wallet, sets, rarest), games, linkedIds, notes ->
            val linked = linkedIds.toHashSet()
            val noteByGame = notes.associate { it.gameId to it.reason }
            LibraryStanding(
                wallet = wallet,
                tracked = sets.mapNotNull { it.toGameStanding() },
                rarestEarned = rarest.mapNotNull { it.toEarnedCoinRef() },
                untracked = games.filterNot { it.id in linked }.map { it.toUntrackedGame(noteByGame[it.id]) },
            )
        }

    /**
     * Fetches [providerGameId] from [provider] and persists the result. On success the per-coin
     * rows are replaced (pruning coins the provider dropped) and the summary is rewritten; any
     * other outcome leaves the stored data untouched and is returned for the UI to surface.
     */
    override suspend fun syncGame(
        gameId: Long,
        provider: AchievementProvider,
        providerGameId: String,
    ): ProviderSyncResult {
        val result = remoteSources.forProvider(provider).fetch(providerGameId)
        if (result !is ProviderSyncResult.Success) return result

        val now = System.currentTimeMillis()
        coinDao.deleteForGame(gameId)
        coinDao.upsertAll(result.coins.map { it.toEntity(gameId, provider) })
        setDao.upsert(summaryOf(gameId, provider, result.providerGameId, result.coins, now))
        credentials.setLastSyncedAt(now)
        return result
    }

    /** Links a game to a provider id by hand — the always-works path (and the only one for RA yet). */
    override suspend fun linkManually(gameId: Long, provider: AchievementProvider, providerGameId: String) {
        linkDao.upsert(
            ProviderGameLinkEntity(
                gameId = gameId,
                provider = provider.name,
                providerGameId = providerGameId.trim(),
                source = "MANUAL",
                resolvedAt = System.currentTimeMillis(),
            ),
        )
        matchNoteDao.deleteForGame(gameId) // it's linked now — drop any "untracked" note
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
                resolvedAt = System.currentTimeMillis(),
            ),
        )
        matchNoteDao.deleteForGame(gameId)
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

    /** Removes a game's provider link so it can be re-matched (edit a wrong auto-match). */
    override suspend fun unlink(gameId: Long) = linkDao.deleteForGame(gameId)

    /** Syncs a game from its stored link; [ProviderSyncResult.NotLinked] if it has none yet. */
    override suspend fun syncGameById(gameId: Long): ProviderSyncResult {
        val link = linkDao.getForGame(gameId) ?: return ProviderSyncResult.NotLinked
        val provider = AchievementProvider.fromName(link.provider) ?: return ProviderSyncResult.NotLinked
        return syncGame(gameId, provider, link.providerGameId)
    }

    /**
     * Syncs every linked game in one pass, refreshing all coin data at once. Each provider fetch is
     * already rate-limited in its client, so this paces itself. [onProgress] reports (done, total);
     * per-game failures are counted, never thrown, so one bad game can't abort the run.
     */
    override suspend fun syncAllLinked(onProgress: (done: Int, total: Int) -> Unit): BatchSyncResult {
        val links = linkDao.getAll()
        var synced = 0
        var noCoins = 0
        var failed = 0
        var missingCredentials = false
        links.forEachIndexed { index, link ->
            onProgress(index, links.size)
            val provider = AchievementProvider.fromName(link.provider)
            if (provider == null) { failed++; return@forEachIndexed }
            when (syncGame(link.gameId, provider, link.providerGameId)) {
                is ProviderSyncResult.Success -> synced++
                ProviderSyncResult.NotFound -> noCoins++
                ProviderSyncResult.MissingCredentials -> missingCredentials = true
                ProviderSyncResult.ProfileNotPublic -> failed++
                is ProviderSyncResult.Failed -> failed++
                ProviderSyncResult.NotLinked -> Unit // impossible here (came from a link)
            }
        }
        onProgress(links.size, links.size)
        return BatchSyncResult(
            total = links.size,
            synced = synced,
            noCoins = noCoins,
            failed = failed,
            missingCredentials = missingCredentials,
        )
    }
}

/** Outcome of a [AchievementRepository.syncAllLinked] pass, for the settings report. */
data class BatchSyncResult(
    val total: Int,
    val synced: Int,
    val noCoins: Int,
    val failed: Int,
    val missingCredentials: Boolean,
)

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun AchievementSetEntity.toGameCoins(): GameCoins? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return GameCoins(
        provider = p,
        earned = CoinCounts(bronzeEarned, silverEarned, goldEarned),
        total = CoinCounts(bronzeTotal, silverTotal, goldTotal),
        isMastered = mastered,
    )
}

private fun GameSetRow.toGameStanding(): GameStanding? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return GameStanding(
        gameId = gameId,
        title = title,
        coins = GameCoins(
            provider = p,
            earned = CoinCounts(bronzeEarned, silverEarned, goldEarned),
            total = CoinCounts(bronzeTotal, silverTotal, goldTotal),
            isMastered = mastered,
        ),
    )
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
        gameId = gameId,
        gameTitle = gameTitle,
        coinTitle = title,
        tier = t,
        globalRarity = globalRarity,
        iconUrl = iconUrl,
    )
}

private fun SyncedCoin.toEntity(gameId: Long, provider: AchievementProvider) = AchievementEntity(
    gameId = gameId,
    provider = provider.name,
    providerAchievementId = providerAchievementId,
    title = title,
    description = description,
    tier = tier.name,
    globalRarity = globalRarity,
    iconUrl = iconUrl,
    isHidden = isHidden,
    isEarned = isEarned,
    earnedAt = earnedAt,
)

private fun summaryOf(
    gameId: Long,
    provider: AchievementProvider,
    providerGameId: String,
    coins: List<SyncedCoin>,
    now: Long,
): AchievementSetEntity {
    fun count(tier: ShibaTier, earnedOnly: Boolean) =
        coins.count { it.tier == tier && (!earnedOnly || it.isEarned) }
    return AchievementSetEntity(
        gameId = gameId,
        provider = provider.name,
        providerGameId = providerGameId,
        bronzeTotal = count(ShibaTier.BRONZE, earnedOnly = false),
        silverTotal = count(ShibaTier.SILVER, earnedOnly = false),
        goldTotal = count(ShibaTier.GOLD, earnedOnly = false),
        bronzeEarned = count(ShibaTier.BRONZE, earnedOnly = true),
        silverEarned = count(ShibaTier.SILVER, earnedOnly = true),
        goldEarned = count(ShibaTier.GOLD, earnedOnly = true),
        // Platinum crown = 100% mastery. RA requires every coin in hardcore; Steam mirrors
        // isEarned into earnedHardcore, so this stays "every coin earned" there.
        mastered = coins.isNotEmpty() && coins.all { it.earnedHardcore },
        lastSyncedAt = now,
    )
}
