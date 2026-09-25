package com.playfieldportal.feature.xmb.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerOption
import com.playfieldportal.feature.xmb.ui.collection.CollectionPickerUi
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.NavigationLogger
import com.playfieldportal.core.navigation.NavigationNode
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.match.MatchConfidence
import com.playfieldportal.feature.artwork.match.MatchProvider
import com.playfieldportal.feature.artwork.match.Storefront
import com.playfieldportal.feature.artwork.match.StorefrontMatchRepository
import com.playfieldportal.feature.artwork.match.MetadataApply
import com.playfieldportal.feature.artwork.match.MetadataApplyPolicy
import com.playfieldportal.feature.artwork.match.MetadataField
import com.playfieldportal.feature.artwork.match.MetadataFieldRow
import com.playfieldportal.feature.artwork.match.MetadataPreset
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.feature.artwork.store.ArtworkKind
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import kotlinx.coroutines.flow.first
import com.playfieldportal.feature.artwork.store.ArtworkStore
import com.playfieldportal.feature.launcher.EmulatorIntentResolver
import com.playfieldportal.feature.launcher.EmulatorLaunchResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.LaunchDispatchResult
import com.playfieldportal.feature.launcher.ResolvedLaunch
import com.playfieldportal.feature.launcher.byLaunchPreference
import com.playfieldportal.feature.launcher.stabilizeCore
import com.playfieldportal.feature.launcher.supportsPlatform
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.roundToInt

// ── Artwork type ──────────────────────────────────────────────────────────────
enum class ArtworkType { ICON, HERO, BACKGROUND }

val ArtworkType.displayLabel: String
    get() = when (this) {
        ArtworkType.ICON       -> "Game Icon"
        ArtworkType.HERO       -> "Hero Banner"
        ArtworkType.BACKGROUND -> "Background"
    }

// ── Unified artwork picker item ───────────────────────────────────────────────
//
// Used by all grid-based sources (SGDB, IGDB, TheGamesDB).
// SGDB items carry a thumbUrl for faster thumbnail loading; other sources use
// the full URL as thumb since no separate thumbnail endpoint is available.
// Mirrors VideoRepositoryImpl / Settings > Video — the pinned external player package.
private val KEY_VIDEO_DEFAULT_PLAYER = stringPreferencesKey("video_default_player")

/** One tile in the Game Detail media strip - videos lead, then screenshots/title screens. */
data class DetailMedia(val uri: String, val isVideo: Boolean)

data class ArtPickerItem(
    val url: String,
    val thumbUrl: String? = null,
    val label: String? = null,
)

// ── UI state ──────────────────────────────────────────────────────────────────
data class GameDetailUiState(
    val game: Game? = null,
    val platform: PlatformEntity? = null,
    /** All rows in the loaded set; empty for ordinary single-ROM/app entries. */
    val discMembers: List<Game> = emptyList(),
    val selectedDiscId: Long? = null,
    val isLoading: Boolean = true,
    val isEditingNote: Boolean = false,
    val noteText: String = "",
    val isFetchingArtwork: Boolean = false,
    val artworkMessage: String? = null,
    val launchError: String? = null,

    // Shiba Coins summary for the glance strip; null when this game isn't tracked yet.
    val coins: com.playfieldportal.core.domain.achievement.GameCoins? = null,
    // Set true to request opening the dedicated Shiba Coins screen (strip tap / SELECT on it).
    val openCoins: Boolean = false,

    // Stored media surfaced on the page (resolved once per load via ArtworkStore.find).
    val videoUri: String? = null,        // the game's video — playable from the Video button/strip
    val hasManual: Boolean = false,
    val showVideoPlayer: Boolean = false,   // built-in fullscreen video player overlay
    // Steam-style MEDIA PREVIEW strip: videos first, then images.
    val detailMedia: List<DetailMedia> = emptyList(),
    val imageViewerUri: String? = null,     // fullscreen image preview overlay

    // ── Navigation (unified engine — see GameDetailNav) ───────────────────
    // The stable key of the node the controller cursor is on, mirrored from the engine so the page
    // can render focus. Null until the page is ready, and when nothing is focusable.
    val navFocusKey: String? = null,
    // False while the last input was touch: the cursor is hidden, logical focus is preserved.
    val cursorVisible: Boolean = true,
    // The Overview row's expanded state — Confirm toggles it.
    val descriptionExpanded: Boolean = false,

    val showOptions: Boolean = false,
    // Mirrors the engine's Options focus for rendering. The engine owns the authoritative node;
    // this index only positions the menu's highlight.
    val optionsIndex: Int = 0,
    // The resolved emulator + RetroArch core and the ladder level that decided them for the loaded
    // game; null while nothing resolves (loading / no emulator / package-backed entry).
    val resolvedLaunch: ResolvedLaunch? = null,
    val confirmRemove: Boolean = false,
    val actionMessage: String? = null,
    val closed: Boolean = false,

    // ── Title editing ─────────────────────────────────────────────────────
    val isEditingTitle: Boolean = false,
    val titleText: String = "",

    // ── In-app manual viewer ──────────────────────────────────────────────
    val manualViewerUri: String? = null,     // non-null = viewer open
    val manualPage: Int = 0,
    val manualPageCount: Int = 0,
    val manualScrollSteps: Int = 0,

    // Fullscreen Artwork Studio (replaces the old in-detail artwork manager UI).
    val showArtworkStudio: Boolean = false,

    // Current-vs-Incoming metadata overlay (C16 task 3.2); null = closed.
    val metadataPreview: MetadataPreviewUi? = null,

    // Storefront match picker and Rematch (C23 T6, Phases 10 and 18); null = closed.
    val storefrontMatch: StorefrontMatchUi? = null,
    val storefrontRematch: StorefrontRematchUi? = null,

    // ── Emulator picker ───────────────────────────────────────────────────
    val showEmulatorPicker: Boolean = false,
    val emulatorPickerOptions: List<EmulatorProfile> = emptyList(),
    val emulatorPickerIndex: Int = 0,

    // ── Add-to-collection picker ──────────────────────────────────────────
    val collectionPicker: CollectionPickerUi = CollectionPickerUi(),
) {
    val selectedDisc: Game?
        get() = discMembers.firstOrNull { it.id == selectedDiscId } ?: game

    val showDiscPicker: Boolean
        get() = discMembers.size > 1

    // Package-backed gaming apps (Android / Windows card entries) launch through their package,
    // shortcut, or captured-intent handle — never an emulator.
    val isPackageBacked: Boolean
        get() = game != null && game.romPath == null && game.packageName != null

    /**
     * Emulator controls apply to this entry: the information band's Emulator field, and confirming
     * the band to change it. Package-backed entries have neither, because emulator configuration is
     * irrelevant for them.
     */
    val showEmulatorAction: Boolean
        get() = game != null && !isPackageBacked

    /**
     * The Game Information band has anything to show. Absent values are omitted rather than filled
     * with "Unknown", and an entry with nothing at all shows no band.
     */
    val showInfoBand: Boolean
        get() {
            val loaded = game ?: return false
            return listOf(
                loaded.displayReleaseYear,
                loaded.displayDeveloper?.takeIf { it.isNotBlank() },
                loaded.displayPublisher?.takeIf { it.isNotBlank() },
                loaded.displayGenre?.takeIf { it.isNotBlank() },
                loaded.lastPlayedAt,
                loaded.totalPlayTimeMillis.takeIf { it > 0 },
                resolvedLaunch?.profile?.name,
            ).any { it != null }
        }

    // The options rows actually shown: the emulator picker is meaningless for package-backed
    // entries, so its row is hidden there. Index-based navigation must use THIS list.
    // Export Game writes a PC game's .pfpgame file (C18 task X.7), so it is offered on Windows games only.
    val visibleActions: List<DetailAction>
        get() = DetailAction.entries.filter { action ->
            when (action) {
                DetailAction.EMULATOR -> !isPackageBacked
                DetailAction.EXPORT   -> game?.platformId == WINDOWS_PLATFORM_ID
                // A storefront identity is a PC-only fact: a console ROM is matched by its file,
                // and there is no store to rematch it against (C23 T6).
                DetailAction.STOREFRONT -> game?.platformId == WINDOWS_PLATFORM_ID
                else                  -> true
            }
        }
}

// ── Metadata preview ──────────────────────────────────────────────────────────

/** One editable line of the Manual column: what is shown today, and what the user has typed. */
data class ManualFieldRow(
    val field: MetadataField,
    /** The effective value — the user's own where they set one, the scraped value otherwise. */
    val current: Any?,
    val text: String,
    /** This field is hand-set today, so it can be reverted to reveal the scraped value. */
    val overridden: Boolean,
)

/**
 * C16 task 3.2 — the Current-vs-Incoming overlay. Retrieval fills it and nothing is written until
 * [GameDetailViewModel.applyMetadataPreview] runs; Back always closes without a write.
 *
 * The "will change" markers ([willWrite]) come from the same `MetadataApply.plan` the repository
 * writes with, so the preview can never promise a change the SQL does not make.
 *
 * C23 T3 added one more source to choose between: the user. The Manual column's cells are text
 * fields rather than a provider's answer, and what it applies lands in the override shadow layer
 * instead of the metadata columns — but it is the same preset, the same policies and the same plan
 * function, so there is no second editing surface to keep honest.
 */
data class MetadataPreviewUi(
    val loading: Boolean = true,
    val applying: Boolean = false,
    /** Retrieval threw, as opposed to every provider answering with nothing. */
    val failed: Boolean = false,
    /**
     * The game's own values were read, so the Manual column has something to seed from and to
     * compare against. False only when retrieval produced nothing at all — see [nothingFound].
     */
    val editable: Boolean = false,
    /** What the metadata COLUMNS hold — what a provider preset is compared against and writes. */
    val current: Map<MetadataField, Any?> = emptyMap(),
    /** What the user SEES — overrides over stored values. A manual edit is compared against this. */
    val effective: Map<MetadataField, Any?> = emptyMap(),
    /** Fields hand-set today. Drives the revert affordance, never the comparison. */
    val overridden: Set<MetadataField> = emptySet(),
    val presets: List<MetadataPreset> = emptyList(),
    /** `0..presets.lastIndex` is a provider; [presets].size is the Manual column. */
    val presetIndex: Int = 0,
    /** What the user has typed per field, seeded from [effective]. */
    val manualText: Map<MetadataField, String> = emptyMap(),
    /** The field whose text editor is open, if any. */
    val editingField: MetadataField? = null,
    val editText: String = "",
    val policy: MetadataApplyPolicy = MetadataApplyPolicy.FILL_MISSING_ONLY,
    val chosen: Set<MetadataField> = emptySet(),
    /** `0..shownFields.lastIndex` is a field row; [applyIndex] is the Apply button. */
    val focus: Int = 0,
) {
    /** The Manual column is selected: the rows are text fields and Apply writes overrides. */
    val isManual: Boolean get() = presetIndex >= presets.size

    /** Every source the chips offer: each provider that answered, plus Manual, which always is. */
    val sourceCount: Int get() = presets.size + 1

    /**
     * The preset for the selected source. For Manual it is built from [manualText] on the spot —
     * a hand-typed preset is exactly a provider preset with a different author, so it goes through
     * `MetadataApply.plan` like any other and the change markers stay honest for free.
     */
    val preset: MetadataPreset?
        get() = if (isManual) manualPreset else presets.getOrNull(presetIndex)

    private val manualPreset: MetadataPreset
        get() = MetadataPreset(
            provider = MatchProvider.MANUAL,
            title = manualText[MetadataField.TITLE],
            description = manualText[MetadataField.DESCRIPTION],
            developer = manualText[MetadataField.DEVELOPER],
            publisher = manualText[MetadataField.PUBLISHER],
            releaseYear = manualText[MetadataField.RELEASE_YEAR]?.trim()?.toIntOrNull(),
            releaseDate = manualText[MetadataField.RELEASE_DATE],
            genre = manualText[MetadataField.GENRE],
            ageRating = manualText[MetadataField.AGE_RATING],
            franchise = manualText[MetadataField.FRANCHISE],
            communityRating = parseRatingPercent(manualText[MetadataField.COMMUNITY_RATING]),
        )

    /** The values the selected source is measured against — see [current] and [effective]. */
    private val comparedAgainst: Map<MetadataField, Any?> get() = if (isManual) effective else current

    /**
     * Retrieval produced nothing usable at all — it threw, or the game is gone. The overlay stays
     * open on an explanation and the user dismisses it; closing on its own, with the reason in a
     * message behind it, looked like a crash. There is no Manual column here on purpose: seeding
     * one from values that could not be read would be inventing them.
     */
    val nothingFound: Boolean get() = !loading && !editable

    /**
     * Every provider answered with nothing, but the game itself read fine. Said inline, above a
     * Manual column that is still offered — which is the whole point: a game no scraper recognises
     * is exactly the one worth typing by hand.
     */
    val noProviderFound: Boolean get() = editable && presets.isEmpty()

    val rows: List<MetadataFieldRow>
        get() = if (isManual) emptyList()
        else preset?.let { MetadataApply.rows(current, it) }.orEmpty()

    /**
     * Every field, always — the Manual column has to offer an empty field to fill, which is the
     * case a provider column never has (it only lists what the provider actually supplied).
     */
    val manualRows: List<ManualFieldRow>
        get() = if (!isManual) emptyList() else MetadataField.entries.map { field ->
            ManualFieldRow(
                field = field,
                current = effective[field],
                text = manualText[field].orEmpty(),
                overridden = field in overridden,
            )
        }

    /** The fields with a row on screen, in order — what the navigation graph is built from. */
    val shownFields: List<MetadataField>
        get() = if (isManual) manualRows.map { it.field } else rows.map { it.field }

    val willWrite: Set<MetadataField>
        get() = preset?.let { MetadataApply.plan(comparedAgainst, it, policy, chosen).keys }.orEmpty()

    val applyIndex: Int get() = shownFields.size
}

/**
 * A community rating typed as a percentage (what the panel displays) back to the stored 0..1 scale.
 * Editing "90%" as `0.9` would ask the user to know an internal normalization.
 */
private fun parseRatingPercent(text: String?): Float? =
    text?.trim()?.removeSuffix("%")?.trim()?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)

/** The stored value as the Manual column's initial text — the inverse of [parseRatingPercent]. */
internal fun metadataEditText(field: MetadataField, value: Any?): String = when {
    value == null -> ""
    field == MetadataField.COMMUNITY_RATING && value is Float -> ((value * 100).roundToInt()).toString()
    else -> value.toString()
}

// ── Options menu ──────────────────────────────────────────────────────────────
enum class DetailAction(val label: String) {
    FAVORITE("Favorite"),
    COLLECTIONS("Collections"),
    ARTWORK("Artwork"),
    SAVES("Saves"),
    EMULATOR("Emulator"),
    MANUAL("Manual"),
    FETCH_ARTWORK("Fetch Artwork"),
    METADATA("Update Metadata"),
    EXPORT("Export Game"),
    STOREFRONT("Rematch Storefront"),
    RENAME("Edit Title"),
    EDIT("Edit Note"),
    LOCATION("Open Location"),
    REMOVE("Remove"),
}

private const val WINDOWS_PLATFORM_ID = "windows"
private const val ANDROID_PLATFORM_ID = "android"

// Key prefixes the node graph hands back through [GameDetailNav.onActivate]. Kept next to the keys
// themselves in GameDetailKeys; these are only the parts the ViewModel splits on.
private const val DISC_KEY_PREFIX = "game-detail:disc:"
private const val MEDIA_KEY_PREFIX = "game-detail:media:"

// Upper bound for the manual viewer's page scrolling — generous enough for the longest pages; the
// viewer clamps to the real content height, so overshoot is harmless.
const val MAX_PAGE_SCROLL_STEPS = 20

// ── ViewModel ─────────────────────────────────────────────────────────────────
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameRepository: GameRepository,
    private val platformDao: PlatformDao,
    private val memoryCardRepository: MemoryCardRepository,
    private val collectionRepository: CollectionRepository,
    private val profileRepository: EmulatorProfileRepository,
    private val autoCoreMemory: com.playfieldportal.feature.launcher.AutoCoreMemory,
    private val intentResolver: EmulatorIntentResolver,
    private val artworkRepository: ArtworkRepository,
    private val artworkStore: ArtworkStore,
    private val artworkRecordDao: com.playfieldportal.core.data.database.dao.ArtworkRecordDao,
    private val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
    private val launcherShortcutRepository: com.playfieldportal.feature.appbar.LauncherShortcutRepository,
    private val achievementRepository: com.playfieldportal.feature.achievements.AchievementController,
    private val launchDispatcher: com.playfieldportal.feature.launcher.LaunchDispatcher,
    private val pcGameExporter: com.playfieldportal.feature.settings.pc.PcGameExporter,
    private val storefrontMatches: StorefrontMatchRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    /**
     * The page's navigation state: stable semantic nodes, geometry-driven traversal, readiness
     * gating, and one modal context per blocking overlay. Owns navigation only — every business
     * action still lives on this ViewModel.
     */
    private val nav = GameDetailNav(logger = NavigationLogger { Timber.w(it) })

    init {
        // The engine reports activations by stable key; the ViewModel decides what each one means.
        nav.onActivate = ::activateNode

        // The page graph follows the state it renders, from one place: media arriving, a manual
        // appearing after an artwork refresh, discs resolving, the emulator changing — none of them
        // touch navigation directly, and the engine preserves (or recovers) focus by itself.
        viewModelScope.launch {
            uiState
                .map { navContentOf(it) }
                .distinctUntilChanged()
                .collect { content ->
                    nav.updateContent(content)
                    // The gate opens as soon as a loaded graph exists: until then the engine ignores
                    // navigation input outright rather than buffering it, so a press during load
                    // cannot fire late. The screen's onPageLaidOut() is the second, idempotent
                    // signal (it also covers the load-error surface, which has no graph at all).
                    if (content.loaded) nav.markReady()
                    publishNav()
                }
        }

        // Modal contexts are pushed/popped from the same state, so an overlay opened by an async
        // action (the manual viewer's file lookup) pauses the page exactly like a synchronous one.
        viewModelScope.launch {
            uiState
                .map { topModalId(it) }
                .distinctUntilChanged()
                .collect {
                    syncNavStack()
                    publishNav()
                }
        }

        // A modal's own rows can arrive asynchronously too (metadata retrieval, the collection list,
        // the installed-emulator catalog), so while one is up its nodes are rebuilt from state.
        // publishNav matters here: rebuilding the graph can be what finally gives the overlay a
        // cursor, and the state mirror has to carry that back to the screen.
        viewModelScope.launch {
            uiState.collect { s ->
                val active = unlessModal(nav) ?: return@collect
                nav.updateModalNodes(modalNodesFor(active), preferredModalFocus(active, s))
                publishNav()
            }
        }
    }

    private fun unlessModal(nav: GameDetailNav): String? =
        if (nav.isModalActive) nav.activeContextId else null

    // ── Navigation plumbing ──────────────────────────────────────────────

    /**
     * What the page's node graph is built from. Only the affordances that actually change the
     * focusable layout — everything else in the UI state is data, not navigation.
     */
    private fun navContentOf(s: GameDetailUiState): GameDetailNavContent {
        val game = s.game
        val loaded = game != null && !s.isLoading
        return GameDetailNavContent(
            gameId      = game?.id ?: 0L,
            loaded      = loaded,
            hasManual   = s.hasManual,
            // Package-backed entries launch through a package/shortcut/intent — never an emulator,
            // so they get no emulator nodes at all.
            showEmulatorControls = loaded && s.showEmulatorAction,
            discIds     = if (s.showDiscPicker) s.discMembers.map { it.id } else emptyList(),
            // Android games can never have achievements.
            showCoins   = loaded && game.platformId != ANDROID_PLATFORM_ID,
            showOverview = loaded,
            showInfo    = loaded && s.showInfoBand,
            mediaIds    = s.detailMedia.map { mediaStableId(it) },
        )
    }

    private fun mediaIndexFor(key: String): Int =
        _uiState.value.detailMedia.indexOfFirst { GameDetailKeys.media(mediaStableId(it)) == key }

    /** Business meaning of a page node's stable key. */
    private fun activateNode(key: String) {
        when {
            key == GameDetailKeys.LAUNCH -> { Timber.d("Controller SELECT activated Launch"); launch() }
            key == GameDetailKeys.FAVORITE -> toggleFavorite()
            key == GameDetailKeys.ARTWORK -> openArtworkManager()
            key == GameDetailKeys.MANUAL -> openManual()
            key == GameDetailKeys.OPTIONS_ACTION -> openOptions()
            key == GameDetailKeys.INFO -> if (_uiState.value.showEmulatorAction) requestChangeEmulator()
            key == GameDetailKeys.COINS -> requestOpenCoins()
            key == GameDetailKeys.OVERVIEW -> toggleDescriptionExpanded()
            key.startsWith(DISC_KEY_PREFIX) ->
                key.removePrefix(DISC_KEY_PREFIX).toLongOrNull()?.let(::selectDisc)
            key.startsWith(MEDIA_KEY_PREFIX) -> {
                val index = mediaIndexFor(key)
                if (index >= 0) openMediaAt(index)
            }
            else -> Unit
        }
    }

    /** Confirm on the Overview row: expand or collapse the description. */
    private fun toggleDescriptionExpanded() =
        _uiState.update { it.copy(descriptionExpanded = !it.descriptionExpanded) }

    /**
     * Publish the engine's result after an input: the modal stack (a confirm may have opened or
     * closed an overlay), the page cursor, and the indices the overlays still take as parameters.
     */
    private fun finishInput() {
        syncNavStack()
        publishNav()
    }

    private fun publishNav() {
        _uiState.update { s ->
            var next = s.copy(navFocusKey = nav.focusedKey, cursorVisible = nav.cursorVisible)
            val focus = next.navFocusKey
            if (next.showOptions) {
                val index = next.visibleActions.indexOfFirst { GameDetailKeys.option(it.name) == focus }
                if (index >= 0) next = next.copy(optionsIndex = index)
            }
            if (next.showEmulatorPicker) {
                val index = next.emulatorPickerOptions.indexOfFirst { GameDetailKeys.emulatorPick(it.id) == focus }
                if (index >= 0) next = next.copy(emulatorPickerIndex = index)
            }
            if (next.collectionPicker.visible) {
                val index = collectionIndexFor(next, focus)
                if (index >= 0) next = next.copy(collectionPicker = next.collectionPicker.copy(selectedIndex = index))
            }
            val preview = next.metadataPreview
            if (preview != null) {
                val index = metadataFocusFor(preview, focus)
                if (index >= 0) next = next.copy(metadataPreview = preview.copy(focus = index))
            }
            next.storefrontMatch?.let { match ->
                val index = storefrontMatchFocusFor(match, focus)
                if (index >= 0) next = next.copy(storefrontMatch = match.copy(focus = index))
            }
            next.storefrontRematch?.let { rematch ->
                val index = storefrontRematchFocusFor(rematch, focus)
                if (index >= 0) next = next.copy(storefrontRematch = rematch.copy(focus = index))
            }
            next
        }
    }

    /** The topmost blocking overlay, or null when the page itself owns input. */
    private fun topModalId(s: GameDetailUiState): String? = when {
        s.showArtworkStudio -> GameDetailKeys.MODAL_ARTWORK_STUDIO
        s.imageViewerUri != null -> GameDetailKeys.MODAL_IMAGE_VIEWER
        s.showVideoPlayer -> GameDetailKeys.MODAL_VIDEO_PLAYER
        s.manualViewerUri != null -> GameDetailKeys.MODAL_MANUAL_VIEWER
        s.confirmRemove -> GameDetailKeys.MODAL_CONFIRM_REMOVE
        s.isEditingNote -> GameDetailKeys.MODAL_NOTE_EDITOR
        s.isEditingTitle -> GameDetailKeys.MODAL_TITLE_EDITOR
        s.storefrontMatch != null -> GameDetailKeys.MODAL_STOREFRONT_MATCH
        s.storefrontRematch != null -> GameDetailKeys.MODAL_STOREFRONT_REMATCH
        s.metadataPreview != null -> GameDetailKeys.MODAL_METADATA
        s.showEmulatorPicker -> GameDetailKeys.MODAL_EMULATOR_PICKER
        s.collectionPicker.visible -> GameDetailKeys.MODAL_COLLECTION_PICKER
        s.showOptions -> GameDetailKeys.MODAL_OPTIONS
        else -> null
    }

    /**
     * Keep the engine's context stack in step with the overlays the state says are open. Overlays in
     * this screen never stack — each one closes the picker below it — so unwinding to the page graph
     * and pushing the new context is the whole story.
     */
    private fun syncNavStack() {
        val s = _uiState.value
        val target = topModalId(s)
        val active = if (nav.isModalActive) nav.activeContextId else null
        if (target == active) {
            // The overlay is the one already on top, but its rows may have arrived after it opened
            // (metadata retrieval, the collection list, the emulator catalog). Re-registering is
            // idempotent: it never steals focus from a user who already moved, and it keeps the
            // overlay's cursor on the node it was on as long as that node still exists.
            if (target != null) {
                nav.updateModalNodes(modalNodesFor(target), preferredModalFocus(target, s))
            }
            return
        }
        while (nav.isModalActive) nav.popModal()
        if (target != null) {
            nav.pushModal(target, modalNodesFor(target), preferredModalFocus(target, s))
        }
    }

    /** The rows of a modal context. Empty for overlays whose input is bespoke (viewers, editors). */
    private fun modalNodesFor(contextId: String): List<NavigationNode> {
        val s = _uiState.value
        return when (contextId) {
            GameDetailKeys.MODAL_OPTIONS -> s.visibleActions.map { action ->
                NavigationNode(GameDetailKeys.option(action.name), onSelect = { activateAction(action) })
            }
            GameDetailKeys.MODAL_EMULATOR_PICKER -> s.emulatorPickerOptions.map { profile ->
                NavigationNode(GameDetailKeys.emulatorPick(profile.id), onSelect = { confirmEmulatorPick(profile.id) })
            }
            GameDetailKeys.MODAL_COLLECTION_PICKER -> buildList {
                s.collectionPicker.options.forEach { option ->
                    add(
                        NavigationNode(
                            GameDetailKeys.collectionRow(option.id),
                            onSelect = { toggleCollection(option.id) },
                        ),
                    )
                }
                add(NavigationNode(GameDetailKeys.COLLECTION_CREATE_ROW, onSelect = { startCreateCollection() }))
            }
            GameDetailKeys.MODAL_STOREFRONT_MATCH -> buildList {
                s.storefrontMatch?.let { match ->
                    match.rows.indices.forEach { index ->
                        add(
                            NavigationNode(
                                GameDetailKeys.storefrontCandidate(index),
                                onSelect = { chooseStorefrontCandidate(index) },
                            ),
                        )
                    }
                    // Last and unconditional: the user is never made to pick one of the offers.
                    add(NavigationNode(GameDetailKeys.STOREFRONT_NO_MATCH, onSelect = { closeStorefrontMatch() }))
                }
            }
            GameDetailKeys.MODAL_STOREFRONT_REMATCH -> buildList {
                s.storefrontRematch?.let { rematch ->
                    rematch.rows.forEach { row ->
                        add(
                            NavigationNode(
                                GameDetailKeys.storefrontStore(row.store.key),
                                onSelect = { takeRematchAction(row.store) },
                            ),
                        )
                    }
                    add(NavigationNode(GameDetailKeys.STOREFRONT_SEARCH_ALL, onSelect = { searchAllStorefronts() }))
                }
            }
            GameDetailKeys.MODAL_METADATA -> buildList {
                s.metadataPreview?.let { preview ->
                    // One node per row on screen, whichever column is selected: a provider row
                    // ticks for Choose Fields, a Manual row opens its text editor.
                    preview.shownFields.forEach { field ->
                        add(
                            NavigationNode(
                                GameDetailKeys.metadataField(field.name),
                                onSelect = { onMetadataRowSelected(field) },
                            ),
                        )
                    }
                    if (preview.preset != null) {
                        add(NavigationNode(GameDetailKeys.METADATA_APPLY, onSelect = { applyMetadataPreview() }))
                    }
                }
            }
            GameDetailKeys.MODAL_CONFIRM_REMOVE -> listOf(
                NavigationNode(GameDetailKeys.CONFIRM_REMOVE, onSelect = { confirmRemoveGame() }),
                NavigationNode(GameDetailKeys.CONFIRM_CANCEL, onSelect = { _uiState.update { it.copy(confirmRemove = false) } }),
            )
            // Viewers, text editors and the full-screen Artwork Studio own their own input.
            else -> emptyList()
        }
    }

    /**
     * Where a freshly opened modal's cursor starts. Only consulted while its graph is still
     * focus-less, so an async row arriving later never steals the cursor from the user.
     */
    private fun preferredModalFocus(contextId: String, s: GameDetailUiState): String? = when (contextId) {
        GameDetailKeys.MODAL_OPTIONS -> s.visibleActions.firstOrNull()?.let { GameDetailKeys.option(it.name) }
        GameDetailKeys.MODAL_EMULATOR_PICKER ->
            s.emulatorPickerOptions.getOrNull(s.emulatorPickerIndex)?.let { GameDetailKeys.emulatorPick(it.id) }
        GameDetailKeys.MODAL_COLLECTION_PICKER -> collectionKeyAt(s, s.collectionPicker.selectedIndex)
        // The metadata overlay opens on Apply: the default policy is the non-destructive one.
        GameDetailKeys.MODAL_METADATA -> GameDetailKeys.METADATA_APPLY
        GameDetailKeys.MODAL_CONFIRM_REMOVE -> GameDetailKeys.CONFIRM_REMOVE
        // The picker opens on the strongest candidate, which is the one the user most likely
        // wants — but it is still a CHOICE, so nothing is written until they press Select.
        GameDetailKeys.MODAL_STOREFRONT_MATCH ->
            if (s.storefrontMatch?.rows.isNullOrEmpty()) GameDetailKeys.STOREFRONT_NO_MATCH
            else GameDetailKeys.storefrontCandidate(0)
        GameDetailKeys.MODAL_STOREFRONT_REMATCH ->
            s.storefrontRematch?.rows?.firstOrNull()?.let { GameDetailKeys.storefrontStore(it.store.key) }
                ?: GameDetailKeys.STOREFRONT_SEARCH_ALL
        else -> null
    }

    private fun collectionKeyAt(s: GameDetailUiState, index: Int): String? {
        val options = s.collectionPicker.options
        return if (index >= options.size) GameDetailKeys.COLLECTION_CREATE_ROW
        else options.getOrNull(index)?.let { GameDetailKeys.collectionRow(it.id) }
    }

    private fun collectionIndexFor(s: GameDetailUiState, focus: String?): Int {
        if (focus == null) return -1
        val options = s.collectionPicker.options
        if (focus == GameDetailKeys.COLLECTION_CREATE_ROW) return options.size
        val optionIndex = options.indexOfFirst { GameDetailKeys.collectionRow(it.id) == focus }
        return if (optionIndex >= 0) optionIndex else -1
    }

    private fun storefrontMatchFocusFor(ui: StorefrontMatchUi, focus: String?): Int {
        if (focus == null) return -1
        if (focus == GameDetailKeys.STOREFRONT_NO_MATCH) return ui.noMatchIndex
        return ui.rows.indices.firstOrNull { GameDetailKeys.storefrontCandidate(it) == focus } ?: -1
    }

    private fun storefrontRematchFocusFor(ui: StorefrontRematchUi, focus: String?): Int {
        if (focus == null) return -1
        if (focus == GameDetailKeys.STOREFRONT_SEARCH_ALL) return ui.searchAllIndex
        return ui.rows.indexOfFirst { GameDetailKeys.storefrontStore(it.store.key) == focus }
    }

    private fun metadataFocusFor(preview: MetadataPreviewUi, focus: String?): Int {
        if (focus == null) return -1
        if (focus == GameDetailKeys.METADATA_APPLY) return preview.applyIndex
        val rowIndex = preview.shownFields.indexOfFirst { GameDetailKeys.metadataField(it.name) == focus }
        return if (rowIndex >= 0) rowIndex else -1
    }

    /** Back on the base page: leave Game Detail. */
    private fun close() = _uiState.update { it.copy(closed = true) }

    /** Back inside a modal: close the topmost overlay before anything else. */
    private fun closeActiveModal() {
        val s = _uiState.value
        when {
            s.storefrontMatch != null -> closeStorefrontMatch()
            s.storefrontRematch != null -> closeStorefrontRematch()
            s.metadataPreview != null -> closeMetadataPreview()
            s.showEmulatorPicker -> closeEmulatorPicker()
            s.collectionPicker.visible -> closeCollectionPicker()
            s.showOptions -> closeOptions()
            else -> close()
        }
    }

    // ── Touch (same actions, one path) ────────────────────────────────────

    /** A tap on any page node: logical focus moves there, then the node activates. */
    fun onNodeTapped(key: String) {
        nav.touch(key)
        finishInput()
    }

    /** Any touch anywhere: hide the controller cursor, keep logical focus (design §10). */
    fun onTouchInput() {
        nav.markTouchInput()
        publishNav()
    }

    /**
     * The page's first usable graph is on screen. Until this fires the engine ignores navigation
     * input outright — it is never buffered — so a press during load cannot fire late.
     */
    fun onPageLaidOut() {
        nav.markReady()
        publishNav()
    }

    /** Test seam: every node key the cursor can reach, inline children included. */
    internal fun focusableNodeKeys(): Set<String> = nav.reachableKeys()

    /** Root-space Y of every page node the screen composed, for geometry-driven movement. */
    fun onNodeGeometry(geometry: Map<String, Float>) {
        nav.reportGeometry(geometry)
        publishNav()
    }

    /**
     * The page is animating the cursor into view. Repeated directional input during the alignment is
     * dropped rather than queued, so a held direction cannot outrun the scroll.
     */
    fun onScrollAlignmentChanged(aligning: Boolean) {
        if (aligning) nav.beginRecoveryLock() else nav.endRecoveryLock()
    }

    // ── Shiba Coins strip ─────────────────────────────────────────────────

    fun prepareForOpen() {
        _uiState.update {
            it.copy(
                closed = false,
                showOptions = false,
                confirmRemove = false,
                isEditingNote = false,
                isEditingTitle = false,
                actionMessage = null,
                launchError = null,
                // An overlay left over from a previous open must not survive into this one, and the
                // engine's modal stack has to unwind with it — otherwise the page would come back
                // with a modal context still on top of its graph.
                showEmulatorPicker = false,
                metadataPreview = null,
                collectionPicker = CollectionPickerUi(),
                manualViewerUri = null,
                imageViewerUri = null,
                showVideoPlayer = false,
                showArtworkStudio = false,
            )
        }
        syncNavStack()
        publishNav()
    }

    /**
     * @param requestedDiscId when set (XMB context menu "Choose Disc"), the set member to select
     *   instead of the primary — the disc an auto-launch then boots. Falls back to the primary
     *   when the id isn't a member (stale row, single-disc game).
     */
    fun loadGame(id: Long, requestedDiscId: Long? = null) {
        // Offline-first coin summary for the glance strip — streams straight from Room.
        viewModelScope.launch {
            achievementRepository.observeGameCoins(id).collect { coins ->
                _uiState.update { it.copy(coins = coins) }
            }
        }
        viewModelScope.launch {
            // Loading is a fresh page: every overlay closes with it, and the engine's modal stack
            // unwinds from the state (below) rather than being left on top of a new graph.
            _uiState.update {
                it.copy(
                    isLoading = true,
                    closed = false,
                    showOptions = false,
                    confirmRemove = false,
                    isEditingNote = false,
                    isEditingTitle = false,
                    showEmulatorPicker = false,
                    metadataPreview = null,
                    collectionPicker = CollectionPickerUi(),
                    manualViewerUri = null,
                    imageViewerUri = null,
                    showVideoPlayer = false,
                )
            }
            syncNavStack()
            publishNav()
            val game     = gameRepository.getById(id)
            // Always keep the detail picker in numeric disc order. The primary flag only
            // determines the highlighted/default selection; it must never move that disc ahead
            // of the numbered rows.
            val discMembers = game?.discSetKey
                ?.let { gameRepository.getDiscSetMembers(it) }
                ?.takeIf { it.isNotEmpty() }
                ?.sortedWith(
                    compareBy<Game> { it.discNumber == null }
                        .thenBy { it.discNumber ?: Int.MAX_VALUE }
                        .thenBy { it.id },
                )
                ?: listOfNotNull(game)
            val selectedDisc = discMembers.firstOrNull { it.id == requestedDiscId }
                ?: discMembers.firstOrNull { it.isDiscPrimary }
                ?: discMembers.firstOrNull()
            val platform = game?.let { platformDao.getById(it.platformId) }
            val resolvedLaunch = game?.let { resolveLaunchProfile(it, platform).getOrNull() }
            // VIDEO and SCREENSHOT are multi-asset kinds (ArtworkFileNaming.MULTI_ASSET_KINDS), so
            // the strip reads the whole ordered set rather than position 0 alone. ICON1 (the icon
            // snap) is a single-art fallback for a game that has no full video at all.
            val videoUris = game
                ?.let { g ->
                    artworkStore.findAll(g.id, ArtworkKind.VIDEO)
                        .ifEmpty { listOfNotNull(artworkStore.find(g.id, ArtworkKind.ICON1)) }
                }
                ?: emptyList()
            val screenshotUris = game?.let { artworkStore.findAll(it.id, ArtworkKind.SCREENSHOT) } ?: emptyList()
            _uiState.update {
                it.copy(
                    game              = game,
                    platform          = platform,
                    discMembers       = discMembers,
                    selectedDiscId    = selectedDisc?.id,
                    noteText          = game?.userNote ?: "",
                    resolvedLaunch    = resolvedLaunch,
                    // Media strip plays the full VIDEO; ICON1 (icon snap) is a fallback so a game
                    // that only has a snap still shows a video card.
                    videoUri          = videoUris.firstOrNull(),
                    detailMedia       = if (game == null) emptyList() else buildList {
                        videoUris.forEach { add(DetailMedia(it, isVideo = true)) }
                        screenshotUris.forEach { add(DetailMedia(it, isVideo = false)) }
                        artworkStore.find(game.id, ArtworkKind.TITLESCREEN)?.let { add(DetailMedia(it, isVideo = false)) }
                    },
                    // The same two sources the viewer opens from, so an action that is enabled always
                    // opens and a manual that exists is never disabled.
                    hasManual         = game?.let { g -> manualPath(g.id) } != null,
                    showVideoPlayer   = false,
                    imageViewerUri    = null,
                    isLoading         = false,
                    // A fresh page shows the description collapsed again; focus itself is preserved
                    // by the engine when this is a reload of the same game (Artwork Studio return).
                    descriptionExpanded = false,
                    manualViewerUri   = null,
                    showOptions       = false,
                    optionsIndex      = 0,
                    confirmRemove     = false,
                    isEditingNote     = false,
                    isEditingTitle    = false,
                    actionMessage     = null,
                    launchError       = null,
                    closed            = false,
                )
            }
            finishInput()
        }
    }

    // ── Disc picker ───────────────────────────────────────────────────────

    fun selectDisc(id: Long) {
        val state = _uiState.value
        if (state.discMembers.any { it.id == id }) {
            _uiState.update { it.copy(selectedDiscId = id, actionMessage = null, launchError = null) }
            // Selecting a disc in detail is the same preference-changing action as selecting one
            // from the XMB context menu. Persist it immediately so the highlight and subsequent
            // launches remain consistent after leaving and reopening this screen.
            val gameId = state.game?.id
            if (gameId != null && state.discMembers.size > 1) {
                viewModelScope.launch {
                    gameRepository.setPreferredDisc(gameId, id)
                }
            }
        }
    }

    // ── Controller input ──────────────────────────────────────────────────

    fun handleGamepadAction(action: GamepadAction) {
        // Catch the engine up before dispatching: an overlay may have been opened since the last
        // input, and an asynchronously loaded row list (metadata rows, the collection list, the
        // installed-emulator catalog) may have arrived. Neither may leave the press navigating the
        // paused page behind the overlay, so the stack is reconciled first.
        syncNavStack()
        val s = _uiState.value

        // ── Overlays that own their own semantics ─────────────────────────────
        // Each of these still pushes a modal navigation context (see syncNavStack), so the page
        // graph behind it is paused and gets its exact cursor back when the overlay closes. What
        // they do NOT do is navigate by node — a text field, a flipbook and a video player have no
        // row geometry to move through.
        if (s.imageViewerUri != null) {
            if (action == GamepadAction.BACK || action == GamepadAction.SELECT) closeImageViewer()
            finishInput()
            return
        }
        if (s.showArtworkStudio) return   // actions are forwarded to the Studio's own VM
        if (s.showVideoPlayer) {
            // Fullscreen snap player: Back (or Confirm) closes; everything else is consumed.
            if (action == GamepadAction.BACK || action == GamepadAction.SELECT) closeVideoPlayer()
            finishInput()
            return
        }
        if (s.manualViewerUri != null) {
            handleManualViewerInput(action)
            finishInput()
            return
        }
        if (s.confirmRemove) {
            when (action) {
                GamepadAction.SELECT -> confirmRemoveGame()
                GamepadAction.BACK   -> _uiState.update { it.copy(confirmRemove = false) }
                else -> Unit
            }
            finishInput()
            return
        }
        if (s.collectionPicker.showCreateDialog) {
            // The new-collection prompt is a text field: the keyboard owns every key except Back,
            // and Confirm belongs to the dialog's own buttons.
            if (action == GamepadAction.BACK) cancelCreateCollection()
            finishInput()
            return
        }
        if (s.isEditingNote) {
            if (action == GamepadAction.BACK) cancelNote()
            finishInput()
            return
        }
        if (s.isEditingTitle) {
            if (action == GamepadAction.BACK) cancelTitleEdit()
            finishInput()
            return
        }
        if (s.storefrontMatch != null) {
            handleStorefrontMatchInput(action)
            finishInput()
            return
        }
        if (s.storefrontRematch != null) {
            handleStorefrontRematchInput(action)
            finishInput()
            return
        }
        if (s.metadataPreview != null) {
            handleMetadataPreviewInput(action)
            finishInput()
            return
        }

        // ── Engine-owned navigation ───────────────────────────────────────────
        // Everything left — the page itself and the pickers whose rows ARE a graph — goes through
        // the shared engine, so exactly one thing owns the cursor at a time.
        when (action) {
            // Y / Triangle opens Options from anywhere on the base page. Inside a modal it is
            // inert: a context menu of a context menu is not a thing.
            GamepadAction.OPEN_CONTEXT_MENU -> if (!nav.isModalActive) openOptions()
            GamepadAction.BACK -> if (nav.isModalActive) closeActiveModal() else close()
            // HOME belongs to the shell (the XMB bar), never to this page.
            GamepadAction.HOME -> Unit
            else -> nav.handleAction(action)
        }
        finishInput()
    }

    // ── Shiba Coins strip ─────────────────────────────────────────────────

    fun requestOpenCoins() = _uiState.update { it.copy(openCoins = true) }
    fun onOpenCoinsConsumed() = _uiState.update { it.copy(openCoins = false) }

    // ── Artwork Studio open / close ───────────────────────────────────────

    fun openArtworkManager() {
        // The legacy in-detail manager is retired — the fullscreen Artwork Studio replaces it.
        _uiState.update { it.copy(showArtworkStudio = true) }
    }

    /** Called when the Studio closes: reload so applied artwork shows immediately. */
    fun onArtworkStudioClosed() {
        _uiState.update { it.copy(showArtworkStudio = false) }
        val id = _uiState.value.game?.id ?: return
        loadGame(id)
    }

    // ── Options menu ──────────────────────────────────────────────────────

    fun openOptions()  = _uiState.update { it.copy(showOptions = true, optionsIndex = 0, actionMessage = null) }
    fun closeOptions() = _uiState.update { it.copy(showOptions = false) }

    fun onOptionClicked(action: DetailAction) {
        _uiState.update { it.copy(optionsIndex = it.visibleActions.indexOf(action).coerceAtLeast(0)) }
        activateAction(action)
    }

    /** Tap on an Options row: focus first, then activate — one path for touch and controller. */
    fun onOptionRowTapped(action: DetailAction) {
        if (!nav.touch(GameDetailKeys.option(action.name))) activateAction(action)
        finishInput()
    }

    fun onPlayClicked()    { Timber.d("Play clicked"); launch() }
    fun onOptionsClicked() = openOptions()

    fun activateAction(action: DetailAction) {
        _uiState.update { it.copy(showOptions = false) }
        when (action) {
            DetailAction.FAVORITE  -> toggleFavorite()
            DetailAction.COLLECTIONS -> openCollectionPicker()
            DetailAction.ARTWORK   -> openArtworkManager()
            DetailAction.SAVES     -> showActionMessage("Save management isn't available yet")
            DetailAction.EMULATOR  -> openEmulatorPicker()
            DetailAction.MANUAL    -> openManual()
            DetailAction.FETCH_ARTWORK -> fetchArtwork()
            DetailAction.METADATA  -> openMetadataPreview()
            DetailAction.STOREFRONT -> openStorefrontRematch()
            DetailAction.EXPORT    -> exportGame()
            DetailAction.RENAME    -> startEditTitle()
            DetailAction.EDIT      -> startEditNote()
            DetailAction.LOCATION  -> showActionMessage(
                _uiState.value.game?.romPath
                    ?: _uiState.value.game?.packageName?.let { "Package: $it" }
                    ?: "No file location on record"
            )
            DetailAction.REMOVE    -> _uiState.update { it.copy(confirmRemove = true) }
        }
    }

    private fun showActionMessage(msg: String) = _uiState.update { it.copy(actionMessage = msg) }

    /**
     * Export Game (C18 task X.7): writes this PC game's `.pfpgame` file into `windows/import`, so a
     * fresh install's Scan Import Folder can bring it back with its artwork.
     */
    private fun exportGame() {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            val report = runCatching { pcGameExporter.exportGame(gameId) }
                .onFailure { Timber.e(it, "Export Game failed for gameId=$gameId") }
                .getOrNull()
            showActionMessage(report?.message ?: "Export failed — see the log.")
        }
    }

    // Opens the scraped PDF manual (ScreenScraper, stored as artwork/{gameId}/manual.pdf) in the
    // user's PDF viewer. Goes through the existing launch-intent channel; deliberately NOT
    // sendLaunchIntent — reading a manual is not "playing", so Discord presence stays untouched.
    fun onManualClicked() = openManual()

    /**
     * Plays the game's video snap. Honors Settings ▸ Video's default player: a pinned external
     * package gets an ACTION_VIEW intent (falling back to built-in on failure); otherwise the
     * built-in fullscreen overlay plays it in place.
     */
    fun onVideoClicked() {
        val uri = _uiState.value.videoUri ?: run {
            _uiState.update { it.copy(actionMessage = "No video snap — enable Download Video Snaps and re-scrape") }
            return
        }
        viewModelScope.launch {
            val playerPackage = runCatching {
                context.pfpDataStore.data.first()[KEY_VIDEO_DEFAULT_PLAYER]
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (playerPackage != null) {
                val sent = runCatching {
                    val content = if (uri.startsWith("content://")) android.net.Uri.parse(uri)
                    else androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", java.io.File(uri),
                    )
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(content, "video/mp4")
                            setPackage(playerPackage)
                            addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                    )
                    true
                }.getOrDefault(false)
                if (sent) return@launch
                Timber.w("External video player '$playerPackage' failed — using built-in")
            }
            _uiState.update { it.copy(showVideoPlayer = true) }
        }
    }

    fun closeVideoPlayer() = _uiState.update { it.copy(showVideoPlayer = false) }

    /** Confirm/tap on a media-strip tile: videos route like the Video button, images open the
     *  fullscreen viewer — Steam-store-style previews. */
    fun openMediaAt(index: Int) {
        val media = _uiState.value.detailMedia.getOrNull(index) ?: return
        if (media.isVideo) onVideoClicked()
        else _uiState.update { it.copy(imageViewerUri = media.uri) }
    }

    fun closeImageViewer() = _uiState.update { it.copy(imageViewerUri = null) }

    private fun openManual() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            val path = manualPath(game.id)
            if (path == null) {
                showActionMessage("No manual available for this game")
                return@launch
            }
            // Displayed in-app via PdfRenderer (ManualViewerOverlay) — no external PDF app needed.
            _uiState.update {
                it.copy(
                    showOptions = false,
                    manualViewerUri = path,
                    manualPage = 0,
                    manualPageCount = 0,
                    manualScrollSteps = 0,
                )
            }
        }
    }

    /**
     * Where [gameId]'s manual lives: the internal store first (scraped manuals), then the portable
     * media library ({platform}/manuals/{name}.pdf, tracked by the game's artwork record).
     */
    private suspend fun manualPath(gameId: Long): String? =
        artworkStore.find(gameId, ArtworkKind.MANUAL)
            ?: artworkRecordDao.get(gameId, ArtworkKind.MANUAL.name)?.documentUri

    fun closeManualViewer() = _uiState.update { it.copy(manualViewerUri = null) }

    fun setManualPageCount(count: Int) = _uiState.update {
        it.copy(manualPageCount = count, manualPage = it.manualPage.coerceIn(0, (count - 1).coerceAtLeast(0)))
    }

    fun manualPrevPage() = _uiState.update {
        it.copy(manualPage = (it.manualPage - 1).coerceAtLeast(0), manualScrollSteps = 0)
    }

    fun manualNextPage() = _uiState.update {
        it.copy(
            manualPage = (it.manualPage + 1).coerceAtMost((it.manualPageCount - 1).coerceAtLeast(0)),
            manualScrollSteps = 0,
        )
    }

    private fun handleManualViewerInput(action: GamepadAction) {
        when (action) {
            GamepadAction.NAVIGATE_LEFT  -> manualPrevPage()
            GamepadAction.NAVIGATE_RIGHT -> manualNextPage()
            GamepadAction.NAVIGATE_DOWN  -> _uiState.update {
                it.copy(manualScrollSteps = (it.manualScrollSteps + 1).coerceAtMost(MAX_PAGE_SCROLL_STEPS))
            }
            GamepadAction.NAVIGATE_UP    -> _uiState.update {
                it.copy(manualScrollSteps = (it.manualScrollSteps - 1).coerceAtLeast(0))
            }
            GamepadAction.BACK           -> closeManualViewer()
            else -> Unit
        }
    }

    fun dismissActionMessage() = _uiState.update { it.copy(actionMessage = null) }

    // ── Remove ────────────────────────────────────────────────────────────

    fun requestRemove() = _uiState.update { it.copy(confirmRemove = true) }
    fun cancelRemove()  = _uiState.update { it.copy(confirmRemove = false) }
    fun confirmRemoveGame() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            gameRepository.delete(game.id)
            _uiState.update { it.copy(confirmRemove = false, closed = true) }
        }
    }

    // ── Launch ────────────────────────────────────────────────────────────

    // playSound is false for direct-launch auto-fire: the XMB icon confirm already handled the
    // launch sound, so replaying it here would double it. Manual Play (button / controller
    // SELECT) leaves it true. Either way, a game boot never scores the App Launch sfx — GameBoot
    // owns sfx_launch when it is on, and with GameBoot off the launch is silent by decision.
    fun launch(playSound: Boolean = true) {
        val selectedGame = _uiState.value.selectedDisc ?: run {
            Timber.w("Play requested before game detail state was loaded")
            _uiState.update { it.copy(actionMessage = null, launchError = "Game is still loading") }
            return
        }
        // A missing game's file was gone on the last trustworthy scan, so every launch handle below
        // would hand the emulator a dead path and surface as an opaque emulator-side error. Refuse
        // here instead, with the reason. Deliberately before the launch sfx — a refused launch that
        // still plays the launch sound reads as a crash.
        //
        // This is the single chokepoint for Play, controller SELECT, and direct-launch auto-fire,
        // so guarding it once covers all three. The entry is untouched: dropping the file back
        // clears is_missing on the next scan and Play works again.
        if (selectedGame.isMissing) {
            Timber.i("Launch refused for missing game: ${selectedGame.title}")
            _uiState.update {
                it.copy(
                    actionMessage = null,
                    launchError = "File not found on the last scan. Reconnect the card or restore " +
                        "the file, then rescan.",
                )
            }
            return
        }
        // No launch sound on a game boot: GameBoot brings its own when it is on, and with
        // GameBoot off the launch stays silent — never the App Launch sfx, which is the same
        // sfx_launch sample the built-in sequence is timed to.
        if (playSound) {
            menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.SELECT)
        }
        _uiState.update {
            it.copy(
                launchError = null,
                actionMessage = "Launching ${selectedGame.title}...",
            )
        }
        viewModelScope.launch {
            val game = gameRepository.getById(selectedGame.id) ?: selectedGame
            val platform = platformDao.getById(game.platformId) ?: _uiState.value.platform
            Timber.d(
                "Launch requested: gameId=${game.id}, title=${game.title}, platform=${game.platformId}, rom=${game.romPath ?: game.packageName.orEmpty()}"
            )

            // Harvested launcher shortcut (Windows Games card) — startShortcut is an API call,
            // not an intent, so it can't ride the normal launch channel.
            if (game.shortcutId != null && game.packageName != null) {
                launcherShortcutRepository.launch(game.packageName!!, game.shortcutId!!)
                    .onSuccess {
                        _uiState.update { it.copy(actionMessage = null) }
                        // Shortcut launches bypass the dispatcher; record the hand-off for the return check.
                        launchDispatcher.noteShortcutHandoff(game)
                        discordPresence.setCurrentGame(game.title)
                    }
                    .onFailure { e ->
                        Timber.e(e, "Shortcut launch failed: ${game.packageName}/${game.shortcutId}")
                        // B1: record + offer the recovery sheet (no intent was involved).
                        launchDispatcher.recordPreflightFailure(
                            game   = game,
                            resolved = null,
                            reason = "Couldn't launch: ${e.message}",
                            offerRecovery = true,
                        )
                        _uiState.update {
                            it.copy(actionMessage = null, launchError = "Couldn't launch: ${e.message}")
                        }
                    }
                return@launch
            }

            // Captured launch intent (add-by-ID / folder-scan PC games, legacy INSTALL_SHORTCUT).
            // Re-hardened at launch so a stored intent can never grant file access or redirect.
            if (game.launchIntentUri != null) {
                runCatching {
                    val parsed = Intent.parseUri(game.launchIntentUri, Intent.URI_INTENT_SCHEME)
                    com.playfieldportal.core.common.security.ShortcutIntentSanitizer
                        .sanitize(parsed, context.packageManager)
                        ?: error("Captured shortcut is not safe to launch")
                }.onSuccess { intent ->
                    dispatchLaunch(intent, game, null)
                }.onFailure { e ->
                    Timber.e(e, "Stored-intent launch failed for gameId=${game.id}")
                    launchDispatcher.recordPreflightFailure(game, null, "Couldn't launch: ${e.message}", offerRecovery = false)
                    _uiState.update {
                        it.copy(actionMessage = null, launchError = "Couldn't launch: ${e.message}")
                    }
                }
                return@launch
            }

            if (game.romPath.isNullOrBlank() && !game.packageName.isNullOrBlank()) {
                val nativeResult = intentResolver.resolveNativeApp(game)
                nativeResult.onFailure { e ->
                    Timber.w(e, "Native game launch failed: gameId=${game.id}, package=${game.packageName}")
                    _uiState.update {
                        it.copy(
                            actionMessage = null,
                            launchError = e.message ?: "Could not launch ${game.title}",
                        )
                    }
                    return@launch
                }
                val nativeIntent = nativeResult.getOrNull() ?: return@launch
                Timber.i(
                    "Launching native gameId=${game.id}, title=${game.title}, package=${game.packageName}, intent=${nativeIntent.toUri(Intent.URI_INTENT_SCHEME)}"
                )
                dispatchLaunch(nativeIntent, game, null)
                return@launch
            }

            val resolved = resolveLaunchProfile(game, platform)
            if (resolved.isFailure) {
                val reason = resolved.exceptionOrNull()?.message ?: "Could not resolve emulator for ${game.title}"
                Timber.w(
                    "Launch blocked: gameId=${game.id}, title=${game.title}, platform=${game.platformId}, reason=$reason"
                )
                launchDispatcher.recordPreflightFailure(game, null, reason, offerRecovery = false)
                _uiState.update { it.copy(actionMessage = null, launchError = reason) }
                return@launch
            }
            val resolvedLaunch = resolved.getOrThrow()
            val profile = resolvedLaunch.profile
            Timber.d(
                "Launch emulator resolved: gameId=${game.id}, platform=${game.platformId}, emulatorId=${profile.id}, emulatorName=${profile.name}, source=${resolvedLaunch.source.name}"
            )

            val result = intentResolver.resolve(game, profile)
            result.onFailure { e ->
                Timber.w(
                    e,
                    "Launch failed before startActivity: gameId=${game.id}, platform=${game.platformId}, emulatorId=${profile.id}, source=${resolvedLaunch.source.name}"
                )
                launchDispatcher.recordPreflightFailure(
                    game, resolvedLaunch, e.message ?: "Could not launch ${profile.name}", offerRecovery = false,
                )
                _uiState.update {
                    it.copy(
                        actionMessage = null,
                        launchError = e.message ?: "Could not launch ${profile.name}",
                    )
                }
                return@launch
            }
            val intent = result.getOrNull() ?: return@launch
            Timber.i(
                "Launching gameId=${game.id}, title=${game.title}, platform=${game.platformId}, emulatorId=${profile.id}, emulator=${profile.name}, source=${resolvedLaunch.source.name}, core=${resolvedLaunch.corePath.orEmpty()}, rom=${game.romPath.orEmpty()}, intent=${intent.toUri(Intent.URI_INTENT_SCHEME)}"
            )
            dispatchLaunch(intent, game, resolvedLaunch)
        }
    }

    // ── Launch funnel (B1) ──────────────────────────────────────────────────
    //
    // Game Detail no longer touches startActivity itself: the resolved intent goes to the shared
    // LaunchDispatcher, which performs startActivity with named failures, records the launch
    // outcome (launch_outcomes), and verifies the emulator actually came to the foreground
    // (home-launcher lifecycle handshake). Game Detail renders the rejection inline (launchError)
    // instead of the dispatcher's recovery sheet to avoid double-surfacing the same failure.
    private suspend fun dispatchLaunch(
        intent: Intent,
        game: Game,
        resolved: ResolvedLaunch?,
    ) {
        when (val result = launchDispatcher.launch(game, resolved, intent)) {
            is LaunchDispatchResult.Rejected -> {
                _uiState.update {
                    it.copy(actionMessage = null, launchError = result.message)
                }
            }
            LaunchDispatchResult.Accepted -> {
                // startActivity succeeded — drop the transient "Launching…" line; the emulator
                // covers the launcher next. About to background PFP for the game — reflect it in
                // the opt-in Discord presence (no-op unless the user connected Discord and
                // enabled sharing).
                _uiState.update { it.copy(actionMessage = null) }
                discordPresence.setCurrentGame(game.title)
            }
        }
    }

    fun onLaunchFailed(message: String) {
        _uiState.update { it.copy(actionMessage = null, launchError = message) }
    }

    /** "Get help" on the Game Detail launch-error line: opens the shell's recovery sheet. */
    fun requestLaunchHelp() {
        val game = _uiState.value.game ?: return
        val error = _uiState.value.launchError ?: return
        viewModelScope.launch {
            launchDispatcher.requestRecovery(game, _uiState.value.resolvedLaunch, error)
        }
    }

    /**
     * Resolves which emulator (and RetroArch core) will launch [game]. This function only gathers
     * the ladder's inputs from their stores; the precedence itself lives in
     * [EmulatorLaunchResolver] (feature-launcher) so it is shared, tested logic.
     */
    private suspend fun resolveLaunchProfile(
        game: Game,
        platform: PlatformEntity? = null,
    ): Result<ResolvedLaunch> {
        val platformId = game.platformId
        val installed = profileRepository.getInstalledProfiles()
        // Ordered so the automatic fallback picks a standalone emulator over a RetroArch core when
        // both support the console. Unavailable profiles (e.g. a RetroArch core the SAF link
        // detected as not installed) are excluded so the fallback never lands on one. The console's
        // remembered RetroArch core is then lifted to the front of the core tier, so the core (and
        // its RetroArch configs) stays stable even as the detected core set changes.
        val platformProfiles =
            installed.filter { it.isAvailable && it.supportsPlatform(platformId) }
                .byLaunchPreference()
                .stabilizeCore(autoCoreMemory.rememberedProfileId(platformId))
        return EmulatorLaunchResolver.resolve(
            platformId           = platformId,
            installedProfiles    = installed,
            platformProfiles     = platformProfiles,
            perGameOverride      = game.emulatorPackage?.takeIf { it.isNotBlank() },
            memoryCardEmulatorId = memoryCardRepository.getById(platformId)?.emulatorId?.takeIf { it.isNotBlank() },
            platformDefault      = (platform?.preferredEmulatorPackage
                ?: platformDao.getById(platformId)?.preferredEmulatorPackage)?.takeIf { it.isNotBlank() },
        )
    }

    // ── Emulator picker ───────────────────────────────────────────────────

    private fun openEmulatorPicker() {
        val game = _uiState.value.game ?: return
        val options = profileRepository.getInstalledProfiles()
            .filter { it.isAvailable && it.supportsPlatform(game.platformId) }
            .byLaunchPreference()
        if (options.isEmpty()) {
            showActionMessage("No emulators installed for ${game.platformId.uppercase()}")
            return
        }
        val stored = game.emulatorPackage
        val currentIndex = if (stored != null) {
            options.indexOfFirst { it.id == stored || it.packageName == stored }.coerceAtLeast(0)
        } else 0
        _uiState.update {
            it.copy(
                showOptions           = false,
                showEmulatorPicker    = true,
                emulatorPickerOptions = options,
                emulatorPickerIndex   = currentIndex,
            )
        }
    }

    fun closeEmulatorPicker() {
        _uiState.update { it.copy(showEmulatorPicker = false) }
    }

    /** Tap on the Game Detail emulator line — same per-game-only override flow as Options ▸ Emulator. */
    fun requestChangeEmulator() = openEmulatorPicker()

    /**
     * Tap on a picker row: the same path as Confirm. Logical focus moves to the picked profile
     * first, so touch and the controller cannot disagree about where the cursor is.
     */
    fun onEmulatorPickTapped(profileId: String) {
        if (!nav.touch(GameDetailKeys.emulatorPick(profileId))) confirmEmulatorPick(profileId)
        finishInput()
    }

    fun confirmEmulatorPick(profileId: String) {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            gameRepository.setPreferredEmulator(game.id, profileId)
            val updated = gameRepository.getById(game.id)
            val profile = profileRepository.getInstalledProfiles().firstOrNull { it.id == profileId }
            // Re-resolve from the fresh override so the emulator line shows the new winner + core
            // and reports PER_GAME_OVERRIDE rather than a stale lower-level attribution.
            val resolved = updated?.let {
                resolveLaunchProfile(it, _uiState.value.platform).getOrNull()
            }
            _uiState.update {
                it.copy(
                    game               = updated ?: it.game,
                    resolvedLaunch     = resolved,
                    showEmulatorPicker = false,
                    actionMessage      = profile?.let { p -> "Emulator set to ${p.name}" },
                )
            }
        }
    }

    // supportsPlatform / platformAliases / corePathFor live in feature-launcher's
    // EmulatorPlatformMapping.kt — shared with EmulatorProfileRepository, EmulatorIntentResolver
    // and EmulatorLaunchResolver so the launch ladder and the UI can never disagree.

    // ── Add-to-collection picker ──────────────────────────────────────────

    private fun openCollectionPicker() {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    showOptions = false,
                    collectionPicker = CollectionPickerUi(
                        visible = true,
                        options = buildCollectionOptions(gameId),
                        selectedIndex = 0,
                    ),
                )
            }
        }
    }

    private suspend fun buildCollectionOptions(gameId: Long): List<CollectionPickerOption> {
        val memberOf = collectionRepository.getCollectionIdsForGame(gameId).toSet()
        return collectionRepository.getAll().map {
            CollectionPickerOption(id = it.id, name = it.name, checked = it.id in memberOf)
        }
    }

    /**
     * Tap on a picker row. Touch takes the same path as Confirm: logical focus moves to the tapped
     * row first, then the engine activates it, so a tap and a Cross press can never drift apart.
     */
    fun onCollectionRowClick(index: Int) {
        val key = collectionKeyAt(_uiState.value, index)
        // A tap can land before the node graph is ready (options load asynchronously); the direct
        // fallback keeps the panel usable in that window.
        if (key == null || !nav.touch(key)) activateCollectionRowAt(index)
        finishInput()
    }

    private fun activateCollectionRowAt(index: Int) {
        val options = _uiState.value.collectionPicker.options
        if (index >= options.size) {
            startCreateCollection()
            return
        }
        options.getOrNull(index)?.let { toggleCollection(it.id) }
    }

    /** Confirm on a picker row: toggle this game's membership of that collection. */
    private fun toggleCollection(collectionId: Long) {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            collectionRepository.toggleGame(collectionId, gameId)
            _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(options = buildCollectionOptions(gameId))) }
        }
    }

    /** The picker's last row opens the new-collection prompt. */
    private fun startCreateCollection() {
        _uiState.update {
            it.copy(collectionPicker = it.collectionPicker.copy(showCreateDialog = true, createText = ""))
        }
    }

    fun onCreateCollectionTextChanged(text: String) {
        _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(createText = text)) }
    }

    fun confirmCreateCollection() {
        val gameId = _uiState.value.game?.id ?: return
        val name = _uiState.value.collectionPicker.createText
        if (name.isBlank()) { cancelCreateCollection(); return }
        viewModelScope.launch {
            val id = collectionRepository.create(name)
            collectionRepository.addGame(id, gameId)
            _uiState.update {
                it.copy(collectionPicker = it.collectionPicker.copy(
                    showCreateDialog = false,
                    createText = "",
                    options = buildCollectionOptions(gameId),
                ))
            }
        }
    }

    fun cancelCreateCollection() {
        _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(showCreateDialog = false, createText = "")) }
    }

    fun closeCollectionPicker() {
        _uiState.update { it.copy(collectionPicker = CollectionPickerUi()) }
    }

    // ── Favorite ──────────────────────────────────────────────────────────

    fun toggleFavorite() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            val next = !game.isFavorite
            gameRepository.setFavorite(game.id, next)
            _uiState.update { it.copy(game = game.copy(isFavorite = next)) }
        }
    }

    // ── Note editing ──────────────────────────────────────────────────────

    fun startEditNote() {
        _uiState.update { it.copy(isEditingNote = true, noteText = it.game?.userNote ?: "") }
    }

    fun onNoteChanged(text: String) = _uiState.update { it.copy(noteText = text) }

    fun saveNote() {
        val game = _uiState.value.game ?: return
        val note = _uiState.value.noteText.trim().ifEmpty { null }
        viewModelScope.launch {
            gameRepository.updateNote(game.id, note)
            _uiState.update { it.copy(game = game.copy(userNote = note), isEditingNote = false) }
        }
    }

    fun cancelNote() {
        _uiState.update { it.copy(isEditingNote = false, noteText = _uiState.value.game?.userNote ?: "") }
    }

    // ── Title editing ─────────────────────────────────────────────────────

    fun startEditTitle() {
        val game = _uiState.value.game ?: return
        _uiState.update { it.copy(isEditingTitle = true, titleText = game.displayTitle) }
    }

    fun onTitleChanged(text: String) = _uiState.update { it.copy(titleText = text) }

    fun saveTitle() {
        val game = _uiState.value.game ?: return
        val newTitle = _uiState.value.titleText.trim().ifEmpty { null }
        viewModelScope.launch {
            gameRepository.updateUserTitleOverride(game.id, newTitle)
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game           = updated ?: it.game,
                    isEditingTitle = false,
                    actionMessage  = if (newTitle != null) "Title updated to \"$newTitle\"" else "Title reset to default",
                )
            }
        }
    }

    fun resetTitleToDefault() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            gameRepository.updateUserTitleOverride(game.id, null)
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game           = updated ?: it.game,
                    isEditingTitle = false,
                    actionMessage  = "Title reset to \"${updated?.displayTitle ?: game.title}\"",
                )
            }
        }
    }

    fun cancelTitleEdit() {
        _uiState.update { it.copy(isEditingTitle = false, titleText = _uiState.value.game?.displayTitle ?: "") }
    }

    // ── Artwork — Fetch Artwork ───────────────────────────────────────────

    fun fetchArtwork() {
        val game = _uiState.value.game ?: return
        if (_uiState.value.isFetchingArtwork) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingArtwork = true, artworkMessage = null) }
            // Shared with the XMB game menu; it evicts this game's refs from the image cache.
            val result = artworkRepository.refetchArtworkForGame(game.id)
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game              = updated ?: it.game,
                    isFetchingArtwork = false,
                    artworkMessage    = when {
                        result.success        -> "Artwork updated"
                        result.alreadyRunning -> "Already fetching artwork for this game"
                        else                  -> result.errorMessage ?: "Artwork fetch failed"
                    },
                )
            }
        }
    }

    fun dismissArtworkMessage() = _uiState.update { it.copy(artworkMessage = null) }
    fun dismissLaunchError()    = _uiState.update { it.copy(launchError = null) }

    // ── Metadata presets — Current vs Incoming (C16 task 3.2) ─────────────

    // Bumped on every open and close: a retrieval that finishes after the overlay was closed (or
    // closed and reopened) is dropped rather than repainting a preview the user already left.
    private var metadataPreviewGeneration = 0L

    fun openMetadataPreview() {
        val game = _uiState.value.game ?: return
        if (_uiState.value.metadataPreview != null) return
        val generation = ++metadataPreviewGeneration
        _uiState.update { it.copy(showOptions = false, metadataPreview = MetadataPreviewUi(), actionMessage = null) }
        viewModelScope.launch {
            val outcome = runCatching { artworkRepository.fetchMetadataPreview(game.id) }
                .onFailure { Timber.w(it, "Metadata preview failed for game ${game.id}") }
            val preview = outcome.getOrNull()
            if (generation != metadataPreviewGeneration) return@launch
            _uiState.update { s ->
                if (s.metadataPreview == null) return@update s
                if (preview == null) {
                    // The retrieval itself failed or the game is gone. Nothing to edit against —
                    // an empty Manual column seeded from values we could not read would be a lie.
                    return@update s.copy(
                        metadataPreview = MetadataPreviewUi(loading = false, failed = outcome.isFailure),
                    )
                }
                // No provider preset is NOT a dead end any more: the Manual column is always
                // offered, and a game no scraper recognises is exactly the one worth typing.
                val loaded = MetadataPreviewUi(
                    loading    = false,
                    editable   = true,
                    current    = preview.current,
                    effective  = preview.effective,
                    overridden = preview.overridden,
                    presets    = preview.presets,
                    manualText = MetadataField.entries.associateWith {
                        metadataEditText(it, preview.effective[it])
                    },
                    chosen = preview.presets.firstOrNull()
                        ?.let { MetadataApply.changedFields(preview.current, it) }
                        .orEmpty(),
                    // Fill Missing Only is the non-destructive default for a provider. When no
                    // provider answered the overlay opens straight on the Manual column, where the
                    // right default is the other one: a value the user types means "use this".
                    policy = if (preview.presets.isEmpty()) MetadataApplyPolicy.REPLACE_ALL
                    else MetadataApplyPolicy.FILL_MISSING_ONLY,
                )
                // Focus starts on Apply: the default policy is the non-destructive one.
                s.copy(metadataPreview = loaded.copy(focus = loaded.applyIndex))
            }
        }
    }

    fun closeMetadataPreview() {
        metadataPreviewGeneration++
        _uiState.update { it.copy(metadataPreview = null) }
    }

    fun selectMetadataPolicy(policy: MetadataApplyPolicy) = updateMetadataPreview { it.copy(policy = policy) }

    fun cycleMetadataPolicy(delta: Int) = updateMetadataPreview { p ->
        val all = MetadataApplyPolicy.entries
        p.copy(policy = all[(p.policy.ordinal + delta).mod(all.size)])
    }

    /**
     * Switches source between the providers that answered and the Manual column; Choose Fields
     * re-ticks what THAT source would change.
     */
    fun cycleMetadataSource(delta: Int) = updateMetadataPreview { p ->
        if (p.sourceCount < 2) return@updateMetadataPreview p
        val index = (p.presetIndex + delta).mod(p.sourceCount)
        val moved = p.copy(presetIndex = index)
        val next = moved.copy(
            chosen = moved.preset?.let {
                MetadataApply.changedFields(if (moved.isManual) p.effective else p.current, it)
            }.orEmpty(),
            // Fill Missing Only is the right default for a provider — it is the non-destructive
            // one — and the wrong default for the user, whose typed value means "use this". So
            // entering the Manual column moves to Replace All; the policy chips still override it.
            policy = if (moved.isManual) MetadataApplyPolicy.REPLACE_ALL else moved.policy,
        )
        next.copy(focus = next.focus.coerceIn(0, next.applyIndex))
    }

    /**
     * Confirming a row. On a provider column that ticks the field for Choose Fields; on the Manual
     * column it opens that field's text editor, because there is nothing to tick — the value the
     * user types IS the choice.
     */
    private fun onMetadataRowSelected(field: MetadataField) {
        if (_uiState.value.metadataPreview?.isManual == true) startEditMetadataField(field)
        else toggleMetadataField(field)
    }

    /** Toggling a row IS choosing fields, so the policy follows to Choose Fields. */
    fun toggleMetadataField(field: MetadataField) = updateMetadataPreview { p ->
        p.copy(
            policy = MetadataApplyPolicy.CHOOSE_FIELDS,
            chosen = if (field in p.chosen) p.chosen - field else p.chosen + field,
        )
    }

    // ── The Manual column's per-field text editor ─────────────────────────

    fun startEditMetadataField(field: MetadataField) = updateMetadataPreview { p ->
        p.copy(editingField = field, editText = p.manualText[field].orEmpty())
    }

    fun onMetadataEditChanged(text: String) = updateMetadataPreview { p ->
        if (p.editingField == null) p else p.copy(editText = text)
    }

    /**
     * Keeps what was typed, in the preview only. Nothing reaches the database until Apply — the
     * overlay's one promise is that Back never writes.
     *
     * Typing a field that differs from what is shown also ticks it, so Choose Fields (which is what
     * editing a row means) does not silently drop the edit the user just made.
     */
    fun saveMetadataEdit() = updateMetadataPreview { p ->
        val field = p.editingField ?: return@updateMetadataPreview p
        val text = p.editText.trim()
        val edited = p.copy(
            manualText = p.manualText + (field to text),
            editingField = null,
            editText = "",
        )
        val changes = edited.preset?.let {
            MetadataApply.changedFields(edited.effective, it)
        }.orEmpty()
        edited.copy(chosen = if (field in changes) edited.chosen + field else edited.chosen - field)
    }

    fun cancelMetadataEdit() = updateMetadataPreview { p ->
        p.copy(editingField = null, editText = "")
    }

    /**
     * Reverts one field: the override is removed and the scraped value underneath — untouched the
     * whole time — becomes visible again. This one DOES write immediately, because it is a discard
     * of something already saved rather than a pending edit.
     */
    fun revertMetadataField(field: MetadataField) {
        val game = _uiState.value.game ?: return
        val p = _uiState.value.metadataPreview ?: return
        if (p.applying) return
        viewModelScope.launch {
            val reverted = runCatching { artworkRepository.clearMetadataOverride(game.id, field) }
                .onFailure { Timber.w(it, "Metadata revert failed for game ${game.id}") }
                .getOrDefault(false)
            val updated = gameRepository.getById(game.id)
            _uiState.update { s ->
                val preview = s.metadataPreview ?: return@update s
                // The scraped value is what the columns already held — no re-retrieval needed.
                val scraped = preview.current[field]
                s.copy(
                    game = updated ?: s.game,
                    metadataPreview = preview.copy(
                        effective = preview.effective + (field to scraped),
                        overridden = preview.overridden - field,
                        manualText = preview.manualText + (field to metadataEditText(field, scraped)),
                        chosen = preview.chosen - field,
                        editingField = null,
                        editText = "",
                    ),
                    actionMessage = if (reverted) "Reverted ${field.label} to the scraped value" else s.actionMessage,
                )
            }
        }
    }

    fun applyMetadataPreview() {
        val game = _uiState.value.game ?: return
        val p = _uiState.value.metadataPreview ?: return
        if (p.loading || p.applying) return
        // The empty preview's only button is Close.
        val preset = p.preset ?: return closeMetadataPreview()
        if (p.policy == MetadataApplyPolicy.KEEP_CURRENT) {
            closeMetadataPreview()
            showActionMessage("Kept current metadata")
            return
        }
        // The button already says "Nothing to Change"; asking the writer to confirm that costs a
        // row read and can only agree, since it plans from the same function.
        if (p.willWrite.isEmpty()) {
            closeMetadataPreview()
            showActionMessage("Nothing to change")
            return
        }
        _uiState.update { it.copy(metadataPreview = p.copy(applying = true)) }
        viewModelScope.launch {
            val written = runCatching { artworkRepository.applyMetadata(game.id, preset, p.policy, p.chosen) }
                .onFailure { Timber.w(it, "Metadata apply failed for game ${game.id}") }
            val updated = gameRepository.getById(game.id)
            metadataPreviewGeneration++
            _uiState.update {
                it.copy(
                    game = updated ?: it.game,
                    metadataPreview = null,
                    actionMessage = written.fold(
                        onSuccess = { fields ->
                            // "set by hand" rather than "from Manual": the provider wording reads
                            // as a source name, and for this one the source is the user.
                            val from =
                                if (preset.provider == MatchProvider.MANUAL) "set by hand"
                                else "from ${preset.provider.label}"
                            when (fields.size) {
                                0    -> "Nothing to change"
                                1    -> "Updated 1 field $from"
                                else -> "Updated ${fields.size} fields $from"
                            }
                        },
                        onFailure = { "Metadata update failed" },
                    ),
                )
            }
        }
    }

    private fun updateMetadataPreview(transform: (MetadataPreviewUi) -> MetadataPreviewUi) = _uiState.update { s ->
        val p = s.metadataPreview ?: return@update s
        if (p.loading || p.applying) s else s.copy(metadataPreview = transform(p))
    }

    /**
     * Metadata overlay input. Its field rows and Apply button are engine nodes, so UP/DOWN/Confirm
     * go through the shared engine like everywhere else; the policy and provider cycles stay
     * horizontal shortcuts, because those rows have no inline siblings for LEFT/RIGHT to traverse.
     */

    // -- Storefront match picker and Rematch (C23 T6, Phases 10 and 18) --------
    //
    // The rule these functions exist to keep: a storefront identity is written when, and only
    // when, a person chooses one. Opening either overlay resolves with `allowAutoLink = false`,
    // so looking costs nothing and changes nothing.

    private var storefrontGeneration = 0L

    /** Options - Rematch Storefront. Lists what this game is linked to today. */
    fun openStorefrontRematch() {
        val gameId = _uiState.value.game?.id ?: return
        val generation = ++storefrontGeneration
        _uiState.update {
            it.copy(
                showOptions = false,
                actionMessage = null,
                storefrontRematch = StorefrontRematchUi(gameTitle = it.game?.displayTitle.orEmpty()),
            )
        }
        viewModelScope.launch {
            val rows = runCatching { storefrontMatches.rematchRows(gameId) }
                .onFailure { Timber.w(it, "Could not read storefront identities for game %d", gameId) }
                .getOrDefault(emptyList())
            if (generation != storefrontGeneration) return@launch
            _uiState.update { state ->
                val current = state.storefrontRematch ?: return@update state
                state.copy(
                    storefrontRematch = current.copy(
                        loading = false,
                        rows = rows.map(::storefrontRematchRowOf),
                    ),
                )
            }
            syncNavStack()
        }
    }

    fun closeStorefrontRematch() {
        storefrontGeneration++
        _uiState.update { it.copy(storefrontRematch = null) }
        syncNavStack()
    }

    /** Tap on a Rematch row: focus it, then take whichever action it has selected. */
    fun onRematchRowTapped(index: Int) {
        val store = _uiState.value.storefrontRematch?.rows?.getOrNull(index)?.store ?: return
        if (!nav.touch(GameDetailKeys.storefrontStore(store.key))) takeRematchAction(store)
        finishInput()
    }

    /** Tap straight on one of a row's buttons - the touch path, where there is no Left/Right. */
    fun onRematchActionTapped(index: Int, action: RematchAction) {
        val row = _uiState.value.storefrontRematch?.rows?.getOrNull(index) ?: return
        if (!row.enabled) return
        nav.touch(GameDetailKeys.storefrontStore(row.store.key))
        updateRematch { ui ->
            ui.copy(rows = ui.rows.map { if (it.store == row.store) it.copy(selectedAction = action) else it })
        }
        takeRematchAction(row.store)
        finishInput()
    }

    /**
     * Left/Right on a Rematch row.
     *
     * The row is ONE focus stop with its actions selected horizontally, rather than two or three
     * separate stops. On a TV a line of small targets is hard to hit and easy to mis-hit, and
     * Remove is not a button anyone should reach by accident.
     */
    fun cycleRematchAction(delta: Int) = updateRematch { ui ->
        val row = ui.focusedRow ?: return@updateRematch ui
        if (row.actions.size <= 1) return@updateRematch ui
        val next = (row.actions.indexOf(row.selectedAction) + delta).mod(row.actions.size)
        ui.copy(
            rows = ui.rows.map {
                if (it.store == row.store) it.copy(selectedAction = row.actions[next]) else it
            },
        )
    }

    private fun takeRematchAction(store: Storefront) {
        val row = _uiState.value.storefrontRematch?.rows?.firstOrNull { it.store == store } ?: return
        if (!row.enabled) {
            showActionMessage(row.storeLabel + " isn't supported yet")
            return
        }
        when (row.selectedAction) {
            RematchAction.SEARCH, RematchAction.REPLACE -> openStorefrontMatch(ignoreStoredIdentity = true)
            RematchAction.REMOVE -> unlinkStorefront(store)
        }
    }

    private fun unlinkStorefront(store: Storefront) {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            runCatching { storefrontMatches.unlink(gameId, store) }
                .onFailure { Timber.w(it, "Could not unlink %s for game %d", store.key, gameId) }
            // Re-read rather than patching the row in place: the table is the truth, and a failed
            // delete must not leave the screen claiming the link is gone.
            val rows = runCatching { storefrontMatches.rematchRows(gameId) }.getOrDefault(emptyList())
            _uiState.update { state ->
                val current = state.storefrontRematch ?: return@update state
                state.copy(storefrontRematch = current.copy(rows = rows.map(::storefrontRematchRowOf)))
            }
            showActionMessage(store.label + " link removed. Your metadata is unchanged.")
            syncNavStack()
        }
    }

    /** The Rematch screen's "Search every store again". */
    fun searchAllStorefronts() {
        updateRematch { it.copy(searching = true) }
        openStorefrontMatch(ignoreStoredIdentity = true)
    }

    /**
     * Opens the picker.
     *
     * [ignoreStoredIdentity] is what Rematch passes: the only way past a stored id, and still
     * write-free - the existing link survives until the user picks a replacement.
     */
    fun openStorefrontMatch(ignoreStoredIdentity: Boolean = false) {
        val gameId = _uiState.value.game?.id ?: return
        val generation = ++storefrontGeneration
        _uiState.update {
            it.copy(
                showOptions = false,
                actionMessage = null,
                storefrontMatch = StorefrontMatchUi(gameTitle = it.game?.displayTitle.orEmpty()),
            )
        }
        viewModelScope.launch {
            val lookup = runCatching { storefrontMatches.lookup(gameId, ignoreStoredIdentity) }
                .onFailure { Timber.w(it, "Storefront lookup failed for game %d", gameId) }
                .getOrNull()
            if (generation != storefrontGeneration) return@launch
            _uiState.update { state ->
                val current = state.storefrontMatch ?: return@update state
                state.copy(
                    storefrontMatch = storefrontMatchFrom(current, lookup),
                    storefrontRematch = state.storefrontRematch?.copy(searching = false),
                )
            }
            syncNavStack()
        }
    }

    fun closeStorefrontMatch() {
        storefrontGeneration++
        _uiState.update {
            it.copy(storefrontMatch = null, storefrontRematch = it.storefrontRematch?.copy(searching = false))
        }
        syncNavStack()
    }

    fun openStorefrontMoreInfo() = updateMatch { ui ->
        if (ui.focusedCandidate == null) ui else ui.copy(moreInfoOpen = true)
    }

    fun closeStorefrontMoreInfo() = updateMatch { it.copy(moreInfoOpen = false) }

    /** Tap on a candidate row: focus first, then activate - one path for touch and controller. */
    fun onStorefrontRowTapped(index: Int) {
        val ui = _uiState.value.storefrontMatch ?: return
        val key =
            if (index >= ui.rows.size) GameDetailKeys.STOREFRONT_NO_MATCH
            else GameDetailKeys.storefrontCandidate(index)
        if (!nav.touch(key)) {
            if (index >= ui.rows.size) closeStorefrontMatch() else chooseStorefrontCandidate(index)
        }
        finishInput()
    }

    /** Select on a candidate. The one place a storefront identity is written. */
    fun chooseStorefrontCandidate(index: Int) {
        val gameId = _uiState.value.game?.id ?: return
        val ui = _uiState.value.storefrontMatch ?: return
        if (ui.confirming) return
        val row = ui.rows.getOrNull(index) ?: return closeStorefrontMatch()
        _uiState.update { it.copy(storefrontMatch = ui.copy(confirming = true)) }
        viewModelScope.launch {
            runCatching {
                storefrontMatches.confirm(gameId, row.candidate, ui.confidence ?: MatchConfidence.AMBIGUOUS)
            }.onFailure { Timber.w(it, "Could not store the chosen storefront identity") }
            storefrontGeneration++
            val rematchRows = if (_uiState.value.storefrontRematch != null) {
                runCatching { storefrontMatches.rematchRows(gameId) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _uiState.update { state ->
                state.copy(
                    storefrontMatch = null,
                    storefrontRematch = state.storefrontRematch?.copy(
                        searching = false,
                        rows = rematchRows.map(::storefrontRematchRowOf),
                    ),
                    actionMessage = "Linked to " + row.storeLabel + ": " + row.title,
                )
            }
            syncNavStack()
        }
    }

    /**
     * A finished lookup as picker state.
     *
     * Every branch ends with the overlay OPEN and saying something. A lookup that found nothing is
     * still an answer the user asked for, and closing the screen they just opened would leave them
     * wondering whether it ran at all.
     */
    private fun storefrontMatchFrom(
        current: StorefrontMatchUi,
        lookup: StorefrontMatchRepository.Lookup?,
    ): StorefrontMatchUi {
        val base = current.copy(loading = false)
        return when (lookup) {
            is StorefrontMatchRepository.Lookup.NeedsChoice -> {
                // One store at a time: the strongest store first, which with Steam alone is Steam.
                val pending = lookup.pending.first()
                base.copy(
                    query = lookup.query,
                    storeLabel = pending.store.label,
                    confidence = pending.confidence,
                    rows = pending.candidates.map(::storefrontRowOf),
                    focus = 0,
                )
            }
            is StorefrontMatchRepository.Lookup.Settled -> base.copy(
                storeLabel = lookup.identities.firstOrNull()?.storeLabel,
                settledLabel = lookup.identities.joinToString(", ") { it.record.resolvedTitle ?: it.storeLabel },
            )
            is StorefrontMatchRepository.Lookup.Unavailable ->
                base.copy(unavailableStores = lookup.stores.map { it.label })
            StorefrontMatchRepository.Lookup.NotApplicable -> base.copy(notApplicable = true)
            StorefrontMatchRepository.Lookup.NoMatch, StorefrontMatchRepository.Lookup.Unknown, null -> base
        }
    }

    private fun updateMatch(transform: (StorefrontMatchUi) -> StorefrontMatchUi) = _uiState.update { s ->
        val ui = s.storefrontMatch ?: return@update s
        if (ui.loading || ui.confirming) s else s.copy(storefrontMatch = transform(ui))
    }

    private fun updateRematch(transform: (StorefrontRematchUi) -> StorefrontRematchUi) = _uiState.update { s ->
        val ui = s.storefrontRematch ?: return@update s
        if (ui.loading) s else s.copy(storefrontRematch = transform(ui))
    }

    /**
     * Picker input. Rows are engine nodes, so Up/Down/Select go through the shared engine; Triangle
     * is More Information, which is the only key this overlay claims for itself.
     */
    private fun handleStorefrontMatchInput(action: GamepadAction) {
        val ui = _uiState.value.storefrontMatch ?: return
        if (ui.confirming) return   // the write is already committed to; let it finish
        if (ui.moreInfoOpen) {
            when (action) {
                GamepadAction.BACK -> closeStorefrontMoreInfo()
                GamepadAction.SELECT -> chooseStorefrontCandidate(ui.focus)
                else -> Unit
            }
            return
        }
        when (action) {
            GamepadAction.BACK -> closeStorefrontMatch()
            GamepadAction.OPEN_CONTEXT_MENU -> openStorefrontMoreInfo()
            GamepadAction.NAVIGATE_UP,
            GamepadAction.NAVIGATE_DOWN,
            GamepadAction.SELECT -> nav.handleAction(action)
            else -> Unit
        }
    }

    /** Rematch input. Left/Right pick a row's action; everything else is the shared engine. */
    private fun handleStorefrontRematchInput(action: GamepadAction) {
        when (action) {
            GamepadAction.BACK -> closeStorefrontRematch()
            GamepadAction.NAVIGATE_LEFT -> cycleRematchAction(-1)
            GamepadAction.NAVIGATE_RIGHT -> cycleRematchAction(+1)
            GamepadAction.NAVIGATE_UP,
            GamepadAction.NAVIGATE_DOWN,
            GamepadAction.SELECT -> nav.handleAction(action)
            else -> Unit
        }
    }

    private fun handleMetadataPreviewInput(action: GamepadAction) {
        val p = _uiState.value.metadataPreview ?: return
        if (p.applying) return   // the write is already committed to; let it finish
        if (p.editingField != null) {
            // The text editor is on top and owns the keyboard; Back discards the edit, like the
            // Edit Title dialog. Nothing else reaches the rows underneath.
            if (action == GamepadAction.BACK) cancelMetadataEdit()
            return
        }
        if (p.nothingFound) {
            // Nothing to choose between: Select and Back both dismiss the explanation.
            if (action == GamepadAction.SELECT || action == GamepadAction.BACK) closeMetadataPreview()
            return
        }
        when (action) {
            GamepadAction.BACK           -> closeMetadataPreview()
            GamepadAction.NAVIGATE_LEFT  -> cycleMetadataPolicy(-1)
            GamepadAction.NAVIGATE_RIGHT -> cycleMetadataPolicy(+1)
            GamepadAction.PREV_CATEGORY  -> cycleMetadataSource(-1)
            GamepadAction.NEXT_CATEGORY  -> cycleMetadataSource(+1)
            GamepadAction.NAVIGATE_UP,
            GamepadAction.NAVIGATE_DOWN,
            GamepadAction.SELECT         -> nav.handleAction(action)
            else -> Unit
        }
    }
}
