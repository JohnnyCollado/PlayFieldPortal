package com.playfieldportal.feature.achievements.provider.retro

import com.haroldadmin.cnradapter.NetworkResponse
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RateLimiter
import org.retroachivements.api.data.pojo.user.GetUserCompletionProgress
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** One row of the account's RA completion-progress list. */
data class RaProgressEntry(
    val gameId: String,
    val title: String,
    val iconUrl: String?,
    val earned: Long,
    val totalAchievements: Long,
)

/** Outcome of walking the account's completion-progress list. */
sealed interface RaProgressResult {
    data class Success(val entries: List<RaProgressEntry>) : RaProgressResult
    data object MissingCredentials : RaProgressResult
    data class Failed(val reason: String) : RaProgressResult
}

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
                return ProviderSyncResult.Failed("network error")
            }

        return when (resp) {
            is NetworkResponse.Success -> RaCoinMapper.map(resp.body, gameId)
            is NetworkResponse.ServerError -> when (resp.code) {
                401, 403 -> ProviderSyncResult.MissingCredentials
                else -> ProviderSyncResult.Failed("RetroAchievements returned ${resp.code ?: "an error"}")
            }
            is NetworkResponse.NetworkError -> ProviderSyncResult.Failed("network error")
            is NetworkResponse.UnknownError -> ProviderSyncResult.Failed("unexpected error")
        }
    }

    /**
     * Walks the account's ENTIRE completion-progress list — every game the user has any RA
     * standing in — page by page (rate-limited per page). The loop advances by the number of
     * rows actually returned, never by an assumed page size, and an empty page always
     * terminates it. See docs/account-achievements-plan.md.
     */
    suspend fun userCompletionProgress(): RaProgressResult {
        val session = clientFactory.session() ?: return RaProgressResult.MissingCredentials
        val entries = mutableListOf<RaProgressEntry>()
        var offset = 0
        while (true) {
            rate.await()
            val resp = runCatching {
                session.api.getUserCompletionProgress(session.username, PROGRESS_PAGE_SIZE, offset)
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                return RaProgressResult.Failed("network error")
            }

            when (resp) {
                is NetworkResponse.Success -> {
                    val page = resp.body.results
                    page.mapTo(entries) { it.toEntry() }
                    offset += page.size
                    if (page.isEmpty() || offset >= resp.body.total) return RaProgressResult.Success(entries)
                }
                is NetworkResponse.ServerError -> return when (resp.code) {
                    401, 403 -> RaProgressResult.MissingCredentials
                    else -> RaProgressResult.Failed("RetroAchievements returned ${resp.code ?: "an error"}")
                }
                is NetworkResponse.NetworkError -> return RaProgressResult.Failed("network error")
                is NetworkResponse.UnknownError -> return RaProgressResult.Failed("unexpected error")
            }
        }
    }

    /**
     * Every registered hash on [consoleId] mapped to its RA game id (lowercased). RA identifies
     * games solely by content hash, so this is the lookup table the hash matcher joins against.
     * `f=1` limits to games with achievements; `h=1` includes the hashes.
     *
     * Returns null when the list COULD NOT be fetched (no credentials, network or server error)
     * so callers can tell "couldn't load" apart from "this console genuinely has no hashes" —
     * a failure returned as an empty map used to get cached and permanently mask every later
     * lookup as "hash isn't registered".
     *
     * Uncached by design — the per-console caching lives in [RaHashResolver].
     */
    suspend fun hashMap(consoleId: Int): Map<String, String>? {
        val session = clientFactory.session() ?: run {
            Timber.i("RA hash list: no credentials — console %d skipped", consoleId)
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
            // INFO so the failure class (e.g. SocketTimeoutException) reaches the release log file.
            Timber.i("RA hash list fetch threw for console %d: %s", consoleId, e.javaClass.simpleName)
            return null
        }

        return when (resp) {
            is NetworkResponse.Success ->
                resp.body.flatMap { game -> game.hashes.map { it.lowercase() to game.id.toString() } }.toMap()
            is NetworkResponse.ServerError -> {
                Timber.i("RA hash list fetch failed for console %d: HTTP %s", consoleId, resp.code ?: "?")
                null
            }
            is NetworkResponse.NetworkError -> {
                Timber.i("RA hash list fetch failed for console %d: %s", consoleId, resp.error.javaClass.simpleName)
                null
            }
            is NetworkResponse.UnknownError -> {
                Timber.i(resp.error, "RA hash list fetch failed for console %d", consoleId)
                null
            }
        }
    }
}

// RA's documented maximum for this endpoint; the walk stays correct if the server caps lower.
private const val PROGRESS_PAGE_SIZE = 500

private const val MEDIA_BASE = "https://media.retroachievements.org"

private fun GetUserCompletionProgress.Progress.toEntry() = RaProgressEntry(
    gameId = gameId.toString(),
    title = title,
    iconUrl = imageIcon.takeIf { it.isNotBlank() }?.let { "$MEDIA_BASE$it" },
    earned = numAwarded,
    totalAchievements = maxPossible,
)
