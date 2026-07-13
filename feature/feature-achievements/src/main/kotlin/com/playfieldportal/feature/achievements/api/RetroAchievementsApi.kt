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
    @SerialName("Title") val title: String = "",
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

    // Per-console index of the RA game list: hash -> gameId and normalized-title -> gameId.
    private class ConsoleIndex(
        val byHash: Map<String, String>,
        val byTitle: Map<String, String>,
    ) {
        companion object { val EMPTY = ConsoleIndex(emptyMap(), emptyMap()) }
    }

    private val indexMutex = Mutex()
    private val indexCache = mutableMapOf<Int, ConsoleIndex>()

    /** The RA game id whose hash list contains [hash] on [consoleId], or null. Caches per console. */
    suspend fun gameIdForHash(consoleId: Int, hash: String): String? =
        index(consoleId).byHash[hash.lowercase()]

    /**
     * The RA game id whose title matches [title] on [consoleId], or null. A fallback for ROMs whose
     * exact dump isn't a registered RA hash (e.g. a different regional dump): RA achievements are
     * per-game, so a normalized-title match links the same coin set. Uses the user-visible display
     * title, which the user can edit to correct a mismatch.
     */
    suspend fun gameIdForTitle(consoleId: Int, title: String): String? {
        val key = normalizeTitle(title)
        return if (key.isEmpty()) null else index(consoleId).byTitle[key]
    }

    private suspend fun index(consoleId: Int): ConsoleIndex = indexMutex.withLock {
        indexCache[consoleId] ?: loadIndex(consoleId).also { indexCache[consoleId] = it }
    }

    // API_GetGameList with h=1 returns each game's known hashes and title; f=1 limits to games with
    // achievements. Built into one hash-> and title-> gameId index per console. On a title
    // collision the first game wins, matching RA's own list order.
    private suspend fun loadIndex(consoleId: Int): ConsoleIndex {
        val user = credentials.raUsername()?.takeIf { it.isNotBlank() } ?: return ConsoleIndex.EMPTY
        val key = credentials.raApiKey()?.takeIf { it.isNotBlank() } ?: return ConsoleIndex.EMPTY
        rate.await()
        return runCatching {
            val games = client.get("$BASE/API_GetGameList.php") {
                parameter("z", user)
                parameter("y", key)
                parameter("i", consoleId)
                parameter("h", 1)
                parameter("f", 1)
            }.body<List<RaGameListEntry>>()
            val byHash = games.flatMap { g -> g.hashes.map { it.lowercase() to g.id.toString() } }.toMap()
            val byTitle = LinkedHashMap<String, String>()
            for (g in games) {
                val t = normalizeTitle(g.title)
                if (t.isNotEmpty()) byTitle.putIfAbsent(t, g.id.toString())
            }
            ConsoleIndex(byHash, byTitle)
        }.getOrElse { ConsoleIndex.EMPTY }
    }

    // Case- and punctuation-insensitive title key: keep only alphanumerics. This folds "A2: Rift"
    // and "A2 - Rift" together, matching the same relaxed scheme the Steam resolver uses.
    private fun normalizeTitle(raw: String): String =
        raw.lowercase().filter(Char::isLetterOrDigit)

    // RA timestamps are "yyyy-MM-dd HH:mm:ss" in UTC.
    private fun parseRaDate(raw: String): Long? = runCatching {
        LocalDateTime.parse(raw.trim(), RA_DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
}
