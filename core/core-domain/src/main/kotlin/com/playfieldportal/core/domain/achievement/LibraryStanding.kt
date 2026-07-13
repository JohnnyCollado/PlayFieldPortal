package com.playfieldportal.core.domain.achievement

/**
 * One tracked game's coin standing plus its identity — the row shape behind the "Closest to
 * Mastery" and "All Tracked Games" lenses of the Shiba Coins hub.
 */
data class GameStanding(
    val gameId: Long,
    val title: String,
    val coins: GameCoins,
) {
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
    val gameId: Long,
    val gameTitle: String,
    val coinTitle: String,
    val tier: ShibaTier,
    val globalRarity: Double,
    val iconUrl: String?,
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

    /** Number of tracked games that own their Platinum. */
    val gamesMastered: Int get() = tracked.count { it.isMastered }

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
