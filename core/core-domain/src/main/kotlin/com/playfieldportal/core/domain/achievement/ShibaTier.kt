package com.playfieldportal.core.domain.achievement

/**
 * The four Shiba Coin tiers, PlayStation-style. Each carries its coin value in the unified XP
 * economy (ratio 1 : 2 : 6 : 20, matching the PSN trophy weights).
 *
 * Bronze / Silver / Gold come from a difficulty signal that differs by provider:
 *  - **RetroAchievements** uses the achievement's own **point value** via [forRaPoints] — RA already
 *    weights achievements by difficulty (its standard values are 0-5 / 10 / 25 / 50 / 100), so the
 *    points are the natural tier spine there.
 *  - **Steam** has no points, so it falls back to global unlock rarity via [forRarity], calibrated
 *    against real PlayStation trophy data (~71 / 23 / 6 split).
 * Platinum is different: it is the 100% mastery award, minted locally when a whole set is completed,
 * and is never derived from either signal. See docs/shiba-coins-achievements-plan.md.
 */
enum class ShibaTier(val coinValue: Int) {
    BRONZE(15),
    SILVER(30),
    GOLD(90),
    PLATINUM(300);

    companion object {
        /** Rarity below this (percent of players who unlocked it) earns Gold. */
        const val GOLD_MAX_RARITY = 5.0

        /** Rarity below this, and at or above [GOLD_MAX_RARITY], earns Silver. */
        const val SILVER_MAX_RARITY = 20.0

        /** RA points at or above this earn Gold (RA's hard achievements are 50 / 100). */
        const val GOLD_MIN_POINTS = 50

        /** RA points at or above this, and below [GOLD_MIN_POINTS], earn Silver (RA's 10 / 25). */
        const val SILVER_MIN_POINTS = 10

        /**
         * Tier for a RetroAchievements coin from its point value. RA sets points by difficulty, so
         * they map straight to tiers: 50+ Gold, 10-49 Silver, below 10 (0-5) Bronze. Never returns
         * [PLATINUM] — that is the set-completion award, not a per-coin tier.
         */
        fun forRaPoints(points: Int): ShibaTier = when {
            points >= GOLD_MIN_POINTS   -> GOLD
            points >= SILVER_MIN_POINTS -> SILVER
            else                        -> BRONZE
        }

        /**
         * Tier for an individual coin from its global unlock rarity (0..100, percent of players
         * who own it) — used for providers without a point signal (Steam). Never returns [PLATINUM].
         * A rarity at or below 0 is treated as ultra-rare (Gold).
         */
        fun forRarity(globalUnlockPercent: Double): ShibaTier {
            val rarity = globalUnlockPercent.coerceAtLeast(0.0)
            return when {
                rarity < GOLD_MAX_RARITY   -> GOLD
                rarity < SILVER_MAX_RARITY -> SILVER
                else                       -> BRONZE
            }
        }
    }
}
