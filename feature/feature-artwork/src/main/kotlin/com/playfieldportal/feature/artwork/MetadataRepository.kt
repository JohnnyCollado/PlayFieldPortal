package com.playfieldportal.feature.artwork

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.IgdbGameInfo
import com.playfieldportal.feature.artwork.api.ScrapeOptions
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
    val source: String,   // "thegamesdb" | "steamgriddb" | "igdb" | "none"
    val message: String,
    val scrapedTitle: String? = null,
)

// Fetches metadata + artwork from multiple sources in priority order.
//
// Default source priority: TheGamesDB → IGDB → SteamGridDB (artwork only)
//
// Per-asset overrides:
//   • preferSteamGridDbHeroes  → SGDB is tried first for hero art
@Singleton
class MetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val theGamesDb: TheGamesDbApi,
    private val steamGridDb: SteamGridDbApi,
    private val igdbApi: IgdbApi,
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val imageLoader: ImageLoader,
    private val cardProcessor: CardArtworkProcessor,
) {
    suspend fun fetchForGame(
        gameId: Long,
        title: String,
        platformId: String,
        romPath: String?,
        options: ScrapeOptions = ScrapeOptions(),
        onAssetProgress: ((source: String, asset: String) -> Unit)? = null,
    ): MetadataFetchResult {
        // Resolve best search title: user override → scraped title → caller-supplied title.
        val gameEntity = gameDao.getById(gameId)
        val bestTitle = gameEntity?.userTitleOverride?.takeIf { it.isNotBlank() }
            ?: gameEntity?.scrapedTitle?.takeIf { it.isNotBlank() }
            ?: title

        if (bestTitle != title) {
            Timber.d("MetadataRepository: using bestTitle='$bestTitle' instead of raw title='$title'")
        }

        // ── 1. TheGamesDB (primary metadata source) ───────────────────────────
        onAssetProgress?.invoke("TheGamesDB", "Searching…")
        val tgdbInfo = runCatching {
            theGamesDb.fetchGameInfo(platformId = platformId, title = bestTitle)
        }.onFailure { Timber.w(it, "TheGamesDB error for '$bestTitle'") }.getOrNull()

        // ── 2. IGDB (secondary artwork) ───────────────────────────────────────
        var igdbInfo: IgdbGameInfo? = null
        if (igdbApi.hasCredentials()) {
            val needsBoxArt = tgdbInfo?.artworkUrl == null
            val needsHero   = tgdbInfo?.heroUrl == null
            if (needsBoxArt || needsHero) {
                onAssetProgress?.invoke("IGDB", "Searching…")
                igdbInfo = runCatching {
                    igdbApi.fetchGameInfo(platformId, bestTitle)
                }.onFailure { Timber.w(it, "IGDB error for '$bestTitle'") }.getOrNull()
            }
        }

        // ── 3. SteamGridDB (artwork) ──────────────────────────────────────────
        val sgdbKey = sgdbKeyProvider.getKey()
        var sgdbGridUrl: String? = null
        var sgdbHeroUrl: String? = null
        var sgdbLogoUrl: String? = null
        var sgdbGameId:  Long?   = null

        if (!sgdbKey.isNullOrBlank()) {
            onAssetProgress?.invoke("SteamGridDB", "Searching…")
            runCatching {
                val match = steamGridDb.searchGame(bestTitle).getOrNull()?.firstOrNull()
                if (match != null) {
                    sgdbGameId  = match.id
                    sgdbGridUrl = steamGridDb.getBestGridUrl(match.id)
                    if (options.downloadHeroes) sgdbHeroUrl = steamGridDb.getBestHeroUrl(match.id)
                    if (options.downloadClearLogos) sgdbLogoUrl = steamGridDb.getBestLogoUrl(match.id)
                }
            }.onFailure { Timber.w(it, "SteamGridDB error for '$bestTitle'") }
        }

        // ── Nothing found ──────────────────────────────────────────────────────
        if (tgdbInfo == null && igdbInfo == null && sgdbGridUrl == null) {
            Timber.i("No metadata found for '$bestTitle'")
            return MetadataFetchResult(false, "none", "Not found on any source")
        }

        // ── Assemble per-asset winners ─────────────────────────────────────────
        val finalBoxArtUrl = tgdbInfo?.artworkUrl ?: igdbInfo?.artworkUrl ?: sgdbGridUrl
        val finalHeroUrl   = if (options.preferSteamGridDbHeroes)
            sgdbHeroUrl ?: tgdbInfo?.heroUrl ?: igdbInfo?.heroUrl
        else
            tgdbInfo?.heroUrl ?: igdbInfo?.heroUrl ?: sgdbHeroUrl
        val finalLogoUrl = if (options.downloadClearLogos)
            igdbInfo?.logoUrl ?: tgdbInfo?.logoUrl ?: sgdbLogoUrl
        else null

        // ── Download to disk ───────────────────────────────────────────────────
        val src = primarySource(tgdbInfo, igdbInfo, sgdbGridUrl)
        onAssetProgress?.invoke(src, "Box Art")
        val cardPath = finalBoxArtUrl?.let { cardProcessor.processBoxArt(gameId, platformId, it) }

        if (options.downloadHeroes) onAssetProgress?.invoke(src, "Hero")
        val heroPath = if (options.downloadHeroes) finalHeroUrl?.let { cardProcessor.downloadRaw(gameId, it, "hero.jpg") } else null

        if (options.downloadClearLogos) onAssetProgress?.invoke(src, "Logo")
        val logoPath = finalLogoUrl?.let { cardProcessor.downloadRaw(gameId, it, "logo.png", asPng = true) }

        // Scraped title from TheGamesDB — only update if no user override exists.
        val newScrapedTitle = tgdbInfo?.title
        val existingOverride = gameEntity?.userTitleOverride
        if (newScrapedTitle != null && existingOverride == null) {
            Timber.d("Updating scraped title to '$newScrapedTitle' for gameId=$gameId")
        }

        // ── Persist metadata (COALESCE in SQL preserves existing non-null values) ─
        gameDao.updateMetadata(
            id           = gameId,
            description  = tgdbInfo?.description,
            developer    = null,
            publisher    = null,
            releaseYear  = tgdbInfo?.releaseYear,
            genre        = null,
            artworkUri   = cardPath ?: finalBoxArtUrl,
            heroUri      = heroPath ?: finalHeroUrl,
            logoUri      = logoPath ?: finalLogoUrl,
            scrapedTitle = if (existingOverride == null) newScrapedTitle else null,
        )

        prewarm(cardPath, heroPath, logoPath)
        fetchHorizontalIcon(gameId, bestTitle, sgdbGameId)

        Timber.i("Metadata from $src: '$bestTitle' (scrapedTitle='$newScrapedTitle')")
        return MetadataFetchResult(true, src, "Found via $src", scrapedTitle = newScrapedTitle)
    }

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
            if (index < games.size - 1) delay(500)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun primarySource(tgdb: TgdbGameInfo?, igdb: IgdbGameInfo?, sgdbUrl: String?): String =
        when {
            tgdb != null    -> "thegamesdb"
            igdb != null    -> "igdb"
            sgdbUrl != null -> "steamgriddb"
            else            -> "mixed"
        }

    private suspend fun fetchHorizontalIcon(gameId: Long, title: String, knownSgdbId: Long?) {
        val key = sgdbKeyProvider.getKey()
        if (key.isNullOrBlank()) return
        runCatching {
            val id = knownSgdbId
                ?: steamGridDb.searchGame(title).getOrNull()?.firstOrNull()?.id
                ?: return
            val url  = steamGridDb.getBestHorizontalGridUrl(id) ?: return
            val path = cardProcessor.downloadRaw(gameId, url, "icon.jpg") ?: url
            gameDao.updateIconUri(gameId, path)
            prewarm(path)
            Timber.d("Horizontal icon from SteamGridDB: '$title'")
        }
    }

    private fun prewarm(vararg urls: String?) {
        urls.filterNotNull().forEach { url ->
            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}
