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
    val source: String,   // "screenscraper" | "thegamesdb" | "steamgriddb" | "igdb" | "none"
    val message: String,
    val scrapedTitle: String? = null,
    val ssDiagnostics: SsLookupDiagnostics? = null,
)

// Fetches metadata + artwork from multiple sources in priority order.
//
// Default source priority: ScreenScraper → SteamGridDB → IGDB → TheGamesDB
//
// Per-asset overrides:
//   • preferSteamGridDbHeroes  → SGDB is tried first for hero art
//   • preferScreenScraperBoxArt → SS is tried first for box art (default on)
//
// Only non-null fields are written to the DB (COALESCE in SQL keeps existing values).
@Singleton
class MetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val screenScraper: ScreenScraperApi,
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
        val romFile = romPath?.let { File(it) }?.takeIf { it.exists() }

        // Resolve the best search title: user override → scraped title → caller-supplied title.
        // Reading from DB here so the caller doesn't need to pass extra fields.
        val gameEntity = gameDao.getById(gameId)
        val bestTitle = gameEntity?.userTitleOverride?.takeIf { it.isNotBlank() }
            ?: gameEntity?.scrapedTitle?.takeIf { it.isNotBlank() }
            ?: title

        if (bestTitle != title) {
            Timber.d("MetadataRepository: using bestTitle='$bestTitle' instead of raw title='$title'")
        }

        // ── 1. ScreenScraper (hash-based — most accurate, has manuals/videos) ──
        onAssetProgress?.invoke("ScreenScraper", "Searching…")
        val ssResult = runCatching {
            screenScraper.fetchGameInfoWithDiagnostics(
                platformId = platformId, romFile = romFile, title = bestTitle,
            )
        }.onFailure { Timber.w(it, "ScreenScraper error for '$bestTitle'") }.getOrNull()

        val ssInfo = ssResult?.info
        val ssDiag = ssResult?.diagnostics

        // Log structured diagnostics (no passwords)
        if (ssDiag != null) {
            if (ssDiag.failureReason != null) {
                Timber.w(
                    "ScreenScraper diagnostics: " +
                    "platform=${ssDiag.platformId} systemId=${ssDiag.screenScraperSystemId} " +
                    "file='${ssDiag.originalFileName}' cleaned='${ssDiag.cleanedSearchName}' " +
                    "credentialsPresent=${ssDiag.credentialsPresent} httpStatus=${ssDiag.httpStatus} " +
                    "reason=${ssDiag.failureReason} detail='${ssDiag.failureDetail}'"
                )
            }
        }

        // ── 2. SteamGridDB ────────────────────────────────────────────────────
        // Called when: key is present AND (hero preference requires it OR SS returned nothing)
        val sgdbKey = sgdbKeyProvider.getKey()
        var sgdbGridUrl:  String? = null
        var sgdbHeroUrl:  String? = null
        var sgdbLogoUrl:  String? = null
        var sgdbIconUrl:  String? = null
        var sgdbGameId:   Long?   = null

        val needSgdbForHero  = options.downloadHeroes && options.preferSteamGridDbHeroes
        val needSgdbForBoxArt = !options.preferScreenScraperBoxArt && ssInfo?.artworkUrl == null
        val needSgdbFallback  = ssInfo == null

        if (!sgdbKey.isNullOrBlank() && (needSgdbForHero || needSgdbForBoxArt || needSgdbFallback)) {
            onAssetProgress?.invoke("SteamGridDB", "Searching…")
            runCatching {
                val match = steamGridDb.searchGame(bestTitle).getOrNull()?.firstOrNull()
                if (match != null) {
                    sgdbGameId   = match.id
                    sgdbGridUrl  = steamGridDb.getBestGridUrl(match.id)
                    if (options.downloadHeroes) sgdbHeroUrl = steamGridDb.getBestHeroUrl(match.id)
                    if (options.downloadClearLogos) sgdbLogoUrl = steamGridDb.getBestLogoUrl(match.id)
                    sgdbIconUrl  = steamGridDb.getBestHorizontalGridUrl(match.id)
                }
            }.onFailure { Timber.w(it, "SteamGridDB error for '$bestTitle'") }
        }

        // ── 3. IGDB (if credentials configured and still missing art) ─────────
        val boxArtSoFar = pickBoxArt(ssInfo?.artworkUrl, sgdbGridUrl, options)
        val heroSoFar   = pickHero(ssInfo?.heroUrl, sgdbHeroUrl, options)
        val logoSoFar   = if (options.downloadClearLogos) ssInfo?.logoUrl ?: sgdbLogoUrl else null

        var igdbInfo: IgdbGameInfo? = null
        if ((boxArtSoFar == null || heroSoFar == null || (options.downloadClearLogos && logoSoFar == null))
            && igdbApi.hasCredentials()
        ) {
            onAssetProgress?.invoke("IGDB", "Searching…")
            igdbInfo = runCatching {
                igdbApi.fetchGameInfo(platformId, bestTitle)
            }.onFailure { Timber.w(it, "IGDB error for '$bestTitle'") }.getOrNull()
        }

        // ── 4. TheGamesDB (title-based fallback) ──────────────────────────────
        val boxArtWithIgdb = boxArtSoFar ?: igdbInfo?.artworkUrl
        val heroWithIgdb   = heroSoFar   ?: igdbInfo?.heroUrl
        val logoWithIgdb   = if (options.downloadClearLogos) logoSoFar ?: igdbInfo?.logoUrl else null

        var tgdbInfo: TgdbGameInfo? = null
        if (boxArtWithIgdb == null || heroWithIgdb == null || (options.downloadClearLogos && logoWithIgdb == null)) {
            onAssetProgress?.invoke("TheGamesDB", "Searching…")
            tgdbInfo = runCatching {
                theGamesDb.fetchGameInfo(platformId = platformId, title = bestTitle)
            }.onFailure { Timber.w(it, "TheGamesDB error for '$bestTitle'") }.getOrNull()
        }

        // ── Nothing found ──────────────────────────────────────────────────────
        if (ssInfo == null && sgdbGridUrl == null && igdbInfo == null && tgdbInfo == null) {
            Timber.i("No metadata found for '$bestTitle'")
            val failDetail = ssDiag?.failureDetail ?: "Not found on any source"
            return MetadataFetchResult(false, "none", failDetail, ssDiagnostics = ssDiag)
        }

        // ── Assemble per-asset winners ─────────────────────────────────────────
        val finalBoxArtUrl = pickBoxArt(ssInfo?.artworkUrl, sgdbGridUrl, options)
            ?: igdbInfo?.artworkUrl ?: tgdbInfo?.artworkUrl
        val finalHeroUrl   = pickHero(ssInfo?.heroUrl, sgdbHeroUrl, options)
            ?: igdbInfo?.heroUrl   ?: tgdbInfo?.heroUrl
        val finalLogoUrl   = if (options.downloadClearLogos)
            ssInfo?.logoUrl ?: sgdbLogoUrl ?: igdbInfo?.logoUrl ?: tgdbInfo?.logoUrl
        else null
        val finalManualUrl = if (options.downloadManuals) ssInfo?.manualUrl else null
        val finalVideoUrl  = if (options.downloadVideoSnaps) ssInfo?.videoUrl else null

        // ── Download to disk ───────────────────────────────────────────────────
        onAssetProgress?.invoke(primarySource(ssInfo, sgdbGridUrl, igdbInfo, tgdbInfo), "Box Art")
        val cardPath = finalBoxArtUrl?.let { cardProcessor.processBoxArt(gameId, platformId, it) }

        if (options.downloadHeroes) {
            onAssetProgress?.invoke(primarySource(ssInfo, sgdbGridUrl, igdbInfo, tgdbInfo), "Hero")
        }
        val heroPath = if (options.downloadHeroes) finalHeroUrl?.let { cardProcessor.downloadRaw(gameId, it, "hero.jpg") } else null

        if (options.downloadClearLogos) {
            onAssetProgress?.invoke(primarySource(ssInfo, sgdbGridUrl, igdbInfo, tgdbInfo), "Logo")
        }
        val logoPath = finalLogoUrl?.let { cardProcessor.downloadRaw(gameId, it, "logo.png", asPng = true) }

        val manualPath = finalManualUrl?.let { cardProcessor.downloadRaw(gameId, it, "manual.pdf") }
        val videoPath  = finalVideoUrl?.let  { cardProcessor.downloadRaw(gameId, it, "video.mp4") }

        // Scraped title from metadata (only from ScreenScraper — most authoritative).
        // Only update if the user hasn't manually set a title override.
        val newScrapedTitle = ssInfo?.title
        val existingOverride = gameEntity?.userTitleOverride
        if (newScrapedTitle != null && existingOverride == null) {
            Timber.d("Updating scraped title to '$newScrapedTitle' for gameId=$gameId")
        }

        // ── Persist metadata (COALESCE in SQL preserves existing non-null values) ─
        gameDao.updateMetadata(
            id           = gameId,
            description  = ssInfo?.description ?: tgdbInfo?.description,
            developer    = ssInfo?.developer,
            publisher    = ssInfo?.publisher,
            releaseYear  = ssInfo?.releaseYear ?: tgdbInfo?.releaseYear,
            genre        = ssInfo?.genre,
            artworkUri   = cardPath ?: finalBoxArtUrl,
            heroUri      = heroPath ?: finalHeroUrl,
            logoUri      = logoPath ?: finalLogoUrl,
            // Only set scraped title when no user override exists — preserve manual renames.
            scrapedTitle = if (existingOverride == null) newScrapedTitle else null,
        )

        prewarm(cardPath, heroPath, logoPath)
        fetchHorizontalIcon(gameId, bestTitle, sgdbGameId)

        val src = primarySource(ssInfo, sgdbGridUrl, igdbInfo, tgdbInfo)
        Timber.i("Metadata from $src: '$bestTitle' (scrapedTitle='$newScrapedTitle')")
        return MetadataFetchResult(true, src, "Found via $src",
            scrapedTitle = newScrapedTitle, ssDiagnostics = ssDiag)
    }

    // Bulk fetch for games missing artwork.
    suspend fun fetchMissingMetadata(onProgress: (current: Int, total: Int) -> Unit) {
        val games = gameDao.getGamesWithoutArtwork()
        games.forEachIndexed { index, game ->
            onProgress(index + 1, games.size)
            // fetchForGame reads the entity again for scrapedTitle/userTitleOverride internally.
            fetchForGame(
                gameId     = game.id,
                title      = game.title,
                platformId = game.platformId,
                romPath    = game.romPath,
            )
            if (index < games.size - 1) delay(1_100)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun pickBoxArt(ssUrl: String?, sgdbUrl: String?, options: ScrapeOptions): String? =
        if (options.preferScreenScraperBoxArt) ssUrl ?: sgdbUrl else sgdbUrl ?: ssUrl

    private fun pickHero(ssUrl: String?, sgdbUrl: String?, options: ScrapeOptions): String? =
        if (options.preferSteamGridDbHeroes) sgdbUrl ?: ssUrl else ssUrl ?: sgdbUrl

    private fun primarySource(
        ssInfo: SsGameInfo?,
        sgdbGridUrl: String?,
        igdbInfo: IgdbGameInfo?,
        tgdbInfo: TgdbGameInfo?,
    ): String = when {
        ssInfo != null     -> "screenscraper"
        tgdbInfo != null   -> "thegamesdb"
        igdbInfo != null   -> "igdb"
        sgdbGridUrl != null -> "steamgriddb"
        else               -> "mixed"
    }

    // Always sources the landscape 144:80 icon from SteamGridDB since ScreenScraper /
    // IGDB / TheGamesDB don't provide the horizontal capsule format.
    private suspend fun fetchHorizontalIcon(gameId: Long, title: String, knownSgdbId: Long?) {
        val key = sgdbKeyProvider.getKey()
        if (key.isNullOrBlank()) return
        runCatching {
            val gameId2 = knownSgdbId
                ?: steamGridDb.searchGame(title).getOrNull()?.firstOrNull()?.id
                ?: return
            val url = steamGridDb.getBestHorizontalGridUrl(gameId2) ?: return
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
