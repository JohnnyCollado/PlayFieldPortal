package com.playfieldportal.feature.achievements.provider.retro

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import org.retroachivements.api.data.pojo.game.GetGameInfoAndUserProgress
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val BADGE_BASE = "https://media.retroachievements.org/Badge"
private val RA_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Maps an api-kotlin `GetGameInfoAndUserProgress` response to the domain [ProviderSyncResult]. Kept
 * as a standalone unit (rather than inline in [RaRemoteDataSource]) so the tier / rarity / earned
 * mapping can be unit-tested against POJOs without the Retrofit / NetworkResponse plumbing.
 */
internal object RaCoinMapper {

    fun map(game: GetGameInfoAndUserProgress.Response, providerGameId: String): ProviderSyncResult {
        if (game.achievements.isEmpty()) return ProviderSyncResult.NotFound

        // Softcore player count as the rarity denominator, matching the prior client's behavior.
        val players = game.numDistinctPlayersCasual.toDouble()
        val coins = game.achievements.values.map { a ->
            val awarded = a.numAwarded.toDouble()
            val percent = if (players > 0) awarded / players * 100.0 else 0.0
            // Prefer the hardcore unlock timestamp; fall back to softcore.
            val earnedAt = a.dateEarnedHardcore?.let(::parseRaDate) ?: a.dateEarned?.let(::parseRaDate)
            SyncedCoin(
                providerAchievementId = a.id,
                title = a.title,
                description = a.description,
                // RA weights achievements by difficulty via points, so the tier comes from points;
                // the rarity percent is still stored for display on the coins screen.
                tier = ShibaTier.forRaPoints(a.points.toInt()),
                globalRarity = percent,
                iconUrl = a.badgeName.takeIf { it.isNotBlank() }?.let { "$BADGE_BASE/$it.png" },
                isHidden = false, // RA has no hidden-until-earned coins
                isEarned = earnedAt != null,
                // Only a hardcore unlock counts toward the Platinum crown.
                earnedHardcore = a.dateEarnedHardcore != null,
                earnedAt = earnedAt,
            )
        }
        return ProviderSyncResult.Success(providerGameId, coins)
    }

    // RA timestamps are "yyyy-MM-dd HH:mm:ss" in UTC.
    private fun parseRaDate(raw: String): Long? = runCatching {
        LocalDateTime.parse(raw.trim(), RA_DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
}
