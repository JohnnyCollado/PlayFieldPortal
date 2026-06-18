package com.playfieldportal.feature.artwork.api

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import com.playfieldportal.core.data.database.dao.GameDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class ArtworkFetchResult(
    val gameId: Long,
    val title: String,
    val success: Boolean,
    val skipped: Boolean = false,   // already had artwork
    val errorMessage: String? = null,
)

@Singleton
class ArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SteamGridDbApi,
    private val gameDao: GameDao,
) {
    // Fetch artwork for all games that don't have any yet.
    // Reports progress via callback — designed to run as a background task.
    // Respects rate limits with a small delay between requests.
    suspend fun fetchMissingArtwork(
        onProgress: (current: Int, total: Int, title: String) -> Unit,
    ): List<ArtworkFetchResult> = withContext(Dispatchers.IO) {
        val games = gameDao.getGamesWithoutArtwork()
        val results = mutableListOf<ArtworkFetchResult>()

        Timber.i("Artwork fetch started — ${games.size} games need artwork")

        games.forEachIndexed { index, game ->
            onProgress(index + 1, games.size, game.title)

            val result = fetchForGame(game.id, game.title)
            results.add(result)

            if (result.success) {
                Timber.d("Artwork fetched: ${game.title}")
            } else if (!result.skipped) {
                Timber.w("Artwork fetch failed: ${game.title} — ${result.errorMessage}")
            }

            // Respect SteamGridDB rate limits — 1 req/sec on free tier
            if (index < games.size - 1) delay(1_100)
        }

        Timber.i("Artwork fetch complete — ${results.count { it.success }} succeeded, " +
                 "${results.count { !it.success && !it.skipped }} failed")
        results
    }

    // Public single-game entry point — used by GameDetailViewModel
    suspend fun fetchArtworkForGame(gameId: Long, title: String): ArtworkFetchResult =
        fetchForGame(gameId, title)

    private suspend fun fetchForGame(gameId: Long, title: String): ArtworkFetchResult {
        return try {
            // 1 — Search for game
            val searchResults = api.searchGame(title).getOrElse {
                return ArtworkFetchResult(gameId, title, false, errorMessage = it.message)
            }
            val match = searchResults.firstOrNull()
                ?: return ArtworkFetchResult(gameId, title, false, errorMessage = "No match found")

            // 2 — Fetch grid art (primary), hero, and logo in parallel-ish
            val gridUrl  = api.getBestGridUrl(match.id)
            val heroUrl  = api.getBestHeroUrl(match.id)
            val logoUrl  = api.getBestLogoUrl(match.id)

            // 3 — Pre-warm Coil cache so art appears instantly on first view
            gridUrl?.let { prewarmCache(it) }

            // 4 — Persist URLs to DB
            if (gridUrl != null) {
                gameDao.updateArtwork(gameId, gridUrl)
                gameDao.updateHeroAndLogo(gameId, heroUrl, logoUrl)
            }

            ArtworkFetchResult(
                gameId  = gameId,
                title   = title,
                success = gridUrl != null,
                errorMessage = if (gridUrl == null) "No grid art available" else null,
            )
        } catch (e: Exception) {
            ArtworkFetchResult(gameId, title, false, errorMessage = e.message)
        }
    }

    fun clearCache() {
        context.imageLoader.diskCache?.clear()
        context.imageLoader.memoryCache?.clear()
        Timber.i("Artwork cache cleared")
    }

    private fun prewarmCache(url: String) {
        val request = ImageRequest.Builder(context).data(url).build()
        context.imageLoader.enqueue(request)
    }
}
