package com.playfieldportal.core.domain.achievement

/**
 * One tracked entry's coin standing plus its identity — the row shape behind the "Closest to
 * Mastery" and "All Tracked" lenses of the Shiba Coins hub. Tracking is account-wide: an entry
 * is identified by its provider game id, and [libraryGameId] is set only when a library game
 * links to it (the hub's "in library" marker; account-imported entries have none).
 */
data class GameStanding(
    val providerGameId: String,
    val libraryGameId: Long?,
    val title: String,
    val iconUrl: String?,
    val coins: GameCoins,
) {
    val inLibrary: Boolean get() = libraryGameId != null

    /** Coin-weighted completion, 0f..1f (Platinum excluded, per [GameCoins.progress]). */
    val progress: Float get() = coins.progress

    /** True once every individual coin is earned. */
    val isMastered: Boolean get() = coins.isMastered
}

/**
 * A game with no achievement link, plus the plain reason why — the row shape behind the hub's
 * "Untracked" section. [reason] is user-facing (e.g. "System not supported by RetroAchievements").
 */
data class UntrackedGame(
    val gameId: Long,
    val title: String,
    val platformId: String,
    val reason: String,
)

/**
 * A single earned coin tied back to its game — the row shape behind the "Rarest Earned" lens.
 * [globalRarity] is the percent of players who own it (lower is rarer).
 */
data class EarnedCoinRef(
    val libraryGameId: Long?,
    val gameTitle: String,
    val coinTitle: String,
    val tier: ShibaTier,
    val globalRarity: Double,
    val iconUrl: String?,
)

/**
 * A recently earned coin tied back to its game — the row shape behind the player status view's
 * "Recent Achievements" list, ordered most-recent first. [earnedAt] is the unlock time in epoch
 * millis.
 */
data class RecentCoin(
    val libraryGameId: Long?,
    val gameTitle: String,
    val coinTitle: String,
    val tier: ShibaTier,
    val iconUrl: String?,
    val earnedAt: Long,
)

/**
 * The whole-library Shiba standing shown by the achievements hub: the account wallet, every tracked
 * game's standing, and the rarest earned coins. Counts and the "closest to mastery" lens derive
 * from [tracked], so there is a single source of truth. Built entirely from cached rows, so the hub
 * renders offline. See docs/shiba-coins-achievements-plan.md.
 */
data class LibraryStanding(
    val wallet: CoinWallet = CoinWallet.EMPTY,
    val tracked: List<GameStanding> = emptyList(),
    val rarestEarned: List<EarnedCoinRef> = emptyList(),
    val untracked: List<UntrackedGame> = emptyList(),
) {
    /** Number of games with a synced set. */
    val gamesTracked: Int get() = tracked.size

    /** Individual coins earned across every tracked set (a count, not the weighted value). */
    val coinsEarned: Int get() = tracked.sumOf { it.coins.earned.total }

    /** Individual coins available across every tracked set. */
    val coinsAvailable: Int get() = tracked.sumOf { it.coins.total.total }

    /** Number of tracked games that own their Platinum. */
    val gamesMastered: Int get() = tracked.count { it.isMastered }

    /**
     * Account-wide earned-coin tally by tier (Bronze/Silver/Gold), summed across every tracked set.
     * Platinum is not an individual coin (see [CoinCounts]); the account's Platinum count is
     * [gamesMastered], the number of mastered sets.
     */
    val walletCounts: CoinCounts
        get() = tracked.fold(CoinCounts.EMPTY) { acc, standing -> acc + standing.coins.earned }

    /** In-progress games nearest to completion, most complete first. */
    fun closestToMastery(limit: Int = 10): List<GameStanding> =
        tracked.asSequence()
            .filter { !it.isMastered && it.progress > 0f }
            .sortedByDescending { it.progress }
            .take(limit)
            .toList()

    /** Tracked games ordered for the "all tracked" lens: mastered first, then by progress. */
    val allByStanding: List<GameStanding>
        get() = tracked.sortedWith(
            compareByDescending<GameStanding> { it.isMastered }.thenByDescending { it.progress },
        )
}
