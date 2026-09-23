package com.playfieldportal.feature.achievements.provider.retro

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a hash lookup, so the matcher can name the actual failure. */
sealed interface RaHashLookup {
    /** The hash maps to a registered RA game. */
    data class Found(val gameId: String) : RaHashLookup

    /** The hash list loaded but this hash isn't in it — wrong dump, romhack, or not on RA. */
    data object NotRegistered : RaHashLookup

    /** The console's hash list couldn't be fetched (no credentials / network / server error). */
    data object Unavailable : RaHashLookup
}

/**
 * Resolves a ROM/disc content hash to its RetroAchievements game id. RA identifies games solely by
 * content hash, so this is the only automatic RA match.
 *
 * The per-console hash list is large and changes rarely — api-kotlin itself flags `GetGameList` as
 * cache-worthy — so it is fetched once per console and cached for the process. Only SUCCESSFUL
 * fetches are cached: a failed fetch (missing credentials, network error) returns
 * [RaHashLookup.Unavailable] and the next lookup retries, instead of a cached empty map silently
 * reporting every hash as unregistered for the rest of the process.
 */
@Singleton
class RaHashResolver @Inject constructor(
    private val remote: RaRemoteDataSource,
) {
    private val mutex = Mutex()
    // One GetGameList per console per process: the hash matcher and the provider-search title
    // picker share it, so typing a search never re-downloads a console list.
    private val catalogs = mutableMapOf<Int, List<RaCatalogGame>>()
    private val hashMaps = mutableMapOf<Int, Map<String, String>>()

    /** Looks [hash] up in [consoleId]'s registered-hash list. */
    suspend fun lookup(consoleId: Int, hash: String): RaHashLookup {
        val map = mutex.withLock {
            hashMaps[consoleId] ?: loadLocked(consoleId)?.let { hashMaps.getValue(consoleId) }
        } ?: return RaHashLookup.Unavailable
        return map[hash.lowercase()]?.let { RaHashLookup.Found(it) } ?: RaHashLookup.NotRegistered
    }

    /** [consoleId]'s game catalog (titles for the provider search), or null when it can't load. */
    suspend fun catalog(consoleId: Int): List<RaCatalogGame>? =
        mutex.withLock { catalogs[consoleId] ?: loadLocked(consoleId) }

    // Only successful fetches are cached — see the class comment.
    private suspend fun loadLocked(consoleId: Int): List<RaCatalogGame>? {
        val catalog = remote.gameCatalog(consoleId) ?: return null
        catalogs[consoleId] = catalog
        hashMaps[consoleId] = catalog.flatMap { game -> game.hashes.map { it.lowercase() to game.gameId } }.toMap()
        return catalog
    }
}
