package com.playfieldportal.feature.achievements.preview

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSources
import com.playfieldportal.feature.achievements.provider.retro.RaHashResolver
import com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A provider search result: enough to tell the user which game (and platform) it is. */
data class PreviewCandidate(
    val provider: AchievementProvider,
    val providerGameId: String,
    val title: String,
    val platformLabel: String,
)

/** Outcome of a RetroAchievements title search. */
sealed interface PreviewSearch {
    data class Results(val candidates: List<PreviewCandidate>) : PreviewSearch

    /** The console catalog couldn't load (offline, no credentials) — not "no results". */
    data object Unavailable : PreviewSearch
}

/**
 * The explicit "Search online" preview (plan Task 8): look up a game that is NOT on this device
 * and view its achievements. Read-only by construction — this class has no DAO, ledger, writer or
 * coordinator to write through — so a preview can never become a tracked game, touch the wallet
 * or feeds, or enter a sync queue. An opened preview is held in memory only until [close].
 *
 * Steam search reuses the storefront search; RA search filters the per-console catalog the hash
 * matcher already caches, so typing never downloads a console list per keystroke.
 */
@Singleton
class AchievementPreviewRepository @Inject constructor(
    private val sources: RemoteAchievementSources,
    private val steamResolver: SteamAppListResolver,
    private val raCatalog: RaHashResolver,
) {
    private val mutex = Mutex()
    private val opened = mutableMapOf<Pair<AchievementProvider, String>, ProviderSyncResult.Success>()

    /** Steam apps matching [query] (at least [MIN_QUERY_LENGTH] characters), capped at [MAX_RESULTS]. */
    suspend fun searchSteam(query: String): List<PreviewCandidate> {
        val needle = query.trim()
        if (needle.length < MIN_QUERY_LENGTH) return emptyList()
        return steamResolver.search(needle, MAX_RESULTS).map {
            PreviewCandidate(AchievementProvider.STEAM, it.appId, it.name, "Steam")
        }
    }

    /** RetroAchievements games on [consoleId] whose title contains [query]. */
    suspend fun searchRetroAchievements(consoleId: Int, query: String): PreviewSearch {
        val needle = query.trim()
        if (needle.length < MIN_QUERY_LENGTH) return PreviewSearch.Results(emptyList())
        val catalog = raCatalog.catalog(consoleId) ?: return PreviewSearch.Unavailable
        return PreviewSearch.Results(
            catalog.asSequence()
                .filter { it.title.contains(needle, ignoreCase = true) }
                .sortedWith(compareBy({ !it.title.startsWith(needle, ignoreCase = true) }, { it.title.lowercase() }))
                .take(MAX_RESULTS)
                .map { PreviewCandidate(AchievementProvider.RETRO_ACHIEVEMENTS, it.gameId, it.title, it.consoleName) }
                .toList(),
        )
    }

    /**
     * The candidate's achievements and, where credentials permit, the user's earned state — a
     * read-only provider fetch. A successful read is reused until [close]; a failure is not kept.
     */
    suspend fun open(candidate: PreviewCandidate): ProviderSyncResult {
        val key = candidate.provider to candidate.providerGameId
        mutex.withLock { opened[key] }?.let { return it }
        val result = sources.forProvider(candidate.provider).fetch(candidate.providerGameId)
        if (result is ProviderSyncResult.Success) mutex.withLock { opened[key] = result }
        return result
    }

    /** Discards the preview; nothing about it remains. */
    suspend fun close(candidate: PreviewCandidate) {
        mutex.withLock { opened.remove(candidate.provider to candidate.providerGameId) }
    }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val MAX_RESULTS = 20
    }
}
