package com.playfieldportal.feature.achievements.provider.retro

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a ROM/disc content hash to its RetroAchievements game id. RA identifies games solely by
 * content hash, so this is the only automatic RA match.
 *
 * The per-console hash list is large and changes rarely — api-kotlin itself flags `GetGameList` as
 * cache-worthy — so it is fetched once per console and cached for the process. This restores the
 * caching the previous client did internally, now that the raw [RaRemoteDataSource.hashMap] fetch
 * is an uncached primitive.
 */
@Singleton
class RaHashResolver @Inject constructor(
    private val remote: RaRemoteDataSource,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<Int, Map<String, String>>()

    /** The RA game id whose hash list contains [hash] on [consoleId], or null. */
    suspend fun gameIdForHash(consoleId: Int, hash: String): String? {
        val map = mutex.withLock {
            cache[consoleId] ?: remote.hashMap(consoleId).also { cache[consoleId] = it }
        }
        return map[hash.lowercase()]
    }
}
