package com.playfieldportal.feature.artwork.api

import android.content.Context
import coil.imageLoader
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.feature.artwork.MetadataRepository
import com.playfieldportal.feature.artwork.store.ArtworkStore
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
    val skipped: Boolean = false,
    val errorMessage: String? = null,
)

// Real, file-aware artwork status for the whole library.
data class ArtworkStatus(
    val total: Int = 0,
    val complete: Int = 0,   // has valid box art (file present / remote URL)
    val missing: Int = 0,    // no artwork reference at all
    val stale: Int = 0,      // reference exists but the local file is gone/empty
)

// Live progress during a scrape run.
data class ScrapeProgress(
    val current: Int,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val title: String,
    val scrapeSource: String = "",   // e.g. "TheGamesDB", "SteamGridDB"
    val scrapeAsset: String = "",    // e.g. "Box Art", "Hero", "Logo"
)

@Singleton
class ArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val metadataRepository: MetadataRepository,
    private val scrapePreferences: ArtworkScrapePreferences,
    private val artworkStore: ArtworkStore,
    private val internalStore: com.playfieldportal.feature.artwork.store.InternalArtworkStore,
    private val ssMediaCacheDao: com.playfieldportal.core.data.database.dao.SsMediaCacheDao,
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

    /** Drops every cached ScreenScraper media-URL list — next scrape refreshes per game. */
    suspend fun clearSsMediaCache() = ssMediaCacheDao.clearAll()

    /**
     * App-side artwork footprint in bytes: Coil's disk cache + the internal artwork store.
     * Files in the user's portable library are the user's own and are never counted.
     */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        val coilBytes = context.imageLoader.diskCache?.size ?: 0L
        coilBytes + internalStore.footprint().second
    }

    /**
     * Full artwork reset for a fresh start: Coil caches, every internally stored file, all
     * artwork_records, and every game's artwork columns. Files in the user's portable
     * library are NEVER deleted — Relink/re-scrape rebuilds from them at any time.
     */
    suspend fun clearCache() {
        context.imageLoader.diskCache?.clear()
        context.imageLoader.memoryCache?.clear()
        artworkStore.deleteAll()          // internal files + artwork_records (never library files)
        gameDao.clearAllArtworkRefs()
        ssMediaCacheDao.clearAll()
        Timber.i("Artwork cache + stored artwork state cleared")
    }

    // ── Accurate, file-aware artwork status ──────────────────────────────────────

    // A reference is valid if it's a remote URL, or a stored file that still resolves to bytes.
    private fun isValidArtworkRef(uri: String?): Boolean = artworkStore.isValidRef(uri)

    suspend fun computeStatus(): ArtworkStatus = withContext(Dispatchers.IO) {
        val games = gameDao.getAll()
        var complete = 0
        var missing = 0
        var stale = 0
        games.forEach { g ->
            when {
                g.artworkUri.isNullOrBlank()      -> missing++
                isValidArtworkRef(g.artworkUri)   -> complete++
                else                              -> stale++   // path set but file missing/empty
            }
        }
        ArtworkStatus(total = games.size, complete = complete, missing = missing, stale = stale)
            .also { Timber.i("Artwork status: $it") }
    }

    // ── Clearing ─────────────────────────────────────────────────────────────────

    // Clears all DB artwork references AND the stored artwork files so nothing stale remains.
    suspend fun clearAllArtwork() = withContext(Dispatchers.IO) {
        gameDao.clearAllArtwork()
        artworkStore.deleteAll()
        clearCache()
        Timber.i("All artwork cleared (db refs + files + cache)")
    }

    // ── Scrape modes ──────────────────────────────────────────────────────────────

    // Re-scrape every game: clears existing artwork first, then fetches fresh art for all.
    suspend fun reScrapeAllGames(onProgress: (ScrapeProgress) -> Unit): ScrapeProgress =
        withContext(Dispatchers.IO) {
            clearAllArtwork()
            // Re-scrape-all exists to pick up upstream changes — bypass the SS URL cache.
            fetchForGames(gameDao.getAll().map { it.id to Triple(it.title, it.platformId, it.romPath) }, onProgress, bypassSsCache = true)
        }

    // Scrape only games whose artwork is missing or stale (invalid local file). Valid artwork
    // is left untouched. Stale refs are cleared first so the dead path can't linger.
    suspend fun scrapeMissingOnly(onProgress: (ScrapeProgress) -> Unit): ScrapeProgress =
        withContext(Dispatchers.IO) {
            val targets = gameDao.getAll().filter { !isValidArtworkRef(it.artworkUri) }
            // Clear stale (non-null but invalid) refs so a fresh fetch isn't masked by COALESCE.
            targets.filter { !it.artworkUri.isNullOrBlank() }.forEach { gameDao.clearArtworkForGame(it.id) }
            fetchForGames(targets.map { it.id to Triple(it.title, it.platformId, it.romPath) }, onProgress)
        }

    // Shared scrape loop with rich progress and per-game error isolation.
    private suspend fun fetchForGames(
        games: List<Pair<Long, Triple<String, String, String?>>>,
        onProgress: (ScrapeProgress) -> Unit,
        bypassSsCache: Boolean = false,
    ): ScrapeProgress {
        metadataRepository.resetSsBatchGuards()
        val options = scrapePreferences.getOptions().copy(bypassSsCache = bypassSsCache)
        var ok = 0
        var fail = 0
        games.forEachIndexed { index, (id, info) ->
            val (title, platformId, romPath) = info
            onProgress(ScrapeProgress(index + 1, games.size, ok, fail, title))
            val result = runCatching {
                metadataRepository.fetchForGame(
                    gameId   = id,
                    title    = title,
                    platformId = platformId,
                    romPath  = romPath,
                    options  = options,
                    onAssetProgress = { source, asset ->
                        onProgress(ScrapeProgress(index + 1, games.size, ok, fail, title,
                            scrapeSource = source, scrapeAsset = asset))
                    },
                )
            }.getOrNull()
            if (result?.success == true) ok++ else fail++
            if (index < games.size - 1) delay(500)
        }
        return ScrapeProgress(games.size, games.size, ok, fail, "")
            .also { Timber.i("Scrape complete: ${it.succeeded} ok, ${it.failed} failed of ${it.total}") }
    }
}
