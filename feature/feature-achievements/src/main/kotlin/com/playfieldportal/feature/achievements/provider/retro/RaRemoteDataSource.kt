package com.playfieldportal.feature.achievements.provider.retro

import com.haroldadmin.cnradapter.NetworkResponse
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RateLimiter
import com.playfieldportal.feature.achievements.api.SyncedCoin
import org.retroachivements.api.data.pojo.game.GetGameInfoAndUserProgress
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val BADGE_BASE = "https://media.retroachievements.org/Badge"
private val RA_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * The RetroAchievements remote data source, built on the official api-kotlin [RetroInterface].
 * This is the only class that speaks Retrofit/Gson/NetworkResponse for RA — the entire api-kotlin
 * stack is quarantined behind it. Callers see only domain types ([ProviderSyncResult],
 * [SyncedCoin]) and a plain hash map. Read-only; self-rate-limited to be gentle with the API.
 *
 * See docs/shiba-coins-achievements-plan.md.
 */
@Singleton
class RaRemoteDataSource @Inject constructor(
    private val clientFactory: RaClientFactory,
) {
    private val rate = RateLimiter(1_100)

    /** Fetches [gameId]'s coins and this user's earned state in one call. [gameId] is the RA game id. */
    suspend fun fetch(gameId: String): ProviderSyncResult {
        val session = clientFactory.session() ?: return ProviderSyncResult.MissingCredentials
        val id = gameId.toLongOrNull() ?: return ProviderSyncResult.Failed("invalid RetroAchievements game id")

        rate.await()
        val resp = runCatching { session.api.getGameInfoAndUserProgress(session.username, id) }
            .getOrElse { return ProviderSyncResult.Failed("network error") }

        return when (resp) {
            is NetworkResponse.Success -> mapSuccess(resp.body, gameId)
            is NetworkResponse.ServerError -> when (resp.code) {
                401, 403 -> ProviderSyncResult.MissingCredentials
                else -> ProviderSyncResult.Failed("RetroAchievements returned ${resp.code ?: "an error"}")
            }
            is NetworkResponse.NetworkError -> ProviderSyncResult.Failed("network error")
            is NetworkResponse.UnknownError -> ProviderSyncResult.Failed("unexpected error")
        }
    }

    /**
     * Every registered hash on [consoleId] mapped to its RA game id (lowercased). RA identifies
     * games solely by content hash, so this is the lookup table the hash matcher joins against.
     * `f=1` limits to games with achievements; `h=1` includes the hashes. Empty on any failure.
     */
    suspend fun hashMap(consoleId: Int): Map<String, String> {
        val session = clientFactory.session() ?: return emptyMap()
        rate.await()
        val resp = runCatching {
            session.api.getGameList(
                consoleId = consoleId.toLong(),
                shouldOnlyRetrieveGamesWithAchievements = 1,
                shouldRetrieveGameHashes = 1,
            )
        }.getOrElse { return emptyMap() }

        return when (resp) {
            is NetworkResponse.Success ->
                resp.body.flatMap { game -> game.hashes.map { it.lowercase() to game.id.toString() } }.toMap()
            else -> emptyMap()
        }
    }

    private fun mapSuccess(game: GetGameInfoAndUserProgress.Response, providerGameId: String): ProviderSyncResult {
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
