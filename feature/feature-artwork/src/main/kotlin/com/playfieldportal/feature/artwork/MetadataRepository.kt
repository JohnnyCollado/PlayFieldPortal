package com.playfieldportal.feature.artwork

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.card.CardArtworkProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class MetadataFetchResult(
    val success: Boolean,
    val source: String,   // "screenscraper" | "thegamesdb" | "steamgriddb" | "none"
    val message: String,
)

// Fetches metadata + artwork in priority order:
//   1. ScreenScraper — hash-based exact ROM matching, returns full metadata + art
//   2. TheGamesDB    — title-based fallback, returns metadata + art
//   3. SteamGridDB   — artwork-only last resort (no metadata)
//
// Only non-null fields are written to the DB (COALESCE in SQL keeps existing values).
@Singleton
class MetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val screenScraper: ScreenScraperApi,
    private val theGamesDb: TheGamesDbApi,
    private val steamGridDb: SteamGridDbApi,
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val imageLoader: ImageLoader,
    private val cardProcessor: CardArtworkProcessor,
) {
    suspend fun fetchForGame(
        gameId: Long,
        title: String,
        platformId: String,
        romPath: String?,
    ): MetadataFetchResult {
        val romFile = romPath?.let { File(it) }?.takeIf { it.exists() }

        // ── 1. ScreenScraper (hash-based — most accurate) ─────────────────
        val ssInfo = screenScraper.fetchGameInfo(
            platformId = platformId,
            romFile    = romFile,
            title      = title,
        )
        if (ssInfo != null) {
            val cardPath = ssInfo.artworkUrl?.let { cardProcessor.processBoxArt(gameId, platformId, it) }
            val heroPath = ssInfo.heroUrl?.let { cardProcessor.downloadRaw(gameId, it, "hero.jpg") }
            val logoPath = ssInfo.logoUrl?.let { cardProcessor.downloadRaw(gameId, it, "logo.png", asPng = true) }
            gameDao.updateMetadata(
                id          = gameId,
                description = ssInfo.description,
                developer   = ssInfo.developer,
                publisher   = ssInfo.publisher,
                releaseYear = ssInfo.releaseYear,
                genre       = ssInfo.genre,
                artworkUri  = cardPath ?: ssInfo.artworkUrl,
                heroUri     = heroPath ?: ssInfo.heroUrl,
                logoUri     = logoPath ?: ssInfo.logoUrl,
            )
            prewarm(cardPath, heroPath, logoPath)
            Timber.i("Metadata from ScreenScraper: '$title'")
            return MetadataFetchResult(true, "screenscraper", "Found on ScreenScraper")
        }

        // ── 2. TheGamesDB (title-based fallback) ──────────────────────────
        val tgdbInfo = theGamesDb.fetchGameInfo(platformId = platformId, title = title)
        if (tgdbInfo != null) {
            val cardPath = tgdbInfo.artworkUrl?.let { cardProcessor.processBoxArt(gameId, platformId, it) }
            val heroPath = tgdbInfo.heroUrl?.let { cardProcessor.downloadRaw(gameId, it, "hero.jpg") }
            val logoPath = tgdbInfo.logoUrl?.let { cardProcessor.downloadRaw(gameId, it, "logo.png", asPng = true) }
            gameDao.updateMetadata(
                id          = gameId,
                description = tgdbInfo.description,
                releaseYear = tgdbInfo.releaseYear,
                artworkUri  = cardPath ?: tgdbInfo.artworkUrl,
                heroUri     = heroPath ?: tgdbInfo.heroUrl,
                logoUri     = logoPath ?: tgdbInfo.logoUrl,
            )
            prewarm(cardPath, heroPath, logoPath)
            Timber.i("Metadata from TheGamesDB: '$title'")
            return MetadataFetchResult(true, "thegamesdb", "Found on TheGamesDB")
        }

        // ── 3. SteamGridDB (artwork only, no metadata) ────────────────────
        val sgdbKey = sgdbKeyProvider.getKey()
        if (!sgdbKey.isNullOrBlank()) {
            val match = steamGridDb.searchGame(title).getOrNull()?.firstOrNull()
            if (match != null) {
                val gridUrl = steamGridDb.getBestGridUrl(match.id)
                val heroUrl = steamGridDb.getBestHeroUrl(match.id)
                val logoUrl = steamGridDb.getBestLogoUrl(match.id)
                if (gridUrl != null || heroUrl != null) {
                    val cardPath = gridUrl?.let { cardProcessor.processBoxArt(gameId, platformId, it) }
                    val heroPath = heroUrl?.let { cardProcessor.downloadRaw(gameId, it, "hero.jpg") }
                    val logoPath = logoUrl?.let { cardProcessor.downloadRaw(gameId, it, "logo.png", asPng = true) }
                    gameDao.updateMetadata(
                        id        = gameId,
                        artworkUri = cardPath ?: gridUrl,
                        heroUri   = heroPath ?: heroUrl,
                        logoUri   = logoPath ?: logoUrl,
                    )
                    prewarm(cardPath, heroPath, logoPath)
                    Timber.i("Artwork-only from SteamGridDB: '$title'")
                    return MetadataFetchResult(true, "steamgriddb", "Artwork from SteamGridDB")
                }
            }
        }

        Timber.i("No metadata found for '$title'")
        return MetadataFetchResult(false, "none", "Not found on any source")
    }

    // Bulk fetch for games missing artwork. Delays 1.1s between requests to
    // respect ScreenScraper's ~1 req/sec rate limit on free accounts.
    suspend fun fetchMissingMetadata(onProgress: (current: Int, total: Int) -> Unit) {
        val games = gameDao.getGamesWithoutArtwork()
        games.forEachIndexed { index, game ->
            onProgress(index + 1, games.size)
            fetchForGame(
                gameId     = game.id,
                title      = game.title,
                platformId = game.platformId,
                romPath    = game.romPath,
            )
            if (index < games.size - 1) delay(1_100)
        }
    }

    private fun prewarm(vararg urls: String?) {
        urls.filterNotNull().forEach { url ->
            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}
