package com.playfieldportal.feature.achievements.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class AppListResponse(val applist: AppListWrap? = null)

@Serializable
private data class AppListWrap(val apps: List<SteamApp> = emptyList())

@Serializable
private data class SteamApp(val appid: Long = 0, val name: String = "")

/** A Steam app candidate for the manual "Find on Steam" picker. */
data class SteamCandidate(val appId: String, val name: String)

/**
 * Resolves a game title to a Steam appid via the public app list (no key required). The full list
 * is large, so it is fetched once and cached for the process. [resolveAppId] is the automatic
 * best-effort link (exact normalized title); [search] backs the manual picker (ranked candidates).
 */
@Singleton
class SteamAppListResolver @Inject constructor(
    @AchievementsHttpClient private val client: HttpClient,
) {
    @Volatile private var apps: List<SteamApp>? = null
    @Volatile private var index: Map<String, String>? = null
    private val mutex = Mutex()

    /** The appid for [title], or null if the list has no normalized-title match. */
    suspend fun resolveAppId(title: String): String? = ensureLoaded().second[normalize(title)]

    /**
     * Up to [limit] Steam apps whose name matches [query], best matches first: exact normalized
     * title, then names starting with the query, then names containing it (shorter names win ties).
     */
    suspend fun search(query: String, limit: Int = 20): List<SteamCandidate> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return ensureLoaded().first.asSequence()
            .mapNotNull { app ->
                val n = normalize(app.name)
                val rank = when {
                    n == q -> 0
                    n.startsWith(q) -> 1
                    n.contains(q) -> 2
                    else -> return@mapNotNull null
                }
                Triple(rank, n.length, app)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { SteamCandidate(it.third.appid.toString(), it.third.name) }
            .toList()
    }

    private suspend fun ensureLoaded(): Pair<List<SteamApp>, Map<String, String>> {
        apps?.let { a -> index?.let { i -> return a to i } }
        return mutex.withLock {
            apps?.let { a -> index?.let { i -> return a to i } }
            val loaded = loadApps()
            val idx = loaded.associate { normalize(it.name) to it.appid.toString() }
            apps = loaded
            index = idx
            loaded to idx
        }
    }

    private suspend fun loadApps(): List<SteamApp> = runCatching {
        client.get("https://api.steampowered.com/ISteamApps/GetAppList/v2/")
            .body<AppListResponse>()
            .applist?.apps.orEmpty()
            .filter { it.name.isNotBlank() }
    }.getOrElse { emptyList() }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
