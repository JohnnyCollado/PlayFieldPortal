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
import com.playfieldportal.feature.artwork.match.CachingMatchEvidence
import com.playfieldportal.feature.artwork.match.GameCandidate
import com.playfieldportal.feature.artwork.match.GameMatch
import com.playfieldportal.feature.artwork.match.GameMatcher
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.MatchTier
import com.playfieldportal.feature.artwork.match.ProviderCapabilities
import com.playfieldportal.feature.artwork.match.ProviderMatchEvidence
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

// ── Studio model ──────────────────────────────────────────────────────────────

/**
 * One artwork destination tab. [contract] is the display rule shown under the tab bar; [tileClass]
 * is the shape its results are judged at, which decides how many fit on a page.
 */
data class StudioTab(
    val kind: ArtworkKind,
    val label: String,
    val contract: String,
    val tileClass: StudioTileClass,
)

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

// Navigation levels, strictly hierarchical: confirm descends, back ascends, left/right acts on
// the current level only. TABS (categories) → SOURCES → GRID.
enum class StudioZone { TABS, SOURCES, GRID }

data class ArtworkStudioUiState(
    val game: Game? = null,
    val isLoading: Boolean = true,
    val tabIndex: Int = 0,
    val sourceIndex: Int = 0,
    val zone: StudioZone = StudioZone.TABS,
    val gridIndex: Int = 0,
    // One page is one measured gridful (AD-17). 4 × 5 until the screen reports the slot's size.
    val gridColumns: Int = StudioGridCapacity.UNMEASURED.columns,
    val gridRows: Int = StudioGridCapacity.UNMEASURED.rows,
    val page: Int = 0,
    val pageCount: Int = 0,
    // 1-based inclusive range of the visible page within the whole result list ("21–40 of 137").
    val rangeStart: Int = 0,
    val rangeEnd: Int = 0,
    val results: List<StudioArt> = emptyList(),
    val totalResults: Int = 0,
    val resultsLoading: Boolean = false,
    // ── Search (C16 task 1.1) ────────────────────────────────────────────────
    // The query the visible results were fetched for. Seeded from the game's title and freely
    // editable; editing it NEVER renames the game — it only changes what the providers are asked.
    val query: String = "",
    // What is in the text field while the search overlay is open, before it is submitted.
    val queryDraft: String = "",
    val searchOpen: Boolean = false,
    // True while the active query differs from the game's own title — drives the "Reset" affordance.
    val queryIsCustom: Boolean = false,
    // ── Game match (C16 task 2.3) ────────────────────────────────────────────
    // Who the active source thinks this game is. Null means "not matched" — a dead end today,
    // and what Change Match exists to fix. Recomputed whenever the active source changes, since
    // a match belongs to ONE provider and is never read across providers.
    val match: GameMatch? = null,
    val matchProvider: MatchProvider? = null,
    val matchResolving: Boolean = false,
    // The last resolution failed because the provider didn't answer, as opposed to finding no match.
    val matchFailed: Boolean = false,
    // Change Match picker, backed by each provider's multi-result title search.
    val changeMatchOpen: Boolean = false,
    val changeMatchDraft: String = "",
    val changeMatchLoading: Boolean = false,
    val changeMatchResults: List<GameCandidate> = emptyList(),
    // -1 is the query field; 0..results.lastIndex are the candidates.
    val changeMatchIndex: Int = -1,
    // True only while the query field is being typed into — the one time the keyboard is open.
    val changeMatchEditing: Boolean = false,
    // The candidates include platforms other than the game's own.
    val changeMatchAcrossPlatforms: Boolean = false,
    // The picker is waiting on ScreenScraper's every-platform search, which takes about ten seconds.
    val changeMatchSearchingEveryPlatform: Boolean = false,
    // Set when the picker's search failed, as opposed to finding nothing.
    val changeMatchError: String? = null,
    // Current asset of the active tab (what the game uses right now).
    val currentUri: String? = null,
    // Bumped on every apply/clear so the preview reloads even when the portable library reuses
    // a stable content URI (same string → Coil would otherwise serve the old bytes).
    val previewVersion: Int = 0,
    val includeNsfw: Boolean = false,
    val hasSgdbKey: Boolean = false,
    // Keyed providers with no key/credentials: still listed, drawn disabled, skipped by source
    // cycling, and never asked. Re-read on every open so a key added in Settings takes effect.
    val unavailableSources: Set<StudioSource> = emptySet(),
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
    // Actions menu (OPEN_CONTEXT_MENU / on-screen ACTIONS) — operates on the active tab's current slot.
    val actionsOpen: Boolean = false,
    val actionsIndex: Int = 0,
    // Whether the menu was opened over a SteamGridDB browse — gates the mature-content entry.
    val sgdbSourceActive: Boolean = false,
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
    /**
     * Placeholder tiles to draw while an uncached page is in flight. A full gridful whenever
     * loading — the screen renders skeletons instead of the grid in that state, so tying this to
     * `results.isEmpty()` would draw an empty panel if the two ever disagreed.
     */
    val skeletonCount: Int get() = if (resultsLoading) pageSize else 0

    val pageSize: Int get() = gridColumns * gridRows

    /** "Matched as <title>" — the game the active source is actually being asked about. */
    val matchTitle: String? get() = match?.candidate?.title

    /** The chip beside it: a match the user picked outranks one the matcher derived. */
    val matchIsConfirmed: Boolean get() = match?.userConfirmed == true

    /**
     * Whether a Change Match picker can be offered at all. Only a provider with a multi-result
     * title search has anything to pick FROM. Since C16 Merge 3 that is every provider, but
     * [ProviderCapabilities] stays the switch rather than this property assuming it.
     */
    val canChangeMatch: Boolean
        get() = matchProvider?.let { ProviderCapabilities[it].supportsTitleSearch } == true

    val hasPreviousPage: Boolean get() = page > 0
    val hasNextPage: Boolean get() = page < pageCount - 1

    /**
     * Actions for the current slot and then the active source, in menu order. Entries that do not
     * apply are hidden.
     */
    val availableActions: List<StudioAction>
        get() = buildList {
            val kind = STUDIO_TABS.getOrNull(tabIndex)?.kind
            val hasCurrent = currentUri != null
            if (hasCurrent && kind != null && kind in CROPPABLE_KINDS) add(StudioAction.CROP)
            if (info?.hasPrevious == true) add(StudioAction.RESTORE_PREVIOUS)
            if (info?.originUrl != null) add(StudioAction.RESET_DEFAULT)
            if (hasCurrent) add(StudioAction.CLEAR)
            if (hasCurrent) add(StudioAction.FILE_INFO)
            // Mature content is a SteamGridDB browse filter, so it belongs to that source's
            // context menu — not to a global button that used to fire on every screen (task 1.3).
            if (sgdbSourceActive) add(StudioAction.TOGGLE_MATURE)
            // The match row's two buttons are touch targets with no controller path, so they are
            // offered here too (task 2.4), under exactly the row's own visibility rules.
            if (matchProvider != null) add(StudioAction.CHANGE_MATCH)
            if (matchIsConfirmed) add(StudioAction.FORGET_MATCH)
        }
}

enum class StudioAction(val label: String) {
    CROP("Adjust Crop / Position"),
    RESTORE_PREVIOUS("Restore Previous"),
    RESET_DEFAULT("Reset to Scraped Default"),
    CLEAR("Clear Artwork"),
    FILE_INFO("View File Information"),
    TOGGLE_MATURE("Mature Content (SteamGridDB)"),
    CHANGE_MATCH("Change Match"),
    FORGET_MATCH("Forget Match"),
}

// Kinds where a crop frame is meaningful. ICON1 (icon-slot video snap) is included — its crop
// re-encodes the video; the rest are stills. PDF manuals and full VIDEO are not croppable.
val CROPPABLE_KINDS = setOf(
    ArtworkKind.ICON, ArtworkKind.ICON1, ArtworkKind.BOX_ART, ArtworkKind.BOX_3D,
    ArtworkKind.PHYSICAL_MEDIA, ArtworkKind.HERO, ArtworkKind.BACKGROUND, ArtworkKind.LOGO,
    ArtworkKind.SCREENSHOT, ArtworkKind.TITLESCREEN,
)

typealias StudioArtworkInfo = com.playfieldportal.feature.artwork.store.StudioArtworkInfo

private const val CROP_PAN_STEP = 0.03f

// Long enough for any real title with edition and subtitle; short enough that a pasted wall of
// text can never become a provider query.
private const val MAX_QUERY_LENGTH = 120

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

// Tabs SteamGridDB, TheGamesDB and IGDB have nothing for: all three are image providers, and none
// offers an icon-slot snap, a PDF manual or a gameplay video. Listed but disabled there.
private val NO_IMAGE_PROVIDER_KINDS = setOf(ArtworkKind.ICON1, ArtworkKind.MANUAL, ArtworkKind.VIDEO)

// Tabs with no provider art type of their own. Rather than hide a provider there, it offers every
// image it has for the game and the crop editor shapes the pick (user decision, 2026-09-10).
private val SHOW_ALL_ART_KINDS = setOf(ArtworkKind.BOX_3D, ArtworkKind.PHYSICAL_MEDIA, ArtworkKind.SCREENSHOT)

val STUDIO_TABS = listOf(
    StudioTab(ArtworkKind.ICON,           "ICON0",       "XMB tile · 144×80 · crop",                       StudioTileClass.LANDSCAPE),
    StudioTab(ArtworkKind.ICON1,          "ICON1",       "XMB icon animation · 60 s muted snap",           StudioTileClass.LANDSCAPE),
    StudioTab(ArtworkKind.BOX_ART,        "BOX ART",     "XMB tile (Box Art mode) · natural aspect",       StudioTileClass.PORTRAIT),
    StudioTab(ArtworkKind.BOX_3D,         "3D BOX",      "XMB tile (3D Box mode) · natural aspect",        StudioTileClass.PORTRAIT),
    StudioTab(ArtworkKind.PHYSICAL_MEDIA, "PHYS. MEDIA", "XMB tile (Physical Media mode) · natural aspect", StudioTileClass.SQUARE),
    StudioTab(ArtworkKind.HERO,           "HERO",        "Game Details banner · wide · crop",              StudioTileClass.LANDSCAPE),
    StudioTab(ArtworkKind.BACKGROUND,     "BACKGROUND",  "XMB hover background · full screen",             StudioTileClass.LANDSCAPE),
    StudioTab(ArtworkKind.LOGO,           "LOGO",        "PIC0 overlay · transparent PNG · fit",           StudioTileClass.WIDE),
    StudioTab(ArtworkKind.SCREENSHOT,     "SCREENSHOT",  "Game Details media strip",                       StudioTileClass.LANDSCAPE),
    StudioTab(ArtworkKind.MANUAL,         "MANUAL",      "In-app PDF manual",                              StudioTileClass.PORTRAIT),
    StudioTab(ArtworkKind.VIDEO,          "VIDEO",       "Game Details media strip · full video",          StudioTileClass.LANDSCAPE),
)

/**
 * Fullscreen Artwork Studio (controller-first) — the single place a game's artwork is browsed
 * and changed. LB/RB switch destination tabs, Left/Right act on the current level, D-pad drives
 * the grid, A previews→applies, B backs out, X opens search, Y opens the per-slot options.
 * Replaces the old in-detail artwork manager.
 *
 * (L2/R2 are unbound: no GamepadAction maps to KEYCODE_BUTTON_L2/R2 in GamepadBinding, so the
 * old "L2/R2 switch sources" line here described a binding that never existed.)
 *
 * ScreenScraper results come straight from ss_media_cache (zero API calls when cached);
 * SteamGridDB pages through the full result list with the web version's mature filter.
 *
 * Every browse goes through one keyed, generation-guarded path (see [loadResults]) — that is
 * what stops a slow response from an old source repainting the grid of a new one.
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
    private val matchEvidence: ProviderMatchEvidence,
) : ViewModel(), ArtworkStudioActions {

    /**
     * Title searches remembered for this open. The matcher re-resolves on every source switch, tab
     * switch and search, and without this each one asked the provider again.
     */
    private val titleSearches = CachingMatchEvidence(matchEvidence)

    /** Tiers 1-3 only; the ranked picker below Tier 3 is deferred (AD-4). */
    private val matcher = GameMatcher(titleSearches)

    private val appCacheDir: java.io.File get() = appContext.cacheDir

    private val _uiState = MutableStateFlow(ArtworkStudioUiState())
    val uiState: StateFlow<ArtworkStudioUiState> = _uiState.asStateFlow()

    // One finished result list per request key. Replaces the single `allResults` field, whose
    // sharing was the disappearing-artwork bug: any late response overwrote whatever was on
    // screen. A response can now only ever be stored under its OWN key (AD-6).
    private val resultCache = StudioResultCache()

    /** The key the visible grid belongs to. A response for any other key is dropped. */
    private var activeKey: StudioRequestKey? = null

    /**
     * Monotonic request token. Incremented on every browse; a response may reduce into state only
     * if its token is still the current one AND its key still matches. Two independent checks,
     * because a user can return to a key while its first request is still in flight.
     */
    private var generation: Long = 0

    /** The in-flight browse, cancelled the moment another one starts. */
    private var loadJob: kotlinx.coroutines.Job? = null

    private var gameId: Long = -1

    /** The grid slot's last reported size in dp; null until the screen has measured it. */
    private var gridSlotDp: Pair<Float, Float>? = null

    fun load(gameId: Long) {
        // Always clear the closed flag: the VM survives across open/close (host-scoped), so a
        // stale closed=true from a prior B-press would otherwise slam the screen shut on reopen.
        // Every open starts at Level 1 (categories).
        _uiState.update { it.copy(closed = false, zone = StudioZone.TABS) }
        // Each open asks the providers afresh: a search that failed last time (providers report a
        // failure as no hits) must not keep the game unmatched for good.
        titleSearches.clear()
        if (this.gameId == gameId && _uiState.value.game != null) {
            // Same game reopened. The VM outlives the screen, so a key added or removed in Settings
            // since the last open has to be re-read here — reading it once per game is what kept a
            // freshly entered TheGamesDB key from ever taking effect.
            viewModelScope.launch {
                val before = _uiState.value.unavailableSources
                refreshProviderAvailability()
                if (_uiState.value.unavailableSources != before) {
                    landOnAvailableSource()
                    resolveMatch()
                    loadResults()
                }
            }
            return
        }
        this.gameId = gameId
        viewModelScope.launch {
            val game = gameRepository.getById(gameId)
            refreshProviderAvailability()
            resultCache.clear()
            // The query starts as the game's title and is the user's from then on.
            val seed = game?.displayTitle.orEmpty()
            _uiState.update {
                it.copy(
                    game = game, isLoading = false,
                    query = seed, queryDraft = seed, queryIsCustom = false,
                )
            }
            landOnAvailableSource()
            refreshCurrent()
            resolveMatch()
            loadResults()
        }
    }

    /** The game's own title — what Reset returns the query to, and what "custom" is measured against. */
    private fun gameTitle(): String = _uiState.value.game?.displayTitle.orEmpty()

    private fun tab() = STUDIO_TABS[_uiState.value.tabIndex]

    /**
     * Every source, on every tab (user decision, 2026-09-10), so the row keeps one shape as the
     * user walks the categories. A source that has nothing for the tab ([servesKind]) or has no
     * key ([ArtworkStudioUiState.unavailableSources]) is drawn disabled and skipped, never removed.
     */
    override fun sourcesForTab(): List<StudioSource> = StudioSource.entries

    /**
     * Whether [source] has anything at all for [kind]. SteamGridDB, TheGamesDB and IGDB are image
     * providers with no icon-slot snap, manual or gameplay video; ScreenScraper covers every tab.
     */
    private fun servesKind(source: StudioSource, kind: ArtworkKind): Boolean = when (source) {
        StudioSource.SCREENSCRAPER -> SS_TYPES_FOR_KIND.containsKey(kind)
        StudioSource.STEAMGRIDDB,
        StudioSource.THEGAMESDB,
        StudioSource.IGDB          -> kind !in NO_IMAGE_PROVIDER_KINDS
        StudioSource.LOCAL         -> true
    }

    private fun sgdbTypesFor(kind: ArtworkKind): List<SgdbArtType> = when (kind) {
        ArtworkKind.ICON    -> listOf(SgdbArtType.GRID)   // all grid dimensions — pass-2 crop shapes the tile
        ArtworkKind.BOX_ART -> listOf(SgdbArtType.GRID)   // 600×900 portrait grids
        ArtworkKind.HERO,
        ArtworkKind.BACKGROUND -> listOf(SgdbArtType.HERO)
        ArtworkKind.LOGO    -> listOf(SgdbArtType.LOGO)
        in SHOW_ALL_ART_KINDS -> SgdbArtType.entries
        else                -> emptyList()
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

    /**
     * Browses the active category + source for the active query.
     *
     * Race safety is coroutine ownership, never a delay (AD-6):
     *  1. the previous browse is cancelled outright;
     *  2. the request carries an immutable key and a monotonic token;
     *  3. the response may write to state only while BOTH still match.
     *
     * A cache hit renders immediately with no loading state at all; a miss clears the grid and
     * shows skeletons, so a source switch can never leave another provider's tiles on screen.
     */
    private fun loadResults() {
        val state = _uiState.value
        val source = sourcesForTab().getOrNull(state.sourceIndex) ?: StudioSource.LOCAL
        val kind = tab().kind
        // The confirmed match is part of the key, so re-pointing the game at another provider
        // entry invalidates exactly its own cached pages and nothing else (Phase 1 left the field
        // in place for precisely this).
        val key = StudioRequestKey.of(state.query, source, kind, state.includeNsfw, state.match?.matchKey)

        loadJob?.cancel()
        activeKey = key
        val token = ++generation

        resultCache[key]?.let { cached ->
            showPage(cached, pageIndex = 0, key = key, token = token)
            return
        }
        if (source == StudioSource.LOCAL) {
            // Local never browses — the grid shows the device-picker action instead.
            resultCache[key] = emptyList()
            showPage(emptyList(), pageIndex = 0, key = key, token = token)
            return
        }

        _uiState.update {
            it.copy(resultsLoading = true, results = emptyList(), gridIndex = 0, page = 0,
                pageCount = 0, rangeStart = 0, rangeEnd = 0, totalResults = 0)
        }
        loadJob = viewModelScope.launch {
            val fetched = when (source) {
                StudioSource.SCREENSCRAPER -> ssResults(kind, state.match)
                StudioSource.STEAMGRIDDB   -> sgdbResults(kind, state.query)
                StudioSource.THEGAMESDB    -> tgdbResults(kind, state.query, state.match)
                StudioSource.IGDB          -> igdbResults(kind, state.query, state.match)
                StudioSource.LOCAL         -> emptyList()
            }
            // A cancelled request is not an answer. Provider calls wrap themselves in runCatching,
            // which also catches the CancellationException and turns it into "nothing found" — so
            // without this check a source switch mid-load cached an empty page under this key, and
            // returning showed "No results" without ever asking again.
            ensureActive()
            // Store under the request's OWN key regardless of what is on screen now — a late
            // response still warms its cache entry, it just may not be shown.
            resultCache[key] = fetched
            showPage(fetched, pageIndex = 0, key = key, token = token)
            if (source == StudioSource.SCREENSCRAPER) refreshSsIdentityAfterBrowse()
        }
    }

    /**
     * The single reducer boundary for results. Rejects any response whose key or token has been
     * superseded — the guard that makes a slow provider unable to overwrite a fast one.
     */
    private fun showPage(
        all: List<StudioArt>,
        pageIndex: Int,
        key: StudioRequestKey,
        token: Long,
        gridIndex: Int = 0,
    ) {
        if (token != generation || key != activeKey) return
        _uiState.update {
            val page = StudioPage.of(all, pageIndex, it.pageSize)
            it.copy(
                resultsLoading = false,
                results = page.items,
                totalResults = page.totalResults,
                page = page.pageIndex,
                pageCount = page.pageCount,
                rangeStart = page.rangeStart,
                rangeEnd = page.rangeEnd,
                gridIndex = gridIndex.coerceIn(0, page.items.lastIndex.coerceAtLeast(0)),
            )
        }
    }

    /**
     * The grid slot's measured size in dp. Recomputes the page for the active tab and, if it
     * changed, re-pages so the focused result stays focused (AD-17). The screen should call this
     * only when the size actually changes; an unchanged capacity is a no-op either way.
     */
    override fun onGridMeasured(widthDp: Float, heightDp: Float) {
        gridSlotDp = widthDp to heightDp
        val capacity = capacityFor(_uiState.value.tabIndex) ?: return
        applyCapacity(capacity)
    }

    /** Capacity for [tabIndex] at the last measured slot, or null before the first measurement. */
    private fun capacityFor(tabIndex: Int): StudioGridCapacity? =
        gridSlotDp?.let { (width, height) -> StudioGridCapacity.of(width, height, STUDIO_TABS[tabIndex].tileClass) }

    private fun applyCapacity(capacity: StudioGridCapacity) {
        val before = _uiState.value
        if (capacity.columns == before.gridColumns && capacity.rows == before.gridRows) return
        // Absolute position of the focused result in the whole list, under the OLD page size.
        val focused = before.page * before.pageSize + before.gridIndex
        _uiState.update { it.copy(gridColumns = capacity.columns, gridRows = capacity.rows) }
        // Mid-load there is no page to move: the response pages at the new size when it lands, and
        // skeletonCount already reads it.
        if (before.resultsLoading) return
        val key = activeKey ?: return
        val all = activeResults()
        if (all.isEmpty()) return
        showPage(all, focused / capacity.pageSize, key, generation, gridIndex = focused % capacity.pageSize)
    }

    // Every SS media of the kind's types — cached lists load free; a game never scraped
    // gets one live scrape-as-you-go lookup (cached + ssId persisted for next time).
    //
    // ScreenScraper media is addressed by game id, not by a title. Unmatched, the catalog identifies
    // the game by its ROM (and saves that identity); matched by title or Change Match, it browses
    // that game's id — without saving a title match to the game row.
    private suspend fun ssResults(kind: ArtworkKind, match: GameMatch?): List<StudioArt> {
        val types = SS_TYPES_FOR_KIND[kind] ?: return emptyList()
        val matchedSsId = match?.candidate
            ?.takeIf { it.provider == MatchProvider.SCREENSCRAPER }
            ?.providerGameId?.toLongOrNull()
        val medias = ssMediaCatalog.mediasFor(gameId, matchedSsId) ?: return emptyList()
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

    private suspend fun sgdbResults(kind: ArtworkKind, query: String): List<StudioArt> {
        val types = sgdbTypesFor(kind).ifEmpty { return emptyList() }
        val game = _uiState.value.game ?: return emptyList()
        // A saved id is the strongest evidence, but only while the user is still searching for
        // THIS game: the moment they type something else, the typed title wins.
        // A match the user confirmed through Change Match is the strongest evidence there is and
        // holds whatever they type next: they already told us which game this is (task 2.3).
        val confirmed = _uiState.value.match
            ?.takeIf { it.userConfirmed && it.candidate.provider == MatchProvider.STEAMGRIDDB }
            ?.candidate?.providerGameId?.toLongOrNull()
        val savedId = game.steamGridDbId?.takeIf { StudioQuery.sameQuery(query, game.displayTitle) }
        val sgdbId = confirmed
            ?: savedId
            ?: steamGridDb.searchGame(query).getOrNull()?.firstOrNull()?.id
            ?: return emptyList()
        // No dimension filter, ICON0 included: every grid shape is a valid candidate now
        // that pass 2's crop editor will shape it to the tile. One request per art type; when a
        // tab shows several, each tile's label names its type.
        return types.flatMap { type ->
            steamGridDb.getArt(
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
                    label = listOfNotNull(
                        type.endpoint.takeIf { types.size > 1 },
                        art.style,
                        art.width?.let { w -> "${w}×${art.height}" },
                    ).joinToString(" · "),
                )
            }
        }
    }

    /** Browsed by the matched id when there is one, exactly like [igdbResults]. */
    private suspend fun tgdbResults(kind: ArtworkKind, query: String, match: GameMatch?): List<StudioArt> {
        val game = _uiState.value.game ?: return emptyList()
        val matchedId = match?.candidate
            ?.takeIf { it.provider == MatchProvider.THEGAMESDB }
            ?.providerGameId?.toLongOrNull()
        // No per-open memo any more — the result cache is keyed on the query, so it already
        // collapses repeat browses AND keeps a second query from serving the first one's art.
        val info = runCatching {
            if (matchedId != null) theGamesDb.fetchGameInfoById(matchedId)
            else theGamesDb.fetchGameInfo(game.platformId, query)
        }
            .onFailure { Timber.w(it, "TGDB browse failed") }.getOrNull()
            ?: return emptyList()
        if (kind in SHOW_ALL_ART_KINDS) {
            return listOfNotNull(
                info.artworkUrl?.let { StudioArt(it, null, "TheGamesDB", "box art") },
                info.heroUrl?.let { StudioArt(it, null, "TheGamesDB", "fanart") },
                info.logoUrl?.let { StudioArt(it, null, "TheGamesDB", "clear logo") },
            )
        }
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

    /**
     * A matched IGDB game is browsed BY ID — the whole point of Change Match is that the art comes
     * from the game the user picked, not from whatever a title search ranks first. Unmatched, it
     * falls back to the best title hit. [match] is the one the request key was built with, never a
     * fresher read of state.
     */
    private suspend fun igdbResults(kind: ArtworkKind, query: String, match: GameMatch?): List<StudioArt> {
        val game = _uiState.value.game ?: return emptyList()
        val matchedId = match?.candidate
            ?.takeIf { it.provider == MatchProvider.IGDB }
            ?.providerGameId?.toLongOrNull()
        val info = runCatching {
            if (matchedId != null) igdbApi.fetchGameInfoById(matchedId)
            else igdbApi.fetchGameInfo(game.platformId, query)
        }
            .onFailure { Timber.w(it, "IGDB browse failed") }.getOrNull()
            ?: return emptyList()
        if (kind in SHOW_ALL_ART_KINDS) {
            // IGDB has no clear logos, so its whole offer is the cover and the first artwork.
            return listOfNotNull(
                info.artworkUrl?.let { StudioArt(it, null, "IGDB", "cover") },
                info.heroUrl?.let { StudioArt(it, null, "IGDB", "artwork") },
            )
        }
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

    // Selecting a category or source (controller cycle OR touch tap) also lands navigation on
    // that level, so a tap jumps straight to the section and the grid refreshes underneath.
    override fun selectTab(index: Int) {
        val tabIndex = index.coerceIn(0, STUDIO_TABS.lastIndex)
        // Another tab can mean another tile class, so the page size follows it from the last
        // measured slot. No re-page here: the load below starts the new tab at page 0 anyway.
        val capacity = capacityFor(tabIndex)
        _uiState.update {
            it.copy(
                tabIndex = tabIndex, sourceIndex = 0, zone = StudioZone.TABS,
                gridColumns = capacity?.columns ?: it.gridColumns,
                gridRows = capacity?.rows ?: it.gridRows,
            )
        }
        landOnAvailableSource()
        // The query persists across categories: a title the user corrected once should not have
        // to be retyped for every artwork kind.
        viewModelScope.launch { refreshCurrent() }
        resolveMatch()
        loadResults()
    }

    fun cycleTab(delta: Int) = selectTab((_uiState.value.tabIndex + delta).mod(STUDIO_TABS.size))

    override fun selectSource(index: Int) {
        val sources = sourcesForTab()
        // A tab with no sources (empty list) must no-op — coercing into 0..-1 throws.
        if (sources.isEmpty()) return
        val clamped = index.coerceIn(0, sources.lastIndex)
        val source = sources[clamped]
        if (!isSourceAvailable(source)) {
            // Disabled, not gone: say what it needs rather than browse a provider that can't answer.
            _uiState.update { it.copy(message = unavailableReason(source)) }
            return
        }
        _uiState.update { it.copy(sourceIndex = clamped, zone = StudioZone.SOURCES) }
        // A match belongs to one provider, so switching source re-asks the question before the
        // grid is filled.
        resolveMatch()
        // Every source — Local included — goes through loadResults so the request key, the
        // generation token and the cache stay the single description of what is on screen.
        loadResults()
    }

    fun cycleSource(delta: Int) {
        val sources = sourcesForTab()
        // .mod(0) throws — tabs with no sources cycle nowhere.
        val count = sources.size
        if (count == 0) return
        // Step over disabled sources. Local is always available, so a lap always finds one.
        var index = _uiState.value.sourceIndex
        repeat(count) {
            index = (index + delta).mod(count)
            if (isSourceAvailable(sources[index])) {
                selectSource(index)
                return
            }
        }
    }

    /**
     * A ScreenScraper browse can identify the game by its ROM and save `ss_id` + `rom_crc32` to the
     * row (SsMediaCatalog's mini-scrape). The match row resolved against the game as it was loaded,
     * so without this it kept saying "No ScreenScraper match" beside a grid full of that game's art.
     * Costs no request: the next browse for the new match key is a media-cache hit.
     */
    private suspend fun refreshSsIdentityAfterBrowse() {
        val shown = _uiState.value.game ?: return
        val stored = gameRepository.getById(gameId) ?: return
        if (stored.ssId == shown.ssId && stored.romCrc32 == shown.romCrc32) return
        _uiState.update { it.copy(game = stored) }
        resolveMatch()
    }

    // ── Provider availability ─────────────────────────────────────────────────

    /** Re-reads which keyed providers can be asked. Cheap DataStore reads — safe on every open. */
    private suspend fun refreshProviderAvailability() {
        val unavailable = buildSet {
            if (sgdbKeyProvider.getKey().isNullOrBlank()) add(StudioSource.STEAMGRIDDB)
            if (!theGamesDb.hasApiKey()) add(StudioSource.THEGAMESDB)
            if (!igdbApi.hasCredentials()) add(StudioSource.IGDB)
        }
        _uiState.update {
            it.copy(unavailableSources = unavailable, hasSgdbKey = StudioSource.STEAMGRIDDB !in unavailable)
        }
    }

    fun isSourceAvailable(source: StudioSource): Boolean = sourceBadge(source) == null

    /**
     * Why [source] is disabled on the active tab, as the source row's short suffix, or null when it
     * can be asked. "Nothing for this tab" outranks "no key": adding a key would not help there.
     */
    override fun sourceBadge(source: StudioSource): String? = when {
        !servesKind(source, tab().kind)                -> "n/a"
        source in _uiState.value.unavailableSources    -> "no key"
        else                                           -> null
    }

    private fun unavailableReason(source: StudioSource): String = when {
        !servesKind(source, tab().kind) -> "${source.label} has no ${tab().label} artwork"
        source == StudioSource.IGDB     -> "IGDB needs a Client ID and Secret — add them in Settings ▸ Artwork"
        else                            -> "${source.label} needs an API key — add one in Settings ▸ Artwork"
    }

    /** Keeps the cursor off a disabled source after a tab change or a key being removed. */
    private fun landOnAvailableSource() {
        val sources = sourcesForTab()
        val current = sources.getOrNull(_uiState.value.sourceIndex)
        if (current != null && isSourceAvailable(current)) return
        val first = sources.indexOfFirst { isSourceAvailable(it) }
        if (first >= 0) _uiState.update { it.copy(sourceIndex = first) }
    }

    /**
     * Flips SteamGridDB's mature filter — START, or the SteamGridDB context menu.
     *
     * A no-op unless SteamGridDB is the active source. The filter is only ever part of a
     * SteamGridDB request key (task 1.3), so flipping it anywhere else would silently change
     * hidden state that nothing on screen reflects and no provider would act on.
     */
    override fun toggleNsfw() {
        if (!sgdbActive()) return
        _uiState.update { it.copy(includeNsfw = !it.includeNsfw, actionsOpen = false) }
        loadResults()
    }

    private fun sgdbActive(): Boolean =
        sourcesForTab().getOrNull(_uiState.value.sourceIndex) == StudioSource.STEAMGRIDDB

    // ── Game match (task 2.3) ─────────────────────────────────────────────────

    /**
     * The provider behind a Studio source, or null when the source is not a provider at all.
     * Local files are the user's own — nothing identifies them and nothing should try.
     */
    private fun providerFor(source: StudioSource?): MatchProvider? = when (source) {
        StudioSource.SCREENSCRAPER -> MatchProvider.SCREENSCRAPER
        StudioSource.STEAMGRIDDB   -> MatchProvider.STEAMGRIDDB
        StudioSource.THEGAMESDB    -> MatchProvider.THEGAMESDB
        StudioSource.IGDB          -> MatchProvider.IGDB
        StudioSource.LOCAL, null   -> null
    }

    /**
     * Monotonic token for match resolution, mirroring [generation].
     *
     * Matching hits the network on a miss, so it races the same way browsing does: without a
     * guard, a slow ScreenScraper lookup could land after the user has moved to SteamGridDB and
     * label the screen with the wrong provider's answer.
     */
    private var matchGeneration: Long = 0

    /**
     * Drops any in-flight match resolution — used when a confirmed match makes it moot. Clears the
     * resolving flag too: the dropped resolution never lands to clear it, and the match row checks
     * that flag first, so it would keep saying "Matching…" over a confirmed match.
     */
    private fun invalidateMatch() {
        matchGeneration++
        _uiState.update { it.copy(matchResolving = false) }
    }

    /**
     * The Change Match picker's running search. It is cancelled, not just ignored, when the user
     * moves past it: ScreenScraper serves this account one request at a time, so on device a typed
     * search waited behind two older ones for the only slot. It is also kept apart from the match
     * resolution's token, which it once shared, leaving the row on "Matching…".
     */
    private var changeMatchJob: kotlinx.coroutines.Job? = null

    /**
     * Resolves who the active source thinks this game is, then re-browses if the answer changed
     * the request key.
     *
     * A confirmed match is never re-derived: the user already decided, and re-running the matcher
     * could only ever disagree with them.
     */
    private fun resolveMatch(force: Boolean = false) {
        val state = _uiState.value
        val game = state.game ?: return
        val provider = providerFor(sourcesForTab().getOrNull(state.sourceIndex))
        if (provider == null) {
            invalidateMatch()
            _uiState.update { it.copy(match = null, matchProvider = null, matchResolving = false, matchFailed = false) }
            return
        }
        val existing = state.match
        if (!force && existing?.userConfirmed == true && existing.candidate.provider == provider) {
            _uiState.update { it.copy(matchProvider = provider) }
            return
        }

        val token = ++matchGeneration
        _uiState.update { it.copy(matchProvider = provider, matchResolving = true, match = null, matchFailed = false) }
        viewModelScope.launch {
            val outcome = runCatching { matcher.resolve(game, provider, state.query) }
                .onFailure { Timber.w(it, "Match resolution failed for %s", provider) }
            // Same two-check reducer discipline as showPage: a superseded answer is dropped, not
            // reconciled.
            if (token != matchGeneration) return@launch
            val resolved = outcome.getOrNull()
            val changed = resolved?.matchKey != _uiState.value.match?.matchKey
            // A provider that didn't answer is said as such, never as "no match": the failure is not
            // remembered, so the next resolution or a Change Match search asks again.
            _uiState.update { it.copy(match = resolved, matchResolving = false, matchFailed = outcome.isFailure) }
            if (changed) loadResults()
        }
    }

    /**
     * What the CHANGE MATCH button and the Change Match menu entry do, including when they can't
     * do anything.
     *
     * The button stays on the row for every provider so the row does not change shape as the user
     * walks the sources. A provider without title search would have nothing to pick FROM, and
     * pressing there says so instead of opening an empty list: silence on a press reads as a
     * broken button. No provider takes that branch today (every [ProviderCapabilities] row supports
     * title search), but the capability table decides that, not this function.
     */
    override fun onChangeMatchPressed() {
        val state = _uiState.value
        if (state.canChangeMatch) {
            openChangeMatch()
            return
        }
        val label = state.matchProvider?.label ?: return
        _uiState.update {
            // Close the menu first when this came from it, or the message would sit under the overlay.
            it.copy(
                message = "$label can't be searched by title — there are no alternatives to choose from.",
                actionsOpen = false,
                showFileInfo = false,
            )
        }
    }

    /** Opens the Change Match picker, seeded with the active query. */
    fun openChangeMatch() {
        if (!_uiState.value.canChangeMatch) return
        val seed = _uiState.value.query.ifBlank { gameTitle() }
        _uiState.update {
            it.copy(
                changeMatchOpen = true,
                changeMatchDraft = seed,
                changeMatchResults = emptyList(),
                changeMatchIndex = -1,
                changeMatchEditing = false,
                actionsOpen = false,
                searchOpen = false,
            )
        }
        submitChangeMatch()
    }

    override fun onChangeMatchDraftChanged(text: String) =
        _uiState.update { it.copy(changeMatchDraft = text.take(MAX_QUERY_LENGTH)) }

    override fun cancelChangeMatch() {
        changeMatchJob?.cancel()
        _uiState.update {
            it.copy(
                changeMatchOpen = false,
                changeMatchResults = emptyList(),
                changeMatchIndex = -1,
                changeMatchEditing = false,
                changeMatchLoading = false,
                changeMatchSearchingEveryPlatform = false,
                changeMatchError = null,
            )
        }
    }

    /** Select (or Square) on the query field: the screen focuses it and opens the keyboard. */
    override fun startChangeMatchEdit() = _uiState.update {
        if (!it.changeMatchOpen) it else it.copy(changeMatchEditing = true, changeMatchIndex = -1)
    }

    override fun stopChangeMatchEdit() = _uiState.update { it.copy(changeMatchEditing = false) }

    /** Walks field (-1) → candidates, clamped at both ends. */
    fun moveChangeMatchCursor(delta: Int) = _uiState.update {
        it.copy(changeMatchIndex = (it.changeMatchIndex + delta).coerceIn(-1, it.changeMatchResults.lastIndex))
    }

    /**
     * Runs the picker's own search. Submit-only, like the artwork query — never per keystroke.
     * A new submit cancels the search before it.
     */
    override fun submitChangeMatch() {
        val state = _uiState.value
        val provider = state.matchProvider ?: return
        val game = state.game ?: return
        val query = state.changeMatchDraft.trim().ifBlank { gameTitle() }
        changeMatchJob?.cancel()
        _uiState.update {
            it.copy(
                changeMatchLoading = true, changeMatchResults = emptyList(), changeMatchIndex = -1,
                changeMatchEditing = false, changeMatchAcrossPlatforms = false,
                changeMatchSearchingEveryPlatform = false, changeMatchError = null,
            )
        }
        changeMatchJob = viewModelScope.launch {
            val outcome = runCatching { changeMatchCandidates(provider, query, game.platformId) }
            // A cancelled search is not an answer: a newer search, or the closed picker, owns the
            // picker's state now.
            ensureActive()
            outcome.onFailure { Timber.w(it, "Change Match search failed") }
            val (results, acrossPlatforms) = outcome.getOrDefault(emptyList<GameCandidate>() to false)
            // The cursor lands on the first candidate, so A confirms the top hit straight away; with
            // nothing found it stays on the field, where A edits the title instead.
            _uiState.update {
                it.copy(
                    changeMatchLoading = false,
                    changeMatchSearchingEveryPlatform = false,
                    changeMatchResults = results,
                    changeMatchAcrossPlatforms = acrossPlatforms,
                    // A failure is said as one, never shown as "No games found". It is not
                    // remembered, so Search asks again.
                    changeMatchError = outcome.exceptionOrNull()
                        ?.let { "${provider.label} didn't answer. Press Search to try again." },
                    changeMatchIndex = if (results.isEmpty()) -1 else 0,
                )
            }
        }
    }

    /**
     * The picker's candidates, and whether they include other platforms.
     *
     * Every search is remembered for this open (see [titleSearches]), so the same title again is
     * instant, and one still running is shared rather than sent twice.
     *
     * Only ScreenScraper widens, and only here, where the user picks and every candidate names its
     * system; the matcher never does. For a Windows game the platform search has found nothing, so
     * the picker asks every platform straight away, the game's own first. Elsewhere it widens only
     * when the platform search comes back empty.
     */
    private suspend fun changeMatchCandidates(
        provider: MatchProvider,
        query: String,
        platformId: String,
    ): Pair<List<GameCandidate>, Boolean> {
        if (provider != MatchProvider.SCREENSCRAPER) return titleSearches.searchByTitle(provider, query, platformId) to false
        if (!matchEvidence.searchesEveryPlatformFirst(provider, platformId)) {
            val onPlatform = titleSearches.searchByTitle(provider, query, platformId)
            if (onPlatform.isNotEmpty()) return onPlatform to false
        }
        _uiState.update { it.copy(changeMatchSearchingEveryPlatform = true) }
        val everyPlatform = titleSearches.remember(provider, query, scope = "every-platform:$platformId") {
            matchEvidence.searchScreenScraperOnAnyPlatform(query, preferredPlatformId = platformId)
        }
        return everyPlatform to everyPlatform.isNotEmpty()
    }

    /**
     * Accepts one candidate as THE match for the active provider.
     *
     * Persisted, so the next session resolves it at Tier 1 without a lookup — and persisted to one
     * provider column only. No artwork file and no metadata column is touched: confirming a match
     * changes what the Studio ASKS FOR, never what the game already has.
     */
    override fun confirmMatch(index: Int) {
        val state = _uiState.value
        val provider = state.matchProvider ?: return
        val candidate = state.changeMatchResults.getOrNull(index) ?: return
        changeMatchJob?.cancel()
        invalidateMatch()
        _uiState.update {
            it.copy(
                // Tier 1 is exactly what this becomes: the id is about to be written to the game
                // row, so the next resolve reads it straight back as a saved provider id.
                match = GameMatch(candidate, MatchTier.SAVED_PROVIDER_ID, userConfirmed = true),
                matchFailed = false,
                changeMatchOpen = false,
                changeMatchResults = emptyList(),
                changeMatchIndex = 0,
                message = "Matched as ${candidate.title}",
            )
        }
        viewModelScope.launch {
            gameRepository.updateProviderMatch(gameId, provider.name, candidate.providerGameId.toLongOrNull())
            _uiState.update { it.copy(game = gameRepository.getById(gameId) ?: it.game) }
            loadResults()
        }
    }

    /**
     * Forgets the confirmed match: clears the provider id and re-derives.
     *
     * Deliberately NOT destructive — every downloaded asset and every scraped field stays exactly
     * where it is. The only thing forgotten is who the provider was told this game is.
     */
    override fun forgetMatch() {
        val provider = _uiState.value.matchProvider ?: return
        invalidateMatch()
        _uiState.update { it.copy(match = null, changeMatchOpen = false, actionsOpen = false) }
        viewModelScope.launch {
            gameRepository.updateProviderMatch(gameId, provider.name, null)
            _uiState.update { it.copy(game = gameRepository.getById(gameId) ?: it.game) }
            resolveMatch(force = true)
            loadResults()
        }
    }

    // ── Search (task 1.1) ─────────────────────────────────────────────────────

    /** Opens the search field, pre-filled with the active query and fully selectable. */
    override fun openSearch() = _uiState.update {
        it.copy(searchOpen = true, queryDraft = it.query, actionsOpen = false, showFileInfo = false)
    }

    override fun onQueryDraftChanged(text: String) = _uiState.update { it.copy(queryDraft = text.take(MAX_QUERY_LENGTH)) }

    override fun cancelSearch() = _uiState.update { it.copy(searchOpen = false, queryDraft = it.query) }

    /**
     * Applies the typed query and re-browses.
     *
     * Submit-only: typing does not fire requests, so a provider is never hit per keystroke. A
     * blank draft falls back to the game's title rather than searching for nothing, and an
     * unchanged query closes the field without discarding the results already on screen.
     */
    override fun submitSearch() {
        val state = _uiState.value
        val submitted = state.queryDraft.trim().ifBlank { gameTitle() }
        val unchanged = StudioQuery.sameQuery(submitted, state.query)
        _uiState.update {
            it.copy(
                searchOpen = false,
                query = submitted,
                queryDraft = submitted,
                queryIsCustom = !StudioQuery.sameQuery(submitted, gameTitle()),
            )
        }
        if (!unchanged) {
            // A new query is a new question about identity too — unless the user already answered
            // it, in which case resolveMatch keeps their confirmed match.
            resolveMatch()
            loadResults()
        }
    }

    /** Returns the query to the game's own title. The game row is never touched either way. */
    override fun resetSearchToTitle() {
        val title = gameTitle()
        if (StudioQuery.sameQuery(title, _uiState.value.query)) {
            _uiState.update { it.copy(searchOpen = false, queryDraft = title, query = title, queryIsCustom = false) }
            return
        }
        _uiState.update {
            it.copy(searchOpen = false, query = title, queryDraft = title, queryIsCustom = false)
        }
        loadResults()
    }

    /** All results for the grid currently on screen, or empty if its key is no longer cached. */
    private fun activeResults(): List<StudioArt> = activeKey?.let { resultCache[it] }.orEmpty()

    override fun nextPage() = goToPage(_uiState.value.page + 1)

    override fun previousPage() = goToPage(_uiState.value.page - 1)

    private fun goToPage(index: Int) {
        val key = activeKey ?: return
        val all = activeResults()
        if (index < 0 || index * _uiState.value.pageSize >= all.size) return
        showPage(all, index, key, generation)
    }

    override fun openCandidate(index: Int) {
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

    override fun onManualPageCount(count: Int) = _uiState.update {
        it.copy(manualPageCount = count, manualPage = it.manualPage.coerceIn(0, (count - 1).coerceAtLeast(0)))
    }

    override fun manualPreviousPage() = _uiState.update {
        it.copy(manualPage = (it.manualPage - 1).coerceAtLeast(0))
    }

    override fun manualNextPage() = _uiState.update {
        it.copy(manualPage = (it.manualPage + 1).coerceAtMost((it.manualPageCount - 1).coerceAtLeast(0)))
    }

    override fun requestLocalPick() = _uiState.update { it.copy(localPickKind = tab().kind) }
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

    override fun applyCandidate() {
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

    /**
     * Opens the actions menu (slot actions, then source actions), loading the record so
     * availability is accurate.
     *
     * Opens even with no current artwork when the source has an entry of its own: SteamGridDB's
     * mature filter (task 1.3), or Change Match on any provider (task 2.4). The unmatched game with
     * no artwork is exactly the one Change Match exists to rescue. Only a source with neither, i.e.
     * Local, still refuses, so the menu never opens empty.
     */
    override fun openActions() {
        val sgdb = sgdbActive()
        val s = _uiState.value
        if (s.currentUri == null && !sgdb && s.matchProvider == null) return
        viewModelScope.launch {
            val info = routingStore.studioInfo(gameId, tab().kind)
            _uiState.update {
                it.copy(
                    info = info, actionsOpen = true, actionsIndex = 0, showFileInfo = false,
                    sgdbSourceActive = sgdb,
                )
            }
        }
    }

    override fun closeActions() = _uiState.update { it.copy(actionsOpen = false, showFileInfo = false) }

    private fun moveActionsCursor(delta: Int) = _uiState.update {
        val n = it.availableActions.size
        if (n == 0) it else it.copy(actionsIndex = (it.actionsIndex + delta).mod(n))
    }

    override fun runAction(action: StudioAction) {
        when (action) {
            StudioAction.CROP             -> beginCrop()
            StudioAction.RESTORE_PREVIOUS -> restorePrevious()
            StudioAction.RESET_DEFAULT    -> resetToScrapedDefault()
            StudioAction.CLEAR            -> { closeActions(); clearCurrent() }
            StudioAction.FILE_INFO        -> _uiState.update { it.copy(showFileInfo = true) }
            StudioAction.TOGGLE_MATURE    -> toggleNsfw()
            // Through the button's own entry point, so an inert provider explains itself the same way.
            StudioAction.CHANGE_MATCH     -> onChangeMatchPressed()
            StudioAction.FORGET_MATCH     -> forgetMatch()
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

    /** A representative video frame as a PNG temp + its (w, h), for the crop editor's framing
     *  preview. Videos usually open on a black fade-in frame, so several points through the clip
     *  are sampled and the brightest (most visible) one is used. */
    private fun extractVideoFrame(video: java.io.File): Triple<java.io.File, Int, Int>? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val pointsUs = if (durMs > 0)
                listOf(0.5, 0.33, 0.66, 0.15, 0.85).map { (durMs * it * 1000).toLong() }
            else listOf(0L)
            var best: android.graphics.Bitmap? = null
            var bestLuma = -1.0
            for (us in pointsUs) {
                val f = retriever.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val luma = averageLuma(f)
                if (luma > bestLuma) { best?.recycle(); best = f; bestLuma = luma } else f.recycle()
                if (bestLuma > 0.12) break   // clearly not a black frame — good enough
            }
            val frame = best ?: return null
            val out = java.io.File.createTempFile("studio_frame_", ".png", appCacheDir)
            out.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val dims = Triple(out, frame.width, frame.height)
            frame.recycle()
            dims
        } finally {
            runCatching { retriever.release() }
        }
    }.onFailure { Timber.w(it, "Video frame extraction failed") }.getOrNull()

    /** Cheap average brightness (0..1) over a sampled grid of pixels — used to skip black frames. */
    private fun averageLuma(bmp: android.graphics.Bitmap): Double {
        val stepX = (bmp.width / 16).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)
        var sum = 0.0; var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                sum += (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)) / 255.0
                n++; x += stepX
            }
            y += stepY
        }
        return if (n > 0) sum / n else 0.0
    }

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

    override fun panCrop(dx: Float, dy: Float) {
        _uiState.update { it.copy(cropCenterX = (it.cropCenterX + dx), cropCenterY = (it.cropCenterY + dy)) }
        recomputeCropRect()
    }

    override fun zoomCrop(factor: Float) {
        _uiState.update { it.copy(cropZoom = (it.cropZoom * factor).coerceIn(1f, 6f)) }
        recomputeCropRect()
    }

    /** Bakes the current crop window and stores it — a PNG region for stills, a re-encoded clip
     *  for ICON1 videos — keeping the untouched original for future re-crops. */
    override fun applyCrop() {
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

    override fun cancelCrop() {
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

    override fun dismissCandidate() {
        _uiState.value.candidateManualPath?.let { runCatching { java.io.File(it).delete() } }
        _uiState.update {
            it.copy(candidate = null, candidateManualPath = null, manualDownloading = false, manualPage = 0, manualPageCount = 0)
        }
    }
    override fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun close() = _uiState.update { it.copy(closed = true) }

    /** The screen calls this right after acting on [ArtworkStudioUiState.closed] so a stale
     *  closed=true never survives to instantly re-close the screen on the next open. */
    fun consumeClosed() = _uiState.update { it.copy(closed = false) }

    // ── Controller ────────────────────────────────────────────────────────────

    override fun handleGamepadAction(action: GamepadAction) {
        val s = _uiState.value
        // Search field: the IME owns typing; the pad only confirms or cancels.
        if (s.searchOpen) {
            when (action) {
                GamepadAction.SELECT -> submitSearch()
                GamepadAction.BACK   -> cancelSearch()
                else -> Unit
            }
            return
        }
        // Change Match picker. The query field is cursor stop -1 and the candidates follow it. The
        // keyboard opens only while editing: an open IME receives key events BEFORE
        // MainActivity.dispatchKeyEvent, so a picker that opened straight into a focused field
        // never saw a single pad press.
        if (s.changeMatchOpen) {
            if (s.changeMatchEditing) {
                // A press that reaches us means the keyboard is already gone — leave edit mode first.
                stopChangeMatchEdit()
                if (action == GamepadAction.BACK) return
            }
            val picker = _uiState.value
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveChangeMatchCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveChangeMatchCursor(+1)
                GamepadAction.CHANGE_SORT   -> startChangeMatchEdit()   // Square, as in the Studio's own search
                GamepadAction.SELECT        ->
                    if (picker.changeMatchIndex < 0) startChangeMatchEdit()
                    else confirmMatch(picker.changeMatchIndex)
                GamepadAction.BACK          -> cancelChangeMatch()
                else -> Unit
            }
            return
        }
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
        // Three hierarchical levels: TABS (categories) → SOURCES → GRID. Confirm descends, BACK
        // ascends (and closes from Level 1). Left/Right — and LB/RB, which mirror them — act on
        // the current level only; in the grid LB/RB page instead. D-pad up/down only moves inside
        // the grid, clamped at the page edges: paging is exclusively LB/RB or the on-screen pills.
        when (action) {
            GamepadAction.BACK -> when (s.zone) {
                StudioZone.TABS    -> close()
                StudioZone.SOURCES -> _uiState.update { it.copy(zone = StudioZone.TABS) }
                StudioZone.GRID    -> _uiState.update { it.copy(zone = StudioZone.SOURCES) }
            }
            GamepadAction.NAVIGATE_LEFT -> when (s.zone) {
                StudioZone.TABS    -> cycleTab(-1)
                StudioZone.SOURCES -> cycleSource(-1)
                StudioZone.GRID    ->
                    if (s.gridIndex > 0) _uiState.update { it.copy(gridIndex = s.gridIndex - 1) }
            }
            GamepadAction.NAVIGATE_RIGHT -> when (s.zone) {
                StudioZone.TABS    -> cycleTab(+1)
                StudioZone.SOURCES -> cycleSource(+1)
                StudioZone.GRID    ->
                    if (s.gridIndex < s.results.lastIndex) _uiState.update { it.copy(gridIndex = s.gridIndex + 1) }
            }
            GamepadAction.NAVIGATE_UP -> if (s.zone == StudioZone.GRID && s.gridIndex >= s.gridColumns) {
                _uiState.update { it.copy(gridIndex = s.gridIndex - s.gridColumns) }
            }
            GamepadAction.NAVIGATE_DOWN -> if (s.zone == StudioZone.GRID &&
                s.gridIndex + s.gridColumns <= s.results.lastIndex
            ) {
                _uiState.update { it.copy(gridIndex = s.gridIndex + s.gridColumns) }
            }
            GamepadAction.PREV_CATEGORY -> when (s.zone) {   // LB
                StudioZone.TABS    -> cycleTab(-1)
                StudioZone.SOURCES -> cycleSource(-1)
                StudioZone.GRID    -> previousPage()
            }
            GamepadAction.NEXT_CATEGORY -> when (s.zone) {   // RB
                StudioZone.TABS    -> cycleTab(+1)
                StudioZone.SOURCES -> cycleSource(+1)
                StudioZone.GRID    -> nextPage()
            }
            GamepadAction.SELECT -> when (s.zone) {
                StudioZone.TABS    -> _uiState.update { it.copy(zone = StudioZone.SOURCES) }
                // Local never enters the grid — confirm opens the device file picker directly.
                StudioZone.SOURCES ->
                    if (sourcesForTab().getOrNull(s.sourceIndex) == StudioSource.LOCAL) requestLocalPick()
                    else _uiState.update { it.copy(zone = StudioZone.GRID) }
                StudioZone.GRID    -> openCandidate(s.gridIndex)
            }
            // X / Square focuses the search field, from any level.
            GamepadAction.CHANGE_SORT -> openSearch()
            // START toggles SteamGridDB's mature filter — a screen-local repurposing, which is
            // how START is already used elsewhere (the pickers bind it to Add/Apply; it has no
            // global behaviour of its own). Silently ignored on every other source.
            GamepadAction.HOME -> toggleNsfw()
            // Y / Triangle opens the per-slot options menu (crop, restore, reset, clear, info) —
            // XMB-style context menu, available at every level.
            GamepadAction.OPEN_CONTEXT_MENU -> openActions()
            else -> Unit
        }
    }
}
