package com.playfieldportal.feature.artwork.api

import android.content.Context
import coil.imageLoader
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.feature.artwork.MetadataRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class ArtworkFetchResult(
    val gameId: Long,
    val title: String,
    val success: Boolean,
    val skipped: Boolean = false,
    val errorMessage: String? = null,
)

@Singleton
class ArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val metadataRepository: MetadataRepository,
) {
    // Fetch artwork + metadata for all games that don't have any artwork yet.
    suspend fun fetchMissingArtwork(
        onProgress: (current: Int, total: Int, title: String) -> Unit,
    ): List<ArtworkFetchResult> = withContext(Dispatchers.IO) {
        val games = gameDao.getGamesWithoutArtwork()
        Timber.i("Metadata fetch started — ${games.size} games need artwork")
        val results = mutableListOf<ArtworkFetchResult>()

        metadataRepository.fetchMissingMetadata { current, total ->
            val title = games.getOrNull(current - 1)?.title ?: ""
            onProgress(current, total, title)
        }

        // Build results list from what we now have in the DB
        games.forEach { game ->
            val updated = gameDao.getById(game.id)
            val success = updated?.artworkUri != null
            results += ArtworkFetchResult(game.id, game.title, success,
                errorMessage = if (!success) "No artwork found" else null)
        }

        Timber.i("Metadata fetch complete — ${results.count { it.success }} succeeded")
        results
    }

    // Single-game entry point — used by GameDetailViewModel.
    // Looks up platformId + romPath from DB so the call-site signature stays stable.
    suspend fun fetchArtworkForGame(gameId: Long, title: String): ArtworkFetchResult {
        val game = gameDao.getById(gameId)
            ?: return ArtworkFetchResult(gameId, title, false, errorMessage = "Game not found")

        val result = metadataRepository.fetchForGame(
            gameId     = gameId,
            title      = title,
            platformId = game.platformId,
            romPath    = game.romPath,
        )
        return ArtworkFetchResult(gameId, title, result.success, errorMessage = result.message.takeIf { !result.success })
    }

    fun clearCache() {
        context.imageLoader.diskCache?.clear()
        context.imageLoader.memoryCache?.clear()
        Timber.i("Artwork cache cleared")
    }
}
