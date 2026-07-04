package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
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
import com.playfieldportal.core.domain.model.HideLocationType
import com.playfieldportal.core.domain.model.HiddenPlacement
import com.playfieldportal.core.data.database.entity.HiddenPlacementEntity
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.feature.appbar.AppCategoryRepository
import com.playfieldportal.feature.appbar.CategorizedApp
import com.playfieldportal.feature.appbar.LauncherShortcutRepository
import com.playfieldportal.feature.appbar.ShortcutHarvestResult
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.discord.DiscordFriend
import com.playfieldportal.core.domain.discord.DiscordPresence
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
import androidx.compose.ui.graphics.toArgb
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

// Set the wave color AND re-derive the light PSP background gradient from it, so the background
// always matches whatever hue the wave is (theme default or per-category accent tint).
private fun PFPColors.withWaveTint(wave: androidx.compose.ui.graphics.Color): PFPColors {
    val argb = wave.toArgb().toLong() and 0xFFFFFFFFL
    val anchors = com.playfieldportal.core.domain.model.lightBackgroundAnchors(argb)
    return copy(
        waveColor        = wave,
        backgroundTop    = androidx.compose.ui.graphics.Color(anchors.first),
        backgroundBottom = androidx.compose.ui.graphics.Color(anchors.second),
    )
}

// ── Context menu types ────────────────────────────────────────────────────────

data class XMBContextMenu(
    val title: String,
    val items: List<XMBContextMenuItem>,
    val selectedIndex: Int = 0,
    // Identifies the source of the menu (platform card, game, or app)
    val platformId: String? = null,
    // Set on the "All Games" card's menu (which is not a real Memory Card).
    val isAllGames: Boolean = false,
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
    // Set on a video playlist row's options menu.
    val videoPlaylistId: Long? = null,
    // Set on a video file's options menu.
    val videoFileId: String? = null,
    // Set on a video library card's options menu.
    val videoLibraryId: String? = null,
    // Set on the "Add to Playlist" submenu opened for a video (marks that submenu).
    val videoPlaylistPickerVideoId: String? = null,
    // Set on a photo row's options menu.
    val photoFileId: String? = null,
    // Set on a photo library (Album) card's options menu.
    val photoLibraryId: String? = null,
    // Set on the Discord account row's options menu (Reconnect).
    val socialAccountMenu: Boolean = false,
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

// Which Video sub-screen is open. Mirrors [MusicNav]: the Video root shows the static items
// (All Videos / Video Libraries / Android Video Apps + add rows); drilling swaps the item list
// without leaving the Video category.
sealed interface VideoNav {
    data object Root : VideoNav
    data object AllVideos : VideoNav
    // "Collections" groups the three curated views (Recently Watched / Favorites / Playlists) under
    // one root entry so the Video root stays uncluttered. Those three live one level below it.
    data object Collections : VideoNav
    data object RecentlyWatched : VideoNav
    data object Favorites : VideoNav
    data object Playlists : VideoNav
    data class Playlist(val id: Long, val name: String) : VideoNav
    data object Libraries : VideoNav
    data class Library(val id: String, val name: String) : VideoNav
    data object VideoApps : VideoNav
}

// The three views that live under "Collections" — used so BACK from them returns to Collections
// rather than the Video root. A Playlist backs to Playlists first (handled separately).
private val VideoNav.isVideoCollectionChild: Boolean
    get() = this == VideoNav.RecentlyWatched || this == VideoNav.Favorites || this == VideoNav.Playlists

// Which Photo sub-screen is open. Mirrors [VideoNav], kept deliberately minimal (PSP memory-card
// style): the Photo root shows All Photos / Camera / Add Photo Library / the user's Albums;
// drilling swaps the item list without leaving the Photo category.
sealed interface PhotoNav {
    data object Root : PhotoNav
    data object AllPhotos : PhotoNav
    data object Albums : PhotoNav
    data object PhotoApps : PhotoNav
    data class Library(val id: String, val name: String) : PhotoNav
}

// Discord Social sub-navigation: Root (the account) → Account (its hub) → Friends (the list) /
// DiscordSettings (account options: Sign Out, and more later).
sealed interface SocialNav {
    data object Root : SocialNav
    data object Account : SocialNav
    data object Friends : SocialNav
    data object ActivitySettings : SocialNav
    data object DiscordSettings : SocialNav
}

// A request to open the fullscreen photo viewer. [libraryId] scopes L1/R1 next/previous to the
// list the photo was opened from (null = All Photos). [openWallpaperPreview] opens straight into
// the wallpaper preview (the row's "Set as Launcher Wallpaper" context action) — still
// preview-first, so nothing changes until the user confirms.
data class PhotoViewerRequest(
    val photoId: String,
    val libraryId: String?,
    val openWallpaperPreview: Boolean = false,
)

sealed interface MusicNav {
    data object Root : MusicNav
    data object AllMusic : MusicNav
    data object Playlists : MusicNav
    data class Playlist(val id: Long, val name: String) : MusicNav
    data object MusicApps : MusicNav
}

// ── Fullscreen music browser (Settings-style, searchable) ───────────────────────
// Opened from the "Music" and "Playlist" root items as a fullscreen overlay (not the inline XMB
// list). Rows reuse XMBItem so the same row visuals/actions apply: tracks play, playlists drill in,
// plus Create Playlist / Add Tracks action rows.

sealed interface MusicBrowserView {
    data object AllMusic : MusicBrowserView
    data object Playlists : MusicBrowserView
    data class Playlist(val id: Long, val name: String) : MusicBrowserView
}

data class MusicBrowserState(
    val view: MusicBrowserView,
    val title: String,
    val query: String = "",
    val rows: List<XMBItem> = emptyList(),   // already filtered + sorted, ready to render
    val selectedIndex: Int = 0,
    // Status-bar style sort hint, non-null on track views (AllMusic / a Playlist's tracks).
    val sortLabel: String? = null,
    // Bumped to snap the list back to the top (sort change / query change).
    val scrollToTopToken: Int = 0,
)

// Drives the "New / Rename Playlist" text dialog. When [forTrackId] is set, the freshly created
// playlist immediately receives that track.
data class PlaylistNameDialogState(
    val title: String,
    val initialText: String = "",
    val forTrackId: String? = null,
    // When set, confirming renames this playlist instead of creating a new one.
    val renamePlaylistId: Long? = null,
    // Video playlist variant: routes create/rename to the video repository instead of music.
    val videoContext: Boolean = false,
    // When set (video create), the freshly-created playlist immediately receives this video.
    val forVideoId: String? = null,
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
    // Last-seen playlist lists, cached so the drill flyout can show a specific playlist's siblings.
    val musicPlaylists: List<com.playfieldportal.core.domain.model.Playlist> = emptyList(),
    val videoPlaylists: List<com.playfieldportal.core.domain.model.VideoPlaylist> = emptyList(),
    // PSP-style sort (X / Square). Tracked per context so switching categories doesn't carry a
    // music sort into games. sortLabel is the status-bar hint, non-null only on a sortable list.
    val gameSortMode: XmbSortMode = XmbSortMode.TITLE,
    val musicSortMode: XmbSortMode = XmbSortMode.TITLE,
    val videoSortMode: XmbSortMode = XmbSortMode.TITLE,
    val sortLabel: String? = null,
    // In-app music player: visible when a song is selected; playback state mirrors the controller.
    val musicPlayerVisible: Boolean = false,
    val musicPlayback: com.playfieldportal.feature.xmb.music.MusicPlaybackState =
        com.playfieldportal.feature.xmb.music.MusicPlaybackState(),

    // ── Vertical axis: games / settings items ─────────────────────────────
    val currentItems: List<XMBItem> = emptyList(),
    val selectedItemIndex: Int = 0,
    // Non-null while drilled into a Games sub-item (a platform card, All Games, Favorites, or a
    // collection): the parent's label, which drives the two-pane "flyout" listing (parent on the
    // left, children in a centre-locked column on the right). Null = normal single-column list.
    val drillTitle: String? = null,
    // The current category's sibling items (All Games / Favorites / collections / memory cards),
    // shown as the flyout's left icon column (PSP-style); [drillSiblingIndex] is the one currently
    // drilled into, which sits centred on the arrow. Empty when not drilled in.
    val drillSiblings: List<XMBItem> = emptyList(),
    val drillSiblingIndex: Int = 0,
    // Bumped whenever the list should snap back to the top regardless of cursor position — e.g. a
    // sort cycle. The item list scrolls to item 0 each time this changes (keyed reorders otherwise
    // keep the viewport anchored to the old top item).
    val scrollToTopToken: Int = 0,

    // ── Input source (drives the on-screen touch-navigation button) ────────
    // True when touch was the most recent input, false when a controller/key was. Flips only on a
    // real input event, so the contextual button doesn't flicker.
    val lastInputWasTouch: Boolean = false,
    val touchNavButtonMode: com.playfieldportal.core.domain.model.TouchNavButtonMode =
        com.playfieldportal.core.domain.model.TouchNavButtonMode.AUTO,
    // Swipe sensitivity for the XMB gesture layer (Settings ▸ Display ▸ Touch Sensitivity).
    val touchSensitivity: com.playfieldportal.core.domain.model.TouchSensitivity =
        com.playfieldportal.core.domain.model.TouchSensitivity.NORMAL,

    // ── Background + rendering ────────────────────────────────────────────
    // A custom wallpaper, when set, automatically replaces the wave.
    val waveStyle: WaveStyle = WaveStyle.ANIMATED,
    // When set (Settings ▸ Display, both default-on), the wave animation freezes while the system is
    // in battery-saver mode / thermally throttling — the wave is a non-essential flourish, so it
    // shouldn't compete for power when the device is trying to conserve it.
    val respectBatterySaver: Boolean = true,
    val thermalThrottleAware: Boolean = true,
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
    // Where a collection created from the App Detail screen should live — the category the app
    // row was opened from when it renders collections, otherwise the Main Game default.
    val activeAppCollectionCategoryId: String = BuiltInCategory.GAMES,
    val pendingAppDetailAction: GamepadAction? = null,
    // ── Video ─────────────────────────────────────────────────────────────
    val videoNav: VideoNav = VideoNav.Root,
    val videoLibraries: List<com.playfieldportal.core.domain.model.VideoLibrary> = emptyList(),
    val activeVideoId: String? = null,
    val pendingVideoDetailAction: GamepadAction? = null,

    // ── Photo ─────────────────────────────────────────────────────────────
    val photoNav: PhotoNav = PhotoNav.Root,
    val photoLibraries: List<com.playfieldportal.core.domain.model.PhotoLibrary> = emptyList(),
    val activePhotoViewer: PhotoViewerRequest? = null,
    val pendingPhotoViewerAction: GamepadAction? = null,

    // ── Context menu (Y/Triangle) ─────────────────────────────────────────
    val activeContextMenu: XMBContextMenu? = null,

    // ── Discord QR login overlay ──────────────────────────────────────────
    val activeDiscordLogin: Boolean = false,
    // ── Discord Social drill: Root (account) → Account (hub) → Friends ─────
    val socialNav: SocialNav = SocialNav.Root,
    val socialAccountAvatarUrl: String? = null,   // cached so the drill sibling column can render it

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

    // ── Fullscreen searchable music browser (Music / Playlist) ─────────────
    val musicBrowser: MusicBrowserState? = null,

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
    // True when the user has drilled into a sub-item on the home screen (a Games platform/collection/
    // All Games/Favorites, or a Music sub-view). Drives the floating Back button and locks Left/Right
    // category switching until the user backs out.
    val isInSubItem: Boolean
        get() = musicNav != MusicNav.Root ||
            videoNav != VideoNav.Root ||
            photoNav != PhotoNav.Root ||
            socialNav != SocialNav.Root ||
            selectedPlatformId != null ||
            selectedCollectionId != null

    // Whether the bottom-right contextual button (App Drawer / Back) should be shown, per the
    // user's Touch Navigation Button setting. AUTO follows the last input source.
    val resolvedShowTouchButton: Boolean
        get() = when (touchNavButtonMode) {
            com.playfieldportal.core.domain.model.TouchNavButtonMode.AUTO -> lastInputWasTouch
            com.playfieldportal.core.domain.model.TouchNavButtonMode.ALWAYS_SHOW -> true
            com.playfieldportal.core.domain.model.TouchNavButtonMode.ALWAYS_HIDE -> false
        }

    // True whenever something is layered over the main XMB. The gamepad dispatcher uses this
    // as a final guard so D-Pad/A never drives the category bar or item list behind an overlay.
    val hasBlockingOverlay: Boolean
        get() = showBootSequence ||
            activeSettingsScreen != null ||
            activeAppDrawerFilter != null ||
            activeGameId != null ||
            activeAppId != null ||
            activeVideoId != null ||
            activePhotoViewer != null ||
            activeContextMenu != null ||
            activeDiscordLogin ||
            colorSchemePicker != null ||
            appPicker != null ||
            gamePickerCategoryId != null ||
            renameAppTarget != null ||
            collectionNameDialog != null ||
            playlistNameDialog != null ||
            musicTrackPicker != null ||
            musicBrowser != null ||
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
    VIDEO_LIBRARY,
    VIDEO_FOLDER,
    VIDEO_FILE,
    VIDEO_APPS,
    VIDEO_RECENT,
    VIDEO_FAVORITES,
    VIDEO_COLLECTIONS,
    PHOTO_ALBUMS,
    PHOTO_FOLDER,
    PHOTO_FILE,
    PHOTO_APPS,
    CAMERA,
    // "Add …" / "Create …" rows (add library/folder/apps/tracks, create playlist) — plus glyph.
    ADD_ACTION,
    // Discord Social section rows.
    SOCIAL_ADD,               // "Sign in with Discord" — opens the QR login overlay
    SOCIAL_ACCOUNT,           // a connected Discord account (L1) — drills into the hub
    SOCIAL_FRIENDS,           // hub row → drills into the Friends list
    SOCIAL_VOICE,             // hub row (placeholder)
    SOCIAL_ACTIVITY_SETTINGS, // hub row → drills into Activity Settings
    SOCIAL_DISCORD_SETTINGS,  // hub row → drills into Discord Settings
    SOCIAL_FRIEND,            // a single friend (L3)
    SOCIAL_TOGGLE,            // an on/off preference row (Activity Settings), toggled on select
    SOCIAL_SIGNOUT,           // "Sign Out"
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
private val VIDEO_SORTS = listOf(XmbSortMode.TITLE, XmbSortMode.DATE_ADDED, XmbSortMode.RECENT_PLAYED)

internal fun List<com.playfieldportal.core.domain.model.Video>.videoSorted(mode: XmbSortMode): List<com.playfieldportal.core.domain.model.Video> = when (mode) {
    XmbSortMode.RECENT_PLAYED -> sortedByDescending { it.lastWatchedAt ?: 0L }
    XmbSortMode.DATE_ADDED    -> sortedByDescending { it.dateAdded ?: 0L }
    else                      -> sortedBy { it.displayTitle.lowercase() }
}

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
    val iconKey: String? = null,        // catalog icon key for COLLECTION rows (null = default memory-card art)
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
    // Discord friend rows: a colored presence dot (ARGB) shown before the subtitle; null = no dot.
    // Only set on SOCIAL_FRIEND rows.
    val socialStatusArgb: Long? = null,
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
    private val emulatorProfileRepository: com.playfieldportal.feature.launcher.EmulatorProfileRepository,
    private val videoRepository: com.playfieldportal.core.domain.repository.VideoRepository,
    private val photoRepository: com.playfieldportal.core.domain.repository.PhotoRepository,
    private val photoScanner: com.playfieldportal.feature.library.scanner.PhotoScanner,
    private val hiddenPlacementDao: com.playfieldportal.core.data.database.dao.HiddenPlacementDao,
    private val discordAuthRepository: com.playfieldportal.core.data.discord.DiscordAuthRepository,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
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
    // Backs the fullscreen music browser: a collector job over the active view's data, plus the raw
    // (unfiltered, DB-order) lists kept so query/sort changes re-derive rows without a DB round-trip.
    private var musicBrowserJob: Job? = null
    private var browserRawTracks: List<MusicTrack> = emptyList()
    private var browserRawPlaylists: List<com.playfieldportal.core.domain.model.Playlist> = emptyList()
    private var platformCache: Map<String, PlatformEntity> = emptyMap()
    // emulator package → friendly name (e.g. "org.ppsspp.ppsspp" → "PPSSPP"), for the game subtitle's
    // "Platform (Emulator)" label. Populated from the emulator profiles.
    private var emulatorNameByPackage: Map<String, String> = emptyMap()
    private var enabledCards: List<MemoryCard> = emptyList()
    private var baseThemeColors: PFPColors = DefaultPFPColors

    // Background work is surfaced to the Android notification bar, not an in-app tray.
    private val taskNotifier = BackgroundTaskNotifier(context)

    init {
        gamepadInputHandler.scope = viewModelScope
        observeIconStyle()
        observeBackgroundSettings()
        observeTouchNavButtonMode()
        observeWallpaper()
        observeLibrarySetupState()
        observeColorScheme()
        observeCategoryBar()
        observeCategories()
        observeAppChanges()
        observeGamepadMappings()
        observeSoundSetting()
        observeMusic()
        observeVideo()
        observePhoto()
        observeHiddenPlacements()
        observeEmulatorProfiles()
        collectGamepadActions()
    }

    // Keeps the emulator package → name map current so game subtitles can show "Platform (Emulator)".
    // Reloads the on-screen items once names arrive so already-listed games pick up their emulator.
    private fun observeEmulatorProfiles() {
        viewModelScope.launch {
            emulatorProfileRepository.profiles.collect { profiles ->
                val map = profiles.associate { it.packageName to it.name }
                if (map != emulatorNameByPackage) {
                    emulatorNameByPackage = map
                    if (_uiState.value.currentItems.any { it.gameId != null }) {
                        loadItemsForCategory(currentCategory())
                    }
                }
            }
        }
    }

    // "Platform (Emulator)" for a game's subtitle: the platform's display name, plus the emulator's
    // friendly name in parens when one is resolvable (the game's override, else the platform default).
    private fun platformEmulatorLabel(g: Game): String {
        val platform = platformCache[g.platformId]?.name ?: g.platformId
        val emulatorPkg = g.emulatorPackage ?: platformCache[g.platformId]?.preferredEmulatorPackage
        val emulator = emulatorPkg?.let { emulatorNameByPackage[it] }
        return if (emulator != null) "$platform ($emulator)" else platform
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
                        // Preserve the cursor by row id: the new "Now Playing" row shifts every
                        // index, and this often happens behind the fullscreen browser — the XMB
                        // must already be re-anchored when it's revealed (no visible snap).
                        refreshMusicRootPreservingCursor()
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
                    // One theme color across the whole XMB (PSP-authentic) — no per-category tint.
                    _uiState.update { it.copy(themeColors = baseThemeColors) }
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

                    // Refresh any collection-rendering category live as cards/counts/collections
                    // change — collections place themselves by categoryId, so gaming categories
                    // AND non-gaming app categories (Network / App Store / custom) must re-render
                    // when the collection list changes.
                    if (categoryShowsCollections(currentCategory())) {
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

    // Categories with their own dedicated loadItemsForCategory branch — these never render
    // collection rows (media/system sections own their layouts).
    private val nonCollectionCategoryIds = setOf(
        BuiltInCategory.FAVORITES, BuiltInCategory.RECENTLY_PLAYED, BuiltInCategory.MUSIC,
        BuiltInCategory.VIDEO, BuiltInCategory.PHOTO, BuiltInCategory.ANDROID,
        BuiltInCategory.APP_DRAWER, BuiltInCategory.SETTINGS,
    )

    /** True for categories that render collection rows: gaming categories, and the generic app
     *  categories (Network / App Store / custom non-gaming) served by the `else` item branch. */
    private fun categoryShowsCollections(category: Category?): Boolean {
        if (category == null) return false
        return category.isGamingCategory || category.id !in nonCollectionCategoryIds
    }

    /** The category a collection created from the current context should live in: the current
     *  category when it renders collections, otherwise the Main Game default. */
    private fun collectionHomeCategoryId(): String {
        val cat = currentCategory() ?: return BuiltInCategory.GAMES
        return if (categoryShowsCollections(cat)) cat.id else BuiltInCategory.GAMES
    }

    private fun loadItemsForCategory(category: Category?) {
        currentItemsJob?.cancel()
        if (category == null) { _uiState.update { it.copy(currentItems = emptyList(), sortLabel = null, drillTitle = null, drillSiblings = emptyList(), drillSiblingIndex = 0) }; return }
        val drill = computeDrillTitle()
        val (sibs, sibIdx) = if (drill != null) computeDrillSiblings(category) else (emptyList<XMBItem>() to 0)
        _uiState.update { it.copy(sortLabel = currentSortLabel(), drillTitle = drill, drillSiblings = sibs, drillSiblingIndex = sibIdx) }

        currentItemsJob = viewModelScope.launch {
            when (category.id) {
                BuiltInCategory.FAVORITES -> {
                    gameRepository.observeFavorites().collect { games ->
                        _uiState.update { it.copy(currentItems = games.notHiddenAt(HideLocationType.FAVORITES).gameSorted(_uiState.value.gameSortMode).toXmbItems()) }
                    }
                }
                BuiltInCategory.ANDROID -> {
                    _uiState.update { it.copy(currentItems = ANDROID_ITEMS) }
                }
                BuiltInCategory.SETTINGS -> {
                    _uiState.update { it.copy(currentItems = SETTINGS_ITEMS) }
                }
                BuiltInCategory.SOCIAL -> when (_uiState.value.socialNav) {
                    SocialNav.Root -> {
                        val items = socialRootItems()
                        val avatar = items.firstOrNull { it.type == XMBItemType.SOCIAL_ACCOUNT }?.coverUri
                        _uiState.update { it.copy(currentItems = items, socialAccountAvatarUrl = avatar) }
                        // Fill in the avatar/name once the gateway is Ready (skip while offline).
                        if (discordAuthRepository.isOnline() &&
                            items.any { it.type == XMBItemType.SOCIAL_ACCOUNT && it.coverUri == null }
                        ) {
                            scheduleSocialAccountRefresh()
                        }
                    }
                    SocialNav.Account -> {
                        val online = if (discordAuthRepository.isOnline()) {
                            discordAuthRepository.friends().count { it.presence.isOnline }
                        } else {
                            null
                        }
                        _uiState.update { it.copy(currentItems = socialHubItems(online)) }
                    }
                    SocialNav.Friends -> _uiState.update { it.copy(currentItems = socialFriendItems()) }
                    SocialNav.ActivitySettings -> _uiState.update { it.copy(currentItems = socialActivitySettingsItems()) }
                    SocialNav.DiscordSettings -> _uiState.update { it.copy(currentItems = socialDiscordSettingsItems()) }
                }
                BuiltInCategory.GAMES -> {
                    val platformId = _uiState.value.selectedPlatformId
                    val collectionId = _uiState.value.selectedCollectionId
                    if (collectionId != null) {
                        // A user collection — games from any platform, app entries allowed only
                        // because they were explicitly added by the user.
                        collectionRepository.observeGames(collectionId).collect { games ->
                            val visible = games.notHiddenAt(HideLocationType.COLLECTION, collectionId.toString())
                            val items = if (visible.isEmpty()) listOf(emptyCollectionItem())
                                        else visible.gameSorted(_uiState.value.gameSortMode).toXmbItems()
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
                            val visible = games.notHiddenAt(HideLocationType.FAVORITES)
                            val items = if (visible.isEmpty()) listOf(emptyFavoritesItem())
                                        else visible.gameSorted(_uiState.value.gameSortMode).toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                    } else if (platformId != null) {
                        gameRepository.observeByPlatform(platformId).collect { games ->
                            // Only the Android platform supports per-location hiding (ROM platforms don't).
                            val visible = if (platformId == ANDROID_PLATFORM_ID)
                                games.notHiddenAt(HideLocationType.ANDROID_PLATFORM) else games
                            val items = if (visible.isEmpty()) listOf(emptyFolderItem(platformId))
                                        else visible.gameSorted(_uiState.value.gameSortMode).toXmbItems()
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
                            _uiState.update { it.copy(currentItems = playlistRootItems(playlists), musicPlaylists = playlists) }
                        }
                    }
                    MusicNav.MusicApps -> {
                        clearMusicTrackCache()
                        val items = musicAppItems()
                        _uiState.update { it.copy(currentItems = items) }
                    }
                }
                BuiltInCategory.VIDEO -> when (val nav = _uiState.value.videoNav) {
                    VideoNav.Root -> _uiState.update { it.copy(currentItems = videoRootItems()) }
                    VideoNav.Collections -> _uiState.update { it.copy(currentItems = videoCollectionsItems()) }
                    VideoNav.AllVideos -> videoRepository.observeAllVideos().collect { videos ->
                        setVideoItems(videos, emptyAllVideosItem())
                    }
                    VideoNav.RecentlyWatched -> videoRepository.observeRecentlyWatched().collect { videos ->
                        // Recency order is intrinsic — don't apply the user sort here.
                        setVideoItems(videos, emptyRecentItem(), sortable = false)
                    }
                    VideoNav.Favorites -> videoRepository.observeFavorites().collect { videos ->
                        setVideoItems(videos, emptyFavoriteVideosItem())
                    }
                    VideoNav.Playlists -> videoRepository.observePlaylists().collect { playlists ->
                        _uiState.update { it.copy(currentItems = videoPlaylistItems(playlists), videoPlaylists = playlists) }
                    }
                    is VideoNav.Playlist -> videoRepository.observePlaylistVideos(nav.id).collect { videos ->
                        // Manual playlist order — keep it, don't re-sort.
                        setVideoItems(videos, emptyPlaylistVideosItem(), sortable = false)
                    }
                    VideoNav.Libraries -> videoRepository.observeLibraries().collect { libs ->
                        _uiState.update { it.copy(currentItems = videoLibraryItems(libs)) }
                    }
                    is VideoNav.Library -> videoRepository.observeVideosByLibrary(nav.id).collect { videos ->
                        setVideoItems(videos, emptyAllVideosItem())
                    }
                    VideoNav.VideoApps -> {
                        val items = videoAppItems()
                        _uiState.update { it.copy(currentItems = items) }
                    }
                }
                BuiltInCategory.PHOTO -> when (val nav = _uiState.value.photoNav) {
                    PhotoNav.Root -> _uiState.update { it.copy(currentItems = photoRootItems()) }
                    PhotoNav.AllPhotos -> photoRepository.observeAllPhotos().collect { photos ->
                        setPhotoItems(photos, emptyAllPhotosItem())
                    }
                    PhotoNav.Albums -> photoRepository.observeLibraries().collect { libs ->
                        _uiState.update { it.copy(currentItems = photoAlbumItems(libs)) }
                    }
                    is PhotoNav.Library -> photoRepository.observePhotosByLibrary(nav.id).collect { photos ->
                        setPhotoItems(photos, emptyLibraryPhotosItem())
                    }
                    PhotoNav.PhotoApps -> {
                        val items = photoAppItems()
                        _uiState.update { it.copy(currentItems = items) }
                    }
                }
                else -> {
                    // Gaming categories show games and collections
                    // Drilled into one of this category's collections — show its members. Works
                    // for gaming and non-gaming categories alike: members are games-table rows,
                    // which in non-gaming (app) collections are ANDROID_APP shortcut rows that
                    // launch by package like anywhere else.
                    val openCollectionId = _uiState.value.selectedCollectionId
                    if (openCollectionId != null) {
                        collectionRepository.observeGames(openCollectionId).collect { games ->
                            val visible = games.notHiddenAt(HideLocationType.COLLECTION, openCollectionId.toString())
                            val items = if (visible.isEmpty()) listOf(emptyCollectionItem())
                                        else visible.gameSorted(_uiState.value.gameSortMode).toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                        return@launch
                    }
                    if (category.isGamingCategory) {
                        // Games are assigned via the junction table (echo/copy model). Reuse the
                        // canonical Game→XMBItem mapping so icons/artwork/launch fields match the
                        // Main Game category exactly; only overlay the "Pinned" marker.
                        val gameRows = gameCategoryRepository.itemsForCategory(category.id)
                            .filterIsInstance<com.playfieldportal.core.data.repository.GameCategoryItem.GameItem>()
                            .filterNot { isHiddenAt(HiddenPlacement.gameKey(it.game.id), HideLocationType.CATEGORY, category.id) }
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
                                    iconKey = collection.iconKey,
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
                            .notHiddenAt(HideLocationType.CATEGORY, category.id)
                        val appItems = apps.map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
                        // App collections homed in this category (categoryId is the single source
                        // of truth for placement, same as gaming categories). Pinned sort first.
                        val collectionItems = _uiState.value.collections
                            .filter { it.categoryId == category.id }
                            .sortedByDescending { it.isPinned }
                            .map { collection ->
                                val count = "${collection.gameCount} ${if (collection.gameCount == 1) "App" else "Apps"}"
                                XMBItem(
                                    id = "col_${collection.id}",
                                    title = collection.name,
                                    subtitle = if (collection.isPinned) "Pinned · $count" else count,
                                    collectionId = collection.id,
                                    iconKey = collection.iconKey,
                                    type = XMBItemType.COLLECTION,
                                )
                            }
                        val combined = collectionItems + appItems
                        val items = if (combined.isEmpty()) listOf(emptyCategoryItem(category)) else combined
                        // "Add Apps" is offered on every app section so the same picker serves
                        // Video, Music, Network, App Store and custom categories alike.
                        _uiState.update { it.copy(currentItems = items + addAppsItem()) }
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
        // The XMB hover background reads artworkUri — populate it (with a hero fallback) so a
        // non-gaming category app shows its assigned background, like games do.
        artworkUri   = artwork?.let { it.artworkUri ?: it.heroUri },
        heroUri      = artwork?.heroUri,
        accentColor  = artwork?.let { platformCache[it.platformId]?.accentColor },
    )

    private fun addAppsItem(): XMBItem = XMBItem(
        id       = ADD_APPS_ITEM_ID,
        title    = "Add Apps",
        subtitle = "Pick installed apps to add to this section",
        type     = XMBItemType.ADD_ACTION,
    )

    private fun addGamesItem(): XMBItem = XMBItem(
        id       = ADD_GAMES_ITEM_ID,
        title    = "Add Games",
        subtitle = "Pick games and collections to add to this category",
        type     = XMBItemType.ADD_ACTION,
    )

    // ── Music ───────────────────────────────────────────────────────────────────

    /** Rebuilds the Music root list in place, relocating the cursor to the same row id so a shape
     *  change (the "Now Playing" row appearing/disappearing) never moves the visible selection. */
    private fun refreshMusicRootPreservingCursor() {
        val s = _uiState.value
        val selectedId = s.currentItems.getOrNull(s.selectedItemIndex)?.id
        clearMusicTrackCache()
        val items = musicRootItems()
        val restored = selectedId
            ?.let { id -> items.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: s.selectedItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        _uiState.update { it.copy(currentItems = items, selectedItemIndex = restored) }
    }

    // Music root: the static items (Now Playing, when something is playing; Playlist; Music Apps)
    // followed by the single "All Music" memory-card item. The root folder is managed in Settings →
    // Music; a getting-started "Add Music Folder" row shows until a root has been added and scanned
    // (keyed off the scan completing, not the track count), then drops away.
    private fun musicRootItems(): List<XMBItem> {
        val folders = _uiState.value.musicFolders
        val totalTracks = folders.sumOf { it.trackCount }
        val hasScannedFolder = folders.any { it.lastScannedAt != null }
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
            // Getting-started prompt: opens Settings → Music. Drops away once a root has been
            // scanned (even if it found no tracks), since the root is then managed in Settings.
            if (!hasScannedFolder) add(addMusicFolderItem())
        }
    }

    private fun addMusicFolderItem(): XMBItem = XMBItem(
        id       = ADD_MUSIC_FOLDER_ITEM_ID,
        title    = "Add Music Folder",
        subtitle = "Set your Music root folder in Settings to get started",
        type     = XMBItemType.ADD_ACTION,
    )

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
            type     = XMBItemType.ADD_ACTION,
        )
    }

    // Music Apps: the apps the user added (stored under a dedicated pseudo-category so they don't
    // mix with the built-in Music category), plus an "Add Music Apps" row.
    private suspend fun musicAppItems(): List<XMBItem> {
        val apps = appCategoryRepository.appsForCategory(MUSIC_APPS_CATEGORY_ID)
            .notHiddenAt(HideLocationType.CATEGORY, MUSIC_APPS_CATEGORY_ID)
        val appItems = apps.map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
        return appItems + XMBItem(
            id       = ADD_MUSIC_APPS_ITEM_ID,
            title    = "Add Music Apps",
            subtitle = "Pick installed apps to show here",
            type     = XMBItemType.ADD_ACTION,
        )
    }

    private fun addTracksItem(): XMBItem = XMBItem(
        id       = ADD_TRACKS_ITEM_ID,
        title    = "Add Tracks",
        subtitle = "Pick songs to add to this playlist",
        type     = XMBItemType.ADD_ACTION,
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
    private fun openMusicView(nav: MusicNav) = navigateRememberingCursor { it.copy(musicNav = nav) }

    private fun closeMusicView() = openMusicView(MusicNav.Root)

    // ── Per-location hiding ───────────────────────────────────────────────────────

    // Fast lookup set of "itemKey|LOCATION_TYPE|locationId" for every hidden placement, refreshed
    // reactively. Item lists are filtered against it as they're built.
    @Volatile private var hiddenKeys: Set<String> = emptySet()

    private fun observeHiddenPlacements() {
        viewModelScope.launch {
            hiddenPlacementDao.observeAll().collect { rows ->
                hiddenKeys = rows.map { "${it.itemKey}|${it.locationType}|${it.locationId}" }.toSet()
                // Re-render the current list so a hide/unhide takes effect immediately.
                loadItemsForCategory(currentCategory())
            }
        }
    }

    private fun isHiddenAt(itemKey: String, type: HideLocationType, locationId: String = ""): Boolean =
        hiddenKeys.contains("$itemKey|${type.name}|$locationId")

    @JvmName("gamesNotHiddenAt")
    private fun List<Game>.notHiddenAt(type: HideLocationType, locationId: String = ""): List<Game> =
        filterNot { isHiddenAt(HiddenPlacement.gameKey(it.id), type, locationId) }

    @JvmName("appsNotHiddenAt")
    private fun List<CategorizedApp>.notHiddenAt(type: HideLocationType, locationId: String = ""): List<CategorizedApp> =
        filterNot { isHiddenAt(HiddenPlacement.appKey(it.packageName), type, locationId) }

    // Persists a hide placement, caching labels so the Hidden Items manager renders without joins.
    private fun persistHide(itemKey: String, itemLabel: String, type: HideLocationType, locationId: String, locationLabel: String) {
        viewModelScope.launch {
            hiddenPlacementDao.upsert(
                HiddenPlacementEntity(itemKey, itemLabel, type.name, locationId, locationLabel, System.currentTimeMillis())
            )
        }
    }

    private fun categoryDisplayName(id: String): String = when (id) {
        MUSIC_APPS_CATEGORY_ID -> "Music Apps"
        VIDEO_APPS_CATEGORY_ID -> "Video Apps"
        else -> _uiState.value.categories.firstOrNull { it.id == id }?.name ?: id
    }

    // The location a GAME row is currently being shown in (for "Hide from here"), or null when the
    // current view doesn't support per-location hiding (All Games, a ROM platform, the Games root).
    private fun currentHideLocation(): Triple<HideLocationType, String, String>? {
        val s = _uiState.value
        val cat = currentCategory()
        return when {
            s.selectedCollectionId != null -> {
                val name = s.collections.firstOrNull { it.id == s.selectedCollectionId }?.name ?: "Collection"
                Triple(HideLocationType.COLLECTION, s.selectedCollectionId.toString(), name)
            }
            s.selectedPlatformId == FAVORITES_PLATFORM_ID || cat?.id == BuiltInCategory.FAVORITES ->
                Triple(HideLocationType.FAVORITES, "", "Favorites")
            s.selectedPlatformId == ANDROID_PLATFORM_ID -> Triple(HideLocationType.ANDROID_PLATFORM, "", "Android")
            cat != null && cat.isGamingCategory && cat.id != BuiltInCategory.GAMES &&
                s.selectedPlatformId == null && s.selectedCollectionId == null ->
                Triple(HideLocationType.CATEGORY, cat.id, cat.name)
            else -> null
        }
    }

    // ── Video ───────────────────────────────────────────────────────────────────

    // Library list drives the Video root; re-render the root when it changes.
    private fun observeVideo() {
        viewModelScope.launch {
            videoRepository.observeLibraries().collect { libraries ->
                _uiState.update { it.copy(videoLibraries = libraries) }
                if (currentCategory()?.id == BuiltInCategory.VIDEO &&
                    _uiState.value.videoNav == VideoNav.Root
                ) {
                    loadItemsForCategory(currentCategory())
                }
            }
        }
    }

    // Video root: browse rows first (Collections, Video Libraries), then the Video Apps counterpart
    // directly above the "Videos" memory card (second-to-bottom). The root folder is managed in
    // Settings → Video; a getting-started "Add Videos" row shows until a root has been added and
    // scanned (keyed off the scan completing, not the video count), then drops away.
    private fun videoRootItems(): List<XMBItem> {
        val libraries = _uiState.value.videoLibraries
        val totalVideos = libraries.sumOf { it.videoCount }
        val hasScannedLibrary = libraries.any { it.lastScannedAt != null }
        return buildList {
            // The three curated views collapse into one "Collections" entry (drills into
            // Recently Watched / Favorites / Playlists) to keep the Video root uncluttered.
            add(
                XMBItem(
                    id       = VIDEO_COLLECTIONS_ITEM_ID,
                    title    = "Collections",
                    subtitle = "Recently Watched, Favorites & Playlists",
                    type     = XMBItemType.VIDEO_COLLECTIONS,
                )
            )
            add(
                XMBItem(
                    id       = VIDEO_LIBRARIES_ITEM_ID,
                    title    = "Video Libraries",
                    subtitle = "${libraries.size} ${if (libraries.size == 1) "library" else "libraries"}",
                    type     = XMBItemType.VIDEO_LIBRARY,
                )
            )
            // Video Apps counterpart, sitting directly above the memory card.
            add(
                XMBItem(
                    id       = VIDEO_APPS_ITEM_ID,
                    title    = "Video Apps",
                    subtitle = "Open your installed video apps",
                    type     = XMBItemType.VIDEO_APPS,
                )
            )
            add(
                XMBItem(
                    id       = ALL_VIDEOS_ITEM_ID,
                    title    = "Videos",
                    subtitle = "$totalVideos ${if (totalVideos == 1) "video" else "videos"}",
                    coverUri = MEMORY_CARD_ASSET_URI,
                    type     = XMBItemType.MEMORY_CARD,
                )
            )
            // Getting-started prompt: opens Settings → Video. Drops away once a root has been
            // scanned (even if it found no videos), since the root is then managed in Settings.
            if (!hasScannedLibrary) add(addVideosItem())
        }
    }

    private fun addVideosItem(): XMBItem = XMBItem(
        id       = ADD_VIDEOS_ITEM_ID,
        title    = "Add Videos",
        subtitle = "Set your Video root folder in Settings to get started",
        type     = XMBItemType.ADD_ACTION,
    )

    // The "Collections" drill-in: the three curated views, one level below the Video root.
    private fun videoCollectionsItems(): List<XMBItem> = listOf(
        XMBItem(
            id       = RECENTLY_WATCHED_ITEM_ID,
            title    = "Recently Watched",
            subtitle = "Pick up where you left off",
            type     = XMBItemType.VIDEO_RECENT,
        ),
        XMBItem(
            id       = FAVORITE_VIDEOS_ITEM_ID,
            title    = "Favorites",
            subtitle = "Your starred videos",
            type     = XMBItemType.VIDEO_FAVORITES,
        ),
        XMBItem(
            id       = VIDEO_PLAYLISTS_ITEM_ID,
            title    = "Playlists",
            subtitle = "Build and play your own lists",
            type     = XMBItemType.PLAYLIST,
        ),
    )

    // One card per video library, drillable into its videos. The root folder is managed in
    // Settings → Video, so there is no add row here.
    private fun videoLibraryItems(libraries: List<com.playfieldportal.core.domain.model.VideoLibrary>): List<XMBItem> {
        val rows = libraries.map { lib ->
            XMBItem(
                id       = "vlib_${lib.id}",
                title    = lib.displayName,
                subtitle = "${lib.videoCount} ${if (lib.videoCount == 1) "video" else "videos"}",
                coverUri = lib.artworkUri,
                type     = XMBItemType.VIDEO_FOLDER,
            )
        }
        return rows.ifEmpty {
            listOf(
                XMBItem(
                    id = EMPTY_CATEGORY_ITEM_ID,
                    title = "No video libraries yet",
                    subtitle = "Set a root folder in Settings → Video",
                    type = XMBItemType.EMPTY,
                ),
            )
        }
    }

    private suspend fun videoAppItems(): List<XMBItem> {
        val apps = appCategoryRepository.appsForCategory(VIDEO_APPS_CATEGORY_ID)
            .notHiddenAt(HideLocationType.CATEGORY, VIDEO_APPS_CATEGORY_ID)
        val appItems = apps.map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
        return appItems + XMBItem(
            id       = ADD_VIDEO_APPS_ITEM_ID,
            title    = "Add Video Apps",
            subtitle = "Pick installed apps to show here",
            type     = XMBItemType.ADD_ACTION,
        )
    }

    private fun List<com.playfieldportal.core.domain.model.Video>.toVideoItems(): List<XMBItem> =
        map { video ->
            XMBItem(
                id       = "vid_${video.id}",
                title    = video.displayTitle,
                subtitle = video.durationMs?.let { formatDuration(it) },
                type     = XMBItemType.VIDEO_FILE,
                mediaUri = video.uri,
                mimeType = video.mimeType,
                coverUri = video.effectiveThumbnailUri,
            )
        }

    private fun setVideoItems(
        videos: List<com.playfieldportal.core.domain.model.Video>,
        emptyItem: XMBItem,
        sortable: Boolean = true,
    ) {
        val ordered = if (sortable) videos.videoSorted(_uiState.value.videoSortMode) else videos
        val items = if (ordered.isEmpty()) listOf(emptyItem) else ordered.toVideoItems()
        _uiState.update { it.copy(currentItems = items) }
    }

    // Playlist list: one row per playlist + a "Create Playlist" row.
    private fun videoPlaylistItems(playlists: List<com.playfieldportal.core.domain.model.VideoPlaylist>): List<XMBItem> {
        val rows = playlists.map { pl ->
            XMBItem(
                id         = "vpl_${pl.id}",
                title      = pl.name,
                subtitle   = "${pl.videoCount} ${if (pl.videoCount == 1) "video" else "videos"}",
                playlistId = pl.id,
                type       = XMBItemType.PLAYLIST,
            )
        }
        return rows + XMBItem(
            id       = CREATE_VIDEO_PLAYLIST_ITEM_ID,
            title    = "Create Playlist",
            subtitle = "Start a new video playlist",
            type     = XMBItemType.ADD_ACTION,
        )
    }

    private fun emptyAllVideosItem(): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "No videos found",
        subtitle = "Add a video library in Settings → Video",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyRecentItem(): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "Nothing watched yet",
        subtitle = "Videos you play show up here",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyFavoriteVideosItem(): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "No favorites yet",
        subtitle = "Star a video from its ⚙ Options menu",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyPlaylistVideosItem(): XMBItem = XMBItem(
        id       = EMPTY_PLAYLIST_ITEM_ID,
        title    = "This playlist is empty",
        subtitle = "Add videos from a video's ⚙ Options menu",
        type     = XMBItemType.EMPTY,
    )

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // Handles A/Cross on any Video row. Returns true when [item] is a Video row it owns.
    private fun handleVideoSelection(item: XMBItem): Boolean = when {
        item.type == XMBItemType.EMPTY -> true
        item.id == ALL_VIDEOS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.AllVideos); true }
        item.id == VIDEO_COLLECTIONS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Collections); true }
        item.id == RECENTLY_WATCHED_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.RecentlyWatched); true }
        item.id == FAVORITE_VIDEOS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Favorites); true }
        item.id == VIDEO_PLAYLISTS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Playlists); true }
        item.id == CREATE_VIDEO_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); promptCreateVideoPlaylist(); true }
        item.id.startsWith("vpl_") && item.playlistId != null -> {
            menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Playlist(item.playlistId, item.title)); true
        }
        item.id == VIDEO_LIBRARIES_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Libraries); true }
        item.id == VIDEO_APPS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.VideoApps); true }
        item.id == ADD_VIDEOS_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            _uiState.update { it.copy(activeSettingsScreen = "settings_video") }
            true
        }
        item.id == ADD_VIDEO_APPS_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            openAppPicker(AppPickerTarget.CategoryShortcuts(VIDEO_APPS_CATEGORY_ID), "Add Video Apps")
            true
        }
        item.id.startsWith("vlib_") -> {
            menuSound.play(MenuSound.SELECT)
            val libId = item.id.removePrefix("vlib_")
            openVideoView(VideoNav.Library(libId, item.title))
            true
        }
        item.type == XMBItemType.VIDEO_FILE -> {
            menuSound.play(MenuSound.SELECT)
            _uiState.update { it.copy(activeVideoId = item.id.removePrefix("vid_")) }
            true
        }
        // Video-app rows launch the app.
        _uiState.value.videoNav == VideoNav.VideoApps && item.packageName != null -> {
            menuSound.play(MenuSound.LAUNCH); appCategoryRepository.launch(item.packageName); true
        }
        else -> false
    }

    // ── View cursor memory ──────────────────────────────────────────────────────
    // One remembered cursor position per drillable view, across EVERY category: Games memory-card
    // folders / All Games / Favorites / collections (built-in and custom categories alike, keyed by
    // category id so future custom categories get their own slots for free), and the Music / Video /
    // Photo sub-views. Drilling in/out (or re-entering a view) lands where the user left off instead
    // of snapping to the first item.
    private val viewCursor = mutableMapOf<String, Int>()

    // Remembered cursor position per category (keyed by category id), so switching categories and
    // returning restores the item you were on instead of the first.
    private val categoryCursor = mutableMapOf<String, Int>()

    // Stable key for whatever list [s] currently shows.
    private fun viewCursorKey(s: XMBUiState): String {
        val catId = s.categories.getOrNull(s.selectedCategoryIndex)?.id ?: "none"
        val sub = when {
            catId == BuiltInCategory.MUSIC -> "music_${musicNavKey(s.musicNav)}"
            catId == BuiltInCategory.VIDEO -> "video_${videoNavKey(s.videoNav)}"
            catId == BuiltInCategory.PHOTO -> "photo_${photoNavKey(s.photoNav)}"
            catId == BuiltInCategory.SOCIAL -> "social_${socialNavKey(s.socialNav)}"
            s.selectedCollectionId != null -> "col_${s.selectedCollectionId}"
            s.selectedPlatformId != null   -> "plat_${s.selectedPlatformId}"
            else                           -> "root"
        }
        return "$catId/$sub"
    }

    // Performs a drill navigation with cursor memory: saves the current view's cursor, applies
    // [mutate] (which must not touch selectedItemIndex), restores the destination view's remembered
    // cursor (0 the first time), then reloads the item list.
    private fun navigateRememberingCursor(mutate: (XMBUiState) -> XMBUiState) {
        val cur = _uiState.value
        viewCursor[viewCursorKey(cur)] = cur.selectedItemIndex
        _uiState.update { state ->
            val next = mutate(state)
            next.copy(selectedItemIndex = viewCursor[viewCursorKey(next)] ?: 0)
        }
        loadItemsForCategory(currentCategory())
    }

    private fun musicNavKey(nav: MusicNav): String = when (nav) {
        MusicNav.Root        -> "root"
        MusicNav.AllMusic    -> "all"
        MusicNav.Playlists   -> "playlists"
        is MusicNav.Playlist -> "playlist_${nav.id}"
        MusicNav.MusicApps   -> "apps"
    }

    private fun videoNavKey(nav: VideoNav): String = when (nav) {
        VideoNav.Root            -> "root"
        VideoNav.AllVideos       -> "all"
        VideoNav.Collections     -> "collections"
        VideoNav.RecentlyWatched -> "recent"
        VideoNav.Favorites       -> "favorites"
        VideoNav.Playlists       -> "playlists"
        is VideoNav.Playlist     -> "playlist_${nav.id}"
        VideoNav.Libraries       -> "libraries"
        is VideoNav.Library      -> "library_${nav.id}"
        VideoNav.VideoApps       -> "apps"
    }

    // Each Social drill level needs its own cursor key — without this every level collides on the
    // category's fallback "root" key, so drilling into a shorter list restores an out-of-range index
    // and the selection highlight lands on nothing.
    private fun socialNavKey(nav: SocialNav): String = when (nav) {
        SocialNav.Root             -> "root"
        SocialNav.Account          -> "account"
        SocialNav.Friends          -> "friends"
        SocialNav.ActivitySettings -> "activity"
        SocialNav.DiscordSettings  -> "discord"
    }

    private fun openVideoView(nav: VideoNav) = navigateRememberingCursor { it.copy(videoNav = nav) }

    private fun closeVideoView() = openVideoView(VideoNav.Root)

    fun onCloseVideoDetail() {
        _uiState.update { it.copy(activeVideoId = null, pendingVideoDetailAction = null) }
    }

    fun consumeVideoDetailAction() {
        _uiState.update { it.copy(pendingVideoDetailAction = null) }
    }

    private fun promptCreateVideoPlaylist(forVideoId: String? = null) {
        _uiState.update { it.copy(
            playlistNameDialog = PlaylistNameDialogState(title = "New Video Playlist", videoContext = true, forVideoId = forVideoId)
        )}
    }

    private fun promptRenameVideoPlaylist(playlistId: Long) {
        val name = _uiState.value.currentItems.firstOrNull { it.playlistId == playlistId }?.title.orEmpty()
        _uiState.update { it.copy(
            playlistNameDialog = PlaylistNameDialogState(
                title = "Rename Playlist",
                initialText = name,
                renamePlaylistId = playlistId,
                videoContext = true,
            )
        )}
    }

    // Long-press options for a video playlist row: open / rename / delete.
    private fun openVideoPlaylistContextMenu(playlistId: Long, name: String) {
        val items = listOf(
            XMBContextMenuItem("open_video_playlist", "Open"),
            XMBContextMenuItem("rename_video_playlist", "Rename Playlist"),
            XMBContextMenuItem("delete_video_playlist", "Delete Playlist", isDestructive = true),
        )
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(name, items, videoPlaylistId = playlistId)) }
    }

    // Opens the △ options menu for a Video row. Returns true when [item] is a video row it owns
    // (a video file, a library card, a playlist row, or a video-app row), so the generic
    // Y/long-press handler can stop.
    private fun openVideoContextMenu(item: XMBItem): Boolean {
        if (currentCategory()?.id != BuiltInCategory.VIDEO) return false
        return when {
            item.type == XMBItemType.VIDEO_FILE && item.id.startsWith("vid_") -> {
                openVideoFileContextMenu(item.id.removePrefix("vid_"), item.title); true
            }
            item.type == XMBItemType.VIDEO_FOLDER && item.id.startsWith("vlib_") -> {
                openVideoLibraryContextMenu(item.id.removePrefix("vlib_"), item.title); true
            }
            item.type == XMBItemType.PLAYLIST && item.playlistId != null -> {
                openVideoPlaylistContextMenu(item.playlistId, item.title); true
            }
            _uiState.value.videoNav == VideoNav.VideoApps && item.packageName != null -> {
                openAppContextMenu(item, categoryIdOverride = VIDEO_APPS_CATEGORY_ID); true
            }
            else -> false
        }
    }

    // Options for a single video file. Favorite label + "Remove from this Playlist" reflect the
    // current state/context. Fetches the video first so the favorite label is correct.
    private fun openVideoFileContextMenu(videoId: String, title: String) {
        viewModelScope.launch {
            val video = videoRepository.getVideo(videoId) ?: return@launch
            val inPlaylist = _uiState.value.videoNav is VideoNav.Playlist
            val items = buildList {
                add(XMBContextMenuItem("video_play", "Play"))
                if (video.resumePositionMs > 0) add(XMBContextMenuItem("video_resume", "Resume"))
                add(XMBContextMenuItem("video_favorite", if (video.isFavorite) "Remove from Favorites" else "Add to Favorites"))
                add(XMBContextMenuItem("video_add_playlist", "Add to Playlist"))
                if (inPlaylist) add(XMBContextMenuItem("video_remove_playlist", "Remove from this Playlist", isDestructive = true))
                add(XMBContextMenuItem("video_details", "Details"))
                add(XMBContextMenuItem("video_remove", "Remove From Library", isDestructive = true))
            }
            _uiState.update { it.copy(activeContextMenu = XMBContextMenu(title, items, videoFileId = videoId)) }
        }
    }

    private fun handleVideoFileAction(videoId: String, itemId: String) {
        when (itemId) {
            "video_play", "video_resume", "video_details" ->
                _uiState.update { it.copy(activeVideoId = videoId) }
            "video_favorite" -> appAction {
                val v = videoRepository.getVideo(videoId) ?: return@appAction
                videoRepository.setFavorite(videoId, !v.isFavorite)
            }
            "video_add_playlist" -> openVideoPlaylistPicker(videoId)
            "video_remove_playlist" -> (_uiState.value.videoNav as? VideoNav.Playlist)?.let { nav ->
                appAction { videoRepository.removeVideoFromPlaylist(nav.id, videoId) }
            }
            "video_remove" -> appAction { videoRepository.removeVideo(videoId) }
        }
    }

    // Second-level menu: the playlists a video can be added to (checkmarks show membership), plus
    // "Create New Playlist". Stays open while toggling so several can be picked at once.
    private fun openVideoPlaylistPicker(videoId: String, selectIndex: Int = 0) {
        viewModelScope.launch {
            val playlists = videoRepository.observePlaylists().first()
            val memberOf = videoRepository.getPlaylistIdsForVideo(videoId).toSet()
            val items = buildList {
                playlists.forEach { pl -> add(XMBContextMenuItem("vpl_${pl.id}", pl.name, checked = pl.id in memberOf)) }
                add(XMBContextMenuItem("vpl_new", "Create New Playlist"))
            }
            _uiState.update { it.copy(
                activeContextMenu = XMBContextMenu(
                    title = "Add to Playlist",
                    items = items,
                    selectedIndex = selectIndex.coerceIn(0, items.size - 1),
                    videoPlaylistPickerVideoId = videoId,
                )
            )}
        }
    }

    // Options for a video library card: open, scan, or manage in Settings.
    private fun openVideoLibraryContextMenu(libraryId: String, name: String) {
        val items = listOf(
            XMBContextMenuItem("video_lib_open", "Open"),
            XMBContextMenuItem("video_lib_manage", "Manage in Settings"),
        )
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(name, items, videoLibraryId = libraryId)) }
    }

    private fun handleVideoLibraryAction(libraryId: String, itemId: String) {
        when (itemId) {
            "video_lib_open" -> {
                val name = _uiState.value.currentItems.firstOrNull { it.id == "vlib_$libraryId" }?.title.orEmpty()
                openVideoView(VideoNav.Library(libraryId, name))
            }
            "video_lib_manage" -> _uiState.update { it.copy(activeSettingsScreen = "settings_video") }
        }
    }

    private fun handleVideoPlaylistRowAction(playlistId: Long, itemId: String) {
        when (itemId) {
            "open_video_playlist" -> {
                val name = _uiState.value.currentItems.firstOrNull { it.playlistId == playlistId }?.title.orEmpty()
                openVideoView(VideoNav.Playlist(playlistId, name))
            }
            "rename_video_playlist" -> promptRenameVideoPlaylist(playlistId)
            "delete_video_playlist" -> appAction {
                videoRepository.deletePlaylist(playlistId)
                if ((_uiState.value.videoNav as? VideoNav.Playlist)?.id == playlistId) openVideoView(VideoNav.Playlists)
            }
        }
    }

    // ── Photo ───────────────────────────────────────────────────────────────────

    // Library (Album) list drives the Photo root; re-render the root when it changes.
    private fun observePhoto() {
        viewModelScope.launch {
            photoRepository.observeLibraries().collect { libraries ->
                _uiState.update { it.copy(photoLibraries = libraries) }
                if (currentCategory()?.id == BuiltInCategory.PHOTO &&
                    _uiState.value.photoNav == PhotoNav.Root
                ) {
                    _uiState.update { it.copy(currentItems = photoRootItems()) }
                }
            }
        }
    }

    // Whether the device can open a camera app. Checked once (the set of camera apps doesn't
    // change while PFP is on screen) so the Photo root never shows a broken Camera item.
    private val cameraAvailable: Boolean by lazy {
        runCatching {
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
    }

    // Launches the system camera app (no result expected, no camera permission needed — the
    // standard safe hand-off). Failure is logged, never crashes the shell.
    private fun launchCamera() {
        runCatching {
            context.startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Timber.w(it, "Could not launch a camera app") }
    }

    // Photo root, PSP-style: Camera (when a camera app exists) and Albums first, then the Photo Apps
    // counterpart directly above the "Photos" memory card (second-to-bottom), with the "Add Photo
    // Library" row last — it disappears once a library has been scanned (further libraries are added
    // from Settings → Photo).
    private fun photoRootItems(): List<XMBItem> {
        val libraries = _uiState.value.photoLibraries
        val totalPhotos = libraries.sumOf { it.photoCount }
        val hasScannedLibrary = libraries.any { it.lastScannedAt != null }
        return buildList {
            if (cameraAvailable) {
                add(
                    XMBItem(
                        id       = CAMERA_ITEM_ID,
                        title    = "Camera",
                        subtitle = "Open the camera",
                        type     = XMBItemType.CAMERA,
                    )
                )
            }
            add(
                XMBItem(
                    id       = PHOTO_ALBUMS_ITEM_ID,
                    title    = "Albums",
                    subtitle = "${libraries.size} ${if (libraries.size == 1) "album" else "albums"}",
                    type     = XMBItemType.PHOTO_ALBUMS,
                )
            )
            // Photo Apps counterpart, sitting directly above the memory card.
            add(
                XMBItem(
                    id       = PHOTO_APPS_ITEM_ID,
                    title    = "Photo Apps",
                    subtitle = "Open your installed photo apps",
                    type     = XMBItemType.PHOTO_APPS,
                )
            )
            add(
                XMBItem(
                    id       = ALL_PHOTOS_ITEM_ID,
                    title    = "Photos",
                    subtitle = "$totalPhotos ${if (totalPhotos == 1) "photo" else "photos"}",
                    coverUri = MEMORY_CARD_ASSET_URI,
                    type     = XMBItemType.MEMORY_CARD,
                )
            )
            // Getting-started prompt: opens Settings → Photo. Drops away once a root has been
            // scanned (even if it found no photos), since the root is then managed in Settings.
            if (!hasScannedLibrary) add(addPhotoLibraryItem())
        }
    }

    private fun addPhotoLibraryItem(): XMBItem = XMBItem(
        id       = ADD_PHOTO_LIBRARY_ITEM_ID,
        title    = "Add Photo Library",
        subtitle = "Set your Photo root folder in Settings to get started",
        type     = XMBItemType.ADD_ACTION,
    )

    // Photo Apps: the apps the user added (stored under a dedicated pseudo-category so they don't
    // mix with the built-in Photo category), plus an "Add Photo Apps" row. Mirrors Music/Video Apps.
    private suspend fun photoAppItems(): List<XMBItem> {
        val apps = appCategoryRepository.appsForCategory(PHOTO_APPS_CATEGORY_ID)
            .notHiddenAt(HideLocationType.CATEGORY, PHOTO_APPS_CATEGORY_ID)
        val appItems = apps.map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
        return appItems + XMBItem(
            id       = ADD_PHOTO_APPS_ITEM_ID,
            title    = "Add Photo Apps",
            subtitle = "Pick installed apps to show here",
            type     = XMBItemType.ADD_ACTION,
        )
    }

    // One folder card per Album, drillable into its photos. The root folder is managed in
    // Settings → Photo, so there is no add row here.
    private fun photoAlbumItems(libraries: List<com.playfieldportal.core.domain.model.PhotoLibrary>): List<XMBItem> {
        val rows = libraries.map { lib ->
            XMBItem(
                id       = "plib_${lib.id}",
                title    = lib.displayName,
                subtitle = "${lib.photoCount} ${if (lib.photoCount == 1) "photo" else "photos"}",
                type     = XMBItemType.PHOTO_FOLDER,
            )
        }
        return rows.ifEmpty {
            listOf(
                XMBItem(
                    id = EMPTY_CATEGORY_ITEM_ID,
                    title = "No albums yet",
                    subtitle = "Set a root folder in Settings → Photo",
                    type = XMBItemType.EMPTY,
                ),
            )
        }
    }

    private fun List<com.playfieldportal.core.domain.model.Photo>.toPhotoItems(): List<XMBItem> =
        map { photo ->
            XMBItem(
                id       = "pho_${photo.id}",
                title    = photo.displayName,
                subtitle = photoSubtitle(photo),
                type     = XMBItemType.PHOTO_FILE,
                mediaUri = photo.uri,
                mimeType = photo.mimeType,
                coverUri = photo.thumbnailUri,
            )
        }

    // "4032×3024  ·  Jul 14, 2026" — whichever parts are known; null when neither is.
    private fun photoSubtitle(photo: com.playfieldportal.core.domain.model.Photo): String? {
        val date = photo.displayDateMs?.let {
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
        }
        return listOfNotNull(photo.resolutionLabel, date).joinToString("  ·  ").ifEmpty { null }
    }

    private fun setPhotoItems(
        photos: List<com.playfieldportal.core.domain.model.Photo>,
        emptyItem: XMBItem,
    ) {
        val items = if (photos.isEmpty()) listOf(emptyItem) else photos.toPhotoItems()
        _uiState.update { it.copy(currentItems = items) }
    }

    private fun emptyAllPhotosItem(): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "No photos found",
        subtitle = "Add a photo library and scan it",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyLibraryPhotosItem(): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "No photos in this album",
        subtitle = "Scan it from its ⚙ Options menu or in Settings → Photo",
        type     = XMBItemType.EMPTY,
    )

    // Handles A/Cross on any Photo row. Returns true when [item] is a Photo row it owns.
    private fun handlePhotoSelection(item: XMBItem): Boolean = when {
        item.id == ALL_PHOTOS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openPhotoView(PhotoNav.AllPhotos); true }
        item.id == PHOTO_ALBUMS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openPhotoView(PhotoNav.Albums); true }
        item.id == PHOTO_APPS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openPhotoView(PhotoNav.PhotoApps); true }
        item.id == CAMERA_ITEM_ID -> { menuSound.play(MenuSound.LAUNCH); launchCamera(); true }
        item.id == ADD_PHOTO_LIBRARY_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            _uiState.update { it.copy(activeSettingsScreen = "settings_photo") }
            true
        }
        item.id == ADD_PHOTO_APPS_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            openAppPicker(AppPickerTarget.CategoryShortcuts(PHOTO_APPS_CATEGORY_ID), "Add Photo Apps")
            true
        }
        // Photo-app rows launch the app.
        _uiState.value.photoNav == PhotoNav.PhotoApps && item.packageName != null -> {
            menuSound.play(MenuSound.LAUNCH); appCategoryRepository.launch(item.packageName); true
        }
        item.type == XMBItemType.PHOTO_FOLDER && item.id.startsWith("plib_") -> {
            menuSound.play(MenuSound.SELECT)
            openPhotoView(PhotoNav.Library(item.id.removePrefix("plib_"), item.title))
            true
        }
        item.type == XMBItemType.PHOTO_FILE && item.id.startsWith("pho_") -> {
            menuSound.play(MenuSound.SELECT)
            openPhotoViewer(item.id.removePrefix("pho_"))
            true
        }
        else -> false
    }

    private fun photoNavKey(nav: PhotoNav): String = when (nav) {
        PhotoNav.Root       -> "root"
        PhotoNav.AllPhotos  -> "all"
        PhotoNav.Albums     -> "albums"
        PhotoNav.PhotoApps  -> "apps"
        is PhotoNav.Library -> "library_${nav.id}"
    }

    private fun openPhotoView(nav: PhotoNav) = navigateRememberingCursor { it.copy(photoNav = nav) }

    private fun closePhotoView() = openPhotoView(PhotoNav.Root)

    // Opens the fullscreen viewer for a photo, scoped to the list it was opened from so L1/R1
    // pages through the same set the user was browsing.
    private fun openPhotoViewer(photoId: String, wallpaperPreview: Boolean = false) {
        val libraryId = (_uiState.value.photoNav as? PhotoNav.Library)?.id
        _uiState.update {
            it.copy(activePhotoViewer = PhotoViewerRequest(photoId, libraryId, openWallpaperPreview = wallpaperPreview))
        }
    }

    fun onClosePhotoViewer() {
        _uiState.update { it.copy(activePhotoViewer = null, pendingPhotoViewerAction = null) }
    }

    fun consumePhotoViewerAction() {
        _uiState.update { it.copy(pendingPhotoViewerAction = null) }
    }

    // Opens the △ options menu for a Photo row. Returns true when [item] is a photo row it owns
    // (a photo file or an Album card), so the generic menus don't also fire.
    private fun openPhotoContextMenu(item: XMBItem): Boolean {
        if (currentCategory()?.id != BuiltInCategory.PHOTO) return false
        return when {
            item.type == XMBItemType.PHOTO_FILE && item.id.startsWith("pho_") -> {
                openPhotoFileContextMenu(item.id.removePrefix("pho_"), item.title); true
            }
            item.type == XMBItemType.PHOTO_FOLDER && item.id.startsWith("plib_") -> {
                openPhotoLibraryContextMenu(item.id.removePrefix("plib_"), item.title); true
            }
            _uiState.value.photoNav == PhotoNav.PhotoApps && item.packageName != null -> {
                openAppContextMenu(item, categoryIdOverride = PHOTO_APPS_CATEGORY_ID); true
            }
            else -> false
        }
    }

    // Options for a single photo row. Viewing-related options (zoom, rotate, wallpaper) live in
    // the fullscreen viewer's own Options menu; the list row only opens/removes.
    private fun openPhotoFileContextMenu(photoId: String, title: String) {
        val items = listOf(
            XMBContextMenuItem("photo_open", "Open"),
            XMBContextMenuItem("photo_set_wallpaper", "Set as Launcher Wallpaper"),
            XMBContextMenuItem("photo_remove", "Remove From Library", isDestructive = true),
        )
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(title, items, photoFileId = photoId)) }
    }

    private fun handlePhotoFileAction(photoId: String, itemId: String) {
        when (itemId) {
            "photo_open"          -> openPhotoViewer(photoId)
            // Opens the viewer with the wallpaper preview already up — apply/cancel from there.
            "photo_set_wallpaper" -> openPhotoViewer(photoId, wallpaperPreview = true)
            "photo_remove"        -> appAction { photoRepository.removePhoto(photoId) }
        }
    }

    // Options for an Album card: open, scan, or manage (rename / change folder / remove) in Settings.
    private fun openPhotoLibraryContextMenu(libraryId: String, name: String) {
        val items = listOf(
            XMBContextMenuItem("photo_lib_open", "Open"),
            XMBContextMenuItem("photo_lib_scan", "Scan Album"),
            XMBContextMenuItem("photo_lib_manage", "Manage in Settings"),
        )
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(name, items, photoLibraryId = libraryId)) }
    }

    private fun handlePhotoLibraryAction(libraryId: String, itemId: String) {
        when (itemId) {
            "photo_lib_open" -> {
                val name = _uiState.value.photoLibraries.firstOrNull { it.id == libraryId }?.displayName.orEmpty()
                openPhotoView(PhotoNav.Library(libraryId, name))
            }
            "photo_lib_scan" -> scanPhotoLibrary(libraryId)
            "photo_lib_manage" -> _uiState.update { it.copy(activeSettingsScreen = "settings_photo") }
        }
    }

    // Quick scan of one Album straight from the XMB card, surfaced via the notification tray like
    // every other background scan. The library list flow refreshes the counts when it lands.
    private fun scanPhotoLibrary(libraryId: String) {
        viewModelScope.launch {
            val library = photoRepository.getLibrary(libraryId) ?: return@launch
            val taskId = "photo_scan_${library.id}"
            val notifier = BackgroundTaskNotifier(context)
            notifier.running(taskId, "Scanning ${library.displayName}", null)
            val existing = photoRepository.getPhotosForLibrary(library.id)
            photoScanner.scan(library, deep = false, existing = existing).collect { result ->
                when (result) {
                    is com.playfieldportal.feature.library.scanner.PhotoScanResult.Progress ->
                        notifier.running(taskId, "Scanning ${result.libraryName}", null)
                    is com.playfieldportal.feature.library.scanner.PhotoScanResult.Complete -> {
                        photoRepository.replacePhotosForLibrary(result.libraryId, result.photos, System.currentTimeMillis())
                        notifier.complete(taskId, "Scanned ${library.displayName}", "${result.photos.size} photos")
                    }
                    is com.playfieldportal.feature.library.scanner.PhotoScanResult.Error ->
                        notifier.failed(taskId, "Scan failed: ${library.displayName}", result.message)
                }
            }
        }
    }

    // ── Fullscreen music browser (searchable) ───────────────────────────────────
    // Opens "Music" (all tracks) or "Playlist" (playlists → a playlist's tracks) as a fullscreen,
    // searchable overlay. A collector keeps the active view in sync with the DB; query/sort changes
    // re-derive the visible rows from the cached raw list without re-hitting the DB.
    private fun openMusicBrowser(view: MusicBrowserView) {
        musicBrowserJob?.cancel()
        val title = when (view) {
            MusicBrowserView.AllMusic    -> "Music"
            MusicBrowserView.Playlists   -> "Playlists"
            is MusicBrowserView.Playlist -> view.name
        }
        _uiState.update { it.copy(musicBrowser = MusicBrowserState(view = view, title = title)) }
        musicBrowserJob = viewModelScope.launch {
            when (view) {
                MusicBrowserView.AllMusic -> musicRepository.observeAllTracks().collect { tracks ->
                    browserRawTracks = tracks; rebuildBrowserTrackRows()
                }
                is MusicBrowserView.Playlist -> musicRepository.observePlaylistTracks(view.id).collect { tracks ->
                    browserRawTracks = tracks; rebuildBrowserTrackRows()
                }
                MusicBrowserView.Playlists -> musicRepository.observePlaylists().collect { playlists ->
                    browserRawPlaylists = playlists; rebuildBrowserPlaylistRows()
                }
            }
        }
    }

    private fun MusicTrack.matchesQuery(q: String): Boolean =
        displayTitle.lowercase().contains(q) ||
            artist?.lowercase()?.contains(q) == true ||
            album?.lowercase()?.contains(q) == true

    private fun rebuildBrowserTrackRows() {
        val state = _uiState.value.musicBrowser ?: return
        val isPlaylist = state.view is MusicBrowserView.Playlist
        val q = state.query.trim().lowercase()
        val sorted = browserRawTracks.trackSorted(_uiState.value.musicSortMode)
        val filtered = if (q.isBlank()) sorted else sorted.filter { it.matchesQuery(q) }
        currentMusicTracks = filtered   // the play queue is exactly what's on screen
        val baseRows = when {
            filtered.isNotEmpty() -> filtered.toMusicItems()
            q.isNotBlank()        -> listOf(browserNoResultsItem())
            isPlaylist            -> listOf(emptyPlaylistItem())
            else                  -> listOf(emptyAllMusicItem())
        }
        val rows = if (isPlaylist) baseRows + addTracksItem() else baseRows
        val label = "Sort: ${_uiState.value.musicSortMode.label}"
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(
            rows = rows,
            selectedIndex = state.selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
            sortLabel = label,
        )) }
    }

    private fun rebuildBrowserPlaylistRows() {
        val state = _uiState.value.musicBrowser ?: return
        val q = state.query.trim().lowercase()
        val filtered = if (q.isBlank()) browserRawPlaylists
                       else browserRawPlaylists.filter { it.name.lowercase().contains(q) }
        val rows = playlistRootItems(filtered)   // playlist rows + "Create Playlist"
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(
            rows = rows,
            selectedIndex = state.selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
            sortLabel = null,
        )) }
    }

    private fun browserNoResultsItem(): XMBItem = XMBItem(
        id = EMPTY_CATEGORY_ITEM_ID, title = "No matches", subtitle = "Try a different search.",
        type = XMBItemType.EMPTY,
    )

    fun onMusicBrowserQueryChange(query: String) {
        markTouchInput()
        val state = _uiState.value.musicBrowser ?: return
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(
            query = query, selectedIndex = 0,
            scrollToTopToken = state.scrollToTopToken + 1,
        )) }
        if (state.view is MusicBrowserView.Playlists) rebuildBrowserPlaylistRows() else rebuildBrowserTrackRows()
    }

    private fun moveMusicBrowser(delta: Int) {
        val b = _uiState.value.musicBrowser ?: return
        val next = (b.selectedIndex + delta).coerceIn(0, (b.rows.size - 1).coerceAtLeast(0))
        if (next != b.selectedIndex) {
            _uiState.update { it.copy(musicBrowser = b.copy(selectedIndex = next)) }
            menuSound.play(MenuSound.SCROLL)
        }
    }

    private fun activateMusicBrowser() {
        val b = _uiState.value.musicBrowser ?: return
        handleMusicBrowserRow(b.rows.getOrNull(b.selectedIndex) ?: return)
    }

    fun onMusicBrowserActivatedAt(index: Int) {
        markTouchInput()
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(selectedIndex = index)) }
        activateMusicBrowser()
    }

    private fun handleMusicBrowserRow(item: XMBItem) {
        when {
            item.type == XMBItemType.EMPTY -> Unit
            item.id == CREATE_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); promptCreatePlaylist() }
            item.id == ADD_TRACKS_ITEM_ID -> {
                menuSound.play(MenuSound.SELECT)
                (_uiState.value.musicBrowser?.view as? MusicBrowserView.Playlist)?.let { openMusicTrackPicker(it.id) }
            }
            item.type == XMBItemType.PLAYLIST && item.playlistId != null -> {
                menuSound.play(MenuSound.SELECT)
                openMusicBrowser(MusicBrowserView.Playlist(item.playlistId, item.title))
            }
            item.type == XMBItemType.MUSIC_TRACK -> { menuSound.play(MenuSound.SELECT); openMusicPlayerForItem(item) }
        }
    }

    private fun openMusicBrowserContextMenu() {
        val b = _uiState.value.musicBrowser ?: return
        val item = b.rows.getOrNull(b.selectedIndex) ?: return
        when {
            item.type == XMBItemType.MUSIC_TRACK -> openMusicTrackContextMenu(item)
            item.type == XMBItemType.PLAYLIST && item.playlistId != null ->
                openPlaylistRowContextMenu(item.playlistId, item.title)
        }
    }

    fun onMusicBrowserLongPressAt(index: Int) {
        markTouchInput()
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(selectedIndex = index)) }
        openMusicBrowserContextMenu()
    }

    fun onMusicBrowserBack() {
        markTouchInput()
        val b = _uiState.value.musicBrowser ?: return
        menuSound.play(MenuSound.BACK)
        when (b.view) {
            // A playlist's tracks back out to the playlists list; everything else closes the browser.
            is MusicBrowserView.Playlist -> openMusicBrowser(MusicBrowserView.Playlists)
            else -> closeMusicBrowser()
        }
    }

    private fun closeMusicBrowser() {
        musicBrowserJob?.cancel(); musicBrowserJob = null
        val view = _uiState.value.musicBrowser?.view
        browserRawTracks = emptyList(); browserRawPlaylists = emptyList()
        _uiState.update { it.copy(musicBrowser = null) }
        // Re-anchor the XMB cursor on the row the browser was opened from, so the reveal is
        // seamless even if the root list changed shape while the browser was open.
        if (currentCategory()?.id == BuiltInCategory.MUSIC && _uiState.value.musicNav == MusicNav.Root) {
            val targetId = when (view) {
                is MusicBrowserView.Playlists, is MusicBrowserView.Playlist -> PLAYLISTS_ITEM_ID
                else -> ALL_MUSIC_ITEM_ID
            }
            val idx = _uiState.value.currentItems.indexOfFirst { it.id == targetId }
            if (idx >= 0) _uiState.update { it.copy(selectedItemIndex = idx) }
        }
    }

    /** Touch: the browser's Sort pill — same as the X button. */
    fun onMusicBrowserSortTapped() {
        markTouchInput()
        cycleSort()
    }

    /** Touch: the browser's Options pill — opens the context menu for the highlighted row,
     *  same as the Y button. */
    fun onMusicBrowserOptionsTapped() {
        markTouchInput()
        openMusicBrowserContextMenu()
    }

    // Playlist context for a track's options menu, resolved from the browser or the inline view.
    private fun currentPlaylistContextId(): Long? =
        (_uiState.value.musicBrowser?.view as? MusicBrowserView.Playlist)?.id
            ?: (_uiState.value.musicNav as? MusicNav.Playlist)?.id

    // Handles A/Cross on any Music row. Returns true when [item] is a Music row it owns. Empty-state
    // rows are consumed silently; everything else plays its own select/launch sound.
    private fun handleMusicSelection(item: XMBItem): Boolean = when {
        item.type == XMBItemType.EMPTY -> true   // not selectable
        item.id == NOW_PLAYING_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            if (_uiState.value.musicPlayback.track != null) _uiState.update { it.copy(musicPlayerVisible = true) }
            true
        }
        // "Music" and "Playlist" open the fullscreen, searchable browser instead of the inline list.
        item.id == PLAYLISTS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicBrowser(MusicBrowserView.Playlists); true }
        item.id == ALL_MUSIC_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicBrowser(MusicBrowserView.AllMusic); true }
        item.id == MUSIC_APPS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicView(MusicNav.MusicApps); true }
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
        // Inside a playlist (inline or browser), offer "Remove from this Playlist"; the playlist id
        // rides on the menu so the action knows which playlist.
        val playlistId = currentPlaylistContextId()
        val items = buildList {
            add(XMBContextMenuItem("play", "Play"))
            add(XMBContextMenuItem("play_background", "Play in Background"))
            add(XMBContextMenuItem("add_to_playlist", "Add to Playlist"))
            if (playlistId != null) {
                add(XMBContextMenuItem("remove_from_playlist", "Remove from this Playlist", isDestructive = true))
            }
            add(XMBContextMenuItem("remove_track", "Remove From Library", isDestructive = true))
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title        = item.title,
                items        = items,
                musicTrackId = item.id.removePrefix("mt_"),
                playlistId   = playlistId,
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
            if (dialog.videoContext) {
                if (renameId != null) {
                    videoRepository.renamePlaylist(renameId, name)
                } else {
                    val id = videoRepository.createPlaylist(name)
                    dialog.forVideoId?.let { videoRepository.addVideoToPlaylist(id, it) }
                }
            } else if (renameId != null) {
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
            // Video lists sort, except the intrinsically-ordered ones (recency / manual playlist).
            cat.id == BuiltInCategory.VIDEO &&
                (s.videoNav == VideoNav.AllVideos || s.videoNav == VideoNav.Favorites ||
                    s.videoNav is VideoNav.Library) -> VIDEO_SORTS
            cat.id == BuiltInCategory.GAMES &&
                (s.selectedPlatformId != null || s.selectedCollectionId != null) -> GAME_SORTS
            cat.isGamingCategory -> GAME_SORTS
            else -> null
        }
    }

    /** Touch: the status-bar sort chip — cycles the sort order, same as X/Square. */
    fun onSortLabelTapped() {
        markTouchInput()
        cycleSort()
    }

    private fun cycleSort() {
        // The fullscreen music browser sorts its own track views (not the playlists list).
        _uiState.value.musicBrowser?.let { browser ->
            if (browser.view is MusicBrowserView.Playlists) return
            val next = MUSIC_SORTS[(MUSIC_SORTS.indexOf(_uiState.value.musicSortMode).coerceAtLeast(0) + 1) % MUSIC_SORTS.size]
            menuSound.play(MenuSound.SYSTEM_BROWSE)
            _uiState.update { it.copy(
                musicSortMode = next,
                musicBrowser = it.musicBrowser?.copy(
                    selectedIndex = 0,
                    scrollToTopToken = browser.scrollToTopToken + 1,
                ),
            )}
            rebuildBrowserTrackRows()
            return
        }
        val cycle = activeSortContext() ?: return
        val isMusic = cycle === MUSIC_SORTS
        val isVideo = cycle === VIDEO_SORTS
        val current = when {
            isMusic -> _uiState.value.musicSortMode
            isVideo -> _uiState.value.videoSortMode
            else    -> _uiState.value.gameSortMode
        }
        val next = cycle[(cycle.indexOf(current).coerceAtLeast(0) + 1) % cycle.size]
        menuSound.play(MenuSound.SYSTEM_BROWSE)
        // Re-sorting moves the cursor back to the top item so the user sees the new ordering from
        // the start, and bumps the scroll token so the list snaps to the top every time (not just
        // the first sort after the cursor moved).
        _uiState.update {
            (when {
                isMusic -> it.copy(musicSortMode = next)
                isVideo -> it.copy(videoSortMode = next)
                else    -> it.copy(gameSortMode = next)
            }).copy(selectedItemIndex = 0, scrollToTopToken = it.scrollToTopToken + 1)
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

    // The parent label for the two-pane flyout, non-null whenever drilled into ANY sub-item — a Games
    // sub-item (platform card / All Games / Favorites / collection) or a Music sub-view (Music Apps /
    // a playlist / All Music). Null = top level, normal single-column list.
    private fun computeDrillTitle(): String? {
        val s = _uiState.value
        // Music sub-navigation is a drill-in too — a non-null title makes it show the two-pane flyout.
        val musicTitle = when (val nav = s.musicNav) {
            MusicNav.MusicApps   -> "Music Apps"
            MusicNav.AllMusic    -> "Music"
            MusicNav.Playlists   -> "Playlist"
            is MusicNav.Playlist -> nav.name
            MusicNav.Root        -> null
        }
        if (musicTitle != null) return musicTitle
        // Video sub-navigation is a drill-in too — a non-null title shows the two-pane flyout.
        val videoTitle = when (val nav = s.videoNav) {
            VideoNav.AllVideos       -> "All Videos"
            VideoNav.Collections     -> "Collections"
            VideoNav.RecentlyWatched -> "Recently Watched"
            VideoNav.Favorites       -> "Favorites"
            VideoNav.Playlists       -> "Playlists"
            is VideoNav.Playlist     -> nav.name
            VideoNav.Libraries       -> "Video Libraries"
            is VideoNav.Library      -> nav.name
            VideoNav.VideoApps       -> "Video Apps"
            VideoNav.Root            -> null
        }
        if (videoTitle != null) return videoTitle
        // Photo sub-navigation is a drill-in too.
        val photoTitle = when (val nav = s.photoNav) {
            PhotoNav.AllPhotos  -> "All Photos"
            PhotoNav.Albums     -> "Albums"
            PhotoNav.PhotoApps  -> "Photo Apps"
            is PhotoNav.Library -> nav.name
            PhotoNav.Root       -> null
        }
        if (photoTitle != null) return photoTitle
        // Discord Social sub-navigation.
        val socialTitle = when (s.socialNav) {
            SocialNav.Account          -> "Account"
            SocialNav.Friends          -> "Friends"
            SocialNav.ActivitySettings -> "Activity Settings"
            SocialNav.DiscordSettings  -> "Discord Settings"
            SocialNav.Root             -> null
        }
        if (socialTitle != null) return socialTitle
        return when {
            s.selectedCollectionId != null ->
                s.collections.firstOrNull { it.id == s.selectedCollectionId }?.name ?: "Collection"
            s.selectedPlatformId == ALL_GAMES_PLATFORM_ID -> "All Games"
            s.selectedPlatformId == FAVORITES_PLATFORM_ID -> "Favorites"
            s.selectedPlatformId != null ->
                enabledCards.firstOrNull { it.platformId == s.selectedPlatformId }?.displayName
                    ?: platformCache[s.selectedPlatformId]?.name
                    ?: s.selectedPlatformId
            else -> null
        }
    }

    // Icon-only sibling lists for the deepest drill level, so the flyout's left column always shows
    // the current level's peers (a library among libraries, an album among albums, a playlist among
    // playlists) — mirroring how the Games flyout shows the console cross.
    private fun videoLibrarySiblings(): List<XMBItem> =
        _uiState.value.videoLibraries.map { XMBItem(id = "vlib_${it.id}", title = it.displayName, type = XMBItemType.VIDEO_FOLDER) }

    private fun photoAlbumSiblings(): List<XMBItem> =
        _uiState.value.photoLibraries.map { XMBItem(id = "plib_${it.id}", title = it.displayName, type = XMBItemType.PHOTO_FOLDER) }

    private fun musicPlaylistSiblings(): List<XMBItem> =
        _uiState.value.musicPlaylists.map { XMBItem(id = "pl_${it.id}", title = it.name, playlistId = it.id, type = XMBItemType.PLAYLIST) }

    private fun videoPlaylistSiblings(): List<XMBItem> =
        _uiState.value.videoPlaylists.map { XMBItem(id = "vpl_${it.id}", title = it.name, playlistId = it.id, type = XMBItemType.PLAYLIST) }

    // The sibling icon column for the flyout's left side. In the Main Game category these are the
    // memory-card root items (All Games / Favorites / collections / consoles); the currently
    // drilled-into one is returned as the centred index. Other categories fall back to just the
    // single parent so the flyout still shows one icon.
    private fun computeDrillSiblings(category: Category?): Pair<List<XMBItem>, Int> {
        val s = _uiState.value
        // Music sub-navigation: the left column is the Music root's sections (Playlist / Music Apps /
        // Music), with the drilled-into one centred on the arrow.
        if (s.musicNav != MusicNav.Root) {
            // Inside a specific playlist: peers are the other playlists.
            (s.musicNav as? MusicNav.Playlist)?.let { nav ->
                val pls = musicPlaylistSiblings()
                if (pls.isNotEmpty()) return pls to pls.indexOfFirst { it.playlistId == nav.id }.coerceAtLeast(0)
            }
            val sibs = musicRootItems().filter {
                it.type == XMBItemType.PLAYLIST || it.type == XMBItemType.MUSIC_APPS ||
                    it.type == XMBItemType.MEMORY_CARD
            }
            val idx = sibs.indexOfFirst { sib ->
                when (s.musicNav) {
                    MusicNav.MusicApps -> sib.type == XMBItemType.MUSIC_APPS
                    MusicNav.AllMusic  -> sib.type == XMBItemType.MEMORY_CARD
                    else               -> sib.type == XMBItemType.PLAYLIST   // Playlists / a Playlist
                }
            }.coerceAtLeast(0)
            return sibs to idx
        }
        // Video sub-navigation. Two levels now: the Collections children (Recently Watched /
        // Favorites / Playlists / a Playlist) show the Collections sub-list as their sibling column;
        // everything else shows the Video root's sections (All Videos / Collections / Video
        // Libraries / Video Apps). The drilled-into one is centred on the arrow.
        if (s.videoNav != VideoNav.Root) {
            // Deepest levels show their own peers: a library among the libraries, a playlist among
            // the playlists.
            (s.videoNav as? VideoNav.Library)?.let { nav ->
                val libs = videoLibrarySiblings()
                if (libs.isNotEmpty()) return libs to libs.indexOfFirst { it.id == "vlib_${nav.id}" }.coerceAtLeast(0)
            }
            (s.videoNav as? VideoNav.Playlist)?.let { nav ->
                val pls = videoPlaylistSiblings()
                if (pls.isNotEmpty()) return pls to pls.indexOfFirst { it.playlistId == nav.id }.coerceAtLeast(0)
            }
            // The three Collections views show the Collections sub-list (distinct icons per view).
            if (s.videoNav.isVideoCollectionChild || s.videoNav is VideoNav.Playlist) {
                val sibs = videoCollectionsItems()
                val idx = sibs.indexOfFirst { sib ->
                    when (s.videoNav) {
                        VideoNav.RecentlyWatched -> sib.type == XMBItemType.VIDEO_RECENT
                        VideoNav.Favorites       -> sib.type == XMBItemType.VIDEO_FAVORITES
                        else                     -> sib.type == XMBItemType.PLAYLIST  // Playlists / a Playlist
                    }
                }.coerceAtLeast(0)
                return sibs to idx
            }
            // Root sections: All Videos / Collections / Video Libraries / Video Apps.
            val sibs = videoRootItems().filter {
                it.type == XMBItemType.MEMORY_CARD || it.type == XMBItemType.VIDEO_COLLECTIONS ||
                    it.type == XMBItemType.VIDEO_LIBRARY || it.type == XMBItemType.VIDEO_APPS
            }
            val idx = sibs.indexOfFirst { sib ->
                when (s.videoNav) {
                    VideoNav.AllVideos   -> sib.type == XMBItemType.MEMORY_CARD
                    VideoNav.Collections -> sib.type == XMBItemType.VIDEO_COLLECTIONS
                    VideoNav.VideoApps   -> sib.type == XMBItemType.VIDEO_APPS
                    else                 -> sib.type == XMBItemType.VIDEO_LIBRARY  // Libraries (list view)
                }
            }.coerceAtLeast(0)
            return sibs to idx
        }
        // Photo sub-navigation: the left column is the Photo root's drillable sections (the All
        // Photos memory card and Albums), with the drilled-into one centred on the arrow. An open
        // Album belongs to the Albums section, like a Video library under Video Libraries.
        if (s.photoNav != PhotoNav.Root) {
            // Inside a specific album: peers are the other albums.
            (s.photoNav as? PhotoNav.Library)?.let { nav ->
                val albums = photoAlbumSiblings()
                if (albums.isNotEmpty()) return albums to albums.indexOfFirst { it.id == "plib_${nav.id}" }.coerceAtLeast(0)
            }
            val sibs = photoRootItems().filter {
                it.type == XMBItemType.MEMORY_CARD || it.type == XMBItemType.PHOTO_ALBUMS ||
                    it.type == XMBItemType.PHOTO_APPS
            }
            val idx = sibs.indexOfFirst { sib ->
                when (s.photoNav) {
                    PhotoNav.AllPhotos -> sib.type == XMBItemType.MEMORY_CARD
                    PhotoNav.PhotoApps -> sib.type == XMBItemType.PHOTO_APPS
                    else               -> sib.type == XMBItemType.PHOTO_ALBUMS  // Albums (list view)
                }
            }.coerceAtLeast(0)
            return sibs to idx
        }
        // Discord Social: drilled into the account → the account is the sibling; drilled deeper →
        // the hub's drillable rows are the siblings.
        if (s.socialNav != SocialNav.Root) {
            return when (s.socialNav) {
                SocialNav.Account -> listOf(
                    XMBItem(id = "social_account", title = "", coverUri = s.socialAccountAvatarUrl, type = XMBItemType.SOCIAL_ACCOUNT),
                ) to 0
                SocialNav.Friends -> {
                    val hub = socialHubSiblings()
                    hub to hub.indexOfFirst { it.type == XMBItemType.SOCIAL_FRIENDS }.coerceAtLeast(0)
                }
                SocialNav.ActivitySettings -> {
                    val hub = socialHubSiblings()
                    hub to hub.indexOfFirst { it.type == XMBItemType.SOCIAL_ACTIVITY_SETTINGS }.coerceAtLeast(0)
                }
                SocialNav.DiscordSettings -> {
                    val hub = socialHubSiblings()
                    hub to hub.indexOfFirst { it.type == XMBItemType.SOCIAL_DISCORD_SETTINGS }.coerceAtLeast(0)
                }
                SocialNav.Root -> emptyList<XMBItem>() to 0
            }
        }
        if (category?.id == BuiltInCategory.GAMES) {
            val sibs = memoryCardItems().filter {
                it.type == XMBItemType.ALL_GAMES || it.type == XMBItemType.FAVORITES ||
                    it.type == XMBItemType.MEMORY_CARD || it.type == XMBItemType.COLLECTION
            }
            val idx = sibs.indexOfFirst { sib ->
                when {
                    s.selectedPlatformId == ALL_GAMES_PLATFORM_ID -> sib.type == XMBItemType.ALL_GAMES
                    s.selectedPlatformId == FAVORITES_PLATFORM_ID -> sib.type == XMBItemType.FAVORITES
                    s.selectedCollectionId != null               -> sib.collectionId == s.selectedCollectionId
                    s.selectedPlatformId != null                 -> sib.platformId == s.selectedPlatformId
                    else -> false
                }
            }.coerceAtLeast(0)
            return sibs to idx
        }
        // Custom gaming category drilled into a collection — just show the single collection icon.
        val parent = XMBItem(id = "drill_parent", title = computeDrillTitle().orEmpty(), type = XMBItemType.COLLECTION)
        return listOf(parent) to 0
    }

    // Status-bar hint for the current list ("Sort: Title"), or null when the list isn't sortable.
    private fun currentSortLabel(): String? {
        val cycle = activeSortContext() ?: return null
        val mode = when {
            cycle === MUSIC_SORTS -> _uiState.value.musicSortMode
            cycle === VIDEO_SORTS -> _uiState.value.videoSortMode
            else                  -> _uiState.value.gameSortMode
        }
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
                iconKey      = collection.iconKey,
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
            subtitle     = platformEmulatorLabel(g),
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
        // PSP-authentic: one theme color across the whole XMB — no per-category wave re-tint.
        _uiState.update { it.copy(themeColors = baseThemeColors) }
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
        markControllerInput()
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

        // ── Fullscreen music browser captures input. Below the context-menu / player / dialog
        //    branches above, so a menu (Y) or the player opened from it wins. ─────────────────
        if (state.musicBrowser != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP    -> moveMusicBrowser(-1)
                GamepadAction.NAVIGATE_DOWN  -> moveMusicBrowser(+1)
                GamepadAction.SELECT         -> activateMusicBrowser()
                GamepadAction.BACK           -> onMusicBrowserBack()
                GamepadAction.BUTTON_Y,
                GamepadAction.LONG_PRESS     -> openMusicBrowserContextMenu()
                GamepadAction.OPEN_TASK_TRAY,
                GamepadAction.CHANGE_SORT    -> cycleSort()
                else -> Unit
            }
            return
        }

        // ── Boot sequence overlay swallows input until it finishes/auto-completes ──
        if (state.showBootSequence) return

        // ── Overlays (innermost wins) ──────────────────────────────────────────
        when {
            state.activePhotoViewer != null -> {
                // Forward everything so the fullscreen photo viewer can handle its own controls
                // (options menu, zoom/pan, wallpaper preview) before popping back to the XMB.
                _uiState.update { it.copy(pendingPhotoViewerAction = action) }
                return
            }
            state.activeVideoId != null -> {
                // Forward everything so the Video Detail page (and its player overlay) can handle
                // input and close its own layers before popping back to the XMB.
                _uiState.update { it.copy(pendingVideoDetailAction = action) }
                return
            }
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
            state.activeDiscordLogin -> {
                // Back cancels the QR overlay; its own Compose UI handles taps/buttons.
                if (action == GamepadAction.BACK) onDiscordLoginClosed()
                return
            }
        }

        // Defensive net: the main XMB navigation below must NEVER run while any overlay,
        // menu, or modal dialog is on screen. Each case above returns for its own handling;
        // this guards against a future overlay being added without its own branch.
        if (state.hasBlockingOverlay) return

        when (action) {
            // Item cursor moves through the shared moveItemCursor() so touch swipes and the D-pad
            // drive identical logic; cancel auto-repeat when we hit a list boundary.
            GamepadAction.NAVIGATE_UP   -> if (!moveItemCursor(-1)) gamepadInputHandler.cancelRepeat()
            GamepadAction.NAVIGATE_DOWN -> if (!moveItemCursor(+1)) gamepadInputHandler.cancelRepeat()
            GamepadAction.NAVIGATE_LEFT -> {
                // While drilled into a sub-item, Left/Right no longer escape to other categories —
                // the user must Back out first.
                if (state.isInSubItem) { gamepadInputHandler.cancelRepeat(); return }
                val next = (state.selectedCategoryIndex - 1).coerceAtLeast(0)
                if (next != state.selectedCategoryIndex) onCategorySelected(next)
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.NAVIGATE_RIGHT -> {
                if (state.isInSubItem) { gamepadInputHandler.cancelRepeat(); return }
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
                    // Two-level video paths back out one level first.
                    state.videoNav is VideoNav.Library -> openVideoView(VideoNav.Libraries)
                    state.videoNav is VideoNav.Playlist -> openVideoView(VideoNav.Playlists)
                    state.videoNav.isVideoCollectionChild -> openVideoView(VideoNav.Collections)
                    state.videoNav != VideoNav.Root -> closeVideoView()
                    // Album drill-in backs out via the Albums list first.
                    state.photoNav is PhotoNav.Library -> openPhotoView(PhotoNav.Albums)
                    state.photoNav != PhotoNav.Root -> closePhotoView()
                    state.socialNav != SocialNav.Root -> socialBack()
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
                    item != null && openVideoContextMenu(item) -> Unit
                    item != null && openPhotoContextMenu(item) -> Unit
                    item?.gameId != null -> openGameContextMenu(item)
                    item?.collectionId != null && item.type == XMBItemType.COLLECTION -> openCollectionRowContextMenu(item.collectionId)
                    item?.type == XMBItemType.ALL_GAMES -> openAllGamesContextMenu()
                    item?.type == XMBItemType.SOCIAL_ACCOUNT -> openSocialAccountContextMenu()
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

    // The "All Games" card isn't a real Memory Card, so it gets its own single-option menu.
    private fun openAllGamesContextMenu() {
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title      = "All Games",
                items      = listOf(XMBContextMenuItem("import_pc_games", "Import PC Games")),
                isAllGames = true,
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
            // Per-location hide for the spot this game is shown in (recoverable in Hidden Items).
            currentHideLocation()?.let { (_, _, label) -> add(XMBContextMenuItem("hide_here", "Hide from $label")) }
            // Android-library apps are user-curated, so let the user remove one like any game.
            if (item.platformId == ANDROID_PLATFORM_ID && item.packageName != null && !inCollection) {
                add(XMBContextMenuItem("remove_app", "Remove from Library", isDestructive = true))
            }
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
        // In non-gaming (app) categories the user wants to simply remove an app from the category,
        // not hide it globally — so "Hide App" is only offered in gaming categories.
        val isGamingCat = _uiState.value.categories.firstOrNull { it.id == categoryId }?.isGamingCategory == true
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
            // Per-location hide (recoverable in Settings ▸ Hidden Items) + global hide-everywhere.
            if (categoryId != null) add(XMBContextMenuItem("hide_from_category", "Hide from ${categoryDisplayName(categoryId)}"))
            add(XMBContextMenuItem("hide_everywhere", "Hide Everywhere"))
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

    // Options menu for a collection row (long-press / △), in any collection-rendering category.
    private fun openCollectionRowContextMenu(collectionId: Long) {
        val collection = _uiState.value.collections.firstOrNull { it.id == collectionId } ?: return
        // Move is only meaningful when there's another category of the same kind to move into
        // (game collections move between gaming categories, app collections between app ones).
        val hasOtherCategory = collectionMoveTargets(collection.categoryId).isNotEmpty()
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
    /** Valid destinations for moving a collection out of [fromCategoryId]: categories of the same
     *  kind (gaming ↔ gaming, app ↔ app) that render collections — an app collection can never
     *  land in a gaming category or a media section, and vice versa. */
    private fun collectionMoveTargets(fromCategoryId: String): List<Category> {
        val fromIsGaming = _uiState.value.categories.firstOrNull { it.id == fromCategoryId }?.isGamingCategory
            ?: (fromCategoryId == BuiltInCategory.GAMES)
        return _uiState.value.categories.filter { cat ->
            cat.id != fromCategoryId && categoryShowsCollections(cat) && cat.isGamingCategory == fromIsGaming
        }
    }

    private fun openCollectionCategoryPicker(collectionId: Long, fromCategoryId: String) {
        val items = collectionMoveTargets(fromCategoryId)
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

        // ── Video "Add to Playlist" submenu — handled before closing so toggles stay in place ──
        if (menu.videoPlaylistPickerVideoId != null) {
            val videoId = menu.videoPlaylistPickerVideoId
            val keepIndex = menu.selectedIndex
            when {
                itemId == "vpl_new" -> {
                    closeContextMenu()
                    promptCreateVideoPlaylist(forVideoId = videoId)
                }
                itemId.startsWith("vpl_") -> {
                    val playlistId = itemId.removePrefix("vpl_").toLongOrNull() ?: return
                    viewModelScope.launch {
                        videoRepository.toggleVideoInPlaylist(playlistId, videoId)
                        openVideoPlaylistPicker(videoId, keepIndex)  // re-open so the checkmark updates
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

        // ── Video file / library / playlist row options menus ───────────────────
        if (menu.videoFileId != null) {
            handleVideoFileAction(menu.videoFileId, itemId)
            return
        }
        if (menu.videoLibraryId != null) {
            handleVideoLibraryAction(menu.videoLibraryId, itemId)
            return
        }
        if (menu.photoFileId != null) {
            handlePhotoFileAction(menu.photoFileId, itemId)
            return
        }
        if (menu.photoLibraryId != null) {
            handlePhotoLibraryAction(menu.photoLibraryId, itemId)
            return
        }
        if (menu.socialAccountMenu) {
            handleSocialAccountAction(itemId)
            return
        }
        if (menu.videoPlaylistId != null) {
            handleVideoPlaylistRowAction(menu.videoPlaylistId, itemId)
            return
        }

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
            menu.isAllGames -> when (itemId) {
                "import_pc_games" -> _uiState.update { it.copy(activeSettingsScreen = "settings_import_pc") }
            }
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
                "hide_here"              -> currentHideLocation()?.let { (type, id, label) ->
                    persistHide(HiddenPlacement.gameKey(menu.gameId), menu.title, type, id, label)
                }
                "remove_app"             -> {
                    val gid = menu.gameId
                    appAction {
                        gameRepository.delete(gid)
                        memoryCardRepository.recountGames(ANDROID_PLATFORM_ID)
                    }
                }
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
                    "hide_from_category" -> menu.categoryContext?.let { cat ->
                        persistHide(HiddenPlacement.appKey(pkg), menu.title, HideLocationType.CATEGORY, cat, categoryDisplayName(cat))
                    }
                    "hide_everywhere" -> appAction { appCategoryRepository.setHidden(pkg, true) }
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
                // A collection created from a collection-rendering category (gaming, Network,
                // App Store, custom) is homed there; other contexts default to Main Game.
                val id = collectionRepository.create(name, collectionHomeCategoryId())
                dialog.forGameId?.let { collectionRepository.addGame(id, it) }
            }
            // Reflect the new/renamed collection in the XMB right away instead of waiting on the
            // reactive collection stream.
            if (categoryShowsCollections(currentCategory())) {
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
                    // Targets are real categories (built-in media categories for the Apps sections,
                    // or a user category) — all already exist, so no pseudo-category seeding needed.
                    packages.forEach { pkg -> appCategoryRepository.addToCategory(pkg, target.categoryId) }
                }
            }
        }
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

        packages.forEach { pkg ->
            // One row per app. If a shortcut row already exists (from artwork/favorites), promote
            // it into the Android library instead of creating a duplicate; otherwise add a new row.
            val existing = gameRepository.getAppEntry(pkg)
            when {
                existing == null -> gameRepository.upsert(
                    com.playfieldportal.core.domain.model.Game(
                        title         = labels[pkg]?.label ?: pkg,
                        platformId    = platformId,
                        packageName   = pkg,
                        isManualEntry = true,
                        // Package-based entry — classified as an app so it stays out of All Games.
                        contentType   = com.playfieldportal.core.domain.model.GameContentType.ANDROID_APP,
                    )
                )
                existing.platformId != platformId ->
                    gameRepository.upsert(existing.copy(platformId = platformId))
                // else: already in the library — nothing to do.
            }
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
        val prev = _uiState.value
        // Remember each category's cursor so moving away and back restores your spot instead of
        // snapping to the first item. Left/Right is locked while drilled in, so the saved index is
        // always a root-level list position for that category.
        prev.categories.getOrNull(prev.selectedCategoryIndex)?.id?.let { categoryCursor[it] = prev.selectedItemIndex }
        val category = prev.categories.getOrNull(index)
        val restore = category?.id?.let { categoryCursor[it] } ?: 0
        // activeAppDrawerFilter is cleared as an invariant: landing on a category always shows the
        // plain XMB (the drawer can't normally be open here, but this keeps the contextual button
        // state correct no matter which path selected the category).
        _uiState.update { it.copy(selectedCategoryIndex = index, selectedItemIndex = restore, selectedPlatformId = null, selectedCollectionId = null, musicNav = MusicNav.Root, videoNav = VideoNav.Root, photoNav = PhotoNav.Root, socialNav = SocialNav.Root, activeAppDrawerFilter = null) }
        tintWaveForCategory(category)
        loadItemsForCategory(category)
    }

    /** Touch tap on a caticon. Unlike the shared [onCategorySelected] (also driven by gamepad ◀ ▶),
     *  this marks the input as touch so the contextual button returns in Auto mode, and it is a
     *  no-op while drilled into a sub-item — matching [stepCategory]'s lock, so a stray tap can't
     *  yank the user out of a folder. */
    fun onCategoryTapped(index: Int) {
        markTouchInput()
        val s = _uiState.value
        if (s.hasBlockingOverlay || s.isInSubItem) return
        onCategorySelected(index)
    }

    /** Touch: step the category selection by [direction] (-1 / +1) from the current one — the swipe
     *  equivalent of D-pad ◀ ▶. */
    fun stepCategory(direction: Int) {
        markTouchInput()
        val s = _uiState.value
        if (s.hasBlockingOverlay) return
        // Locked while drilled into a sub-item — the user must Back out before changing category.
        if (s.isInSubItem) return
        val next = (s.selectedCategoryIndex + direction)
            .coerceIn(0, (s.categories.size - 1).coerceAtLeast(0))
        if (next != s.selectedCategoryIndex) onCategorySelected(next)
    }

    // ── Shared item-cursor movement (D-pad + touch swipe) ─────────────────────────

    /**
     * Moves the item cursor by [delta] rows in one clamped, batched update, playing a single scroll
     * sound if it moved. Returns whether the cursor actually moved (the D-pad path uses this to
     * cancel auto-repeat at a list boundary). Shared by [dispatchGamepadAction]'s NAVIGATE_UP/DOWN
     * and the touch [stepItem], so both drive identical logic — no parallel navigation.
     */
    private fun moveItemCursor(delta: Int): Boolean {
        val s = _uiState.value
        if (s.hasBlockingOverlay || delta == 0) return false
        val max = (s.currentItems.size - 1).coerceAtLeast(0)
        val next = (s.selectedItemIndex + delta).coerceIn(0, max)
        if (next == s.selectedItemIndex) return false
        _uiState.update { it.copy(selectedItemIndex = next) }
        menuSound.play(MenuSound.SCROLL)
        return true
    }

    /** Touch: step the item cursor by [steps] rows (a swipe = repeated D-pad ▲▼), batched into one
     *  update so a multi-row swipe is a single recomposition. */
    fun stepItem(steps: Int) {
        markTouchInput()
        moveItemCursor(steps)
    }

    /** Touch tap on row [index]: move the cursor there, or — if it's already the selected row —
     *  activate it. Keeps touch faithful to the XMB cursor model (tap to point, tap again to open).
     *  The controller SELECT path still activates in one press via [activateSelected]. */
    fun onItemTap(index: Int) {
        markTouchInput()
        val s = _uiState.value
        if (s.hasBlockingOverlay) return
        if (index == s.selectedItemIndex) {
            activateSelected()
        } else {
            val clamped = index.coerceIn(0, (s.currentItems.size - 1).coerceAtLeast(0))
            if (clamped != s.selectedItemIndex) {
                _uiState.update { it.copy(selectedItemIndex = clamped) }
                menuSound.play(MenuSound.SCROLL)
            }
        }
    }

    /** Activates the currently selected item (launch / drill / open) — the shared body of the
     *  controller SELECT and a second tap on the focused row. */
    private fun activateSelected() {
        onItemSelected(_uiState.value.selectedItemIndex)
    }

    // ── Input-source tracking (drives the touch-navigation button) ────────────────

    /** Marks the last input source as touch. Public so fullscreen overlays (detail screens, the
     *  music browser) can report a touch interaction, keeping the single `lastInputWasTouch` source
     *  of truth — the same one that drives the XMB's contextual App Drawer button. */
    fun markTouchInput() {
        if (!_uiState.value.lastInputWasTouch) _uiState.update { it.copy(lastInputWasTouch = true) }
    }

    private fun markControllerInput() {
        if (_uiState.value.lastInputWasTouch) _uiState.update { it.copy(lastInputWasTouch = false) }
    }

    /** Touch: the left-edge-swipe Back — exit an open folder, or open the app drawer at the root
     *  (mirrors the gamepad BACK behaviour on the home screen). No-op while an overlay is up. */
    fun onHomeBack() {
        markTouchInput()
        val s = _uiState.value
        if (s.hasBlockingOverlay) return
        menuSound.play(MenuSound.BACK)
        when {
            s.musicNav != MusicNav.Root -> closeMusicView()
            s.videoNav is VideoNav.Library -> openVideoView(VideoNav.Libraries)
            s.videoNav is VideoNav.Playlist -> openVideoView(VideoNav.Playlists)
            s.videoNav.isVideoCollectionChild -> openVideoView(VideoNav.Collections)
            s.videoNav != VideoNav.Root -> closeVideoView()
            s.photoNav is PhotoNav.Library -> openPhotoView(PhotoNav.Albums)
            s.photoNav != PhotoNav.Root -> closePhotoView()
            s.socialNav != SocialNav.Root -> socialBack()
            s.selectedPlatformId != null || s.selectedCollectionId != null -> closePlatformFolder()
            else -> onOpenAppDrawer()
        }
    }

    // ── Item selection ────────────────────────────────────────────────────────

    // ── Discord Social section ────────────────────────────────────────────────
    private suspend fun socialRootItems(): List<XMBItem> {
        if (!discordAuthRepository.hasSession()) {
            return listOf(
                XMBItem(
                    id = "social_add",
                    title = "Sign in with Discord",
                    subtitle = "Scan a QR code with your phone",
                    type = XMBItemType.SOCIAL_ADD,
                ),
            )
        }
        val online = discordAuthRepository.isOnline()
        val user = if (online) discordAuthRepository.currentUser() else null
        // L1 is the account itself; Friends / Voice / Settings / Sign Out live in its hub (L2).
        return listOf(
            XMBItem(
                id = "social_account",
                title = user?.label ?: "Connected to Discord",
                subtitle = when {
                    !online -> "Offline"
                    user != null -> "Online"
                    else -> "Connecting…"
                },
                coverUri = user?.avatarUrl?.takeIf { it.isNotBlank() },
                type = XMBItemType.SOCIAL_ACCOUNT,
            ),
        )
    }

    // L2 (Account hub): the account's sections. Sign Out now lives under Discord Settings.
    // [friendsOnline] fills the Friends row's live count when known (null keeps the generic hint,
    // e.g. for the drill sibling column that renders without a network read).
    private fun socialHubItems(friendsOnline: Int? = null): List<XMBItem> = listOf(
        XMBItem(
            id = "social_friends",
            title = "Friends",
            subtitle = when {
                friendsOnline == null -> "See who's online"
                friendsOnline == 0    -> "No friends online"
                friendsOnline == 1    -> "1 online"
                else                  -> "$friendsOnline online"
            },
            type = XMBItemType.SOCIAL_FRIENDS,
        ),
        XMBItem(id = "social_voice", title = "Voice", subtitle = "Coming soon", type = XMBItemType.SOCIAL_VOICE),
        XMBItem(id = "social_activity", title = "Activity Settings", subtitle = "Share what you're playing", type = XMBItemType.SOCIAL_ACTIVITY_SETTINGS),
        XMBItem(id = "social_settings", title = "Discord Settings", subtitle = "Account & sign out", type = XMBItemType.SOCIAL_DISCORD_SETTINGS),
    )

    // The hub's drillable sections — the sibling column shown when drilled deeper than the hub.
    private fun socialHubSiblings(): List<XMBItem> = socialHubItems()

    // L3 (Activity Settings): opt-in presence sharing + generic mode, each an on/off toggle row.
    private suspend fun socialActivitySettingsItems(): List<XMBItem> {
        val sharing = discordPresence.isShareEnabled()
        val generic = discordPresence.isGenericMode()
        return listOf(
            XMBItem(
                id = "activity_share",
                title = "Share Activity",
                subtitle = if (sharing) "On · friends can see you're in Playfield Portal"
                           else "Off · nothing is shared with friends",
                type = XMBItemType.SOCIAL_TOGGLE,
            ),
            XMBItem(
                id = "activity_generic",
                title = "Generic Mode",
                subtitle = if (generic) "On · shows \"a game\" instead of the app name"
                           else "Off · shows the app name",
                type = XMBItemType.SOCIAL_TOGGLE,
            ),
        )
    }

    private fun toggleActivitySetting(id: String) {
        viewModelScope.launch {
            when (id) {
                "activity_share"   -> discordPresence.setShareEnabled(!discordPresence.isShareEnabled())
                "activity_generic" -> discordPresence.setGenericMode(!discordPresence.isGenericMode())
            }
            // Re-render the toggle rows with their new on/off state (setters broadcast internally).
            loadItemsForCategory(currentCategory())
        }
    }

    // L3 (Discord Settings): account options. Sign Out for now; notifications & more land here later.
    private suspend fun socialDiscordSettingsItems(): List<XMBItem> {
        val user = discordAuthRepository.currentUser()
        return listOf(
            XMBItem(
                id = "social_signout",
                title = "Sign Out",
                subtitle = user?.label?.let { "Disconnect $it" } ?: "Disconnect this Discord account",
                type = XMBItemType.SOCIAL_SIGNOUT,
            ),
        )
    }

    // L3 (Friends): friends online-first, with offline / empty placeholders.
    private suspend fun socialFriendItems(): List<XMBItem> {
        if (!discordAuthRepository.isOnline()) {
            return listOf(XMBItem(id = "social_offline", title = "You're offline", subtitle = "Reconnect to see your friends", type = XMBItemType.EMPTY))
        }
        val friends = discordAuthRepository.friends().sortedWith(
            compareByDescending<DiscordFriend> { it.presence.isOnline }.thenBy { it.label.lowercase() },
        )
        if (friends.isEmpty()) {
            return listOf(XMBItem(id = "social_nofriends", title = "No friends to show", type = XMBItemType.EMPTY))
        }
        return friends.map { f ->
            XMBItem(
                id = "friend_${f.id}",
                title = f.label,
                // Subtext under the name: what they're playing, else the presence word — prefixed by
                // the colored presence dot in the row renderer.
                subtitle = f.activity?.let { "Playing $it" } ?: socialPresenceLabel(f.presence).takeIf { it.isNotBlank() },
                coverUri = f.avatarUrl.takeIf { it.isNotBlank() },
                socialStatusArgb = socialPresenceArgb(f.presence),
                type = XMBItemType.SOCIAL_FRIEND,
            )
        }
    }

    private fun socialPresenceLabel(p: DiscordPresence): String = when (p) {
        DiscordPresence.ONLINE -> "Online"
        DiscordPresence.IDLE -> "Idle"
        DiscordPresence.DND -> "Do Not Disturb"
        DiscordPresence.STREAMING -> "Streaming"
        DiscordPresence.OFFLINE -> "Offline"
        DiscordPresence.UNKNOWN -> ""
    }

    // Discord-style presence colors for the friend-row status dot; null = no dot (unknown).
    private fun socialPresenceArgb(p: DiscordPresence): Long? = when (p) {
        DiscordPresence.ONLINE    -> 0xFF43B581   // green
        DiscordPresence.IDLE      -> 0xFFFAA61A   // amber
        DiscordPresence.DND       -> 0xFFF04747   // red
        DiscordPresence.STREAMING -> 0xFF593695   // purple
        DiscordPresence.OFFLINE   -> 0xFF747F8D   // gray
        DiscordPresence.UNKNOWN   -> null
    }

    private fun openSocialView(nav: SocialNav) = navigateRememberingCursor { it.copy(socialNav = nav) }

    // One level up: Friends / Discord Settings → Account → Root.
    private fun socialBack() {
        val parent = when (_uiState.value.socialNav) {
            SocialNav.Friends          -> SocialNav.Account
            SocialNav.ActivitySettings -> SocialNav.Account
            SocialNav.DiscordSettings  -> SocialNav.Account
            SocialNav.Account          -> SocialNav.Root
            SocialNav.Root             -> SocialNav.Root
        }
        openSocialView(parent)
    }

    // When connected but the profile hasn't loaded yet (gateway still connecting), poll briefly and
    // reload the Social list once the user resolves so the avatar + name fill in.
    private var socialRefreshJob: Job? = null
    private fun scheduleSocialAccountRefresh() {
        socialRefreshJob?.cancel()
        socialRefreshJob = viewModelScope.launch {
            repeat(8) {
                kotlinx.coroutines.delay(1000)
                if (currentCategory()?.id != BuiltInCategory.SOCIAL ||
                    _uiState.value.socialNav != SocialNav.Root
                ) return@launch
                if (discordAuthRepository.currentUser() != null) {
                    loadItemsForCategory(currentCategory())
                    return@launch
                }
            }
        }
    }

    // Options (△ / long-press) on the connected account row. "Reconnect" re-hands the stored token
    // to the SDK — the recovery path when the network dropped and came back (the account row shows
    // "Offline" until then).
    private fun openSocialAccountContextMenu() {
        _uiState.update {
            it.copy(
                activeContextMenu = XMBContextMenu(
                    title = "Account",
                    items = listOf(XMBContextMenuItem("social_reconnect", "Reconnect")),
                    socialAccountMenu = true,
                ),
            )
        }
    }

    private fun handleSocialAccountAction(itemId: String) {
        when (itemId) {
            "social_reconnect" -> viewModelScope.launch {
                discordAuthRepository.restoreSession()
                discordPresence.refresh()   // re-broadcast presence once reconnected (if sharing is on)
                // Re-render the account row (Offline → Connecting…/Online) and poll until the gateway
                // reaches Ready so the avatar/name and Online state fill back in.
                loadItemsForCategory(currentCategory())
                scheduleSocialAccountRefresh()
            }
        }
    }

    // Each Social row owns its sound and returns early (mirrors handleMusicSelection).
    private fun handleSocialSelection(item: XMBItem): Boolean {
        when (item.type) {
            XMBItemType.SOCIAL_ADD -> {
                menuSound.play(MenuSound.SELECT)
                _uiState.update { it.copy(activeDiscordLogin = true) }
            }
            XMBItemType.SOCIAL_SIGNOUT -> {
                menuSound.play(MenuSound.SELECT)
                // Logging out invalidates the drill (no account), so return to the Social root, which
                // re-renders to the "Sign in with Discord" row.
                viewModelScope.launch {
                    discordAuthRepository.logout()
                    openSocialView(SocialNav.Root)
                }
            }
            XMBItemType.SOCIAL_ACCOUNT -> { menuSound.play(MenuSound.SELECT); openSocialView(SocialNav.Account) }
            XMBItemType.SOCIAL_FRIENDS -> { menuSound.play(MenuSound.SELECT); openSocialView(SocialNav.Friends) }
            XMBItemType.SOCIAL_ACTIVITY_SETTINGS -> { menuSound.play(MenuSound.SELECT); openSocialView(SocialNav.ActivitySettings) }
            XMBItemType.SOCIAL_DISCORD_SETTINGS -> { menuSound.play(MenuSound.SELECT); openSocialView(SocialNav.DiscordSettings) }
            XMBItemType.SOCIAL_TOGGLE -> { menuSound.play(MenuSound.SELECT); toggleActivitySetting(item.id) }
            XMBItemType.SOCIAL_VOICE,
            XMBItemType.SOCIAL_FRIEND -> menuSound.play(MenuSound.SELECT)  // placeholder / friend profile later
            else -> return false
        }
        return true
    }

    /** Called by the shell when the QR login overlay closes (connected or cancelled). */
    fun onDiscordLoginClosed() {
        _uiState.update { it.copy(activeDiscordLogin = false) }
        // Broadcast the opt-in presence if the user just connected and has sharing on (no-op otherwise).
        viewModelScope.launch { discordPresence.refresh() }
        loadItemsForCategory(currentCategory())
    }

    fun onItemSelected(index: Int) {
        // Touch guard: XMB rows must never activate while any overlay is up (the gamepad path is
        // guarded in the dispatcher; this closes the same hole for taps that slip through an
        // overlay's non-interactive areas).
        if (_uiState.value.hasBlockingOverlay) return
        _uiState.update { it.copy(selectedItemIndex = index) }
        val category = _uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex)
        val item     = _uiState.value.currentItems.getOrNull(index)

        // Music rows are handled together (static items, the All Music card, playlists, tracks,
        // and the various add/setup rows), each owning its sound and returning early.
        if (category?.id == BuiltInCategory.MUSIC && item != null && handleMusicSelection(item)) return

        // Video rows (static items, library cards, video files, app rows) are handled together.
        if (category?.id == BuiltInCategory.VIDEO && item != null && handleVideoSelection(item)) return

        // Photo rows (the All Photos card, Camera, Add Photo Library, Album cards, photo files).
        if (category?.id == BuiltInCategory.PHOTO && item != null && handlePhotoSelection(item)) return

        // Social rows (Sign in / connected account / Sign out).
        if (category?.id == BuiltInCategory.SOCIAL && item != null && handleSocialSelection(item)) return

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
            // Mirror the ROM path: reflect the launch in the opt-in Discord presence (no-op unless
            // Discord is connected and sharing is on). Cleared on return via MainActivity.onResume.
            viewModelScope.launch { discordPresence.setCurrentGame(item.title) }
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
            // Fixed system intent constant, no user-controlled data; NEW_TASK because the
            // launcher isn't an activity task the settings app should join.
            ANDROID_SETTINGS_ITEM_ID -> {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Timber.w(it, "Could not open device settings") }
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
        if (_uiState.value.hasBlockingOverlay) return
        val item = _uiState.value.currentItems.getOrNull(index)
        when {
            item != null && openMusicContextMenu(item) -> Unit
            item != null && openVideoContextMenu(item) -> Unit
            item != null && openPhotoContextMenu(item) -> Unit
            item?.gameId != null -> openGameContextMenu(item)
            item?.collectionId != null && item.type == XMBItemType.COLLECTION -> openCollectionRowContextMenu(item.collectionId)
            item?.type == XMBItemType.ALL_GAMES -> openAllGamesContextMenu()
            item?.type == XMBItemType.SOCIAL_ACCOUNT -> openSocialAccountContextMenu()
            item?.platformId != null -> openPlatformContextMenu(item.platformId)
            item?.packageName != null -> openAppContextMenu(item)
        }
    }

    private fun openPlatformFolder(platformId: String) {
        val gamesCategoryIndex = _uiState.value.categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
        navigateRememberingCursor {
            it.copy(
                selectedCategoryIndex = gamesCategoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = platformId,
            )
        }
    }

    private fun openAllGamesFolder() {
        val gamesCategoryIndex = _uiState.value.categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
        navigateRememberingCursor {
            it.copy(
                selectedCategoryIndex = gamesCategoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = ALL_GAMES_PLATFORM_ID,
                selectedCollectionId = null,
            )
        }
    }

    private fun openFavoritesFolder() {
        val gamesCategoryIndex = _uiState.value.categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
        navigateRememberingCursor {
            it.copy(
                selectedCategoryIndex = gamesCategoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = FAVORITES_PLATFORM_ID,
                selectedCollectionId = null,
            )
        }
    }

    private fun openCollectionFolder(collectionId: Long) {
        // Open the collection within the category it belongs to — a collection in a custom
        // gaming category must stay in that category, not jump back to Main Game.
        val targetCategoryId = _uiState.value.collections
            .firstOrNull { it.id == collectionId }?.categoryId ?: BuiltInCategory.GAMES
        val categoryIndex = _uiState.value.categories.indexOfFirst { it.id == targetCategoryId }
        navigateRememberingCursor {
            it.copy(
                selectedCategoryIndex = categoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = null,
                selectedCollectionId = collectionId,
            )
        }
    }

    // Closes any open Games-root drill-down (platform card, All Games, or a collection).
    private fun closePlatformFolder() = navigateRememberingCursor {
        it.copy(selectedPlatformId = null, selectedCollectionId = null)
    }

    // ── Game detail overlay ───────────────────────────────────────────────────

    fun onCloseGameDetail() {
        _uiState.update { it.copy(activeGameId = null, pendingGameDetailAction = null) }
        // Rebuild the visible list: title/artwork edits made in the detail screen must show the
        // moment the overlay closes (the item build is one-shot, not reactive to those tables).
        loadItemsForCategory(currentCategory())
    }

    fun consumeGameDetailAction() {
        _uiState.update { it.copy(pendingGameDetailAction = null) }
    }

    // ── App detail overlay ────────────────────────────────────────────────────

    private fun openAppDetail(knownGameId: Long?, packageName: String) {
        val collectionHome = collectionHomeCategoryId()
        if (knownGameId != null) {
            _uiState.update { it.copy(activeAppId = knownGameId, activeAppCollectionCategoryId = collectionHome) }
            return
        }
        viewModelScope.launch {
            val id = ensureAppShortcut(packageName)
            _uiState.update { it.copy(activeAppId = id, activeAppCollectionCategoryId = collectionHome) }
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
                // Sentinel platform: a shortcut row backs artwork/favorites/collections without
                // placing the app in the Android library (which is observeByPlatform("android")).
                platformId    = APP_SHORTCUT_PLATFORM_ID,
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
                                        // Harvested shortcuts live in a collection, not the Android library.
                                        platformId    = APP_SHORTCUT_PLATFORM_ID,
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
            val parsed = android.content.Intent.parseUri(intentUri, android.content.Intent.URI_INTENT_SCHEME)
            // Defense in depth: re-harden at launch (also cleans entries captured before the
            // sanitizer existed) so a stored intent can never grant file access or be redirected.
            val launch = (com.playfieldportal.core.common.security.ShortcutIntentSanitizer
                .sanitize(parsed, context.packageManager)
                ?: error("Captured shortcut is not safe to launch"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }.onFailure { e ->
            Timber.e(e, "Failed to launch captured shortcut: $label")
            taskNotifier.failed("launch_intent_${label.hashCode()}", label, "Couldn't launch: ${e.message}")
        }
    }

    fun onCloseAppDetail() {
        _uiState.update { it.copy(activeAppId = null, pendingAppDetailAction = null) }
        // Rebuild the visible list so a freshly assigned background/icon (games-table row keyed by
        // package) reaches the XMB rows immediately — this is what puts artworkUri on app items.
        loadItemsForCategory(currentCategory())
    }

    fun consumeAppDetailAction() {
        _uiState.update { it.copy(pendingAppDetailAction = null) }
    }

    // ── Settings overlay ──────────────────────────────────────────────────────

    fun onCloseSettingsScreen() {
        Timber.d("Settings closed")
        _uiState.update { it.copy(activeSettingsScreen = null, pendingSettingsAction = null) }
    }

    // Bridge from Library Settings → the shared installed-app picker. Closes the settings overlay
    // and opens the same picker the XMB Android card uses, so apps are added the one way.
    fun openAndroidLibraryPicker() {
        _uiState.update { it.copy(activeSettingsScreen = null, pendingSettingsAction = null) }
        openAppPicker(AppPickerTarget.AndroidGames(ANDROID_PLATFORM_ID), "Add Android Apps")
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

    // ── Touch-navigation button preference ────────────────────────────────────────

    private fun observeTouchNavButtonMode() {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                val mode = com.playfieldportal.core.domain.model.TouchNavButtonMode
                    .fromName(prefs[KEY_TOUCH_NAV_BUTTON])
                val sensitivity = com.playfieldportal.core.domain.model.TouchSensitivity
                    .fromName(prefs[KEY_TOUCH_SENSITIVITY])
                _uiState.update { it.copy(touchNavButtonMode = mode, touchSensitivity = sensitivity) }
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
                _uiState.update {
                    it.copy(
                        waveStyle            = style,
                        respectBatterySaver  = prefs[KEY_RESPECT_BATTERY] ?: true,
                        thermalThrottleAware = prefs[KEY_THERMAL_AWARE] ?: true,
                    )
                }
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
        // Must match DisplaySettingsViewModel — both read/write these wave power-throttle prefs.
        private val KEY_RESPECT_BATTERY   = booleanPreferencesKey("display_battery_saver")
        private val KEY_THERMAL_AWARE     = booleanPreferencesKey("display_thermal_aware")
        private val KEY_COLOR_SCHEME      = stringPreferencesKey("display_color_scheme")
        private val KEY_SETUP_COMPLETE    = booleanPreferencesKey("library_setup_complete")
        private val KEY_CUSTOM_WALLPAPER  = stringPreferencesKey("display_custom_wallpaper")
        private val KEY_MENU_SOUND_ENABLED = booleanPreferencesKey("sound_menu_enabled")
        // Must match DisplaySettingsViewModel.KEY_TOUCH_NAV_BUTTON — both read/write this pref.
        private val KEY_TOUCH_NAV_BUTTON  = stringPreferencesKey("interface_touch_nav_button")
        // Must match DisplaySettingsViewModel.KEY_TOUCH_SENSITIVITY — both read/write this pref.
        private val KEY_TOUCH_SENSITIVITY = stringPreferencesKey("interface_touch_sensitivity")
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
        // Sentinel platform for app rows that merely BACK a category app's artwork / favorite /
        // collection membership. They reference an app by package but are NOT in the Android
        // library, so they use this id instead of "android" to stay out of observeByPlatform.
        private const val APP_SHORTCUT_PLATFORM_ID = "app_shortcut"

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
        // The "Apps" sections (Music / Video / Photo) are backed by the REAL built-in media
        // categories — not hidden pseudo-categories. Apps auto-populate from the classifier
        // (installed music / video / photo apps) and any manual picks live in the same real
        // category, so there is nothing hidden for users to tamper with in Category settings.
        private const val MUSIC_APPS_CATEGORY_ID = "music"
        // Video root item ids.
        private const val ALL_VIDEOS_ITEM_ID = "all_videos"
        private const val VIDEO_COLLECTIONS_ITEM_ID = "video_collections"
        private const val RECENTLY_WATCHED_ITEM_ID = "recently_watched"
        private const val FAVORITE_VIDEOS_ITEM_ID = "favorite_videos"
        private const val VIDEO_PLAYLISTS_ITEM_ID = "video_playlists"
        private const val CREATE_VIDEO_PLAYLIST_ITEM_ID = "create_video_playlist"
        private const val VIDEO_LIBRARIES_ITEM_ID = "video_libraries"
        private const val VIDEO_APPS_ITEM_ID = "video_apps_item"
        private const val ADD_VIDEOS_ITEM_ID = "add_videos"
        private const val ADD_VIDEO_APPS_ITEM_ID = "add_video_apps"
        private const val VIDEO_APPS_CATEGORY_ID = "videos"
        // Photo root item ids.
        private const val ALL_PHOTOS_ITEM_ID = "all_photos"
        private const val CAMERA_ITEM_ID = "photo_camera"
        private const val ADD_PHOTO_LIBRARY_ITEM_ID = "add_photo_library"
        private const val PHOTO_ALBUMS_ITEM_ID = "photo_albums"
        private const val PHOTO_APPS_ITEM_ID = "photo_apps_item"
        private const val ADD_PHOTO_APPS_ITEM_ID = "add_photo_apps"
        private const val PHOTO_APPS_CATEGORY_ID = "photos"
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

        // First item opens the device's own Settings app (not a PFP screen).
        internal const val ANDROID_SETTINGS_ITEM_ID = "settings_android_system"

        private val SETTINGS_ITEMS = listOf(
            XMBItem(id = ANDROID_SETTINGS_ITEM_ID,  title = "Android Settings", subtitle = "Opens device settings"),
            XMBItem(id = "settings_library",    title = "Library",          subtitle = "ROM sources & scanning"),
            XMBItem(id = "settings_music",      title = "Music",            subtitle = "Music folders & default player"),
            XMBItem(id = "settings_video",      title = "Video",            subtitle = "Video libraries, scanning & playback"),
            XMBItem(id = "settings_photo",      title = "Photo",            subtitle = "Photo libraries & scanning"),
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

    // Rebuilds the bar from the canonical built-in definitions (name/icon/position/order) merged with
    // per-row DB fields. [categories] is the *visible* set, so a built-in the user hid is simply
    // absent from [byId] and dropped here — that's what makes "Show On Bar" work for Main categories.
    // Settings is the one exception: it's always kept, since it's the only route back into category
    // management and hiding it would soft-lock the user out.
    private fun canonicalXmbCategories(categories: List<Category>): List<Category> {
        val byId = categories.associateBy { it.id }
        val builtInIds = FALLBACK_CATEGORIES.map { it.id }.toSet()

        val builtIns = FALLBACK_CATEGORIES.mapNotNull { fallback ->
            val stored = byId[fallback.id]
            // Drop hidden built-ins (absent from the visible set); never drop Settings.
            if (stored == null && fallback.id != BuiltInCategory.SETTINGS) return@mapNotNull null
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
