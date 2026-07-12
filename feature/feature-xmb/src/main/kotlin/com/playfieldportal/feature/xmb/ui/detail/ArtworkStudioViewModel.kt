package com.playfieldportal.feature.xmb.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

// ── Studio model ──────────────────────────────────────────────────────────────

/** One artwork destination tab. [contract] is the display rule shown under the tab bar. */
data class StudioTab(val kind: ArtworkKind, val label: String, val contract: String)

enum class StudioSource(val label: String) {
    SCREENSCRAPER("ScreenScraper"),
    STEAMGRIDDB("SteamGridDB"),
    THEGAMESDB("TheGamesDB"),
    IGDB("IGDB"),
    LOCAL("Local File"),
}

/** One result tile in the Available Artwork grid. */
data class StudioArt(
    val url: String,
    val thumb: String?,
    val provider: String,
    val label: String? = null,
    val isVideo: Boolean = false,
)

enum class StudioZone { TABS, SOURCES, GRID }

data class ArtworkStudioUiState(
    val game: Game? = null,
    val isLoading: Boolean = true,
    val tabIndex: Int = 0,
    val sourceIndex: Int = 0,
    val zone: StudioZone = StudioZone.GRID,
    val gridIndex: Int = 0,
    val page: Int = 0,
    val results: List<StudioArt> = emptyList(),
    val totalResults: Int = 0,
    val resultsLoading: Boolean = false,
    // Current asset of the active tab (what the game uses right now).
    val currentUri: String? = null,
    // Bumped on every apply/clear so the preview reloads even when the portable library reuses
    // a stable content URI (same string → Coil would otherwise serve the old bytes).
    val previewVersion: Int = 0,
    val includeNsfw: Boolean = false,
    val hasSgdbKey: Boolean = false,
    // Candidate preview overlay (A on a grid tile). Apply/Cancel from here.
    val candidate: StudioArt? = null,
    // Manual candidates: the PDF is downloaded to cache and paged before Apply.
    val candidateManualPath: String? = null,
    val manualDownloading: Boolean = false,
    val manualPage: Int = 0,
    val manualPageCount: Int = 0,
    val applying: Boolean = false,
    val message: String? = null,
    // Set when the user picked "Local File" — the screen launches the SAF picker for it.
    val localPickKind: ArtworkKind? = null,
    // Actions menu (LONG_PRESS / on-screen ACTIONS) — operates on the active tab's current slot.
    val actionsOpen: Boolean = false,
    val actionsIndex: Int = 0,
    val info: StudioArtworkInfo? = null,
    val showFileInfo: Boolean = false,
    // Crop editor (task: crop/position) — non-null path = editing the untouched original.
    // For ICON1 the path is a still frame extracted for framing; the video to re-encode is
    // held in cropVideoSourcePath.
    val cropEditorPath: String? = null,
    val cropVideoSourcePath: String? = null,
    val cropPreparing: Boolean = false,
    val cropSrcW: Int = 0,
    val cropSrcH: Int = 0,
    val cropZoom: Float = 1f,
    val cropCenterX: Float = 0.5f,
    val cropCenterY: Float = 0.5f,
    // Computed normalized crop window (0..1) — the UI draws the frame from these.
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 1f,
    val cropB: Float = 1f,
    val closed: Boolean = false,
) {
    /** Actions that make sense for the current slot, in menu order. Empty entries are hidden. */
    val availableActions: List<StudioAction>
        get() = buildList {
            val kind = STUDIO_TABS.getOrNull(tabIndex)?.kind
            val hasCurrent = currentUri != null
            if (hasCurrent && kind != null && kind in CROPPABLE_KINDS) add(StudioAction.CROP)
            if (info?.hasPrevious == true) add(StudioAction.RESTORE_PREVIOUS)
            if (info?.originUrl != null) add(StudioAction.RESET_DEFAULT)
            if (hasCurrent) add(StudioAction.CLEAR)
            if (hasCurrent) add(StudioAction.FILE_INFO)
        }
}

enum class StudioAction(val label: String) {
    CROP("Adjust Crop / Position"),
    RESTORE_PREVIOUS("Restore Previous"),
    RESET_DEFAULT("Reset to Scraped Default"),
    CLEAR("Clear Artwork"),
    FILE_INFO("View File Information"),
}

// Kinds where a crop frame is meaningful. ICON1 (icon-slot video snap) is included — its crop
// re-encodes the video; the rest are stills. PDF manuals and full VIDEO are not croppable.
val CROPPABLE_KINDS = setOf(
    ArtworkKind.ICON, ArtworkKind.ICON1, ArtworkKind.BOX_ART, ArtworkKind.BOX_3D,
    ArtworkKind.PHYSICAL_MEDIA, ArtworkKind.HERO, ArtworkKind.BACKGROUND, ArtworkKind.LOGO,
    ArtworkKind.SCREENSHOT, ArtworkKind.TITLESCREEN,
)

typealias StudioArtworkInfo = com.playfieldportal.feature.artwork.store.StudioArtworkInfo

private const val PAGE_SIZE = 20
private const val CROP_PAN_STEP = 0.03f

// SS media types browsable per destination (order = preference; all variants are listed).
// ICON0 has no exact SS equivalent — the landscape "mix" composites and screen-marquee come
// closest for the 144:80 tile; box art is offered as a croppable fallback.
private val SS_TYPES_FOR_KIND: Map<ArtworkKind, List<String>> = mapOf(
    ArtworkKind.ICON           to listOf("mixrbv2", "mixrbv1", "screenmarquee", "steamgrid", "box-2D"),
    ArtworkKind.BOX_ART        to listOf("box-2D"),
    ArtworkKind.BOX_3D         to listOf("box-3D"),
    ArtworkKind.PHYSICAL_MEDIA to listOf("support-2D", "support-texture"),
    ArtworkKind.HERO           to listOf("fanart", "ss"),
    ArtworkKind.BACKGROUND     to listOf("fanart", "ss", "box-2D"),
    ArtworkKind.LOGO           to listOf("wheel", "wheel-hd"),
    ArtworkKind.SCREENSHOT     to listOf("ss", "sstitle"),
    ArtworkKind.MANUAL         to listOf("manuel"),
    ArtworkKind.VIDEO          to listOf("video"),              // full gameplay video
    ArtworkKind.ICON1          to listOf("video-normalized", "video"),  // icon-slot snap
)

const val STUDIO_GRID_COLUMNS = 4

val STUDIO_TABS = listOf(
    StudioTab(ArtworkKind.ICON,           "ICON0",       "XMB tile · 144×80 · crop"),
    StudioTab(ArtworkKind.ICON1,          "ICON1",       "XMB icon animation · 60 s muted snap"),
    StudioTab(ArtworkKind.BOX_ART,        "BOX ART",     "XMB tile (Box Art mode) · natural aspect"),
    StudioTab(ArtworkKind.BOX_3D,         "3D BOX",      "XMB tile (3D Box mode) · natural aspect"),
    StudioTab(ArtworkKind.PHYSICAL_MEDIA, "PHYS. MEDIA", "XMB tile (Physical Media mode) · natural aspect"),
    StudioTab(ArtworkKind.HERO,           "HERO",        "Game Details banner · wide · crop"),
    StudioTab(ArtworkKind.BACKGROUND,     "BACKGROUND",  "XMB hover background · full screen"),
    StudioTab(ArtworkKind.LOGO,           "LOGO",        "PIC0 overlay · transparent PNG · fit"),
    StudioTab(ArtworkKind.SCREENSHOT,     "SCREENSHOT",  "Game Details media strip"),
    StudioTab(ArtworkKind.MANUAL,         "MANUAL",      "In-app PDF manual"),
    StudioTab(ArtworkKind.VIDEO,          "VIDEO",       "Game Details media strip · full video"),
)

/**
 * Fullscreen Artwork Studio (controller-first) — the single place a game's artwork is browsed
 * and changed. LB/RB switch destination tabs, L2/R2 switch sources, D-pad drives the grid,
 * A previews→applies, B backs out. Replaces the old in-detail artwork manager.
 *
 * ScreenScraper results come straight from ss_media_cache (zero API calls when cached);
 * SteamGridDB pages through the full result list with the web version's NSFW filter.
 */
@HiltViewModel
class ArtworkStudioViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val gameRepository: GameRepository,
    private val artworkStore: ArtworkStore,
    // Concrete store for the pass-2 record-driven ops (provenance, restore, reset, crop, info).
    private val routingStore: com.playfieldportal.feature.artwork.store.RoutingArtworkStore,
    private val ssMediaCatalog: com.playfieldportal.feature.artwork.api.SsMediaCatalog,
    private val steamGridDb: SteamGridDbApi,
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val theGamesDb: com.playfieldportal.feature.artwork.TheGamesDbApi,
    private val igdbApi: com.playfieldportal.feature.artwork.api.IgdbApi,
    private val videoSnapTranscoder: com.playfieldportal.feature.artwork.video.VideoSnapTranscoder,
) : ViewModel() {

    private val appCacheDir: java.io.File get() = appContext.cacheDir

    private val _uiState = MutableStateFlow(ArtworkStudioUiState())
    val uiState: StateFlow<ArtworkStudioUiState> = _uiState.asStateFlow()

    // Full (unpaged) result list for the active tab+source; the state carries one page.
    private var allResults: List<StudioArt> = emptyList()
    private var gameId: Long = -1

    // One-shot per-open caches for the single-result sources (null until first browse).
    private var tgdbInfo: com.playfieldportal.feature.artwork.TgdbGameInfo? = null
    private var tgdbFetched = false
    private var igdbInfo: com.playfieldportal.feature.artwork.api.IgdbGameInfo? = null
    private var igdbFetched = false
    private var hasIgdbCreds = false

    fun load(gameId: Long) {
        // Always clear the closed flag: the VM survives across open/close (host-scoped), so a
        // stale closed=true from a prior B-press would otherwise slam the screen shut on reopen.
        _uiState.update { it.copy(closed = false) }
        if (this.gameId == gameId && _uiState.value.game != null) return
        this.gameId = gameId
        viewModelScope.launch {
            val game = gameRepository.getById(gameId)
            val hasSgdb = !sgdbKeyProvider.getKey().isNullOrBlank()
            hasIgdbCreds = igdbApi.hasCredentials()
            tgdbFetched = false; tgdbInfo = null
            igdbFetched = false; igdbInfo = null
            _uiState.update { it.copy(game = game, isLoading = false, hasSgdbKey = hasSgdb) }
            refreshCurrent()
            loadResults()
        }
    }

    private fun tab() = STUDIO_TABS[_uiState.value.tabIndex]

    /** Sources that can actually serve the active tab's kind. */
    fun sourcesForTab(): List<StudioSource> = buildList {
        val kind = tab().kind
        if (SS_TYPES_FOR_KIND.containsKey(kind)) add(StudioSource.SCREENSCRAPER)
        if (sgdbTypeFor(kind) != null && _uiState.value.hasSgdbKey) {
            add(StudioSource.STEAMGRIDDB)
        }
        // Single-result title-match sources. ICON0 is included so the tile can be built
        // from ANY provider's art (cropped to 144:80) — maximum customization.
        val titleMatchKinds = setOf(
            ArtworkKind.ICON, ArtworkKind.BOX_ART, ArtworkKind.HERO,
            ArtworkKind.BACKGROUND, ArtworkKind.LOGO,
        )
        if (kind in titleMatchKinds) {
            add(StudioSource.THEGAMESDB)
            if (hasIgdbCreds) add(StudioSource.IGDB)
        }
        add(StudioSource.LOCAL)
    }

    private fun sgdbTypeFor(kind: ArtworkKind): SgdbArtType? = when (kind) {
        ArtworkKind.ICON    -> SgdbArtType.GRID   // all grid dimensions — pass-2 crop shapes the tile
        ArtworkKind.BOX_ART -> SgdbArtType.GRID   // 600×900 portrait grids
        ArtworkKind.HERO,
        ArtworkKind.BACKGROUND -> SgdbArtType.HERO
        ArtworkKind.LOGO    -> SgdbArtType.LOGO
        else                -> null
    }

    private suspend fun refreshCurrent() {
        val kind = tab().kind
        val current = artworkStore.find(gameId, kind) ?: when (kind) {
            ArtworkKind.ICON           -> _uiState.value.game?.iconUri
            ArtworkKind.BOX_ART        -> _uiState.value.game?.boxArtUri
            ArtworkKind.BOX_3D         -> _uiState.value.game?.box3dUri
            ArtworkKind.PHYSICAL_MEDIA -> _uiState.value.game?.physicalMediaUri
            ArtworkKind.HERO           -> _uiState.value.game?.heroUri
            ArtworkKind.BACKGROUND     -> _uiState.value.game?.artworkUri
            ArtworkKind.LOGO           -> _uiState.value.game?.logoUri
            else                       -> null
        }
        _uiState.update { it.copy(currentUri = current) }
    }

    private fun loadResults() {
        val source = sourcesForTab().getOrNull(_uiState.value.sourceIndex) ?: StudioSource.LOCAL
        val kind = tab().kind
        _uiState.update { it.copy(resultsLoading = true, results = emptyList(), gridIndex = 0, page = 0) }
        viewModelScope.launch {
            allResults = when (source) {
                StudioSource.SCREENSCRAPER -> ssResults(kind)
                StudioSource.STEAMGRIDDB   -> sgdbResults(kind)
                StudioSource.THEGAMESDB    -> tgdbResults(kind)
                StudioSource.IGDB          -> igdbResults(kind)
                StudioSource.LOCAL         -> emptyList()   // grid shows the pick action instead
            }
            _uiState.update {
                it.copy(
                    resultsLoading = false,
                    totalResults = allResults.size,
                    results = allResults.take(PAGE_SIZE),
                    page = 0,
                )
            }
        }
    }

    // Every SS media of the kind's types — cached lists load free; a game never scraped
    // gets one live scrape-as-you-go lookup (cached + ssId persisted for next time).
    private suspend fun ssResults(kind: ArtworkKind): List<StudioArt> {
        val types = SS_TYPES_FOR_KIND[kind] ?: return emptyList()
        val medias = ssMediaCatalog.mediasFor(gameId) ?: return emptyList()
        return types.flatMap { type ->
            medias.filter { it.type == type && it.url != null }.map { m ->
                StudioArt(
                    url = m.url!!,
                    thumb = null,
                    provider = "ScreenScraper",
                    label = listOfNotNull(m.type, m.region?.uppercase()).joinToString(" · "),
                    isVideo = kind == ArtworkKind.VIDEO || kind == ArtworkKind.ICON1,
                )
            }
        }
    }

    private suspend fun sgdbResults(kind: ArtworkKind): List<StudioArt> {
        val type = sgdbTypeFor(kind) ?: return emptyList()
        val game = _uiState.value.game ?: return emptyList()
        val sgdbId = game.steamGridDbId
            ?: steamGridDb.searchGame(game.displayTitle).getOrNull()?.firstOrNull()?.id
            ?: return emptyList()
        // No dimension filter, ICON0 included: every grid shape is a valid candidate now
        // that pass 2's crop editor will shape it to the tile.
        return steamGridDb.getArt(
            gameId = sgdbId,
            type = type,
            dimensions = emptyList(),
            includeNsfw = _uiState.value.includeNsfw,
        ).getOrElse {
            Timber.w(it, "SGDB browse failed")
            emptyList()
        }.map { art ->
            StudioArt(
                url = art.url,
                thumb = art.thumb,
                provider = "SteamGridDB",
                label = listOfNotNull(art.style, art.width?.let { w -> "${w}×${art.height}" })
                    .joinToString(" · "),
            )
        }
    }

    private suspend fun tgdbResults(kind: ArtworkKind): List<StudioArt> {
        val game = _uiState.value.game ?: return emptyList()
        if (!tgdbFetched) {
            tgdbFetched = true
            tgdbInfo = runCatching { theGamesDb.fetchGameInfo(game.platformId, game.displayTitle) }
                .onFailure { Timber.w(it, "TGDB browse failed") }.getOrNull()
        }
        val info = tgdbInfo ?: return emptyList()
        if (kind == ArtworkKind.ICON) {
            return listOfNotNull(
                info.artworkUrl?.let { StudioArt(it, null, "TheGamesDB", "box art · crop to tile") },
                info.heroUrl?.let { StudioArt(it, null, "TheGamesDB", "hero · crop to tile") },
            )
        }
        val url = when (kind) {
            ArtworkKind.BOX_ART                        -> info.artworkUrl
            ArtworkKind.HERO, ArtworkKind.BACKGROUND   -> info.heroUrl
            ArtworkKind.LOGO                           -> info.logoUrl
            else                                       -> null
        } ?: return emptyList()
        return listOf(StudioArt(url = url, thumb = null, provider = "TheGamesDB", label = "best title match"))
    }

    private suspend fun igdbResults(kind: ArtworkKind): List<StudioArt> {
        val game = _uiState.value.game ?: return emptyList()
        if (!igdbFetched) {
            igdbFetched = true
            igdbInfo = runCatching { igdbApi.fetchGameInfo(game.platformId, game.displayTitle) }
                .onFailure { Timber.w(it, "IGDB browse failed") }.getOrNull()
        }
        val info = igdbInfo ?: return emptyList()
        if (kind == ArtworkKind.ICON) {
            return listOfNotNull(
                info.artworkUrl?.let { StudioArt(it, null, "IGDB", "cover · crop to tile") },
                info.heroUrl?.let { StudioArt(it, null, "IGDB", "artwork · crop to tile") },
            )
        }
        val url = when (kind) {
            ArtworkKind.BOX_ART                        -> info.artworkUrl
            ArtworkKind.HERO, ArtworkKind.BACKGROUND   -> info.heroUrl
            ArtworkKind.LOGO                           -> info.logoUrl
            else                                       -> null
        } ?: return emptyList()
        return listOf(StudioArt(url = url, thumb = null, provider = "IGDB", label = "best title match"))
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun selectTab(index: Int) {
        _uiState.update { it.copy(tabIndex = index.coerceIn(0, STUDIO_TABS.lastIndex), sourceIndex = 0) }
        viewModelScope.launch { refreshCurrent() }
        loadResults()
    }

    fun cycleTab(delta: Int) = selectTab((_uiState.value.tabIndex + delta).mod(STUDIO_TABS.size))

    fun selectSource(index: Int) {
        val sources = sourcesForTab()
        val clamped = index.coerceIn(0, sources.lastIndex)
        _uiState.update { it.copy(sourceIndex = clamped) }
        if (sources[clamped] == StudioSource.LOCAL) {
            _uiState.update { it.copy(results = emptyList(), totalResults = 0) }
        } else loadResults()
    }

    fun cycleSource(delta: Int) = selectSource((_uiState.value.sourceIndex + delta).mod(sourcesForTab().size))

    fun toggleNsfw() {
        _uiState.update { it.copy(includeNsfw = !it.includeNsfw) }
        if (sourcesForTab().getOrNull(_uiState.value.sourceIndex) == StudioSource.STEAMGRIDDB) loadResults()
    }

    fun nextPage() {
        val next = _uiState.value.page + 1
        if (next * PAGE_SIZE >= allResults.size) return
        _uiState.update {
            it.copy(page = next, gridIndex = 0, results = allResults.drop(next * PAGE_SIZE).take(PAGE_SIZE))
        }
    }

    fun previousPage() {
        val prev = _uiState.value.page - 1
        if (prev < 0) return
        _uiState.update {
            it.copy(page = prev, gridIndex = 0, results = allResults.drop(prev * PAGE_SIZE).take(PAGE_SIZE))
        }
    }

    fun openCandidate(index: Int) {
        val art = _uiState.value.results.getOrNull(index) ?: return
        _uiState.update { it.copy(candidate = art, gridIndex = index) }
        // Manuals preview as a paged PDF — pull the file down first (reused by Apply).
        if (tab().kind == ArtworkKind.MANUAL) {
            _uiState.update {
                it.copy(manualDownloading = true, candidateManualPath = null, manualPage = 0, manualPageCount = 0)
            }
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val tmp = downloadToCache(art.url, ".pdf")
                _uiState.update { it.copy(manualDownloading = false, candidateManualPath = tmp?.absolutePath) }
            }
        }
    }

    /** Plain bounded download for candidate previews (no ktor dependency in this module). */
    private fun downloadToCache(url: String, suffix: String): java.io.File? = runCatching {
        val tmp = java.io.File.createTempFile("studio_", suffix, appCacheDir)
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > 60L * 1024 * 1024) error("preview download too large")
                    out.write(buf, 0, n)
                }
            }
        }
        tmp.takeIf { it.length() > 0 } ?: run { tmp.delete(); null }
    }.onFailure { Timber.w(it, "Candidate preview download failed") }.getOrNull()

    fun onManualPageCount(count: Int) = _uiState.update {
        it.copy(manualPageCount = count, manualPage = it.manualPage.coerceIn(0, (count - 1).coerceAtLeast(0)))
    }

    fun manualPreviousPage() = _uiState.update {
        it.copy(manualPage = (it.manualPage - 1).coerceAtLeast(0))
    }

    fun manualNextPage() = _uiState.update {
        it.copy(manualPage = (it.manualPage + 1).coerceAtMost((it.manualPageCount - 1).coerceAtLeast(0)))
    }

    fun requestLocalPick() = _uiState.update { it.copy(localPickKind = tab().kind) }
    fun consumeLocalPick() = _uiState.update { it.copy(localPickKind = null) }

    fun applyLocal(uri: Uri) {
        val kind = tab().kind
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true) }
            // Copy the picked document to a temp so the store can record provenance + back up.
            val tmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val suffix = "." + (appContext.contentResolver.getType(uri)?.substringAfterLast('/') ?: "bin")
                    java.io.File.createTempFile("studio_local_", suffix, appCacheDir).also { f ->
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            f.outputStream().use { input.copyTo(it) }
                        } ?: run { f.delete(); return@runCatching null }
                    }
                }.getOrNull()
            }
            val path = if (tmp != null) {
                routingStore.studioApplyFromFile(gameId, kind, tmp, provider = "Local file", originUrl = null)
            } else {
                artworkStore.saveVersionedFromUri(gameId, kind, uri)
            }
            finishApply(kind, path, "Local file")
        }
    }

    fun applyCandidate() {
        val art = _uiState.value.candidate ?: return
        val kind = tab().kind
        val manualFile = _uiState.value.candidateManualPath?.let { java.io.File(it) }
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true) }
            // A previewed manual is already on disk — store that file instead of re-downloading.
            val path = if (kind == ArtworkKind.MANUAL && manualFile?.exists() == true) {
                routingStore.studioApplyFromFile(gameId, kind, manualFile, provider = art.provider, originUrl = art.url)
            } else {
                routingStore.studioApplyFromUrl(gameId, kind, art.url, provider = art.provider)
            }
            _uiState.update { it.copy(candidateManualPath = null) }
            finishApply(kind, path, art.provider)
        }
    }

    // ── Actions menu (pass 2) ───────────────────────────────────────────────────

    /** Opens the per-slot actions menu, loading the record so availability is accurate. */
    fun openActions() {
        if (_uiState.value.currentUri == null) return
        viewModelScope.launch {
            val info = routingStore.studioInfo(gameId, tab().kind)
            _uiState.update { it.copy(info = info, actionsOpen = true, actionsIndex = 0, showFileInfo = false) }
        }
    }

    fun closeActions() = _uiState.update { it.copy(actionsOpen = false, showFileInfo = false) }

    private fun moveActionsCursor(delta: Int) = _uiState.update {
        val n = it.availableActions.size
        if (n == 0) it else it.copy(actionsIndex = (it.actionsIndex + delta).mod(n))
    }

    fun runAction(action: StudioAction) {
        when (action) {
            StudioAction.CROP             -> beginCrop()
            StudioAction.RESTORE_PREVIOUS -> restorePrevious()
            StudioAction.RESET_DEFAULT    -> resetToScrapedDefault()
            StudioAction.CLEAR            -> { closeActions(); clearCurrent() }
            StudioAction.FILE_INFO        -> _uiState.update { it.copy(showFileInfo = true) }
        }
    }

    private fun restorePrevious() {
        val kind = tab().kind
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, actionsOpen = false) }
            val path = routingStore.restorePrevious(gameId, kind)
            if (path == null) {
                _uiState.update { it.copy(applying = false, message = "No previous version to restore") }
            } else {
                repointColumn(kind, path)
                _uiState.update {
                    it.copy(applying = false, currentUri = path, previewVersion = it.previewVersion + 1,
                        message = "${tab().label} restored to previous")
                }
            }
        }
    }

    private fun resetToScrapedDefault() {
        val kind = tab().kind
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, actionsOpen = false) }
            val path = routingStore.resetToScrapedDefault(gameId, kind)
            if (path == null) {
                _uiState.update { it.copy(applying = false, message = "Could not re-download the scraped default") }
            } else {
                repointColumn(kind, path)
                _uiState.update {
                    it.copy(applying = false, currentUri = path, previewVersion = it.previewVersion + 1,
                        message = "${tab().label} reset to scraped default")
                }
            }
        }
    }

    // ── Crop / position editor (pass 2) ─────────────────────────────────────────

    /** Loads the untouched original to a temp file and opens the crop editor over it. For
     *  ICON1 the original is a video: a still frame is extracted for framing and the video is
     *  kept for re-encoding on apply. */
    private fun beginCrop() {
        val kind = tab().kind
        viewModelScope.launch {
            _uiState.update { it.copy(actionsOpen = false, cropPreparing = true) }
            val original = routingStore.originalToTemp(gameId, kind)
            if (original == null) {
                _uiState.update { it.copy(cropPreparing = false, message = "Could not open the original to crop") }
                return@launch
            }
            val prepared = withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (isVideoKind(kind)) {
                    val frame = extractVideoFrame(original)
                    if (frame == null) { original.delete(); null }
                    else Triple(frame.first.absolutePath, original.absolutePath, frame.second to frame.third)
                } else {
                    val (w, h) = decodeBounds(original)
                    Triple(original.absolutePath, null, w to h)
                }
            }
            if (prepared == null) {
                _uiState.update { it.copy(cropPreparing = false, message = "Could not open the original to crop") }
                return@launch
            }
            val (displayPath, videoPath, dims) = prepared
            val seed = _uiState.value.info?.cropRect?.let { parseCropRect(it) }
            _uiState.update {
                it.copy(
                    cropPreparing = false, cropEditorPath = displayPath, cropVideoSourcePath = videoPath,
                    cropSrcW = dims.first, cropSrcH = dims.second, cropZoom = 1f,
                    cropCenterX = seed?.let { r -> (r[0] + r[2]) / 2f } ?: 0.5f,
                    cropCenterY = seed?.let { r -> (r[1] + r[3]) / 2f } ?: 0.5f,
                )
            }
            recomputeCropRect()
        }
    }

    private fun isVideoKind(kind: ArtworkKind) = kind == ArtworkKind.ICON1 || kind == ArtworkKind.VIDEO

    private fun cropTargetAspect(kind: ArtworkKind, srcAspect: Float): Float = when (kind) {
        ArtworkKind.ICON,
        ArtworkKind.ICON1      -> 144f / 80f     // XMB tile container
        ArtworkKind.HERO       -> 920f / 430f
        ArtworkKind.BACKGROUND -> 16f / 9f
        else                   -> srcAspect        // free crop: keep the source's proportions
    }

    /** First video frame as a PNG temp + its (w, h), for the crop editor's framing preview. */
    private fun extractVideoFrame(video: java.io.File): Triple<java.io.File, Int, Int>? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val out = java.io.File.createTempFile("studio_frame_", ".png", appCacheDir)
            out.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val dims = Triple(out, frame.width, frame.height)
            frame.recycle()
            dims
        } finally {
            runCatching { retriever.release() }
        }
    }.onFailure { Timber.w(it, "Video frame extraction failed") }.getOrNull()

    /** Recomputes the normalized crop window from zoom/center + per-kind aspect, clamped inside. */
    private fun recomputeCropRect() = _uiState.update { s ->
        if (s.cropSrcW <= 0 || s.cropSrcH <= 0) return@update s
        val srcAspect = s.cropSrcW.toFloat() / s.cropSrcH
        val target = cropTargetAspect(tab().kind, srcAspect)
        // Largest target-aspect window fitting the source at zoom=1, then shrunk by zoom.
        var wN: Float; var hN: Float
        if (target >= srcAspect) { wN = 1f; hN = srcAspect / target } else { hN = 1f; wN = target / srcAspect }
        wN /= s.cropZoom; hN /= s.cropZoom
        val cx = s.cropCenterX.coerceIn(wN / 2f, 1f - wN / 2f)
        val cy = s.cropCenterY.coerceIn(hN / 2f, 1f - hN / 2f)
        s.copy(
            cropCenterX = cx, cropCenterY = cy,
            cropL = cx - wN / 2f, cropT = cy - hN / 2f, cropR = cx + wN / 2f, cropB = cy + hN / 2f,
        )
    }

    fun panCrop(dx: Float, dy: Float) {
        _uiState.update { it.copy(cropCenterX = (it.cropCenterX + dx), cropCenterY = (it.cropCenterY + dy)) }
        recomputeCropRect()
    }

    fun zoomCrop(factor: Float) {
        _uiState.update { it.copy(cropZoom = (it.cropZoom * factor).coerceIn(1f, 6f)) }
        recomputeCropRect()
    }

    /** Bakes the current crop window and stores it — a PNG region for stills, a re-encoded clip
     *  for ICON1 videos — keeping the untouched original for future re-crops. */
    fun applyCrop() {
        val kind = tab().kind
        val s = _uiState.value
        val displayPath = s.cropEditorPath ?: return
        val videoPath = s.cropVideoSourcePath
        val l = s.cropL; val t = s.cropT; val r = s.cropR; val b = s.cropB
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, cropEditorPath = null, cropVideoSourcePath = null) }
            val baked = if (videoPath != null) {
                // ICON1: re-encode the video cropped to the frame (Media3 Transformer + Crop).
                val out = java.io.File.createTempFile("studio_crop_", ".mp4", appCacheDir)
                val ok = videoSnapTranscoder.transcodeCropped(java.io.File(videoPath), out, l, t, r, b)
                java.io.File(videoPath).delete()
                if (ok) out else { out.delete(); null }
            } else {
                withContext(kotlinx.coroutines.Dispatchers.IO) { bakeCrop(java.io.File(displayPath), l, t, r, b) }
            }
            java.io.File(displayPath).delete()
            if (baked == null) {
                _uiState.update { it.copy(applying = false, message = "Crop failed") }
                return@launch
            }
            val rect = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, l, t, r, b)
            val path = routingStore.saveCropBaked(gameId, kind, baked, rect)
            if (path == null) {
                _uiState.update { it.copy(applying = false, message = "Could not save the cropped artwork") }
            } else {
                repointColumn(kind, path)
                _uiState.update {
                    it.copy(applying = false, currentUri = path, previewVersion = it.previewVersion + 1,
                        message = "${tab().label} cropped")
                }
            }
        }
    }

    fun cancelCrop() {
        val s = _uiState.value
        s.cropEditorPath?.let { runCatching { java.io.File(it).delete() } }
        s.cropVideoSourcePath?.let { runCatching { java.io.File(it).delete() } }
        _uiState.update { it.copy(cropEditorPath = null, cropVideoSourcePath = null) }
    }

    private fun decodeBounds(file: java.io.File): Pair<Int, Int> {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        return (opts.outWidth.takeIf { it > 0 } ?: 1) to (opts.outHeight.takeIf { it > 0 } ?: 1)
    }

    private fun parseCropRect(s: String): FloatArray? =
        s.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 4 }?.toFloatArray()

    /** Decodes [src], crops the normalized rect, returns a PNG temp (lossless, keeps alpha). */
    private fun bakeCrop(src: java.io.File, left: Float, top: Float, right: Float, bottom: Float): java.io.File? =
        runCatching {
            val full = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return null
            val w = full.width; val h = full.height
            val x = (left * w).toInt().coerceIn(0, w - 1)
            val y = (top * h).toInt().coerceIn(0, h - 1)
            val cw = ((right - left) * w).toInt().coerceIn(1, w - x)
            val ch = ((bottom - top) * h).toInt().coerceIn(1, h - y)
            val cropped = android.graphics.Bitmap.createBitmap(full, x, y, cw, ch)
            if (cropped != full) full.recycle()
            val out = java.io.File.createTempFile("studio_crop_", ".png", appCacheDir)
            out.outputStream().use { cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            cropped.recycle()
            out.takeIf { it.length() > 0 } ?: run { out.delete(); null }
        }.onFailure { Timber.w(it, "bakeCrop failed") }.getOrNull()

    /** Repoints the column-backed game row for [kind] to [path]; record-only kinds no-op. */
    private suspend fun repointColumn(kind: ArtworkKind, path: String?) {
        when (kind) {
            ArtworkKind.ICON           -> gameRepository.updateIconArt(gameId, path)
            ArtworkKind.BOX_ART        -> gameRepository.updateBoxArtTile(gameId, path)
            ArtworkKind.BOX_3D         -> gameRepository.updateBox3dArt(gameId, path)
            ArtworkKind.PHYSICAL_MEDIA -> gameRepository.updatePhysicalMediaArt(gameId, path)
            ArtworkKind.HERO           -> gameRepository.updateHeroArt(gameId, path)
            ArtworkKind.BACKGROUND     -> gameRepository.updateBoxArt(gameId, path)
            ArtworkKind.LOGO           -> gameRepository.updateLogoArt(gameId, path)
            else                       -> Unit
        }
    }

    private suspend fun finishApply(kind: ArtworkKind, path: String?, provider: String) {
        if (path == null) {
            _uiState.update { it.copy(applying = false, message = "Could not apply — download or file was rejected") }
            return
        }
        // Column-backed kinds repoint the game row; record-only kinds resolve by fixed name.
        repointColumn(kind, path)
        val game = gameRepository.getById(gameId)
        _uiState.update {
            it.copy(
                game = game,
                applying = false,
                candidate = null,
                currentUri = path,
                previewVersion = it.previewVersion + 1,
                message = "${tab().label} updated from $provider",
            )
        }
    }

    fun clearCurrent() {
        val kind = tab().kind
        viewModelScope.launch {
            // Delete the stored file, its backup + original, and the record; then unwire the column.
            routingStore.clearArtwork(gameId, kind)
            repointColumn(kind, null)
            _uiState.update {
                it.copy(currentUri = null, info = null, previewVersion = it.previewVersion + 1,
                    message = "${tab().label} cleared")
            }
        }
    }

    fun dismissCandidate() {
        _uiState.value.candidateManualPath?.let { runCatching { java.io.File(it).delete() } }
        _uiState.update {
            it.copy(candidate = null, candidateManualPath = null, manualDownloading = false, manualPage = 0, manualPageCount = 0)
        }
    }
    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun close() = _uiState.update { it.copy(closed = true) }

    /** The screen calls this right after acting on [ArtworkStudioUiState.closed] so a stale
     *  closed=true never survives to instantly re-close the screen on the next open. */
    fun consumeClosed() = _uiState.update { it.copy(closed = false) }

    // ── Controller ────────────────────────────────────────────────────────────

    fun handleGamepadAction(action: GamepadAction) {
        val s = _uiState.value
        // Crop editor: D-pad pans, LB/RB zoom out/in, A bakes, B cancels.
        if (s.cropEditorPath != null) {
            when (action) {
                GamepadAction.NAVIGATE_LEFT  -> panCrop(-CROP_PAN_STEP, 0f)
                GamepadAction.NAVIGATE_RIGHT -> panCrop(CROP_PAN_STEP, 0f)
                GamepadAction.NAVIGATE_UP    -> panCrop(0f, -CROP_PAN_STEP)
                GamepadAction.NAVIGATE_DOWN  -> panCrop(0f, CROP_PAN_STEP)
                GamepadAction.NEXT_CATEGORY  -> zoomCrop(1.1f)   // RB — zoom in
                GamepadAction.PREV_CATEGORY  -> zoomCrop(1f / 1.1f)   // LB — zoom out
                GamepadAction.SELECT         -> applyCrop()
                GamepadAction.BACK           -> cancelCrop()
                else -> Unit
            }
            return
        }
        if (s.actionsOpen) {
            val actions = s.availableActions
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveActionsCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveActionsCursor(+1)
                GamepadAction.SELECT        -> actions.getOrNull(s.actionsIndex)?.let { runAction(it) }
                GamepadAction.BACK          ->
                    if (s.showFileInfo) _uiState.update { it.copy(showFileInfo = false) } else closeActions()
                else -> Unit
            }
            return
        }
        if (s.candidate != null) {
            when (action) {
                GamepadAction.SELECT -> applyCandidate()
                GamepadAction.BACK   -> dismissCandidate()
                // Manual preview pages with Left/Right before applying.
                GamepadAction.NAVIGATE_LEFT  -> if (s.candidateManualPath != null) manualPreviousPage()
                GamepadAction.NAVIGATE_RIGHT -> if (s.candidateManualPath != null) manualNextPage()
                else -> Unit
            }
            return
        }
        when (action) {
            GamepadAction.PREV_CATEGORY -> cycleTab(-1)   // LB / RB
            GamepadAction.NEXT_CATEGORY -> cycleTab(+1)
            GamepadAction.BACK -> close()
            GamepadAction.NAVIGATE_UP -> when (s.zone) {
                // Grid moves a whole ROW up; only the top row exits to the source row.
                StudioZone.GRID ->
                    if (s.gridIndex >= STUDIO_GRID_COLUMNS) {
                        _uiState.update { it.copy(gridIndex = s.gridIndex - STUDIO_GRID_COLUMNS) }
                    } else _uiState.update { it.copy(zone = StudioZone.SOURCES) }
                else -> _uiState.update { it.copy(zone = StudioZone.TABS) }
            }
            GamepadAction.NAVIGATE_DOWN -> when (s.zone) {
                StudioZone.TABS -> _uiState.update { it.copy(zone = StudioZone.SOURCES) }
                StudioZone.SOURCES -> _uiState.update { it.copy(zone = StudioZone.GRID) }
                // Grid moves a whole ROW down; past the last row flips to the next page.
                StudioZone.GRID ->
                    if (s.gridIndex + STUDIO_GRID_COLUMNS <= s.results.lastIndex) {
                        _uiState.update { it.copy(gridIndex = s.gridIndex + STUDIO_GRID_COLUMNS) }
                    } else nextPage()
            }
            GamepadAction.NAVIGATE_LEFT -> when (s.zone) {
                StudioZone.TABS    -> cycleTab(-1)
                StudioZone.SOURCES -> cycleSource(-1)
                StudioZone.GRID    ->
                    if (s.gridIndex > 0) _uiState.update { it.copy(gridIndex = s.gridIndex - 1) }
                    else previousPage()
            }
            GamepadAction.NAVIGATE_RIGHT -> when (s.zone) {
                StudioZone.TABS    -> cycleTab(+1)
                StudioZone.SOURCES -> cycleSource(+1)
                StudioZone.GRID    ->
                    if (s.gridIndex < s.results.lastIndex) _uiState.update { it.copy(gridIndex = s.gridIndex + 1) }
                    else nextPage()
            }
            GamepadAction.SELECT -> when (s.zone) {
                StudioZone.TABS    -> _uiState.update { it.copy(zone = StudioZone.GRID) }
                StudioZone.SOURCES ->
                    if (sourcesForTab().getOrNull(s.sourceIndex) == StudioSource.LOCAL) requestLocalPick()
                    else _uiState.update { it.copy(zone = StudioZone.GRID) }
                StudioZone.GRID    ->
                    if (sourcesForTab().getOrNull(s.sourceIndex) == StudioSource.LOCAL) requestLocalPick()
                    else openCandidate(s.gridIndex)
            }
            // X / Square toggles the SGDB NSFW filter while browsing that source.
            GamepadAction.CHANGE_SORT, GamepadAction.OPEN_TASK_TRAY -> toggleNsfw()
            // Y / Triangle jumps the cursor straight to the source row (lands on the
            // active source, since sourceIndex already tracks it).
            GamepadAction.BUTTON_Y -> _uiState.update { it.copy(zone = StudioZone.SOURCES) }
            // Long-press opens the per-slot actions menu (crop, restore, reset, clear, info).
            GamepadAction.LONG_PRESS -> openActions()
            else -> Unit
        }
    }
}
