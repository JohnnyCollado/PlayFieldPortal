package com.playfieldportal.feature.artwork

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.IgdbGameInfo
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.SsGameInfo
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.rom.RomHasher
import com.playfieldportal.feature.artwork.rom.RomIdentity
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class MetadataFetchResult(
    val success: Boolean,
    val source: String,   // "screenscraper" | "thegamesdb" | "steamgriddb" | "igdb" | "none"
    val message: String,
    val scrapedTitle: String? = null,
)

// Fetches metadata + artwork from multiple sources in priority order.
//
// Default source priority: ScreenScraper (hash-based) → TheGamesDB → IGDB → SteamGridDB (artwork)
//
// ScreenScraper matches by CRC32 + size + filename (never by cleaned-up title), so it is tried
// first and the later sources only fill the gaps it leaves. Per-asset overrides:
//   • preferSteamGridDbHeroes  → SGDB is tried first for hero art
@Singleton
class MetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val screenScraper: ScreenScraperApi,
    private val romHasher: RomHasher,
    private val theGamesDb: TheGamesDbApi,
    private val steamGridDb: SteamGridDbApi,
    private val igdbApi: IgdbApi,
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val imageLoader: ImageLoader,
    private val artworkStore: ArtworkStore,
) {
    // Batch guards, set from ScreenScraper's typed failures: 430 (daily quota) and credential
    // failures stop SS for the rest of the run; 431 stops only hash-less lookups (each miss digs
    // the account deeper into the unrecognized-ROM penalty). Reset at the start of each batch.
    @Volatile private var ssStopped = false
    @Volatile private var ssUnhashedStopped = false

    fun resetSsBatchGuards() {
        ssStopped = false
        ssUnhashedStopped = false
    }
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

        // ── 1. ScreenScraper (hash-based, primary) ────────────────────────────
        var ssInfo: SsGameInfo? = null
        var romIdentity: RomIdentity? = null
        if (screenScraper.isEnabled && !ssStopped) {
            onAssetProgress?.invoke("ScreenScraper", "Hashing ROM…")
            romIdentity = romHasher.identify(gameEntity?.romPath ?: romPath, gameEntity?.romUri)
            val skipUnhashed = ssUnhashedStopped && romIdentity.crc32 == null && gameEntity?.ssId == null
            if (!skipUnhashed) {
                onAssetProgress?.invoke("ScreenScraper", "Searching…")
                val ssResult = screenScraper.fetchGameInfo(
                    platformId = platformId,
                    rom        = romIdentity,
                    ssGameId   = gameEntity?.ssId,   // known id → direct fetch, no matching
                )
                if (ssResult.isBatchStopper) ssStopped = true
                if (ssResult.stopsUnhashedLookups) ssUnhashedStopped = true
                ssInfo = ssResult.info
            }
        }

        // ── 2. TheGamesDB (fills what ScreenScraper left open) ────────────────
        var tgdbInfo: TgdbGameInfo? = null
        if (ssInfo == null || ssInfo.artworkUrl == null || ssInfo.description == null) {
            onAssetProgress?.invoke("TheGamesDB", "Searching…")
            tgdbInfo = runCatching {
                theGamesDb.fetchGameInfo(platformId = platformId, title = bestTitle)
            }.onFailure { Timber.w(it, "TheGamesDB error for '$bestTitle'") }.getOrNull()
        }

        // ── 3. IGDB (secondary artwork) ───────────────────────────────────────
        var igdbInfo: IgdbGameInfo? = null
        if (igdbApi.hasCredentials()) {
            val needsBoxArt = ssInfo?.artworkUrl == null && tgdbInfo?.artworkUrl == null
            val needsHero   = ssInfo?.heroUrl == null && tgdbInfo?.heroUrl == null
            if (needsBoxArt || needsHero) {
                onAssetProgress?.invoke("IGDB", "Searching…")
                igdbInfo = runCatching {
                    igdbApi.fetchGameInfo(platformId, bestTitle)
                }.onFailure { Timber.w(it, "IGDB error for '$bestTitle'") }.getOrNull()
            }
        }

        // ── 4. SteamGridDB (artwork) ──────────────────────────────────────────
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
        if (ssInfo == null && tgdbInfo == null && igdbInfo == null && sgdbGridUrl == null) {
            Timber.i("No metadata found for '$bestTitle'")
            return MetadataFetchResult(false, "none", "Not found on any source")
        }

        // ── Assemble per-asset winners ─────────────────────────────────────────
        val finalBoxArtUrl = ssInfo?.artworkUrl ?: tgdbInfo?.artworkUrl ?: igdbInfo?.artworkUrl ?: sgdbGridUrl
        val finalHeroUrl   = if (options.preferSteamGridDbHeroes)
            sgdbHeroUrl ?: ssInfo?.heroUrl ?: tgdbInfo?.heroUrl ?: igdbInfo?.heroUrl
        else
            ssInfo?.heroUrl ?: tgdbInfo?.heroUrl ?: igdbInfo?.heroUrl ?: sgdbHeroUrl
        val finalLogoUrl = if (options.downloadClearLogos)
            ssInfo?.logoUrl ?: igdbInfo?.logoUrl ?: tgdbInfo?.logoUrl ?: sgdbLogoUrl
        else null

        // ── Download to disk ───────────────────────────────────────────────────
        val src = primarySource(ssInfo, tgdbInfo, igdbInfo, sgdbGridUrl)

        if (options.downloadHeroes) onAssetProgress?.invoke(src, "Hero")
        val heroPath = if (options.downloadHeroes) finalHeroUrl?.let { artworkStore.saveFromUrl(gameId, ArtworkKind.HERO, it) } else null

        // Background (artworkUri) drives the full-screen XMB background, so it MUST be full
        // resolution. The old path composited box art into a tiny fixed-size card (≈288px) which
        // looked crunchy stretched to fullscreen. Download full-res instead, preferring a landscape
        // hero (reuse the hero file if we already have it), else the box/grid art.
        onAssetProgress?.invoke(src, "Background")
        val backgroundPath = heroPath
            ?: finalHeroUrl?.let { artworkStore.saveFromUrl(gameId, ArtworkKind.BACKGROUND, it) }
            ?: finalBoxArtUrl?.let { artworkStore.saveFromUrl(gameId, ArtworkKind.BACKGROUND, it) }

        if (options.downloadClearLogos) onAssetProgress?.invoke(src, "Logo")
        val logoPath = finalLogoUrl?.let { artworkStore.saveFromUrl(gameId, ArtworkKind.LOGO, it) }

        // ScreenScraper-only extras — stored under fixed names, looked up via ArtworkStore.find
        // (never referenced from game columns).
        if (options.downloadManuals) ssInfo?.manualUrl?.let {
            onAssetProgress?.invoke("ScreenScraper", "Manual")
            artworkStore.saveFromUrl(gameId, ArtworkKind.MANUAL, it)
        }
        if (options.downloadVideoSnaps) ssInfo?.videoUrl?.let {
            onAssetProgress?.invoke("ScreenScraper", "Video Snap")
            artworkStore.saveFromUrl(gameId, ArtworkKind.VIDEO, it)
        }

        // Scraped title: ScreenScraper's canonical name wins, TheGamesDB fallback — only
        // updated when no user override exists.
        val newScrapedTitle = ssInfo?.title ?: tgdbInfo?.title
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
            artworkUri   = backgroundPath ?: finalHeroUrl ?: finalBoxArtUrl,
            heroUri      = heroPath ?: finalHeroUrl,
            logoUri      = logoPath ?: finalLogoUrl,
            scrapedTitle = if (existingOverride == null) newScrapedTitle else null,
            ssId         = ssInfo?.ssId,
            tgdbId       = tgdbInfo?.tgdbId,
            steamGridDbId = sgdbGameId,
            romCrc32     = romIdentity?.crc32,
        )

        prewarm(backgroundPath, heroPath, logoPath)
        fetchHorizontalIcon(gameId, bestTitle, sgdbGameId)

        Timber.i("Metadata from $src: '$bestTitle' (scrapedTitle='$newScrapedTitle')")
        return MetadataFetchResult(true, src, "Found via $src", scrapedTitle = newScrapedTitle)
    }

    suspend fun fetchMissingMetadata(onProgress: (current: Int, total: Int) -> Unit) {
        resetSsBatchGuards()
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

    private fun primarySource(ss: SsGameInfo?, tgdb: TgdbGameInfo?, igdb: IgdbGameInfo?, sgdbUrl: String?): String =
        when {
            ss != null      -> "screenscraper"
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
            val path = artworkStore.saveFromUrl(gameId, ArtworkKind.ICON, url) ?: url
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
