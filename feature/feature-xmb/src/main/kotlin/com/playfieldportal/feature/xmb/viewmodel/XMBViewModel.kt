package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.LibrarySourceDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.feature.appbar.AppCategoryRepository
import com.playfieldportal.feature.appbar.CategorizedApp
import com.playfieldportal.feature.appbar.LauncherShortcutRepository
import com.playfieldportal.feature.appbar.ShortcutHarvestResult
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.theme.DefaultPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.domain.model.XmbPalette
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.core.domain.model.resolve
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.library.scanner.RomScanner
import com.playfieldportal.feature.library.scanner.ScanResult
import com.playfieldportal.feature.library.scanner.ScanType
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.domain.model.MusicTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private fun XmbPalette.toPFPColors() = PFPColors(
    waveColor         = androidx.compose.ui.graphics.Color(waveColor),
    accentColor       = androidx.compose.ui.graphics.Color(accentColor),
    textPrimary       = androidx.compose.ui.graphics.Color(textColor),
    textSecondary     = androidx.compose.ui.graphics.Color(textColor).copy(alpha = 0.7f),
    backgroundOverlay = androidx.compose.ui.graphics.Color(0x88000000),
    selectedItem      = androidx.compose.ui.graphics.Color(accentColor),
    categoryBar       = androidx.compose.ui.graphics.Color(0x00000000),
    backgroundTop     = androidx.compose.ui.graphics.Color(backgroundTop),
    backgroundBottom  = androidx.compose.ui.graphics.Color(backgroundBottom),
)

// ── Context menu types ────────────────────────────────────────────────────────

data class XMBContextMenu(
    val title: String,
    val items: List<XMBContextMenuItem>,
    val selectedIndex: Int = 0,
    // Identifies the source of the menu (platform card, game, or app)
    val platformId: String? = null,
    val gameId: Long? = null,
    val packageName: String? = null,
    // The category the app is being acted on from (for remove/pin)
    val categoryContext: String? = null,
    // Set on the category-picker submenu: "move" or "add"
    val pendingAppAction: String? = null,
    // Set on the "Add to Collection" submenu — the game being added.
    val collectionGameId: Long? = null,
    // Set on a collection row's own options menu (rename / delete / open).
    val collectionRowId: Long? = null,
    // Host app's launcher-shortcut id when the menu is for a harvested per-game entry.
    val shortcutId: String? = null,
    // Captured legacy INSTALL_SHORTCUT launch intent when the menu is for such an entry.
    val launchIntentUri: String? = null,
    // Set on a music folder's options menu / a music track's options menu.
    val musicFolderId: String? = null,
    val musicTrackId: String? = null,
    // Set on a playlist row's options menu, and as context on a track menu opened inside a playlist
    // (so "Remove from this Playlist" knows which playlist).
    val playlistId: Long? = null,
    // Set on the "Add to Playlist" submenu — the track being added (marks that submenu).
    val playlistPickerTrackId: String? = null,
)

data class XMBContextMenuItem(
    val id: String,
    val label: String,
    val isDestructive: Boolean = false,
    // Renders a checkmark (e.g. collections the game already belongs to).
    val checked: Boolean = false,
)

// Drives the "Create New Collection" text dialog. When [forGameId] is set, the freshly
// created collection immediately receives that game.
data class CollectionNameDialogState(
    val title: String,
    val initialText: String = "",
    val forGameId: Long? = null,
    // When set, confirming renames this collection instead of creating a new one.
    val renameCollectionId: Long? = null,
)

// A simple read-only message dialog (e.g. "View File Location"). Dismissed with A/B or tap.
data class InfoDialogState(
    val title: String,
    val message: String,
)

// ── Color-scheme picker (opened from Settings ▸ Themes ▸ Color Scheme) ──────────
// A PSP-style submenu that previews each scheme live as the cursor moves over it.

data class ColorSchemePickerState(
    val options: List<ColorSchemeOption>,
    val selectedIndex: Int = 0,
)

data class ColorSchemeOption(
    val scheme: XmbColorScheme,
    val label: String,
    val sublabel: String,
    val swatch: Long, // ARGB preview color (the scheme's wave color)
)

// ── Installed-app picker ───────────────────────────────────────────────────────
// A reusable multi-select picker over installed apps. Where the selection goes is
// described by the target, so the same flow serves the Android Library ("Find Games")
// and app sections like Video / Music ("Add Apps").

sealed interface AppPickerTarget {
    // Selected apps become launchable Game entries under an Android-style Memory Card.
    data class AndroidGames(val platformId: String) : AppPickerTarget
    // Selected apps become launchable shortcuts in an app category (Video, Music, …).
    data class CategoryShortcuts(val categoryId: String) : AppPickerTarget
}

data class AppPickerEntry(
    val packageName: String,
    val label: String,
)

data class AppPickerState(
    val title: String,
    val target: AppPickerTarget,
    val apps: List<AppPickerEntry>,
    val selected: Set<String> = emptySet(),
    val selectedIndex: Int = 0,   // index 0 = the Confirm row; 1..n = apps
)

// ── Music navigation ───────────────────────────────────────────────────────────
// Which Music sub-screen is open. The Music root shows the static items (Now Playing / Playlist /
// Music Apps) plus the single "All Music" memory-card item; drilling into any of them swaps the
// item list without leaving the Music category.

sealed interface MusicNav {
    data object Root : MusicNav
    data object AllMusic : MusicNav
    data object Playlists : MusicNav
    data class Playlist(val id: Long, val name: String) : MusicNav
    data object MusicApps : MusicNav
}

// Drives the "New / Rename Playlist" text dialog. When [forTrackId] is set, the freshly created
// playlist immediately receives that track.
data class PlaylistNameDialogState(
    val title: String,
    val initialText: String = "",
    val forTrackId: String? = null,
    // When set, confirming renames this playlist instead of creating a new one.
    val renamePlaylistId: Long? = null,
)

// Multi-select picker over all scanned tracks, used by a playlist's "Add Tracks" row.
data class MusicTrackPickerState(
    val playlistId: Long,
    val playlistName: String,
    val tracks: List<MusicTrack>,
    val selected: Set<String> = emptySet(),
    val selectedIndex: Int = 0,   // index 0 = the Confirm row; 1..n = tracks
)

// ── Main XMB state ────────────────────────────────────────────────────────────

data class XMBUiState(
    // ── Horizontal axis: platforms (SD cards) + utility tabs ──────────────
    val categories: List<Category> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val platformGameCounts: Map<String, Int> = emptyMap(),
    // Total real games (content_type = GAME) across all platforms — the "All Games" count.
    val allGamesCount: Int = 0,
    // Count of favorited entries — drives the Games-root "Favorites" item visibility.
    val favoritesCount: Int = 0,
    val selectedPlatformId: String? = null,
    // When non-null, the Games category is showing the contents of a user collection.
    val selectedCollectionId: Long? = null,
    // User-created collections, ordered, shown under "All Games" in the Games root.
    val collections: List<GameCollection> = emptyList(),
    // Music drill-down: which Music sub-screen is open (Root shows the static items + All Music).
    val musicNav: MusicNav = MusicNav.Root,
    val musicFolders: List<com.playfieldportal.core.domain.model.MusicFolder> = emptyList(),
    // PSP-style sort (X / Square). Tracked per context so switching categories doesn't carry a
    // music sort into games. sortLabel is the status-bar hint, non-null only on a sortable list.
    val gameSortMode: XmbSortMode = XmbSortMode.TITLE,
    val musicSortMode: XmbSortMode = XmbSortMode.TITLE,
    val sortLabel: String? = null,
    // In-app music player: visible when a song is selected; playback state mirrors the controller.
    val musicPlayerVisible: Boolean = false,
    val musicPlayback: com.playfieldportal.feature.xmb.music.MusicPlaybackState =
        com.playfieldportal.feature.xmb.music.MusicPlaybackState(),

    // ── Vertical axis: games / settings items ─────────────────────────────
    val currentItems: List<XMBItem> = emptyList(),
    val selectedItemIndex: Int = 0,
    // Bumped whenever the list should snap back to the top regardless of cursor position — e.g. a
    // sort cycle. The item list scrolls to item 0 each time this changes (keyed reorders otherwise
    // keep the viewport anchored to the old top item).
    val scrollToTopToken: Int = 0,

    // ── Background + rendering ────────────────────────────────────────────
    // A custom wallpaper, when set, automatically replaces the wave.
    val waveStyle: WaveStyle = WaveStyle.ANIMATED,
    val customWallpaperPath: String? = null,

    val showBootSequence: Boolean = true,

    // ── Overlay screens ───────────────────────────────────────────────────
    val activeSettingsScreen: String? = null,
    val pendingSettingsAction: GamepadAction? = null,
    val activeAppDrawerFilter: String? = null,
    val pendingDrawerAction: GamepadAction? = null,
    val pendingGameDetailAction: GamepadAction? = null,
    val activeGameId: Long? = null,
    val activeAppId: Long? = null,
    val pendingAppDetailAction: GamepadAction? = null,

    // ── Context menu (Y/Triangle) ─────────────────────────────────────────
    val activeContextMenu: XMBContextMenu? = null,

    // ── Color-scheme picker (Settings ▸ Themes ▸ Color Scheme) ─────────────
    val colorSchemePicker: ColorSchemePickerState? = null,

    // ── App rename dialog ─────────────────────────────────────────────────
    val renameAppTarget: String? = null,    // package name being renamed
    val renameAppCurrent: String? = null,   // current label, prefills the field

    // ── Create-collection text dialog ─────────────────────────────────────
    val collectionNameDialog: CollectionNameDialogState? = null,

    // ── Create/rename-playlist text dialog ────────────────────────────────
    val playlistNameDialog: PlaylistNameDialogState? = null,

    // ── "Add Tracks" picker (inside a playlist) ───────────────────────────
    val musicTrackPicker: MusicTrackPickerState? = null,

    // ── Simple read-only info dialog (e.g. file location) ──────────────────
    val infoDialog: InfoDialogState? = null,

    // ── Installed-app picker (Android Library / Video / Music) ─────────────
    val appPicker: AppPickerState? = null,

    // ── Game picker (for adding games to gaming categories) ────────────────
    val gamePickerCategoryId: String? = null,
    val pendingGamePickerAction: GamepadAction? = null,

    // ── Misc ──────────────────────────────────────────────────────────────
    val iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    val librarySetupComplete: Boolean = false,
    val themeColors: PFPColors = DefaultPFPColors,
) {
    // True whenever something is layered over the main XMB. The gamepad dispatcher uses this
    // as a final guard so D-Pad/A never drives the category bar or item list behind an overlay.
    val hasBlockingOverlay: Boolean
        get() = showBootSequence ||
            activeSettingsScreen != null ||
            activeAppDrawerFilter != null ||
            activeGameId != null ||
            activeAppId != null ||
            activeContextMenu != null ||
            colorSchemePicker != null ||
            appPicker != null ||
            gamePickerCategoryId != null ||
            renameAppTarget != null ||
            collectionNameDialog != null ||
            playlistNameDialog != null ||
            musicTrackPicker != null ||
            musicPlayerVisible ||
            infoDialog != null
}

enum class XMBItemType {
    STANDARD,
    ALL_GAMES,
    FAVORITES,
    MEMORY_CARD,
    COLLECTION,
    MUSIC_FOLDER,
    MUSIC_TRACK,
    PLAYLIST,
    MUSIC_APPS,
    EMPTY,
}

// PSP-style sort cycling (X / Square). Each list type cycles only the modes that make sense for
// it (see MUSIC_SORTS / GAME_SORTS); a shared enum keeps the status-bar label simple.
enum class XmbSortMode(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENT_PLAYED("Recently Played"),
    DATE_ADDED("Date Added"),
}

private val MUSIC_SORTS = listOf(XmbSortMode.TITLE, XmbSortMode.ARTIST, XmbSortMode.ALBUM, XmbSortMode.DATE_ADDED)
private val GAME_SORTS  = listOf(XmbSortMode.TITLE, XmbSortMode.RECENT_PLAYED, XmbSortMode.DATE_ADDED)

// Pure sort comparators (top-level so they're unit-testable). DATE_ADDED uses the autoincrement
// game id / file lastModified as the recency proxy; modes that don't apply fall back to title.
internal fun List<Game>.gameSorted(mode: XmbSortMode): List<Game> = when (mode) {
    XmbSortMode.RECENT_PLAYED -> sortedByDescending { it.lastPlayedAt ?: 0L }
    XmbSortMode.DATE_ADDED    -> sortedByDescending { it.id }
    else                      -> sortedBy { it.displayTitle.lowercase() }
}

internal fun List<MusicTrack>.trackSorted(mode: XmbSortMode): List<MusicTrack> = when (mode) {
    XmbSortMode.ARTIST     -> sortedWith(
        compareBy(nullsLast<String>()) { t: MusicTrack -> t.artist?.lowercase() }
            .thenBy(nullsLast<String>()) { t -> t.album?.lowercase() }
            .thenBy { t -> t.displayTitle.lowercase() }
    )
    XmbSortMode.ALBUM      -> sortedWith(
        compareBy(nullsLast<String>()) { t: MusicTrack -> t.album?.lowercase() }
            .thenBy { t -> t.trackNumber ?: Int.MAX_VALUE }
            .thenBy { t -> t.displayTitle.lowercase() }
    )
    XmbSortMode.DATE_ADDED -> sortedByDescending { it.lastModified ?: 0L }
    else                   -> sortedBy { it.displayTitle.lowercase() }
}

data class XMBItem(
    val id: String,
    val title: String,
    val artworkUri: String? = null,
    val heroUri: String? = null,        // PIC1 / hero background art
    val iconUri: String? = null,        // landscape 144:80 icon art (SGDB horizontal grid)
    val subtitle: String? = null,
    val gameId: Long? = null,
    val platformId: String? = null,
    val collectionId: Long? = null,     // set on COLLECTION rows in the Games root
    val accentColor: Long? = null,
    val isFavorite: Boolean = false,
    val isAndroidApp: Boolean = false,
    val packageName: String? = null,
    // Host app's launcher-shortcut id for harvested per-game entries; launched via LauncherApps.
    val shortcutId: String? = null,
    // Captured legacy INSTALL_SHORTCUT launch intent (Intent.toUri); launched by parsing it.
    val launchIntentUri: String? = null,
    // Music: folder id (on MUSIC_FOLDER rows) and the track's SAF uri + mime (on MUSIC_TRACK rows).
    val musicFolderId: String? = null,
    val mediaUri: String? = null,
    val mimeType: String? = null,
    // Square album-cover art (on MUSIC_TRACK rows); a file:// uri cached during scan, may be null.
    val coverUri: String? = null,
    // Playlist id on PLAYLIST rows.
    val playlistId: Long? = null,
    val type: XMBItemType = XMBItemType.STANDARD,
)

// A unit of background work surfaced to the notification bar. [progress] null = indeterminate.
data class BackgroundTaskInfo(
    val id: String,
    val label: String,
    val progress: Float?,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * The single source of truth for the XMB home screen.
 *
 * Exposes one [XMBUiState] via [uiState] that the stateless `XMBShell` renders. It owns:
 *  - the category bar (built-in + custom categories) and the item list under the selected category;
 *  - navigation into synthetic Games-root folders (All Games, Favorites, collections) and Memory
 *    Card consoles, tracked by [XMBUiState.selectedPlatformId] / `selectedCollectionId`;
 *  - the gamepad input dispatcher, which routes D-pad/A/B/Y to whichever overlay or layer has focus
 *    (guarded by [XMBUiState.hasBlockingOverlay]);
 *  - game/app launching, context menus, and the various modal overlays (pickers, dialogs).
 *
 * Library state (memory cards, game counts, collections, favorites) is observed reactively, so the
 * XMB re-renders as the underlying data changes.
 */
@HiltViewModel
class XMBViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val platformDao: PlatformDao,
    private val themeDao: ThemeDao,
    private val librarySourceDao: LibrarySourceDao,
    private val memoryCardRepository: MemoryCardRepository,
    private val collectionRepository: CollectionRepository,
    private val categoryRepository: CategoryRepositoryImpl,
    private val appCategoryRepository: AppCategoryRepository,
    private val gameCategoryRepository: com.playfieldportal.core.data.repository.GameCategoryRepository,
    private val launcherShortcutRepository: LauncherShortcutRepository,
    private val romScanner: RomScanner,
    private val artworkRepository: ArtworkRepository,
    @ApplicationContext private val context: Context,
    private val gamepadInputHandler: GamepadInputHandler,
    private val mappingRepository: ControllerMappingRepository,
    private val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer,
    private val musicRepository: com.playfieldportal.core.domain.repository.MusicRepository,
    private val musicScanner: com.playfieldportal.feature.library.scanner.MusicScanner,
    private val musicIntentResolver: com.playfieldportal.core.data.music.MusicIntentResolver,
    private val musicPlayer: com.playfieldportal.feature.xmb.music.MusicPlayerController,
) : ViewModel() {

    // The track list currently on screen (in display/sort order), used as the in-app player's queue
    // when a song is picked. [currentMusicTracksRaw] is the same set in DB order, kept so a sort
    // cycle can re-order instantly without waiting on a fresh DB emission.
    private var currentMusicTracks: List<MusicTrack> = emptyList()
    private var currentMusicTracksRaw: List<MusicTrack> = emptyList()

    // Tracks whether playback currently has a track, so the Music root only rebuilds (to add/drop
    // the "Now Playing" row) when that flips — not on every half-second playback tick.
    private var lastHadPlayingTrack = false

    // Cached so a track launch (a discrete event) doesn't need to suspend-read DataStore.
    @Volatile
    private var defaultMusicPlayer: String? = null

    private val _uiState = MutableStateFlow(XMBUiState())
    val uiState: StateFlow<XMBUiState> = _uiState.asStateFlow()

    private var currentItemsJob: Job? = null
    private var platformCache: Map<String, PlatformEntity> = emptyMap()
    private var enabledCards: List<MemoryCard> = emptyList()
    private var baseThemeColors: PFPColors = DefaultPFPColors

    // Background work is surfaced to the Android notification bar, not an in-app tray.
    private val taskNotifier = BackgroundTaskNotifier(context)

    init {
        gamepadInputHandler.scope = viewModelScope
        observeIconStyle()
        observeBackgroundSettings()
        observeWallpaper()
        observeLibrarySetupState()
        observeColorScheme()
        observeCategoryBar()
        observeCategories()
        observeAppChanges()
        observeGamepadMappings()
        observeSoundSetting()
        observeMusic()
        collectGamepadActions()
    }

    // Music folders drive the Music category's root list; the default player is cached for launch.
    private fun observeMusic() {
        viewModelScope.launch {
            musicRepository.observeFolders().collect { folders ->
                _uiState.update { it.copy(musicFolders = folders) }
                if (currentCategory()?.id == BuiltInCategory.MUSIC &&
                    _uiState.value.musicNav == MusicNav.Root
                ) {
                    loadItemsForCategory(currentCategory())
                }
            }
        }
        viewModelScope.launch {
            musicRepository.observeDefaultPlayerPackage().collect { defaultMusicPlayer = it }
        }
        viewModelScope.launch {
            musicPlayer.state.collect { playback ->
                _uiState.update { it.copy(musicPlayback = playback) }
                // Rebuild the Music root only when playback gains/loses a track, so the "Now Playing"
                // row appears/disappears — never on the half-second position ticks.
                val hasTrack = playback.track != null
                if (hasTrack != lastHadPlayingTrack) {
                    lastHadPlayingTrack = hasTrack
                    if (currentCategory()?.id == BuiltInCategory.MUSIC &&
                        _uiState.value.musicNav == MusicNav.Root
                    ) {
                        loadItemsForCategory(currentCategory())
                    }
                }
            }
        }
    }

    // Menu sounds default on; the Display settings toggle persists the override.
    private fun observeSoundSetting() {
        viewModelScope.launch {
            context.pfpDataStore.data
                .map { it[KEY_MENU_SOUND_ENABLED] ?: true }
                .distinctUntilChanged()
                .collect { menuSound.enabled = it }
        }
    }

    // ── Color scheme ────────────────────────────────────────────────────────────

    // The active XMB color scheme (PSP-style presets + the month-based "Original" theme)
    // is the source of truth for the palette. ORIGINAL re-resolves to the current month each
    // time the scheme is (re)observed — i.e. on app start and whenever the user changes it.
    private fun observeColorScheme() {
        viewModelScope.launch {
            context.pfpDataStore.data
                .map { it[KEY_COLOR_SCHEME] ?: XmbColorScheme.CLASSIC_BLUE.name }
                .distinctUntilChanged()
                .collect { name ->
                    val scheme = runCatching { XmbColorScheme.valueOf(name) }
                        .getOrDefault(XmbColorScheme.CLASSIC_BLUE)
                    val month = java.time.LocalDate.now().monthValue
                    baseThemeColors = scheme.resolve(month).toPFPColors()
                    // Re-apply the current category's accent tint on top of the new base palette.
                    val category = _uiState.value.categories
                        .getOrNull(_uiState.value.selectedCategoryIndex)
                    val waveColor = category?.accentColor
                        ?.let { androidx.compose.ui.graphics.Color(it) }
                        ?: baseThemeColors.waveColor
                    _uiState.update { it.copy(themeColors = baseThemeColors.copy(waveColor = waveColor)) }
                }
        }
    }

    // ── Category bar (DB-driven) ────────────────────────────────────────────────

    // The main XMB is intentionally the seven PSP-style categories from the launcher spec.
    // Platform folders stay inside Game as Memory Card rows.
    private fun observeCategoryBar() {
        viewModelScope.launch {
            categoryRepository.observeVisible().collect { categories ->
                val allCategories = canonicalXmbCategories(categories.ifEmpty { FALLBACK_CATEGORIES })
                val prevId   = _uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex)?.id
                val isInitialSelection = _uiState.value.categories.isEmpty()
                // Keep the same category selected across reorders/hides when possible.
                val newIndex = if (isInitialSelection) {
                    defaultXmbCategoryIndex(allCategories)
                } else {
                    allCategories.indexOfFirst { it.id == prevId }
                        .takeIf { it >= 0 }
                        ?: defaultXmbCategoryIndex(allCategories)
                }
                val newId    = allCategories.getOrNull(newIndex)?.id

                _uiState.update { it.copy(categories = allCategories, selectedCategoryIndex = newIndex) }

                if (newId != prevId || _uiState.value.currentItems.isEmpty()) {
                    tintWaveForCategory(allCategories.getOrNull(newIndex))
                    loadItemsForCategory(allCategories.getOrNull(newIndex))
                }
            }
        }
    }

    // ── Library (memory cards + game counts) ────────────────────────────────────

    private fun observeCategories() {
        viewModelScope.launch {
            combine(
                memoryCardRepository.observeEnabled(),
                gameRepository.observeAll(),
                platformDao.observeAll(),
                collectionRepository.observeCollections(),
            ) { cards, games, platforms, collections -> CardsGamesPlatformsCollections(cards, games, platforms, collections) }
                .collect { (cards, games, platforms, collections) ->
                    platformCache = platforms.associateBy { it.id }
                    enabledCards  = cards
                    val counts = games.groupBy { it.platformId }.mapValues { it.value.size }
                    val gamesOnlyTotal = games.count { it.contentType == GameContentType.GAME }
                    val favoritesTotal = games.count { it.isFavorite }

                    // Drop a stale platform folder if its card was removed or disabled. The
                    // synthetic All Games and Favorites folders are always valid.
                    val validPlatformId = _uiState.value.selectedPlatformId
                        ?.takeIf { id ->
                            id == ALL_GAMES_PLATFORM_ID ||
                                id == FAVORITES_PLATFORM_ID ||
                                cards.any { c -> c.platformId == id }
                        }
                    // Drop a stale collection folder if the collection was deleted.
                    val validCollectionId = _uiState.value.selectedCollectionId
                        ?.takeIf { id -> collections.any { c -> c.id == id } }

                    _uiState.update { it.copy(
                        platformGameCounts = counts,
                        allGamesCount = gamesOnlyTotal,
                        favoritesCount = favoritesTotal,
                        selectedPlatformId = validPlatformId,
                        selectedCollectionId = validCollectionId,
                        collections = collections,
                    )}

                    // Refresh any gaming category live as cards/counts/collections change —
                    // collections now place themselves by categoryId, so a custom gaming
                    // category must also re-render when the collection list changes.
                    if (currentCategory()?.isGamingCategory == true) {
                        loadItemsForCategory(currentCategory())
                    }
                }
        }
    }

    private data class CardsGamesPlatformsCollections(
        val cards: List<MemoryCard>,
        val games: List<Game>,
        val platforms: List<PlatformEntity>,
        val collections: List<GameCollection>,
    )

    // ── App category changes (assignments / overrides) ──────────────────────────

    private fun observeAppChanges() {
        viewModelScope.launch {
            appCategoryRepository.changes().collect {
                val category = currentCategory() ?: return@collect
                if (isAppCategory(category.id)) loadItemsForCategory(category)
            }
        }
    }

    private fun currentCategory(): Category? =
        _uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex)

    // App-populated categories are everything except Settings and Games.
    private fun isAppCategory(categoryId: String): Boolean =
        categoryId != BuiltInCategory.SETTINGS && categoryId != BuiltInCategory.GAMES

    private fun loadItemsForCategory(category: Category?) {
        currentItemsJob?.cancel()
        if (category == null) { _uiState.update { it.copy(currentItems = emptyList(), sortLabel = null) }; return }
        _uiState.update { it.copy(sortLabel = currentSortLabel()) }

        currentItemsJob = viewModelScope.launch {
            when (category.id) {
                BuiltInCategory.FAVORITES -> {
                    gameRepository.observeFavorites().collect { games ->
                        _uiState.update { it.copy(currentItems = games.gameSorted(_uiState.value.gameSortMode).toXmbItems()) }
                    }
                }
                BuiltInCategory.ANDROID -> {
                    _uiState.update { it.copy(currentItems = ANDROID_ITEMS) }
                }
                BuiltInCategory.SETTINGS -> {
                    _uiState.update { it.copy(currentItems = SETTINGS_ITEMS) }
                }
                BuiltInCategory.GAMES -> {
                    val platformId = _uiState.value.selectedPlatformId
                    val collectionId = _uiState.value.selectedCollectionId
                    if (collectionId != null) {
                        // A user collection — games from any platform, app entries allowed only
                        // because they were explicitly added by the user.
                        collectionRepository.observeGames(collectionId).collect { games ->
                            val items = if (games.isEmpty()) listOf(emptyCollectionItem())
                                        else games.gameSorted(_uiState.value.gameSortMode).toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                    } else if (platformId == ALL_GAMES_PLATFORM_ID) {
                        // All Games aggregates real games only (content_type = GAME).
                        gameRepository.observeGamesOnly().collect { games ->
                            val items = if (games.isEmpty()) listOf(emptyAllGamesItem())
                                        else games.gameSorted(_uiState.value.gameSortMode).toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                    } else if (platformId == FAVORITES_PLATFORM_ID) {
                        // Favorites folder — every favorited entry (games and app shortcuts).
                        gameRepository.observeFavorites().collect { games ->
                            val items = if (games.isEmpty()) listOf(emptyFavoritesItem())
                                        else games.gameSorted(_uiState.value.gameSortMode).toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                    } else if (platformId != null) {
                        gameRepository.observeByPlatform(platformId).collect { games ->
                            val items = if (games.isEmpty()) listOf(emptyFolderItem(platformId))
                                        else games.gameSorted(_uiState.value.gameSortMode).toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                    } else {
                        _uiState.update { it.copy(currentItems = memoryCardItems()) }
                    }
                }
                BuiltInCategory.MUSIC -> when (val nav = _uiState.value.musicNav) {
                    MusicNav.Root -> {
                        clearMusicTrackCache()
                        _uiState.update { it.copy(currentItems = musicRootItems()) }
                    }
                    MusicNav.AllMusic -> musicRepository.observeAllTracks().collect { tracks ->
                        setMusicTrackItems(tracks, emptyAllMusicItem())
                    }
                    is MusicNav.Playlist -> musicRepository.observePlaylistTracks(nav.id).collect { tracks ->
                        setMusicTrackItems(tracks, emptyPlaylistItem(), trailing = listOf(addTracksItem()))
                    }
                    MusicNav.Playlists -> {
                        clearMusicTrackCache()
                        musicRepository.observePlaylists().collect { playlists ->
                            _uiState.update { it.copy(currentItems = playlistRootItems(playlists)) }
                        }
                    }
                    MusicNav.MusicApps -> {
                        clearMusicTrackCache()
                        val items = musicAppItems()
                        _uiState.update { it.copy(currentItems = items) }
                    }
                }
                else -> {
                    // Gaming categories show games and collections
                    if (category.isGamingCategory) {
                        // Drilled into one of this category's collections — show its games.
                        val openCollectionId = _uiState.value.selectedCollectionId
                        if (openCollectionId != null) {
                            collectionRepository.observeGames(openCollectionId).collect { games ->
                                val items = if (games.isEmpty()) listOf(emptyCollectionItem())
                                            else games.gameSorted(_uiState.value.gameSortMode).toXmbItems()
                                _uiState.update { it.copy(currentItems = items) }
                            }
                            return@launch
                        }
                        // Games are assigned via the junction table (echo/copy model). Reuse the
                        // canonical Game→XMBItem mapping so icons/artwork/launch fields match the
                        // Main Game category exactly; only overlay the "Pinned" marker.
                        val gameRows = gameCategoryRepository.itemsForCategory(category.id)
                            .filterIsInstance<com.playfieldportal.core.data.repository.GameCategoryItem.GameItem>()
                        val pinnedGameIds = gameRows.filter { it.pinned }.map { it.game.id }.toSet()
                        val gameItems = gameRows.map { it.game }.gameSorted(_uiState.value.gameSortMode).toXmbItems().map { xmb ->
                            if (xmb.gameId in pinnedGameIds) xmb.copy(subtitle = "Pinned") else xmb
                        }
                        // Collections belong to exactly one category, tracked by categoryId —
                        // the single source of truth for placement (not the junction table).
                        // Pinned collections sort to the top.
                        val collectionItems = _uiState.value.collections
                            .filter { it.categoryId == category.id }
                            .sortedByDescending { it.isPinned }
                            .map { collection ->
                                val games = "${collection.gameCount} ${if (collection.gameCount == 1) "Game" else "Games"}"
                                XMBItem(
                                    id = "col_${collection.id}",
                                    title = collection.name,
                                    subtitle = if (collection.isPinned) "Pinned · $games" else games,
                                    collectionId = collection.id,
                                    type = XMBItemType.COLLECTION,
                                )
                            }
                        val combined = collectionItems + gameItems
                        val items = if (combined.isEmpty()) listOf(emptyCategoryItem(category)) else combined
                        _uiState.update { it.copy(currentItems = items + addGamesItem()) }
                    } else {
                        // Non-gaming categories show apps (Photo / Music / Video / Network / App Store / custom).
                        // Apps the user has given artwork (via Edit App Details → a games-table row keyed
                        // by package, typed ANDROID_APP) render that art so the category looks uniform.
                        // The rows stay ANDROID_APP, so this never makes them appear in All Games.
                        val apps = appCategoryRepository.appsForCategory(category.id)
                        val appItems = if (apps.isEmpty()) listOf(emptyCategoryItem(category))
                                       else apps.map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
                        // "Add Apps" is offered on every app section so the same picker serves
                        // Video, Music, Network, App Store and custom categories alike.
                        _uiState.update { it.copy(currentItems = appItems + addAppsItem()) }
                    }
                }
            }
        }
    }

    // [artwork] is the app's optional games-table row (ANDROID_APP, keyed by package). When it
    // carries landscape art, the item shows the game-style tile; gameId stays null so the row keeps
    // app behaviour (app context menu, package launch) and never aggregates into All Games.
    private fun CategorizedApp.toXmbItem(
        artwork: com.playfieldportal.core.domain.model.Game? = null,
    ): XMBItem = XMBItem(
        id           = "app_$packageName",
        title        = label,
        subtitle     = if (pinned) "Pinned" else null,
        packageName  = packageName,
        isAndroidApp = true,
        iconUri      = artwork?.let { it.iconUri ?: it.heroUri ?: it.artworkUri },
        accentColor  = artwork?.let { platformCache[it.platformId]?.accentColor },
    )

    private fun addAppsItem(): XMBItem = XMBItem(
        id       = ADD_APPS_ITEM_ID,
        title    = "Add Apps",
        subtitle = "Pick installed apps to add to this section",
    )

    private fun addGamesItem(): XMBItem = XMBItem(
        id       = ADD_GAMES_ITEM_ID,
        title    = "Add Games",
        subtitle = "Pick games and collections to add to this category",
    )

    // ── Music ───────────────────────────────────────────────────────────────────

    // Music root: the static items (Now Playing, when something is playing; Playlist; Music Apps)
    // followed by the single "All Music" memory-card item. Folder management lives in Settings →
    // Music; an "Add Music Folder" row only appears here while no folders are configured yet.
    private fun musicRootItems(): List<XMBItem> {
        val folders = _uiState.value.musicFolders
        val totalTracks = folders.sumOf { it.trackCount }
        return buildList {
            // Now Playing — only when a track is loaded; clicking returns to the active song.
            _uiState.value.musicPlayback.track?.let { track ->
                add(
                    XMBItem(
                        id       = NOW_PLAYING_ITEM_ID,
                        title    = track.displayTitle,
                        subtitle = listOfNotNull("Now Playing", track.artist).joinToString("  ·  "),
                        coverUri = track.artUri,
                        type     = XMBItemType.MUSIC_TRACK,   // renders the album-cover leading tile
                    )
                )
            }
            add(
                XMBItem(
                    id       = PLAYLISTS_ITEM_ID,
                    title    = "Playlist",
                    subtitle = "Build and play your own track lists",
                    type     = XMBItemType.PLAYLIST,
                )
            )
            add(
                XMBItem(
                    id       = MUSIC_APPS_ITEM_ID,
                    title    = "Music Apps",
                    subtitle = "Open your installed music apps",
                    type     = XMBItemType.MUSIC_APPS,
                )
            )
            // All scanned music collapses into one memory-card item (like All Games). Uses the
            // physical-media "_default.png" memory-card art rather than the blank console fallback.
            add(
                XMBItem(
                    id       = ALL_MUSIC_ITEM_ID,
                    title    = "Music",
                    subtitle = "$totalTracks ${if (totalTracks == 1) "track" else "tracks"}",
                    coverUri = MEMORY_CARD_ASSET_URI,
                    type     = XMBItemType.MEMORY_CARD,
                )
            )
            if (folders.isEmpty()) {
                add(
                    XMBItem(
                        id       = ADD_MUSIC_FOLDER_ITEM_ID,
                        title    = "Add Music Folder",
                        subtitle = "Pick a folder of music to get started",
                    )
                )
            }
        }
    }

    // Playlist list: one row per playlist + a "Create Playlist" row.
    private fun playlistRootItems(playlists: List<com.playfieldportal.core.domain.model.Playlist>): List<XMBItem> {
        val rows = playlists.map { pl ->
            XMBItem(
                id         = "pl_${pl.id}",
                title      = pl.name,
                subtitle   = "${pl.trackCount} ${if (pl.trackCount == 1) "track" else "tracks"}",
                playlistId = pl.id,
                type       = XMBItemType.PLAYLIST,
            )
        }
        return rows + XMBItem(
            id       = CREATE_PLAYLIST_ITEM_ID,
            title    = "Create Playlist",
            subtitle = "Start a new playlist",
        )
    }

    // Music Apps: the apps the user added (stored under a dedicated pseudo-category so they don't
    // mix with the built-in Music category), plus an "Add Music Apps" row.
    private suspend fun musicAppItems(): List<XMBItem> {
        val apps = appCategoryRepository.appsForCategory(MUSIC_APPS_CATEGORY_ID)
        val appItems = apps.map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
        return appItems + XMBItem(
            id       = ADD_MUSIC_APPS_ITEM_ID,
            title    = "Add Music Apps",
            subtitle = "Pick installed apps to show here",
        )
    }

    private fun addTracksItem(): XMBItem = XMBItem(
        id       = ADD_TRACKS_ITEM_ID,
        title    = "Add Tracks",
        subtitle = "Pick songs to add to this playlist",
    )

    private fun List<com.playfieldportal.core.domain.model.MusicTrack>.toMusicItems(): List<XMBItem> =
        map { track ->
            XMBItem(
                id            = "mt_${track.id}",
                title         = track.displayTitle,
                subtitle      = track.artist?.takeIf { it.isNotBlank() },
                type          = XMBItemType.MUSIC_TRACK,
                mediaUri      = track.uri,
                mimeType      = track.mimeType,
                coverUri      = track.artUri,
                musicFolderId = track.folderId,
            )
        }

    // Caches the on-screen track list (raw + sorted) and pushes the sorted items, or an empty-state
    // row when there are none. [trailing] rows (e.g. a playlist's "Add Tracks") always show.
    private fun setMusicTrackItems(
        tracks: List<MusicTrack>,
        emptyItem: XMBItem,
        trailing: List<XMBItem> = emptyList(),
    ) {
        currentMusicTracksRaw = tracks
        val sorted = tracks.trackSorted(_uiState.value.musicSortMode)
        currentMusicTracks = sorted
        val items = if (sorted.isEmpty()) listOf(emptyItem) else sorted.toMusicItems()
        _uiState.update { it.copy(currentItems = items + trailing) }
    }

    private fun clearMusicTrackCache() {
        currentMusicTracks = emptyList()
        currentMusicTracksRaw = emptyList()
    }

    private fun emptyAllMusicItem(): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "No music found",
        subtitle = "Add a music folder in Settings → Music",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyPlaylistItem(): XMBItem = XMBItem(
        id       = EMPTY_PLAYLIST_ITEM_ID,
        title    = "This playlist is empty",
        subtitle = "Add tracks below or from a song's options (△) menu.",
        type     = XMBItemType.EMPTY,
    )

    // ── Music navigation (drill into / out of the Music sub-screens) ────────────
    private fun openMusicView(nav: MusicNav) {
        _uiState.update { it.copy(musicNav = nav, selectedItemIndex = 0) }
        loadItemsForCategory(currentCategory())
    }

    private fun closeMusicView() {
        _uiState.update { it.copy(musicNav = MusicNav.Root, selectedItemIndex = 0) }
        loadItemsForCategory(currentCategory())
    }

    // Handles A/Cross on any Music row. Returns true when [item] is a Music row it owns. Empty-state
    // rows are consumed silently; everything else plays its own select/launch sound.
    private fun handleMusicSelection(item: XMBItem): Boolean = when {
        item.type == XMBItemType.EMPTY -> true   // not selectable
        item.id == NOW_PLAYING_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            if (_uiState.value.musicPlayback.track != null) _uiState.update { it.copy(musicPlayerVisible = true) }
            true
        }
        item.id == PLAYLISTS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicView(MusicNav.Playlists); true }
        item.id == MUSIC_APPS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicView(MusicNav.MusicApps); true }
        item.id == ALL_MUSIC_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicView(MusicNav.AllMusic); true }
        item.id == ADD_MUSIC_FOLDER_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            _uiState.update { it.copy(activeSettingsScreen = "settings_music") }
            true
        }
        item.id == CREATE_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); promptCreatePlaylist(); true }
        item.id == ADD_MUSIC_APPS_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            openAppPicker(AppPickerTarget.CategoryShortcuts(MUSIC_APPS_CATEGORY_ID), "Add Music Apps")
            true
        }
        item.id == ADD_TRACKS_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            (_uiState.value.musicNav as? MusicNav.Playlist)?.let { openMusicTrackPicker(it.id) }
            true
        }
        item.type == XMBItemType.MUSIC_TRACK -> { menuSound.play(MenuSound.SELECT); openMusicPlayerForItem(item); true }
        item.type == XMBItemType.PLAYLIST && item.playlistId != null -> {
            menuSound.play(MenuSound.SELECT); openMusicView(MusicNav.Playlist(item.playlistId, item.title)); true
        }
        // Music-app rows launch the app.
        _uiState.value.musicNav == MusicNav.MusicApps && item.packageName != null -> {
            menuSound.play(MenuSound.LAUNCH); appCategoryRepository.launch(item.packageName); true
        }
        else -> false
    }

    // Selecting a song opens the in-app full player, with the on-screen track list as the queue.
    private fun openMusicPlayerForItem(item: XMBItem) {
        val trackId = item.id.removePrefix("mt_")
        val startIndex = currentMusicTracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        if (currentMusicTracks.isEmpty()) return
        musicPlayer.setQueue(currentMusicTracks, startIndex)
        _uiState.update { it.copy(musicPlayerVisible = true) }
    }

    // ── In-app player controls (driven by the player overlay) ───────────────────
    fun musicPlayPause() = musicPlayer.playPause()
    fun musicNext() = musicPlayer.next()
    fun musicPrev() = musicPlayer.prev()
    fun musicSeekTo(ms: Int) = musicPlayer.seekTo(ms)
    private fun musicSeekBy(deltaMs: Int) = musicPlayer.seekBy(deltaMs)

    // Back / tap-outside on the player only hides the overlay — playback keeps going so the Music
    // root's "Now Playing" item can return to it. Stopping is explicit (player Y → Stop & Close).
    fun closeMusicPlayer() {
        _uiState.update { it.copy(musicPlayerVisible = false) }
    }

    private fun stopAndCloseMusicPlayer() {
        musicPlayer.stop()
        _uiState.update { it.copy(musicPlayerVisible = false) }
    }

    private fun openMusicPlayerOptions() {
        val title = musicPlayer.currentTrack()?.displayTitle ?: "Now Playing"
        _uiState.update {
            it.copy(
                activeContextMenu = XMBContextMenu(
                    title = title,
                    items = listOf(
                        XMBContextMenuItem("music_background", "Play in Background"),
                        XMBContextMenuItem("music_close", "Stop & Close"),
                    ),
                    musicTrackId = MUSIC_PLAYER_MENU_MARKER,
                )
            )
        }
    }

    // Options (△) for the "Now Playing" row in the Music root: toggle playback or stop & close.
    private fun openNowPlayingContextMenu() {
        val playback = _uiState.value.musicPlayback
        if (playback.track == null) return
        _uiState.update {
            it.copy(
                activeContextMenu = XMBContextMenu(
                    title = playback.track.displayTitle,
                    items = listOf(
                        XMBContextMenuItem("music_playpause", if (playback.isPlaying) "Pause" else "Resume"),
                        XMBContextMenuItem("music_close", "Stop and Close"),
                    ),
                    musicTrackId = MUSIC_PLAYER_MENU_MARKER,
                )
            )
        }
    }

    // Keep PFP's own playback going and promote it to a foreground media notification so the user
    // can leave PFP and use other apps; just hide the full-screen player UI.
    private fun musicPlayInBackground() {
        if (musicPlayer.currentTrack() == null) return
        com.playfieldportal.feature.xmb.music.MusicPlaybackService.start(context)
        _uiState.update { it.copy(musicPlayerVisible = false) }
    }

    private fun openMusicTrackContextMenu(item: XMBItem) {
        // Inside a playlist, offer "Remove from this Playlist"; the playlist id rides on the menu.
        val playlist = _uiState.value.musicNav as? MusicNav.Playlist
        val items = buildList {
            add(XMBContextMenuItem("play", "Play"))
            add(XMBContextMenuItem("play_background", "Play in Background"))
            add(XMBContextMenuItem("add_to_playlist", "Add to Playlist"))
            if (playlist != null) {
                add(XMBContextMenuItem("remove_from_playlist", "Remove from this Playlist", isDestructive = true))
            }
            add(XMBContextMenuItem("remove_track", "Remove From Library", isDestructive = true))
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title        = item.title,
                items        = items,
                musicTrackId = item.id.removePrefix("mt_"),
                playlistId   = playlist?.id,
            )
        )}
    }

    // Options menu for a playlist row: open / rename / add tracks / delete.
    private fun openPlaylistRowContextMenu(playlistId: Long, name: String) {
        val items = listOf(
            XMBContextMenuItem("open_playlist", "Open"),
            XMBContextMenuItem("add_tracks", "Add Tracks"),
            XMBContextMenuItem("rename_playlist", "Rename Playlist"),
            XMBContextMenuItem("delete_playlist", "Delete Playlist", isDestructive = true),
        )
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(name, items, playlistId = playlistId)) }
    }

    // Second-level menu: the playlists a track can be added to (checkmarks show membership), plus
    // "Create New Playlist". Stays open while toggling so several can be picked at once.
    private fun openPlaylistPicker(trackId: String, selectIndex: Int = 0) {
        viewModelScope.launch {
            val playlists = musicRepository.observePlaylists().first()
            val memberOf = musicRepository.getPlaylistIdsForTrack(trackId).toSet()
            val items = buildList {
                playlists.forEach { pl ->
                    add(XMBContextMenuItem("pl_${pl.id}", pl.name, checked = pl.id in memberOf))
                }
                add(XMBContextMenuItem("pl_new", "Create New Playlist"))
            }
            _uiState.update { it.copy(
                activeContextMenu = XMBContextMenu(
                    title                 = "Add to Playlist",
                    items                 = items,
                    selectedIndex         = selectIndex.coerceIn(0, items.size - 1),
                    playlistPickerTrackId = trackId,
                )
            )}
        }
    }

    // Opens the right options (△) menu for a Music item. Returns true when [item] is a music row
    // it owns (track / playlist / music-app), so the generic Y handler can stop. The Now Playing
    // row is consumed without a menu (its options live in the full player).
    private fun openMusicContextMenu(item: XMBItem): Boolean {
        if (currentCategory()?.id != BuiltInCategory.MUSIC) return false
        return when {
            item.id == NOW_PLAYING_ITEM_ID -> { openNowPlayingContextMenu(); true }
            item.type == XMBItemType.MUSIC_TRACK -> { openMusicTrackContextMenu(item); true }
            item.type == XMBItemType.PLAYLIST && item.playlistId != null -> {
                openPlaylistRowContextMenu(item.playlistId, item.title); true
            }
            _uiState.value.musicNav == MusicNav.MusicApps && item.packageName != null -> {
                openAppContextMenu(item, categoryIdOverride = MUSIC_APPS_CATEGORY_ID); true
            }
            else -> false
        }
    }

    // ── Playlist name dialog (create / rename) ──────────────────────────────────
    private fun promptCreatePlaylist(forTrackId: String? = null) {
        _uiState.update { it.copy(
            playlistNameDialog = PlaylistNameDialogState(title = "New Playlist", forTrackId = forTrackId)
        )}
    }

    private fun promptRenamePlaylist(playlistId: Long) {
        val name = _uiState.value.currentItems.firstOrNull { it.playlistId == playlistId }?.title.orEmpty()
        _uiState.update { it.copy(
            playlistNameDialog = PlaylistNameDialogState(
                title = "Rename Playlist",
                initialText = name,
                renamePlaylistId = playlistId,
            )
        )}
    }

    fun onConfirmPlaylistName(name: String) {
        val dialog = _uiState.value.playlistNameDialog ?: return
        _uiState.update { it.copy(playlistNameDialog = null) }
        if (name.isBlank()) return
        viewModelScope.launch {
            val renameId = dialog.renamePlaylistId
            if (renameId != null) {
                musicRepository.renamePlaylist(renameId, name)
            } else {
                val id = musicRepository.createPlaylist(name)
                dialog.forTrackId?.let { musicRepository.addTrackToPlaylist(id, it) }
            }
        }
    }

    fun onCancelPlaylistName() {
        _uiState.update { it.copy(playlistNameDialog = null) }
    }

    // ── "Add Tracks" picker (inside a playlist) ─────────────────────────────────
    private fun openMusicTrackPicker(playlistId: Long) {
        viewModelScope.launch {
            val playlist = musicRepository.observePlaylists().first().firstOrNull { it.id == playlistId }
            // Offer tracks not already in the playlist.
            val inPlaylist = musicRepository.observePlaylistTracks(playlistId).first().map { it.id }.toSet()
            val tracks = musicRepository.observeAllTracks().first()
                .filterNot { it.id in inPlaylist }
                .trackSorted(_uiState.value.musicSortMode)
            _uiState.update { it.copy(
                musicTrackPicker = MusicTrackPickerState(
                    playlistId   = playlistId,
                    playlistName = playlist?.name ?: "Playlist",
                    tracks       = tracks,
                )
            )}
        }
    }

    private fun moveMusicTrackPicker(delta: Int) {
        val picker = _uiState.value.musicTrackPicker ?: return
        val maxIndex = picker.tracks.size   // 0 = Confirm row, 1..size = tracks
        val next = (picker.selectedIndex + delta).coerceIn(0, maxIndex)
        _uiState.update { it.copy(musicTrackPicker = picker.copy(selectedIndex = next)) }
    }

    private fun activateMusicTrackPicker() {
        val picker = _uiState.value.musicTrackPicker ?: return
        if (picker.selectedIndex == 0) {
            confirmMusicTrackPicker()
        } else {
            val track = picker.tracks.getOrNull(picker.selectedIndex - 1) ?: return
            val selected = if (track.id in picker.selected) picker.selected - track.id
                           else picker.selected + track.id
            _uiState.update { it.copy(musicTrackPicker = picker.copy(selected = selected)) }
        }
    }

    fun onMusicTrackPickerActivatedAt(index: Int) {
        _uiState.update { it.copy(musicTrackPicker = it.musicTrackPicker?.copy(selectedIndex = index)) }
        activateMusicTrackPicker()
    }

    fun onMusicTrackPickerConfirm() = confirmMusicTrackPicker()

    fun closeMusicTrackPicker() {
        _uiState.update { it.copy(musicTrackPicker = null) }
    }

    private fun confirmMusicTrackPicker() {
        val picker = _uiState.value.musicTrackPicker ?: return
        val playlistId = picker.playlistId
        val trackIds = picker.tracks.map { it.id }.filter { it in picker.selected }
        closeMusicTrackPicker()
        if (trackIds.isEmpty()) return
        viewModelScope.launch {
            trackIds.forEach { musicRepository.addTrackToPlaylist(playlistId, it) }
        }
    }

    // Music folder context-menu actions, dispatched from activateContextMenuItem. Folder management
    // now lives in Settings → Music; this is retained for the scan/enable/remove paths it backs.
    private fun handleMusicFolderAction(folderId: String, itemId: String) {
        when (itemId) {
            "scan_folder" -> scanMusicFolder(folderId)
            "rename_folder" -> _uiState.update { it.copy(activeSettingsScreen = "settings_music") }
            "enable_folder" -> appAction { musicRepository.setFolderEnabled(folderId, true) }
            "disable_folder" -> appAction { musicRepository.setFolderEnabled(folderId, false) }
            "remove_folder" -> appAction { musicRepository.removeFolder(folderId) }
        }
    }

    private fun handleMusicTrackAction(trackId: String, itemId: String, playlistId: Long?) {
        when (itemId) {
            // Play in the in-app full player, queuing from the current on-screen list.
            "play" -> {
                val startIndex = currentMusicTracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                if (currentMusicTracks.isNotEmpty()) {
                    musicPlayer.setQueue(currentMusicTracks, startIndex)
                    _uiState.update { it.copy(musicPlayerVisible = true) }
                }
            }
            // Play in PFP and promote straight to the background media notification (no full
            // player UI), queuing from the current on-screen list.
            "play_background" -> {
                val startIndex = currentMusicTracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                if (currentMusicTracks.isNotEmpty()) {
                    musicPlayer.setQueue(currentMusicTracks, startIndex)
                    com.playfieldportal.feature.xmb.music.MusicPlaybackService.start(context)
                }
            }
            "add_to_playlist" -> openPlaylistPicker(trackId)
            "remove_from_playlist" -> if (playlistId != null) {
                appAction { musicRepository.removeTrackFromPlaylist(playlistId, trackId) }
            }
            "remove_track" -> appAction {
                val track = musicRepository.getTrack(trackId) ?: return@appAction
                removeSingleTrack(track.folderId, trackId)
            }
        }
    }

    // Playlist row actions, dispatched from activateContextMenuItem.
    private fun handlePlaylistRowAction(playlistId: Long, itemId: String) {
        when (itemId) {
            "open_playlist"   -> {
                val name = _uiState.value.currentItems.firstOrNull { it.playlistId == playlistId }?.title.orEmpty()
                openMusicView(MusicNav.Playlist(playlistId, name))
            }
            "add_tracks"      -> openMusicTrackPicker(playlistId)
            "rename_playlist" -> promptRenamePlaylist(playlistId)
            "delete_playlist" -> appAction {
                musicRepository.deletePlaylist(playlistId)
                if ((_uiState.value.musicNav as? MusicNav.Playlist)?.id == playlistId) closeMusicView()
            }
        }
    }

    private fun scanMusicFolder(folderId: String) {
        viewModelScope.launch {
            val folder = musicRepository.getFolder(folderId) ?: return@launch
            val taskId = "music_scan_$folderId"
            addBackgroundTask(BackgroundTaskInfo(taskId, "Scanning ${folder.displayName}", null))
            musicScanner.scan(folder).collect { result ->
                when (result) {
                    is com.playfieldportal.feature.library.scanner.MusicScanResult.Progress -> Unit
                    is com.playfieldportal.feature.library.scanner.MusicScanResult.Complete -> {
                        musicRepository.replaceTracksForFolder(result.folderId, result.tracks, System.currentTimeMillis())
                        completeBackgroundTask(taskId, "${result.tracks.size} tracks")
                    }
                    is com.playfieldportal.feature.library.scanner.MusicScanResult.Error ->
                        failBackgroundTask(taskId, result.message)
                }
            }
        }
    }

    private suspend fun removeSingleTrack(folderId: String, trackId: String) {
        // Read current tracks once, drop the removed one, and replace the folder set.
        val tracks = musicRepository.observeTracksByFolder(folderId).first().filterNot { it.id == trackId }
        musicRepository.replaceTracksForFolder(folderId, tracks, System.currentTimeMillis())
    }

    // ── Sort (X / Square) ─────────────────────────────────────────────────────

    // The sort modes valid for the list currently on screen, or null when it isn't sortable
    // (the Games memory-card root, the Music root, playlist list, and app sections don't sort).
    private fun activeSortContext(): List<XmbSortMode>? {
        val cat = currentCategory() ?: return null
        val s = _uiState.value
        return when {
            cat.id == BuiltInCategory.MUSIC &&
                (s.musicNav == MusicNav.AllMusic || s.musicNav is MusicNav.Playlist) -> MUSIC_SORTS
            cat.id == BuiltInCategory.GAMES &&
                (s.selectedPlatformId != null || s.selectedCollectionId != null) -> GAME_SORTS
            cat.isGamingCategory -> GAME_SORTS
            else -> null
        }
    }

    private fun cycleSort() {
        val cycle = activeSortContext() ?: return
        val isMusic = cycle === MUSIC_SORTS
        val current = if (isMusic) _uiState.value.musicSortMode else _uiState.value.gameSortMode
        val next = cycle[(cycle.indexOf(current).coerceAtLeast(0) + 1) % cycle.size]
        menuSound.play(MenuSound.SYSTEM_BROWSE)
        // Re-sorting moves the cursor back to the top item so the user sees the new ordering from
        // the start, and bumps the scroll token so the list snaps to the top every time (not just
        // the first sort after the cursor moved).
        _uiState.update {
            (if (isMusic) it.copy(musicSortMode = next) else it.copy(gameSortMode = next))
                .copy(selectedItemIndex = 0, scrollToTopToken = it.scrollToTopToken + 1)
        }
        // Music track lists re-sort instantly from the cached raw list — no DB round-trip, so the
        // reorder is always visible immediately. A playlist keeps its trailing "Add Tracks" row.
        if (isMusic) {
            val trailing = if (_uiState.value.musicNav is MusicNav.Playlist) listOf(addTracksItem()) else emptyList()
            val emptyItem = if (_uiState.value.musicNav is MusicNav.Playlist) emptyPlaylistItem() else emptyAllMusicItem()
            setMusicTrackItems(currentMusicTracksRaw, emptyItem, trailing)
            _uiState.update { it.copy(sortLabel = currentSortLabel()) }
            return
        }
        loadItemsForCategory(currentCategory())
    }

    // Status-bar hint for the current list ("Sort: Title"), or null when the list isn't sortable.
    private fun currentSortLabel(): String? {
        val cycle = activeSortContext() ?: return null
        val mode = if (cycle === MUSIC_SORTS) _uiState.value.musicSortMode else _uiState.value.gameSortMode
        return "Sort: ${mode.label}"
    }

    private fun emptyCategoryItem(category: Category): XMBItem {
        val (message, subtitle) = if (category.isGamingCategory) {
            "No games assigned." to "Add games to this category."
        } else {
            val msg = when (category.id) {
                "videos"    -> "No video apps found."
                "network"   -> "No browser apps found."
                "app_store" -> "No app stores found."
                "music"     -> "No music apps found."
                "photos"    -> "No photo apps found."
                else        -> "No apps assigned."
            }
            msg to "Install some apps to get started."
        }
        return XMBItem(
            id       = EMPTY_CATEGORY_ITEM_ID,
            title    = message,
            subtitle = subtitle,
            type     = XMBItemType.EMPTY,
        )
    }

    // Games root: one item per enabled Memory Card (already ordered pinned-first by the DAO).
    private fun memoryCardItems(): List<XMBItem> {
        // Real games only (excludes app-style entries), matching what All Games actually shows.
        val totalGames = _uiState.value.allGamesCount
        val allGamesItem = XMBItem(
            id       = ALL_GAMES_ITEM_ID,
            title    = "All Games",
            subtitle = "Total Games $totalGames",
            type     = XMBItemType.ALL_GAMES,
        )

        // Favorites sits directly under All Games, but only when at least one game is favorited.
        val favoritesCount = _uiState.value.favoritesCount
        val favoritesItem = if (favoritesCount > 0) {
            XMBItem(
                id       = FAVORITES_ITEM_ID,
                title    = "Favorites",
                subtitle = "$favoritesCount ${if (favoritesCount == 1) "Game" else "Games"}",
                type     = XMBItemType.FAVORITES,
            )
        } else null
        val header = listOfNotNull(allGamesItem, favoritesItem)

        // User collections sit just under All Games / Favorites — like Favorites but user-defined.
        // Only collections assigned to this (the Main Game) category appear here; categoryId
        // is the single source of truth for a collection's placement. Pinned collections first.
        val collectionItems = _uiState.value.collections
            .filter { it.categoryId == BuiltInCategory.GAMES }
            .sortedByDescending { it.isPinned }
            .map { collection ->
            val games = "${collection.gameCount} ${if (collection.gameCount == 1) "Game" else "Games"}"
            XMBItem(
                id           = "collection_${collection.id}",
                title        = collection.name,
                subtitle     = if (collection.isPinned) "Pinned · $games" else games,
                collectionId = collection.id,
                type         = XMBItemType.COLLECTION,
            )
        }

        if (enabledCards.isEmpty()) {
            return header + collectionItems + XMBItem(
                id       = NO_CONSOLES_ITEM_ID,
                title    = "No consoles configured",
                subtitle = "Open Library Manager to add a Memory Card",
                type     = XMBItemType.EMPTY,
            )
        }

        return header + collectionItems + enabledCards.map { card ->
            val count = _uiState.value.platformGameCounts[card.platformId] ?: card.gameCount
            XMBItem(
                id          = "card_${card.platformId}",
                title       = card.displayName,
                subtitle    = "$count ${if (count == 1) "Game" else "Games"}",
                platformId  = card.platformId,
                accentColor = platformCache[card.platformId]?.accentColor,
                type        = XMBItemType.MEMORY_CARD,
            )
        }
    }

    private fun emptyAllGamesItem(): XMBItem = XMBItem(
        id       = NO_GAMES_ITEM_ID,
        title    = "No games imported yet",
        subtitle = "Open a Memory Card to scan your library.",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyCollectionItem(): XMBItem = XMBItem(
        id       = EMPTY_COLLECTION_ITEM_ID,
        title    = "This collection is empty",
        subtitle = "Add games from any console with the options (△) menu.",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyFavoritesItem(): XMBItem = XMBItem(
        id       = EMPTY_FAVORITES_ITEM_ID,
        title    = "No favorites yet",
        subtitle = "Mark a game as a favorite from its options (△) menu.",
        type     = XMBItemType.EMPTY,
    )

    // Shown when an opened Memory Card has no games yet. Keeps the platformId so the
    // context menu (Triangle) can still offer "Scan This Console".
    private fun emptyFolderItem(platformId: String): XMBItem {
        // Android-style libraries pick installed apps instead of scanning folders.
        if (platformId == ANDROID_PLATFORM_ID) {
            return XMBItem(
                id         = FIND_GAMES_ITEM_ID,
                title      = "Find Games",
                subtitle   = "Pick installed apps to add to this library",
                platformId = platformId,
            )
        }
        val card = enabledCards.firstOrNull { it.platformId == platformId }
        val subtitle = when {
            card?.romDirectory == null -> "ROM directory not configured"
            else                       -> "Press ▲ to scan this console"
        }
        return XMBItem(
            id         = NO_GAMES_ITEM_ID,
            title      = "No games found in this folder",
            subtitle   = subtitle,
            platformId = platformId,
            type       = XMBItemType.EMPTY,
        )
    }

    private fun List<com.playfieldportal.core.domain.model.Game>.toXmbItems() = map { g ->
        XMBItem(
            id           = g.id.toString(),
            title        = g.displayTitle,
            artworkUri   = g.artworkUri,
            heroUri      = g.heroUri,
            iconUri      = g.iconUri,
            subtitle     = g.releaseYear?.toString(),
            gameId       = g.id,
            platformId   = g.platformId,
            accentColor  = platformCache[g.platformId]?.accentColor,
            isFavorite   = g.isFavorite,
            isAndroidApp = g.packageName != null,
            packageName  = g.packageName,
            shortcutId   = g.shortcutId,
            launchIntentUri = g.launchIntentUri,
        )
    }

    private fun tintWaveForCategory(category: Category?) {
        val accentColor = category?.accentColor
            ?.let { androidx.compose.ui.graphics.Color(it) }
            ?: baseThemeColors.waveColor
        _uiState.update { it.copy(themeColors = it.themeColors.copy(waveColor = accentColor)) }
    }

    // ── Gamepad ───────────────────────────────────────────────────────────────

    private fun observeGamepadMappings() {
        viewModelScope.launch {
            mappingRepository.mappings.collect { mappings ->
                gamepadInputHandler.currentMappings = mappings
            }
        }
    }

    private fun collectGamepadActions() {
        viewModelScope.launch {
            gamepadInputHandler.actions.collect { action ->
                onUserInteraction()
                dispatchGamepadAction(action)
            }
        }
    }

    private fun dispatchGamepadAction(action: GamepadAction) {
        val state = _uiState.value

        // ── Installed-app picker captures ALL input when open ──────────────────
        if (state.appPicker != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveAppPicker(-1)
                GamepadAction.NAVIGATE_DOWN -> moveAppPicker(+1)
                GamepadAction.SELECT        -> activateAppPicker()
                // Start button confirms the picker (Add apps / Done), regardless of row.
                GamepadAction.HOME          -> confirmAppPicker()
                GamepadAction.BACK,
                GamepadAction.LONG_PRESS    -> closeAppPicker()
                else -> Unit
            }
            return
        }

        // ── "Add Tracks" music picker captures ALL input when open ─────────────
        if (state.musicTrackPicker != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveMusicTrackPicker(-1)
                GamepadAction.NAVIGATE_DOWN -> moveMusicTrackPicker(+1)
                GamepadAction.SELECT        -> activateMusicTrackPicker()
                GamepadAction.HOME          -> confirmMusicTrackPicker()
                GamepadAction.BACK,
                GamepadAction.LONG_PRESS    -> closeMusicTrackPicker()
                else -> Unit
            }
            return
        }

        // ── Game picker captures ALL input when open ───────────────────────────
        if (state.gamePickerCategoryId != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP,
                GamepadAction.NAVIGATE_DOWN,
                GamepadAction.SELECT,
                GamepadAction.HOME,
                GamepadAction.BACK,
                GamepadAction.BUTTON_Y,
                GamepadAction.LONG_PRESS -> _uiState.update { it.copy(pendingGamePickerAction = action) }
                else -> Unit
            }
            return
        }

        // ── Context menu captures ALL input when open ──────────────────────────
        if (state.activeContextMenu != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> shiftContextMenu(-1)
                GamepadAction.NAVIGATE_DOWN -> shiftContextMenu(+1)
                GamepadAction.SELECT        -> activateContextMenuItem()
                GamepadAction.BACK,
                GamepadAction.LONG_PRESS,
                GamepadAction.BUTTON_Y      -> closeContextMenu()
                else -> Unit
            }
            return
        }

        // ── In-app music player captures ALL input while open ──────────────────
        // (Below the context-menu branch so the player's own Y options menu wins when shown.)
        if (state.musicPlayerVisible) {
            when (action) {
                GamepadAction.SELECT         -> musicPlayPause()
                GamepadAction.NAVIGATE_LEFT  -> musicPrev()
                GamepadAction.NAVIGATE_RIGHT -> musicNext()
                GamepadAction.NAVIGATE_UP    -> musicSeekBy(10_000)
                GamepadAction.NAVIGATE_DOWN  -> musicSeekBy(-10_000)
                GamepadAction.BUTTON_Y,
                GamepadAction.LONG_PRESS     -> openMusicPlayerOptions()
                GamepadAction.BACK           -> closeMusicPlayer()
                else -> Unit
            }
            return
        }

        // ── Color-scheme picker captures ALL input when open (sits above Settings) ──
        if (state.colorSchemePicker != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveColorSchemePicker(-1)
                GamepadAction.NAVIGATE_DOWN -> moveColorSchemePicker(+1)
                GamepadAction.SELECT        -> confirmColorSchemePicker()
                GamepadAction.BACK,
                GamepadAction.LONG_PRESS    -> cancelColorSchemePicker()
                else -> Unit
            }
            return
        }

        // ── Modal text dialogs capture ALL input — text entry needs a keyboard, so
        //    only BACK is meaningful (cancel). The XMB behind must never move. ──────
        if (state.renameAppTarget != null) {
            if (action == GamepadAction.BACK) onCancelAppRename()
            return
        }
        if (state.collectionNameDialog != null) {
            if (action == GamepadAction.BACK) onCancelCollectionName()
            return
        }
        if (state.playlistNameDialog != null) {
            if (action == GamepadAction.BACK) onCancelPlaylistName()
            return
        }
        // Read-only info dialog (e.g. file location) — A or B closes it.
        if (state.infoDialog != null) {
            if (action == GamepadAction.BACK || action == GamepadAction.SELECT) dismissInfoDialog()
            return
        }

        // ── Boot sequence overlay swallows input until it finishes/auto-completes ──
        if (state.showBootSequence) return

        // ── Overlays (innermost wins) ──────────────────────────────────────────
        when {
            state.activeGameId != null -> {
                // Forward everything (incl. BACK) so the Details page can close its own inner
                // overlays first and only then pop back to the XMB (via onCloseGameDetail).
                _uiState.update { it.copy(pendingGameDetailAction = action) }
                return
            }
            state.activeAppId != null -> {
                // Forward everything so the App Detail page can close its own inner overlays
                // (artwork picker) before popping back to the XMB (via onCloseAppDetail).
                _uiState.update { it.copy(pendingAppDetailAction = action) }
                return
            }
            state.activeSettingsScreen != null -> {
                Timber.d("Gamepad → settings(${state.activeSettingsScreen}): $action")
                // BACK is forwarded into the settings layer (not handled here) so the active
                // screen can do one-level-up navigation through its own back handler — exactly
                // like the on-screen Back button. The screen calls onCloseSettingsScreen() only
                // when it's already at its top level, which returns to the XMB.
                when (action) {
                    GamepadAction.BACK,
                    GamepadAction.NAVIGATE_UP,
                    GamepadAction.NAVIGATE_DOWN,
                    GamepadAction.SELECT -> _uiState.update { it.copy(pendingSettingsAction = action) }
                    else -> Unit
                }
                return
            }
            state.activeAppDrawerFilter != null -> {
                if (action == GamepadAction.BACK) onCloseAppDrawer()
                else _uiState.update { it.copy(pendingDrawerAction = action) }
                return
            }
        }

        // Defensive net: the main XMB navigation below must NEVER run while any overlay,
        // menu, or modal dialog is on screen. Each case above returns for its own handling;
        // this guards against a future overlay being added without its own branch.
        if (state.hasBlockingOverlay) return

        when (action) {
            GamepadAction.NAVIGATE_UP -> {
                val next = (state.selectedItemIndex - 1).coerceAtLeast(0)
                if (next != state.selectedItemIndex) { _uiState.update { it.copy(selectedItemIndex = next) }; menuSound.play(MenuSound.SCROLL) }
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.NAVIGATE_DOWN -> {
                val max  = (state.currentItems.size - 1).coerceAtLeast(0)
                val next = (state.selectedItemIndex + 1).coerceAtMost(max)
                if (next != state.selectedItemIndex) { _uiState.update { it.copy(selectedItemIndex = next) }; menuSound.play(MenuSound.SCROLL) }
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.NAVIGATE_LEFT -> {
                val next = (state.selectedCategoryIndex - 1).coerceAtLeast(0)
                if (next != state.selectedCategoryIndex) onCategorySelected(next)
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.NAVIGATE_RIGHT -> {
                val max  = (state.categories.size - 1).coerceAtLeast(0)
                val next = (state.selectedCategoryIndex + 1).coerceAtMost(max)
                if (next != state.selectedCategoryIndex) onCategorySelected(next)
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.SELECT     -> onItemSelected(state.selectedItemIndex)
            GamepadAction.BACK       -> {
                menuSound.play(MenuSound.BACK)
                when {
                    state.musicNav != MusicNav.Root -> closeMusicView()
                    state.selectedPlatformId != null || state.selectedCollectionId != null -> closePlatformFolder()
                    else -> onOpenAppDrawer()
                }
            }
            GamepadAction.LONG_PRESS,
            GamepadAction.BUTTON_Y -> {
                // Y / Triangle — open context menu for whichever item type has focus
                val item = state.currentItems.getOrNull(state.selectedItemIndex)
                when {
                    item != null && openMusicContextMenu(item) -> Unit
                    item?.gameId != null -> openGameContextMenu(item)
                    item?.collectionId != null && item.type == XMBItemType.COLLECTION -> openCollectionRowContextMenu(item.collectionId)
                    item?.platformId != null -> openPlatformContextMenu(item.platformId)
                    item?.packageName != null -> openAppContextMenu(item)
                }
            }
            // Start button no longer restarts / shows the boot screen.
            GamepadAction.HOME          -> Unit
            // X / Square — cycle the sort order of the current list (PSP-style). The task tray was
            // removed, so its old action is repurposed to sort too (covers stale saved mappings
            // where X is still bound to OPEN_TASK_TRAY).
            GamepadAction.OPEN_TASK_TRAY,
            GamepadAction.CHANGE_SORT -> cycleSort()
            GamepadAction.BUTTON_Y,
            GamepadAction.PREV_CATEGORY,
            GamepadAction.NEXT_CATEGORY -> Unit
        }
    }

    fun onKeyDown(action: GamepadAction) {
        if (action.isDirectional()) gamepadInputHandler.startRepeating(action, viewModelScope)
    }

    fun onKeyUp(action: GamepadAction) {
        if (action.isDirectional()) gamepadInputHandler.cancelRepeat()
    }

    private fun GamepadAction.isDirectional() = this in setOf(
        GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN,
        GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT,
    )

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun openPlatformContextMenu(platformId: String) {
        val card = enabledCards.firstOrNull { it.platformId == platformId } ?: return
        val isAndroid = platformId == ANDROID_PLATFORM_ID
        val items = buildList {
            // Android libraries pick installed apps; consoles scan ROM folders.
            if (isAndroid) add(XMBContextMenuItem("find_games", "Find Games"))
            else           add(XMBContextMenuItem("scan_roms",  "Scan This Console"))
            add(XMBContextMenuItem("refresh_metadata", "Refresh Metadata"))
            add(XMBContextMenuItem("refresh_artwork",  "Refresh Artwork"))
            if (card.pinned) add(XMBContextMenuItem("unpin", "Unpin"))
            else             add(XMBContextMenuItem("pin",   "Pin To Top"))
            add(XMBContextMenuItem("library_manager",  "Open in Library Manager"))
            add(XMBContextMenuItem("hide",             "Hide From Games"))
            add(XMBContextMenuItem("remove",           "Remove Memory Card", isDestructive = true))
        }

        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title      = card.displayName,
                items      = items,
                platformId = platformId,
            )
        )}
    }

    private fun openGameContextMenu(item: XMBItem) {
        val inCollection = _uiState.value.selectedCollectionId != null
        val currentCat = currentCategory()
        val inGamingCategory = currentCat?.isGamingCategory == true

        val items = buildList {
            add(XMBContextMenuItem("launch", "Launch Game"))
            if (item.packageName != null) add(XMBContextMenuItem("edit_app", "Edit App Details"))
            add(XMBContextMenuItem(
                id    = if (item.isFavorite) "unfavorite" else "favorite",
                label = if (item.isFavorite) "Remove from Favorites" else "Add to Favorites",
            ))
            add(XMBContextMenuItem("add_to_collection", "Add to Collection"))
            // Only offer removal when viewing the game from inside a collection.
            if (inCollection) add(XMBContextMenuItem("remove_from_collection", "Remove from Collection"))
            add(XMBContextMenuItem("manage_collections", "Manage Collections"))

            // Gaming category options. Games in the Main Game category can only be COPIED into
            // another category (never moved out or removed); custom gaming categories allow
            // move / remove / pin. Move/Add only appear when a real destination exists — a
            // custom gaming category other than the current one (Main Game is never a target).
            if (inGamingCategory && currentCat != null) {
                val hasOtherCustomCategory = _uiState.value.categories.any {
                    it.isGamingCategory && it.id != BuiltInCategory.GAMES && it.id != currentCat.id
                }
                if (currentCat.id == BuiltInCategory.GAMES) {
                    if (hasOtherCustomCategory) add(XMBContextMenuItem("add_category", "Add to Category"))
                } else {
                    if (hasOtherCustomCategory) add(XMBContextMenuItem("move_category", "Move to Category"))
                    add(XMBContextMenuItem("remove_category", "Remove from Category"))
                    val pinned = item.subtitle == "Pinned"
                    add(XMBContextMenuItem(
                        if (pinned) "unpin_category" else "pin_category",
                        if (pinned) "Unpin" else "Pin",
                    ))
                }
            }

            add(XMBContextMenuItem("refresh_metadata", "Refresh Metadata"))
            add(XMBContextMenuItem("refresh_artwork",  "Refresh Artwork"))
            add(XMBContextMenuItem("file_location",    "View File Location"))
        }

        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title       = item.title,
                items       = items,
                gameId      = item.gameId,
                packageName = item.packageName,
                shortcutId  = item.shortcutId,
                launchIntentUri = item.launchIntentUri,
                categoryContext = if (inGamingCategory) currentCat?.id else null,
            )
        )}
    }

    // Second-level menu: the collections a game can be added to (checkmarks show current
    // membership), plus "Create New Collection". Opened from the game options menu. The menu
    // stays open while toggling so the user can add to several collections at once.
    private fun openCollectionPicker(gameId: Long, selectIndex: Int = 0) {
        viewModelScope.launch {
            val collections = collectionRepository.getAll()
            val memberOf = collectionRepository.getCollectionIdsForGame(gameId).toSet()
            val items = buildList {
                collections.forEach { c ->
                    add(XMBContextMenuItem(
                        id      = "col_${c.id}",
                        label   = c.name,
                        checked = c.id in memberOf,
                    ))
                }
                add(XMBContextMenuItem("col_new", "Create New Collection"))
            }
            _uiState.update { it.copy(
                activeContextMenu = XMBContextMenu(
                    title            = "Add to Collection",
                    items            = items,
                    selectedIndex    = selectIndex.coerceIn(0, items.size - 1),
                    gameId           = gameId,
                    collectionGameId = gameId,
                )
            )}
        }
    }

    private fun openAppContextMenu(item: XMBItem, categoryIdOverride: String? = null) {
        val pkg = item.packageName ?: return
        val categoryId = categoryIdOverride ?: currentCategory()?.id
        val items = buildList {
            add(XMBContextMenuItem("launch",   "Launch"))
            add(XMBContextMenuItem("edit_app", "Edit App Details"))
            // Shortcut actions — these materialize a launch shortcut (a games-table row that
            // references the app by package) so it can live in Favorites / Collections without
            // duplicating the app's metadata. Works for every Android app, GameHub included.
            add(XMBContextMenuItem("favorite",          "Add to Favorites"))
            add(XMBContextMenuItem("add_to_collection", "Add to Collection"))
            // Pull the app's own per-game launcher shortcuts (GameHub PCs, etc.) into PFP.
            add(XMBContextMenuItem("import_shortcuts",  "Import Game Shortcuts"))
            add(XMBContextMenuItem("move",     "Move To Category"))
            add(XMBContextMenuItem("add",      "Add To Category"))
            if (categoryId != null) add(XMBContextMenuItem("remove", "Remove From Category"))
            if (categoryId != null) add(XMBContextMenuItem("pin",    "Pin To Category"))
            add(XMBContextMenuItem("hide",     "Hide App"))
            add(XMBContextMenuItem("rename",   "Rename Shortcut"))
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title           = item.title,
                items           = items,
                gameId          = item.gameId,
                packageName     = pkg,
                categoryContext = categoryId,
            )
        )}
    }

    // Options menu for a collection row (long-press / △), in the Games root or any gaming category.
    private fun openCollectionRowContextMenu(collectionId: Long) {
        val collection = _uiState.value.collections.firstOrNull { it.id == collectionId } ?: return
        // Move is only meaningful when there's another gaming category to move into.
        val hasOtherCategory = _uiState.value.categories.any { it.isGamingCategory && it.id != collection.categoryId }
        val items = buildList {
            add(XMBContextMenuItem("open_collection",   "Open"))
            add(XMBContextMenuItem("rename_collection", "Rename Collection"))
            if (hasOtherCategory) add(XMBContextMenuItem("move_collection_category", "Move to Category"))
            add(XMBContextMenuItem(
                if (collection.isPinned) "unpin_collection" else "pin_collection",
                if (collection.isPinned) "Unpin" else "Pin",
            ))
            add(XMBContextMenuItem("manage_collections", "Manage Collections"))
            add(XMBContextMenuItem("delete_collection",  "Delete Collection", isDestructive = true))
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title           = collection.name,
                items           = items,
                collectionRowId = collectionId,
            )
        )}
    }

    // Second-level menu: pick a destination gaming category for moving a collection. Collections
    // belong to exactly one category, so this reassigns categoryId (the source of truth).
    private fun openCollectionCategoryPicker(collectionId: Long, fromCategoryId: String) {
        val items = _uiState.value.categories
            .filter { it.isGamingCategory && it.id != fromCategoryId }
            .map { cat -> XMBContextMenuItem("movecol_${cat.id}", cat.name) }
        if (items.isEmpty()) return
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title           = "Move Collection To",
                items           = items,
                collectionRowId = collectionId,
            )
        )}
    }

    // Second-level menu: pick a destination category for Move / Add.
    private fun openCategoryPicker(pkg: String, fromCategory: String?, action: String) {
        val items = _uiState.value.categories.map { cat ->
            XMBContextMenuItem("pick_${cat.id}", cat.name)
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title            = if (action == "move") "Move To…" else "Add To…",
                items            = items,
                packageName      = pkg,
                categoryContext  = fromCategory,
                pendingAppAction = action,
            )
        )}
    }

    private fun shiftContextMenu(delta: Int) {
        val menu = _uiState.value.activeContextMenu ?: return
        val next = (menu.selectedIndex + delta).coerceIn(0, menu.items.size - 1)
        _uiState.update { it.copy(activeContextMenu = menu.copy(selectedIndex = next)) }
    }

    private fun activateContextMenuItem() {
        val menu   = _uiState.value.activeContextMenu ?: return
        val itemId = menu.items.getOrNull(menu.selectedIndex)?.id ?: return

        // ── Gaming category picker submenu — move or add game to another category ──
        if (itemId.startsWith("cat_") && menu.gameId != null && menu.categoryContext != null && menu.pendingAppAction != null) {
            val gameId = menu.gameId
            val fromCategory = menu.categoryContext
            val toCategory = itemId.removePrefix("cat_")
            val action = menu.pendingAppAction
            closeContextMenu()
            // Reload AFTER the write completes — reloading synchronously would read stale
            // junction rows and leave the moved game visible in the source category.
            appAction {
                when (action) {
                    "move" -> gameCategoryRepository.moveGameToCategory(gameId, fromCategory, toCategory)
                    "add"  -> gameCategoryRepository.addGameToCategory(gameId, toCategory)
                }
                if (currentCategory()?.id == fromCategory) {
                    loadItemsForCategory(currentCategory())
                }
            }
            return
        }

        // ── Collection picker submenu — handled before closing so toggles stay in place ──
        if (menu.collectionGameId != null) {
            val gameId = menu.collectionGameId
            val keepIndex = menu.selectedIndex
            when {
                itemId == "col_new" -> {
                    closeContextMenu()
                    promptCreateCollection(forGameId = gameId)
                }
                itemId.startsWith("col_") -> {
                    val collectionId = itemId.removePrefix("col_").toLongOrNull() ?: return
                    viewModelScope.launch {
                        collectionRepository.toggleGame(collectionId, gameId)
                        // Re-open so the checkmark reflects the new membership.
                        openCollectionPicker(gameId, keepIndex)
                    }
                }
            }
            return
        }

        // ── Playlist picker submenu — handled before closing so toggles stay in place ──
        if (menu.playlistPickerTrackId != null) {
            val trackId = menu.playlistPickerTrackId
            val keepIndex = menu.selectedIndex
            when {
                itemId == "pl_new" -> {
                    closeContextMenu()
                    promptCreatePlaylist(forTrackId = trackId)
                }
                itemId.startsWith("pl_") -> {
                    val playlistId = itemId.removePrefix("pl_").toLongOrNull() ?: return
                    viewModelScope.launch {
                        musicRepository.toggleTrackInPlaylist(playlistId, trackId)
                        // Re-open so the checkmark reflects the new membership.
                        openPlaylistPicker(trackId, keepIndex)
                    }
                }
            }
            return
        }

        closeContextMenu()

        // ── Playlist row options menu ──────────────────────────────────────────
        if (menu.playlistId != null && menu.musicTrackId == null) {
            handlePlaylistRowAction(menu.playlistId, itemId)
            return
        }

        // ── Collection row options menu (and the Move-to-Category submenu) ──────
        if (menu.collectionRowId != null) {
            val collectionId = menu.collectionRowId
            when {
                // Destination chosen in the Move submenu — reassign the collection's category.
                itemId.startsWith("movecol_") -> {
                    val toCategory = itemId.removePrefix("movecol_")
                    appAction { collectionRepository.setCategory(collectionId, toCategory) }
                }
                itemId == "open_collection"   -> openCollectionFolder(collectionId)
                itemId == "rename_collection" -> promptRenameCollection(collectionId)
                itemId == "move_collection_category" -> {
                    val from = _uiState.value.collections.firstOrNull { it.id == collectionId }?.categoryId
                        ?: BuiltInCategory.GAMES
                    openCollectionCategoryPicker(collectionId, from)
                }
                itemId == "pin_collection"   -> appAction { collectionRepository.setPinned(collectionId, true) }
                itemId == "unpin_collection" -> appAction { collectionRepository.setPinned(collectionId, false) }
                itemId == "manage_collections" -> _uiState.update { it.copy(activeSettingsScreen = "settings_collections") }
                itemId == "delete_collection"  -> appAction {
                    collectionRepository.delete(collectionId)
                    if (_uiState.value.selectedCollectionId == collectionId) closePlatformFolder()
                }
            }
            return
        }

        when {
            menu.musicTrackId == MUSIC_PLAYER_MENU_MARKER -> when (itemId) {
                "music_background" -> musicPlayInBackground()
                "music_playpause"  -> musicPlayPause()
                "music_close"      -> stopAndCloseMusicPlayer()
            }
            menu.musicTrackId != null -> handleMusicTrackAction(menu.musicTrackId, itemId, menu.playlistId)
            menu.musicFolderId != null -> handleMusicFolderAction(menu.musicFolderId, itemId)
            menu.platformId != null -> when (itemId) {
                "find_games"       -> openAppPicker(AppPickerTarget.AndroidGames(menu.platformId), "Find Games")
                "scan_roms"        -> scanCard(menu.platformId)
                "refresh_artwork"  -> refreshPlatformArtwork(menu.platformId)
                "refresh_metadata" -> refreshPlatformArtwork(menu.platformId) // same path for now
                "pin"              -> setCardPinned(menu.platformId, true)
                "unpin"            -> setCardPinned(menu.platformId, false)
                "library_manager"  -> _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
                "hide"             -> hideCard(menu.platformId)
                "remove"           -> removeCard(menu.platformId)
            }
            menu.gameId != null -> when (itemId) {
                "launch"                 -> when {
                    menu.launchIntentUri != null -> launchStoredIntent(menu.launchIntentUri, menu.title)
                    menu.shortcutId != null -> launchHarvestedShortcut(menu.packageName, menu.shortcutId)
                    else -> _uiState.update { it.copy(activeGameId = menu.gameId) }
                }
                "edit_app"               -> openAppDetail(menu.gameId, menu.packageName ?: return)
                "favorite"               -> toggleGameFavorite(menu.gameId, true)
                "unfavorite"             -> toggleGameFavorite(menu.gameId, false)
                "add_to_collection"      -> openCollectionPicker(menu.gameId)
                "remove_from_collection" -> {
                    val gid = menu.gameId   // local val so it smart-casts inside the lambda
                    _uiState.value.selectedCollectionId?.let { cid ->
                        appAction { collectionRepository.removeGame(cid, gid) }
                    }
                }
                "manage_collections"     -> _uiState.update { it.copy(activeSettingsScreen = "settings_collections") }
                "add_category"           -> menu.categoryContext?.let { openGameCategoryPicker(menu.gameId, it, "add") }
                "move_category"          -> menu.categoryContext?.let { openGameCategoryPicker(menu.gameId, it, "move") }
                "remove_category"        -> menu.categoryContext?.let { cat ->
                    val gid = menu.gameId
                    appAction {
                        gameCategoryRepository.removeGameFromCategory(gid, cat)
                        loadItemsForCategory(currentCategory())
                    }
                }
                "pin_category"           -> menu.categoryContext?.let { cat ->
                    val gid = menu.gameId
                    appAction {
                        gameCategoryRepository.pinGameInCategory(gid, cat, true)
                        loadItemsForCategory(currentCategory())
                    }
                }
                "unpin_category"         -> menu.categoryContext?.let { cat ->
                    val gid = menu.gameId
                    appAction {
                        gameCategoryRepository.pinGameInCategory(gid, cat, false)
                        loadItemsForCategory(currentCategory())
                    }
                }
                "refresh_artwork"        -> refreshGameArtwork(menu.gameId)
                "refresh_metadata"       -> refreshGameArtwork(menu.gameId)
                "file_location"          -> showGameFileLocation(menu.gameId)
            }
            menu.packageName != null -> {
                val pkg = menu.packageName
                if (itemId.startsWith("pick_")) {
                    val targetCategory = itemId.removePrefix("pick_")
                    when (menu.pendingAppAction) {
                        "move" -> appAction { appCategoryRepository.moveToCategory(pkg, targetCategory) }
                        "add"  -> appAction { appCategoryRepository.addToCategory(pkg, targetCategory) }
                    }
                } else when (itemId) {
                    "launch"    -> appCategoryRepository.launch(pkg)
                    "edit_app"  -> openAppDetail(menu.gameId, pkg)
                    "favorite"          -> addAppToFavorites(pkg, menu.title)
                    "add_to_collection" -> addAppToCollection(pkg, menu.title)
                    "import_shortcuts"  -> importGameShortcuts(pkg, menu.title)
                    "move"      -> openCategoryPicker(pkg, menu.categoryContext, "move")
                    "add"       -> openCategoryPicker(pkg, menu.categoryContext, "add")
                    "remove"    -> menu.categoryContext?.let { cat -> appAction { appCategoryRepository.removeFromCategory(pkg, cat) } }
                    "pin"       -> menu.categoryContext?.let { cat -> appAction { appCategoryRepository.pinToCategory(pkg, cat) } }
                    "hide"      -> appAction { appCategoryRepository.setHidden(pkg, true) }
                    "rename"    -> _uiState.update { it.copy(renameAppTarget = pkg, renameAppCurrent = menu.title) }
                }
            }
        }
    }

    private fun appAction(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ── App rename dialog ─────────────────────────────────────────────────────

    fun onConfirmAppRename(newLabel: String) {
        val pkg = _uiState.value.renameAppTarget ?: return
        viewModelScope.launch {
            // Blank reverts to the real app label.
            appCategoryRepository.rename(pkg, newLabel.ifBlank { null })
            _uiState.update { it.copy(renameAppTarget = null, renameAppCurrent = null) }
        }
    }

    fun onCancelAppRename() {
        _uiState.update { it.copy(renameAppTarget = null, renameAppCurrent = null) }
    }

    // ── Create-collection dialog ───────────────────────────────────────────────

    private fun promptCreateCollection(forGameId: Long? = null) {
        _uiState.update { it.copy(
            collectionNameDialog = CollectionNameDialogState(title = "New Collection", forGameId = forGameId)
        )}
    }

    private fun promptRenameCollection(collectionId: Long) {
        val name = _uiState.value.collections.firstOrNull { it.id == collectionId }?.name.orEmpty()
        _uiState.update { it.copy(
            collectionNameDialog = CollectionNameDialogState(
                title = "Rename Collection",
                initialText = name,
                renameCollectionId = collectionId,
            )
        )}
    }

    fun onConfirmCollectionName(name: String) {
        val dialog = _uiState.value.collectionNameDialog ?: return
        _uiState.update { it.copy(collectionNameDialog = null) }
        if (name.isBlank()) return
        viewModelScope.launch {
            val renameId = dialog.renameCollectionId
            if (renameId != null) {
                collectionRepository.rename(renameId, name)
            } else {
                val id = collectionRepository.create(name)
                dialog.forGameId?.let { collectionRepository.addGame(id, it) }
            }
            // Reflect the new/renamed collection in the XMB right away — a collection created
            // here lands in the Main Game category (the default), so refresh the current
            // gaming category instead of waiting on the reactive collection stream.
            if (currentCategory()?.isGamingCategory == true) {
                loadItemsForCategory(currentCategory())
            }
        }
    }

    fun onCancelCollectionName() {
        _uiState.update { it.copy(collectionNameDialog = null) }
    }

    private fun showGameFileLocation(gameId: Long) {
        viewModelScope.launch {
            val game = gameRepository.getById(gameId) ?: return@launch
            val location = game.romPath
                ?: game.packageName?.let { "Package: $it" }
                ?: "No file location on record"
            _uiState.update {
                it.copy(infoDialog = InfoDialogState(title = game.displayTitle, message = location))
            }
        }
    }

    fun dismissInfoDialog() = _uiState.update { it.copy(infoDialog = null) }

    // Called from touch interaction on the overlay
    fun onContextMenuItemActivatedAt(index: Int) {
        _uiState.update { it.copy(activeContextMenu = it.activeContextMenu?.copy(selectedIndex = index)) }
        activateContextMenuItem()
    }

    fun closeContextMenu() {
        _uiState.update { it.copy(activeContextMenu = null) }
    }

    // ── Installed-app picker ────────────────────────────────────────────────────

    private fun openAppPicker(target: AppPickerTarget, title: String) {
        viewModelScope.launch {
            val entries = appCategoryRepository.allInstalledApps()
                .map { AppPickerEntry(it.packageName, it.label) }   // already sorted by label
            _uiState.update {
                it.copy(appPicker = AppPickerState(title = title, target = target, apps = entries))
            }
        }
    }

    private fun moveAppPicker(delta: Int) {
        val picker = _uiState.value.appPicker ?: return
        val maxIndex = picker.apps.size   // 0 = Confirm row, 1..size = apps
        val next = (picker.selectedIndex + delta).coerceIn(0, maxIndex)
        _uiState.update { it.copy(appPicker = picker.copy(selectedIndex = next)) }
    }

    private fun activateAppPicker() {
        val picker = _uiState.value.appPicker ?: return
        if (picker.selectedIndex == 0) {
            confirmAppPicker()
        } else {
            val app = picker.apps.getOrNull(picker.selectedIndex - 1) ?: return
            val selected = if (app.packageName in picker.selected) {
                picker.selected - app.packageName
            } else {
                picker.selected + app.packageName
            }
            _uiState.update { it.copy(appPicker = picker.copy(selected = selected)) }
        }
    }

    // Touch entry point: toggling an app or pressing Confirm in the overlay.
    fun onAppPickerActivatedAt(index: Int) {
        _uiState.update { it.copy(appPicker = it.appPicker?.copy(selectedIndex = index)) }
        activateAppPicker()
    }

    fun onAppPickerConfirm() = confirmAppPicker()

    fun closeAppPicker() {
        _uiState.update { it.copy(appPicker = null) }
    }

    private fun confirmAppPicker() {
        val picker = _uiState.value.appPicker ?: return
        val packages = picker.selected
        val target = picker.target
        closeAppPicker()
        if (packages.isEmpty()) return

        viewModelScope.launch {
            when (target) {
                is AppPickerTarget.AndroidGames -> importAndroidGames(target.platformId, packages)
                is AppPickerTarget.CategoryShortcuts -> {
                    // Music Apps live under a synthetic category that isn't seeded in the categories
                    // table; category_items has a FK to categories, so the row must exist first or
                    // the insert throws (crash). Seed it (hidden) before adding.
                    if (target.categoryId == MUSIC_APPS_CATEGORY_ID) ensureMusicAppsCategory()
                    packages.forEach { pkg -> appCategoryRepository.addToCategory(pkg, target.categoryId) }
                }
            }
        }
    }

    // Creates the hidden pseudo-category that backs Music Apps the first time it's used, so the
    // category_items foreign key is satisfied. Hidden (is_visible = false) so it never shows in the
    // XMB bar or category pickers. Only inserted when absent — never overwritten (a REPLACE would
    // cascade-delete its items).
    private suspend fun ensureMusicAppsCategory() {
        val exists = categoryRepository.observeAll().first().any { it.id == MUSIC_APPS_CATEGORY_ID }
        if (exists) return
        categoryRepository.upsert(
            Category(
                id        = MUSIC_APPS_CATEGORY_ID,
                name      = "Music Apps",
                iconKey   = "ic_music",
                type      = CategoryType.BUILT_IN,
                position  = 900,
                isVisible = false,
            )
        )
    }

    // ── Game picker (for gaming categories) ────────────────────────────────────

    fun openGamePicker(categoryId: String) {
        _uiState.update { it.copy(gamePickerCategoryId = categoryId) }
    }

    fun closeGamePicker() {
        _uiState.update { it.copy(gamePickerCategoryId = null, pendingGamePickerAction = null) }
    }

    fun consumeGamePickerAction() {
        _uiState.update { it.copy(pendingGamePickerAction = null) }
    }

    fun confirmGamePicker(selectedGameIds: Set<Long>, selectedCollectionIds: Set<Long>) {
        val categoryId = _uiState.value.gamePickerCategoryId ?: return
        closeGamePicker()

        viewModelScope.launch {
            selectedGameIds.forEach { gameId ->
                gameCategoryRepository.addGameToCategory(gameId, categoryId)
            }
            // Collections are placed by categoryId (one category each), not the junction table.
            selectedCollectionIds.forEach { collectionId ->
                collectionRepository.setCategory(collectionId, categoryId)
            }
            // Refresh the current category display
            val category = _uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex)
            if (category?.id == categoryId) {
                loadItemsForCategory(category)
            }
        }
    }

    // Shows a menu of other gaming categories for moving/adding a game. Main Game is never a
    // destination for an individual game — every game already lives there via its platform, so
    // moving a game "to Main Game" is redundant (and would only leave a stray junction row).
    private fun openGameCategoryPicker(gameId: Long, fromCategoryId: String, action: String) {
        val items = buildList {
            _uiState.value.categories
                .filter { it.isGamingCategory && it.id != fromCategoryId && it.id != BuiltInCategory.GAMES }
                .forEach { cat ->
                    add(XMBContextMenuItem("cat_${cat.id}", cat.name))
                }
        }

        if (items.isEmpty()) return

        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title       = if (action == "move") "Move Game To" else "Add Game To",
                items       = items,
                gameId      = gameId,
                categoryContext = fromCategoryId,
                pendingAppAction = action,  // reuse this field to store the action type
            )
        )}
    }

    // Adds the selected apps as launchable Game entries under an Android Memory Card. Stores
    // the package name (launch reference) and label; the icon is loaded by package at render
    // time. Skips apps already present so re-running the picker is safe.
    private suspend fun importAndroidGames(platformId: String, packages: Set<String>) {
        val labels = appCategoryRepository.allInstalledApps().associateBy { it.packageName }
        val existing = runCatching {
            gameRepository.observeByPlatform(platformId).first().mapNotNull { it.packageName }.toSet()
        }.getOrDefault(emptySet())

        packages.filterNot { it in existing }.forEach { pkg ->
            gameRepository.upsert(
                com.playfieldportal.core.domain.model.Game(
                    title         = labels[pkg]?.label ?: pkg,
                    platformId    = platformId,
                    packageName   = pkg,
                    isManualEntry = true,
                    // Package-based entry — classified as an app so it stays out of All Games.
                    contentType   = com.playfieldportal.core.domain.model.GameContentType.ANDROID_APP,
                )
            )
        }
        memoryCardRepository.recountGames(platformId)
        Timber.i("Android library import: ${packages.size} app(s) selected for $platformId")
    }

    // ── Platform actions ──────────────────────────────────────────────────────

    fun onPlatformLongPress(categoryIndex: Int) {
        _uiState.value.currentItems.getOrNull(categoryIndex)?.platformId?.let(::openPlatformContextMenu)
    }

    // Scans only this Memory Card's directory for only its supported extensions, assigning
    // every match to its platform. A PSP card can never pull in another console's ROMs.
    private fun scanCard(platformId: String) {
        viewModelScope.launch {
            val card = memoryCardRepository.getById(platformId) ?: return@launch
            val taskId = "scan_$platformId"
            val dir = card.romDirectory
            if (dir.isNullOrBlank()) {
                addBackgroundTask(BackgroundTaskInfo(id = taskId, label = card.displayName, progress = null))
                failBackgroundTask(taskId, "ROM directory not configured")
                return@launch
            }

            val existingPaths = runCatching {
                gameRepository.observeByPlatform(platformId).first().mapNotNull { it.romPath }.toSet()
            }.getOrDefault(emptySet())

            addBackgroundTask(BackgroundTaskInfo(id = taskId, label = "Scanning ${card.displayName}…", progress = null))

            romScanner.scanDirectory(
                directory        = dir,
                extensions       = card.supportedExtensions,
                platformId       = platformId,
                recursive        = card.scanRecursively,
                existingRomPaths = existingPaths,
            ).collect { result ->
                when (result) {
                    is ScanResult.Progress -> updateBackgroundTask(
                        taskId, result.progress.filesScanned.toFloat() /
                                (result.progress.totalEstimated.coerceAtLeast(1))
                    )
                    is ScanResult.Complete -> {
                        result.newGames.forEach { game -> gameRepository.upsert(game) }
                        memoryCardRepository.recordScan(platformId, System.currentTimeMillis())
                        completeBackgroundTask(taskId,
                            if (result.newGames.isEmpty()) "No new ROMs found"
                            else "${result.newGames.size} new ROM(s) added"
                        )
                        Timber.i("Card scan complete: ${result.newGames.size} new games for $platformId")
                    }
                    is ScanResult.Error -> {
                        failBackgroundTask(taskId, result.message)
                        Timber.e("Card scan error for $platformId: ${result.message}")
                    }
                }
            }
        }
    }

    private fun refreshPlatformArtwork(platformId: String) {
        // Route to artwork settings screen for now; bulk per-platform refresh is a future feature
        _uiState.update { it.copy(activeSettingsScreen = "settings_artwork") }
    }

    private fun setCardPinned(platformId: String, pinned: Boolean) {
        viewModelScope.launch { memoryCardRepository.setPinned(platformId, pinned) }
    }

    private fun hideCard(platformId: String) {
        viewModelScope.launch {
            memoryCardRepository.setEnabled(platformId, false)
            if (_uiState.value.selectedPlatformId == platformId) closePlatformFolder()
        }
    }

    private fun removeCard(platformId: String) {
        viewModelScope.launch {
            memoryCardRepository.remove(platformId)
            if (_uiState.value.selectedPlatformId == platformId) closePlatformFolder()
        }
    }

    // ── Game actions ──────────────────────────────────────────────────────────

    private fun toggleGameFavorite(gameId: Long, isFavorite: Boolean) {
        if (isFavorite) menuSound.play(MenuSound.FAVORITE)
        viewModelScope.launch {
            gameRepository.setFavorite(gameId, isFavorite)
        }
    }

    private fun refreshGameArtwork(gameId: Long) {
        viewModelScope.launch {
            val game   = gameRepository.getById(gameId) ?: return@launch
            val taskId = "artwork_$gameId"
            addBackgroundTask(BackgroundTaskInfo(id = taskId, label = "Fetching artwork: ${game.displayTitle}", progress = null))
            val result = artworkRepository.fetchArtworkForGame(gameId, game.displayTitle)
            if (result.success) {
                completeBackgroundTask(taskId, "Artwork updated")
            } else {
                failBackgroundTask(taskId, result.errorMessage ?: "Artwork fetch failed")
            }
        }
    }

    // ── Background task management ────────────────────────────────────────────

    // Background work is reported through the Android notification bar. We keep a
    // tiny in-memory label map so progress/complete updates can re-title the same
    // notification without the caller having to re-supply the label each time.
    private val taskLabels = mutableMapOf<String, String>()

    private fun addBackgroundTask(task: BackgroundTaskInfo) {
        taskLabels[task.id] = task.label
        taskNotifier.running(task.id, task.label, task.progress)
    }

    private fun updateBackgroundTask(id: String, progress: Float) {
        val label = taskLabels[id] ?: return
        taskNotifier.running(id, label, progress.coerceIn(0f, 1f))
    }

    private fun completeBackgroundTask(id: String, message: String? = null) {
        val label = taskLabels.remove(id) ?: "Done"
        taskNotifier.complete(id, label, message)
    }

    private fun failBackgroundTask(id: String, message: String) {
        val label = taskLabels.remove(id) ?: "Task failed"
        taskNotifier.failed(id, label, message)
    }

    // ── Category / platform selection ─────────────────────────────────────────

    fun onCategorySelected(index: Int) {
        if (index != _uiState.value.selectedCategoryIndex) menuSound.play(MenuSound.SYSTEM_BROWSE)
        val category = _uiState.value.categories.getOrNull(index)
        _uiState.update { it.copy(selectedCategoryIndex = index, selectedItemIndex = 0, selectedPlatformId = null, selectedCollectionId = null, musicNav = MusicNav.Root) }
        tintWaveForCategory(category)
        loadItemsForCategory(category)
    }

    /** Touch: step the category selection by [direction] (-1 / +1) from the current one — the swipe
     *  equivalent of D-pad ◀ ▶. */
    fun stepCategory(direction: Int) {
        val s = _uiState.value
        if (s.hasBlockingOverlay) return
        val next = (s.selectedCategoryIndex + direction)
            .coerceIn(0, (s.categories.size - 1).coerceAtLeast(0))
        if (next != s.selectedCategoryIndex) onCategorySelected(next)
    }

    /** Touch: the left-edge-swipe Back — exit an open folder, or open the app drawer at the root
     *  (mirrors the gamepad BACK behaviour on the home screen). No-op while an overlay is up. */
    fun onHomeBack() {
        val s = _uiState.value
        if (s.hasBlockingOverlay) return
        menuSound.play(MenuSound.BACK)
        when {
            s.musicNav != MusicNav.Root -> closeMusicView()
            s.selectedPlatformId != null || s.selectedCollectionId != null -> closePlatformFolder()
            else -> onOpenAppDrawer()
        }
    }

    // ── Item selection ────────────────────────────────────────────────────────

    fun onItemSelected(index: Int) {
        _uiState.update { it.copy(selectedItemIndex = index) }
        val category = _uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex)
        val item     = _uiState.value.currentItems.getOrNull(index)

        // Music rows are handled together (static items, the All Music card, playlists, tracks,
        // and the various add/setup rows), each owning its sound and returning early.
        if (category?.id == BuiltInCategory.MUSIC && item != null && handleMusicSelection(item)) return

        // Sound: launch for items that boot something immediately; select for opening a folder,
        // detail, picker, or settings; silent for non-selectable placeholder rows.
        val silentRow = item?.id in setOf(NO_GAMES_ITEM_ID, EMPTY_COLLECTION_ITEM_ID, EMPTY_CATEGORY_ITEM_ID)
        val launches  = item?.launchIntentUri != null ||
            (item?.shortcutId != null && item.packageName != null) ||
            item?.packageName != null
        if (!silentRow) menuSound.play(if (launches) MenuSound.LAUNCH else MenuSound.SELECT)

        // Empty-state rows
        when (item?.id) {
            NO_CONSOLES_ITEM_ID -> {
                _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
                return
            }
            ALL_GAMES_ITEM_ID -> {
                openAllGamesFolder()
                return
            }
            FAVORITES_ITEM_ID -> {
                openFavoritesFolder()
                return
            }
            ADD_APPS_ITEM_ID -> {
                category?.id?.let { openAppPicker(AppPickerTarget.CategoryShortcuts(it), "Add Apps") }
                return
            }
            ADD_GAMES_ITEM_ID -> {
                category?.id?.let { openGamePicker(it) }
                return
            }
            FIND_GAMES_ITEM_ID -> {
                (item.platformId ?: _uiState.value.selectedPlatformId)?.let {
                    openAppPicker(AppPickerTarget.AndroidGames(it), "Find Games")
                }
                return
            }
            NO_GAMES_ITEM_ID,
            EMPTY_COLLECTION_ITEM_ID,
            EMPTY_CATEGORY_ITEM_ID -> return   // not selectable
        }

        // User collection folder — open it.
        if (item?.collectionId != null && item.type == XMBItemType.COLLECTION) {
            openCollectionFolder(item.collectionId)
            return
        }

        // Legacy captured shortcut (BannerHub / old Winlator) — launch its stored intent.
        if (item?.launchIntentUri != null) {
            launchStoredIntent(item.launchIntentUri, item.title)
            return
        }

        // Harvested launcher shortcut — A/Cross launches the host app's specific shortcut.
        if (item?.shortcutId != null && item.packageName != null) {
            launchHarvestedShortcut(item.packageName, item.shortcutId)
            return
        }

        // Android app — A/Cross launches it
        if (item?.packageName != null) {
            appCategoryRepository.launch(item.packageName)
            return
        }

        if (item?.gameId != null) {
            _uiState.update { it.copy(activeGameId = item.gameId) }
            return
        }
        if (item?.platformId != null) {
            openPlatformFolder(item.platformId)
            return
        }

        when (item?.id) {
            SETUP_ITEM_ID -> {
                Timber.d("Opening settings screen: settings_library (via setup prompt)")
                _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
            }
            else -> when (category?.id) {
                BuiltInCategory.SETTINGS -> {
                    if (item?.id != null) {
                        Timber.d("Opening settings screen: ${item.id}")
                        _uiState.update { it.copy(activeSettingsScreen = item.id) }
                    }
                }
                BuiltInCategory.ANDROID -> {
                    if (item?.id?.startsWith("drawer_") == true) {
                        val filter = item.id.removePrefix("drawer_").uppercase()
                        _uiState.update { it.copy(activeAppDrawerFilter = filter) }
                    }
                }
            }
        }
    }

    fun onItemLongPress(index: Int) {
        val item = _uiState.value.currentItems.getOrNull(index)
        when {
            item != null && openMusicContextMenu(item) -> Unit
            item?.gameId != null -> openGameContextMenu(item)
            item?.collectionId != null && item.type == XMBItemType.COLLECTION -> openCollectionRowContextMenu(item.collectionId)
            item?.platformId != null -> openPlatformContextMenu(item.platformId)
            item?.packageName != null -> openAppContextMenu(item)
        }
    }

    private fun openPlatformFolder(platformId: String) {
        val gamesCategoryIndex = _uiState.value.categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
        _uiState.update {
            it.copy(
                selectedCategoryIndex = gamesCategoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = platformId,
                selectedItemIndex = 0,
            )
        }
        loadItemsForCategory(_uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex))
    }

    private fun openAllGamesFolder() {
        val gamesCategoryIndex = _uiState.value.categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
        _uiState.update {
            it.copy(
                selectedCategoryIndex = gamesCategoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = ALL_GAMES_PLATFORM_ID,
                selectedCollectionId = null,
                selectedItemIndex = 0,
            )
        }
        loadItemsForCategory(_uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex))
    }

    private fun openFavoritesFolder() {
        val gamesCategoryIndex = _uiState.value.categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
        _uiState.update {
            it.copy(
                selectedCategoryIndex = gamesCategoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = FAVORITES_PLATFORM_ID,
                selectedCollectionId = null,
                selectedItemIndex = 0,
            )
        }
        loadItemsForCategory(_uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex))
    }

    private fun openCollectionFolder(collectionId: Long) {
        // Open the collection within the category it belongs to — a collection in a custom
        // gaming category must stay in that category, not jump back to Main Game.
        val targetCategoryId = _uiState.value.collections
            .firstOrNull { it.id == collectionId }?.categoryId ?: BuiltInCategory.GAMES
        val categoryIndex = _uiState.value.categories.indexOfFirst { it.id == targetCategoryId }
        _uiState.update {
            it.copy(
                selectedCategoryIndex = categoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = null,
                selectedCollectionId = collectionId,
                selectedItemIndex = 0,
            )
        }
        loadItemsForCategory(_uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex))
    }

    // Closes any open Games-root drill-down (platform card, All Games, or a collection).
    private fun closePlatformFolder() {
        _uiState.update { it.copy(selectedPlatformId = null, selectedCollectionId = null, selectedItemIndex = 0) }
        loadItemsForCategory(_uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex))
    }

    // ── Game detail overlay ───────────────────────────────────────────────────

    fun onCloseGameDetail() {
        _uiState.update { it.copy(activeGameId = null, pendingGameDetailAction = null) }
    }

    fun consumeGameDetailAction() {
        _uiState.update { it.copy(pendingGameDetailAction = null) }
    }

    // ── App detail overlay ────────────────────────────────────────────────────

    private fun openAppDetail(knownGameId: Long?, packageName: String) {
        if (knownGameId != null) {
            _uiState.update { it.copy(activeAppId = knownGameId) }
            return
        }
        viewModelScope.launch {
            val id = ensureAppShortcut(packageName)
            _uiState.update { it.copy(activeAppId = id) }
        }
    }

    // ── Android-app launch shortcuts ───────────────────────────────────────────
    //
    // Favorites and Collections are keyed on a games-table row id. Apps placed in XMB
    // categories are package-based (no game row), so they can't join either until they have a
    // "shortcut" row. A shortcut is a GameEntity that REFERENCES the app by packageName (the only
    // duplicated field is the display label) and is typed ANDROID_APP so it never aggregates into
    // All Games. It is deduped by package — one shortcut per app, reused by Favorites, every
    // Collection, and the App Detail screen. This is what makes GameHub (and any Android app)
    // shortcutable; GameHub is otherwise treated identically to any other app.
    private suspend fun ensureAppShortcut(packageName: String): Long {
        gameRepository.getAppEntry(packageName)?.let { return it.id }
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrDefault(packageName)
        return gameRepository.upsert(
            Game(
                title         = label,
                platformId    = ANDROID_PLATFORM_ID,
                packageName   = packageName,
                isManualEntry = true,
                contentType   = GameContentType.ANDROID_APP,
            )
        )
    }

    private fun addAppToFavorites(packageName: String, label: String) {
        menuSound.play(MenuSound.FAVORITE)
        viewModelScope.launch {
            runCatching {
                val id = ensureAppShortcut(packageName)
                gameRepository.setFavorite(id, true)
            }.onSuccess {
                Timber.i("App shortcut favorited: $packageName")
                taskNotifier.complete("shortcut_fav_$packageName", label, "Added to Favorites")
            }.onFailure { e ->
                Timber.e(e, "Failed to add app to Favorites: $packageName")
                taskNotifier.failed("shortcut_fav_$packageName", label, "Couldn't add to Favorites: ${e.message}")
            }
        }
    }

    private fun addAppToCollection(packageName: String, label: String) {
        viewModelScope.launch {
            runCatching { ensureAppShortcut(packageName) }
                .onSuccess { id -> openCollectionPicker(id) }
                .onFailure { e ->
                    Timber.e(e, "Failed to prepare app shortcut for collection: $packageName")
                    taskNotifier.failed("shortcut_col_$packageName", label, "Couldn't create shortcut: ${e.message}")
                }
        }
    }

    // ── Launcher-shortcut harvesting (GameHub PCs, Lime3DS games, …) ────────────
    //
    // Pulls the host app's published launcher shortcuts and imports each as a launchable PFP
    // entry (a games-table row carrying the host package + shortcut id), grouped into a
    // collection named after the app. Requires PFP to be the active default launcher.
    private fun importGameShortcuts(hostPackage: String, hostLabel: String) {
        val taskId = "harvest_$hostPackage"
        viewModelScope.launch {
            when (val result = launcherShortcutRepository.harvest(hostPackage)) {
                is ShortcutHarvestResult.NotDefaultLauncher ->
                    taskNotifier.failed(taskId, hostLabel,
                        "Set Play Field Portal as your default Home app, then try again.")
                is ShortcutHarvestResult.Error ->
                    taskNotifier.failed(taskId, hostLabel, result.message)
                is ShortcutHarvestResult.Success -> {
                    val shortcuts = result.shortcuts
                    if (shortcuts.isEmpty()) {
                        taskNotifier.complete(taskId, hostLabel, "No game shortcuts published by this app.")
                        return@launch
                    }
                    runCatching {
                        val collectionId = collectionRepository.getAll().firstOrNull { it.name == hostLabel }?.id
                            ?: collectionRepository.create(hostLabel)
                        shortcuts.forEach { sc ->
                            val gameId = gameRepository.getLauncherShortcut(sc.hostPackage, sc.shortcutId)?.id
                                ?: gameRepository.upsert(
                                    Game(
                                        title         = sc.label,
                                        platformId    = ANDROID_PLATFORM_ID,
                                        packageName   = sc.hostPackage,
                                        isManualEntry = true,
                                        contentType   = GameContentType.ANDROID_APP,
                                        shortcutId    = sc.shortcutId,
                                    )
                                )
                            collectionRepository.addGame(collectionId, gameId)
                        }
                        shortcuts.size
                    }.onSuccess { count ->
                        Timber.i("Imported $count launcher shortcut(s) from $hostPackage")
                        taskNotifier.complete(taskId, hostLabel,
                            "Imported $count shortcut${if (count == 1) "" else "s"} into collection \"$hostLabel\"")
                    }.onFailure { e ->
                        Timber.e(e, "Failed to import shortcuts from $hostPackage")
                        taskNotifier.failed(taskId, hostLabel, "Import failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun launchHarvestedShortcut(hostPackage: String?, shortcutId: String?) {
        if (hostPackage == null || shortcutId == null) return
        launcherShortcutRepository.launch(hostPackage, shortcutId).onFailure { e ->
            Timber.e(e, "Failed to launch shortcut $hostPackage/$shortcutId")
            taskNotifier.failed("launch_sc_$shortcutId", hostPackage, "Couldn't launch: ${e.message}")
        }
    }

    // Launches a captured legacy INSTALL_SHORTCUT entry by parsing its stored intent.
    private fun launchStoredIntent(intentUri: String, label: String) {
        runCatching {
            val launch = android.content.Intent.parseUri(intentUri, android.content.Intent.URI_INTENT_SCHEME)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }.onFailure { e ->
            Timber.e(e, "Failed to launch captured shortcut: $label")
            taskNotifier.failed("launch_intent_${label.hashCode()}", label, "Couldn't launch: ${e.message}")
        }
    }

    fun onCloseAppDetail() {
        _uiState.update { it.copy(activeAppId = null, pendingAppDetailAction = null) }
    }

    fun consumeAppDetailAction() {
        _uiState.update { it.copy(pendingAppDetailAction = null) }
    }

    // ── Settings overlay ──────────────────────────────────────────────────────

    fun onCloseSettingsScreen() {
        Timber.d("Settings closed")
        _uiState.update { it.copy(activeSettingsScreen = null, pendingSettingsAction = null) }
    }

    fun consumeSettingsAction() {
        _uiState.update { it.copy(pendingSettingsAction = null) }
    }

    // ── App drawer overlay ────────────────────────────────────────────────────

    fun onOpenAppDrawer() {
        _uiState.update { it.copy(activeAppDrawerFilter = "ALL") }
    }

    fun onCloseAppDrawer() {
        _uiState.update { it.copy(activeAppDrawerFilter = null, pendingDrawerAction = null) }
    }

    fun consumeDrawerAction() {
        _uiState.update { it.copy(pendingDrawerAction = null) }
    }

    // ── Color-scheme picker ─────────────────────────────────────────────────────
    //
    // The picker previews schemes live: moving the cursor writes the highlighted
    // scheme to DataStore so observeColorScheme repaints the wave/background. BACK
    // restores whatever scheme was active when the picker opened; SELECT commits.

    private var colorSchemeOriginal: XmbColorScheme? = null

    fun openColorSchemePicker() {
        viewModelScope.launch {
            val prefs = context.pfpDataStore.data.first()
            val current = runCatching {
                XmbColorScheme.valueOf(prefs[KEY_COLOR_SCHEME] ?: XmbColorScheme.CLASSIC_BLUE.name)
            }.getOrDefault(XmbColorScheme.CLASSIC_BLUE)
            colorSchemeOriginal = current

            val month = java.time.LocalDate.now().monthValue
            val options = XmbColorScheme.values().map { scheme ->
                ColorSchemeOption(
                    scheme   = scheme,
                    label    = scheme.displayLabel(),
                    sublabel = if (scheme == XmbColorScheme.ORIGINAL) "Changes with the month" else "Fixed color preset",
                    swatch   = scheme.resolve(month).waveColor,
                )
            }
            val index = options.indexOfFirst { it.scheme == current }.coerceAtLeast(0)
            _uiState.update { it.copy(colorSchemePicker = ColorSchemePickerState(options, index)) }
        }
    }

    private fun moveColorSchemePicker(delta: Int) {
        val picker = _uiState.value.colorSchemePicker ?: return
        val next = (picker.selectedIndex + delta).coerceIn(0, picker.options.lastIndex)
        if (next == picker.selectedIndex) { gamepadInputHandler.cancelRepeat(); return }
        _uiState.update { it.copy(colorSchemePicker = picker.copy(selectedIndex = next)) }
        previewColorScheme(picker.options[next].scheme)
    }

    /** Touch handler — highlight (and live-preview) the tapped row. */
    fun onColorSchemeHighlightedAt(index: Int) {
        val picker = _uiState.value.colorSchemePicker ?: return
        if (index !in picker.options.indices || index == picker.selectedIndex) return
        _uiState.update { it.copy(colorSchemePicker = picker.copy(selectedIndex = index)) }
        previewColorScheme(picker.options[index].scheme)
    }

    private fun previewColorScheme(scheme: XmbColorScheme) {
        viewModelScope.launch { context.pfpDataStore.edit { it[KEY_COLOR_SCHEME] = scheme.name } }
    }

    fun confirmColorSchemePicker() {
        val picker = _uiState.value.colorSchemePicker ?: return
        val chosen = picker.options.getOrNull(picker.selectedIndex)?.scheme
        viewModelScope.launch {
            if (chosen != null) context.pfpDataStore.edit { it[KEY_COLOR_SCHEME] = chosen.name }
            colorSchemeOriginal = null
            _uiState.update { it.copy(colorSchemePicker = null) }
        }
    }

    fun cancelColorSchemePicker() {
        val original = colorSchemeOriginal
        viewModelScope.launch {
            if (original != null) context.pfpDataStore.edit { it[KEY_COLOR_SCHEME] = original.name }
            colorSchemeOriginal = null
            _uiState.update { it.copy(colorSchemePicker = null) }
        }
    }

    // ── Boot sequence ─────────────────────────────────────────────────────────

    fun onBootSequenceComplete() {
        _uiState.update { it.copy(showBootSequence = false) }
    }

    // ── User interaction ──────────────────────────────────────────────────────

    /**
     * Hook invoked on every gamepad action. The background mode and wave style are now
     * explicit, user-controlled settings, so interaction no longer mutates the wave.
     */
    fun onUserInteraction() = Unit

    // ── Library setup state ───────────────────────────────────────────────────

    private fun observeLibrarySetupState() {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                val complete = prefs[KEY_SETUP_COMPLETE] ?: false
                _uiState.update { it.copy(librarySetupComplete = complete) }
            }
        }
    }

    // ── Icon style ────────────────────────────────────────────────────────────

    private fun observeIconStyle() {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                val styleName = prefs[KEY_ICON_STYLE] ?: GameIconStyle.PSP_RECTANGLE.name
                val style = runCatching { GameIconStyle.valueOf(styleName) }
                    .getOrDefault(GameIconStyle.PSP_RECTANGLE)
                _uiState.update { it.copy(iconStyle = style) }
            }
        }
    }

    // ── Wave style ──────────────────────────────────────────────────────────────

    private fun observeBackgroundSettings() {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                val style = runCatching {
                    WaveStyle.valueOf(prefs[KEY_WAVE_STYLE] ?: WaveStyle.ANIMATED.name)
                }.getOrDefault(WaveStyle.ANIMATED)
                _uiState.update { it.copy(waveStyle = style) }
            }
        }
    }

    // ── Custom wallpaper ──────────────────────────────────────────────────────

    private fun observeWallpaper() {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                val path = prefs[KEY_CUSTOM_WALLPAPER]
                // Validate the file still exists before surfacing it to the UI.
                val validPath = if (path != null && java.io.File(path).exists()) path else null
                _uiState.update { it.copy(customWallpaperPath = validPath) }
            }
        }
    }

    // ── Static data ───────────────────────────────────────────────────────────

    companion object {
        private val KEY_ICON_STYLE        = stringPreferencesKey("display_icon_style")
        private val KEY_WAVE_STYLE        = stringPreferencesKey("display_wave_style")
        private val KEY_COLOR_SCHEME      = stringPreferencesKey("display_color_scheme")
        private val KEY_SETUP_COMPLETE    = booleanPreferencesKey("library_setup_complete")
        private val KEY_CUSTOM_WALLPAPER  = stringPreferencesKey("display_custom_wallpaper")
        private val KEY_MENU_SOUND_ENABLED = booleanPreferencesKey("sound_menu_enabled")
        private const val SETUP_ITEM_ID = "library_setup"
        private const val NO_CONSOLES_ITEM_ID = "no_consoles"
        private const val NO_GAMES_ITEM_ID    = "no_games"
        private const val EMPTY_COLLECTION_ITEM_ID = "empty_collection"
        private const val EMPTY_FAVORITES_ITEM_ID = "empty_favorites"
        private const val EMPTY_CATEGORY_ITEM_ID = "empty_category"
        private const val ALL_GAMES_ITEM_ID = "all_games"
        private const val ALL_GAMES_PLATFORM_ID = "__all_games__"
        private const val FAVORITES_ITEM_ID = "favorites_folder"
        private const val FAVORITES_PLATFORM_ID = "__favorites__"
        private const val ADD_APPS_ITEM_ID = "add_apps"
        private const val ADD_GAMES_ITEM_ID = "add_games"
        private const val FIND_GAMES_ITEM_ID = "find_games"
        // Platform id whose library is built from installed apps (picker) instead of ROM scans.
        private const val ANDROID_PLATFORM_ID = "android"

        // Music category synthetic rows / drill ids.
        private const val ADD_MUSIC_FOLDER_ITEM_ID = "add_music_folder"
        private const val ALL_MUSIC_ITEM_ID = "all_music"
        private const val NOW_PLAYING_ITEM_ID = "now_playing"
        private const val PLAYLISTS_ITEM_ID = "playlists"
        private const val MUSIC_APPS_ITEM_ID = "music_apps_item"
        private const val ADD_MUSIC_APPS_ITEM_ID = "add_music_apps"
        private const val CREATE_PLAYLIST_ITEM_ID = "create_playlist"
        private const val ADD_TRACKS_ITEM_ID = "add_tracks"
        private const val EMPTY_PLAYLIST_ITEM_ID = "empty_playlist"
        // Pseudo-category id the user's chosen Music Apps are stored under (kept apart from the
        // built-in "music" category so the two never mix).
        private const val MUSIC_APPS_CATEGORY_ID = "music_apps"
        // Generic memory-card art for the "Music" (All Music) item — the physical-media default
        // PNG, loaded from assets via Coil (same convention as PhysicalMediaIcon).
        private const val MEMORY_CARD_ASSET_URI =
            "file:///android_asset/systems/physical-media/_default.png"
        // Sentinel in XMBContextMenu.musicTrackId marking the in-app player's own options menu.
        private const val MUSIC_PLAYER_MENU_MARKER = "__music_player__"

        // Used only if the categories table hasn't been seeded yet (first frame on first run).
        // The main XMB always presents these seven categories in this order.
        val FALLBACK_CATEGORIES = listOf(
            Category(id = BuiltInCategory.SETTINGS, name = "Settings",  iconKey = "ic_settings", type = CategoryType.BUILT_IN, position = 0),
            Category(id = "photos",                 name = "Photo",     iconKey = "ic_photos",   type = CategoryType.BUILT_IN, position = 1),
            Category(id = "music",                  name = "Music",     iconKey = "ic_music",    type = CategoryType.BUILT_IN, position = 2),
            Category(id = "videos",                 name = "Video",     iconKey = "ic_videos",   type = CategoryType.BUILT_IN, position = 3),
            Category(id = BuiltInCategory.GAMES,    name = "Game",      iconKey = "ic_games",    type = CategoryType.BUILT_IN, position = 4, isGamingCategory = true),
            Category(id = "network",                name = "Network",   iconKey = "ic_network",  type = CategoryType.BUILT_IN, position = 5),
            Category(id = "app_store",              name = "App Store", iconKey = "ic_appstore", type = CategoryType.BUILT_IN, position = 6),
        )

        private val ANDROID_ITEMS = listOf(
            XMBItem(id = "drawer_all",       title = "All Apps",      subtitle = "Browse every installed app"),
            XMBItem(id = "drawer_games",     title = "Games",         subtitle = "Apps categorized as games"),
            XMBItem(id = "drawer_emulators", title = "Emulators",     subtitle = "RetroArch, PPSSPP, Dolphin and more"),
            XMBItem(id = "drawer_recent",    title = "Recently Used", subtitle = "Apps you've used lately"),
        )

        private val SETTINGS_ITEMS = listOf(
            XMBItem(id = "settings_library",    title = "Library",          subtitle = "ROM sources & scanning"),
            XMBItem(id = "settings_music",      title = "Music",            subtitle = "Music folders & default player"),
            XMBItem(id = "settings_categories", title = "Categories",       subtitle = "Manage XMB categories"),
            XMBItem(id = "settings_collections", title = "Collections",     subtitle = "Create & manage game collections"),
            XMBItem(id = "settings_artwork",    title = "Artwork",          subtitle = "Scraping sources & cache"),
            XMBItem(id = "settings_emulators",  title = "Emulators",        subtitle = "Launch profiles"),
            XMBItem(id = "settings_themes",     title = "Themes",           subtitle = "XMB appearance & color scheme"),
            XMBItem(id = "settings_display",    title = "Display",          subtitle = "Wave, wallpaper, boot & icons"),
            XMBItem(id = "settings_controller", title = "Controller",       subtitle = "Button mapping"),
            XMBItem(id = "settings_backup",     title = "Backup & Restore", subtitle = "Export & import"),
            XMBItem(id = "settings_logs",       title = "Logs",             subtitle = "Debug & error log viewer"),
            XMBItem(id = "settings_about",      title = "About",            subtitle = "Play Field Portal"),
            XMBItem(id = "settings_credits",    title = "Credits",          subtitle = "Artwork & attributions"),
        )
    }

    private fun canonicalXmbCategories(categories: List<Category>): List<Category> {
        val byId = categories.associateBy { it.id }
        val builtInIds = FALLBACK_CATEGORIES.map { it.id }.toSet()

        val builtIns = FALLBACK_CATEGORIES.map { fallback ->
            val stored = byId[fallback.id]
            fallback.copy(
                accentColor      = stored?.accentColor,
                customIconUri    = stored?.customIconUri,
                filterRules      = stored?.filterRules,
                // Preserve the system-defined gaming flag from the DB (reconciled each launch);
                // without this the rebuilt Main Game category loses isGamingCategory, which hides
                // "Move to Category" for collections and suppresses live refresh.
                isGamingCategory = stored?.isGamingCategory ?: fallback.isGamingCategory,
            )
        }

        val customCategories = categories.filter { it.id !in builtInIds }

        return (builtIns + customCategories).sortedBy { it.position }
    }

    private fun defaultXmbCategoryIndex(categories: List<Category>): Int =
        categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
            .takeIf { it >= 0 }
            ?: 0
}
