package com.playfieldportal.core.domain.achievement

/**
 * A count of individual coins by tier. Platinum is deliberately absent — it is the set-completion
 * award (see [GameCoins.isMastered]), not something tallied here.
 */
data class CoinCounts(
    val bronze: Int = 0,
    val silver: Int = 0,
    val gold: Int = 0,
) {
    /** Total number of individual coins. */
    val total: Int get() = bronze + silver + gold

    /** Weighted coin value of this tally in the unified XP economy. */
    val coinValue: Int
        get() = bronze * ShibaTier.BRONZE.coinValue +
            silver * ShibaTier.SILVER.coinValue +
            gold * ShibaTier.GOLD.coinValue

    operator fun plus(other: CoinCounts) = CoinCounts(
        bronze + other.bronze,
        silver + other.silver,
        gold + other.gold,
    )

    /** This tally plus one coin of [tier]. Platinum is a no-op (it is not an individual coin). */
    fun withCoin(tier: ShibaTier): CoinCounts = when (tier) {
        ShibaTier.BRONZE -> copy(bronze = bronze + 1)
        ShibaTier.SILVER -> copy(silver = silver + 1)
        ShibaTier.GOLD   -> copy(gold = gold + 1)
        ShibaTier.PLATINUM -> this
    }

    companion object {
        val EMPTY = CoinCounts()
    }
}

/**
 * A single game's coin standing: which coins are earned out of the total available, from one
 * provider. Everything the coin surfaces render derives from here.
 */
data class GameCoins(
    val provider: AchievementProvider,
    val earned: CoinCounts,
    val total: CoinCounts,
) {
    /** True once every individual coin is earned — the game is mastered and wins the Platinum crown. */
    val isMastered: Boolean get() = total.total > 0 && earned == total

    /** Weighted value of coins earned so far (individual coins only). */
    val earnedCoinValue: Int get() = earned.coinValue

    /** Weighted value of all individual coins available. */
    val totalCoinValue: Int get() = total.coinValue

    /**
     * Coin-weighted completion, 0f..1f. The Platinum is the prize for finishing, so it is excluded
     * from the denominator — progress reflects the individual coins only. Returns 0f when the game
     * has no coins.
     */
    val progress: Float
        get() = if (total.coinValue == 0) 0f
        else (earned.coinValue.toFloat() / total.coinValue).coerceIn(0f, 1f)

    /**
     * Coins this game banks into the wallet: its earned individual coins as they unlock (partial
     * credit), plus the Platinum's value once the set is mastered.
     */
    val walletContribution: Int
        get() = earned.coinValue + if (isMastered) ShibaTier.PLATINUM.coinValue else 0
}

/**
 * The account-wide wallet: a running coin total that resolves to a Shiba Level and rank. Built by
 * summing every game's [GameCoins.walletContribution].
 */
data class CoinWallet(val totalCoins: Int) {
    val level: Int get() = ShibaLevel.levelForCoins(totalCoins)
    val rank: ShibaRank get() = ShibaLevel.rankFor(level)
    val levelProgress: LevelProgress get() = ShibaLevel.progress(totalCoins)

    companion object {
        val EMPTY = CoinWallet(0)

        fun of(games: Iterable<GameCoins>): CoinWallet =
            CoinWallet(games.sumOf { it.walletContribution })
    }
}
