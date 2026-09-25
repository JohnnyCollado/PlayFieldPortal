package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.domain.model.MetadataOverrides
import com.playfieldportal.feature.artwork.MetadataRepository
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MetadataApply
import com.playfieldportal.feature.artwork.match.MetadataApplyPolicy
import com.playfieldportal.feature.artwork.match.MetadataField
import com.playfieldportal.feature.artwork.match.MetadataPreset
import com.playfieldportal.feature.artwork.match.MetadataPreview
import com.playfieldportal.feature.artwork.store.ArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

data class ArtworkFetchResult(
    val gameId: Long,
    val title: String,
    val success: Boolean,
    // Another surface is already fetching this game; nothing was scraped by this call.
    val alreadyRunning: Boolean = false,
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
    private val imageCache: ArtworkImageCache,
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

    // Games with a single-game fetch in flight. Game Detail and the XMB game menu can both ask for
    // the same game; the second ask is answered with alreadyRunning instead of a second scrape.
    private val refetching: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    /**
     * Fetch Artwork for one game — the single entry point for Game Detail and the XMB game menu.
     *
     * Re-scraped files reuse stable names, so this game's refs (old and new) are evicted from the
     * image cache afterwards. Only evicted: never clearCache(), which is the library-wide reset
     * behind Settings > Artwork > Clear All Artwork.
     */
    suspend fun refetchArtworkForGame(
        gameId: Long,
        onAssetProgress: ((source: String, asset: String) -> Unit)? = null,
    ): ArtworkFetchResult {
        val before = gameDao.getById(gameId)
            ?: return ArtworkFetchResult(gameId, "", false, errorMessage = "Game not found")
        if (!refetching.add(gameId)) {
            return ArtworkFetchResult(gameId, before.title, false, alreadyRunning = true)
        }
        try {
            val result = metadataRepository.fetchForGame(
                gameId          = gameId,
                title           = before.title,
                platformId      = before.platformId,
                romPath         = before.romPath,
                onAssetProgress = onAssetProgress,
            )
            val after = gameDao.getById(gameId)
            evictFromImageCache((artRefsOf(before) + artRefsOf(after)).toSet())
            return ArtworkFetchResult(
                gameId, before.title, result.success,
                errorMessage = result.message.takeIf { !result.success },
            )
        } finally {
            refetching.remove(gameId)
        }
    }

    // Every artwork column a scrape can rewrite — the eviction set for a single-game fetch.
    private fun artRefsOf(game: GameEntity?): List<String> = listOfNotNull(
        game?.artworkUri, game?.heroUri, game?.logoUri, game?.iconUri,
        game?.boxArtUri, game?.physicalMediaUri, game?.box3dUri,
    )

    // ── Metadata presets (C16 task 3.2) ──────────────────────────────────────────

    /**
     * Current-vs-Incoming for one game. Writes no `games` column (AD-7).
     *
     * Bypasses the ScreenScraper media-URL cache on purpose: a cache hit is URL-only
     * (`SsMediaSelection.infoFromCache`), so it would never offer a ScreenScraper preset. That costs
     * one jeuInfos call per explicit open, never per scrape.
     */
    suspend fun fetchMetadataPreview(gameId: Long): MetadataPreview? = withContext(Dispatchers.IO) {
        val game = gameDao.getById(gameId) ?: return@withContext null
        val candidates = metadataRepository.fetchCandidates(
            gameId     = gameId,
            title      = game.title,
            platformId = game.platformId,
            romPath    = game.romPath,
            options    = ScrapeOptions(metadataOnly = true, bypassSsCache = true),
        )
        MetadataPreview(
            current    = MetadataApply.currentOf(game),
            effective  = MetadataApply.effectiveOf(game),
            overridden = MetadataApply.overriddenFieldsOf(game),
            presets    = MetadataApply.presetsFrom(candidates),
        )
    }

    /**
     * Applies [incoming] under [policy] against the game's CURRENT row (re-read here, so a preview
     * left open never writes against stale values). Returns the fields written; empty means nothing
     * was touched.
     *
     * Two destinations, one plan. A provider preset writes the ordinary metadata columns exactly as
     * it always has — no artwork column, provider id or title override can change through this
     * path. A [MatchProvider.MANUAL] preset writes the user-override shadow layer instead and
     * leaves every metadata column alone, which is what makes a hand-set value survive the next
     * Re-scrape All: the scrape keeps refreshing a layer that no longer reaches the screen.
     */
    suspend fun applyMetadata(
        gameId: Long,
        incoming: MetadataPreset,
        policy: MetadataApplyPolicy,
        chosen: Set<MetadataField> = emptySet(),
    ): Set<MetadataField> = withContext(Dispatchers.IO) {
        val game = gameDao.getById(gameId) ?: return@withContext emptySet()
        // A manual edit is measured against what the user SEES, a provider's against what the
        // columns hold — the only thing it can write.
        val comparedAgainst =
            if (incoming.provider == MatchProvider.MANUAL) MetadataApply.effectiveOf(game)
            else MetadataApply.currentOf(game)
        val plan = MetadataApply.plan(comparedAgainst, incoming, policy, chosen)
        if (plan.isEmpty()) return@withContext emptySet()

        if (incoming.provider == MatchProvider.MANUAL) {
            writeOverrides(game, plan)
            return@withContext plan.keys
        }

        val description     = plan[MetadataField.DESCRIPTION] as String?
        val developer       = plan[MetadataField.DEVELOPER] as String?
        val publisher       = plan[MetadataField.PUBLISHER] as String?
        val releaseYear     = plan[MetadataField.RELEASE_YEAR] as Int?
        val genre           = plan[MetadataField.GENRE] as String?
        val scrapedTitle    = plan[MetadataField.TITLE] as String?
        val ageRating       = plan[MetadataField.AGE_RATING] as String?
        val franchise       = plan[MetadataField.FRANCHISE] as String?
        val communityRating = plan[MetadataField.COMMUNITY_RATING] as Float?
        val releaseDate     = plan[MetadataField.RELEASE_DATE] as String?

        if (policy == MetadataApplyPolicy.FILL_MISSING_ONLY) {
            gameDao.updateMetadataIfMissing(
                id = gameId, description = description, developer = developer, publisher = publisher,
                releaseYear = releaseYear, genre = genre, scrapedTitle = scrapedTitle,
                ageRating = ageRating, franchise = franchise, communityRating = communityRating,
                releaseDate = releaseDate,
            )
        } else {
            // COALESCE(:new, old): the plan holds only non-blank incoming values, so this overwrites
            // exactly the planned fields and leaves every other column as it is.
            gameDao.updateMetadata(
                id = gameId, description = description, developer = developer, publisher = publisher,
                releaseYear = releaseYear, genre = genre, scrapedTitle = scrapedTitle,
                ageRating = ageRating, franchise = franchise, communityRating = communityRating,
                releaseDate = releaseDate,
            )
        }
        plan.keys
    }

    /**
     * Merges [plan] into the game's hand-set overrides. Merge, not replace: applying an edit to one
     * field must not silently revert the eight the user set earlier.
     *
     * TITLE goes to its own column (`MetadataOverrides` documents why it lives there), so the two
     * halves of one map are written by the two statements that own them.
     */
    private suspend fun writeOverrides(game: GameEntity, plan: Map<MetadataField, Any>) {
        var overrides = MetadataOverrides.parse(game.userMetadataOverrides)
        plan.forEach { (field, value) ->
            if (field == MetadataField.TITLE) {
                gameDao.updateUserTitleOverride(game.id, value.toString())
            } else {
                overrides = overrides.with(field.name, value)
            }
        }
        gameDao.updateUserMetadataOverrides(game.id, overrides.toColumnValue())
    }

    /**
     * Reverts one field to whatever the scrapers last stored: the override key is removed and the
     * column underneath, untouched all along, becomes visible again.
     *
     * Returns true when a value was actually removed, so the caller can say "reverted" rather than
     * "reverted" over a field that had no override.
     */
    suspend fun clearMetadataOverride(gameId: Long, field: MetadataField): Boolean =
        withContext(Dispatchers.IO) {
            val game = gameDao.getById(gameId) ?: return@withContext false
            if (field == MetadataField.TITLE) {
                if (game.userTitleOverride.isNullOrBlank()) return@withContext false
                gameDao.updateUserTitleOverride(gameId, null)
                return@withContext true
            }
            val overrides = MetadataOverrides.parse(game.userMetadataOverrides)
            if (!overrides.isOverridden(field.name)) return@withContext false
            gameDao.updateUserMetadataOverrides(gameId, overrides.without(field.name).toColumnValue())
            true
        }

    /** Drops every cached ScreenScraper media-URL list — next scrape refreshes per game. */
    suspend fun clearSsMediaCache() = ssMediaCacheDao.clearAll()

    /** Evicts just [uris] from the display caches — see [ArtworkImageCache.evict]. */
    fun evictFromImageCache(uris: Collection<String>) = imageCache.evict(uris)

    /**
     * App-side artwork footprint in bytes: Coil's disk cache + the internal artwork store.
     * Files in the user's portable library are the user's own and are never counted.
     */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        imageCache.diskSizeBytes() + internalStore.footprint().second
    }

    /**
     * Full artwork reset for a fresh start: Coil caches, every internally stored file, all
     * artwork_records, and every game's artwork columns. Files in the user's portable
     * library are NEVER deleted — Relink/re-scrape rebuilds from them at any time.
     */
    suspend fun clearCache() {
        imageCache.clear()
        artworkStore.deleteAll()          // internal files + artwork_records (never library files)
        gameDao.clearAllArtworkRefs()
        ssMediaCacheDao.clearAll()
        Timber.i("Artwork cache + stored artwork state cleared")
    }

    // ── Accurate, file-aware artwork status ──────────────────────────────────────

    // A reference is valid if it's a remote URL, or a stored file that still resolves to bytes.
    private fun isValidArtworkRef(uri: String?): Boolean = artworkStore.isValidRef(uri)

    // The primary display artwork a matched game can reliably obtain from the scrapers: the
    // full-screen background, the box-art tile, and the clear logo. A game counts as complete
    // (and is skipped by Scrape Missing) only when ALL of these resolve. Physical media, 3D
    // boxes, hero fanart, and the SGDB-grid icon are intentionally excluded — ScreenScraper
    // lacks them for many titles, so requiring them would re-target those games forever. They
    // are still filled opportunistically whenever a game IS scraped.
    private fun primaryArtRefs(g: com.playfieldportal.core.data.database.entity.GameEntity) =
        listOf(g.artworkUri, g.boxArtUri, g.logoUri)

    private fun needsArtwork(g: com.playfieldportal.core.data.database.entity.GameEntity): Boolean =
        primaryArtRefs(g).any { !isValidArtworkRef(it) }

    suspend fun computeStatus(): ArtworkStatus = withContext(Dispatchers.IO) {
        val games = gameDao.getAll()
        var complete = 0
        var missing = 0
        var stale = 0
        games.forEach { g ->
            val refs = primaryArtRefs(g)
            when {
                refs.all { isValidArtworkRef(it) }                        -> complete++
                refs.any { !it.isNullOrBlank() && !isValidArtworkRef(it) } -> stale++   // a set path broke
                else                                                     -> missing++  // simply absent
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

    // Scrape every game still missing any primary artwork — not just those missing a background.
    // A game with, say, a valid background but no box art or logo is now included and its gaps
    // are filled (the store's conflict gate leaves already-valid assets untouched, so nothing
    // that exists is re-downloaded or overwritten).
    suspend fun scrapeMissingOnly(onProgress: (ScrapeProgress) -> Unit): ScrapeProgress =
        withContext(Dispatchers.IO) {
            val targets = gameDao.getAll().filter { needsArtwork(it) }
            // Only games whose BACKGROUND ref is itself stale get the slate cleared before the
            // re-fetch (clearArtworkForGame also nulls hero/logo/icon). Games pulled in only for
            // a missing box art keep their valid background — COALESCE fills the gaps in place.
            targets.filter { !it.artworkUri.isNullOrBlank() && !isValidArtworkRef(it.artworkUri) }
                .forEach { gameDao.clearArtworkForGame(it.id) }
            fetchForGames(targets.map { it.id to Triple(it.title, it.platformId, it.romPath) }, onProgress)
        }

    // Scrape only [platformId]'s games still missing primary artwork — the per-card menu action.
    // Same rules as scrapeMissingOnly: complete games are skipped, stale backgrounds cleared,
    // gaps filled in place without re-downloading anything valid.
    suspend fun scrapeMissingForPlatform(platformId: String, onProgress: (ScrapeProgress) -> Unit): ScrapeProgress =
        withContext(Dispatchers.IO) {
            val targets = gameDao.getAll().filter { it.platformId == platformId && needsArtwork(it) }
            targets.filter { !it.artworkUri.isNullOrBlank() && !isValidArtworkRef(it.artworkUri) }
                .forEach { gameDao.clearArtworkForGame(it.id) }
            fetchForGames(targets.map { it.id to Triple(it.title, it.platformId, it.romPath) }, onProgress)
        }

    // Text-only metadata pass over every game on [platformId] — no artwork is downloaded and no
    // artwork column is written (COALESCE fills missing text fields, existing values stay).
    suspend fun updateMetadataForPlatform(platformId: String, onProgress: (ScrapeProgress) -> Unit): ScrapeProgress =
        withContext(Dispatchers.IO) {
            val games = gameDao.getAll().filter { it.platformId == platformId }
            fetchForGames(games.map { it.id to Triple(it.title, it.platformId, it.romPath) }, onProgress, metadataOnly = true)
        }

    // Shared scrape loop with rich progress and per-game error isolation.
    private suspend fun fetchForGames(
        games: List<Pair<Long, Triple<String, String, String?>>>,
        onProgress: (ScrapeProgress) -> Unit,
        bypassSsCache: Boolean = false,
        metadataOnly: Boolean = false,
    ): ScrapeProgress {
        metadataRepository.resetSsBatchGuards()
        val options = scrapePreferences.getOptions().copy(bypassSsCache = bypassSsCache, metadataOnly = metadataOnly)
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
