package com.playfieldportal.launcher.debug

import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaLevel
import com.playfieldportal.core.domain.achievement.ShibaRank
import com.playfieldportal.core.domain.achievement.ShibaTier
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds fake account achievement data so the player status view can be exercised at any rank.
 * Debug-only: writes a fixed, deterministic block of sets + coins (always the same provider ids),
 * so re-seeding a different rank cleanly replaces the previous block and never touches real synced
 * data. Each seed targets a mid-band level for the chosen rank (so the rank reads unambiguously),
 * grants a few mastered sets (the Platinum pill), and attaches a spread of recent unlocks plus one
 * ultra-rare coin for the "rarest" card.
 */
@Singleton
class ShibaStandingSeeder @Inject constructor(
    private val setDao: AccountAchievementSetDao,
    private val coinDao: AccountAchievementDao,
) {
    private val provider = AchievementProvider.STEAM.name

    // Deterministic fake set ids, always cleared before a re-seed so blocks never accumulate.
    private val bigSetId = "dbg_std_career"
    private fun masteredId(i: Int) = "dbg_std_master_$i"
    private val allSetIds get() = listOf(bigSetId) + (0 until MAX_MASTERED).map(::masteredId)

    /** A mid-band total level for each rank, so the resulting rank is unambiguous. */
    private fun targetLevelFor(rank: ShibaRank): Int = when (rank) {
        ShibaRank.PUP -> 12
        ShibaRank.RUFFIAN -> 35
        ShibaRank.LONE_RON_INU -> 60
        ShibaRank.INU_MASTER -> 85
        ShibaRank.LEGENDARY_HACHIKO -> 320 // three completed Bone cycles
    }

    private fun masteredCountFor(rank: ShibaRank): Int = when (rank) {
        ShibaRank.PUP -> 0
        ShibaRank.RUFFIAN -> 1
        ShibaRank.LONE_RON_INU -> 2
        ShibaRank.INU_MASTER -> 3
        ShibaRank.LEGENDARY_HACHIKO -> 6
    }

    /** Wipes any previously seeded standing block. */
    suspend fun clear() {
        allSetIds.forEach { id ->
            coinDao.deleteForSet(provider, id)
            setDao.deleteSet(provider, id)
        }
    }

    suspend fun seed(rank: ShibaRank) {
        clear()

        val targetXp = ShibaLevel.coinsForLevel(targetLevelFor(rank))
        val masteredCount = masteredCountFor(rank)

        // Fully-mastered small sets: each grants the Platinum crown (+300 XP) and a little tier XP.
        val masteredXp = masteredCount * MASTERED_SET_XP
        (0 until masteredCount).forEach { i ->
            setDao.upsert(
                AccountAchievementSetEntity(
                    provider = provider,
                    providerGameId = masteredId(i),
                    title = "Perfect Run ${i + 1}",
                    bronzeTotal = MASTERED_BRONZE, silverTotal = MASTERED_SILVER, goldTotal = MASTERED_GOLD,
                    bronzeEarned = MASTERED_BRONZE, silverEarned = MASTERED_SILVER, goldEarned = MASTERED_GOLD,
                    mastered = true,
                    lastSyncedAt = System.currentTimeMillis(),
                ),
            )
        }

        // The "career" set carries the remaining XP in a Bronze>Silver>Gold pyramid (ratio 13:4:1).
        val remaining = (targetXp - masteredXp).coerceAtLeast(0)
        val units = (remaining / UNIT_XP).coerceAtLeast(1)
        val bronze = 13 * units
        val silver = 4 * units
        val gold = 1 * units
        setDao.upsert(
            AccountAchievementSetEntity(
                provider = provider,
                providerGameId = bigSetId,
                title = "Career Progress",
                bronzeTotal = (bronze * 1.3).toInt(), silverTotal = (silver * 1.3).toInt(), goldTotal = (gold * 1.3).toInt(),
                bronzeEarned = bronze, silverEarned = silver, goldEarned = gold,
                mastered = false,
                lastSyncedAt = System.currentTimeMillis(),
            ),
        )

        coinDao.upsertAll(recentCoins())

        Timber.i(
            "Debug Shiba seed: rank=${rank.label} targetLevel=${targetLevelFor(rank)} " +
                "xp=$targetXp mastered=$masteredCount",
        )
    }

    // Individual coins attached to the career set: six recent unlocks plus one ultra-rare coin that
    // becomes the "rarest" card. Timestamps are spread so the feed shows a realistic age ladder.
    private fun recentCoins(): List<AccountAchievementEntity> {
        val now = System.currentTimeMillis()
        val min = 60_000L
        val hour = 3_600_000L
        val day = 86_400_000L
        data class C(val id: String, val title: String, val tier: ShibaTier, val rarity: Double, val ago: Long)
        val coins = listOf(
            C("c_thief", "Master Thief", ShibaTier.GOLD, 3.2, 5 * min),
            C("c_dragon", "Dragon Slayer", ShibaTier.SILVER, 14.0, 26 * hour),
            C("c_blood", "First Blood", ShibaTier.BRONZE, 55.0, 27 * hour),
            C("c_explore", "Explorer", ShibaTier.BRONZE, 40.0, 2 * day),
            C("c_speed", "Speedrunner", ShibaTier.GOLD, 6.5, 4 * day),
            C("c_complete", "Completionist", ShibaTier.SILVER, 20.0, 6 * day),
            C("c_abyss", "Legend of the Abyss", ShibaTier.GOLD, 0.38, 10 * day),
        )
        return coins.map { c ->
            AccountAchievementEntity(
                provider = provider,
                providerGameId = bigSetId,
                providerAchievementId = c.id,
                title = c.title,
                description = "Debug seeded achievement.",
                tier = c.tier.name,
                globalRarity = c.rarity,
                iconUrl = null,
                isHidden = false,
                isEarned = true,
                earnedAt = now - c.ago,
            )
        }
    }

    private companion object {
        const val MAX_MASTERED = 6
        const val MASTERED_BRONZE = 10
        const val MASTERED_SILVER = 4
        const val MASTERED_GOLD = 2
        // Weighted XP of one mastered set, including the +300 Platinum crown.
        const val MASTERED_SET_XP =
            MASTERED_BRONZE * 15 + MASTERED_SILVER * 30 + MASTERED_GOLD * 90 + 300
        // Weighted XP of one 13:4:1 pyramid unit (13 bronze, 4 silver, 1 gold).
        const val UNIT_XP = 13 * 15 + 4 * 30 + 1 * 90
    }
}
