package com.playfieldportal.core.domain.achievement

/**
 * The four Shiba Coin tiers, PlayStation-style. Each carries its coin value in the unified XP
 * economy (ratio 1 : 2 : 6 : 20, matching the PSN trophy weights).
 *
 * Bronze / Silver / Gold are assigned from a coin's global unlock rarity via [forRarity] —
 * calibrated against real PlayStation trophy data so the tier mix reproduces the roughly
 * 71 / 23 / 6 split. Platinum is different: it is the 100% mastery award, minted locally when a
 * whole set is completed, and is never derived from rarity. See
 * docs/shiba-coins-achievements-plan.md.
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

        /**
         * Tier for an individual coin from its global unlock rarity (0..100, percent of players
         * who own it). Never returns [PLATINUM] — that is the set-completion award, not a
         * per-coin tier. A rarity at or below 0 is treated as ultra-rare (Gold).
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
