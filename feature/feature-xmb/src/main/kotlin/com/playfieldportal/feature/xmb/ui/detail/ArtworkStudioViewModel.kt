package com.playfieldportal.feature.xmb.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.SsMediaCacheDao
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.api.SsMediaSelection
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val closed: Boolean = false,
)

private const val PAGE_SIZE = 20

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
    private val ssMediaCacheDao: SsMediaCacheDao,
    private val steamGridDb: SteamGridDbApi,
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val theGamesDb: com.playfieldportal.feature.artwork.TheGamesDbApi,
    private val igdbApi: com.playfieldportal.feature.artwork.api.IgdbApi,
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
        ArtworkKind.ICON    -> SgdbArtType.GRID   // horizontal capsules, filtered by dimension
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

    // Every cached SS media of the kind's types — all regions/variants, zero API calls.
    private suspend fun ssResults(kind: ArtworkKind): List<StudioArt> {
        val ssId = _uiState.value.game?.ssId ?: return emptyList()
        val row = ssMediaCacheDao.get(ssId) ?: return emptyList()
        val medias = SsMediaSelection.decode(row.mediasJson) ?: return emptyList()
        val types = SS_TYPES_FOR_KIND[kind] ?: return emptyList()
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
        val dimensions = if (kind == ArtworkKind.ICON) listOf("920x430", "460x215") else emptyList()
        return steamGridDb.getArt(
            gameId = sgdbId,
            type = type,
            dimensions = dimensions,
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
            val path = artworkStore.saveVersionedFromUri(gameId, kind, uri)
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
                artworkStore.saveFromFile(gameId, kind, manualFile)   // consumes the temp file
            } else {
                artworkStore.saveVersionedFromUrl(gameId, kind, art.url)
            }
            _uiState.update { it.copy(candidateManualPath = null) }
            finishApply(kind, path, art.provider)
        }
    }

    private suspend fun finishApply(kind: ArtworkKind, path: String?, provider: String) {
        if (path == null) {
            _uiState.update { it.copy(applying = false, message = "Could not apply — download or file was rejected") }
            return
        }
        // Column-backed kinds repoint the game row; record-only kinds resolve by fixed name.
        when (kind) {
            ArtworkKind.ICON           -> gameRepository.updateIconArt(gameId, path)
            ArtworkKind.BOX_ART        -> gameRepository.updateBoxArtTile(gameId, path)
            ArtworkKind.BOX_3D         -> gameRepository.updateBox3dArt(gameId, path)
            ArtworkKind.PHYSICAL_MEDIA -> gameRepository.updatePhysicalMediaArt(gameId, path)
            ArtworkKind.HERO           -> gameRepository.updateHeroArt(gameId, path)
            ArtworkKind.BACKGROUND     -> gameRepository.updateBoxArt(gameId, path)   // legacy name = background column
            ArtworkKind.LOGO           -> gameRepository.updateLogoArt(gameId, path)
            else                       -> Unit
        }
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
            when (kind) {
                ArtworkKind.ICON           -> gameRepository.updateIconArt(gameId, null)
                ArtworkKind.BOX_ART        -> gameRepository.updateBoxArtTile(gameId, null)
                ArtworkKind.BOX_3D         -> gameRepository.updateBox3dArt(gameId, null)
                ArtworkKind.PHYSICAL_MEDIA -> gameRepository.updatePhysicalMediaArt(gameId, null)
                ArtworkKind.HERO           -> gameRepository.updateHeroArt(gameId, null)
                ArtworkKind.BACKGROUND     -> gameRepository.updateBoxArt(gameId, null)
                ArtworkKind.LOGO           -> gameRepository.updateLogoArt(gameId, null)
                else                       -> Unit
            }
            _uiState.update {
                it.copy(currentUri = null, previewVersion = it.previewVersion + 1, message = "${tab().label} cleared")
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
            else -> Unit
        }
    }
}
