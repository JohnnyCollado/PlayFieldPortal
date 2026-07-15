package com.playfieldportal.core.domain.achievement

/**
 * A player's overall standing on the account-wide Shiba Level, one of five named bands spread
 * evenly across the 100-level Bone cycle. The rank is cosmetic (a coin frame / title on the
 * player card); the number is [ShibaLevel]. Ranks derive from the uncapped total level, so the
 * top title — like a Bone — is earned once and never resets.
 */
enum class ShibaRank(val fromLevel: Int, val label: String) {
    PUP(1, "Pup"),
    RUFFIAN(25, "Ruffian"),
    LONE_RON_INU(50, "Lone Ron-inu"),
    INU_MASTER(75, "Inu Master"),
    LEGENDARY_HACHIKO(100, "Legendary Hachiko");
}

/**
 * The banded Shiba Level curve: cheap early levels, progressively steeper, never capped. Coins
 * bank in as they are earned (partial credit), so this maps a running total to a level.
 *
 * Pure integer math, no Android dependencies — fully unit-tested. Calibrated so a median 100%
 * game (~1,700 coins) is meaningful early progress. See docs/shiba-coins-achievements-plan.md.
 */
object ShibaLevel {

    /** Levels per prestige cycle: earning this many mints one Bone and the display rolls over. */
    const val LEVELS_PER_BONE = 100

    /** Bones (prestige marks) for a total level — one per completed 100-level cycle. */
    fun bonesForLevel(totalLevel: Int): Int = (totalLevel - 1).coerceAtLeast(0) / LEVELS_PER_BONE

    /** The odometer level shown to the player, cycling 1..100 within the current Bone cycle. */
    fun cycleLevelFor(totalLevel: Int): Int = (totalLevel - 1).coerceAtLeast(0) % LEVELS_PER_BONE + 1

    // A contiguous run of levels sharing one per-level coin cost. [toLevel] null = open-ended tail.
    private data class Band(val fromLevel: Int, val toLevel: Int?, val costPerLevel: Int)

    private val BANDS = listOf(
        Band(1, 9, 100),
        Band(10, 24, 250),
        Band(25, 49, 600),
        Band(50, 74, 1_200),
        Band(75, 99, 2_000),
        Band(100, null, 3_000),
    )

    /** Coins required to reach the start of [level]. Level 1 = 0. */
    fun coinsForLevel(level: Int): Int {
        require(level >= 1) { "level must be >= 1, was $level" }
        var total = 0
        for (band in BANDS) {
            if (level <= band.fromLevel) break
            val upper = band.toLevel ?: (level - 1)
            val lastInBand = minOf(level - 1, upper)
            if (lastInBand >= band.fromLevel) {
                total += (lastInBand - band.fromLevel + 1) * band.costPerLevel
            }
        }
        return total
    }

    /** The level a running coin total resolves to. Non-positive totals are level 1. */
    fun levelForCoins(totalCoins: Int): Int {
        if (totalCoins <= 0) return 1
        var level = 1
        var remaining = totalCoins
        for (band in BANDS) {
            // Invariant on entry: level == band.fromLevel, remaining = coins beyond reaching it.
            val affordable = remaining / band.costPerLevel
            val span = band.toLevel?.let { it - band.fromLevel + 1 }
            if (span != null && affordable >= span) {
                remaining -= span * band.costPerLevel
                level = band.toLevel!! + 1
            } else {
                return level + affordable
            }
        }
        return level
    }

    /** Where a coin total sits within its current level, for a progress bar. */
    fun progress(totalCoins: Int): LevelProgress {
        val level = levelForCoins(totalCoins)
        val base = coinsForLevel(level)
        val next = coinsForLevel(level + 1)
        return LevelProgress(
            level = level,
            coinsIntoLevel = totalCoins.coerceAtLeast(0) - base,
            coinsForNextLevel = next - base,
        )
    }

    /** The named rank for a level. */
    fun rankFor(level: Int): ShibaRank =
        ShibaRank.entries.last { it.fromLevel <= level }
}

/** Progress within a single Shiba Level: how far into it, and how much the level spans. */
data class LevelProgress(
    val level: Int,
    val coinsIntoLevel: Int,
    val coinsForNextLevel: Int,
) {
    /** 0f..1f fraction toward the next level. */
    val fraction: Float
        get() = if (coinsForNextLevel <= 0) 0f
        else (coinsIntoLevel.toFloat() / coinsForNextLevel).coerceIn(0f, 1f)
}
