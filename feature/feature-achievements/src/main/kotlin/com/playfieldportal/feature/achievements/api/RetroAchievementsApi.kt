package com.playfieldportal.feature.achievements.api

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// ── Response models ───────────────────────────────────────────────────────────
// RA returns some counts as JSON strings on older responses and numbers on newer ones, so the
// count fields are JsonElement and read via [asDouble] to tolerate both.

@Serializable
private data class RaGameProgress(
    @SerialName("ID") val id: Long = 0,
    @SerialName("NumDistinctPlayersCasual") val numDistinctPlayers: JsonElement? = null,
    @SerialName("Achievements") val achievements: Map<String, RaAchievement> = emptyMap(),
)

@Serializable
private data class RaAchievement(
    @SerialName("ID") val id: Long = 0,
    @SerialName("Title") val title: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("NumAwarded") val numAwarded: JsonElement? = null,
    @SerialName("DateEarned") val dateEarned: String? = null,
    @SerialName("DateEarnedHardcore") val dateEarnedHardcore: String? = null,
    @SerialName("BadgeName") val badgeName: String? = null,
)

@Serializable
private data class RaGameListEntry(
    @SerialName("ID") val id: Long = 0,
    @SerialName("Hashes") val hashes: List<String> = emptyList(),
)

private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

// ── API client ────────────────────────────────────────────────────────────────

private const val BASE = "https://retroachievements.org/API"
private const val BADGE_BASE = "https://media.retroachievements.org/Badge"
private val RA_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Read-only RetroAchievements client. Authenticates with the user's own username + personal Web
 * API key (query params z / y), never a password. One call returns every coin with its award
 * counts and this user's earned state; the tier comes from the derived global unlock rarity.
 */
@Singleton
class RetroAchievementsApi @Inject constructor(
    @AchievementsHttpClient private val client: HttpClient,
    private val credentials: AchievementCredentialsProvider,
) {
    private val rate = RateLimiter(1_100)

    /** [gameId] is the RA game id. */
    suspend fun fetch(gameId: String): ProviderSyncResult {
        val user = credentials.raUsername()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials
        val key = credentials.raApiKey()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials

        rate.await()
        val response = runCatching {
            client.get("$BASE/API_GetGameInfoAndUserProgress.php") {
                parameter("z", user)
                parameter("y", key)
                parameter("u", user)
                parameter("g", gameId)
            }
        }.getOrElse { return ProviderSyncResult.Failed("network error") }

        if (response.status.value == 401 || response.status.value == 403) {
            return ProviderSyncResult.MissingCredentials
        }

        val game = runCatching { response.body<RaGameProgress>() }
            .getOrElse { return ProviderSyncResult.Failed("parse error") }

        if (game.achievements.isEmpty()) return ProviderSyncResult.NotFound

        val players = game.numDistinctPlayers.asDouble() ?: 0.0
        val coins = game.achievements.values.map { a ->
            val awarded = a.numAwarded.asDouble() ?: 0.0
            val percent = if (players > 0) (awarded / players * 100.0) else 0.0
            // Prefer the hardcore unlock timestamp; fall back to softcore.
            val earnedAt = a.dateEarnedHardcore?.let(::parseRaDate) ?: a.dateEarned?.let(::parseRaDate)
            SyncedCoin(
                providerAchievementId = a.id.toString(),
                title = a.title.orEmpty(),
                description = a.description.orEmpty(),
                tier = ShibaTier.forRarity(percent),
                globalRarity = percent,
                iconUrl = a.badgeName?.let { "$BADGE_BASE/$it.png" },
                isHidden = false, // RA has no hidden-until-earned coins
                isEarned = earnedAt != null,
                earnedAt = earnedAt,
            )
        }
        return ProviderSyncResult.Success(gameId, coins)
    }

    private val hashListMutex = Mutex()
    private val hashCache = mutableMapOf<Int, Map<String, String>>()

    /** The RA game id whose hash list contains [hash] on [consoleId], or null. Caches per console. */
    suspend fun gameIdForHash(consoleId: Int, hash: String): String? {
        val map = hashListMutex.withLock {
            hashCache[consoleId] ?: loadHashMap(consoleId).also { hashCache[consoleId] = it }
        }
        return map[hash.lowercase()]
    }

    // API_GetGameList with h=1 returns each game's known hashes; f=1 limits to games with
    // achievements. Built into one hash -> gameId map per console.
    private suspend fun loadHashMap(consoleId: Int): Map<String, String> {
        val user = credentials.raUsername()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val key = credentials.raApiKey()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        rate.await()
        return runCatching {
            client.get("$BASE/API_GetGameList.php") {
                parameter("z", user)
                parameter("y", key)
                parameter("i", consoleId)
                parameter("h", 1)
                parameter("f", 1)
            }.body<List<RaGameListEntry>>()
                .flatMap { g -> g.hashes.map { it.lowercase() to g.id.toString() } }
                .toMap()
        }.getOrElse { emptyMap() }
    }

    // RA timestamps are "yyyy-MM-dd HH:mm:ss" in UTC.
    private fun parseRaDate(raw: String): Long? = runCatching {
        LocalDateTime.parse(raw.trim(), RA_DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
}
