package com.playfieldportal.feature.xmb.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.api.SsCachedMedia
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
import com.playfieldportal.feature.artwork.store.CropProfileRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
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
    STEAM("Steam"),
    LOCAL("Local File"),
}

/**
 * One result tile in the Available Artwork grid. [providerAssetId] is the provider's own id for the
 * asset when it has one (task 5.1); without it the URL is the asset's only identity ([StudioArtKey]).
 */
data class StudioArt(
    val url: String,
    val thumb: String?,
    val provider: String,
    val label: String? = null,
    val isVideo: Boolean = false,
    val providerAssetId: String? = null,
    /** What the Options menu's filters read (style, size, region, media type); null filters nothing. */
    val facets: StudioArtFacets? = null,
)

/** One row of the Triangle menu: a filter row of the active source, or a slot/source action. */
sealed interface StudioMenuItem {
    data class Filter(val option: StudioFilterOption) : StudioMenuItem
    data class Action(val action: StudioAction) : StudioMenuItem
}

// Navigation levels: SOURCES → GRID. Confirm descends, back ascends, left/right acts on the
// current level only. There is no tab level: LB/RB change the artwork type from the source row, so
// a level whose only job was to be confirmed out of just cost a press (user decision, 2026-09-29).
enum class StudioZone { SOURCES, GRID }

/** Where one pick is on its way into the library (task 5.2). */
enum class StudioQueueState { QUEUED, DOWNLOADING, ADDED, FAILED }

/** A pick handed to the download queue. [gameId] is kept because the queue outlives the screen. */
data class StudioQueueItem(
    val gameId: Long,
    val key: StudioArtKey,
    val art: StudioArt,
    val state: StudioQueueState,
)

/**
 * What a tile's corner badge shows (tasks 5.1, 5.2, 5.3). A stored asset reads as checked ([ADDED])
 * until unchecked; on a single-art tab the one asset the slot holds reads as [CURRENT] instead,
 * because there is nothing to add it to — it already IS the artwork.
 */
enum class StudioTileMark { NONE, PICKED, QUEUED, DOWNLOADING, ADDED, CURRENT, FAILED, TO_REMOVE }

/**
 * The page line's account of the active tab: [toAdd] new picks and [toRemove] unchecked stored assets
 * waiting for Apply, then the downloads this open.
 */
data class StudioQueueSummary(
    val toAdd: Int = 0,
    val toRemove: Int = 0,
    val added: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
) {
    val hasChanges: Boolean get() = toAdd > 0 || toRemove > 0
    val inQueue: Boolean get() = total > 0
}

/** The leave prompt's rows, in order: B asked to close while changes were not applied (task 5.2). */
enum class StudioLeaveChoice(val label: String) {
    APPLY("Apply and Close"),
    DISCARD("Discard Changes"),
    STAY("Stay"),
}

/**
 * The crop editor's context-menu rows (task 6.3), in order. The preview row is only built for kinds
 * that have an inset to switch, so a menu may carry the shape rows alone.
 */
enum class CropOption {
    /** The task 6.7 live-preview switch, a row here rather than its own button — see task 6.3. */
    PREVIEW,
    SHAPE_PLATFORM_DEFAULT,
    SHAPE_ORIGINAL_IMAGE;

    /** The shape this row selects, or null for the preview switch. */
    val shape: CropShapeChoice?
        get() = when (this) {
            PREVIEW -> null
            SHAPE_PLATFORM_DEFAULT -> CropShapeChoice.PLATFORM_DEFAULT
            SHAPE_ORIGINAL_IMAGE -> CropShapeChoice.ORIGINAL_IMAGE
        }
}

/** The crop menu's title. */
const val STUDIO_CROP_OPTIONS_TITLE = "Crop Options"

/** One crop-menu row as the panel shows it: a label, its setting at the right, a check for the shape in force. */
data class StudioCropRow(val label: String, val value: String, val checked: Boolean = false)

/** The panel row for [option]: Live Preview with its On/Off, or Shape with the shape that row selects. */
fun studioCropRow(option: CropOption, previewEnabled: Boolean, current: CropShapeChoice): StudioCropRow {
    val shape = option.shape ?: return StudioCropRow("Live Preview", if (previewEnabled) "On" else "Off")
    return StudioCropRow("Shape", shape.label, checked = shape == current)
}

/** Shown when Triangle is pressed on a source that has no options to offer. */
const val STUDIO_NO_OPTIONS_MESSAGE = "No options available here"

/**
 * The crop shapes a game can be pinned to (task 6.3).
 *
 * [storedKey] is what lands in `artwork_records.crop_profile_key`. PLATFORM_DEFAULT stores null on
 * purpose — "follow the defaults" is the absence of an override, so choosing it IS Reset to Platform
 * Default and a later change to the shared table reaches this game without a migration.
 */
enum class CropShapeChoice(val label: String, val storedKey: String?) {
    PLATFORM_DEFAULT("Platform Default", null),
    ORIGINAL_IMAGE("Original Image", CropProfileRegistry.ORIGINAL_KEY);

    companion object {
        /** The row a stored key selects. An unrecognized key reads as the default, matching how
         *  [CropProfileRegistry.resolve] ignores one. */
        fun of(storedKey: String?): CropShapeChoice =
            entries.firstOrNull { it.storedKey != null && it.storedKey == storedKey } ?: PLATFORM_DEFAULT
    }
}

/** The apply confirmation's rows, in order: START, the Apply pill or Apply Changes ask before changing the slot. */
enum class StudioApplyChoice(val label: String) {
    APPLY("Apply"),
    CANCEL("Cancel"),
}

/**
 * The replace prompt's rows, in order: Apply on a single-art tile the slot already holds (task 5.3).
 *
 * [CANCEL] is first because the press it answers is almost always a slip. There is no third "view it"
 * row: A opened the candidate to get here, so the image is already on screen, and the stored asset is
 * that same image. Replacing re-downloads identical bytes and backs them up over the one real
 * previous version — nothing gained, one version lost.
 */
enum class StudioReplaceChoice(val label: String) {
    CANCEL("Cancel"),
    REPLACE("Replace Anyway"),
}

/**
 * The two Options-menu actions that ask first through the shared PFP confirm modal (context-menu
 * plan task 1.4), instead of the Studio's own row prompts above. Neither runs on one press.
 */
enum class StudioDestructive { CLEAR_ARTWORK, FORGET_MATCH }

/** Which confirmation [StudioConfirmPrompt] describes — what activating a row resolves. */
enum class StudioConfirmKind { APPLY, REPLACE }

/** One row of a confirmation overlay. */
data class StudioConfirmRow(val label: String, val isDestructive: Boolean = false)

/**
 * The open confirmation, or null when none is. The screen renders this one descriptor and the
 * gamepad handler serves this one branch, so a further confirmation is a new builder here rather
 * than another overlay early-return in the screen — which C17 is about to collapse onto the
 * engine's modal stack anyway.
 */
data class StudioConfirmPrompt(
    val kind: StudioConfirmKind,
    val title: String,
    val rows: List<StudioConfirmRow>,
    val selectedIndex: Int,
)

/** "3 screenshots", "1 video": [count] assets of [kind], for the confirmation's title. */
fun studioAssetCount(kind: ArtworkKind, count: Int): String {
    val noun = when (kind) {
        ArtworkKind.SCREENSHOT -> "screenshot"
        ArtworkKind.VIDEO      -> "video"
        else                   -> "image"
    }
    return if (count == 1) "1 $noun" else "$count ${noun}s"
}

/** The provider a record picked from the device is stored under. */
const val LOCAL_FILE_PROVIDER = "Local file"

/**
 * What a batch of local picks did: "Added 3 screenshots", with the cap or failures after it when
 * either held some back.
 */
fun studioLocalAddMessage(kind: ArtworkKind, added: Int, failed: Int, droppedOverCap: Boolean): String {
    val capacity = com.playfieldportal.feature.artwork.store.ArtworkFileNaming.MAX_SORT_ORDER + 1
    return when {
        droppedOverCap && added == 0 -> "A game holds ${studioAssetCount(kind, capacity)} at most"
        droppedOverCap               -> "Added ${studioAssetCount(kind, added)} — a game holds $capacity at most"
        failed > 0 && added == 0     -> if (failed == 1) "The file could not be added" else "$failed files could not be added"
        failed > 0                   ->
            "Added ${studioAssetCount(kind, added)} · $failed ${if (failed == 1) "file" else "files"} could not be added"
        else                         -> "Added ${studioAssetCount(kind, added)}"
    }
}

/** The apply confirmation's title: "Add 2 screenshots?", "Remove 1 video?" or "Add 2 screenshots and remove 1?". */
fun studioApplyTitle(kind: ArtworkKind, toAdd: Int, toRemove: Int): String = when {
    toAdd > 0 && toRemove > 0 -> "Add ${studioAssetCount(kind, toAdd)} and remove $toRemove?"
    toRemove > 0              -> "Remove ${studioAssetCount(kind, toRemove)}?"
    else                      -> "Add ${studioAssetCount(kind, toAdd)}?"
}

data class ArtworkStudioUiState(
    val game: Game? = null,
    val isLoading: Boolean = true,
    val tabIndex: Int = 0,
    val sourceIndex: Int = 0,
    val zone: StudioZone = StudioZone.SOURCES,
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
    // The search card's controller cursor: null while it is on the query field, else the button.
    val searchButton: StudioSearchButton? = null,
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
    // SteamGridDB's Mature filter. Carries over between opens, unlike the rest of [filters].
    val includeNsfw: Boolean = false,
    // ── Per-source filters (2026-09-29) ──────────────────────────────────────
    // Reset on every open except SteamGridDB's styles, which are remembered per art type.
    val filters: StudioFilters = StudioFilters(),
    // The filter list open over the Triangle menu (Style, Region, …), or null for the menu's root.
    val filterGroup: StudioFilterGroup? = null,
    val filterGroupIndex: Int = 0,
    // How many results the active browse returned before the filters: "12 of 40 shown".
    val unfilteredTotal: Int = 0,
    // ScreenScraper's Region list for the active browse: the disc's region, the usual ones, the rest.
    val filterRegions: List<String> = emptyList(),
    val hasSgdbKey: Boolean = false,
    // Keyed providers with no key/credentials: still listed, drawn disabled, skipped by source
    // cycling, and never asked. Re-read on every open so a key added in Settings takes effect.
    val unavailableSources: Set<StudioSource> = emptySet(),
    // Candidate preview overlay (A on a single-art tab's tile, or Preview in the menu). Apply/Cancel from here.
    val candidate: StudioArt? = null,
    // ── Selection (C16 task 5.1) ─────────────────────────────────────────────
    // Tiles picked on a multi-asset tab, in pick order. Keyed by asset, not grid position, so a pick
    // survives paging, a re-page, a source switch and a new query. Applying moves them to [queue].
    val selection: Map<StudioArtKey, StudioArt> = emptyMap(),
    // Stored assets the user unchecked, keyed like [selection]. Applying deletes them from the slot.
    val removals: Map<StudioArtKey, StudioArt> = emptyMap(),
    // ── Download queue (C16 task 5.2) ────────────────────────────────────────
    // Picks handed over for download, in the order they were added. Closing the screen does not stop
    // it: the ViewModel outlives the screen, and so does a download in flight.
    val queue: List<StudioQueueItem> = emptyList(),
    // B asked to close while changes were not applied: Apply and Close / Discard Changes / Stay.
    val leavePromptOpen: Boolean = false,
    val leavePromptIndex: Int = 0,
    // START, the Apply pill or Apply Changes asked to apply this tab's changes: Apply / Cancel.
    val applyConfirmOpen: Boolean = false,
    val applyConfirmIndex: Int = 0,
    // Apply was pressed on a single-art tile the slot already holds: View / Replace / Cancel (5.3).
    val replacePromptOpen: Boolean = false,
    val replacePromptIndex: Int = 0,
    // Clear Artwork or Forget Match was chosen and is waiting on the shared confirm modal. The
    // screen hosts that modal, so there is no cursor here: only which action is asking.
    val destructiveConfirm: StudioDestructive? = null,
    // Stored-assets manager (task 5.4): reorders the active multi-asset slot. Its list is [library],
    // which is already the slot's stored assets in order, so the panel holds no copy of its own.
    val managerOpen: Boolean = false,
    val managerIndex: Int = 0,
    // Set while a reorder is being written, so the panel cannot start a second one over it.
    val managerBusy: Boolean = false,
    // What the active multi-asset slot already holds, re-read on every open, tab, apply, clear and add.
    val library: StudioLibraryAssets = StudioLibraryAssets(),
    // Manual candidates: the PDF is downloaded to cache and paged before Apply.
    val candidateManualPath: String? = null,
    val manualDownloading: Boolean = false,
    val manualPage: Int = 0,
    val manualPageCount: Int = 0,
    val applying: Boolean = false,
    val message: String? = null,
    // Set when the user picked "Local File" — the screen launches the SAF picker for it.
    val localPickKind: ArtworkKind? = null,
    // The grid cursor is on Local File's upload bar rather than a tile. Read through
    // [localBarFocused], which also requires the grid and Local File to still be where the user is.
    val localBarCursor: Boolean = false,
    // Actions menu (OPEN_CONTEXT_MENU / on-screen ACTIONS) — operates on the active tab's current slot.
    val actionsOpen: Boolean = false,
    val actionsIndex: Int = 0,
    // The row actually under the cursor (task 3.3). menuItems is recomputed from other state, so
    // when it changes while the menu is open (e.g. a queue item fails), the cursor is re-anchored to
    // this row rather than to actionsIndex's raw position.
    val actionsSelected: StudioMenuItem? = null,
    val info: StudioArtworkInfo? = null,
    val showFileInfo: Boolean = false,
    // Crop editor (task: crop/position) — non-null path = editing the untouched original.
    // For ICON1 the path is a still frame extracted for framing; the video to re-encode is
    // held in cropVideoSourcePath.
    val cropEditorPath: String? = null,
    val cropVideoSourcePath: String? = null,
    // Set only while cropping a pick that has NOT been applied yet, so Apply can land it with the
    // provider it came from instead of as an anonymous user file.
    val cropCandidate: StudioArt? = null,
    // The live result inset in the crop editor (tasks 6.2/6.6), switchable from Settings ▸ Artwork
    // or from the editor's Ⓨ menu. One switch for every kind, stills and video alike.
    val cropPreviewEnabled: Boolean =
        com.playfieldportal.core.data.repository.CropPreviewPreferences.DEFAULT_ENABLED,
    // This game's stored crop-profile override for the tab being cropped (task 6.3), read at
    // editor open. Null means it follows the shared defaults — Reset to Platform Default.
    val cropProfileOverride: String? = null,
    // The crop editor's context menu: the live-preview switch plus the Crop Shape rows. Built at
    // open, because the preview row is only offered for kinds that have an inset to show.
    val cropOptionsOpen: Boolean = false,
    val cropOptionsIndex: Int = 0,
    val cropOptionRows: List<CropOption> = emptyList(),
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

    /** The active tab holds several assets (SCREENSHOT, VIDEO), so A picks tiles instead of previewing one. */
    val selectsMultiple: Boolean
        get() = STUDIO_TABS.getOrNull(tabIndex)?.kind
            ?.let(com.playfieldportal.feature.artwork.store.ArtworkFileNaming::supportsMultiple) == true

    fun isSelected(art: StudioArt): Boolean =
        STUDIO_TABS.getOrNull(tabIndex)?.let { StudioArtKey.of(it.kind, art) in selection } == true

    /** Picks for the active tab only: the other tabs' picks are kept, just not counted here. */
    val selectedOnTab: Int
        get() = STUDIO_TABS.getOrNull(tabIndex)?.kind?.let { kind -> selection.keys.count { it.kind == kind } } ?: 0

    /** Local File is the active source, so the grid carries the upload bar above the slot's own files. */
    val localSourceActive: Boolean
        get() = StudioSource.entries.getOrNull(sourceIndex) == StudioSource.LOCAL

    /** The grid cursor is on the upload bar: A opens the device picker instead of acting on a tile. */
    val localBarFocused: Boolean
        get() = zone == StudioZone.GRID && localSourceActive && localBarCursor

    /** The tile under the grid cursor, or null when the cursor is on the upload bar or there is none. */
    private val focusedTile: StudioArt?
        get() = if (zone != StudioZone.GRID || localBarFocused) null else results.getOrNull(gridIndex)

    /**
     * A took Preview's place on this tab, so the menu offers it for the focused tile. Not for a
     * [LOCAL_ART] tile: its preview's Apply would store a file the slot already holds.
     */
    val canPreviewFocused: Boolean
        get() = selectsMultiple && focusedTile?.let { it.provider != LOCAL_ART } == true

    /** How the active tab's [art] is getting on in the open game's queue, or null if it was never added. */
    fun queueStateOf(art: StudioArt): StudioQueueState? {
        val kind = STUDIO_TABS.getOrNull(tabIndex)?.kind ?: return null
        val key = StudioArtKey.of(kind, art)
        return queue.firstOrNull { it.gameId == game?.id && it.key == key }?.state
    }

    /**
     * A tile's badge: a download still in flight or failed first, then a stored asset (this open's
     * download or the slot's own) that is unchecked or still checked, then a new pick.
     */
    fun tileMarkOf(art: StudioArt): StudioTileMark {
        val kind = STUDIO_TABS.getOrNull(tabIndex)?.kind ?: return StudioTileMark.NONE
        val key = StudioArtKey.of(kind, art)
        val queued = queueStateOf(art)
        return when {
            queued == StudioQueueState.QUEUED      -> StudioTileMark.QUEUED
            queued == StudioQueueState.DOWNLOADING -> StudioTileMark.DOWNLOADING
            queued == StudioQueueState.FAILED      -> StudioTileMark.FAILED
            key in removals                        -> StudioTileMark.TO_REMOVE
            queued == StudioQueueState.ADDED       -> StudioTileMark.ADDED
            // The same comparison either way; only the reading differs, so single-art says CURRENT.
            library.holds(kind, art)               ->
                if (selectsMultiple) StudioTileMark.ADDED else StudioTileMark.CURRENT
            key in selection                       -> StudioTileMark.PICKED
            else                                   -> StudioTileMark.NONE
        }
    }

    /** Unchecked stored assets on the active tab only, like [selectedOnTab]. */
    val removalsOnTab: Int
        get() = STUDIO_TABS.getOrNull(tabIndex)?.kind?.let { kind -> removals.keys.count { it.kind == kind } } ?: 0

    val queueSummary: StudioQueueSummary
        get() {
            val kind = STUDIO_TABS.getOrNull(tabIndex)?.kind ?: return StudioQueueSummary()
            val items = queue.filter { it.gameId == game?.id && it.key.kind == kind }
            return StudioQueueSummary(
                toAdd = selectedOnTab,
                toRemove = removalsOnTab,
                added = items.count { it.state == StudioQueueState.ADDED },
                failed = items.count { it.state == StudioQueueState.FAILED },
                total = items.size,
            )
        }

    /** What the stored-assets manager lists: the active slot's assets, lowest position first. */
    val managedAssets: List<com.playfieldportal.feature.artwork.store.StudioArtworkSlot>
        get() = library.slots

    /**
     * How many picks must be unchecked before Apply fits, or 0 when it already does.
     *
     * A slot holds [ArtworkFileNaming.MAX_SORT_ORDER] + 1 positions. `nextSortOrder` clamps to the
     * last one instead of refusing, so an append past the cap silently rewrites position 99 — which
     * makes this the only place the cap can actually be enforced. Counted against [library], which
     * already includes everything added this open.
     */
    val overCapacityBy: Int
        get() {
            if (!selectsMultiple) return 0
            val after = library.slots.size - removalsOnTab + selectedOnTab
            val capacity = com.playfieldportal.feature.artwork.store.ArtworkFileNaming.MAX_SORT_ORDER + 1
            return (after - capacity).coerceAtLeast(0)
        }

    /**
     * The confirmation on screen, or null. Both confirmations are described here so the screen and
     * the gamepad handler each serve them through one path (task 5.3). The apply confirmation wins
     * a tie it cannot actually have: [applyChanges] is unreachable while a candidate is open.
     */
    val confirmPrompt: StudioConfirmPrompt?
        get() = when {
            applyConfirmOpen -> StudioConfirmPrompt(
                kind = StudioConfirmKind.APPLY,
                title = STUDIO_TABS.getOrNull(tabIndex)
                    ?.let { studioApplyTitle(it.kind, queueSummary.toAdd, queueSummary.toRemove) }
                    ?: "Apply changes?",
                rows = StudioApplyChoice.entries.map {
                    // Apply reads as destructive when it deletes stored artwork.
                    StudioConfirmRow(it.label, isDestructive = it == StudioApplyChoice.APPLY && queueSummary.toRemove > 0)
                },
                selectedIndex = applyConfirmIndex,
            )
            replacePromptOpen -> StudioConfirmPrompt(
                kind = StudioConfirmKind.REPLACE,
                title = "${STUDIO_TABS.getOrNull(tabIndex)?.label ?: "This artwork"} is already this image",
                rows = StudioReplaceChoice.entries.map {
                    // Replace overwrites the one stored previous version, so it is the destructive row.
                    StudioConfirmRow(it.label, isDestructive = it == StudioReplaceChoice.REPLACE)
                },
                selectedIndex = replacePromptIndex,
            )
            else -> null
        }

    /**
     * Actions for the current slot and then the active source, in menu order. Entries that do not
     * apply are hidden.
     */
    val availableActions: List<StudioAction>
        get() = buildList {
            val kind = STUDIO_TABS.getOrNull(tabIndex)?.kind
            val hasCurrent = currentUri != null
            if (queueSummary.hasChanges) add(StudioAction.APPLY_CHANGES)
            if (canPreviewFocused) add(StudioAction.PREVIEW)
            if (queueSummary.failed > 0) {
                add(StudioAction.RETRY_FAILED)
                add(StudioAction.REMOVE_FAILED)
            }
            // Only where there is an order to change: several positions, on a tab that has them.
            if (selectsMultiple && library.slots.size > 1) add(StudioAction.MANAGE_ASSETS)
            if (hasCurrent && kind != null && kind in CROPPABLE_KINDS) add(StudioAction.CROP)
            // Crop a pick BEFORE it is applied. Offered whenever a grid tile is focused on a
            // croppable tab — independent of whether the slot already holds art, since this frames
            // the candidate rather than what is already there. Plain Apply is untouched. A stored
            // file is not a candidate; Adjust Crop is how that gets re-framed.
            val tile = focusedTile
            if (kind != null && kind in CROPPABLE_KINDS && tile != null && !tile.isVideo && tile.provider != LOCAL_ART) {
                add(StudioAction.CROP_BEFORE_APPLY)
            }
            if (info?.hasPrevious == true) add(StudioAction.RESTORE_PREVIOUS)
            if (info?.originUrl != null) add(StudioAction.RESET_DEFAULT)
            if (hasCurrent) add(StudioAction.CLEAR)
            if (hasCurrent) add(StudioAction.FILE_INFO)
            // SteamGridDB's Mature filter is one of its filter rows now, above these actions.
            // The match row's two buttons are touch targets with no controller path, so they are
            // offered here too (task 2.4), under exactly the row's own visibility rules.
            if (matchProvider != null) add(StudioAction.CHANGE_MATCH)
            if (matchIsConfirmed) add(StudioAction.FORGET_MATCH)
        }

    /** The source whose grid is on screen. */
    val activeSource: StudioSource? get() = StudioSource.entries.getOrNull(sourceIndex)

    private val ssTypesOnTab: List<String>
        get() = STUDIO_TABS.getOrNull(tabIndex)?.kind?.let { SS_TYPES_FOR_KIND[it] }.orEmpty()

    /** The active source's filter rows, at the top of the Triangle menu. Empty when it has none. */
    val filterRootRows: List<StudioFilterRow>
        get() = studioFilterRows(this, group = null, ssTypes = ssTypesOnTab, regions = filterRegions)

    /** The open filter list's rows, or empty at the menu's root. */
    val filterGroupRows: List<StudioFilterRow>
        get() = filterGroup?.let { studioFilterRows(this, it, ssTypesOnTab, filterRegions) }.orEmpty()

    /** The Triangle menu's root, in order: the active source's filters, then [availableActions]. */
    val menuItems: List<StudioMenuItem>
        get() = filterRootRows.map { StudioMenuItem.Filter(it.option) } + availableActions.map { StudioMenuItem.Action(it) }

    /** The action under the cursor, or null when the cursor is on a filter row. */
    val actionsSelectedAction: StudioAction? get() = (actionsSelected as? StudioMenuItem.Action)?.action

    /** Whether [source] is filtered narrower than its defaults — the ● on its chip. */
    fun sourceFiltered(source: StudioSource): Boolean =
        STUDIO_TABS.getOrNull(tabIndex)?.kind?.let { filters.isActive(source, it, includeNsfw) } == true

    /** The browse found art and the filters hid all of it: the grid offers Clear Filters instead. */
    val filtersHideEverything: Boolean get() = !resultsLoading && totalResults == 0 && unfilteredTotal > 0

    /**
     * Where the cursor sits in the current [menuItems] (task 3.3). Prefers the position of
     * [actionsSelected] so the cursor follows that row across a list change; falls back to the raw
     * [actionsIndex], clamped, when that row is no longer offered.
     */
    val resolvedActionsIndex: Int
        get() {
            val items = menuItems
            if (items.isEmpty()) return 0
            val byItem = actionsSelected?.let(items::indexOf) ?: -1
            return if (byItem >= 0) byItem else actionsIndex.coerceIn(0, items.lastIndex)
        }
}

enum class StudioAction(val label: String) {
    APPLY_CHANGES("Apply Changes"),
    PREVIEW("Preview"),
    RETRY_FAILED("Retry Failed Downloads"),
    REMOVE_FAILED("Remove Failed Downloads"),
    MANAGE_ASSETS("Reorder Stored Artwork"),
    CROP("Adjust Crop / Position"),
    CROP_BEFORE_APPLY("Crop Before Applying"),
    RESTORE_PREVIOUS("Restore Previous"),
    RESET_DEFAULT("Reset to Scraped Default"),
    CLEAR("Clear Artwork"),
    FILE_INFO("View File Information"),
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
internal val SS_TYPES_FOR_KIND: Map<ArtworkKind, List<String>> = mapOf(
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

// Resolved in the background when the Studio opens (AD-22). Never TheGamesDB (monthly allowance on
// free keys) and never ScreenScraper (one request slot): those two resolve only when visited.
private val BACKGROUND_SOURCES = listOf(
    StudioSource.STEAMGRIDDB to MatchProvider.STEAMGRIDDB,
    StudioSource.IGDB to MatchProvider.IGDB,
)

// The saved id each provider resolves from first (tier 1). One changing under the Studio makes the
// matches remembered for that provider stale.
private val PROVIDER_IDS: List<Pair<MatchProvider, (Game) -> Long?>> = listOf(
    MatchProvider.SCREENSCRAPER to { it.ssId },
    MatchProvider.STEAMGRIDDB to { it.steamGridDbId },
    MatchProvider.THEGAMESDB to { it.tgdbId },
    MatchProvider.IGDB to { it.igdbId },
)

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
 * and changed. LB/RB switch destination tabs — and only that, except in the grid, where they page
 * instead; D-pad Left/Right act on the current level (the source row, then the tile cursor);
 * A previews→applies, B backs out (the source row closes), X opens search, Y opens the per-slot
 * options.
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
    private val steamStorefront: com.playfieldportal.feature.artwork.api.SteamStorefrontApi,
    private val videoSnapTranscoder: com.playfieldportal.feature.artwork.video.VideoSnapTranscoder,
    private val matchEvidence: ProviderMatchEvidence,
    private val cropPreviewPreferences: com.playfieldportal.core.data.repository.CropPreviewPreferences,
    private val sgdbStylePreferences: com.playfieldportal.core.data.repository.SgdbStylePreferences,
    titleSearchStore: com.playfieldportal.feature.artwork.match.TitleSearchStore,
) : ViewModel(), ArtworkStudioActions {

    /**
     * Title searches, shared by the matcher and the Change Match picker, so the picker's seeded
     * search and a re-resolution after a match is forgotten never ask twice. Remembered in memory
     * for this open, and kept between opens by [titleSearchStore] (AD-21). Matches themselves are
     * remembered in [matchMemo].
     */
    private val titleSearches = CachingMatchEvidence(matchEvidence, titleSearchStore)

    /** Tiers 1-3 only; the ranked picker below Tier 3 is deferred (AD-4). */
    private val matcher = GameMatcher(titleSearches)

    private val appCacheDir: java.io.File get() = appContext.cacheDir

    private val _uiState = MutableStateFlow(ArtworkStudioUiState())
    val uiState: StateFlow<ArtworkStudioUiState> = _uiState.asStateFlow()

    // Declared AFTER _uiState on purpose: Kotlin runs initializers in declaration order, so an
    // init block above it would collect into a field that does not exist yet.
    init {
        // Settings ▸ Artwork writes the same preference, so a change made there shows up here even
        // while the Studio is open.
        viewModelScope.launch {
            cropPreviewPreferences.enabledFlow.collect { enabled ->
                _uiState.update { it.copy(cropPreviewEnabled = enabled) }
            }
        }
    }

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

    /**
     * Where file, decode and download work runs. Tests point it at their own dispatcher so that
     * work cannot outlive the test; a constructor parameter would need a Hilt binding for one seam.
     */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    // The one job draining the download queue (task 5.2). Never cancelled by close(): picks the user
    // chose to add keep downloading after the screen is gone.
    private var queueJob: kotlinx.coroutines.Job? = null

    private var gameId: Long = -1

    /** Follows the open game's row so a write from elsewhere (a scrape, a rename) lands here live. */
    private var gameWatchJob: kotlinx.coroutines.Job? = null

    /** The grid slot's last reported size in dp; null until the screen has measured it. */
    private var gridSlotDp: Pair<Float, Float>? = null

    fun load(gameId: Long) {
        // Always clear the closed flag: the VM survives across open/close (host-scoped), so a
        // stale closed=true from a prior B-press would otherwise slam the screen shut on reopen.
        // Every open starts on the first tab's source row with no picks: a pick left over from a
        // closed screen would be invisible, and so would a tab or source left over from the last
        // open. sourceIndex = 0 makes landOnAvailableSource() below pick the first usable source.
        // The queue keeps what is still downloading and forgets the rest.
        val capacity = capacityFor(0)
        _uiState.update { s ->
            s.copy(
                tabIndex = 0, sourceIndex = 0, zone = StudioZone.SOURCES,
                gridColumns = capacity?.columns ?: s.gridColumns,
                gridRows = capacity?.rows ?: s.gridRows,
                closed = false, selection = emptyMap(), removals = emptyMap(),
                leavePromptOpen = false, applyConfirmOpen = false, replacePromptOpen = false,
                destructiveConfirm = null, managerOpen = false, actionsOpen = false, filterGroup = null,
                queue = s.queue.filter { it.state == StudioQueueState.QUEUED || it.state == StudioQueueState.DOWNLOADING },
            )
        }
        // Each open asks the providers afresh: a search that failed last time (providers report a
        // failure as no hits) must not keep the game unmatched for good.
        titleSearches.clear()
        matchMemo.clear()
        if (this.gameId == gameId && _uiState.value.game != null) {
            // Same game reopened. The VM outlives the screen, so a key added or removed in Settings
            // since the last open has to be re-read here — reading it once per game is what kept a
            // freshly entered TheGamesDB key from ever taking effect.
            viewModelScope.launch {
                // The row too: a metadata update since the last open may have renamed the game,
                // and the held copy would keep "Use game title" on the old name.
                gameRepository.getById(gameId)?.let(::adoptGame)
                watchGame(gameId)
                refreshProviderAvailability()
                resetFilters(_uiState.value.game)
                // Always, not only when a key changed: the reset above moved the tab and source, so
                // the grid still holds the last open's results. The result cache makes this cheap.
                landOnAvailableSource()
                loadResults()
                // What the slot holds may have changed while the screen was closed: a queue that
                // finished after Add and Close, or a scrape.
                refreshCurrent()
                // This open's memo was just cleared, so the unvisited providers resolve afresh too.
                resolveInBackground()
            }
            return
        }
        this.gameId = gameId
        // The last game's resolutions must not land in this game's memo or on its match row.
        cancelLoad()
        cancelBackgroundResolutions()
        viewModelScope.launch {
            val game = gameRepository.getById(gameId)
            refreshProviderAvailability()
            resetFilters(game)
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
            loadResults()
            resolveInBackground()
            watchGame(gameId)
        }
    }

    /**
     * Keeps the held row current while the Studio is open. Started only after load() has seeded
     * the game, so the first emission (the row as it is now) is a no-op rather than a second seed.
     */
    private fun watchGame(id: Long) {
        gameWatchJob?.cancel()
        gameWatchJob = viewModelScope.launch {
            gameRepository.observeById(id).collect { fresh ->
                val held = _uiState.value.game
                if (fresh == null || held == null || fresh == held || this@ArtworkStudioViewModel.gameId != id) {
                    return@collect
                }
                // A provider id written from outside makes every match remembered for it stale.
                val movedIds = PROVIDER_IDS.filter { (_, idOf) -> idOf(fresh) != idOf(held) }.map { it.first }
                movedIds.forEach(::forgetMatches)
                val queryBefore = _uiState.value.query
                adoptGame(fresh)
                if (movedIds.isNotEmpty() || !StudioQuery.sameQuery(queryBefore, _uiState.value.query)) {
                    loadResults()
                }
            }
        }
    }

    /**
     * Every open starts with fresh filters (user decision, 2026-09-29): ScreenScraper on the disc's
     * region when it is known, SteamGridDB with the styles remembered for each art type, the rest at
     * their defaults. Mature is left as it was.
     */
    private suspend fun resetFilters(game: Game?) {
        val remembered = try {
            sgdbStylePreferences.styles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Remembered SteamGridDB styles unreadable")
            emptyMap()
        }
        val styles = remembered.mapNotNull { (endpoint, set) ->
            SgdbArtType.entries.firstOrNull { it.endpoint == endpoint }?.let { it to set }
        }.toMap()
        _uiState.update { it.copy(filters = StudioFilters.forOpen(game?.region, styles)) }
    }

    /** The game's own title — what Reset returns the query to, and what "custom" is measured against. */
    private fun gameTitle(): String = _uiState.value.game?.displayTitle.orEmpty()

    /**
     * Takes a fresh read of the game row. A query that still follows the title follows it to the
     * new one; a custom query is the user's and stays, re-measured against the new title.
     */
    private fun adoptGame(fresh: Game) = _uiState.update { s ->
        val title = fresh.displayTitle
        if (s.queryIsCustom) {
            s.copy(game = fresh, queryIsCustom = !StudioQuery.sameQuery(s.query, title))
        } else {
            s.copy(game = fresh, query = title, queryDraft = if (s.searchOpen) s.queryDraft else title)
        }
    }

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
        // Steam's trailers fill ICON1 and Video; only a manual is missing.
        StudioSource.STEAM         -> kind != ArtworkKind.MANUAL
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
        refreshLibrary()
    }

    /**
     * Re-reads what the active slot holds, so an asset stored on an earlier visit shows as added on a
     * multi-asset tab (task 5.2) and as current on a single-art one (task 5.3). Single-art kinds
     * return at most the position-0 record, which is exactly the comparison the replace prompt needs.
     *
     * Only records whose file still opens: a lost file must not read as held while nothing is there.
     * A slot written by the internal (non-portable) store has no record at all, so it reads as empty
     * — the same limitation the ADDED badge already carries.
     */
    private suspend fun refreshLibrary() {
        val kind = tab().kind
        _uiState.update { it.copy(library = StudioLibraryAssets.of(kind, routingStore.studioAssetsOnDisk(gameId, kind))) }
        // Local File's grid IS the library, so it follows every re-read: an add, a removal, a tab.
        val key = activeKey
        if (key != null && key.source == StudioSource.LOCAL) {
            val s = _uiState.value
            showLocalTiles(key, generation, pageIndex = s.page, gridIndex = s.gridIndex)
        }
    }

    /**
     * Local File's grid: the slot's stored files on a multi-asset tab, nothing on a single-art one
     * (where the upload bar is the whole grid).
     */
    private fun showLocalTiles(key: StudioRequestKey, token: Long, pageIndex: Int, gridIndex: Int = 0) {
        val s = _uiState.value
        val tiles = if (s.selectsMultiple) localArtTiles(tab().kind, s.library) else emptyList()
        resultCache[key] = tiles
        showPage(tiles, pageIndex, key, token, gridIndex)
    }

    /**
     * Resolves the active source's match, then browses the active category + source with it. The
     * one entry for both (AD-20): a tab, source, query or filter change calls this and nothing else.
     *
     * Race safety is coroutine ownership, never a delay (AD-6):
     *  1. the previous job — resolution and browse alike — is cancelled outright;
     *  2. the request carries an immutable key and a monotonic token;
     *  3. the response may write to state only while BOTH still match.
     *
     * A match already known (none needed, confirmed, or resolved earlier this open) is used at
     * once, and a cache hit then renders with no loading state at all. Otherwise the grid shows
     * skeletons until the match is resolved and the browse answers, so it never browses without
     * the match and a source switch never leaves another provider's tiles on screen.
     */
    private fun loadResults() {
        loadJob?.cancel()
        val token = ++generation
        val state = _uiState.value
        val source = sourcesForTab().getOrNull(state.sourceIndex) ?: StudioSource.LOCAL
        val kind = tab().kind
        val provider = providerFor(source)
        val known = knownMatch(state, provider)
        _uiState.update {
            it.copy(matchProvider = provider, match = known?.match, matchResolving = known == null, matchFailed = false)
        }

        if (known != null) {
            val key = requestKey(state, source, kind, known.match)
            activeKey = key
            if (source == StudioSource.LOCAL) {
                // Local never browses. Its grid is the slot itself, read fresh rather than from the
                // cache, which would still list a file removed since.
                showLocalTiles(key, token, pageIndex = 0)
                return
            }
            resultCache[key]?.let { cached ->
                showPage(cached, pageIndex = 0, key = key, token = token)
                return
            }
        } else {
            // No key until the match is known: nothing may page or re-page a grid whose request
            // has not been decided yet.
            activeKey = null
        }

        _uiState.update {
            it.copy(resultsLoading = true, results = emptyList(), gridIndex = 0, page = 0,
                pageCount = 0, rangeStart = 0, rangeEnd = 0, totalResults = 0)
        }
        loadJob = viewModelScope.launch {
            // ScreenScraper identifies a ROM best by the catalog's own lookup (name, size and checksum),
            // so that runs before the matcher, which then need not ask by checksum again.
            val romLookup = if (known == null && provider == MatchProvider.SCREENSCRAPER) lookUpSsRomIdentity() else null
            // known == null only ever happens for a real provider (knownMatch answers for none).
            var match = if (known != null) {
                known.match
            } else {
                resolveMatch(checkNotNull(provider), state.query, token, skipRomHash = romLookup != null)
            }
            var key = requestKey(state, source, kind, match)
            activeKey = key
            browse(source, kind, state.query, match, key, token, romLookup)
            if (source != StudioSource.SCREENSCRAPER || !refreshSsIdentityAfterBrowse()) return@launch
            // The browse identified the game and saved its id. Resolve again (Tier 1, no request)
            // and re-browse only if that moved the key — the new key's browse is a media-cache hit.
            match = resolveMatch(MatchProvider.SCREENSCRAPER, state.query, token)
            val movedKey = requestKey(state, source, kind, match)
            if (movedKey == key) return@launch
            key = movedKey
            activeKey = key
            browse(source, kind, state.query, match, key, token)
        }
    }

    // The match is part of the key, so re-pointing the game at another provider entry invalidates
    // exactly its own cached pages and nothing else.
    private fun requestKey(state: ArtworkStudioUiState, source: StudioSource, kind: ArtworkKind, match: GameMatch?) =
        StudioRequestKey.of(state.query, source, kind, state.filters.sgdbRequest(state.includeNsfw), match?.matchKey)

    /**
     * What ScreenScraper's catalog answered when asked to identify the game's ROM, ahead of
     * resolution. [ssId] is the id on the game row afterwards (null when it found nothing), and
     * [medias] the catalog's answer (null when it found nothing).
     */
    private class SsRomLookup(val ssId: Long?, val medias: List<SsCachedMedia>?)

    /**
     * Runs the catalog's ROM identity lookup for a game ScreenScraper has no id for, and takes the
     * row it leaves behind: a hit saves `ss_id`, so the resolution that follows is Tier 1 with no
     * further request.
     *
     * Null when there is nothing for the catalog to hash (no ROM path or URI, as on Windows) or the
     * game already has an id — then the matcher's own tiers run as before, stored checksum included.
     * Null too when the lookup threw, since that asked nothing the matcher could skip.
     */
    private suspend fun lookUpSsRomIdentity(): SsRomLookup? {
        val game = _uiState.value.game ?: return null
        if (game.ssId != null || (game.romPath == null && game.romUri == null)) return null
        val medias = try {
            ssMediaCatalog.mediasFor(gameId, matchedSsId = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ScreenScraper ROM lookup failed")
            return null
        }
        val stored = gameRepository.getById(gameId) ?: game
        currentCoroutineContext().ensureActive()
        if (stored.ssId != game.ssId || stored.romCrc32 != game.romCrc32) {
            _uiState.update { it.copy(game = stored) }
            // Anything remembered for ScreenScraper was resolved against the row before its id.
            forgetMatches(MatchProvider.SCREENSCRAPER)
        }
        return SsRomLookup(stored.ssId, medias)
    }

    /**
     * Fetches [key] unless it is cached, stores the answer under [key], and shows it if still current.
     * [romLookup] is this job's ScreenScraper ROM lookup, if one ran, so the browse does not ask it again.
     */
    private suspend fun browse(
        source: StudioSource,
        kind: ArtworkKind,
        query: String,
        match: GameMatch?,
        key: StudioRequestKey,
        token: Long,
        romLookup: SsRomLookup? = null,
    ) {
        val cached = resultCache[key]
        if (cached != null) {
            showPage(cached, pageIndex = 0, key = key, token = token)
            return
        }
        val fetched = when (source) {
            StudioSource.SCREENSCRAPER -> ssResults(kind, match, romLookup)
            StudioSource.STEAMGRIDDB   -> sgdbResults(kind, query, match, key.sgdb ?: SgdbRequestFilter())
            StudioSource.THEGAMESDB    -> tgdbResults(kind, query, match)
            StudioSource.IGDB          -> igdbResults(kind, query, match)
            StudioSource.STEAM         -> steamResults(kind, query, match)
            StudioSource.LOCAL         -> emptyList()
        }
        // A cancelled request is not an answer. Provider calls wrap themselves in runCatching,
        // which also catches the CancellationException and turns it into "nothing found" — so
        // without this check a source switch mid-load cached an empty page under this key, and
        // returning showed "No results" without ever asking again.
        currentCoroutineContext().ensureActive()
        // Store under the request's OWN key regardless of what is on screen now — a late
        // response still warms its cache entry, it just may not be shown.
        resultCache[key] = fetched
        showPage(fetched, pageIndex = 0, key = key, token = token)
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
            // The cache holds the whole answer; the filters are applied here, as it is shown.
            val shown = it.filters.visible(key.source, key.kind, all)
            val page = StudioPage.of(shown, pageIndex, it.pageSize)
            it.copy(
                unfilteredTotal = all.size,
                filterRegions =
                    if (key.source == StudioSource.SCREENSCRAPER) {
                        StudioFilters.regionChoices(all, StudioFilters.ssRegionFor(it.game?.region))
                    } else {
                        emptyList()
                    },
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
        if (visibleResults().isEmpty()) return
        showPage(all, focused / capacity.pageSize, key, generation, gridIndex = focused % capacity.pageSize)
    }

    // Every SS media of the kind's types — cached lists load free; a game never scraped
    // gets one live scrape-as-you-go lookup (cached + ssId persisted for next time).
    //
    // ScreenScraper media is addressed by game id, not by a title. Unmatched, the catalog identifies
    // the game by its ROM (and saves that identity); matched by title or Change Match, it browses
    // that game's id — without saving a title match to the game row.
    //
    // When [romLookup] ran in this job and the match is its own answer (the id it saved, or no match
    // at all), its medias are used as they are: asking the catalog again would repeat the lookup that
    // just missed, or re-read what it just cached.
    private suspend fun ssResults(kind: ArtworkKind, match: GameMatch?, romLookup: SsRomLookup?): List<StudioArt> {
        val types = SS_TYPES_FOR_KIND[kind] ?: return emptyList()
        val matchedSsId = match?.candidate
            ?.takeIf { it.provider == MatchProvider.SCREENSCRAPER }
            ?.providerGameId?.toLongOrNull()
        val usesLookup = romLookup != null && (matchedSsId == null || matchedSsId == romLookup.ssId)
        val medias = (if (usesLookup) romLookup.medias else ssMediaCatalog.mediasFor(gameId, matchedSsId))
            ?: return emptyList()
        return screenScraperTiles(kind, types, medias)
    }

    /** [ask] is what the request key says SteamGridDB is asked to leave out, never a fresher read of state. */
    private suspend fun sgdbResults(kind: ArtworkKind, query: String, match: GameMatch?, ask: SgdbRequestFilter): List<StudioArt> {
        val types = sgdbTypesFor(kind).ifEmpty { return emptyList() }
        val game = _uiState.value.game ?: return emptyList()
        // Which SteamGridDB game to browse, strongest evidence first. [match] is the one the request
        // key was built with, never a fresher read of state.
        val sgdbMatch = match?.takeIf { it.candidate.provider == MatchProvider.STEAMGRIDDB }
        // 1. A match the user confirmed through Change Match holds whatever they type next: they
        //    already told us which game this is (task 2.3).
        val confirmed = sgdbMatch?.takeIf { it.userConfirmed }?.candidate?.providerGameId?.toLongOrNull()
        // 2. A saved id, but only while the user is still searching for THIS game: the moment they
        //    type something else, the typed title wins.
        val savedId = game.steamGridDbId?.takeIf { StudioQuery.sameQuery(query, game.displayTitle) }
        // 3. The resolved match, when the query produced it. A Tier 1 match ignores the query, and
        //    step 2 already applies the query rule to the saved id.
        val resolved = sgdbMatch
            ?.takeIf { it.tier == MatchTier.CONTENT_ID || it.tier == MatchTier.EXACT_TITLE }
            ?.candidate?.providerGameId?.toLongOrNull()
        val sgdbId = confirmed
            ?: savedId
            ?: resolved
            ?: firstSgdbHit(query, game.platformId)
            ?: return emptyList()
        // No dimension filter, ICON0 included: every grid shape is a valid candidate now
        // that pass 2's crop editor will shape it to the tile. One request per art type; when a
        // tab shows several, each tile's label names its type.
        return types.flatMap { type ->
            steamGridDb.getArt(
                gameId = sgdbId,
                type = type,
                dimensions = emptyList(),
                includeNsfw = ask.mature,
                types = ask.animation.param,
                humor = if (ask.humor) "any" else "false",
                epilepsy = if (ask.epilepsy) "any" else "false",
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
                    // SteamGridDB numbers each art type separately, so a grid and a hero can share an id.
                    providerAssetId = "${type.endpoint}:${art.id}",
                    facets = StudioArtFacets(sgdbType = type, style = art.style, width = art.width, height = art.height),
                )
            }
        }
    }

    /**
     * 4. SteamGridDB's first autocomplete hit for [query], so an ambiguous title still browses
     * something. Asked through [titleSearches], which the matcher already filled for this query, so
     * however many tabs are browsed SteamGridDB is searched once.
     */
    private suspend fun firstSgdbHit(query: String, platformId: String): Long? = try {
        titleSearches.searchByTitle(MatchProvider.STEAMGRIDDB, query, platformId).firstOrNull()?.providerGameId?.toLongOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "SGDB search failed")
        null
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

    /**
     * Steam's store media for the matched appid — a Steam game's own, from its storefront pair — or,
     * unmatched, for the first store search hit, exactly like SteamGridDB's fallback. Keyless.
     */
    private suspend fun steamResults(kind: ArtworkKind, query: String, match: GameMatch?): List<StudioArt> {
        val game = _uiState.value.game ?: return emptyList()
        val appId = match?.candidate?.takeIf { it.provider == MatchProvider.STEAM }?.providerGameId
            ?: firstSteamHit(query, game.platformId)
            ?: return emptyList()
        val media = steamMedia(appId) ?: return emptyList()
        return steamTiles(kind, media)
    }

    /**
     * One request answers every tab, so an app's media is kept for the ViewModel's life, "not on
     * the store" included. A failure is not an answer and is asked again on the next browse.
     */
    private suspend fun steamMedia(appId: String): com.playfieldportal.feature.artwork.api.SteamStoreMedia? {
        if (steamMediaByApp.containsKey(appId)) return steamMediaByApp[appId]
        return when (val result = steamStorefront.storeMedia(appId)) {
            is com.playfieldportal.feature.artwork.api.SteamResult.Ok -> result.value.also { steamMediaByApp[appId] = it }
            is com.playfieldportal.feature.artwork.api.SteamResult.Failure -> {
                Timber.w("Steam store media failed for %s: %s", appId, result.kind)
                null
            }
        }
    }

    private val steamMediaByApp = mutableMapOf<String, com.playfieldportal.feature.artwork.api.SteamStoreMedia?>()

    /** Steam's first store search hit for [query], asked through [titleSearches] like [firstSgdbHit]. */
    private suspend fun firstSteamHit(query: String, platformId: String): String? = try {
        titleSearches.searchByTitle(MatchProvider.STEAM, query, platformId).firstOrNull()?.providerGameId
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Steam search failed")
        null
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
                tabIndex = tabIndex, sourceIndex = 0, zone = StudioZone.SOURCES,
                gridColumns = capacity?.columns ?: it.gridColumns,
                gridRows = capacity?.rows ?: it.gridRows,
            )
        }
        landOnAvailableSource()
        // The query persists across categories: a title the user corrected once should not have
        // to be retyped for every artwork kind.
        viewModelScope.launch { refreshCurrent() }
        // A match never depends on the tab, so this resolves nothing already known this open.
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
        // A match belongs to one provider, so switching source answers that provider's question
        // before the grid is filled. Every source — Local included — goes through loadResults so
        // the request key, the generation token and the cache stay the single description of
        // what is on screen.
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
     *
     * Re-reads the row, and when that identity moved, takes the new row and forgets ScreenScraper's
     * remembered matches, which were resolved against the old one. Returns whether it moved; the
     * caller resolves again.
     */
    private suspend fun refreshSsIdentityAfterBrowse(): Boolean {
        val shown = _uiState.value.game ?: return false
        val stored = gameRepository.getById(gameId) ?: return false
        currentCoroutineContext().ensureActive()
        if (stored.ssId == shown.ssId && stored.romCrc32 == shown.romCrc32) return false
        _uiState.update { it.copy(game = stored) }
        forgetMatches(MatchProvider.SCREENSCRAPER)
        return true
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
        _uiState.update { it.copy(includeNsfw = !it.includeNsfw) }
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
        StudioSource.STEAM         -> MatchProvider.STEAM
        StudioSource.LOCAL, null   -> null
    }

    /**
     * Matches resolved this open, per provider and query, "no match" included (AD-20). A match
     * never depends on the tab, so a tab walk asks each provider once. Cleared on every open, and
     * for one provider whenever its saved id changes. A resolution that failed is never stored.
     */
    private val matchMemo = HashMap<MatchMemoKey, GameMatch?>()

    private data class MatchMemoKey(val provider: MatchProvider, val query: String) {
        companion object {
            private val WHITESPACE = Regex("\\s+")

            // Case and spacing only, like CachingMatchEvidence's search key: punctuation can change
            // what a provider returns.
            fun of(provider: MatchProvider, query: String) =
                MatchMemoKey(provider, query.trim().replace(WHITESPACE, " ").lowercase(java.util.Locale.ROOT))
        }
    }

    /** A known answer to "who is this game", where [match] null means known to be unmatched. */
    private class KnownMatch(val match: GameMatch?)

    /**
     * The match [loadResults] can use without resolving: none for a source with no provider, the
     * user's confirmed match (never re-derived — the user already decided, and the matcher could
     * only ever disagree), or this open's remembered answer. Null means it has to be resolved.
     */
    private fun knownMatch(state: ArtworkStudioUiState, provider: MatchProvider?): KnownMatch? {
        if (provider == null) return KnownMatch(null)
        state.match?.takeIf { it.userConfirmed && it.candidate.provider == provider }?.let { return KnownMatch(it) }
        val key = MatchMemoKey.of(provider, state.query)
        return if (matchMemo.containsKey(key)) KnownMatch(matchMemo[key]) else null
    }

    private fun forgetMatches(provider: MatchProvider) {
        matchMemo.keys.removeAll { it.provider == provider }
        // A background answer still on its way was resolved against the same stale row.
        cancelBackgroundResolutions(provider)
    }

    /**
     * Stops the running resolution and browse — used when a confirmed or forgotten match makes
     * them moot. Clears the resolving flag too, since the cancelled job never lands to clear it,
     * and the match row checks that flag first.
     */
    private fun cancelLoad() {
        loadJob?.cancel()
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
     * Resolves who [provider] thinks the game is, for [query], and writes it to the match row.
     * Runs inside [loadResults]' job, so leaving the source cancels it, request and all.
     *
     * A provider that didn't answer is said as such, never as "no match", and resolves to no match
     * for this browse. The failure is not remembered, so the next visit or a Change Match search
     * asks again.
     */
    private suspend fun resolveMatch(
        provider: MatchProvider,
        query: String,
        token: Long,
        skipRomHash: Boolean = false,
    ): GameMatch? {
        val game = _uiState.value.game ?: return null
        val outcome = joinBackgroundResolution(provider, query)
            ?: resolveAndRemember(game, provider, query, skipRomHash)
        val resolved = outcome.getOrNull()
        // Same token discipline as showPage: a job that is no longer the current one writes nothing.
        if (token == generation) {
            _uiState.update { it.copy(match = resolved, matchResolving = false, matchFailed = outcome.isFailure) }
        }
        return resolved
    }

    /**
     * Runs the matcher and remembers a successful answer in [matchMemo]. Writes no state, so the
     * background resolutions use it as they are. A failure comes back as a failed [Result] and is
     * never remembered; a cancellation is rethrown.
     */
    private suspend fun resolveAndRemember(
        game: Game,
        provider: MatchProvider,
        query: String,
        skipRomHash: Boolean = false,
        background: Boolean = false,
    ): Result<GameMatch?> {
        val startedAt = System.currentTimeMillis()
        val outcome = try {
            Result.success(matcher.resolve(game, provider, query, skipRomHash))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Match resolution failed for %s", provider)
            Result.failure(e)
        }
        // Some provider clients turn a cancellation into an empty answer, which must not be
        // remembered as "no match".
        currentCoroutineContext().ensureActive()
        Timber.d(
            "Studio match %s%s: %s in %d ms",
            provider,
            if (background) " (background)" else "",
            outcome.fold({ it?.tier?.name ?: "none" }, { "failed" }),
            System.currentTimeMillis() - startedAt,
        )
        if (outcome.isSuccess) matchMemo[MatchMemoKey.of(provider, query)] = outcome.getOrNull()
        return outcome
    }

    // ── Background resolution on open (task M.6, AD-22) ───────────────────────

    /** A resolution started on open for a provider the user has not visited yet. */
    private class BackgroundResolution(val key: MatchMemoKey, val outcome: Deferred<Result<GameMatch?>>)

    /** The background resolutions still running, by provider. Each removes itself when it ends. */
    private val backgroundResolutions = HashMap<MatchProvider, BackgroundResolution>()

    /**
     * Starts resolving SteamGridDB and IGDB for the query at open, so switching to either is
     * instant. Only those two (AD-22): TheGamesDB's free keys have a monthly allowance, and
     * ScreenScraper has one request slot, so both resolve only when visited.
     *
     * Skipped for a provider with no key, the active one (its own job is resolving it), one
     * already answered this open, or one already resolving this query. Each runs on its own, so
     * one failing never stops the other, and a failure is not remembered. A later query change is
     * resolved on visit, not here.
     */
    private fun resolveInBackground() {
        val state = _uiState.value
        val game = state.game ?: return
        val active = providerFor(sourcesForTab().getOrNull(state.sourceIndex))
        BACKGROUND_SOURCES.forEach { (source, provider) ->
            if (provider == active || source in state.unavailableSources) return@forEach
            val key = MatchMemoKey.of(provider, state.query)
            if (matchMemo.containsKey(key) || backgroundResolutions[provider]?.key == key) return@forEach
            backgroundResolutions.remove(provider)?.outcome?.cancel()
            // Lazy, so the entry is in the map before the resolution can finish and remove it.
            val outcome = viewModelScope.async(start = CoroutineStart.LAZY) {
                resolveAndRemember(game, provider, state.query, background = true)
            }
            val entry = BackgroundResolution(key, outcome)
            backgroundResolutions[provider] = entry
            outcome.invokeOnCompletion { backgroundResolutions.remove(provider, entry) }
            outcome.start()
        }
    }

    /**
     * The answer of a background resolution already running for [provider] and [query], so a visit
     * that arrives mid-flight waits for it instead of asking again. Null when there is none, or
     * when it was cancelled while waited on (a forgotten match, a new game), in which case the
     * visit resolves for itself.
     */
    private suspend fun joinBackgroundResolution(provider: MatchProvider, query: String): Result<GameMatch?>? {
        val running = backgroundResolutions[provider]?.takeIf { it.key == MatchMemoKey.of(provider, query) } ?: return null
        return try {
            running.outcome.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private fun cancelBackgroundResolutions(provider: MatchProvider? = null) {
        val cancelled = if (provider == null) backgroundResolutions.values.toList() else listOfNotNull(backgroundResolutions[provider])
        cancelled.forEach { it.outcome.cancel() }
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
            val startedAt = System.currentTimeMillis()
            val outcome = runCatching { changeMatchCandidates(provider, query, game.platformId) }
            // A cancelled search is not an answer: a newer search, or the closed picker, owns the
            // picker's state now.
            ensureActive()
            outcome.onFailure { Timber.w(it, "Change Match search failed") }
            Timber.d(
                "Studio Change Match %s: %s in %d ms, every platform: %b",
                provider,
                outcome.fold({ "${it.first.size} candidates" }, { "failed" }),
                System.currentTimeMillis() - startedAt,
                _uiState.value.changeMatchSearchingEveryPlatform,
            )
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
        cancelLoad()
        // Every remembered answer for this provider predates the id about to be saved.
        forgetMatches(provider)
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
            gameRepository.getById(gameId)?.let(::adoptGame)
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
        if (_uiState.value.matchProvider == null) return
        _uiState.update { it.copy(destructiveConfirm = StudioDestructive.FORGET_MATCH, actionsOpen = false) }
    }

    private fun performForgetMatch() {
        val provider = _uiState.value.matchProvider ?: return
        cancelLoad()
        forgetMatches(provider)
        _uiState.update { it.copy(match = null, changeMatchOpen = false, actionsOpen = false) }
        viewModelScope.launch {
            gameRepository.updateProviderMatch(gameId, provider.name, null)
            gameRepository.getById(gameId)?.let(::adoptGame)
            // Nothing is confirmed or remembered for this provider now, so this re-derives.
            loadResults()
        }
    }

    // ── Search (task 1.1) ─────────────────────────────────────────────────────

    /** Opens the search field, pre-filled with the active query and fully selectable. */
    override fun openSearch() = _uiState.update {
        it.copy(
            searchOpen = true, searchButton = null, queryDraft = it.query, actionsOpen = false, showFileInfo = false,
        )
    }

    /** BACK on PFP's keyboard: off the field and onto the card's buttons, starting at Search. */
    override fun leaveSearchField() = _uiState.update {
        if (it.searchOpen) it.copy(searchButton = StudioSearchButton.SEARCH) else it
    }

    private fun moveSearchButton(step: Int) = _uiState.update { s ->
        val current = s.searchButton ?: return@update s
        val next = StudioSearchButton.entries.getOrNull(current.ordinal + step) ?: return@update s
        s.copy(searchButton = next)
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
            // it, in which case loadResults keeps their confirmed match.
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
        if (index < 0 || index * _uiState.value.pageSize >= visibleResults().size) return
        showPage(activeResults(), index, key, generation)
    }

    /** [activeResults] as the filters leave them — what the pages are cut from. */
    private fun visibleResults(): List<StudioArt> {
        val key = activeKey ?: return emptyList()
        return _uiState.value.filters.visible(key.source, key.kind, activeResults())
    }

    override fun openCandidate(index: Int) {
        val art = _uiState.value.results.getOrNull(index) ?: return
        _uiState.update { it.copy(candidate = art, gridIndex = index) }
        // Manuals preview as a paged PDF — pull the file down first (reused by Apply).
        if (tab().kind == ArtworkKind.MANUAL) {
            _uiState.update {
                it.copy(manualDownloading = true, candidateManualPath = null, manualPage = 0, manualPageCount = 0)
            }
            viewModelScope.launch(ioDispatcher) {
                val tmp = downloadToCache(art.url, ".pdf")
                _uiState.update { it.copy(manualDownloading = false, candidateManualPath = tmp?.absolutePath) }
            }
        }
    }

    /**
     * Checks or unchecks the tile at [index] on a multi-asset tab (tasks 5.1, 5.2). A new asset is picked
     * to add. One the slot already holds starts checked, so unchecking it marks it for removal and
     * checking it again keeps it. Keyed by [StudioArtKey], never by [index], so a change survives paging.
     * A download still in flight, or a failed one (Retry / Remove Failed), cannot be toggled. A
     * single-art tab ignores it: one slot has nothing to pick several of.
     */
    override fun toggleSelection(index: Int) = _uiState.update { s ->
        val art = s.results.getOrNull(index)
        if (art == null || !s.selectsMultiple) return@update s
        val key = StudioArtKey.of(STUDIO_TABS[s.tabIndex].kind, art)
        when (s.tileMarkOf(art)) {
            StudioTileMark.QUEUED, StudioTileMark.DOWNLOADING, StudioTileMark.FAILED -> s
            // Not reachable — CURRENT is a single-art mark and this returned above on those tabs.
            StudioTileMark.CURRENT   -> s
            // Each change also takes the cursor off the upload bar, onto the tile it changed.
            StudioTileMark.ADDED     -> s.copy(gridIndex = index, localBarCursor = false, removals = s.removals + (key to art))
            StudioTileMark.TO_REMOVE -> s.copy(gridIndex = index, localBarCursor = false, removals = s.removals - key)
            StudioTileMark.PICKED    -> s.copy(gridIndex = index, localBarCursor = false, selection = s.selection - key)
            StudioTileMark.NONE      -> s.copy(gridIndex = index, localBarCursor = false, selection = s.selection + (key to art))
        }
    }

    // ── Apply and the download queue (task 5.2) ───────────────────────────────

    /**
     * START, the Apply pill and Apply Changes: asks first, so nothing is added or removed on a stray
     * press — and refuses outright when the picks would not fit, so the confirmation is never the
     * thing that overflows the slot.
     */
    override fun applyChanges() = _uiState.update { s ->
        val over = s.overCapacityBy
        when {
            !s.queueSummary.hasChanges -> s
            over > 0 -> s.copy(
                actionsOpen = false,
                message = STUDIO_TABS.getOrNull(s.tabIndex)?.kind?.let { kind ->
                    val capacity = com.playfieldportal.feature.artwork.store.ArtworkFileNaming.MAX_SORT_ORDER + 1
                    "A game holds ${studioAssetCount(kind, capacity)} at most — uncheck $over to apply"
                } ?: s.message,
            )
            else -> s.copy(applyConfirmOpen = true, applyConfirmIndex = 0, actionsOpen = false)
        }
    }

    /** Apply commits the active tab's changes; Cancel keeps them waiting. */
    fun resolveApplyConfirm(choice: StudioApplyChoice) {
        _uiState.update { it.copy(applyConfirmOpen = false) }
        if (choice == StudioApplyChoice.APPLY) {
            val kind = tab().kind
            commit { it.kind == kind }
        }
    }

    private fun moveApplyConfirmCursor(delta: Int) = _uiState.update {
        it.copy(applyConfirmIndex = (it.applyConfirmIndex + delta).mod(StudioApplyChoice.entries.size))
    }

    /**
     * Replace Anyway is the apply exactly as it was before this task; Cancel keeps the candidate
     * open, so a mistaken press costs nothing but the press.
     */
    fun resolveReplacePrompt(choice: StudioReplaceChoice) {
        _uiState.update { it.copy(replacePromptOpen = false) }
        if (choice == StudioReplaceChoice.REPLACE) performApplyCandidate()
    }

    private fun moveReplacePromptCursor(delta: Int) = _uiState.update {
        it.copy(replacePromptIndex = (it.replacePromptIndex + delta).mod(StudioReplaceChoice.entries.size))
    }

    // ── Stored-assets manager (task 5.4) ──────────────────────────────────────

    /**
     * Opens the reorder panel over the active multi-asset slot. Read-and-reorder only: removal stays
     * the checklist's job, so there is one way to delete an asset rather than two.
     */
    override fun openAssetManager() = _uiState.update {
        if (!it.selectsMultiple) it else it.copy(managerOpen = true, managerIndex = 0, actionsOpen = false)
    }

    override fun closeAssetManager() = _uiState.update { it.copy(managerOpen = false) }

    /** Touch: tapping a row focuses it, so the move controls act on the row the user pointed at. */
    override fun focusManagedAsset(index: Int) = _uiState.update {
        if (index in it.managedAssets.indices) it.copy(managerIndex = index) else it
    }

    private fun moveManagerCursor(delta: Int) = _uiState.update {
        val n = it.managedAssets.size
        if (n == 0) it else it.copy(managerIndex = (it.managerIndex + delta).coerceIn(0, n - 1))
    }

    /** Moves the focused asset [delta] positions, carrying the cursor with it. */
    override fun moveManagedAsset(delta: Int) {
        val s = _uiState.value
        val from = s.managerIndex
        val to = from + delta
        if (from !in s.managedAssets.indices || to !in s.managedAssets.indices) return
        val order = s.managedAssets.map { it.sortOrder }.toMutableList()
        order.add(to, order.removeAt(from))
        commitOrder(order, cursor = to)
    }

    /** Moves the focused asset to position 0 — the one the rail and the Game Detail strip show. */
    override fun makeManagedAssetPrimary() {
        val s = _uiState.value
        val from = s.managerIndex
        if (from <= 0 || from !in s.managedAssets.indices) return
        val order = s.managedAssets.map { it.sortOrder }.toMutableList()
        order.add(0, order.removeAt(from))
        commitOrder(order, cursor = 0)
    }

    /**
     * Writes [order] — the slot's current positions, in their new order — and re-reads.
     *
     * Rows only: the files keep their ordinal names. Relink rebuilds position from those names
     * (`ArtworkImportManager`), and the Windows PC export claims records back by exact name, so
     * renaming to express an order would break every exported game's claims. A Relink therefore
     * restores file order, by design.
     */
    private fun commitOrder(order: List<Int>, cursor: Int) {
        if (_uiState.value.managerBusy) return
        val kind = tab().kind
        val gid = gameId
        _uiState.update { it.copy(managerBusy = true) }
        viewModelScope.launch {
            try {
                routingStore.reorderAssets(gid, kind, order)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Studio asset reorder failed")
                _uiState.update { it.copy(message = "Could not reorder artwork") }
            }
            // Re-reads the rail's thumbnail and [library], so the panel redraws in the written order
            // rather than one this function guessed.
            if (gid == gameId && kind == tab().kind) refreshCurrent()
            _uiState.update {
                it.copy(
                    managerBusy = false,
                    managerIndex = cursor.coerceIn(0, (it.managedAssets.size - 1).coerceAtLeast(0)),
                )
            }
        }
    }

    // ── The confirmation overlay's shared entry points (task 5.3) ─────────────
    // One descriptor on screen, so one activation and one dismissal, routed by which is open.

    override fun resolveConfirm(index: Int) {
        when (_uiState.value.confirmPrompt?.kind) {
            StudioConfirmKind.APPLY   -> StudioApplyChoice.entries.getOrNull(index)?.let(::resolveApplyConfirm)
            StudioConfirmKind.REPLACE -> StudioReplaceChoice.entries.getOrNull(index)?.let(::resolveReplacePrompt)
            null -> Unit
        }
    }

    /** B or a scrim tap: the row that changes nothing. */
    override fun dismissConfirm() {
        when (_uiState.value.confirmPrompt?.kind) {
            StudioConfirmKind.APPLY   -> resolveApplyConfirm(StudioApplyChoice.CANCEL)
            StudioConfirmKind.REPLACE -> resolveReplacePrompt(StudioReplaceChoice.CANCEL)
            null -> Unit
        }
    }

    private fun moveConfirmCursor(delta: Int) {
        when (_uiState.value.confirmPrompt?.kind) {
            StudioConfirmKind.APPLY   -> moveApplyConfirmCursor(delta)
            StudioConfirmKind.REPLACE -> moveReplacePromptCursor(delta)
            null -> Unit
        }
    }

    /**
     * Applies the changes [which] accepts: unchecked stored assets are deleted, then new picks are
     * queued. Deleting first means an append that starts now numbers its position after the deletes
     * have closed their gaps.
     */
    private fun commit(which: (StudioArtKey) -> Boolean) {
        val removals = _uiState.value.removals.filterKeys(which)
        if (removals.isEmpty()) {
            enqueue(which)
            return
        }
        _uiState.update { it.copy(removals = it.removals - removals.keys, actionsOpen = false) }
        viewModelScope.launch {
            removeStored(removals)
            enqueue(which)
        }
    }

    /**
     * Deletes the stored assets behind [removals], each kind's highest position first: every delete
     * closes its gap, which would move the positions below it read before.
     */
    private suspend fun removeStored(removals: Map<StudioArtKey, StudioArt>) {
        val gid = gameId
        var failed = 0
        for ((kind, entries) in removals.entries.groupBy { it.key.kind }) {
            val library = StudioLibraryAssets.of(kind, routingStore.studioAssets(gid, kind))
            val positions = entries.flatMap { library.sortOrdersHolding(it.value) }.distinct().sortedDescending()
            for (position in positions) {
                val removed = try {
                    routingStore.deleteAssetAt(gid, kind, position)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Studio asset removal failed")
                    false
                }
                if (!removed) failed++
            }
        }
        _uiState.update { s ->
            s.copy(
                // A removed asset's download this open is over, or its tile would still read as added.
                queue = s.queue.filterNot { it.gameId == gid && it.key in removals.keys && it.state == StudioQueueState.ADDED },
                message = if (failed > 0) "Some artwork could not be removed" else s.message,
            )
        }
        if (gid == gameId) refreshCurrent()
    }

    override fun retryFailed() {
        val kind = tab().kind
        _uiState.update { s ->
            s.copy(
                actionsOpen = false,
                queue = s.queue.map { if (it.isFailedOn(kind)) it.copy(state = StudioQueueState.QUEUED) else it },
            )
        }
        drainQueue()
    }

    override fun removeFailed() {
        val kind = tab().kind
        _uiState.update { s -> s.copy(actionsOpen = false, queue = s.queue.filterNot { it.isFailedOn(kind) }) }
    }

    private fun StudioQueueItem.isFailedOn(kind: ArtworkKind) =
        gameId == this@ArtworkStudioViewModel.gameId && key.kind == kind && state == StudioQueueState.FAILED

    override fun resolveLeavePrompt(choice: StudioLeaveChoice) {
        _uiState.update { it.copy(leavePromptOpen = false) }
        when (choice) {
            // Every tab's changes, not just this one's: the prompt counted them all.
            StudioLeaveChoice.APPLY   -> { commit { true }; close() }
            StudioLeaveChoice.DISCARD -> { _uiState.update { it.copy(selection = emptyMap(), removals = emptyMap()) }; close() }
            StudioLeaveChoice.STAY    -> Unit
        }
    }

    private fun moveLeavePromptCursor(delta: Int) = _uiState.update {
        it.copy(leavePromptIndex = (it.leavePromptIndex + delta).mod(StudioLeaveChoice.entries.size))
    }

    /** Moves the picks [which] accepts out of the selection and onto the end of the queue. */
    private fun enqueue(which: (StudioArtKey) -> Boolean) {
        _uiState.update { s ->
            val picked = s.selection.filterKeys(which)
            if (picked.isEmpty()) return@update s
            s.copy(
                actionsOpen = false,
                selection = s.selection - picked.keys,
                queue = s.queue + picked.map { (key, art) -> StudioQueueItem(gameId, key, art, StudioQueueState.QUEUED) },
            )
        }
        drainQueue()
    }

    /**
     * Downloads the queue one item at a time, so a long list never opens a connection per pick. At
     * most one job: it runs on the main dispatcher, so an item queued while it works is either found
     * by its next look or finds the job already finished and starts a new one.
     */
    private fun drainQueue() {
        if (queueJob?.isActive == true) return
        queueJob = viewModelScope.launch {
            while (true) {
                val next = _uiState.value.queue.firstOrNull { it.state == StudioQueueState.QUEUED } ?: break
                setQueueState(next, StudioQueueState.DOWNLOADING)
                val path = try {
                    routingStore.studioAppendFromUrl(
                        next.gameId, next.key.kind, next.art.url, next.art.provider, next.art.providerAssetId,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad download is a failed item, never a stopped queue.
                    Timber.w(e, "Studio queue download failed")
                    null
                }
                setQueueState(next, if (path != null) StudioQueueState.ADDED else StudioQueueState.FAILED)
                if (path != null && next.gameId == gameId && next.key.kind == tab().kind) refreshCurrent()
            }
        }
    }

    private fun setQueueState(item: StudioQueueItem, state: StudioQueueState) = _uiState.update { s ->
        s.copy(queue = s.queue.map { if (it.gameId == item.gameId && it.key == item.key) it.copy(state = state) else it })
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

    /**
     * The device picker's answer. A multi-asset tab appends every file ([appendLocal]); a single-art
     * tab has one slot, so its first file replaces what is there.
     */
    fun applyLocal(uris: List<Uri>) {
        val uri = uris.firstOrNull() ?: return
        val kind = tab().kind
        if (com.playfieldportal.feature.artwork.store.ArtworkFileNaming.supportsMultiple(kind)) {
            appendLocal(kind, uris)
            return
        }
        val gid = gameId
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true) }
            val tmp = copyLocalToCache(uri)
            val path = if (tmp != null) {
                routingStore.studioApplyFromFile(gid, kind, tmp, provider = LOCAL_FILE_PROVIDER, originUrl = null)
            } else {
                artworkStore.saveVersionedFromUri(gid, kind, uri)
            }
            finishApply(gid, kind, path, LOCAL_FILE_PROVIDER)
        }
    }

    /**
     * Adds each picked file at the end of [kind]'s slot, one at a time so every append numbers its
     * position after the last one landed. Files past the slot's cap are dropped rather than handed
     * to a store whose position would clamp onto the last asset and overwrite it.
     */
    private fun appendLocal(kind: ArtworkKind, uris: List<Uri>) {
        val gid = gameId
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true) }
            val capacity = com.playfieldportal.feature.artwork.store.ArtworkFileNaming.MAX_SORT_ORDER + 1
            val room = (capacity - routingStore.studioAssets(gid, kind).size).coerceAtLeast(0)
            var added = 0
            for (uri in uris.take(room)) {
                val path = try {
                    copyLocalToCache(uri)?.let { routingStore.studioAppendFromFile(gid, kind, it, LOCAL_FILE_PROVIDER) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One unreadable file is a failed file, never a stopped batch.
                    Timber.w(e, "Studio local append failed")
                    null
                }
                if (path != null) added++
            }
            if (gid == gameId && kind == tab().kind) refreshCurrent()
            val failed = uris.size.coerceAtMost(room) - added
            _uiState.update {
                it.copy(
                    applying = false,
                    previewVersion = it.previewVersion + 1,
                    message = studioLocalAddMessage(kind, added, failed, droppedOverCap = uris.size > room),
                )
            }
        }
    }

    /** Copies a picked document to a cache temp, so the store can validate it, record it and back up. */
    private suspend fun copyLocalToCache(uri: Uri): java.io.File? = withContext(ioDispatcher) {
        runCatching {
            val suffix = "." + (appContext.contentResolver.getType(uri)?.substringAfterLast('/') ?: "bin")
            java.io.File.createTempFile("studio_local_", suffix, appCacheDir).also { f ->
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                } ?: run { f.delete(); return@runCatching null }
            }
        }.onFailure { Timber.w(it, "Could not copy the picked file") }.getOrNull()
    }

    /**
     * Apply from the candidate overlay. On a single-art tab, applying the asset the slot already
     * holds asks first (task 5.3): the write re-downloads identical bytes and backs them up as the
     * record's previous version, destroying the one real previous version there was. Multi-asset tabs never reach
     * here — their tiles pick instead of previewing.
     */
    override fun applyCandidate() {
        val s = _uiState.value
        val art = s.candidate ?: return
        if (!s.selectsMultiple && s.library.holds(tab().kind, art)) {
            _uiState.update { it.copy(replacePromptOpen = true, replacePromptIndex = 0) }
            return
        }
        performApplyCandidate()
    }

    /** The apply itself, once nothing is left to ask. */
    private fun performApplyCandidate() {
        val art = _uiState.value.candidate ?: return
        val kind = tab().kind
        val manualFile = _uiState.value.candidateManualPath?.let { java.io.File(it) }
        val gid = gameId
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true) }
            // A previewed manual is already on disk — store that file instead of re-downloading.
            val path = if (kind == ArtworkKind.MANUAL && manualFile?.exists() == true) {
                routingStore.studioApplyFromFile(gid, kind, manualFile, provider = art.provider, originUrl = art.url)
            } else {
                // Without the asset id a single-art record carries only origin_url, and the holds
                // comparison this task adds would be URL-only forever on the tabs it serves.
                routingStore.studioApplyFromUrl(
                    gid, kind, art.url, provider = art.provider, providerAssetId = art.providerAssetId,
                )
            }
            _uiState.update { it.copy(candidateManualPath = null) }
            finishApply(gid, kind, path, art.provider)
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
     * Local, still refuses, so the menu never opens empty. A focused tile on a multi-asset tab also
     * opens it, for Preview (task 5.1), and so do changes waiting to be applied or failed downloads (5.2).
     */
    override fun openActions() {
        val sgdb = sgdbActive()
        val s = _uiState.value
        if (s.currentUri == null && !sgdb && s.matchProvider == null && !s.canPreviewFocused &&
            !s.queueSummary.hasChanges && s.queueSummary.failed == 0 && s.filterRootRows.isEmpty()
        ) {
            _uiState.update { it.copy(message = STUDIO_NO_OPTIONS_MESSAGE) }
            return
        }
        viewModelScope.launch {
            val info = routingStore.studioInfo(gameId, tab().kind)
            _uiState.update {
                val opened = it.copy(
                    info = info, actionsOpen = true, actionsIndex = 0, showFileInfo = false, filterGroup = null,
                )
                // The cursor starts on the first action, so Preview and Apply Changes stay one press
                // away; the Filters row sits above it, reached with Up.
                val items = opened.menuItems
                val first = items.indexOfFirst { item -> item is StudioMenuItem.Action }.coerceAtLeast(0)
                opened.copy(actionsIndex = first, actionsSelected = items.getOrNull(first))
            }
        }
    }

    override fun closeActions() = _uiState.update { it.copy(actionsOpen = false, showFileInfo = false, filterGroup = null) }

    private fun moveActionsCursor(delta: Int) = _uiState.update {
        val items = it.menuItems
        val n = items.size
        if (n == 0) {
            it
        } else {
            val newIndex = (it.resolvedActionsIndex + delta).mod(n)
            it.copy(actionsIndex = newIndex, actionsSelected = items.getOrNull(newIndex))
        }
    }

    /** A of the Triangle menu's root, or a tap on its row [index]: a filter row or an action. */
    override fun activateMenuItem(index: Int) {
        when (val item = _uiState.value.menuItems.getOrNull(index) ?: return) {
            is StudioMenuItem.Action -> runAction(item.action)
            is StudioMenuItem.Filter -> {
                _uiState.update { it.copy(actionsIndex = index, actionsSelected = item) }
                runFilter(item.option)
            }
        }
    }

    // ── Filters (2026-09-29) ─────────────────────────────────────────────────

    /** A of an open filter list, or a tap on its row [index]. */
    override fun activateFilterRow(index: Int) {
        val row = _uiState.value.filterGroupRows.getOrNull(index) ?: return
        _uiState.update { it.copy(filterGroupIndex = index) }
        runFilter(row.option)
    }

    /**
     * B in a filter list: one level back, on the row that opened it. Style, Region and the other
     * lists return to Filters; Filters returns to the menu's root, where the cursor is still on
     * its row ([ArtworkStudioUiState.actionsSelected]).
     */
    override fun closeFilterGroup() = _uiState.update {
        when (val open = it.filterGroup) {
            null, StudioFilterGroup.FILTERS -> it.copy(filterGroup = null)
            else -> {
                val back = it.copy(filterGroup = StudioFilterGroup.FILTERS)
                val opener = back.filterGroupRows.indexOfFirst { row -> row.option == StudioFilterOption.Open(open) }
                back.copy(filterGroupIndex = opener.coerceAtLeast(0))
            }
        }
    }

    private fun moveFilterCursor(delta: Int) = _uiState.update {
        val last = it.filterGroupRows.lastIndex.coerceAtLeast(0)
        it.copy(filterGroupIndex = (it.filterGroupIndex + delta).coerceIn(0, last))
    }

    /**
     * Does what a filter row says. Toggles and the multi-select Style list keep the menu open; a
     * single-choice list closes it, as the Tracker's Options lists do, so the grid is seen at once.
     *
     * What SteamGridDB is asked (Mature, Humor, Epilepsy Warning, Animation) re-requests through
     * [loadResults], which a cached key answers at once; the rest re-filters what is already here.
     */
    private fun runFilter(option: StudioFilterOption) {
        val kind = tab().kind
        val sgdbType = sgdbFilterType(kind)
        fun closeMenu(s: ArtworkStudioUiState) = s.copy(actionsOpen = false, filterGroup = null)
        when (option) {
            is StudioFilterOption.Open -> _uiState.update {
                val listed = it.copy(filterGroup = option.group)
                listed.copy(filterGroupIndex = listed.filterGroupRows.indexOfFirst { row -> row.checked }.coerceAtLeast(0))
            }
            is StudioFilterOption.Style -> {
                val type = sgdbType ?: return
                val chosen = _uiState.value.filters.sgdbStyles[type].orEmpty()
                val next = if (option.style in chosen) chosen - option.style else chosen + option.style
                _uiState.update { it.copy(filters = it.filters.copy(sgdbStyles = it.filters.sgdbStyles + (type to next))) }
                viewModelScope.launch { sgdbStylePreferences.setStyles(type.endpoint, next) }
                refilter()
            }
            is StudioFilterOption.Dimension -> {
                val type = sgdbType ?: return
                _uiState.update {
                    val sizes = if (option.size == null) it.filters.sgdbDimensions - type else it.filters.sgdbDimensions + (type to option.size)
                    closeMenu(it).copy(filters = it.filters.copy(sgdbDimensions = sizes))
                }
                refilter()
            }
            is StudioFilterOption.Animation -> {
                _uiState.update { closeMenu(it).copy(filters = it.filters.copy(sgdbAnimation = option.animation)) }
                loadResults()
            }
            is StudioFilterOption.Region -> {
                _uiState.update { closeMenu(it).copy(filters = it.filters.copy(ssRegion = option.code)) }
                refilter()
            }
            is StudioFilterOption.Media -> {
                _uiState.update {
                    val media = if (option.type == null) it.filters.ssMedia - kind else it.filters.ssMedia + (kind to option.type)
                    closeMenu(it).copy(filters = it.filters.copy(ssMedia = media))
                }
                refilter()
            }
            StudioFilterOption.Mature -> toggleNsfw()
            StudioFilterOption.Humor -> {
                _uiState.update { it.copy(filters = it.filters.copy(sgdbHumor = !it.filters.sgdbHumor)) }
                loadResults()
            }
            StudioFilterOption.Epilepsy -> {
                _uiState.update { it.copy(filters = it.filters.copy(sgdbEpilepsy = !it.filters.sgdbEpilepsy)) }
                loadResults()
            }
            StudioFilterOption.Clear -> clearFilters()
        }
    }

    /**
     * Clear Filters on the active source. On SteamGridDB it also turns Mature off and forgets every
     * remembered style (user decision, 2026-09-29). Also what A does on a grid the filters emptied.
     */
    override fun clearFilters() {
        val source = _uiState.value.activeSource ?: return
        _uiState.update {
            it.copy(
                filters = it.filters.cleared(source),
                includeNsfw = if (source == StudioSource.STEAMGRIDDB) false else it.includeNsfw,
                actionsOpen = false, filterGroup = null,
            )
        }
        if (source == StudioSource.STEAMGRIDDB) viewModelScope.launch { sgdbStylePreferences.clear() }
        loadResults()
    }

    /** Re-shows the active browse under the current filters, from its first page. */
    private fun refilter() {
        val key = activeKey ?: return
        // A browse still in flight is filtered when it lands.
        if (_uiState.value.resultsLoading) return
        showPage(activeResults(), pageIndex = 0, key = key, token = generation)
    }

    override fun runAction(action: StudioAction) {
        when (action) {
            StudioAction.APPLY_CHANGES    -> applyChanges()
            StudioAction.PREVIEW          -> { closeActions(); openCandidate(_uiState.value.gridIndex) }
            StudioAction.RETRY_FAILED     -> retryFailed()
            StudioAction.MANAGE_ASSETS    -> openAssetManager()
            StudioAction.REMOVE_FAILED    -> removeFailed()
            StudioAction.CROP             -> beginCrop()
            StudioAction.CROP_BEFORE_APPLY -> beginCropForCandidate()
            StudioAction.RESTORE_PREVIOUS -> restorePrevious()
            StudioAction.RESET_DEFAULT    -> resetToScrapedDefault()
            StudioAction.CLEAR            -> { closeActions(); requestClear() }
            StudioAction.FILE_INFO        -> _uiState.update { it.copy(showFileInfo = true) }
            // Through the button's own entry point, so an inert provider explains itself the same way.
            StudioAction.CHANGE_MATCH     -> onChangeMatchPressed()
            StudioAction.FORGET_MATCH     -> forgetMatch()
        }
    }

    private fun restorePrevious() {
        val kind = tab().kind
        val gid = gameId
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, actionsOpen = false) }
            val path = routingStore.restorePrevious(gid, kind)
            if (path == null) {
                _uiState.update { it.copy(applying = false, message = "No previous version to restore") }
            } else {
                repointColumn(gid, kind, path)
                _uiState.update {
                    it.copy(applying = false, currentUri = path, previewVersion = it.previewVersion + 1,
                        message = "${tab().label} restored to previous")
                }
            }
        }
    }

    private fun resetToScrapedDefault() {
        val kind = tab().kind
        val gid = gameId
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, actionsOpen = false) }
            val path = routingStore.resetToScrapedDefault(gid, kind)
            if (path == null) {
                _uiState.update { it.copy(applying = false, message = "Could not re-download the scraped default") }
            } else {
                repointColumn(gid, kind, path)
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
    /**
     * Flips the crop editor's result inset and remembers the choice.
     *
     * Writes through [CropPreviewPreferences], the same store Settings ▸ Artwork writes, so the two
     * places can never drift; the flow collected in `init` brings the new value back into state.
     */
    override fun toggleCropPreview() {
        val next = !_uiState.value.cropPreviewEnabled
        _uiState.update { it.copy(cropPreviewEnabled = next) }
        viewModelScope.launch { cropPreviewPreferences.setEnabled(next) }
    }

    /**
     * Opens the crop editor over a pick that has not been applied yet.
     *
     * The candidate is downloaded to a temp file through the same path the apply queue uses, and
     * held in [ArtworkStudioUiState.cropCandidate] so [applyCrop] can carry its provider through.
     * Nothing is written to the library until Apply — cancelling leaves the slot exactly as it was.
     */
    private fun beginCropForCandidate() {
        val s = _uiState.value
        val kind = tab().kind
        val art = s.results.getOrNull(s.gridIndex) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(actionsOpen = false, cropPreparing = true) }
            val temp = withContext(ioDispatcher) {
                routingStore.candidateToTemp(kind, art.url)
            }
            if (temp == null) {
                _uiState.update { it.copy(cropPreparing = false, message = "Could not download that pick to crop") }
                return@launch
            }
            val (w, h) = withContext(ioDispatcher) { decodeBounds(temp) }
            if (w <= 0 || h <= 0) {
                temp.delete()
                _uiState.update { it.copy(cropPreparing = false, message = "That pick is not an image this can crop") }
                return@launch
            }
            val override = routingStore.cropProfileOverride(gameId, kind)
            // No seed from the slot's stored rect: this is a different image, so it starts centred.
            _uiState.update {
                it.copy(
                    cropPreparing = false, cropEditorPath = temp.absolutePath,
                    cropVideoSourcePath = null, cropCandidate = art,
                    cropProfileOverride = override,
                    cropSrcW = w, cropSrcH = h, cropZoom = 1f,
                    cropCenterX = 0.5f, cropCenterY = 0.5f,
                )
            }
            recomputeCropRect()
        }
    }

    private fun beginCrop() {
        val kind = tab().kind
        viewModelScope.launch {
            _uiState.update { it.copy(actionsOpen = false, cropPreparing = true) }
            val original = routingStore.originalToTemp(gameId, kind)
            if (original == null) {
                _uiState.update { it.copy(cropPreparing = false, message = "Could not open the original to crop") }
                return@launch
            }
            val prepared = withContext(ioDispatcher) {
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
            val override = routingStore.cropProfileOverride(gameId, kind)
            _uiState.update {
                it.copy(
                    cropPreparing = false, cropEditorPath = displayPath, cropVideoSourcePath = videoPath,
                    cropProfileOverride = override,
                    cropSrcW = dims.first, cropSrcH = dims.second, cropZoom = 1f,
                    cropCenterX = seed?.let { r -> (r[0] + r[2]) / 2f } ?: 0.5f,
                    cropCenterY = seed?.let { r -> (r[1] + r[3]) / 2f } ?: 0.5f,
                )
            }
            recomputeCropRect()
        }
    }

    private fun isVideoKind(kind: ArtworkKind) = kind == ArtworkKind.ICON1 || kind == ArtworkKind.VIDEO

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
        val profile = CropProfileRegistry.Default.resolve(
            tab().kind, s.game?.platformId, s.game?.region, s.cropProfileOverride,
        )
        val target = profile.aspect ?: srcAspect
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

    // ── Crop Shape: the per-game crop-profile override (task 6.3) ────────────
    //
    // Two choices, because two is what the shipped kind-defaults table can honestly express:
    // follow the defaults, or frame at the image's own ratio. Choosing PLATFORM_DEFAULT is the
    // Reset — it clears the stored key rather than storing a second name for the same thing.

    override fun openCropOptions() {
        val s = _uiState.value
        // The preview row only where there is an inset to switch: for a kind with no XMB tile it
        // would be a control that does nothing visible.
        val rows = buildList {
            if (cropPreviewChromeFor(tab().kind) != null) add(CropOption.PREVIEW)
            add(CropOption.SHAPE_PLATFORM_DEFAULT)
            add(CropOption.SHAPE_ORIGINAL_IMAGE)
        }
        val current = CropShapeChoice.of(s.cropProfileOverride)
        _uiState.update {
            it.copy(
                cropOptionsOpen = true,
                cropOptionRows = rows,
                cropOptionsIndex = rows.indexOfFirst { row -> row.shape == current }.coerceAtLeast(0),
            )
        }
    }

    override fun closeCropOptions() = _uiState.update { it.copy(cropOptionsOpen = false) }

    override fun moveCropOptionsCursor(delta: Int) = _uiState.update {
        it.copy(cropOptionsIndex = (it.cropOptionsIndex + delta).coerceIn(0, it.cropOptionRows.lastIndex.coerceAtLeast(0)))
    }

    override fun activateCropOption(index: Int) {
        val row = _uiState.value.cropOptionRows.getOrNull(index) ?: return
        val shape = row.shape
        if (shape == null) {
            toggleCropPreview()
            _uiState.update { it.copy(cropOptionsOpen = false) }
            return
        }
        val kind = tab().kind
        val key = shape.storedKey
        viewModelScope.launch {
            // Persisted immediately, not on Apply: the override outlives this crop, and a cancelled
            // crop should still leave the shape you chose for next time.
            routingStore.setCropProfileOverride(gameId, kind, key)
            _uiState.update { it.copy(cropProfileOverride = key, cropOptionsOpen = false) }
            recomputeCropRect()
        }
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
        // Captured with the kind: an ICON1 re-encode takes long enough to close the Studio and open
        // another game's, and `gameId` would then name that game when the clip is finally saved.
        val gid = gameId
        val s = _uiState.value
        val displayPath = s.cropEditorPath ?: return
        val videoPath = s.cropVideoSourcePath
        // Non-null only for a crop-before-apply: its provider has to survive into the record.
        val candidate = s.cropCandidate
        val l = s.cropL; val t = s.cropT; val r = s.cropR; val b = s.cropB
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    applying = true, cropEditorPath = null, cropVideoSourcePath = null,
                    cropCandidate = null, cropProfileOverride = null, cropOptionsOpen = false,
                )
            }
            val rect = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, l, t, r, b)
            // 32 bytes reaches a WebP's VP8X animation flag (byte 20).
            val header = withContext(ioDispatcher) {
                com.playfieldportal.feature.artwork.store.ArtworkTempIO.headerOf(java.io.File(displayPath), size = 32)
            }
            val path = when (CropSave.of(isVideo = videoPath != null, header = header)) {
                // An animated image keeps its animation: the editor's copy of the original is saved
                // whole, and the crop is applied while drawing (Animated Images).
                CropSave.AT_DRAW -> routingStore.saveCropAtDraw(
                    gid, kind, java.io.File(displayPath), rect,
                    candidateOriginUrl = candidate?.url,
                    candidateProvider = candidate?.provider,
                    candidateAssetId = candidate?.providerAssetId,
                ).also { java.io.File(displayPath).delete() }
                CropSave.REENCODE_VIDEO, CropSave.BAKE -> {
                    val baked = if (videoPath != null) {
                        // ICON1: re-encode the video cropped to the frame (Media3 Transformer + Crop).
                        val out = java.io.File.createTempFile("studio_crop_", ".mp4", appCacheDir)
                        val ok = videoSnapTranscoder.transcodeCropped(java.io.File(videoPath), out, l, t, r, b)
                        java.io.File(videoPath).delete()
                        if (ok) out else { out.delete(); null }
                    } else {
                        withContext(ioDispatcher) { bakeCrop(java.io.File(displayPath), l, t, r, b) }
                    }
                    java.io.File(displayPath).delete()
                    if (baked == null) {
                        _uiState.update { it.copy(applying = false, message = "Crop failed") }
                        return@launch
                    }
                    routingStore.saveCropBaked(
                        gid, kind, baked, rect,
                        candidateOriginUrl = candidate?.url,
                        candidateProvider = candidate?.provider,
                        candidateAssetId = candidate?.providerAssetId,
                    )
                }
            }
            if (path == null) {
                _uiState.update { it.copy(applying = false, message = "Could not save the cropped artwork") }
            } else {
                repointColumn(gid, kind, path)
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
        _uiState.update {
            it.copy(
                cropEditorPath = null, cropVideoSourcePath = null,
                cropCandidate = null, cropProfileOverride = null, cropOptionsOpen = false,
            )
        }
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

    /**
     * Repoints the column-backed row of game [gid] for [kind] to [path]; record-only kinds no-op.
     *
     * The game is a parameter, never the `gameId` field: every caller reaches this after a
     * download, a copy or a re-encode, and the Studio is reused from game to game. Reading the
     * field here pointed whichever game was open by then at the first game's file.
     */
    private suspend fun repointColumn(gid: Long, kind: ArtworkKind, path: String?) {
        when (kind) {
            ArtworkKind.ICON           -> gameRepository.updateIconArt(gid, path)
            ArtworkKind.BOX_ART        -> gameRepository.updateBoxArtTile(gid, path)
            ArtworkKind.BOX_3D         -> gameRepository.updateBox3dArt(gid, path)
            ArtworkKind.PHYSICAL_MEDIA -> gameRepository.updatePhysicalMediaArt(gid, path)
            ArtworkKind.HERO           -> gameRepository.updateHeroArt(gid, path)
            ArtworkKind.BACKGROUND     -> gameRepository.updateBoxArt(gid, path)
            ArtworkKind.LOGO           -> gameRepository.updateLogoArt(gid, path)
            else                       -> Unit
        }
    }

    private suspend fun finishApply(gid: Long, kind: ArtworkKind, path: String?, provider: String) {
        if (path == null) {
            _uiState.update { it.copy(applying = false, message = "Could not apply — download or file was rejected") }
            return
        }
        // Column-backed kinds repoint the game row; record-only kinds resolve by fixed name.
        repointColumn(gid, kind, path)
        // The write belonged to the game it was for. What follows redraws the Studio, which may
        // be showing another game by now — and is then not this apply's to redraw.
        if (gid != gameId) {
            _uiState.update { it.copy(applying = false) }
            return
        }
        refreshLibrary()
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

    private fun requestClear() = _uiState.update { it.copy(destructiveConfirm = StudioDestructive.CLEAR_ARTWORK) }

    /** The shared confirm's Confirm: runs whichever action asked, once. */
    fun confirmDestructive() {
        val asked = _uiState.value.destructiveConfirm ?: return
        _uiState.update { it.copy(destructiveConfirm = null) }
        when (asked) {
            StudioDestructive.CLEAR_ARTWORK -> clearCurrent()
            StudioDestructive.FORGET_MATCH  -> performForgetMatch()
        }
    }

    /** The shared confirm's Cancel (and Back): the slot or match is exactly as it was. */
    fun cancelDestructive() = _uiState.update { it.copy(destructiveConfirm = null) }

    private fun clearCurrent() {
        val kind = tab().kind
        val gid = gameId
        viewModelScope.launch {
            // Delete the stored file, its backup + original, and the record; then unwire the column.
            routingStore.clearArtwork(gid, kind)
            repointColumn(gid, kind, null)
            refreshLibrary()
            _uiState.update {
                it.copy(currentUri = null, info = null, previewVersion = it.previewVersion + 1,
                    message = "${tab().label} cleared")
            }
        }
    }

    override fun dismissCandidate() {
        _uiState.value.candidateManualPath?.let { runCatching { java.io.File(it).delete() } }
        _uiState.update {
            it.copy(
                candidate = null, candidateManualPath = null, manualDownloading = false,
                manualPage = 0, manualPageCount = 0,
                // The prompt asks about the candidate; without one there is nothing to ask about.
                replacePromptOpen = false,
            )
        }
    }
    override fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun close() {
        // The ViewModel outlives the screen, so work started for this open must stop here. The
        // download queue is deliberately left running (see load()'s comment on queueJob).
        cancelBackgroundResolutions()
        // Reopening re-reads the row, so nothing written while closed is missed.
        gameWatchJob?.cancel()
        // cancelLoad() only clears matchResolving — resultsLoading is cleared by showPage(), which a
        // cancelled loadJob never reaches, so a reopen of the same game (load()'s early-return path)
        // would otherwise show a spinner over a browse that is never coming back.
        cancelLoad()
        changeMatchJob?.cancel()
        _uiState.update {
            it.copy(
                closed = true,
                resultsLoading = false,
                changeMatchLoading = false,
                changeMatchSearchingEveryPlatform = false,
            )
        }
    }

    /** The screen calls this right after acting on [ArtworkStudioUiState.closed] so a stale
     *  closed=true never survives to instantly re-close the screen on the next open. */
    fun consumeClosed() = _uiState.update { it.copy(closed = false) }

    // ── Controller ────────────────────────────────────────────────────────────

    override fun handleGamepadAction(action: GamepadAction) {
        val s = _uiState.value
        // The shared confirm is hosted by the screen, which takes presses first. One that still gets
        // here means no host took it, so only Back is honoured and nothing can confirm by accident.
        if (s.destructiveConfirm != null) {
            if (action == GamepadAction.BACK) cancelDestructive()
            return
        }
        // Search card. On the field the keyboard owns typing, so the pad only confirms, cancels or
        // steps down to the buttons; on the buttons it moves between Search · Use game title ·
        // Cancel, and UP goes back to the field (which reopens PFP's keyboard).
        if (s.searchOpen) {
            val button = s.searchButton
            if (button == null) {
                when (action) {
                    GamepadAction.SELECT        -> submitSearch()
                    GamepadAction.BACK          -> cancelSearch()
                    GamepadAction.NAVIGATE_DOWN -> leaveSearchField()
                    else -> Unit
                }
            } else {
                when (action) {
                    GamepadAction.NAVIGATE_LEFT  -> moveSearchButton(-1)
                    GamepadAction.NAVIGATE_RIGHT -> moveSearchButton(1)
                    GamepadAction.NAVIGATE_UP    -> _uiState.update { it.copy(searchButton = null) }
                    GamepadAction.BACK           -> cancelSearch()
                    GamepadAction.SELECT -> when (button) {
                        StudioSearchButton.SEARCH         -> submitSearch()
                        StudioSearchButton.USE_GAME_TITLE -> resetSearchToTitle()
                        StudioSearchButton.CANCEL         -> cancelSearch()
                    }
                    else -> Unit
                }
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
        // The crop editor's context menu (task 6.3). Ahead of the crop editor's own branch, because
        // it opens OVER the editor and must take the pad while it is up.
        if (s.cropOptionsOpen) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveCropOptionsCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveCropOptionsCursor(+1)
                GamepadAction.SELECT        -> activateCropOption(_uiState.value.cropOptionsIndex)
                GamepadAction.BACK, GamepadAction.OPEN_CONTEXT_MENU -> closeCropOptions()
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
                // The context button keeps its app-wide meaning here: it opens the editor's menu,
                // which holds the preview switch (task 6.7, one press deeper now) and Crop Shape.
                // Square is NOT free — it opens search everywhere else in the Studio — and START
                // means Apply Changes, so neither could take a third control.
                GamepadAction.OPEN_CONTEXT_MENU -> openCropOptions()
                else -> Unit
            }
            return
        }
        // Confirmations (apply, replace): A activates the row, B takes the row that changes nothing.
        // START is ignored here, so pressing it twice cannot confirm.
        s.confirmPrompt?.let { prompt ->
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveConfirmCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveConfirmCursor(+1)
                GamepadAction.SELECT        -> resolveConfirm(prompt.selectedIndex)
                GamepadAction.BACK          -> dismissConfirm()
                else -> Unit
            }
            return
        }
        // Leave prompt: B from the source row while changes wait to be applied. B again means Stay.
        if (s.leavePromptOpen) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveLeavePromptCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveLeavePromptCursor(+1)
                GamepadAction.SELECT        -> resolveLeavePrompt(StudioLeaveChoice.entries[s.leavePromptIndex])
                GamepadAction.BACK          -> resolveLeavePrompt(StudioLeaveChoice.STAY)
                else -> Unit
            }
            return
        }
        // Stored-assets manager: D-pad moves the cursor, LB/RB move the asset itself, A makes it the
        // primary, B closes. Placed above the actions menu, which is what opened it.
        if (s.managerOpen) {
            when (action) {
                GamepadAction.NAVIGATE_UP    -> moveManagerCursor(-1)
                GamepadAction.NAVIGATE_DOWN  -> moveManagerCursor(+1)
                GamepadAction.PREV_CATEGORY  -> moveManagedAsset(-1)   // LB
                GamepadAction.NEXT_CATEGORY  -> moveManagedAsset(+1)   // RB
                GamepadAction.SELECT         -> makeManagedAssetPrimary()
                GamepadAction.BACK           -> closeAssetManager()
                else -> Unit
            }
            return
        }
        // A filter list opened from the Triangle menu (Filters, or a list inside it): B steps back one level.
        if (s.actionsOpen && s.filterGroup != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveFilterCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveFilterCursor(+1)
                GamepadAction.SELECT        -> activateFilterRow(s.filterGroupIndex)
                GamepadAction.BACK          -> closeFilterGroup()
                GamepadAction.OPEN_CONTEXT_MENU -> closeActions()   // Triangle closes from any depth
                else -> Unit
            }
            return
        }
        if (s.actionsOpen) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveActionsCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveActionsCursor(+1)
                GamepadAction.SELECT        -> activateMenuItem(s.resolvedActionsIndex)
                GamepadAction.BACK          ->
                    if (s.showFileInfo) _uiState.update { it.copy(showFileInfo = false) } else closeActions()
                GamepadAction.OPEN_CONTEXT_MENU -> closeActions()
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
        // Two levels: SOURCES → GRID. Confirm descends, BACK ascends, and closes from the source row.
        //
        // The two horizontal pairs mean ONE thing each rather than mirroring each other:
        //
        //   LB/RB     the artwork type — everywhere except the grid, which takes them back for
        //             paging, because that is the only level with more than one screen of content.
        //   D-pad ←/→ whatever the current level holds: the source row, then the tile cursor.
        //             Nothing at the tab level, so the artwork type cannot be changed by accident
        //             while arrowing around.
        //
        // D-pad up/down only moves inside the grid, clamped at the page edges: paging is
        // exclusively LB/RB or the on-screen pills.
        when (action) {
            GamepadAction.BACK -> when (s.zone) {
                StudioZone.SOURCES ->
                    if (s.selection.isNotEmpty() || s.removals.isNotEmpty()) {
                        _uiState.update { it.copy(leavePromptOpen = true, leavePromptIndex = 0) }
                    }
                    else close()
                StudioZone.GRID    -> _uiState.update { it.copy(zone = StudioZone.SOURCES) }
            }
            GamepadAction.NAVIGATE_LEFT -> when (s.zone) {
                // The artwork type moves on the shoulders alone, so arrowing about never changes it.
                StudioZone.SOURCES -> cycleSource(-1)
                // The upload bar is one wide stop, so there is nothing beside it to move to.
                StudioZone.GRID    ->
                    if (!s.localBarFocused && s.gridIndex > 0) _uiState.update { it.copy(gridIndex = s.gridIndex - 1) }
            }
            GamepadAction.NAVIGATE_RIGHT -> when (s.zone) {
                StudioZone.SOURCES -> cycleSource(+1)
                StudioZone.GRID    ->
                    if (!s.localBarFocused && s.gridIndex < s.results.lastIndex) {
                        _uiState.update { it.copy(gridIndex = s.gridIndex + 1) }
                    }
            }
            // Local File's upload bar sits above the top row: up from any top-row tile reaches it,
            // and down from it lands on the first tile, if the slot has one.
            GamepadAction.NAVIGATE_UP -> when {
                s.zone != StudioZone.GRID || s.localBarFocused -> Unit
                s.gridIndex >= s.gridColumns -> _uiState.update { it.copy(gridIndex = s.gridIndex - s.gridColumns) }
                s.localSourceActive          -> _uiState.update { it.copy(localBarCursor = true) }
            }
            GamepadAction.NAVIGATE_DOWN -> when {
                s.zone != StudioZone.GRID -> Unit
                s.localBarFocused ->
                    if (s.results.isNotEmpty()) _uiState.update { it.copy(localBarCursor = false, gridIndex = 0) }
                s.gridIndex + s.gridColumns <= s.results.lastIndex ->
                    _uiState.update { it.copy(gridIndex = s.gridIndex + s.gridColumns) }
            }
            // LB/RB are the artwork type, until the grid claims them for paging. Cycling the type
            // from the source row runs selectTab, which lands on that type's first available
            // source — sources belong to a type, so carrying an index across is meaningless.
            GamepadAction.PREV_CATEGORY -> when (s.zone) {   // LB
                StudioZone.SOURCES -> cycleTab(-1)
                StudioZone.GRID    -> previousPage()
            }
            GamepadAction.NEXT_CATEGORY -> when (s.zone) {   // RB
                StudioZone.SOURCES -> cycleTab(+1)
                StudioZone.GRID    -> nextPage()
            }
            GamepadAction.SELECT -> when (s.zone) {
                // Local File enters its grid on the upload bar, the one thing every tab's Local has.
                StudioZone.SOURCES -> _uiState.update { it.copy(zone = StudioZone.GRID, localBarCursor = s.localSourceActive) }
                // A multi-asset tab picks tiles; Preview is in the Triangle menu there (task 5.1).
                StudioZone.GRID    -> when {
                    // Nothing to act on: the filters hid every result, so A clears them.
                    s.filtersHideEverything -> clearFilters()
                    s.localBarFocused -> requestLocalPick()
                    s.selectsMultiple -> toggleSelection(s.gridIndex)
                    else              -> openCandidate(s.gridIndex)
                }
            }
            // X / Square focuses the search field, from any level.
            GamepadAction.CHANGE_SORT -> openSearch()
            // START applies the active tab's changes, as it confirms in the other pickers (task 5.2).
            // SteamGridDB's mature filter, which it used to toggle, is in the Triangle menu.
            GamepadAction.HOME -> applyChanges()
            // Y / Triangle opens the per-slot options menu (crop, restore, reset, clear, info) —
            // XMB-style context menu, available at every level.
            GamepadAction.OPEN_CONTEXT_MENU -> openActions()
        }
    }
}

/** The search card's buttons, left to right. */
enum class StudioSearchButton { SEARCH, USE_GAME_TITLE, CANCEL }
