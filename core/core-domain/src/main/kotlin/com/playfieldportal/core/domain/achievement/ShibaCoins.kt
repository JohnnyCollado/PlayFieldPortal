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
    /**
     * The Platinum crown — every individual coin earned. Stored, not re-derived from [earned] ==
     * [total], because RetroAchievements grants mastery only for a full HARDCORE clear while
     * [earned] deliberately banks softcore unlocks too, so a count comparison would over-award the
     * crown. The provider sync is the single source of truth (see AchievementRepository.summaryOf);
     * Steam has no softcore/hardcore split, so there it coincides with earned == total.
     */
    val isMastered: Boolean,
) {

    /** Weighted value of coins earned so far (individual coins only). */
    val earnedCoinValue: Int get() = earned.coinValue

    /** Weighted value of all individual coins available. */
    val totalCoinValue: Int get() = total.coinValue

    /**
     * Completion as a plain count ratio (earned coins / total coins), 0f..1f. Deliberately NOT
     * coin-weighted: weighting by tier value would make a game with a few Golds look more complete
     * than it is. The Platinum is the prize for finishing, so it is excluded from both sides —
     * these tallies only ever count individual Bronze/Silver/Gold coins. Returns 0f when the game
     * has no coins.
     */
    val progress: Float
        get() = if (total.total == 0) 0f
        else (earned.total.toFloat() / total.total).coerceIn(0f, 1f)

    /**
     * Coins this game banks into the wallet: its earned individual coins as they unlock (partial
     * credit), plus the Platinum's value once the set is mastered.
     */
    val walletContribution: Int
        get() = earned.coinValue + if (isMastered) ShibaTier.PLATINUM.coinValue else 0
}

/**
 * The account-wide wallet: a running coin total that resolves to a Shiba Level, rank, and Paws.
 * Built by summing every game's [GameCoins.walletContribution].
 *
 * The level curve is uncapped internally ([totalLevel]); the DISPLAYED [level] is an odometer
 * that rolls over every 999 levels, minting one Paw per completed cycle. Ranks derive from the
 * total, so Inu Master — like a Paw — is earned once and never resets (July 2026 decisions).
 */
data class CoinWallet(val totalCoins: Int) {
    /** The uncapped level the coin total resolves to — the internal source of truth. */
    val totalLevel: Int get() = ShibaLevel.levelForCoins(totalCoins)

    /** The displayed Shiba Level, cycling 1..999 within the current Paw cycle. */
    val level: Int get() = ShibaLevel.cycleLevelFor(totalLevel)

    /** Prestige marks: one Paw per 999 levels earned. */
    val paws: Int get() = ShibaLevel.pawsForLevel(totalLevel)

    val rank: ShibaRank get() = ShibaLevel.rankFor(totalLevel)
    val levelProgress: LevelProgress get() = ShibaLevel.progress(totalCoins)

    companion object {
        val EMPTY = CoinWallet(0)

        fun of(games: Iterable<GameCoins>): CoinWallet =
            CoinWallet(games.sumOf { it.walletContribution })
    }
}
