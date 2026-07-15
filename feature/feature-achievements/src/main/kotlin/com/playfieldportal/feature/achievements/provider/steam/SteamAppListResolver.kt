package com.playfieldportal.feature.achievements.provider.steam

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** A Steam app candidate for the manual "Find on Steam" picker. */
data class SteamCandidate(val appId: String, val name: String)

/**
 * Resolves a game title to a Steam appid via Steam's storefront search (a small, per-query request —
 * no key, no 20 MB full-app-list download). [resolveAppId] auto-links only on an exact normalized
 * name match (so a fuzzy result never mislinks); [search] returns Steam's ranked matches for the
 * manual picker.
 */
@Singleton
class SteamAppListResolver @Inject constructor(
    private val storeApi: SteamStoreApi,
) {
    /** The appid whose Steam name matches [title] exactly (after normalizing), or null. */
    suspend fun resolveAppId(title: String): String? {
        val key = normalize(title)
        if (key.isEmpty()) return null
        return storeSearch(title)
            .firstOrNull { it.type == "app" && normalize(it.name) == key }
            ?.id?.toString()
    }

    /** Up to [limit] Steam apps matching [query], in Steam's own relevance order. */
    suspend fun search(query: String, limit: Int = 20): List<SteamCandidate> {
        if (query.isBlank()) return emptyList()
        return storeSearch(query)
            .filter { it.type == "app" }
            .take(limit)
            .map { SteamCandidate(it.id.toString(), it.name) }
    }

    private suspend fun storeSearch(term: String): List<StoreItem> =
        runCatching { storeApi.search(term) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return emptyList()
            }
            .body()?.items.orEmpty()

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
