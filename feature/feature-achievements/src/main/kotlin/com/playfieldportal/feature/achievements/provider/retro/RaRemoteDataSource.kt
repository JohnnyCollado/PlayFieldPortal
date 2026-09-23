package com.playfieldportal.feature.achievements.provider.retro

import com.haroldadmin.cnradapter.NetworkResponse
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RateLimiter
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A game's progress summary as GetUserProgress / GetUserRecentlyPlayedGames report it — the cheap
 * change signal compared before any full-detail request. Hardcore counts are part of it because
 * mastery depends on them.
 */
data class RaProgressSnapshot(
    val numAchieved: Long,
    val numAchievedHardcore: Long,
    val scoreAchieved: Long,
    val scoreAchievedHardcore: Long,
    val numPossible: Long,
    val possibleScore: Long,
) {
    /** Stored as the identity's comparison snapshot. */
    fun encode(): String =
        "ra:v1:$numAchieved,$numAchievedHardcore,$scoreAchieved,$scoreAchievedHardcore,$numPossible,$possibleScore"
}

/** Outcome of a summary request. */
sealed interface RaSummaryResult {
    /** Summaries keyed by RA game id; ids the server left out are simply absent. */
    data class Success(val byGameId: Map<String, RaProgressSnapshot>) : RaSummaryResult
    data object MissingCredentials : RaSummaryResult

    /** The id list made the request URI too long (HTTP 414) — split the batch and retry. */
    data object UriTooLong : RaSummaryResult

    /** [offline] = no connection; [retryAfterMs] = a server retry hint, when one is known. */
    data class Failed(
        val reason: String,
        val offline: Boolean = false,
        val retryAfterMs: Long? = null,
    ) : RaSummaryResult
}

/** One game from a console's RA catalog: title for the provider search, hashes for the matcher. */
data class RaCatalogGame(
    val gameId: String,
    val title: String,
    val consoleName: String,
    val iconUrl: String?,
    val hashes: List<String>,
)

/**
 * The RetroAchievements remote data source, built on the official api-kotlin `RetroInterface`.
 * This is the only class that speaks Retrofit / Gson / NetworkResponse for RA — the entire
 * api-kotlin stack is quarantined behind it. Callers see only domain types ([ProviderSyncResult])
 * and a plain hash map. Read-only; self-rate-limited to be gentle with the API.
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
        // Rethrow cancellation (runCatching would swallow it) so a cancelled batch sync stops
        // immediately instead of reporting this game as failed and marching on.
        val resp = runCatching { session.api.getGameInfoAndUserProgress(session.username, id) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Full throwable: the cause chain names the real failure (redacted on write).
                Timber.i(e, "RA game sync threw for %s", gameId)
                return ProviderSyncResult.Failed("network error")
            }

        return when (resp) {
            is NetworkResponse.Success -> RaCoinMapper.map(resp.body, gameId)
            is NetworkResponse.ServerError -> when (resp.code) {
                401, 403 -> ProviderSyncResult.MissingCredentials
                else -> {
                    Timber.i("RA game sync failed for %s: HTTP %s", gameId, resp.code ?: "?")
                    ProviderSyncResult.Failed("RetroAchievements returned ${resp.code ?: "an error"}")
                }
            }
            is NetworkResponse.NetworkError -> {
                Timber.i("RA game sync failed for %s: %s", gameId, resp.error.toString())
                ProviderSyncResult.Failed("network error")
            }
            is NetworkResponse.UnknownError -> {
                Timber.i(resp.error, "RA game sync failed for %s", gameId)
                ProviderSyncResult.Failed("unexpected error")
            }
        }
    }

    /**
     * The account's most recently played games with their progress summaries (documented
     * GetUserRecentlyPlayedGames). One request — a fast change signal, not complete coverage.
     */
    suspend fun recentlyPlayed(count: Int = 50): RaSummaryResult {
        val session = clientFactory.session() ?: return RaSummaryResult.MissingCredentials
        rate.await()
        val resp = runCatching { session.api.getUserRecentlyPlayedGames(session.username, count, 0) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.i("RA recently-played request threw: %s", e.toString())
                return RaSummaryResult.Failed("network error", offline = true)
            }
        return when (resp) {
            is NetworkResponse.Success -> RaSummaryResult.Success(
                resp.body.associate { g ->
                    g.gameId.toString() to RaProgressSnapshot(
                        numAchieved = g.numAchieved,
                        numAchievedHardcore = g.numAchievedHardcore,
                        scoreAchieved = g.scoreAchieved,
                        scoreAchievedHardcore = g.scoreAchievedHardcore,
                        numPossible = g.numPossibleAchievements,
                        possibleScore = g.possibleScore,
                    )
                },
            )
            else -> summaryFailure(resp, "recently-played")
        }
    }

    /**
     * Progress summaries for a batch of RA game ids in one request (documented GetUserProgress,
     * ids comma-separated in the query). Callers keep batches small; a 414 comes back as
     * [RaSummaryResult.UriTooLong] so the batch can be split.
     */
    suspend fun userProgress(gameIds: List<String>): RaSummaryResult {
        if (gameIds.isEmpty()) return RaSummaryResult.Success(emptyMap())
        val session = clientFactory.session() ?: return RaSummaryResult.MissingCredentials
        rate.await()
        val resp = runCatching { session.api.getUserProgress(session.username, gameIds.joinToString(",")) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.i("RA progress request threw: %s", e.toString())
                return RaSummaryResult.Failed("network error", offline = true)
            }
        return when (resp) {
            is NetworkResponse.Success -> RaSummaryResult.Success(
                resp.body.mapValues { (_, p) ->
                    RaProgressSnapshot(
                        numAchieved = p.numAchieved,
                        numAchievedHardcore = p.numAchievedHardcore,
                        scoreAchieved = p.scoreAchieved,
                        scoreAchievedHardcore = p.scoreAchievedHardcore,
                        numPossible = p.numPossibleAchievements,
                        possibleScore = p.possibleScore,
                    )
                },
            )
            else -> summaryFailure(resp, "progress")
        }
    }

    private fun summaryFailure(resp: NetworkResponse<*, *>, what: String): RaSummaryResult = when (resp) {
        is NetworkResponse.ServerError -> when (resp.code) {
            401, 403 -> RaSummaryResult.MissingCredentials
            414 -> RaSummaryResult.UriTooLong
            else -> {
                Timber.i("RA %s request failed: HTTP %s", what, resp.code ?: "?")
                RaSummaryResult.Failed("RetroAchievements returned ${resp.code ?: "an error"}")
            }
        }
        is NetworkResponse.NetworkError -> {
            Timber.i("RA %s request failed: %s", what, resp.error.javaClass.simpleName)
            RaSummaryResult.Failed("network error", offline = true)
        }
        is NetworkResponse.UnknownError -> {
            Timber.i(resp.error, "RA %s request failed", what)
            RaSummaryResult.Failed("unexpected error")
        }
        is NetworkResponse.Success -> RaSummaryResult.Failed("unexpected response")
    }

    /**
     * Every game with achievements on [consoleId], with its title and registered hashes
     * (GetGameList `f=1&h=1`). One large request per console — cached by [RaHashResolver], which
     * serves both the hash matcher and the provider-search title picker.
     *
     * Null when the list COULD NOT be fetched (no credentials, network or server error), so
     * callers can tell "couldn't load" apart from "this console genuinely has no games" — a
     * failure returned as empty used to get cached and mask every later lookup.
     */
    suspend fun gameCatalog(consoleId: Int): List<RaCatalogGame>? {
        val session = clientFactory.session() ?: run {
            Timber.i("RA game list: no credentials — console %d skipped", consoleId)
            return null
        }
        rate.await()
        val resp = runCatching {
            session.api.getGameList(
                consoleId = consoleId.toLong(),
                shouldOnlyRetrieveGamesWithAchievements = 1,
                shouldRetrieveGameHashes = 1,
            )
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            // INFO so the failure (class + message — the message survives R8 renaming) reaches
            // the release log file.
            Timber.i("RA game list fetch threw for console %d: %s", consoleId, e.toString())
            return null
        }

        return when (resp) {
            is NetworkResponse.Success -> resp.body.map { game ->
                RaCatalogGame(
                    gameId = game.id.toString(),
                    title = game.title,
                    consoleName = game.consoleName,
                    iconUrl = game.imageIcon.takeIf { it.isNotBlank() }?.let { "$MEDIA_BASE$it" },
                    // Gson can leave a missing array null despite the non-null Kotlin type.
                    hashes = (game.hashes as List<String>?).orEmpty().map { it.lowercase() },
                )
            }
            is NetworkResponse.ServerError -> {
                Timber.i("RA game list fetch failed for console %d: HTTP %s", consoleId, resp.code ?: "?")
                null
            }
            is NetworkResponse.NetworkError -> {
                Timber.i("RA game list fetch failed for console %d: %s", consoleId, resp.error.javaClass.simpleName)
                null
            }
            is NetworkResponse.UnknownError -> {
                Timber.i(resp.error, "RA game list fetch failed for console %d", consoleId)
                null
            }
        }
    }

    /**
     * Every registered hash on [consoleId] mapped to its RA game id (lowercased), derived from
     * [gameCatalog]. Null when the list could not be fetched.
     */
    suspend fun hashMap(consoleId: Int): Map<String, String>? =
        gameCatalog(consoleId)?.let { catalog ->
            catalog.flatMap { game -> game.hashes.map { it to game.gameId } }.toMap()
        }
}

private const val MEDIA_BASE = "https://media.retroachievements.org"
