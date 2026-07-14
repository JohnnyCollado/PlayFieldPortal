package com.playfieldportal.feature.achievements.provider.retro

import com.haroldadmin.cnradapter.NetworkResponse
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RateLimiter
import javax.inject.Inject
import javax.inject.Singleton

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
        val resp = runCatching { session.api.getGameInfoAndUserProgress(session.username, id) }
            .getOrElse { return ProviderSyncResult.Failed("network error") }

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
     * Every registered hash on [consoleId] mapped to its RA game id (lowercased). RA identifies
     * games solely by content hash, so this is the lookup table the hash matcher joins against.
     * `f=1` limits to games with achievements; `h=1` includes the hashes. Empty on any failure.
     *
     * Uncached by design — the per-console caching lives in [RaHashResolver].
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
}
