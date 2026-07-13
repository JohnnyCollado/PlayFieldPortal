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

/**
 * Resolves a game title to a Steam appid via the public app list (no key required). The full list
 * is large, so it is fetched once and cached for the process. Matching is by normalized title
 * (case- and punctuation-insensitive) — a best-effort automatic link the user can override.
 */
@Singleton
class SteamAppListResolver @Inject constructor(
    @AchievementsHttpClient private val client: HttpClient,
) {
    @Volatile private var index: Map<String, String>? = null
    private val mutex = Mutex()

    /** The appid for [title], or null if the list has no normalized-title match. */
    suspend fun resolveAppId(title: String): String? {
        val idx = index ?: mutex.withLock { index ?: loadIndex().also { index = it } }
        return idx[normalize(title)]
    }

    private suspend fun loadIndex(): Map<String, String> = runCatching {
        client.get("https://api.steampowered.com/ISteamApps/GetAppList/v2/")
            .body<AppListResponse>()
            .applist?.apps.orEmpty()
            .filter { it.name.isNotBlank() }
            .associate { normalize(it.name) to it.appid.toString() }
    }.getOrElse { emptyMap() }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
