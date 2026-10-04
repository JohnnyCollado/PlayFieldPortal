package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.data.repository.MediaRootKind
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.HiddenPlacementEntity
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.data.repository.CustomIconStore
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.core.data.repository.ThemePrefKeys
import com.playfieldportal.core.ui.components.PspMenuCue
import com.playfieldportal.core.ui.components.PspMenuNav
import com.playfieldportal.core.ui.components.PspMenuOutcome
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.media.resolveGameBootAudio
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.core.domain.discord.DiscordFriend
import com.playfieldportal.core.domain.discord.DiscordPresence
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.FeatureFlags
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.HiddenPlacement
import com.playfieldportal.core.domain.model.HideLocationType
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.core.domain.model.resolve
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.DetailAction
import com.playfieldportal.core.domain.model.toNotificationAction
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import com.playfieldportal.feature.xmb.ui.buildNotificationRows
import com.playfieldportal.feature.xmb.ui.clampCursor
import com.playfieldportal.feature.xmb.ui.firstSelectableIndex
import com.playfieldportal.feature.xmb.ui.moveCursor
import com.playfieldportal.feature.xmb.ui.notificationAt
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.theme.DefaultPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.feature.appbar.AppCategoryRepository
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerTarget
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerTargetAction
import com.playfieldportal.feature.appbar.AppMenuContext
import com.playfieldportal.feature.appbar.AppMenuIds
import com.playfieldportal.feature.appbar.InstalledAppRepository
import com.playfieldportal.feature.appbar.appMenuItems
import com.playfieldportal.feature.appbar.CategorizedApp
import com.playfieldportal.feature.appbar.LauncherShortcutRepository
import com.playfieldportal.feature.launcher.LaunchDispatchResult
import com.playfieldportal.feature.launcher.LaunchRecoveryAction
import com.playfieldportal.feature.launcher.ResolvedLaunch
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.ScrapeProgress
import com.playfieldportal.feature.artwork.api.relinkAll
import com.playfieldportal.feature.library.scanner.LibraryScanner
import com.playfieldportal.feature.library.scanner.ScanStatus
import com.playfieldportal.feature.library.scanner.isScannable
import com.playfieldportal.feature.library.scanner.scanOutcomeMessage
import com.playfieldportal.feature.library.scanner.PlatformScanOutcome
import com.playfieldportal.feature.library.scanner.failureNotes
import com.playfieldportal.feature.library.scanner.scanResults
import com.playfieldportal.feature.artwork.api.GameScrapeOutcome
import com.playfieldportal.feature.artwork.api.scrapeResults
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.feature.xmb.R
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import com.playfieldportal.core.navigation.NavigationCommand
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.NavigationEngine
import com.playfieldportal.core.navigation.NavigationLogger
import com.playfieldportal.core.navigation.NavigationNode
import com.playfieldportal.core.navigation.NavigationTouchAction
import com.playfieldportal.feature.xmb.music.MusicPlaybackState
import com.playfieldportal.feature.xmb.music.nowPlayingRowKey
import com.playfieldportal.feature.xmb.ui.visualizer.VisualizerIds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.roundToInt
import com.playfieldportal.core.domain.playlist.PlaylistKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

// ── Context menu types ────────────────────────────────────────────────────────

data class XMBContextMenu(
    val title: String,
    val items: List<XMBContextMenuItem>,
    val selectedIndex: Int = 0,
    // Identifies the source of the menu (platform card, game, or app)
    val platformId: String? = null,
    // Set on the "All Games" card's menu (which is not a real Memory Card).
    val isAllGames: Boolean = false,
    // Set on a section memory card's menu ("Music" / "Videos" / "Photos"), naming the section
    // whose scan and settings the rows act on.
    val mediaCard: MediaRootKind? = null,
    val gameId: Long? = null,
    val packageName: String? = null,
    // The category the app is being acted on from (for remove/pin)
    val categoryContext: String? = null,
    // Set on the category-picker submenu: "move" or "add"
    val pendingAppAction: String? = null,
    // Set on the "Add to Card" submenu — the game being added.
    val collectionGameId: Long? = null,
    // Set on a collection row's own options menu (rename / delete / open).
    val collectionRowId: Long? = null,
    // Host app's launcher-shortcut id when the menu is for a harvested per-game entry.
    val shortcutId: String? = null,
    // Captured legacy INSTALL_SHORTCUT launch intent when the menu is for such an entry.
    val launchIntentUri: String? = null,
    // Set on any menu opened from the fullscreen music browser that carries its list-level rows
    // (Resume, Sort). Independent of the row fields above, because those rows are appended to a
    // track menu, a playlist menu, or a menu with nothing else in it.
    val browserList: Boolean = false,
    // Set on a music track's options menu.
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
    // Set on a Shiba Coins hub row's options menu (Update Installed Achievements).
    val achievementsHubMenu: Boolean = false,
    // Set on the notification panel's menu (Mark All Read / Clear Read / Clear All). There is no
    // per-row menu: a row does one thing, and Confirm already does it.
    val notificationListMenu: Boolean = false,
    // Set on the Games category's Filter menu (Square / the status pill). Unlike every other
    // submenu here, this one has a root worth returning to, so [gamesFilterGroup] is null on the
    // root and names the open group otherwise — and BACK inside a group returns to the root
    // rather than closing. See the two-level Options menu in ShibaLibraryViewModel.
    val gamesFilterMenu: Boolean = false,
    val gamesFilterGroup: GamesFilterGroup? = null,
    // The list row this menu was opened for, by its key in the list's stored order — what the
    // shared Move row acts on. A touch long-press opens a menu without moving the cursor, so the
    // focused row is not always the row the menu is about.
    val rowKey: String? = null,
    // Set on a list's Sort picker: the list whose own sort the rows set, and what it holds.
    val sortListKey: String? = null,
    val sortListKind: XmbListKind? = null,
    // Set on the global Sort picker: which global sort (games' or apps') the rows set.
    val globalSortKind: XmbListKind? = null,
    // Set on a custom category's own Memory Card row's menu.
    val categoryCardMenu: Boolean = false,
    // Set on the menu a long-press on a category icon opens: the category it is for.
    val categoryMenuId: String? = null,
    // Set on the Favorites / Missing row's menu in the Games root.
    val rootRowMenu: Boolean = false,
    // Set on the "Add N Games to Card" picker opened from multi-select — the marked games.
    val markedGameIds: Set<Long> = emptySet(),
    // The menu this one was opened from, exactly as it was left. Set on a second-level picker
    // (Icon Display, Choose Emulator, Add to Card…) so BACK climbs to it — see [afterBack].
    val parent: XMBContextMenu? = null,
)

/**
 * What BACK leaves on screen: the menu this one was opened from, cursor where it was left, or
 * null — closed — when this is a root menu.
 */
internal fun XMBContextMenu.afterBack(): XMBContextMenu? = parent

/** Levels below the root: the menus above this one, plus a Games Filter group's own level. */
internal val XMBContextMenu.depth: Int
    get() = generateSequence(parent) { it.parent }.count() + if (gamesFilterGroup != null) 1 else 0

/**
 * One controller press on this menu, through the rules every PSP-panel menu shares; plays the
 * cue and returns what the host must apply. The focused row's cue is its own (Favorite is silent).
 */
internal fun XMBContextMenu.press(
    action: GamepadAction,
    sounds: com.playfieldportal.core.ui.sound.MenuSoundSink,
): PspMenuOutcome {
    val row = items.getOrNull(selectedIndex)
    val cue = if (row == null) PspMenuCue.NONE else PspMenuRow(row.label, opensMenu = row.opensMenu, silent = row.silent).cue
    return PspMenuNav.handle(action, selectedIndex, items.size, depth, cue, sounds)
}

/** True when a menu has just appeared from nothing — a picker replacing its parent is not an opening. */
internal fun contextMenuOpened(before: XMBContextMenu?, after: XMBContextMenu?): Boolean =
    before == null && after != null

/** A second-level list inside the Games Filter menu, opened from its root row. */
enum class GamesFilterGroup(val title: String) { SORT("Sort") }

data class XMBContextMenuItem(
    val id: String,
    val label: String,
    val isDestructive: Boolean = false,
    // Renders a checkmark (e.g. collections the game already belongs to).
    val checked: Boolean = false,
    // The row's current setting, drawn dimmer and pinned to the panel's right edge. See
    // [com.playfieldportal.core.ui.components.PspMenuRow.value].
    val value: String? = null,
    // A group name drawn above this row — set on the first row of each group.
    val header: String? = null,
    // Activating the row opens a list: the panel draws › and the cue is SELECT.
    val opensMenu: Boolean = false,
    // Activating the row plays no cue (Favorite).
    val silent: Boolean = false,
)

// How a media section names itself on screen — the memory card's own title, which is also what
// its scan row scans ("Music" is already plural).
private val MediaRootKind.pluralNoun: String
    get() = when (this) {
        MediaRootKind.MUSIC -> "Music"
        MediaRootKind.VIDEO -> "Videos"
        MediaRootKind.PHOTO -> "Photos"
    }

private val MediaRootKind.settingsScreenId: String
    get() = when (this) {
        MediaRootKind.MUSIC -> "settings_music"
        MediaRootKind.VIDEO -> "settings_video"
        MediaRootKind.PHOTO -> "settings_photo"
    }

// Drives the shared text-input dialog. Creating a collection is the default; the optional
// targets repurpose it for renames and the game Edit Title / Edit Note actions.
data class CollectionNameDialogState(
    val title: String,
    val initialText: String = "",
    val forGameId: Long? = null,
    // The games marked in multi-select: all of them go into the card being created.
    val forGameIds: Set<Long> = emptySet(),
    // When set, confirming renames this collection instead of creating a new one.
    val renameCollectionId: Long? = null,
    // When set, confirming saves a display-title override for this game (blank resets it).
    val editTitleGameId: Long? = null,
    // When set, confirming saves this game's note (blank clears it).
    val editNoteGameId: Long? = null,
)

// A simple read-only message dialog (e.g. "View File Location"). Dismissed with A/B or tap.
data class InfoDialogState(
    val title: String,
    val message: String,
)

/**
 * The import reports still to show, one Results sheet per picked file. [reports] is never empty:
 * the head is on screen, and closing it [advance]s. [total] stays fixed so the sheet can say
 * "1 of 3".
 */
data class PlaylistImportQueue(
    val reports: List<PlaylistImportReport>,
    val total: Int = reports.size,
) {
    val current: PlaylistImportReport get() = reports.first()
    val position: Int get() = total - reports.size + 1

    /** The queue after the current report is closed, or null when it was the last. */
    fun advance(): PlaylistImportQueue? =
        if (reports.size <= 1) null else copy(reports = reports.drop(1))

    operator fun plus(more: List<PlaylistImportReport>): PlaylistImportQueue =
        if (more.isEmpty()) this else copy(reports = reports + more, total = total + more.size)
}

// ── Color-scheme picker (opened from Settings ▸ Themes ▸ Color Scheme) ──────────
// A PSP-style submenu that previews each scheme live as the cursor moves over it.

data class ColorSchemePickerState(
    val options: List<ColorSchemeOption>,
    val selectedIndex: Int = 0,
)

data class ColorSchemeOption(
    val scheme: XmbColorScheme?,
    val label: String,
    val sublabel: String,
    val swatch: Long,
    val isCustom: Boolean = false,
)

// ── Live "Adjust XMB Layout" editor ────────────────────────────────────────────
// A full-screen editor rendered OVER the real XMB (settings closed): D-pad / shoulder buttons or
// on-screen sliders drive the cross's scale + horizontal + vertical placement live. [draft] is the
// value applied while editing; [original] is restored on cancel; [bucketKey] is the form-factor the
// result is saved under so a handheld and a foldable each keep their own tuning.
data class XmbLayoutAdjustSession(
    val draft: com.playfieldportal.themekit.XmbLayoutAdjust,
    val original: com.playfieldportal.themekit.XmbLayoutAdjust,
    val bucketKey: String,
    val slidersVisible: Boolean = false,
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
    // Selected apps go on a custom memory card in an app category (the card's Add Apps).
    data class CardApps(val collectionId: Long) : AppPickerTarget
}

data class AppPickerEntry(
    val packageName: String,
    val label: String,
    // Icon resolved once, off the main thread, when the picker opens — never per-tile in
    // composition (a 7-wide grid would run PackageManager binder calls on the UI thread).
    val icon: android.graphics.drawable.Drawable? = null,
)

// Columns of the picker grid — one denser than the App Drawer's 6, and declared beside
// PICKER_GRID_COLUMNS in AppPickerLogic so the layout and the navigation math can't drift.
const val PICKER_GRID_COLUMNS = 7

data class AppPickerState(
    val title: String,
    val target: AppPickerTarget,
    val apps: List<AppPickerEntry>,
    val selected: Set<String> = emptySet(),
    /** Membership at open time — the baseline the Apply diff runs against. */
    val initialSelected: Set<String> = emptySet(),
    /** Index into `visibleApps()`, NOT into `apps` — the Confirm row is gone (Apply moved to the footer). */
    val focusedIndex: Int = 0,
    val query: String = "",
    val searchActive: Boolean = false,
    // Bumped when X brings PFP's keyboard back to an open search that still holds text.
    val searchReopens: Int = 0,
    val confirmingRemovals: Boolean = false,
    /**
     * Which confirm-panel option the gamepad cursor sits on while [confirmingRemovals] is up.
     * The modal is a hard input boundary: while it is open, dpad navigation drives THIS cursor,
     * never the grid behind the scrim.
     */
    val confirmFocusedOption: Int = CONFIRM_CANCEL,
    /** Mirrors AppDrawerUiState.usingTouch — hides the cursor and suppresses auto-scroll. */
    val usingTouch: Boolean = false,
) {
    companion object {
        const val CONFIRM_CANCEL = 0
        const val CONFIRM_REMOVE = 1
    }
}

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
    data object Voice : SocialNav
    data object VoiceSettings : SocialNav
    data object VoiceInvites : SocialNav        // pending invites + join requests
    data object VoiceInviteFriends : SocialNav  // pick friends to invite to your lobby
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

// The Shiba Coins hub is a single root list (All Tracked and Untracked open fullscreen overlays);
// this stays a sealed interface so the drill/back plumbing keeps a stable type.
sealed interface AchievementsNav {
    data object Root : AchievementsNav
}

/** Which fullscreen Shiba Coins library overlay is open. */
enum class ShibaLibraryMode { TRACKED, UNTRACKED }

// ── Settings hierarchy ────────────────────────────────────────────────────────
// The Settings category root shows the Android system-settings leaf plus these six nested L1
// sections. Selecting a section drills into the shared two-pane flyout (same interaction model
// as Music/Video/Photo/Social); selecting an L2 row inside it opens the existing settings screen
// overlay. L2 row ids ARE screen route ids — SettingsNavHost resolves them, so legacy direct
// callers keep working during migration.
enum class SettingsSection(
    val id: String,
    val title: String,
    val subtitle: String,
) {
    LIBRARY     ("settings_section_library",      "Library",      "Sources, cards, artwork & hidden games"),
    EMULATORS   ("settings_section_emulators",    "Emulators",    "Launch profiles & RetroArch cores"),
    INTERFACE   ("settings_section_interface",    "Interface",    "Categories, themes, display & controller"),
    ACHIEVEMENTS("settings_section_achievements", "Achievements", "RetroAchievements & Steam"),
    MEDIA       ("settings_section_media",        "Media",        "Music, video & photo settings"),
    SYSTEM      (
        "settings_section_system", "System",
        if (FeatureFlags.BACKUP_RESTORE) "About, logs, backup, setup & credits" else "About, logs, setup & credits",
    ),
}

/** False for a screen that is switched off (Backup & Restore), so a stale notification cannot open it. */
internal fun isSettingsRouteEnabled(routeId: String): Boolean = routeId != "settings_backup" || FeatureFlags.BACKUP_RESTORE

fun settingsSectionForId(id: String): SettingsSection? =
    SettingsSection.entries.firstOrNull { it.id == id }

// The L2 rows of a section. Ids must be unique inside the list (list keys + cursor restore) and
// distinct from every section id (the select handler routes section ids to the flyout and
// everything else to activeSettingsScreen — see SettingsHierarchyTest).
fun settingsSectionItems(section: SettingsSection): List<XMBItem> = when (section) {
    SettingsSection.LIBRARY -> listOf(
        XMBItem(id = "settings_library",        title = "Library Manager",    subtitle = "ROM sources & scanning"),
        XMBItem(id = "settings_windows_games",  title = "Windows Games", subtitle = "PC games, launchers & imports"),
        XMBItem(id = "settings_collections",    title = "Custom Memory Cards", subtitle = "Create & manage custom memory cards"),
        XMBItem(id = "settings_artwork",        title = "Artwork",      subtitle = "Scraping sources & cache"),
        XMBItem(id = "settings_app_visibility", title = "Hidden Games", subtitle = "Review apps & games you've hidden"),
    )
    SettingsSection.EMULATORS -> listOf(
        // First pass: all three open the combined Emulators screen (plan §4); distinct ids keep
        // list keys stable so per-section focus targets can land later without migrating callers.
        XMBItem(id = "settings_emulators_installed", title = "Installed",        subtitle = "Detected emulator profiles"),
        XMBItem(id = "settings_emulators_custom",    title = "Custom Emulators", subtitle = "Custom profiles & Add Custom Emulator"),
        XMBItem(id = "settings_emulators_retroarch", title = "RetroArch",        subtitle = "Core detection & linking"),
        // B4: per-platform assignment screen — which emulator + core each console uses, and how
        // many of its games override that (with bulk clearing of those overrides).
        XMBItem(id = "settings_emulators_assign", title = "Per-System Defaults", subtitle = "Default emulator & core per console, and per-game overrides"),
        XMBItem(id = "settings_emulators_knowledge", title = "Emulator knowledge", subtitle = "Updates, your own files & reset to built-in"),
    )
    SettingsSection.INTERFACE -> listOf(
        XMBItem(id = "settings_display",    title = "Display",    subtitle = "Wave, wallpaper, boot & icons"),
        // Phase 3 of the seven-sounds plan: renamed Audio → Sound (it owns the boot sound now).
        // The id deliberately stays settings_audio — the route, SETTINGS_SCREEN_ROUTES, the row
        // focus keys (audio_<slot>) and SettingsHierarchyTest all key off it.
        XMBItem(id = "settings_audio",      title = "Sound",      subtitle = "Menu & boot sounds"),
        // Sits with Display and Sound: the panel is part of how the launcher presents itself,
        // not a library concern.
        XMBItem(id = "settings_notifications", title = "Notifications", subtitle = "Panel history & retention"),
        XMBItem(id = "settings_categories", title = "Categories", subtitle = "Manage XMB categories"),
        XMBItem(id = "settings_themes",     title = "Themes",     subtitle = "XMB appearance, colors & icons"),
        XMBItem(id = "settings_controller", title = "Controller", subtitle = "Button mapping"),
    )
    SettingsSection.ACHIEVEMENTS -> listOf(
        // Same first-pass note as Emulators: the combined Shiba Coins screen, distinct ids.
        XMBItem(id = "settings_achievements_player_card",   title = "Player Card",          subtitle = "Levels, ranks & sync status"),
        XMBItem(id = "settings_achievements_credentials",   title = "Provider Credentials", subtitle = "RetroAchievements & Steam accounts"),
        XMBItem(id = "settings_achievements_local_windows", title = "Local Windows",        subtitle = "Track local Windows (Steam-emu) games"),
        XMBItem(id = "settings_achievements_update",        title = "Update Achievements",  subtitle = "Sync & auto-match tracked games"),
    )
    SettingsSection.MEDIA -> listOf(
        XMBItem(id = "settings_music", title = "Music", subtitle = "Music folders & default player"),
        XMBItem(id = "settings_video", title = "Video", subtitle = "Video libraries, scanning & playback"),
        XMBItem(id = "settings_photo", title = "Photo", subtitle = "Photo libraries & scanning"),
    )
    SettingsSection.SYSTEM -> listOfNotNull(
        XMBItem(id = "settings_about",  title = "About",            subtitle = "Play Field Portal"),
        XMBItem(id = "settings_logs",   title = "Logs",             subtitle = "Debug & error log viewer"),
        // Parked until the backup rework; the route stays registered.
        XMBItem(id = "settings_backup", title = "Backup & Restore", subtitle = "Export & import")
            .takeIf { FeatureFlags.BACKUP_RESTORE },
        XMBItem(id = XMBViewModel.INITIAL_SETUP_SCREEN_ID, title = "Setup Wizard", subtitle = "Guided folder & account setup"),
        XMBItem(id = "settings_credits", title = "Credits",         subtitle = "Artwork & attributions"),
    )
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
    // The bare sort mode ("Title"), non-null on track views (AllMusic / a Playlist's tracks).
    // Deliberately without the "Sort: " prefix, which belongs to the drawing — see [sortPillLabel].
    val sortLabel: String? = null,
    // Bumped to snap the list back to the top (sort change / query change).
    val scrollToTopToken: Int = 0,
    // Whether the controller cursor should be drawn. Owned by [browserNav] (the shared navigation
    // engine), not inferred from the last input source: touch hides it, and the next controller
    // press re-anchors it to the nearest visible row before it reappears.
    val cursorVisible: Boolean = true,
    // Focus signal for the (always-visible) search field: true puts the caret in it and raises the
    // keyboard. Unlike the app picker's flag this does not show or hide the field — the browser's
    // field is permanent, because its query is the list's filter and hiding it would hide why the
    // list looks the way it does.
    val searchActive: Boolean = false,
    // The loaded song, for the now-playing strip along the bottom of the browser. Null when nothing
    // is loaded, which is also when the strip is absent rather than blank.
    val nowPlaying: MusicBrowserNowPlaying? = null,
) {
    /**
     * The sort hint as the UI draws it — "Sort: Title".
     *
     * Two places render this (the header pill and the browser's Options row), and each adds the
     * prefix itself. When [sortLabel] also carried one the pair produced "Sort: Sort: Title", so
     * the prefix lives here, once, and [sortLabel] stays the bare mode name.
     */
    val sortPillLabel: String? get() = sortLabel?.let { "Sort: $it" }
}

/**
 * The Games column's search field, while it is open.
 *
 * [text] filters the column live on every keystroke — the field is not a form to submit, so
 * Confirm only dismisses it. [textOnOpen] is what Cancel restores, which is why the query is not
 * simply read back off [XMBUiState.gameQuery].
 */
data class GameSearchFieldState(val text: String, val textOnOpen: String)

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
    // Whether the controller cursor should be drawn, owned the same way the browser owns its own:
    // touch hides it and the next controller press re-anchors it to the nearest visible track
    // before showing it again. Deliberately not inferred from `lastInputWasTouch`, which a finger
    // scrolling the list never updates — so a touch session used to leave the highlight riding a
    // row far off screen.
    val cursorVisible: Boolean = true,
)

// ── Main XMB state ────────────────────────────────────────────────────────────

/**
 * One rung of the home screen's drill-out ladder — what a single Back (button, left-edge pull,
 * leftward swipe, or D-pad LEFT) unwinds next. [XMBUiState.drillOutStep] picks the rung from state
 * and [XMBViewModel.backOutOfDrill] performs it; keeping the choice separate from the doing is what
 * lets the ladder's precedence be pinned by a plain state test, with no ViewModel to build.
 */
enum class DrillOutStep {
    SETTINGS_SECTION,
    MUSIC,
    /** A video Library backs out to the Libraries list before leaving Video. */
    VIDEO_LIBRARY,
    VIDEO_PLAYLIST,
    VIDEO_COLLECTION_CHILD,
    VIDEO,
    /** A photo album backs out to the Albums list before leaving Photo. */
    PHOTO_LIBRARY,
    PHOTO,
    SOCIAL,
    ACHIEVEMENTS,
    /** A Games platform folder, collection, All Games or Favorites. */
    PLATFORM_FOLDER,
}

data class XMBUiState(
    // ── Horizontal axis: platforms (SD cards) + utility tabs ──────────────
    val categories: List<Category> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val platformGameCounts: Map<String, Int> = emptyMap(),
    // Total real games (content_type = GAME) across all platforms — the "All Games" count.
    val allGamesCount: Int = 0,
    // Count of favorited entries — drives the Games-root "Favorites" item visibility.
    val favoritesCount: Int = 0,
    // Games flagged missing by the reconciler. Tracked separately because every other count here
    // comes from queries that filter is_missing = 0, so missing rows are invisible to them.
    val missingCount: Int = 0,
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
    // gameSortMode and appSortMode are the GLOBAL sorts; a list with a sort of its own is in
    // listSortOverrides. Read the sort in force through activeGameSort / activeSortFor.
    val gameSortMode: XmbSortMode = XmbSortMode.TITLE,
    val appSortMode: XmbSortMode = XmbSortMode.TITLE,
    val listSortOverrides: Map<String, XmbSortMode> = emptyMap(),
    // Each list's stored Custom order and pinned games, by list key.
    val listStates: Map<String, com.playfieldportal.core.domain.model.ListState> = emptyMap(),
    // Non-null while a row is being moved (Move in a Custom-sorted list).
    val moveSession: MoveSession? = null,
    // A category lifted on the crossbar, sliding left / right until placed or cancelled.
    val categoryMoveSession: CategoryMoveSession? = null,
    // Multi-select in a games list: the marked games, and whether marking is on at all.
    val markMode: Boolean = false,
    val markedGameIds: Set<Long> = emptySet(),
    // The game the user inserted as each gaming column's UMD, by category id.
    val umdInserted: Map<String, Long> = emptyMap(),
    val musicSortMode: XmbSortMode = XmbSortMode.TITLE,
    val videoSortMode: XmbSortMode = XmbSortMode.TITLE,
    val sortLabel: String? = null,
    // In-app music player: visible when a song is selected; playback state mirrors the controller.
    val musicPlayerVisible: Boolean = false,
    val musicPlayback: com.playfieldportal.feature.xmb.music.MusicPlaybackState =
        com.playfieldportal.feature.xmb.music.MusicPlaybackState(),
    // Which field the player draws behind its chrome — one of VisualizerIds, persisted.
    val musicVisualizerId: String = VisualizerIds.OFF,
    // Player chrome (banner, metadata, transport, clock). Auto-hides only when a field is up:
    // under `Off` hiding everything would leave a bare wallpaper with no way back but a blind tap.
    val musicChromeVisible: Boolean = true,
    // Cursor in the visualizer picker strip; null means the strip is closed. While it is non-null
    // the strip owns the D-pad's horizontal axis (see onGamepadAction).
    val musicPickerIndex: Int? = null,

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

    // ── Section landing ─────────────────────────────────────────────────────
    // True from moving to a section until its root rows first arrive; those rows then put the
    // cursor on the section's default row (see landedFrom). True at start-up: the first section
    // lands the same way.
    val landingPending: Boolean = true,
    // Bumped by each landing, so the list snaps to the default row instead of scrolling there.
    val landingToken: Int = 0,

    // ── Input source (drives the on-screen touch-navigation button) ────────
    // True when touch was the most recent input, false when a controller/key was. Flips only on a
    // real input event, so the contextual button doesn't flicker.
    val lastInputWasTouch: Boolean = false,
    // The user's chosen controller button-glyph style (Settings ▸ Controller ▸ Display Type).
    // True when the user has been idle on an item that has a context menu, while touch controls
    // are active and no overlay is up — drives the small "Options" hint pill. See
    // XMBViewModel's idle-timer loop for the gate conditions.
    val showContextMenuHint: Boolean = false,
    // True when the user has been idle inside the App Drawer with a controller — the drawer's own
    // contextual hint bar (see shouldShowAppDrawerHint). Deliberately a separate flag from
    // showContextMenuHint: the drawer is a blocking overlay, so the XMB pill's gate is false
    // exactly while the drawer is open; AppDrawerScreen renders its own pill from this flag.
    val showAppDrawerHint: Boolean = false,
    // True when the user has been idle on the Sound settings screen with a controller. Settings
    // overlays are blocking by design, so this needs its own idle flag rather than reusing the XMB
    // context-menu hint (whose blocking-overlay gate would always suppress it).
    val showSettingsHint: Boolean = false,
    // True when the user has been idle in the notification panel with a controller. Its own flag
    // for the same reason the App Drawer's is: the panel is a blocking overlay, so the XMB pill's
    // gate is false exactly while the panel is open, and this one is true only then.
    val showNotificationHint: Boolean = false,
    // True when the user has been idle on a media screen (video, photo, music browser, player or
    // track picker) with a controller. Those screens are overlays too, so they need their own flag.
    val showMediaHint: Boolean = false,
    // User setting (Display ▸ Options Hint). When false the idle hint never shows.
    val contextMenuHintEnabled: Boolean = true,
    // User-configured idle delay in seconds, clamped to 1..5 and defaulting to the original 2.5s.
    val contextMenuHintDelaySeconds: Float = 2.5f,
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
    // Looping motion wallpaper (MP4/WebM/GIF) behind the XMB. Only meaningful together with
    // [customWallpaperPath] — the poster is the freeze/failure fallback, so on read "motion set,
    // poster missing" degrades to "no motion" rather than trying to recover.
    val motionWallpaperPath: String? = null,
    // Normalized source-frame crop for an MP4/WebM motion wallpaper (null = center-crop).
    val motionCrop: com.playfieldportal.themekit.MotionCrop? = null,

    val showBootSequence: Boolean = true,
    // The user's boot media, when assigned (Settings ▸ Display ▸ Boot Sequence). Null means the
    // built-in logo animation / silence — both are supported, in any combination.
    val bootVideoPath: String? = null,
    val bootAudioPath: String? = null,
    /**
     * Boot Sequence's resolved level. Carried in state rather than read by the overlay so the
     * chime follows a slider that is being dragged, and so the overlay stays draw-only.
     */
    val bootAudioGain: Float = 1f,
    /** The GameBoot level, for a custom clip's own track; the built-in sound gets it from its player. */
    val gameBootAudioGain: Float = 1f,
    // The GameBoot presentation currently on screen, from the launch gate or from the settings
    // preview. Null the rest of the time.
    val activeGameBoot: com.playfieldportal.feature.launcher.GameBootRequest? = null,
    // True when [activeGameBoot] came from Settings ▸ Display ▸ GameBoot ▸ Preview, which must
    // never touch the gate — nothing is launching.
    val gameBootIsPreview: Boolean = false,
    // Startup choreography: the boot sequence holds on a black frame until MainActivity reports
    // the notification-permission dialog is out of the way, so the order on a fresh install is
    // permission dialog -> boot animation -> first-run setup wizard.
    val startupPermissionsSettled: Boolean = false,
    // True once checkInitialSetup has resolved (wizard opened, seeded, or already seen). The
    // boot animation also holds on this, so on a fresh install the wizard is guaranteed to be
    // composed beneath the boot overlay before the dissolve can reveal what's under it.
    val initialSetupDecided: Boolean = false,

    // ── Overlay screens ───────────────────────────────────────────────────
    val activeSettingsScreen: String? = null,
    // Category Manager's landing spot when the category menu opened it: one category, with its
    // rename or icon picker up. Cleared once the screen has applied it.
    val settingsCategoryTarget: com.playfieldportal.feature.settings.viewmodel.CategoryManagerTarget? = null,
    // Set for one composition when the Windows card's Batch Match Local Games is chosen: the shell
    // owns the SAF tree launcher, so the ViewModel can only ask for the pick.
    val requestLocalSteamFolderPick: Boolean = false,
    // Set for one composition when an Import Playlist row or menu entry is chosen: the shell owns
    // the multi-file picker, and the kind says which playlists the picked files will fill.
    val requestPlaylistImportPick: PlaylistKind? = null,
    // The drilled-into Settings L1 section — non-null while its two-pane flyout shows the L2 rows,
    // null at the flat section root. Deliberately NOT part of hasBlockingOverlay: the flyout is
    // XMB foreground, so input keeps driving the item list exactly like every other drill.
    val settingsSectionNav: SettingsSection? = null,
    // Settings ▸ Controller ▸ Left Backs Out. Mirrored from ControllerLayoutRepository for the XMB's
    // own LEFT (Settings screens never back out on LEFT). Default true matches the pref's.
    val leftBacksOut: Boolean = true,
    val pendingSettingsAction: GamepadAction? = null,
    val activeAppDrawerFilter: String? = null,
    val pendingDrawerAction: GamepadAction? = null,
    val pendingGameDetailAction: GamepadAction? = null,
    val activeGameId: Long? = null,
    // The dedicated Shiba Coins screen overlay (set from the game context menu / glance strip /
    // hub rows) — a library game or an account entry with no library copy.
    val activeShibaCoinsTarget: com.playfieldportal.feature.xmb.ui.detail.ShibaCoinsTarget? = null,
    val pendingShibaCoinsAction: GamepadAction? = null,
    // True when the Game Detail screen should fire its Play action as soon as the game loads —
    // set by direct-launch confirms and the Options menu's "Launch Game" entry; cleared on close.
    val activeGameAutoLaunch: Boolean = false,
    // The specific disc to open (and auto-launch) when [activeGameId] is set — set by the game
    // context menu's "Choose Disc" so a direct-launch user can pick a non-primary disc. The
    // primary remains the default whenever this is null.
    val activeGameDiscId: Long? = null,
    // Global launch behavior: confirm on a game launches directly (true) or opens Detail (false).
    val directLaunch: Boolean = false,
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

    // ── Games filter (Square / the status pill) ───────────────────────────
    // The Games column's live search term; blank = unfiltered. Games only: Music and Video keep
    // the plain sort cycle. Cleared whenever the list it was typed against changes (drilling into
    // a Memory Card, opening a collection, leaving Games) — see [clearGameQuery].
    val gameQuery: String = "",
    // The transient search field, while open. Non-null means it owns input, like a name dialog.
    val gameSearchField: GameSearchFieldState? = null,

    // ── Shiba Coins hub: Root (summary + lenses) → Rarest Earned drill ─────
    val achievementsNav: AchievementsNav = AchievementsNav.Root,
    val libraryStanding: com.playfieldportal.core.domain.achievement.LibraryStanding =
        com.playfieldportal.core.domain.achievement.LibraryStanding(),
    // True once RA or Steam credentials are connected — shows the player card before any sync.
    val achievementsConnected: Boolean = false,
    // (done, total) while the hub's "Update Installed Achievements" runs; null when idle.
    val hubSyncAll: Pair<Int, Int>? = null,
    // True while the hub's "Auto-Matching" pass runs (hubSyncAll then carries match progress).
    val hubMatching: Boolean = false,
    // Fullscreen All Tracked / Untracked overlay (null = closed).
    val activeShibaLibrary: ShibaLibraryMode? = null,
    val pendingShibaLibraryAction: GamepadAction? = null,
    // Fullscreen "Search online" overlay, opened from the library's pinned action row. It draws
    // over the library (which stays open behind it), and nothing it shows is ever written.
    val activeSearchOnline: Boolean = false,
    val pendingSearchOnlineAction: GamepadAction? = null,
    // Fullscreen player status view, opened from the Shiba Coin player card (XMB + Settings).
    val activePlayerStatus: Boolean = false,
    val pendingPlayerStatusAction: GamepadAction? = null,

    // ── Discord QR login overlay ──────────────────────────────────────────
    val activeDiscordLogin: Boolean = false,
    // ── Discord Social drill: Root (account) → Account (hub) → Friends ─────
    val socialNav: SocialNav = SocialNav.Root,
    val socialAccountAvatarUrl: String? = null,   // cached so the drill sibling column can render it
    // Voice room (M4): the polled call snapshot, plus a one-shot flag the shell watches to request
    // the RECORD_AUDIO grant before the first join.
    val voiceState: com.playfieldportal.core.domain.discord.DiscordVoiceState =
        com.playfieldportal.core.domain.discord.DiscordVoiceState.Idle,
    val requestMicPermission: Boolean = false,
    // One-shot: ask the shell to open the "Draw over other apps" settings for the PTT overlay.
    val requestOverlayPermission: Boolean = false,
    // True while the Voice Settings "PTT Button" row is waiting to capture the next controller press.
    val capturingPttKey: Boolean = false,

    // ── Color-scheme picker (Settings ▸ Themes ▸ Color Scheme) ─────────────
    val colorSchemePicker: ColorSchemePickerState? = null,
    val customColorPicker: com.playfieldportal.core.ui.components.HsvPickerState? = null,

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

    // ── Playlist import results: one Results sheet per picked file, shown in turn ──
    val playlistImportQueue: PlaylistImportQueue? = null,

    // ── A context-menu action waiting on its confirm (drawn by the shell's shared Confirm modal) ──
    val pendingConfirm: XmbConfirm? = null,

    // ── One-time "finish setting up your Windows Library" prompt ───────────
    // Raised by the pin workflow when a PC shortcut arrived before setup was complete
    // (docs/windows-library-refactor-plan.md section 3); consumed on first XMB open.
    val showWindowsSetupPrompt: Boolean = false,
    // PFP's virtual keyboard is open (VirtualKeyboardController owns the session; mirrored here
    // so the blocking-overlay guard sees it).
    val virtualKeyboardOpen: Boolean = false,
    /** A legacy INSTALL_SHORTCUT request waiting for Add / Ignore (it used to be asked in the shade). */
    val shortcutReview: com.playfieldportal.core.data.repository.PendingShortcutRequest? = null,

    // ── Launch recovery sheet (B1) ─────────────────────────────────────────
    // Non-null while the recovery sheet should be drawn over the shell.
    val launchRecovery: com.playfieldportal.feature.launcher.LaunchRecoveryRequest? = null,

    // ── Installed-app picker (Android Library / Video / Music) ─────────────
    val appPicker: AppPickerState? = null,

    // ── Game picker (for adding games to gaming categories) ────────────────
    val gamePickerCategoryId: String? = null,
    // Set instead when the picker fills a custom memory card (the card's Add Games).
    val gamePickerCollectionId: Long? = null,
    // The games already put in that category or card — the picker opens with them checked, and
    // only one of these that the user unchecks is removed.
    val gamePickerPreselected: Set<Long> = emptySet(),
    val pendingGamePickerAction: GamepadAction? = null,

    // ── Notification panel (START, or the bell leading the status strip) ──
    // Non-null while the panel is open; it joins hasBlockingOverlay below, without which the
    // D-pad would keep driving the crossbar behind an open panel.
    val notificationPanel: NotificationPanelState? = null,
    // RUNNING: live work, in memory, never a Room row — a persisted running task would strand at
    // 40% after a force-stop with nothing alive left to finish or fail it (plan section 4.2).
    val runningTasks: List<BackgroundTaskInfo> = emptyList(),
    // EARLIER: the durable history, newest first, in the DAO's own order.
    val notifications: List<com.playfieldportal.core.domain.model.PfpNotification> = emptyList(),
    val unreadNotifications: Int = 0,

    // ── Misc ──────────────────────────────────────────────────────────────
    val iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    // Global icon display mode (Custom ICON0 / Box Art / Physical Media / 3D Box) — the default
    // every console follows until it is given an override of its own. Per-game overrides ride on
    // each XMBItem; resolution happens at render via [resolveIconDisplay].
    val iconDisplayMode: IconDisplayMode = IconDisplayMode.DEFAULT,
    // Per-console overrides keyed by platform id; a console absent here follows [iconDisplayMode].
    val iconDisplayModeByPlatform: Map<String, IconDisplayMode> = emptyMap(),
    // Icon legibility treatment (PSP-style matte behind XMB silhouette glyphs), Display ▸
    // Appearance. Provided as LocalIconLegibility; NONE renders today's glyph exactly.
    val iconLegibility: com.playfieldportal.core.domain.model.IconLegibilityStyle =
        com.playfieldportal.core.domain.model.IconLegibilityStyle.DEFAULT,
    // "Solid Unfocused Icons": when true, unselected XMB icons skip the unfocused alpha dim
    // (selection still reads by icon size and label). Default false = today's dimming.
    val solidUnfocusedIcons: Boolean = false,
    // "Text Shadow": directional drop shadow behind XMB row subtitles (the faded gray helper
    // text), so it stays readable over bright wallpaper regions. Default on — without it the
    // subtitle is the only row label with no separation treatment.
    val textShadow: Boolean = true,
    // "Item List Motion" (Display): how the item column steps — Rewind's hand-off or one Glide.
    val itemListMotion: com.playfieldportal.core.domain.model.XmbListMotion =
        com.playfieldportal.core.domain.model.XmbListMotion.DEFAULT,
    // "UMD Slot" (Display): Off, only an inserted game, or inserted else last played.
    val umdSlotMode: com.playfieldportal.core.domain.model.UmdSlotMode =
        com.playfieldportal.core.domain.model.UmdSlotMode.DEFAULT,
    // The focused game's ICON1 video snap — set only after the linger + battery gates pass.
    val focusedGameVideo: com.playfieldportal.feature.xmb.ui.FocusedGameVideo? = null,
    val librarySetupComplete: Boolean = false,
    val themeColors: PFPColors = DefaultPFPColors,
    // Both icon tiers — the user's picks (custom-icons/) over the applied theme's (theme-icons/).
    // Provided as LocalXmbIcons; render sites ask it rather than restating the precedence.
    val xmbIcons: com.playfieldportal.core.ui.icons.XmbIcons = com.playfieldportal.core.ui.icons.XmbIcons.EMPTY,
    // Non-null while the live "Customize XMB Icons" editor is open (rendered over the real XMB).
    val customIconSession: CustomIconSession? = null,
    // One-shot: a saved-theme bundle awaiting the share sheet (Save as Theme… flow). Consumed
    // by XMBShell via onThemeShareConsumed once ACTION_SEND has fired.
    val pendingThemeShareFile: java.io.File? = null,
    // One-shot forwarded pad action for the icon editor (SELECT / OPTIONS / BACK); the overlay
    // consumes it via onCustomIconsActionConsumed.
    val pendingCustomIconsAction: GamepadAction? = null,
    // One-shot forwarded pad action for the shell's shared modal (name entry, info notice, Windows
    // setup prompt); XMBShell consumes it via onShellModalActionConsumed.
    val pendingShellModalAction: GamepadAction? = null,
    // Non-null while the "Save as Theme…" name dialog is up over the icon editor.
    val saveThemeNameDialog: PlaylistNameDialogState? = null,
    // Per-theme XMB geometry (crossbar line, headroom, previous-item rise). DEFAULT holds the
    // pixel-tuned authentic-PSP values; imported themes may override (theme-kit XmbLayoutSpec).
    val layoutSpec: com.playfieldportal.themekit.XmbLayoutSpec = com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT,
    // Whole-launcher UI scale (Display ▸ Scale & Layout) — applied as a density multiplier at
    // the shell root so every screen scales together to fit different devices. Legacy default used
    // when the active form-factor has no saved layout adjustment.
    val xmbScale: Float = 1f,
    // Saved "Adjust XMB Layout" tunings, keyed by form-factor bucket (XmbFormFactor.key). The shell
    // resolves the entry for the current screen; absent = fall back to [xmbScale] + theme bar line.
    val xmbLayoutAdjustMap: Map<String, com.playfieldportal.themekit.XmbLayoutAdjust> = emptyMap(),
    // Non-null while the live layout editor is open (rendered over the real XMB).
    val xmbLayoutAdjust: XmbLayoutAdjustSession? = null,
) {
    // True when the user has drilled into a sub-item on the home screen (a Games platform/collection/
    // All Games/Favorites, or a Music sub-view). Drives the floating Back button and locks Left/Right
    // category switching until the user backs out.
    //
    // Defined as "there is a level to back out of" rather than as its own list of conditions: the
    // ladder below and this flag used to be two hand-written lists that had to agree, and three
    // callers (gamepad BACK, touch Back, and now D-pad LEFT) is exactly where they would drift.
    val isInSubItem: Boolean
        get() = drillOutStep != null

    // The single rung [XMBViewModel.backOutOfDrill] would unwind next, or null at the category
    // root. Order IS the precedence: two-level video/photo paths back out through their list
    // before leaving the section, so each press climbs exactly one level.
    val drillOutStep: DrillOutStep?
        get() = when {
            settingsSectionNav != null -> DrillOutStep.SETTINGS_SECTION
            musicNav != MusicNav.Root -> DrillOutStep.MUSIC
            videoNav is VideoNav.Library -> DrillOutStep.VIDEO_LIBRARY
            videoNav is VideoNav.Playlist -> DrillOutStep.VIDEO_PLAYLIST
            videoNav.isVideoCollectionChild -> DrillOutStep.VIDEO_COLLECTION_CHILD
            videoNav != VideoNav.Root -> DrillOutStep.VIDEO
            photoNav is PhotoNav.Library -> DrillOutStep.PHOTO_LIBRARY
            photoNav != PhotoNav.Root -> DrillOutStep.PHOTO
            socialNav != SocialNav.Root -> DrillOutStep.SOCIAL
            achievementsNav != AchievementsNav.Root -> DrillOutStep.ACHIEVEMENTS
            selectedPlatformId != null || selectedCollectionId != null -> DrillOutStep.PLATFORM_FOLDER
            else -> null
        }

    // The item currently under the XMB cursor, or null.
    val focusedItem: XMBItem?
        get() = currentItems.getOrNull(selectedItemIndex)

    // True iff a Y/Triangle press on the focused item would open a context menu — the exact mirror
    // of XMBViewModel.onItemLongPress / dispatchGamepadAction(OPEN_CONTEXT_MENU)'s when-branches, so the
    // idle hint and the real trigger never drift apart. Computed (never stored) so it stays
    // current with the cursor without plumbing at every stepItem call site.
    val focusedItemHasContextMenu: Boolean
        get() = focusedItem?.hasContextMenu(this) == true

    // True iff an X/Square press would re-sort the list currently on screen — drives the Sort
    // half of the hint pill. Computed, like focusedItemHasContextMenu, so it tracks the cursor
    // and the drill level without plumbing.
    val canSortCurrentList: Boolean
        get() = activeSortModes() != null || arrangeableRootKind() != null

    // What the CHANGE_SORT half of the hint pill is called here. Games opens a Filter menu rather
    // than cycling, and the pill names actions — so it has to say which action.
    val sortActionLabel: String
        get() = if (activeSortModes() === GAME_SORTS) "Filter" else "Sort"

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
            activeGameBoot != null ||
            notificationPanel != null ||
            activeSettingsScreen != null ||
            activeAppDrawerFilter != null ||
            activeGameId != null ||
            activeShibaCoinsTarget != null ||
            activeShibaLibrary != null ||
            activePlayerStatus ||
            activeAppId != null ||
            activeVideoId != null ||
            activePhotoViewer != null ||
            activeContextMenu != null ||
            gameSearchField != null ||
            activeDiscordLogin ||
            colorSchemePicker != null ||
            customColorPicker != null ||
            xmbLayoutAdjust != null ||
            customIconSession != null ||
            saveThemeNameDialog != null ||
            appPicker != null ||
            gamePickerCategoryId != null ||
            gamePickerCollectionId != null ||
            renameAppTarget != null ||
            collectionNameDialog != null ||
            playlistNameDialog != null ||
            musicTrackPicker != null ||
            musicBrowser != null ||
            musicPlayerVisible ||
            infoDialog != null ||
            playlistImportQueue != null ||
            pendingConfirm != null ||
            launchRecovery != null ||
            showWindowsSetupPrompt ||
            virtualKeyboardOpen ||
            shortcutReview != null ||
            // A move in progress owns the D-pad: nothing else may act on the list under it.
            moveSession != null ||
            categoryMoveSession != null
}

enum class XMBItemType {
    STANDARD,
    ALL_GAMES,
    FAVORITES,
    // The Missing bucket: games whose ROM file was gone on the last trustworthy scan. Sits beside
    // All Games / Favorites and only appears when something is actually missing.
    MISSING,
    MEMORY_CARD,
    COLLECTION,
    // A custom gaming category's own Memory Card: every game in the category, loose or in one of
    // its custom memory cards. The category's counterpart of All Games.
    CATEGORY_CARD,
    // The game "inserted" in a gaming column, always its first row. Drawn as the PSP's UMD until
    // focused, when it becomes the game's own icon over the game's art.
    UMD_SLOT,
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
    SOCIAL_VOICE,             // hub row → drills into the Voice room
    SOCIAL_VOICE_CREATE,      // "Create Lobby" — requests mic + hosts a fresh private lobby
    SOCIAL_VOICE_INVITE,      // "Invite Friends" (in a lobby) → drills into the friend picker
    SOCIAL_VOICE_INVITES,     // "Invites (N)" → drills into pending invites + join requests
    SOCIAL_VOICE_INVITE_ROW,  // a pending invite / join request (id = "vinv_<index>_<0|1 request>")
    SOCIAL_VOICE_FRIEND_PICK, // a friend row in the invite picker (id = "vpick_<userId>")
    SOCIAL_VOICE_MUTE,        // mute/unmute toggle (in a room)
    SOCIAL_VOICE_SETTINGS,    // entry row → drills into Voice Settings
    SOCIAL_VOICE_TOGGLE,      // a boolean voice setting (noise/echo/agc), flipped on select
    SOCIAL_VOICE_CYCLE,       // a multi-value voice setting (sensitivity, volumes), cycled on select
    SOCIAL_VOICE_LEAVE,       // leave the room + call
    SOCIAL_ACTIVITY_SETTINGS, // hub row → drills into Activity Settings
    SOCIAL_DISCORD_SETTINGS,  // hub row → drills into Discord Settings
    SOCIAL_FRIEND,            // a single friend (L3) — also a voice participant row
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
    // The user's own arrangement of one list, saved per list. Never part of a sort cycle.
    CUSTOM("Custom"),
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

/**
 * Does this game answer to [query]? Case-insensitive substring over the **display** title — the
 * title the row actually shows, which a manual override can move away from [Game.title]. Matching
 * the raw title would hide a game under exactly the name the user just gave it.
 *
 * Substring rather than prefix on purpose: "zelda" has to find "The Legend of Zelda".
 */
internal fun Game.matchesGameQuery(query: String): Boolean =
    displayTitle.lowercase().contains(query)

/**
 * Every Games row the column shows, in order: **filter by the live query, then sort**.
 *
 * The single funnel for the Games column. Every path through loadItemsForCategory goes through
 * this one function rather than calling [gameSorted] itself — there are eight of them (All Games,
 * a Memory Card, Favorites, Missing, a collection, a gaming category, …) and a filter applied at
 * the call sites would eventually miss one, leaving a screen that silently ignores search.
 *
 * Filtering first is both cheaper and clearer: the sort then provably orders exactly what is on
 * screen. A blank or whitespace-only query filters nothing at all.
 */
internal fun gamesForDisplay(
    games: List<Game>,
    query: String,
    mode: XmbSortMode,
    listState: com.playfieldportal.core.domain.model.ListState = com.playfieldportal.core.domain.model.ListState.EMPTY,
    // When each game was added to THIS list (a custom memory card, a category's Memory Card), by
    // game id. Empty for lists with no such moment — Date Added is then when the game joined the
    // library.
    dateAdded: Map<Long, Long> = emptyMap(),
): List<Game> {
    val q = query.trim().lowercase()
    val filtered = if (q.isBlank()) games else games.filter { it.matchesGameQuery(q) }
    val sorted = when {
        // Custom is the stored order laid over title order, so a list nobody has arranged yet —
        // and any game the order has never seen — still lands alphabetically.
        mode == XmbSortMode.CUSTOM -> com.playfieldportal.core.domain.model.ListArrangement.customOrder(
            filtered.gameSorted(XmbSortMode.TITLE), listState.positions,
        ) { com.playfieldportal.core.domain.model.ListKeys.game(it.id) }
        // Newest addition to this list first; games with no recorded time fall back to the
        // library order, after the ones that have one.
        mode == XmbSortMode.DATE_ADDED && dateAdded.isNotEmpty() -> filtered.sortedWith(
            compareByDescending<Game> { dateAdded[it.id] ?: 0L }.thenByDescending { it.id }
        )
        else -> filtered.gameSorted(mode)
    }
    // A pin outranks every sort.
    return com.playfieldportal.core.domain.model.ListArrangement.pinnedFirst(sorted) {
        com.playfieldportal.core.domain.model.ListKeys.game(it.id) in listState.pinned
    }
}

/**
 * The Games Filter menu's rows, shaped like the Shiba Library's Options menu: the root names each
 * list with its current choice (label "Search", value "None"), and a group checks the active one.
 * A naming row's value is a separate field, so the menu pins it to the panel's right edge.
 *
 * Pure and top-level so the rows can be asserted without a ViewModel, and so the menu can never
 * disagree with the state it describes.
 *
 * "Clear Search" appears only while a query is active — a row that would do nothing is worse than
 * a shorter menu, and it is the fast way off a filtered column.
 */
fun gamesFilterRows(state: XMBUiState, group: GamesFilterGroup?): List<XMBContextMenuItem> =
    when (group) {
        null -> buildList {
            val term = state.gameQuery.trim()
            // Label and value are separate fields so the values pin to the panel's right edge and
            // line up with each other. They used to be one string joined by two spaces, which the
            // approved mockup never showed: it puts the value at the far edge, dimmer than the label.
            add(
                XMBContextMenuItem(
                    GAMES_FILTER_SEARCH_ID,
                    "Search",
                    value = if (term.isBlank()) "None" else "\"$term\"",
                )
            )
            // The sort belongs to the list on screen: its own, or "Global: …" while it follows the
            // global setting.
            val sortValue = state.currentListKey()?.let { state.sortValueLabel(it, state.openListSortKind) }
                ?: state.activeGameSort.label
            add(XMBContextMenuItem(GAMES_FILTER_SORT_ID, "Sort", value = sortValue))
            if (term.isNotBlank()) add(XMBContextMenuItem(GAMES_FILTER_CLEAR_ID, "Clear Search"))
        }
        // The list's own Sort picker: follow the global setting, take a sort of its own, or Custom.
        GamesFilterGroup.SORT -> listSortMenuItems(
            kind = state.openListSortKind,
            global = if (state.openListSortKind == XmbListKind.APPS) state.appSortMode else state.gameSortMode,
            override = state.listSortOverrides[state.currentListKey()],
        )
    }

const val GAMES_FILTER_SEARCH_ID = "games_filter_search"
const val GAMES_FILTER_SORT_ID = "games_filter_sort"
const val GAMES_FILTER_CLEAR_ID = "games_filter_clear"

/**
 * Where the cursor belongs after the list it is on is refreshed: on the same row, found by id.
 * Game lists sort by display title, so a rename re-sorts the list under the cursor. Keeping the
 * INDEX would leave it on whichever game moved into that slot. A row that is gone falls back to
 * the old index, clamped to the new list.
 */
internal fun cursorAfterRefresh(previous: List<XMBItem>, previousIndex: Int, next: List<XMBItem>): Int {
    val selectedId = previous.getOrNull(previousIndex)?.id
    val kept = selectedId?.let { id -> next.indexOfFirst { it.id == id } } ?: -1
    return if (kept >= 0) kept else previousIndex.coerceIn(0, (next.size - 1).coerceAtLeast(0))
}

// One row per logical game for display-only counts (card subtitles, the All Games total). The
// snapshot is the DAO's projection (GameDao.observeAll): present singles, plus the primary of every
// set that still has a present disc. That primary can itself be missing while another disc is
// present — the card's list shows the row, so it counts here too; dropping it made a card read
// "1 Game" over a list of two. Without a primary row the set falls back to a present disc.
internal fun List<Game>.projectGamesForDisplay(): List<Game> {
    val singles = filter { it.discSetKey == null && !it.isMissing }
    val sets = groupBy { it.discSetKey }
        .filterKeys { it != null }
        .values
        .mapNotNull { members ->
            val display = members.firstOrNull { it.isDiscPrimary }
                ?: members.firstOrNull { !it.isMissing }
                ?: return@mapNotNull null
            // A favorite on any member makes the logical set favorite; preserve that signal when
            // this snapshot feeds the Favorites count and card badges.
            display.copy(isFavorite = members.any { it.isFavorite })
        }
    return singles + sets
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

/** Which context menu a row opens. One kind per `openXxxContextMenu` family. */
enum class ContextMenuKind {
    MUSIC, VIDEO, PHOTO, ACHIEVEMENTS, MARK_MODE, GAME, COLLECTION, ALL_GAMES, ROOT_ROW,
    SOCIAL_ACCOUNT, PLATFORM, APP,
}

/** What an Options press resolves to: the [kind] of menu, and whether it was opened by touch. */
data class ContextMenuTarget(val kind: ContextMenuKind, val byTouch: Boolean)

/**
 * The one resolver behind Y/Triangle ([byTouch] false) and long-press ([byTouch] true): which menu
 * [item] opens under [state], or null when it has none. `XMBViewModel.openContextMenuFor` dispatches
 * on it and [hasContextMenu] is `!= null`, so the idle hint, the pad and the finger cannot disagree.
 * Pure (no side effects) so the hint can read it without opening a menu. The per-category
 * `openXxxContextMenu` functions start with a category guard that returns false on mismatch; those
 * guards are reproduced here as category-id checks so this never calls the side-effectful openers.
 * Multi-select (mark mode) wins over the game rows: Options there acts on everything marked, so it
 * resolves even with no focused row.
 */
fun contextMenuTarget(item: XMBItem?, state: XMBUiState, byTouch: Boolean): ContextMenuTarget? {
    val kind = contextMenuKind(item, state) ?: return null
    // Missing's only row is Open, which repeats the press: nothing to show on a pad.
    if (!byTouch && item?.type == XMBItemType.MISSING && rootRowMenuItems(null, canMove = false, byTouch = false).isEmpty()) return null
    return ContextMenuTarget(kind, byTouch)
}

private fun contextMenuKind(item: XMBItem?, state: XMBUiState): ContextMenuKind? {
    val categoryId = state.categories.getOrNull(state.selectedCategoryIndex)?.id
    if (item == null) return if (state.markMode) ContextMenuKind.MARK_MODE else null
    return with(item) {
        when {
            // The Music memory card / tracks / Now Playing / playlists / music-apps.
            categoryId == BuiltInCategory.MUSIC && (
                id == XMBViewModel.NOW_PLAYING_ITEM_ID ||
                    id == XMBViewModel.ALL_MUSIC_ITEM_ID ||
                    type == XMBItemType.MUSIC_TRACK ||
                    (type == XMBItemType.PLAYLIST && playlistId != null) ||
                    (state.musicNav == MusicNav.MusicApps && packageName != null)
            ) -> ContextMenuKind.MUSIC
            // The Videos memory card / files / libraries / playlists / video-apps.
            categoryId == BuiltInCategory.VIDEO && (
                id == XMBViewModel.ALL_VIDEOS_ITEM_ID ||
                    (type == XMBItemType.VIDEO_FILE && id.startsWith("vid_")) ||
                    (type == XMBItemType.VIDEO_FOLDER && id.startsWith("vlib_")) ||
                    (type == XMBItemType.PLAYLIST && playlistId != null) ||
                    (state.videoNav == VideoNav.VideoApps && packageName != null)
            ) -> ContextMenuKind.VIDEO
            // The Photos memory card / files / libraries / photo-apps.
            categoryId == BuiltInCategory.PHOTO && (
                id == XMBViewModel.ALL_PHOTOS_ITEM_ID ||
                    (type == XMBItemType.PHOTO_FILE && id.startsWith("pho_")) ||
                    (type == XMBItemType.PHOTO_FOLDER && id.startsWith("plib_")) ||
                    (state.photoNav == PhotoNav.PhotoApps && packageName != null)
            ) -> ContextMenuKind.PHOTO
            // Achievements hub: the summary / all / untracked rows.
            categoryId == BuiltInCategory.ACHIEVEMENTS &&
                (id == XMBViewModel.ACH_ALL_ITEM_ID ||
                    id == XMBViewModel.ACH_SUMMARY_ITEM_ID ||
                    id == XMBViewModel.ACH_UNTRACKED_ITEM_ID) -> ContextMenuKind.ACHIEVEMENTS
            // Multi-select: Options acts on everything marked, not on the focused row.
            state.markMode -> ContextMenuKind.MARK_MODE
            gameId != null -> ContextMenuKind.GAME
            collectionId != null && type == XMBItemType.COLLECTION -> ContextMenuKind.COLLECTION
            type == XMBItemType.ALL_GAMES -> ContextMenuKind.ALL_GAMES
            // Sort for the list behind the row, and Move while the root is Custom sorted.
            type == XMBItemType.CATEGORY_CARD ||
                type == XMBItemType.FAVORITES ||
                type == XMBItemType.MISSING -> ContextMenuKind.ROOT_ROW
            type == XMBItemType.SOCIAL_ACCOUNT -> ContextMenuKind.SOCIAL_ACCOUNT
            platformId != null -> ContextMenuKind.PLATFORM
            packageName != null -> ContextMenuKind.APP
            else -> null
        }
    }
}

/** True iff Options on [this] item would open a context menu under [state] — see [contextMenuTarget]. */
fun XMBItem.hasContextMenu(state: XMBUiState): Boolean =
    contextMenuTarget(this, state, byTouch = false) != null

/**
 * The sort modes valid for the list currently on screen, or null when it isn't sortable (the
 * Games memory-card root, the Music root, playlist lists, and app sections don't sort).
 *
 * Pure and top-level for the same reason as [XMBItem.hasContextMenu]: the idle hint has to ask
 * "can this list sort?" without triggering a sort, and both it and the real X/Square handler now
 * read one function, so the pill cannot promise an action the press won't perform.
 */
fun XMBUiState.activeSortModes(): List<XmbSortMode>? {
    val cat = categories.getOrNull(selectedCategoryIndex) ?: return null
    return when {
        cat.id == BuiltInCategory.MUSIC &&
            (musicNav == MusicNav.AllMusic || musicNav is MusicNav.Playlist) -> MUSIC_SORTS
        // Video lists sort, except the intrinsically-ordered ones (recency / manual playlist).
        cat.id == BuiltInCategory.VIDEO &&
            (videoNav == VideoNav.AllVideos || videoNav == VideoNav.Favorites ||
                videoNav is VideoNav.Library) -> VIDEO_SORTS
        // A games list: any drilled-in card or custom memory card. A column's root holds cards,
        // not games — it is arranged through [arrangeableRootKind] instead.
        categoryShowsCollections(cat) &&
            (selectedPlatformId != null || selectedCollectionId != null) -> GAME_SORTS
        else -> null
    }
}

/**
 * What the list on screen holds when it is one that Sort opens a plain Sort picker for — a gaming
 * column's root or an app list — rather than the Games Filter menu. Null everywhere else.
 */
fun XMBUiState.arrangeableRootKind(): XmbListKind? =
    currentListKind()?.takeIf { it == XmbListKind.ROOT || it == XmbListKind.APPS }

/**
 * Pure decision: should the idle context-menu hint be visible right now? Top-level so unit tests
 * can exercise it without a ViewModel instance. Gates:
 *  - the most recent input came from a controller (not touch);
 *  - no blocking overlay, no open context menu ([XMBUiState.hasBlockingOverlay],
 *    [XMBUiState.activeContextMenu]);
 *  - the pill has something true to say — the focused item has a context menu
 *    ([XMBUiState.focusedItemHasContextMenu]) or the current list can sort
 *    ([XMBUiState.canSortCurrentList]);
 *  - the idle delay [idleMs] has elapsed (>= IDLE_HINT_DELAY_MS).
 *
 * Deliberately NOT gated on [XMBUiState.isInSubItem]: drilled-in items (the game flyout, a
 * library's files) have context menus and can sort, so that gate hid the hint exactly where a
 * new user is most likely to need it. The App Drawer button it used to pair with is touch-only
 * and this hint is controller-only, so the two are never really on screen together anyway.
 */
fun shouldShowContextMenuHint(state: XMBUiState, idleMs: Long): Boolean =
    state.contextMenuHintEnabled &&
        !state.lastInputWasTouch &&
        !state.hasBlockingOverlay &&
        state.activeContextMenu == null &&
        (state.focusedItemHasContextMenu || state.canSortCurrentList) &&
        idleMs >= (state.contextMenuHintDelaySeconds * 1_000f).toLong()

/**
 * Pure decision: should the App Drawer's contextual controller hint bar be visible right now?
 * Top-level so unit tests can exercise it without a ViewModel instance. Gates:
 *  - the most recent input came from a controller (not touch);
 *  - the App Drawer is actually open ([XMBUiState.activeAppDrawerFilter] non-null);
 *  - no context menu is up (one can never sit over the drawer, but the gate stays symmetric with
 *    [shouldShowContextMenuHint]);
 *  - the idle delay [idleMs] has elapsed.
 *
 * Deliberately separate from [shouldShowContextMenuHint]: the drawer is a blocking overlay
 * ([XMBUiState.hasBlockingOverlay]), so the XMB pill's gate is false whenever the drawer is open —
 * and this gate is true only then. Both share the same idle clock and the
 * contextMenuHintEnabled / contextMenuHintDelaySeconds settings, so Display ▸ Options Hint
 * toggles the drawer hint too.
 */
fun shouldShowAppDrawerHint(state: XMBUiState, idleMs: Long): Boolean =
    state.contextMenuHintEnabled &&
        !state.lastInputWasTouch &&
        state.activeAppDrawerFilter != null &&
        state.activeContextMenu == null &&
        idleMs >= (state.contextMenuHintDelaySeconds * 1_000f).toLong()

/**
 * Pure decision for the settings helper footer. It deliberately does not use
 * [XMBUiState.hasBlockingOverlay], because the settings screen itself is the overlay that owns
 * this footer. Keeping the same delay and enable setting as the XMB/App Drawer makes all helper
 * chrome appear on one timing contract.
 *
 * Any open settings screen qualifies — the gate is simply "a settings screen is up". SettingsScaffold
 * already renders the footer band on every non-wizard screen and falls back to the Enter/Back
 * prompts when the screen supplies no items of its own, so restricting this to a named screen only
 * ever left the other screens with a reserved band that could never fill in. The wizard passes its
 * own themed footer and never consults this flag.
 */
fun shouldShowSettingsHint(state: XMBUiState, idleMs: Long): Boolean =
    state.contextMenuHintEnabled &&
        !state.lastInputWasTouch &&
        state.activeSettingsScreen != null &&
        idleMs >= (state.contextMenuHintDelaySeconds * 1_000f).toLong()

/**
 * Pure decision: should the notification panel's idle hint be visible right now?
 *
 * The panel is where the affordances are least guessable in the whole app — nothing on screen
 * says that Confirm opens a row or that the list has a menu of its own — and it is reached by a
 * button most users will press once out of curiosity. So it earns the same idle pill the crossbar
 * and the App Drawer already have, on the same clock and the same setting.
 *
 * Gated on the context menu being closed: the menu the pill is advertising is already open, and a
 * hint offering Options over it would name an action that no longer does that.
 */
fun shouldShowNotificationHint(state: XMBUiState, idleMs: Long): Boolean =
    state.contextMenuHintEnabled &&
        !state.lastInputWasTouch &&
        state.notificationPanel != null &&
        !state.notificationPanel.hasLayer &&
        state.activeContextMenu == null &&
        idleMs >= (state.contextMenuHintDelaySeconds * 1_000f).toLong()

/**
 * Pure decision for the media screens' prompt rows: video (detail and player), the photo viewer,
 * the music browser, the music player and its track picker. They used to show their prompts
 * permanently; they now keep the crossbar's contract — fade in after the idle delay, gone on the
 * next press — on the same clock and the same Display ▸ Options Hint setting.
 */
fun shouldShowMediaHint(state: XMBUiState, idleMs: Long): Boolean =
    state.contextMenuHintEnabled &&
        !state.lastInputWasTouch &&
        (state.activeVideoId != null ||
            state.activePhotoViewer != null ||
            state.musicBrowser != null ||
            state.musicPlayerVisible ||
            state.musicTrackPicker != null) &&
        idleMs >= (state.contextMenuHintDelaySeconds * 1_000f).toLong()

data class XMBItem(
    val id: String,
    val title: String,
    val artworkUri: String? = null,
    val heroUri: String? = null,        // PIC1 / hero background art
    val iconUri: String? = null,        // landscape 144:80 icon art (SGDB horizontal grid)
    val logoUri: String? = null,        // clear logo — the PIC0-style overlay on the hover bg
    // Icon-display-mode artwork + the game's per-mode override; resolved against the global
    // setting at render time by [resolveIconDisplay].
    val boxArtUri: String? = null,
    val physicalMediaUri: String? = null,
    val box3dUri: String? = null,
    val iconDisplayModeOverride: String? = null,
    val subtitle: String? = null,
    // A controller prompt appended to the subtitle: the physical position plus its label.
    // Deliberately a position and not a GamepadAction — the only user today is the PTT capture,
    // whose cancel reads a RAW keycode before any mapping is applied, so no action describes it.
    // Domain types, so the item model stays free of Compose.
    val subtitleHintIcon: ControllerIcon? = null,
    val subtitleHintLabel: String? = null,
    val gameId: Long? = null,
    val platformId: String? = null,
    val collectionId: Long? = null,     // set on COLLECTION rows in the Games root
    val iconKey: String? = null,        // catalog icon key for COLLECTION rows (null = default memory-card art)
    val accentColor: Long? = null,
    val isFavorite: Boolean = false,
    val isAndroidApp: Boolean = false,
    // True for contentType GAME rows — real games open the Game Detail page on select, even when
    // package/shortcut-backed (Android/Windows gaming apps). Standard apps launch directly.
    val isRealGame: Boolean = false,
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
    // Text-only row: never draws a leading icon/tile and always shows its label, regardless of
    // selection. Used by the Shiba Coins "Untracked" list so games read as plain text + reason.
    val textOnly: Boolean = false,
    // When set, the leading slot draws a tinted circle with this text centered (e.g. "Lv 27") —
    // the Shiba Coins player-card summary row.
    val levelBadge: String? = null,
    // Prestige Bones earned (player-card summary row only); renders "• N [bone glyph]" after the
    // title when greater than zero.
    val boneCount: Int = 0,
    // Pinned to the top of its list. Rows only ever move among rows of the same pinned state.
    val pinned: Boolean = false,
    val type: XMBItemType = XMBItemType.STANDARD,
)

/**
 * The tile art [GameIcon] should draw for [item] under the given global mode.
 * [naturalAspect] = render at the art's own aspect (fit-inside, chrome hugging the fitted
 * bounds); false = PSP-authentic 144:80 edge-to-edge fill. [mode] is the RESOLVED mode and
 * drives the placeholder when [uri] is null: PHYSICAL_MEDIA → the bundled per-platform
 * cartridge/disc icon; BOX_ART / BOX_3D → a letter tile shaped like the platform's box.
 */
data class ResolvedIcon(val uri: String?, val naturalAspect: Boolean, val mode: IconDisplayMode)

// Top-level and pure so mode/fallback behaviour is unit-testable. Each mode shows ONLY its
// own asset, and each owns its missing-art placeholder so switching modes always visibly
// changes the tile: ICON0 → the 144:80 letter tile, BOX_ART / BOX_3D → a letter tile in the
// platform's box shape, PHYSICAL_MEDIA → the bundled per-platform cartridge/disc icon.
//
// Resolution order is game override > console override ([platformModes], keyed by platform id)
// > the global mode, so picking a mode on one Memory Card never moves any other console.
fun resolveIconDisplay(
    item: XMBItem,
    globalMode: IconDisplayMode,
    platformModes: Map<String, IconDisplayMode> = emptyMap(),
): ResolvedIcon {
    val mode = IconDisplayMode.fromName(item.iconDisplayModeOverride)
        ?: item.platformId?.let { platformModes[it] }
        ?: globalMode
    return when (mode) {
        IconDisplayMode.ICON0 ->
            ResolvedIcon(item.iconUri, naturalAspect = false, mode = mode)
        IconDisplayMode.BOX_ART ->
            ResolvedIcon(item.boxArtUri, naturalAspect = true, mode = mode)
        IconDisplayMode.PHYSICAL_MEDIA ->
            ResolvedIcon(item.physicalMediaUri, naturalAspect = item.physicalMediaUri != null, mode = mode)
        IconDisplayMode.BOX_3D ->
            ResolvedIcon(item.box3dUri, naturalAspect = true, mode = mode)
    }
}

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
    private val memoryCardRepository: MemoryCardRepository,
    private val collectionRepository: CollectionRepository,
    private val categoryRepository: CategoryRepositoryImpl,
    private val appCategoryRepository: AppCategoryRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val gameCategoryRepository: com.playfieldportal.core.data.repository.GameCategoryRepository,
    private val launcherShortcutRepository: LauncherShortcutRepository,
    private val libraryScanner: LibraryScanner,
    private val artworkRepository: ArtworkRepository,
    @ApplicationContext private val context: Context,
    private val gamepadInputHandler: GamepadInputHandler,
    private val remapCoordinator: com.playfieldportal.core.data.repository.RemapCoordinator,
    private val mappingRepository: ControllerMappingRepository,
    private val controllerLayoutRepository: com.playfieldportal.core.data.repository.ControllerLayoutRepository,
    private val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer,
    private val musicRepository: com.playfieldportal.core.domain.repository.MusicRepository,
    private val musicScanner: com.playfieldportal.feature.library.scanner.MusicScanner,
    private val musicPlayer: com.playfieldportal.feature.xmb.music.MusicPlayerController,
    private val emulatorProfileRepository: com.playfieldportal.feature.launcher.EmulatorProfileRepository,
    // Which emulator launches a game — shared with Game Detail, so a console default decides both.
    private val gameLaunchLadder: com.playfieldportal.feature.launcher.GameLaunchLadder,
    private val intentResolver: com.playfieldportal.feature.launcher.EmulatorIntentResolver,
    private val videoRepository: com.playfieldportal.core.domain.repository.VideoRepository,
    private val photoRepository: com.playfieldportal.core.domain.repository.PhotoRepository,
    private val photoScanner: com.playfieldportal.feature.library.scanner.PhotoScanner,
    private val scanRunner: com.playfieldportal.feature.settings.media.WizardMediaScanRunner,
    private val hiddenPlacementDao: com.playfieldportal.core.data.database.dao.HiddenPlacementDao,
    private val discordAuthRepository: com.playfieldportal.core.data.discord.DiscordAuthRepository,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
    private val discordVoice: com.playfieldportal.core.data.discord.DiscordVoiceController,
    private val pttOverlay: com.playfieldportal.feature.xmb.voice.PttOverlayManager,
    private val iconDisplayPreferences: com.playfieldportal.core.data.repository.IconDisplayPreferences,
    private val artworkStore: com.playfieldportal.feature.artwork.store.ArtworkStore,
    private val artworkRelinkLauncher: com.playfieldportal.feature.artwork.api.ArtworkRelinkLauncher,
    private val gameLaunchPreferences: com.playfieldportal.core.data.repository.GameLaunchPreferences,
    private val achievementRepository: com.playfieldportal.feature.achievements.AchievementController,
    private val achievementMatchAndUpdate: com.playfieldportal.feature.achievements.match.AchievementMatchAndUpdate,
    // Enqueues the scheduled achievement check when PFP is used (startup and each return).
    private val achievementAutoUpdates: com.playfieldportal.feature.achievements.sync.AchievementAutoUpdateScheduler,
    private val achievementCredentials: com.playfieldportal.core.data.achievement.AchievementCredentialsProvider,
    private val localAchievementFolders: com.playfieldportal.core.data.repository.LocalAchievementFolders,
    private val windowsLibrarySetup: com.playfieldportal.core.data.repository.WindowsLibrarySetup,
    private val pcShortcutImporter: com.playfieldportal.feature.launcher.PcShortcutImporter,
    private val pcGameScanner: com.playfieldportal.feature.settings.pc.PcGameScanner,
    private val pcGameExporter: com.playfieldportal.feature.settings.pc.PcGameExporter,
    private val localSteamSchemaGenerator: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaGenerator,
    private val localSteamDiscovery: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery,
    private val localSteamBatchMatcher: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamBatchMatcher,
    private val launchDispatcher: com.playfieldportal.feature.launcher.LaunchDispatcher,
    private val shortcutRequests: com.playfieldportal.feature.launcher.ShortcutRequestResolver,
    private val setupStateProvider: com.playfieldportal.feature.launcher.SetupStateProvider,
    private val customIconStore: CustomIconStore,
    private val pfpThemeStore: PfpThemeStore,
    // Both icon tiers on disk and the rule between them (the user's pick over the theme's).
    private val themeTiers: com.playfieldportal.core.data.repository.ThemeTiers,
    // The colours, icons, geometry and boot media the XMB draws, as one observed value.
    private val themeLook: ThemeLook,
    private val uiMediaStore: com.playfieldportal.core.data.repository.UiMediaStore,
    private val gameBootGate: com.playfieldportal.feature.launcher.GameBootGate,
    /** The app's one virtual-keyboard session; fields reach it through LocalVirtualKeyboard. */
    val virtualKeyboard: com.playfieldportal.core.ui.keyboard.VirtualKeyboardController,
    // The preview plays its own audio: the gate owns playback for a real launch, and a preview
    // must never touch the gate. Same singleton player, so the two can never sound different.
    private val uiMediaAudioPlayer: com.playfieldportal.core.ui.media.UiMediaAudioPlayer,
    private val ambienceController: com.playfieldportal.core.ui.sound.AmbienceController,
    private val audioLevels: com.playfieldportal.core.ui.sound.AudioLevels,
    // The notification panel's durable half, and the settings behind it. The panel's RUNNING half
    // never reaches the repository — it lives in [runningTasks] below (plan section 4.2).
    private val notificationRepository: com.playfieldportal.core.domain.repository.NotificationRepository,
    // Shared with every other producer in the app (workers, scanners), so the panel shows all
    // background work rather than only what the XMB itself started.
    private val backgroundTasks: BackgroundTaskCenter,
    private val gameArtworkFetchRunner: GameArtworkFetchRunner,
    // Playlist file import: reads the picked documents, then parses, matches and writes them.
    private val playlistDocumentReader: PlaylistDocumentReader,
    private val playlistImportRunner: PlaylistImportRunner,
    // Per-list arrangement: each list's Custom order, pinned games and own sort; the two global
    // sorts; and the game inserted as each gaming column's UMD.
    private val listStateRepository: com.playfieldportal.core.data.repository.ListStateRepository,
    private val sortPreferences: com.playfieldportal.core.data.repository.SortPreferences,
    private val umdSlotRepository: com.playfieldportal.core.data.repository.UmdSlotRepository,
) : ViewModel() {

    // Drives the "convert detected games?" multi-select picker after a Windows-card scan; the same
    // controller and dialog serve the Library Manager (see LibraryManagerViewModel).
    private val convertPickerController =
        com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamConvertPickerController(
            localSteamSchemaGenerator, viewModelScope,
        )
    val convertPicker: StateFlow<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamConvertPickerController.Picker?> =
        convertPickerController.picker

    fun onConvertToggle(index: Int) = convertPickerController.toggle(index)
    fun onConvertSelectAllNone() {
        val picker = convertPickerController.picker.value ?: return
        convertPickerController.setAll(picker.rows.any { !it.selected && !it.unselectable })
    }
    fun onConvertConfirm() = convertPickerController.confirm()
    fun onConvertSkip() = convertPickerController.skip()
    fun onConvertCancel() = convertPickerController.cancel()

    /**
     * Controller input while the convert panel is open. True when the panel took it.
     *
     * The panel sits ABOVE the card it was opened from — the same layering the coins page uses — so
     * this is checked before the XMB's own navigation, and before Start reaches the notification
     * panel: inside this panel Start means Install.
     */
    fun onConvertGamepadAction(action: GamepadAction): Boolean =
        convertPickerController.onGamepadAction(action)

    /** True while the convert panel owns the screen. */
    val convertPanelOpen: Boolean get() = convertPickerController.picker.value != null

    /**
     * Batch Match Local Games from the Windows card's context menu.
     *
     * The picked tree and everything after it are identical to the Library Manager's row — one
     * matcher, one convert panel, one report — so the two surfaces cannot drift.
     */
    fun batchMatchLocalGames(parentFolder: android.net.Uri) {
        viewModelScope.launch {
            val taskId = "xmb_local_steam_batch"
            val open = NotificationAction.OpenMemoryCard(WINDOWS_PLATFORM_ID)
            backgroundTasks.startStoppable(taskId, "Matching local Windows games…", TaskKind.SCAN,
                stopNote = "Games already matched stay linked.")
            var error: Throwable? = null
            val report = try {
                runCatching { localSteamBatchMatcher.run(parentFolder) }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        Timber.e(it, "Local Steam batch match failed"); error = it
                    }
                    .getOrNull()
            } catch (e: kotlinx.coroutines.CancellationException) {
                backgroundTasks.settleCancelled(taskId, "Stopped before it finished", open,
                    title = "Windows achievements match stopped")
                throw e
            }
            if (report == null) {
                backgroundTasks.fail(
                    taskId, "Batch match failed", open,
                    detail = NotificationDetail.notes(PfpErrorCode.AC_2001, summary = error?.message,
                        diagnostic = error?.stackTraceToString()?.take(4_000)),
                    title = "Windows achievements match failed",
                )
                return@launch
            }
            backgroundTasks.complete(taskId, report.message, open, title = "Windows achievements match finished")
            // The convertible pile — the one place a DLL swap is ever authorised, per game.
            convertPickerController.start(report.convertible) { outcome ->
                viewModelScope.launch {
                    val linked = runCatching { localSteamBatchMatcher.linkAndSync(outcome.convertedFolders) }
                        .onFailure { Timber.e(it, "Linking converted Local Steam folders failed") }
                        .getOrNull()
                    convertOutcomeMessage(outcome)?.let { msg ->
                        val id = "schema_gen_windows"
                        addBackgroundTask(
                            BackgroundTaskInfo(id = id, label = "Achievement schemas", kind = TaskKind.ACHIEVEMENT)
                        )
                        completeBackgroundTask(
                            id,
                            msg + (linked?.let { " ${it.linkedToLibrary} linked to library games." } ?: ""),
                        )
                    }
                }
            }
        }
    }

    /** Asks the shell to open the folder picker for a batch match. Cleared once launched. */
    fun onBatchMatchPickLaunched() = _uiState.update { it.copy(requestLocalSteamFolderPick = false) }

    /** Asks the shell to open the playlist file picker. Cleared once launched. */
    private fun requestPlaylistImportPick(kind: PlaylistKind) =
        _uiState.update { it.copy(requestPlaylistImportPick = kind) }

    fun onPlaylistImportPickLaunched() = _uiState.update { it.copy(requestPlaylistImportPick = null) }

    /** The picker's result: reads and imports each file, then queues one Results sheet per report. */
    fun onPlaylistFilesPicked(kind: PlaylistKind, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val reports = playlistImportRunner.run(kind, playlistDocumentReader.read(uris))
            if (reports.isEmpty()) return@launch
            _uiState.update { s ->
                s.copy(playlistImportQueue = s.playlistImportQueue?.plus(reports) ?: PlaylistImportQueue(reports))
            }
        }
    }

    /** Close on an import sheet: on to the next file's report, if any. */
    fun closePlaylistImportSheet() =
        _uiState.update { it.copy(playlistImportQueue = it.playlistImportQueue?.advance()) }

    /** Open Playlist: drops the rest of the queue and goes to the playlist the import created. */
    fun openImportedPlaylist(report: PlaylistImportReport) {
        val id = report.playlistId ?: return
        val name = report.playlistName.orEmpty()
        _uiState.update { it.copy(playlistImportQueue = null) }
        when (report.kind) {
            PlaylistKind.MUSIC -> openMusicBrowser(MusicBrowserView.Playlist(id, name))
            PlaylistKind.VIDEO -> openVideoView(VideoNav.Playlist(id, name))
        }
    }

    private fun convertOutcomeMessage(
        outcome: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamConvertPickerController.Outcome,
    ): String? {
        if (outcome.converted == 0 && outcome.noAchievements == 0 &&
            outcome.noKey == 0 && outcome.failed == 0
        ) {
            return null
        }
        return buildString {
            append("Converted ${outcome.converted} game(s)")
            if (outcome.noAchievements > 0) append(", ${outcome.noAchievements} had no achievements")
            if (outcome.noKey > 0) append(", ${outcome.noKey} need a Steam Web API key")
            if (outcome.failed > 0) append(", ${outcome.failed} failed")
            append(". Play each game to start earning coins.")
        }
    }

    // The track list currently on screen (in display/sort order), used as the in-app player's queue
    // when a song is picked. [currentMusicTracksRaw] is the same set in DB order, kept so a sort
    // cycle can re-order instantly without waiting on a fresh DB emission.
    private var currentMusicTracks: List<MusicTrack> = emptyList()
    private var currentMusicTracksRaw: List<MusicTrack> = emptyList()

    /**
     * The fullscreen browser's on-screen track list — its play queue.
     *
     * Deliberately NOT [currentMusicTracks]. That field belongs to the XMB's own item list, and
     * [refreshMusicRootPreservingCursor] clears it every time the Music root rebuilds. The root
     * rebuilds whenever the "Now Playing" row's contents change, i.e. the moment a song starts —
     * so a browser sharing the field lost its queue the instant it played anything, and every
     * later pick fell out of [openMusicPlayerForItem] on the empty-list guard, silently.
     */
    private var browserQueue: List<MusicTrack> = emptyList()

    /**
     * Controller navigation for the fullscreen browser, on the shared engine rather than a private
     * index. The engine owns cursor visibility, stable-key focus across rebuilds, nearest-survivor
     * recovery when the focused row is filtered away, and no-wrap clamping — all of which the
     * hand-rolled `selectedIndex` either got wrong or did not have. `MusicBrowserState.selectedIndex`
     * is now derived from [NavigationEngine.focusedKey] purely so the list has something to scroll to.
     */
    private val browserNav = NavigationEngine("music_browser", NavigationLogger { Timber.w(it) })

    /**
     * True once a finger has been on the list — a drag or a tap — so the next controller press is
     * the one that takes over from it and re-anchors, rather than the one that navigates.
     */
    private var browserTouchScrolled = false

    /** Viewport centre in the list's own coordinates, reported by the screen with the row window. */
    private var browserViewportCentreY = 0f

    /**
     * The browser's visible window: row id → the row's centre Y, replaced wholesale on every layout.
     *
     * It is deliberately kept here rather than pushed into [browserNav]'s geometry. Those Y values
     * are offsets inside the current viewport, so they are only true of the frame that reported
     * them; the engine's map is persistent and merges, so every row ever seen would keep a value
     * from the scroll position it was last seen at — and that map is what the engine sorts by to
     * decide visual order. After one flick the order became a jumble of two scroll positions and a
     * D-pad press walked it, which is the jump this replaces. The browser's rows are registered top
     * to bottom, which IS its visual order, and an engine with no geometry traverses in exactly that
     * order (spec §7.2) — so the browser never reports geometry at all.
     */
    private var browserVisibleRowCentres: Map<String, Float> = emptyMap()

    /** True once a finger has been on the track picker, so the next controller press revives. */
    private var pickerTouchUsed = false

    /** The picker's visible window: row index → the row's centre Y, replaced on every layout. */
    private var pickerVisibleRowCentres: Map<Int, Float> = emptyMap()
    private var pickerViewportCentreY = 0f

    // Identity of the "Now Playing" row as last published, so the Music root only rebuilds when
    // that row's contents actually change — not on every half-second playback tick.
    private var lastNowPlayingKey: String? = null

    // Cached so a track launch (a discrete event) doesn't need to suspend-read DataStore.
    @Volatile
    private var defaultMusicPlayer: String? = null

    // Idle timer behind [pokeMusicChrome]. One job, always cancelled before a new one starts, so a
    // burst of input cannot stack up several pending hides.
    private var musicChromeJob: Job? = null

    // Elapsed-realtime ms of the most recent user input (touch or controller). Drives the idle
    // context-menu hint: after IDLE_HINT_DELAY_MS with no input, if the focused item has a context
    // menu and touch controls are active, the hint pill fades in. Refreshed by markTouchInput,
    // markControllerInput, and onUserInteraction.
    @Volatile
    private var lastInteractionMs: Long = 0L

    private val _uiState = MutableStateFlow(XMBUiState())
    val uiState: StateFlow<XMBUiState> = _uiState.asStateFlow()

    // Every write to the XMB state comes through here (it takes precedence over kotlinx's
    // `update` inside this class), so a section landing resolves in the same write that first
    // delivers the section's rows, whichever branch built them — see [landedFrom]. Drawing never
    // sees the new rows under the old cursor, so the list snaps to the default row.
    private inline fun MutableStateFlow<XMBUiState>.update(transform: (XMBUiState) -> XMBUiState) {
        while (true) {
            val before = value
            if (compareAndSet(before, transform(before).landedFrom(before))) return
        }
    }

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
    private val hostVisible = MutableStateFlow(true)
    private var enabledCards: List<MemoryCard> = emptyList()
    private var baseThemeColors: PFPColors = DefaultPFPColors


    init {
        scheduleAchievementCheck()
        gamepadInputHandler.scope = viewModelScope
        observeContextMenuHintIdle()
        observeContextMenuOpening()
        observeIconDisplayMode()
        observeFocusedGameVideo()
        observeBackgroundSettings()
        observeTouchNavButtonMode()
        observeWallpaper()
        observeLibrarySetupState()
        checkInitialSetup()
        logStartupSequence()
        observeThemeLook()
        observeCategoryBar()
        observeCategories()
        observeListArrangement()
        observeMissingGames()
        observeAppChanges()
        warmInstalledAppCache()
        observeGamepadMappings()
        observeBootPreferences()
        observeGameBoot()
        observeMusic()
        observeVideo()
        observePhoto()
        observeLibraryStanding()
        observeHiddenPlacements()
        observeEmulatorProfiles()
        collectGamepadActions()
        consumeWindowsSetupPrompt()
        observeVirtualKeyboard()
        observeLaunchRecoveryRequests()
        observeSetupState()
        observeNotifications()
        observeShortcutRequests()
        // Live pin reconcile: an emulator UPDATING an already-pinned shortcut never fires the
        // confirm activity, so the OS callback is the only signal — import it the moment it lands.
        pcShortcutImporter.watchPinChanges(viewModelScope)
    }

    // ── Launch recovery sheet (B1) ─────────────────────────────────────────────
    //
    // The shared LaunchDispatcher raises a LaunchRecoveryRequest whenever a game-path launch
    // fails outright or never reaches the emulator's foreground. This shell is its host: the
    // request lands in XMBUiState so XMBShell can draw the sheet over whatever is on screen,
    // and the gamepad router gives it SELECT/BACK handling like every other overlay.
    private fun observeLaunchRecoveryRequests() {
        viewModelScope.launch {
            launchDispatcher.recoveryRequests.collect { request ->
                _uiState.update { it.copy(launchRecovery = request) }
            }
        }
    }

    /** A button on the recovery sheet. */
    fun onLaunchRecoveryAction(action: LaunchRecoveryAction) {
        when (action) {
            LaunchRecoveryAction.DISMISS -> launchDispatcher.dismissRecovery()
            LaunchRecoveryAction.RETRY   -> {
                val request = _uiState.value.launchRecovery ?: return
                launchDispatcher.dismissRecovery()
                launchGameDirectly(request.gameId)
            }
            LaunchRecoveryAction.CHANGE_EMULATOR -> {
                val gameId = _uiState.value.launchRecovery?.gameId ?: return
                launchDispatcher.dismissRecovery()
                openEmulatorPickerMenu(gameId)
            }
            LaunchRecoveryAction.PER_SYSTEM_DEFAULTS -> {
                launchDispatcher.dismissRecovery()
                _uiState.update { it.copy(activeSettingsScreen = "settings_emulators_assign") }
            }
            LaunchRecoveryAction.COPY_DIAGNOSTIC -> {
                val request = _uiState.value.launchRecovery ?: return
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "PFP launch diagnostic", request.diagnostic,
                ))
                backgroundTasks.report(
                    id = "launch_diag_${request.gameId}",
                    label = request.gameTitle,
                    message = "Diagnostic copied to clipboard",
                    kind = com.playfieldportal.core.domain.model.NotificationKind.LAUNCH,
                    action = NotificationAction.OpenGame(request.gameId),
                )
            }
        }
    }

    // The keyboard's presses sound through the same menu cues as every other tier, and its open
    // state joins the blocking-overlay guard.
    private fun observeVirtualKeyboard() {
        virtualKeyboard.soundSink = { menuSound.play(it) }
        viewModelScope.launch {
            virtualKeyboard.session.collect { session ->
                _uiState.update { it.copy(virtualKeyboardOpen = session != null) }
            }
        }
    }

    // One-shot: a PC shortcut arrived while the Windows Library was unconfigured, so the pin flow
    // flagged a follow-up prompt. Consuming clears the flag — the dialog fires once, never nags.
    private fun consumeWindowsSetupPrompt() {
        viewModelScope.launch {
            if (runCatching { windowsLibrarySetup.consumeSetupPrompt() }.getOrDefault(false)) {
                _uiState.update { it.copy(showWindowsSetupPrompt = true) }
            }
        }
    }

    /** "Set Up" on the Windows setup prompt: straight into Library Manager. */
    fun confirmWindowsSetupPrompt() = _uiState.update {
        it.copy(showWindowsSetupPrompt = false, activeSettingsScreen = "settings_library")
    }

    fun dismissWindowsSetupPrompt() = _uiState.update { it.copy(showWindowsSetupPrompt = false) }

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
                    refreshMediaRootPreservingCursor(musicRootItems())
                }
            }
        }
        viewModelScope.launch {
            musicRepository.observeDefaultPlayerPackage().collect { defaultMusicPlayer = it }
        }
        viewModelScope.launch {
            musicRepository.observeVisualizerId().collect { id ->
                _uiState.update { it.copy(musicVisualizerId = id) }
            }
        }
        viewModelScope.launch {
            // Hidden, the half-second position ticks are not copied in (nothing shows them); the
            // latest state lands the moment the launcher is visible again.
            musicPlayer.state.combine(hostVisible) { playback, visible -> playback to visible }.collect { (playback, visible) ->
                if (!shouldApplyMusicUpdate(_uiState.value.musicPlayback, playback, visible)) return@collect
                _uiState.update { it.copy(musicPlayback = playback) }
                syncBrowserNowPlaying(playback)
                // Rebuild the Music root only when the "Now Playing" row's own contents change —
                // it appearing or disappearing, and equally the song advancing underneath it —
                // never on the half-second position ticks, which the row doesn't show.
                val nowPlayingKey = nowPlayingRowKey(playback)
                if (nowPlayingKey != lastNowPlayingKey) {
                    lastNowPlayingKey = nowPlayingKey
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

    // ── Theme look ──────────────────────────────────────────────────────────────

    // The colours, both icon tiers, the XMB geometry and the boot media, as ThemeLook derives them
    // (each input re-read only when it moves). One theme colour across the whole XMB
    // (PSP-authentic) — no per-category tint.
    private fun observeThemeLook() {
        viewModelScope.launch {
            themeLook.observe().collect { look ->
                baseThemeColors = look.colors
                _uiState.update {
                    it.copy(
                        themeColors = look.colors,
                        xmbIcons = look.icons,
                        layoutSpec = look.layoutSpec,
                        xmbScale = look.xmbScale,
                        xmbLayoutAdjustMap = look.layoutAdjustMap,
                        bootVideoPath = look.bootVideoPath,
                        bootAudioPath = look.bootAudioPath,
                    )
                }
            }
        }
    }

    // ── Category bar (DB-driven) ────────────────────────────────────────────────

    // The main XMB is intentionally the seven PSP-style categories from the launcher spec.
    // Platform folders stay inside Game as Memory Card rows.
    private fun observeCategoryBar() {
        viewModelScope.launch {
            categoryRepository.observeVisible().collect { categories ->
                // The bar on screen IS the move in progress; rebuilding it from the store would
                // snap the lifted category back. Applied once the move ends instead.
                if (_uiState.value.categoryMoveSession != null) {
                    deferredCategoryBar = categories
                    return@collect
                }
                applyCategoryBar(categories)
            }
        }
    }

    // What the store last said while a category was lifted (see observeCategoryBar).
    private var deferredCategoryBar: List<Category>? = null

    private fun applyCategoryBar(categories: List<Category>) {
        val allCategories = canonicalXmbCategories(categories.ifEmpty { FALLBACK_CATEGORIES }, FALLBACK_CATEGORIES)
            // The "lite" build ships without the Discord SDK — drop the Social column there.
            .let { cats ->
                if (discordAuthRepository.isDiscordAvailable()) cats
                else cats.filterNot { it.id == BuiltInCategory.SOCIAL }
            }
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

    // ── Library (memory cards + game counts) ────────────────────────────────────

    // Missing games need their own collector: every other library flow here (observeAll,
    // observeGamesOnly, the per-platform counts) filters is_missing = 0, so a game becoming missing
    // is invisible to observeCategories and the bucket would never appear or update.
    private fun observeMissingGames() {
        viewModelScope.launch {
            gameRepository.observeMissing().collect { missing ->
                val was = _uiState.value.missingCount
                _uiState.update { it.copy(missingCount = missing.size) }
                // Crossing the zero boundary adds or removes the Missing row itself, so the Games
                // root has to rebuild. Count-only changes just re-render the row's subtitle, which
                // memoryCardItems() reads from state on the next natural render.
                if ((was == 0) != (missing.size == 0) &&
                    currentCategory()?.id == BuiltInCategory.GAMES
                ) {
                    loadItemsForCategory(currentCategory())
                }
            }
        }
    }

    private fun observeCategories() {
        viewModelScope.launch {
            combine(
                memoryCardRepository.observeEnabled(),
                gameRepository.observeAll(),
                platformDao.observeAll(),
                collectionRepository.observeCollections(),
                gameRepository.observeFavorites(),
            ) { cards, games, platforms, collections, favorites ->
                CardsGamesPlatformsCollections(cards, games, platforms, collections, favorites)
            }
                .collect { (cards, games, platforms, collections, favorites) ->
                    platformCache = platforms.associateBy { it.id }
                    enabledCards  = cards
                    // Card subtitles count what the card actually shows: real games only. Standard
                    // (unmarked) apps stay rows in the table but are invisible to Memory Cards.
                    val displayGames = games.projectGamesForDisplay()
                    val counts = displayGames.filter { it.contentType == GameContentType.GAME }
                        .groupBy { it.platformId }.mapValues { it.value.size }
                    val gamesOnlyTotal = displayGames.count { it.contentType == GameContentType.GAME }
                    // What Main Game's UMD slot falls back over: every real game, one row per set.
                    allRealGames = displayGames.filter { it.contentType == GameContentType.GAME }
                    // The display snapshot contains only primaries, so a favorite on a secondary
                    // disc would otherwise be lost. observeFavorites already applies the set-level
                    // projection and is the authoritative count for this folder.
                    val favoritesTotal = favorites.size

                    // Drop a stale platform folder if its card was removed or disabled. The
                    // synthetic All Games, Favorites, and Missing folders are always valid.
                    val validPlatformId = _uiState.value.selectedPlatformId
                        ?.takeIf { id ->
                            id == ALL_GAMES_PLATFORM_ID ||
                                id == FAVORITES_PLATFORM_ID ||
                                id == MISSING_PLATFORM_ID ||
                                // A custom category's own Memory Card is not a platform card.
                                id == CATEGORY_CARD_PLATFORM_ID ||
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
                        // Same list, reloaded — and any games-table write lands here, a rename
                        // included. Restarting the list job would otherwise publish it as a fresh
                        // list and leave the cursor on the renamed game's old slot.
                        loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
                    }
                }
        }
    }

    private data class CardsGamesPlatformsCollections(
        val cards: List<MemoryCard>,
        val games: List<Game>,
        val platforms: List<PlatformEntity>,
        val collections: List<GameCollection>,
        val favorites: List<Game>,
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

    // The installed-app enumeration behind every app list is a one-time PackageManager sweep held
    // in AppCategoryRepository's cache. Paying for it on the first drill into Music/Video/Photo
    // Apps is what made that first open slow; warm it at startup instead, off the critical path.
    private fun warmInstalledAppCache() {
        viewModelScope.launch { appCategoryRepository.ensureLoaded() }
    }

    private fun currentCategory(): Category? =
        _uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex)

    // App-populated categories are everything except Settings and Games.
    private fun isAppCategory(categoryId: String): Boolean =
        categoryId != BuiltInCategory.SETTINGS && categoryId != BuiltInCategory.GAMES

    // ── List arrangement (sorts, Custom order, pins, the UMD slot) ───────────────

    // Every real game, one row per disc set — what Main Game's UMD slot falls back over.
    private var allRealGames: List<Game> = emptyList()

    // The game inserted as each gaming column's UMD, resolved from umdInserted's ids.
    private var umdInsertedGames: Map<String, Game> = emptyMap()
    // When each gaming column's UMD was ejected: its slot stays empty until a game is played since.
    private var umdEjectedAt: Map<String, Long> = emptyMap()

    // Set when a reload was asked for while a row was being moved; honoured when the move ends.
    private var reloadAfterMove = false

    private fun observeListArrangement() {
        viewModelScope.launch {
            combine(
                listStateRepository.states,
                listStateRepository.sortOverrides,
                sortPreferences.gamesSortFlow,
                sortPreferences.appsSortFlow,
            ) { states, overrides, gamesSort, appsSort ->
                ListArrangementSnapshot(states, overrides.mapValues { it.value.toXmbSort() }, gamesSort.toXmbSort(), appsSort.toXmbSort())
            }.collect { snapshot ->
                val before = _uiState.value
                val unchanged = before.listStates == snapshot.states &&
                    before.listSortOverrides == snapshot.overrides &&
                    before.gameSortMode == snapshot.gamesSort &&
                    before.appSortMode == snapshot.appsSort
                if (unchanged) return@collect
                _uiState.update { it.copy(
                    listStates = snapshot.states,
                    listSortOverrides = snapshot.overrides,
                    gameSortMode = snapshot.gamesSort,
                    appSortMode = snapshot.appsSort,
                )}
                refreshArrangedList()
            }
        }
        viewModelScope.launch {
            umdSlotRepository.insertedGameIds.collect { ids ->
                umdInsertedGames = ids.mapNotNull { (columnId, gameId) ->
                    gameRepository.getById(gameId)?.let { columnId to it }
                }.toMap()
                _uiState.update { it.copy(umdInserted = ids) }
                if (currentCategory()?.isGamingCategory == true && _uiState.value.currentListKind() == XmbListKind.ROOT) {
                    refreshArrangedList()
                }
            }
        }
        viewModelScope.launch {
            umdSlotRepository.ejectedAt.collect { ejected ->
                umdEjectedAt = ejected
                if (currentCategory()?.isGamingCategory == true && _uiState.value.currentListKind() == XmbListKind.ROOT) {
                    refreshArrangedList()
                }
            }
        }
    }

    private data class ListArrangementSnapshot(
        val states: Map<String, com.playfieldportal.core.domain.model.ListState>,
        val overrides: Map<String, XmbSortMode>,
        val gamesSort: XmbSortMode,
        val appsSort: XmbSortMode,
    )

    /**
     * Re-renders the list on screen after its arrangement changed (a sort, a saved order, a pin).
     * Skipped while a row is being moved: the list on screen IS the move in progress, and
     * rebuilding it from the store would snap the row back under the user's thumb.
     */
    private fun refreshArrangedList() {
        if (_uiState.value.moveSession != null) return
        if (_uiState.value.currentListKey() == null) return
        _uiState.update { it.copy(sortLabel = currentSortLabel()) }
        // A pin or a sort change moves rows; the cursor stays on the row it was on.
        loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
    }

    private fun listState(listKey: String?): com.playfieldportal.core.domain.model.ListState =
        _uiState.value.listStates[listKey] ?: com.playfieldportal.core.domain.model.ListState.EMPTY

    /**
     * The UMD slot row for [columnId] (a gaming category's id), or null when nothing fills it.
     * [columnGames] is the column's own games: the inserted game wins while it is among them,
     * otherwise the most recently played of them.
     */
    private fun umdSlotItem(columnId: String, columnGames: List<Game>): XMBItem? {
        val game = com.playfieldportal.core.domain.model.UmdSlotResolver
            .resolve(umdInsertedGames[columnId], columnGames, umdEjectedAt[columnId], _uiState.value.umdSlotMode) ?: return null
        return listOf(game).toXmbItems().single().copy(id = UMD_SLOT_ITEM_ID, type = XMBItemType.UMD_SLOT)
    }

    /**
     * Publishes a column's top level. The cursor follows its row by id, so a row arriving above
     * it (the UMD slot, once a game has been played) does not shift what is focused. Landing on
     * the column (its default row, under the UMD slot) is [landedFrom]'s.
     */
    private fun publishRootItems(items: List<XMBItem>) {
        if (deferWhileMoving()) return
        _uiState.update { state ->
            val index = cursorAfterRefresh(state.currentItems, state.selectedItemIndex, items)
            state.copy(currentItems = items, selectedItemIndex = index)
        }
    }

    /**
     * True while a row is lifted: a live collector's emission would overwrite the move in
     * progress (and [placeMovingRow] would then save the reverted order), so the list is
     * rebuilt once the move ends instead.
     */
    private fun deferWhileMoving(): Boolean {
        if (_uiState.value.moveSession == null) return false
        reloadAfterMove = true
        return true
    }

    /** The category a collection created from the current context should live in: the current
     *  category when it renders collections, otherwise the Main Game default. */
    private fun collectionHomeCategoryId(): String {
        val cat = currentCategory() ?: return BuiltInCategory.GAMES
        return if (categoryShowsCollections(cat)) cat.id else BuiltInCategory.GAMES
    }

    /**
     * Publishes an Apps sub-view's rows, blanking the list first.
     *
     * Every other media sub-view either builds its rows in place or collects a Room flow, so it
     * replaces [XMBUiState.currentItems] as the navigation happens. The Apps views resolve
     * asynchronously (installed-app enumeration plus a per-app DB read), and until they return the
     * state still holds the view the user just left — which is why opening Photo Apps showed a copy
     * of the Photo root. Clearing first makes the flyout open empty and fill, never wrong.
     */
    private suspend fun publishAppSectionItems(resolve: suspend () -> List<XMBItem>) {
        _uiState.update { it.copy(currentItems = emptyList()) }
        val items = resolve()
        _uiState.update { it.copy(currentItems = items) }
    }

    /**
     * @param keepCursorOnRow when true, even the FIRST game list this load publishes keeps the
     *   cursor on the row it was on, by id. Later emissions of a live game list always do; this is
     *   for a caller that reloads the list already on screen, like Edit Title.
     */
    private fun loadItemsForCategory(category: Category?, keepCursorOnRow: Boolean = false) {
        // A row being moved IS the list on screen; rebuilding it would snap the row back. The
        // reload runs once the move ends instead.
        if (_uiState.value.moveSession != null) {
            reloadAfterMove = true
            return
        }
        currentItemsJob?.cancel()
        // Dropped before the new list is built: a builder left over from the previous view would
        // rebuild a column that is no longer on screen. Each Games branch installs its own.
        rebuildGameRows = null
        if (category == null) { _uiState.update { it.copy(currentItems = emptyList(), sortLabel = null, drillTitle = null, drillSiblings = emptyList(), drillSiblingIndex = 0) }; return }
        val drill = computeDrillTitle()
        val (sibs, sibIdx) = if (drill != null) computeDrillSiblings(category) else (emptyList<XMBItem>() to 0)
        _uiState.update { it.copy(sortLabel = currentSortLabel(), drillTitle = drill, drillSiblings = sibs, drillSiblingIndex = sibIdx) }

        currentItemsJob = viewModelScope.launch {
            when (category.id) {
                BuiltInCategory.FAVORITES -> {
                    var keepCursor = keepCursorOnRow
                    gameRepository.observeFavorites().collect { games ->
                        publishGames(keepCursor) {
                            gameRowsOrEmpty(games.notHiddenAt(HideLocationType.FAVORITES))
                        }
                        keepCursor = true
                    }
                }
                BuiltInCategory.ANDROID -> {
                    _uiState.update { it.copy(currentItems = ANDROID_ITEMS) }
                }
                BuiltInCategory.SETTINGS -> {
                    // Root rows while no section is open; the drilled-into section's L2 rows otherwise.
                    // Always reset the cursor against the newly selected list so nested Settings
                    // cannot retain an out-of-range index from the parent list.
                    _uiState.update { state ->
                        val items = state.settingsSectionNav
                            ?.let(::settingsSectionItems)
                            ?: SETTINGS_ROOT_ITEMS
                        state.copy(
                            currentItems = items,
                            selectedItemIndex = state.selectedItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                        )
                    }
                }
                BuiltInCategory.ACHIEVEMENTS -> {
                    // Rebuilt reactively by observeLibraryStanding() and on nav change; a one-shot
                    // build from the current standing is enough here.
                    _uiState.update {
                        it.copy(currentItems = achievementsItems(it.libraryStanding, it.achievementsNav, it.achievementsConnected, it.hubSyncAll, it.hubMatching))
                    }
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
                    SocialNav.Voice -> _uiState.update { it.copy(currentItems = socialVoiceItems()) }
                    SocialNav.VoiceSettings -> _uiState.update { it.copy(currentItems = socialVoiceSettingsItems()) }
                    SocialNav.VoiceInvites -> _uiState.update { it.copy(currentItems = socialVoiceInvitesItems()) }
                    SocialNav.VoiceInviteFriends -> _uiState.update { it.copy(currentItems = socialVoiceInviteFriendsItems()) }
                    SocialNav.ActivitySettings -> _uiState.update { it.copy(currentItems = socialActivitySettingsItems()) }
                    SocialNav.DiscordSettings -> _uiState.update { it.copy(currentItems = socialDiscordSettingsItems()) }
                }
                BuiltInCategory.GAMES -> {
                    val platformId = _uiState.value.selectedPlatformId
                    val collectionId = _uiState.value.selectedCollectionId
                    if (collectionId != null) {
                        // A user collection — games from any platform, app entries allowed only
                        // because they were explicitly added by the user.
                        var keepCursor = keepCursorOnRow
                        collectionRepository.observeGames(collectionId).collect { games ->
                            val visible = games.notHiddenAt(HideLocationType.COLLECTION, collectionId.toString())
                            // Date Added inside a card is when the game went onto the card.
                            val addedAt = collectionRepository.addedAtByGame(collectionId)
                            publishGames(keepCursor) {
                                gameRowsOrEmpty(visible, ::emptyCollectionItem, dateAdded = addedAt)
                            }
                            keepCursor = true
                        }
                    } else if (platformId == ALL_GAMES_PLATFORM_ID) {
                        // All Games aggregates real games only (content_type = GAME), minus any
                        // the user hid from this card (recoverable in Settings > Hidden Items).
                        // Multi-disc sets project one row (the primary) — see observeAllGames.
                        var keepCursor = keepCursorOnRow
                        gameRepository.observeAllGames().collect { games ->
                            val visible = games.notHiddenAt(HideLocationType.ALL_GAMES)
                            publishGames(keepCursor) { gameRowsOrEmpty(visible, ::emptyAllGamesItem) }
                            keepCursor = true
                        }
                    } else if (platformId == FAVORITES_PLATFORM_ID) {
                        // Favorites folder — every favorited entry (games and app shortcuts).
                        var keepCursor = keepCursorOnRow
                        gameRepository.observeFavorites().collect { games ->
                            val visible = games.notHiddenAt(HideLocationType.FAVORITES)
                            publishGames(keepCursor) { gameRowsOrEmpty(visible, ::emptyFavoritesItem) }
                            keepCursor = true
                        }
                    } else if (platformId == MISSING_PLATFORM_ID) {
                        // The Missing bucket. Deliberately NOT filtered by notHiddenAt: a game the
                        // user hid from a normal view still needs to be reachable here, since this
                        // is the only place "Remove permanently" is offered.
                        var keepCursor = keepCursorOnRow
                        gameRepository.observeMissing().collect { games ->
                            publishGames(keepCursor) {
                                gameRowsOrEmpty(games, ::emptyMissingItem, mapRows = { rows ->
                                    // Each row states why it is here, per the plan. The subtitle
                                    // would otherwise carry play stats that are meaningless for a
                                    // file that isn't there.
                                    rows.map { it.copy(subtitle = MISSING_REASON) }
                                })
                            }
                            keepCursor = true
                        }
                    } else if (platformId != null) {
                        // Multi-disc sets project one row (the primary) — see observePlatformGames.
                        var keepCursor = keepCursorOnRow
                        gameRepository.observePlatformGames(platformId).collect { all ->
                            // Memory Cards show real games only — a standard (unmarked) app row on
                            // this platform stays in the table for art/collections but not here.
                            val games = all.filter { it.contentType == GameContentType.GAME }
                            // Per-card hiding: Android keeps its legacy location type; every other
                            // card hides via PLATFORM keyed by its platform id.
                            val visible = if (platformId == ANDROID_PLATFORM_ID)
                                games.notHiddenAt(HideLocationType.ANDROID_PLATFORM)
                            else
                                games.notHiddenAt(HideLocationType.PLATFORM, platformId)
                            publishGames(keepCursor) {
                                gameRowsOrEmpty(visible, emptyItem = { emptyFolderItem(platformId) })
                            }
                            keepCursor = true
                        }
                    } else {
                        // The Games root re-renders live: a pin/scan can create a card or change
                        // counts while this screen is up (previously a one-shot snapshot that went
                        // stale until the next navigation). enabledCards/counts are refreshed by
                        // observeCategories' collector over these same sources before this fires.
                        combine(
                            memoryCardRepository.observeEnabled(),
                            gameRepository.observeAll(),
                            collectionRepository.observeCollections(),
                        ) { _, _, _ -> }.collect {
                            publishRootItems(memoryCardItems())
                        }
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
                        publishAppSectionItems { musicAppItems() }
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
                    VideoNav.VideoApps -> publishAppSectionItems { videoAppItems() }
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
                    PhotoNav.PhotoApps -> publishAppSectionItems { photoAppItems() }
                }
                else -> {
                    // Gaming categories show games and collections
                    // Drilled into one of this category's collections — show its members. Works
                    // for gaming and non-gaming categories alike: members are games-table rows,
                    // which in non-gaming (app) collections are ANDROID_APP shortcut rows that
                    // launch by package like anywhere else.
                    val openCollectionId = _uiState.value.selectedCollectionId
                    if (openCollectionId != null) {
                        var keepCursor = keepCursorOnRow
                        collectionRepository.observeGames(openCollectionId).collect { games ->
                            val visible = games.notHiddenAt(HideLocationType.COLLECTION, openCollectionId.toString())
                            // Date Added inside a card is when the game went onto the card.
                            val addedAt = collectionRepository.addedAtByGame(openCollectionId)
                            // Apps in a non-gaming category's card read like that category's
                            // own app rows: the name alone, never a game's platform line.
                            val asApps = !category.isGamingCategory
                            publishGames(keepCursor) {
                                gameRowsOrEmpty(
                                    games = visible,
                                    emptyItem = ::emptyCollectionItem,
                                    mapRows = { rows -> if (asApps) rows.map { it.asAppRow() } else rows },
                                    dateAdded = addedAt,
                                )
                            }
                            keepCursor = true
                        }
                        return@launch
                    }
                    if (category.isGamingCategory) {
                        // Every game in the category — put there directly or sitting in one of
                        // its custom memory cards — once each. A one-shot read: the collectors
                        // over games and collections re-run this branch when either changes.
                        val cardGames = gameCategoryRepository.categoryCardGames(category.id)
                            .filterNot { isHiddenAt(HiddenPlacement.gameKey(it.game.id), HideLocationType.CATEGORY, category.id) }
                        if (_uiState.value.selectedPlatformId == CATEGORY_CARD_PLATFORM_ID) {
                            // The category's own Memory Card: the same funnel as every other
                            // Games list, each row saying where in the category the game lives.
                            val sources = cardGames.associate { it.game.id to it.cardNames }
                            val addedAt = cardGames.associate { it.game.id to it.addedAt }
                            publishGames(keepCursorOnRow) {
                                gameRowsOrEmpty(
                                    games = cardGames.map { it.game },
                                    emptyItem = ::emptyCategoryCardItem,
                                    mapRows = { rows ->
                                        rows.map { row ->
                                            row.copy(subtitle = categoryCardSubtitle(row.subtitle, sources[row.gameId].orEmpty()))
                                        }
                                    },
                                    dateAdded = addedAt,
                                )
                            }
                            return@launch
                        }
                        // The root holds containers only — the UMD slot is the one game row. Every
                        // other game is reached through the Memory Card or a custom memory card.
                        val rootKey = com.playfieldportal.core.domain.model.ListKeys.root(category.id)
                        publishRootItems(
                            assembleRoot(
                                umd = umdSlotItem(category.id, cardGames.map { it.game }),
                                movable = listOf(categoryCardItem(category, cardGames.size)) +
                                    customCardRows(category.id, noun = "Game"),
                                trailing = listOf(addGamesItem()),
                                listState = listState(rootKey),
                                custom = _uiState.value.isCustomSorted(rootKey, XmbListKind.ROOT),
                            )
                        )
                    } else {
                        // Non-gaming categories show apps (Photo / Music / Video / Network / App Store / custom).
                        // Apps the user has given artwork (via Edit App Details → a games-table row keyed
                        // by package, typed ANDROID_APP) render that art so the category looks uniform.
                        // The rows stay ANDROID_APP, so this never makes them appear in All Games.
                        val listKey = com.playfieldportal.core.domain.model.ListKeys.root(category.id)
                        val mode = _uiState.value.activeSortFor(listKey, XmbListKind.APPS)
                        // Apps put in one of this category's own custom cards live there, not here.
                        val apps = appCategoryRepository.appsForCategory(category.id)
                            .notHiddenAt(HideLocationType.CATEGORY, category.id)
                            .notInCards(cardedPackagesIn(category.id))
                            .appSorted(mode, listState(listKey))
                        val appItems = apps.map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
                        // Custom memory cards homed in this category (categoryId is the single
                        // source of truth for placement, same as gaming categories) come first in
                        // the default order; under Custom they are arranged with the apps.
                        val rows = arrangeRows(
                            customCardRows(category.id, noun = "App") + appItems,
                            listState(listKey),
                            custom = mode == XmbSortMode.CUSTOM,
                        )
                        val items = if (rows.isEmpty()) listOf(emptyCategoryItem(category)) else rows
                        // "Add Apps" is offered on every app section so the same picker serves
                        // Video, Music, Network, App Store and custom categories alike.
                        publishListItems(items + addAppsItem(), keepCursorOnRow)
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
        pinned       = pinned,
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
        subtitle = "Pick games and custom memory cards to add to this category",
        type     = XMBItemType.ADD_ACTION,
    )

    // A custom gaming category's own Memory Card row — the category's counterpart of All Games.
    private fun categoryCardItem(category: Category, count: Int): XMBItem = XMBItem(
        id       = CATEGORY_CARD_ITEM_ID,
        title    = "${category.name} Memory Card",
        subtitle = "Total Games $count",
        type     = XMBItemType.CATEGORY_CARD,
    )

    // Where a game lives inside its category, after the row's own platform line: "Loose" when it
    // was put in the category directly, otherwise the custom memory card(s) holding it.
    private fun categoryCardSubtitle(base: String?, cardNames: List<String>): String =
        listOfNotNull(base, cardNames.joinToString(", ").ifBlank { "Loose" }).joinToString(" · ")

    private fun emptyCategoryCardItem(): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "No games in this category yet",
        subtitle = "Go back and choose Add Games.",
        type     = XMBItemType.EMPTY,
    )

    // The custom memory cards homed in [categoryId], pinned first, as root rows. [noun] is what
    // they hold in this kind of column ("Game" / "App").
    private fun customCardRows(categoryId: String, noun: String): List<XMBItem> =
        _uiState.value.collections
            .filter { it.categoryId == categoryId }
            .sortedByDescending { it.isPinned }
            .map { collection ->
                val count = "${collection.gameCount} ${if (collection.gameCount == 1) noun else "${noun}s"}"
                XMBItem(
                    id = "col_${collection.id}",
                    title = collection.name,
                    subtitle = customCardSubtitle(collection.isPinned, count),
                    collectionId = collection.id,
                    iconKey = collection.iconKey,
                    pinned = collection.isPinned,
                    type = XMBItemType.COLLECTION,
                )
            }

    // Packages of the apps sitting in [categoryId]'s own custom memory cards. A one-shot read, like
    // the root's: the collections collector re-runs the root when a card's membership changes.
    private suspend fun cardedPackagesIn(categoryId: String): Set<String> =
        _uiState.value.collections
            .filter { it.categoryId == categoryId }
            .flatMap { collectionRepository.observeGames(it.id).first() }
            .mapNotNullTo(mutableSetOf()) { it.packageName }

    // "Custom" tells a custom memory card apart from the Memory Card rows it shares a glyph with.
    private fun customCardSubtitle(pinned: Boolean, count: String): String =
        if (pinned) "Custom · Pinned · $count" else "Custom · $count"

    // A media section's app rows (Music / Video / Photo Apps), in that list's own sort.
    private suspend fun sectionAppItems(sectionId: String): List<XMBItem> {
        val listKey = com.playfieldportal.core.domain.model.ListKeys.apps(sectionId)
        val mode = _uiState.value.activeSortFor(listKey, XmbListKind.APPS)
        return appCategoryRepository.appsForCategory(sectionId)
            .notHiddenAt(HideLocationType.CATEGORY, sectionId)
            .appSorted(mode, listState(listKey))
            .map { it.toXmbItem(gameRepository.getAppEntry(it.packageName)) }
    }

    /** Publishes a non-Games list; with [keepCursorOnRow] the cursor follows its row by id. */
    private fun publishListItems(items: List<XMBItem>, keepCursorOnRow: Boolean) = _uiState.update {
        if (!keepCursorOnRow) it.copy(currentItems = items)
        else it.copy(
            currentItems = items,
            selectedItemIndex = cursorAfterRefresh(it.currentItems, it.selectedItemIndex, items),
        )
    }

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

    /** Refreshes a visible media root without losing focus when rows appear or disappear. */
    private fun refreshMediaRootPreservingCursor(items: List<XMBItem>) {
        val state = _uiState.value
        val selectedId = state.currentItems.getOrNull(state.selectedItemIndex)?.id
        val restored = selectedId
            ?.let { id -> items.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: state.selectedItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
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
                    subtitle = "",
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

    // Music Apps: the apps the user added (stored under a dedicated pseudo-category so they don't
    // mix with the built-in Music category), plus an "Add Music Apps" row.
    private suspend fun musicAppItems(): List<XMBItem> {
        val appItems = sectionAppItems(MUSIC_APPS_CATEGORY_ID)
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
        subtitle = "Add tracks below or from a song's Options menu.",
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
    // current view doesn't support per-location hiding (the Games root).
    private fun currentHideLocation(): Triple<HideLocationType, String, String>? {
        val s = _uiState.value
        val cat = currentCategory()
        return when {
            s.selectedCollectionId != null -> {
                val name = s.collections.firstOrNull { it.id == s.selectedCollectionId }?.name ?: "Custom Card"
                Triple(HideLocationType.COLLECTION, s.selectedCollectionId.toString(), name)
            }
            s.selectedPlatformId == FAVORITES_PLATFORM_ID || cat?.id == BuiltInCategory.FAVORITES ->
                Triple(HideLocationType.FAVORITES, "", "Favorites")
            // No per-location hide in the Missing bucket. It is the only place "Remove permanently"
            // is offered, so hiding a row here would strand the entry: invisible everywhere (it is
            // already filtered out of normal views by is_missing) and no longer removable.
            s.selectedPlatformId == MISSING_PLATFORM_ID -> null
            // A custom category's own Memory Card hides from the category, as its games did
            // when they were listed at the category's root.
            s.selectedPlatformId == CATEGORY_CARD_PLATFORM_ID ->
                cat?.let { Triple(HideLocationType.CATEGORY, it.id, it.name) }
            s.selectedPlatformId == ANDROID_PLATFORM_ID -> Triple(HideLocationType.ANDROID_PLATFORM, "", "Android")
            // The aggregated All Games card — hides from THIS view only; the game stays on its
            // own Memory Card, in collections, and in Favorites.
            s.selectedPlatformId == ALL_GAMES_PLATFORM_ID ->
                Triple(HideLocationType.ALL_GAMES, "", "All Games")
            // Any other Memory Card (ROM platforms, Windows Games) — hides from THIS card only;
            // the game stays in All Games, collections, and categories.
            s.selectedPlatformId != null -> {
                val name = enabledCards.firstOrNull { it.platformId == s.selectedPlatformId }?.displayName
                    ?: s.selectedPlatformId
                Triple(HideLocationType.PLATFORM, s.selectedPlatformId, name)
            }
            // Reached only when no platform or collection is selected — the branches above
            // have already claimed every one of those cases.
            cat != null && cat.isGamingCategory && cat.id != BuiltInCategory.GAMES ->
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
                    refreshMediaRootPreservingCursor(videoRootItems())
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
                    subtitle = "",
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
                subtitle = "",
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
        val appItems = sectionAppItems(VIDEO_APPS_CATEGORY_ID)
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
        item.id == ALL_VIDEOS_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            scanRunner.refreshIfStale(MediaRootKind.VIDEO)
            openVideoView(VideoNav.AllVideos)
            true
        }
        item.id == VIDEO_COLLECTIONS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Collections); true }
        item.id == RECENTLY_WATCHED_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.RecentlyWatched); true }
        item.id == FAVORITE_VIDEOS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Favorites); true }
        item.id == VIDEO_PLAYLISTS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openVideoView(VideoNav.Playlists); true }
        item.id == CREATE_VIDEO_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); promptCreateVideoPlaylist(); true }
        item.id == IMPORT_VIDEO_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); requestPlaylistImportPick(PlaylistKind.VIDEO); true }
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
            scanRunner.kickoffLibrary(MediaRootKind.VIDEO, libId, force = false)
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
            appCategoryRepository.launch(item.packageName); true
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

    // Performs a drill navigation with cursor memory: saves the current view's cursor, applies
    // [mutate] (which must not touch selectedItemIndex), restores the destination view's remembered
    // cursor (0 the first time), then reloads the item list.
    private fun navigateRememberingCursor(mutate: (XMBUiState) -> XMBUiState) {
        val cur = _uiState.value
        viewCursor[cur.viewCursorKey()] = cur.selectedItemIndex
        _uiState.update { state ->
            val next = mutate(state)
            val remembered = viewCursor[next.viewCursorKey()] ?: 0
            // A Games search term belongs to the list it was typed against. Every navigation that
            // changes which list is on screen passes through here, so this is the one place the
            // query has to be dropped — see [clearGameQuery].
            val query = if (next.viewCursorKey() == state.viewCursorKey()) next.gameQuery else ""
            // Marks belong to the list they were made in, like the query.
            next.copy(selectedItemIndex = remembered, gameQuery = query, markMode = false, markedGameIds = emptySet())
        }
        loadItemsForCategory(currentCategory())
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
    private fun openVideoPlaylistContextMenu(playlistId: Long, name: String, byTouch: Boolean) {
        val items = videoPlaylistMenuItems(byTouch)
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(name, items, videoPlaylistId = playlistId)) }
    }

    // Opens the △ options menu for a Video row. Returns true when [item] is a video row it owns
    // (a video file, a library card, a playlist row, or a video-app row), so the generic
    // Y/long-press handler can stop.
    private fun openVideoContextMenu(item: XMBItem, byTouch: Boolean): Boolean {
        if (currentCategory()?.id != BuiltInCategory.VIDEO) return false
        return when {
            item.id == ALL_VIDEOS_ITEM_ID -> { openMediaCardContextMenu(MediaRootKind.VIDEO); true }
            item.type == XMBItemType.VIDEO_FILE && item.id.startsWith("vid_") -> {
                openVideoFileContextMenu(item.id.removePrefix("vid_"), item.title, byTouch); true
            }
            item.type == XMBItemType.VIDEO_FOLDER && item.id.startsWith("vlib_") -> {
                openVideoLibraryContextMenu(item.id.removePrefix("vlib_"), item.title, byTouch); true
            }
            item.type == XMBItemType.PLAYLIST && item.playlistId != null -> {
                openVideoPlaylistContextMenu(item.playlistId, item.title, byTouch); true
            }
            _uiState.value.videoNav == VideoNav.VideoApps && item.packageName != null -> {
                openAppContextMenu(item, categoryIdOverride = VIDEO_APPS_CATEGORY_ID, byTouch = byTouch); true
            }
            else -> false
        }
    }

    // Options for a single video file. Favorite label + "Remove from this Playlist" reflect the
    // current state/context. Fetches the video first so the favorite label is correct.
    private fun openVideoFileContextMenu(videoId: String, title: String, byTouch: Boolean) {
        viewModelScope.launch {
            val video = videoRepository.getVideo(videoId) ?: return@launch
            val inPlaylist = _uiState.value.videoNav is VideoNav.Playlist
            val items = videoFileMenuItems(video.isFavorite, inPlaylist, byTouch)
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
                    selectedIndex = selectIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
                    videoPlaylistPickerVideoId = videoId,
                )
            )}
        }
    }

    // Options for a video library card: open, scan, or manage in Settings.
    private fun openVideoLibraryContextMenu(libraryId: String, name: String, byTouch: Boolean) {
        val items = videoLibraryMenuItems(byTouch)
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(name, items, videoLibraryId = libraryId)) }
    }

    private fun handleVideoLibraryAction(libraryId: String, itemId: String) {
        when (itemId) {
            "video_lib_open" -> {
                val name = _uiState.value.currentItems.firstOrNull { it.id == "vlib_$libraryId" }?.title.orEmpty()
                openVideoView(VideoNav.Library(libraryId, name))
            }
            "video_lib_scan" -> scanRunner.kickoffLibrary(MediaRootKind.VIDEO, libraryId, force = true)
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
                    refreshMediaRootPreservingCursor(photoRootItems())
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
                    subtitle = "",
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
        val appItems = sectionAppItems(PHOTO_APPS_CATEGORY_ID)
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
                subtitle = "",
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
        item.id == ALL_PHOTOS_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            scanRunner.refreshIfStale(MediaRootKind.PHOTO)
            openPhotoView(PhotoNav.AllPhotos)
            true
        }
        item.id == PHOTO_ALBUMS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openPhotoView(PhotoNav.Albums); true }
        item.id == PHOTO_APPS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openPhotoView(PhotoNav.PhotoApps); true }
        item.id == CAMERA_ITEM_ID -> { launchCamera(); true }
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
            appCategoryRepository.launch(item.packageName); true
        }
        item.type == XMBItemType.PHOTO_FOLDER && item.id.startsWith("plib_") -> {
            menuSound.play(MenuSound.SELECT)
            val libId = item.id.removePrefix("plib_")
            scanRunner.kickoffLibrary(MediaRootKind.PHOTO, libId, force = false)
            openPhotoView(PhotoNav.Library(libId, item.title))
            true
        }
        item.type == XMBItemType.PHOTO_FILE && item.id.startsWith("pho_") -> {
            menuSound.play(MenuSound.SELECT)
            openPhotoViewer(item.id.removePrefix("pho_"))
            true
        }
        else -> false
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
    private fun openPhotoContextMenu(item: XMBItem, byTouch: Boolean): Boolean {
        if (currentCategory()?.id != BuiltInCategory.PHOTO) return false
        return when {
            item.id == ALL_PHOTOS_ITEM_ID -> { openMediaCardContextMenu(MediaRootKind.PHOTO); true }
            item.type == XMBItemType.PHOTO_FILE && item.id.startsWith("pho_") -> {
                openPhotoFileContextMenu(item.id.removePrefix("pho_"), item.title, byTouch); true
            }
            item.type == XMBItemType.PHOTO_FOLDER && item.id.startsWith("plib_") -> {
                openPhotoLibraryContextMenu(item.id.removePrefix("plib_"), item.title, byTouch); true
            }
            _uiState.value.photoNav == PhotoNav.PhotoApps && item.packageName != null -> {
                openAppContextMenu(item, categoryIdOverride = PHOTO_APPS_CATEGORY_ID, byTouch = byTouch); true
            }
            else -> false
        }
    }

    // Options for a single photo row. Viewing-related options (zoom, rotate, wallpaper) live in
    // the fullscreen viewer's own Options menu; the list row only opens/removes.
    private fun openPhotoFileContextMenu(photoId: String, title: String, byTouch: Boolean) {
        val items = photoFileMenuItems(byTouch)
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(title, items, photoFileId = photoId)) }
    }

    private fun handlePhotoFileAction(photoId: String, itemId: String) {
        when (itemId) {
            "photo_open"          -> openPhotoViewer(photoId)
            // Opens the viewer with the wallpaper preview already up — apply/cancel from there.
            "photo_set_wallpaper" -> openPhotoViewer(photoId, wallpaperPreview = true)
        }
    }

    // Options for an Album card: open, scan, or manage (rename / change folder / remove) in Settings.
    private fun openPhotoLibraryContextMenu(libraryId: String, name: String, byTouch: Boolean) {
        val items = photoAlbumMenuItems(byTouch)
        _uiState.update { it.copy(activeContextMenu = XMBContextMenu(name, items, photoLibraryId = libraryId)) }
    }

    private fun handlePhotoLibraryAction(libraryId: String, itemId: String) {
        when (itemId) {
            "photo_lib_open" -> {
                val name = _uiState.value.photoLibraries.firstOrNull { it.id == libraryId }?.displayName.orEmpty()
                openPhotoView(PhotoNav.Library(libraryId, name))
            }
            "photo_lib_scan" -> scanRunner.kickoffLibrary(MediaRootKind.PHOTO, libraryId, force = true)
            "photo_lib_manage" -> _uiState.update { it.copy(activeSettingsScreen = "settings_photo") }
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
        // New view, new list: drop the old focus so the engine seeds on the first row rather than
        // trying to preserve a key that belongs to the list we just left.
        browserNav.setFocused(null)
        browserTouchScrolled = false
        _uiState.update { it.copy(musicBrowser = MusicBrowserState(
            view = view,
            title = title,
            // Seeded from the player: the browser is regularly opened mid-song, and a strip that
            // only appears on the next playback emission would blink in a tick later.
            nowPlaying = browserNowPlaying(it.musicPlayback),
        )) }
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

    /**
     * Mirrors the loaded track into the browser's now-playing strip.
     *
     * Written from the same collector as `XMBUiState.musicPlayback` so the strip can never name a
     * song the player has moved off, and skipped when the strip would render exactly what it already
     * shows: that collector fires twice a second, and nothing in the strip is positional.
     */
    private fun syncBrowserNowPlaying(playback: MusicPlaybackState) {
        val next = browserNowPlaying(playback)
        _uiState.update { state ->
            val b = state.musicBrowser ?: return@update state
            if (b.nowPlaying == next) state else state.copy(musicBrowser = b.copy(nowPlaying = next))
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
        browserQueue = filtered   // the play queue is exactly what's on screen
        val baseRows = when {
            filtered.isNotEmpty() -> filtered.toMusicItems()
            q.isNotBlank()        -> listOf(browserNoResultsItem())
            isPlaylist            -> listOf(emptyPlaylistItem())
            else                  -> listOf(emptyAllMusicItem())
        }
        val rows = if (isPlaylist) baseRows + addTracksItem() else baseRows
        // The mode alone — the header pill and the Options row each add their own "Sort:" prefix,
        // and carrying it here rendered "Sort: Sort: Title" in the pill.
        publishBrowserRows(rows, sortLabel = _uiState.value.musicSortMode.label)
    }

    private fun rebuildBrowserPlaylistRows() {
        val state = _uiState.value.musicBrowser ?: return
        val q = state.query.trim().lowercase()
        val filtered = if (q.isBlank()) browserRawPlaylists
                       else browserRawPlaylists.filter { it.name.lowercase().contains(q) }
        val rows = playlistRootItems(filtered)   // playlist rows + "Create Playlist"
        publishBrowserRows(rows, sortLabel = null)
    }

    /**
     * Hands a new row list to the engine and mirrors the result into the UI state.
     *
     * The engine keeps focus on the same row id when it survives, and falls back to the nearest
     * survivor (geometry first, then order) when it does not — which is what makes filtering the
     * list under the cursor behave rather than dumping it back at row 0.
     */
    private fun publishBrowserRows(rows: List<XMBItem>, sortLabel: String?) {
        browserNav.replaceNodes(
            rows.map { row ->
                val actionable = row.type != XMBItemType.EMPTY
                NavigationNode(
                    key = row.id,
                    focusable = actionable,
                    selectable = actionable,
                    // The engine activates the row; the ViewModel still decides what a row means.
                    onSelect = { handleMusicBrowserRow(row) },
                    onLongPress = { openMusicBrowserContextMenu(byTouch = true) },
                )
            }
        )
        browserNav.markReady()
        _uiState.update { state ->
            val b = state.musicBrowser ?: return@update state
            state.copy(musicBrowser = b.copy(
                rows = rows,
                sortLabel = sortLabel,
                selectedIndex = browserIndexOfFocus(rows),
                cursorVisible = browserNav.cursorVisible,
            ))
        }
    }

    private fun browserIndexOfFocus(rows: List<XMBItem>): Int =
        rows.indexOfFirst { it.id == browserNav.focusedKey }.takeIf { it >= 0 } ?: 0

    /** Mirrors the engine's focus and cursor visibility into the state after any dispatch. */
    private fun syncBrowserCursor() {
        _uiState.update { state ->
            val b = state.musicBrowser ?: return@update state
            state.copy(musicBrowser = b.copy(
                selectedIndex = browserIndexOfFocus(b.rows),
                cursorVisible = browserNav.cursorVisible,
            ))
        }
    }

    private fun browserNoResultsItem(): XMBItem = XMBItem(
        id = EMPTY_CATEGORY_ITEM_ID, title = "No matches", subtitle = "Try a different search.",
        type = XMBItemType.EMPTY,
    )

    fun onMusicBrowserQueryChange(query: String) {
        markTouchInput()
        val state = _uiState.value.musicBrowser ?: return
        // Focus is not reset here: the engine keeps the focused row when it survives the filter and
        // recovers to its nearest neighbour when it does not, which is the whole point of the
        // migration. Only the scroll snaps to the top.
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(
            query = query,
            scrollToTopToken = state.scrollToTopToken + 1,
        )) }
        if (state.view is MusicBrowserView.Playlists) rebuildBrowserPlaylistRows() else rebuildBrowserTrackRows()
    }

    /**
     * The source transition from finger to pad, run once per controller press before the press is
     * given a meaning.
     *
     * A revival press — the first one after a finger has been on the list, a drag or a tap, since
     * either leaves the cursor hidden — spends itself re-anchoring to the row nearest the viewport
     * centre (the content the user was actually looking at) and doing nothing else; a directional
     * press that also moved would step the cursor straight past the visible area while it was still
     * reappearing. The row a tap left focused is deliberately not what gets re-anchored to: the
     * window is what the user is looking at, and a tapped row is usually one that navigated away
     * from this screen anyway. Same rule as SettingsScaffold, because a user who learns one list has
     * learned the other.
     *
     * It lives on the source transition rather than inside [moveMusicBrowser] because *every*
     * button is one. Re-anchoring only for directionals left Options, Search, Open and Back acting
     * on the stale pre-drag row — which the list then had to travel back to, so the first press
     * looked like it grabbed the screen and threw it somewhere the user had left.
     *
     * Returns true when this press was that revival press.
     */
    private fun reanchorMusicBrowserAfterTouch(): Boolean {
        val reviving = browserTouchScrolled || !browserNav.cursorVisible
        browserTouchScrolled = false
        if (!reviving) return false
        browserNav.markControllerInput()
        focusBrowserNearestToViewportCentre()
        syncBrowserCursor()
        return true
    }

    private fun moveMusicBrowser(delta: Int) {
        if (_uiState.value.musicBrowser == null) return
        val before = browserNav.focusedKey
        browserNav.dispatch(
            NavigationCommand.Direction(
                if (delta < 0) NavigationDirection.UP else NavigationDirection.DOWN
            )
        )        // Clamped at the ends by the engine, so silence there rather than a click that did nothing.
        if (browserNav.focusedKey != before) menuSound.play(MenuSound.SCROLL)
        syncBrowserCursor()
    }

    /** The engine's nearest-visible recovery, on the geometry the screen reports each layout. */
    private fun focusBrowserNearestToViewportCentre() {
        val focusable = browserNav.focusableKeys()
        val nearest = nearestToViewportCentre(
            centreY = browserViewportCentreY,
            visibleRowCentres = browserVisibleRowCentres.filterKeys { it in focusable },
        )
        if (nearest != null) browserNav.setFocused(nearest)
    }

    private fun activateMusicBrowser() {
        if (_uiState.value.musicBrowser == null) return
        // Confirm runs the focused node's own onSelect, wired in publishBrowserRows.
        browserNav.dispatch(NavigationCommand.Confirm)
        syncBrowserCursor()
    }

    fun onMusicBrowserActivatedAt(index: Int) {
        markTouchInput()
        val key = _uiState.value.musicBrowser?.rows?.getOrNull(index)?.id ?: return
        // A tap is not a scroll: it names its own row, so there is nothing to revive to.
        browserTouchScrolled = false
        browserNav.dispatchTouch(key, NavigationTouchAction.TAP)
        syncBrowserCursor()
    }

    /** A finger on the list. Hides the cursor and arms the revival press. */
    fun onMusicBrowserTouchInput() {
        markTouchInput()
        if (_uiState.value.musicBrowser == null) return
        browserTouchScrolled = true
        browserNav.markTouchInput()
        syncBrowserCursor()
    }

    /**
     * The visible row window and the viewport centre, in the list's own coordinates, reported on
     * every layout change. This is what [focusBrowserNearestToViewportCentre] reads.
     */
    fun onMusicBrowserGeometry(geometry: Map<String, Float>, viewportCentreY: Float) {
        if (_uiState.value.musicBrowser == null) return
        browserViewportCentreY = viewportCentreY
        browserVisibleRowCentres = geometry
    }

    private fun handleMusicBrowserRow(item: XMBItem) {
        when {
            item.type == XMBItemType.EMPTY -> Unit
            item.id == CREATE_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); promptCreatePlaylist() }
            item.id == IMPORT_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); requestPlaylistImportPick(PlaylistKind.MUSIC) }
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

    /**
     * [byTouch] is true for the kebab and a long-press, so a touch-opened menu keeps its Play / Open
     * row; the controller's Triangle already did that press.
     */
    private fun openMusicBrowserContextMenu(byTouch: Boolean) {
        val b = _uiState.value.musicBrowser ?: return
        val item = b.rows.getOrNull(b.selectedIndex)
        when {
            item == null -> openMusicBrowserListMenu()
            item.type == XMBItemType.MUSIC_TRACK -> openMusicTrackContextMenu(item, byTouch)
            item.type == XMBItemType.PLAYLIST && item.playlistId != null ->
                openPlaylistRowContextMenu(item.playlistId, item.title, byTouch)
            // Nothing actionable under the cursor (an empty library, a query with no matches, the
            // "Add Tracks" row) — but Sort belongs to the list, not to a row, so the menu still
            // has something to offer.
            else -> openMusicBrowserListMenu()
        }
        appendBrowserListItems()
    }

    /**
     * Rows that belong to the browser's *list* rather than to any one row in it.
     *
     * Built in one place because they are appended to three different menus — a track's, a
     * playlist's, and a bare one — and three copies would drift.
     */
    private fun browserListMenuItems(): List<XMBContextMenuItem> = browserListMenuItems(
        resumeTrack = musicPlayer.currentTrack()?.displayTitle,
        sortLabel = _uiState.value.musicBrowser?.sortLabel,
        view = _uiState.value.musicBrowser?.view,
    )

    /**
     * The list-level menu, for when the cursor is not on an actionable row.
     *
     * Opened empty and filled by [appendBrowserListItems], so it is skipped entirely when there is
     * nothing list-level to offer rather than presenting a menu with nothing in it.
     */
    private fun openMusicBrowserListMenu() {
        val b = _uiState.value.musicBrowser ?: return
        if (browserListMenuItems().isEmpty()) return
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(title = b.title, items = emptyList())
        )}
    }

    /** Appends [browserListMenuItems] to whatever menu the browser just opened. */
    private fun appendBrowserListItems() {
        if (_uiState.value.musicBrowser == null) return
        val extra = browserListMenuItems()
        if (extra.isEmpty()) return
        _uiState.update { state ->
            val menu = state.activeContextMenu ?: return@update state
            state.copy(activeContextMenu = menu.copy(
                items = menu.items + extra,
                browserList = true,
            ))
        }
    }

    fun onMusicBrowserLongPressAt(index: Int) {
        markTouchInput()
        val key = _uiState.value.musicBrowser?.rows?.getOrNull(index)?.id ?: return
        browserTouchScrolled = false
        // LONG_PRESS never falls through to TAP in the engine, so this cannot also activate the row.
        browserNav.dispatchTouch(key, NavigationTouchAction.LONG_PRESS)
        syncBrowserCursor()
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
        browserRawTracks = emptyList(); browserRawPlaylists = emptyList(); browserQueue = emptyList()
        browserNav.setFocused(null)
        browserTouchScrolled = false
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

    /** Touch: the now-playing strip — the same reveal as the Options menu's Resume row. */
    fun onMusicBrowserNowPlayingTapped() {
        markTouchInput()
        if (_uiState.value.musicPlayback.track == null) return
        showMusicPlayer()
    }

    /**
     * Raises or dismisses the search field's caret and keyboard. The query survives either way —
     * see [MusicBrowserState.searchActive].
     */
    fun setMusicBrowserSearchActive(active: Boolean) {
        val browser = _uiState.value.musicBrowser ?: return
        if (browser.searchActive == active) return
        menuSound.play(if (active) MenuSound.SELECT else MenuSound.BACK)
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(searchActive = active)) }
    }

    /** Touch: tapping the field owns its own focus, so this only keeps our flag honest. */
    fun onMusicBrowserSearchFocusChanged(focused: Boolean) {
        val browser = _uiState.value.musicBrowser ?: return
        if (browser.searchActive == focused) return
        _uiState.update { it.copy(musicBrowser = it.musicBrowser?.copy(searchActive = focused)) }
    }

    /** Touch: the browser's Options pill — opens the context menu for the highlighted row,
     *  same as the Y button. */
    fun onMusicBrowserOptionsTapped() {
        markTouchInput()
        openMusicBrowserContextMenu(byTouch = true)
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
            if (_uiState.value.musicPlayback.track != null) showMusicPlayer()
            true
        }
        // "Music" and "Playlist" open the fullscreen, searchable browser instead of the inline list.
        item.id == PLAYLISTS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicBrowser(MusicBrowserView.Playlists); true }
        item.id == ALL_MUSIC_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            scanRunner.refreshIfStale(MediaRootKind.MUSIC)
            openMusicBrowser(MusicBrowserView.AllMusic)
            true
        }
        item.id == MUSIC_APPS_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openMusicView(MusicNav.MusicApps); true }
        item.id == ADD_MUSIC_FOLDER_ITEM_ID -> {
            menuSound.play(MenuSound.SELECT)
            _uiState.update { it.copy(activeSettingsScreen = "settings_music") }
            true
        }
        item.id == CREATE_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); promptCreatePlaylist(); true }
        item.id == IMPORT_PLAYLIST_ITEM_ID -> { menuSound.play(MenuSound.SELECT); requestPlaylistImportPick(PlaylistKind.MUSIC); true }
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
            appCategoryRepository.launch(item.packageName); true
        }
        else -> false
    }

    // Selecting a song opens the in-app full player, with the on-screen track list as the queue.
    private fun openMusicPlayerForItem(item: XMBItem) {
        val trackId = item.id.removePrefix("mt_")
        // Selecting the song that is already loaded means "show me that" — not "start it again".
        // Re-queueing here would restart the track from 0 and drop the user's position, which is
        // the opposite of what picking the thing you are currently listening to should do.
        if (musicPlayer.currentTrack()?.id == trackId) {
            showMusicPlayer()
            return
        }
        val queue = activeMusicQueue()
        if (queue.isEmpty()) return
        val startIndex = queue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        musicPlayer.setQueue(queue, startIndex, currentQueueName())
        showMusicPlayer()
    }

    /**
     * What the player's banner calls the queue: the playlist or browser view the tracks came from,
     * falling back to the library as a whole. Resolved at queue time rather than read live, so the
     * banner keeps naming where the songs came from even after the user navigates away.
     */
    private fun currentQueueName(): String {
        val state = _uiState.value
        (state.musicBrowser?.view as? MusicBrowserView.Playlist)?.let { return it.name }
        (state.musicNav as? MusicNav.Playlist)?.let { return it.name }
        return "All Music"
    }

    /**
     * The track list a Play acts on: the browser's while it is open, the XMB item list's otherwise.
     *
     * Every caller that queues music goes through here, so the two lists cannot be confused for
     * each other again.
     */
    private fun activeMusicQueue(): List<MusicTrack> =
        if (_uiState.value.musicBrowser != null) browserQueue else currentMusicTracks

    /** Shows the player and starts its chrome timer, which is the only way it ever starts. */
    private fun showMusicPlayer() {
        _uiState.update { it.copy(musicPlayerVisible = true) }
        pokeMusicChrome()
    }

    // ── In-app player controls (driven by the player overlay) ───────────────────
    fun musicPlayPause() = musicPlayer.playPause()
    fun musicNext() = musicPlayer.next()
    fun musicPrev() = musicPlayer.prev()
    fun musicSeekTo(ms: Int) = musicPlayer.seekTo(ms)
    private fun musicSeekBy(deltaMs: Int) = musicPlayer.seekBy(deltaMs)

    // The player's touch transport: the same ±10s step the D-pad takes, and the same options menu
    // Y opens — the kebab and the face button must never diverge.
    fun musicSeekBack() = musicSeekBy(-MUSIC_SEEK_STEP_MS)
    fun musicSeekForward() = musicSeekBy(MUSIC_SEEK_STEP_MS)
    fun musicOpenOptions() = openMusicPlayerOptions()

    // ── Player chrome and the visualizer picker ─────────────────────────────────

    /**
     * Restores the chrome and restarts the idle timer. Every input inside the player calls this —
     * pad, touch and the transport alike — so there is one definition of "recently used".
     *
     * Auto-hide is gated on a field being selected. With `Off` the backdrop is the wallpaper, and
     * hiding the chrome over it would leave nothing on screen to aim at but a blind tap.
     */
    fun pokeMusicChrome() {
        musicChromeJob?.cancel()
        musicChromeJob = null
        _uiState.update { it.copy(musicChromeVisible = true) }
        val state = _uiState.value
        if (state.musicVisualizerId == VisualizerIds.OFF || state.musicPickerIndex != null) return
        musicChromeJob = viewModelScope.launch {
            delay(MUSIC_CHROME_TIMEOUT_MS)
            _uiState.update { it.copy(musicChromeVisible = false) }
        }
    }

    /** Opens the strip on the current selection and pins the chrome for as long as it is up. */
    fun openMusicVisualizerPicker() {
        musicChromeJob?.cancel()
        musicChromeJob = null
        val index = VisualizerIds.ALL.indexOf(_uiState.value.musicVisualizerId).coerceAtLeast(0)
        menuSound.play(MenuSound.SELECT)
        _uiState.update { it.copy(musicPickerIndex = index, musicChromeVisible = true) }
    }

    /**
     * Closes the strip and hands the horizontal axis back to seek — via [pokeMusicChrome], which
     * also restarts the idle timer the open strip was suppressing.
     */
    fun closeMusicVisualizerPicker() {
        if (_uiState.value.musicPickerIndex == null) return
        menuSound.play(MenuSound.BACK)
        _uiState.update { it.copy(musicPickerIndex = null) }
        pokeMusicChrome()
    }

    fun toggleMusicVisualizerPicker() {
        if (_uiState.value.musicPickerIndex != null) closeMusicVisualizerPicker()
        else openMusicVisualizerPicker()
    }

    /** Any touch anywhere in the player: records the input source and restores the chrome. */
    fun onMusicPlayerTouchInput() {
        markTouchInput()
        pokeMusicChrome()
    }

    /** Touch entry point for the player's strip affordance. */
    fun onMusicStripAffordanceTapped() {
        markTouchInput()
        toggleMusicVisualizerPicker()
    }

    /** A tap picks the tile outright — no separate confirm, the way every other PFP tile behaves. */
    fun onMusicVisualizerTileTapped(index: Int) {
        markTouchInput()
        _uiState.update { it.copy(musicPickerIndex = index) }
        applyMusicVisualizer(index)
    }

    private fun moveMusicVisualizerFocus(delta: Int) {
        val index = _uiState.value.musicPickerIndex ?: return
        val next = (index + delta).coerceIn(0, VisualizerIds.ALL.lastIndex)
        if (next == index) return
        menuSound.play(MenuSound.SCROLL)
        _uiState.update { it.copy(musicPickerIndex = next) }
    }

    private fun applyMusicVisualizer(index: Int) {
        val id = VisualizerIds.ALL.getOrNull(index) ?: return
        menuSound.play(MenuSound.SELECT)
        // Optimistic, then persisted: the field has to change under the cursor on the same frame,
        // and the DataStore write round-trips through observeVisualizerId a moment later.
        _uiState.update { it.copy(musicVisualizerId = id) }
        viewModelScope.launch { musicRepository.setVisualizerId(id) }
        pokeMusicChrome()
    }

    // Back (B, or the touch pill) only hides the player — playback keeps going so the Music root's
    // "Now Playing" item can return to it. Stopping is explicit (player Y → Stop & Close).
    fun closeMusicPlayer() {
        _uiState.update { it.copy(musicPlayerVisible = false) }
        resetMusicPlayerChrome()
    }

    private fun stopAndCloseMusicPlayer() {
        musicPlayer.stop()
        _uiState.update { it.copy(musicPlayerVisible = false) }
        resetMusicPlayerChrome()
    }

    /**
     * Leaves the player in the state it should be found in next time: chrome up, strip closed, no
     * timer pending. Also stops the visualizer clock, which is gated on the player being visible.
     */
    private fun resetMusicPlayerChrome() {
        musicChromeJob?.cancel()
        musicChromeJob = null
        _uiState.update { it.copy(musicChromeVisible = true, musicPickerIndex = null) }
    }

    private fun openMusicPlayerOptions() {
        val title = musicPlayer.currentTrack()?.displayTitle ?: "Now Playing"
        _uiState.update {
            it.copy(
                activeContextMenu = XMBContextMenu(
                    title = title,
                    items = musicPlayerOptionsMenuItems(),
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
                    items = nowPlayingMenuItems(playback.isPlaying),
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

    // Options for a section's memory card ("Music" / "Videos" / "Photos"). The card stands for the
    // whole section, so it carries the section-wide actions and nothing narrower: rescan every root
    // of that kind, or open that section's settings. There is no "Open" row — X on the card already
    // opens it, and a menu that repeats the press it was opened from teaches nothing.
    private fun openMediaCardContextMenu(kind: MediaRootKind) {
        val items = mediaCardMenuItems(kind.pluralNoun)
        _uiState.update {
            it.copy(activeContextMenu = XMBContextMenu(kind.pluralNoun, items, mediaCard = kind))
        }
    }

    private fun handleMediaCardAction(kind: MediaRootKind, itemId: String) {
        when (itemId) {
            // Forced: the user asked for a scan, so it reconciles even when the tree signature
            // says nothing moved. The card-open path is the signature-gated one.
            "media_card_scan" -> scanRunner.kickoff(kind, force = true)
            "media_card_manage" -> _uiState.update { it.copy(activeSettingsScreen = kind.settingsScreenId) }
        }
    }

    private fun openMusicTrackContextMenu(item: XMBItem, byTouch: Boolean = false) {
        // Inside a playlist (inline or browser), offer "Remove from this Playlist"; the playlist id
        // rides on the menu so the action knows which playlist.
        val playlistId = currentPlaylistContextId()
        val items = musicTrackMenuItems(inPlaylist = playlistId != null, byTouch = byTouch)
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title        = item.title,
                items        = items,
                musicTrackId = item.id.removePrefix("mt_"),
                playlistId   = playlistId,
            )
        )}
    }

    // Options menu for a playlist row: open (touch only) / add tracks / rename / delete.
    private fun openPlaylistRowContextMenu(playlistId: Long, name: String, byTouch: Boolean = false) {
        val items = musicPlaylistMenuItems(byTouch)
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
                    selectedIndex         = selectIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
                    playlistPickerTrackId = trackId,
                )
            )}
        }
    }

    // Opens the right options (△) menu for a Music item. Returns true when [item] is a music row
    // it owns (track / playlist / music-app), so the generic Y handler can stop. The Now Playing
    // row opens a small menu (Pause/Resume, Stop and Close); nothing plays, so no track is needed.
    private fun openMusicContextMenu(item: XMBItem, byTouch: Boolean): Boolean {
        if (currentCategory()?.id != BuiltInCategory.MUSIC) return false
        return when {
            item.id == NOW_PLAYING_ITEM_ID -> { openNowPlayingContextMenu(); true }
            item.id == ALL_MUSIC_ITEM_ID -> { openMediaCardContextMenu(MediaRootKind.MUSIC); true }
            item.type == XMBItemType.MUSIC_TRACK -> { openMusicTrackContextMenu(item, byTouch); true }
            item.type == XMBItemType.PLAYLIST && item.playlistId != null -> {
                openPlaylistRowContextMenu(item.playlistId, item.title, byTouch); true
            }
            _uiState.value.musicNav == MusicNav.MusicApps && item.packageName != null -> {
                openAppContextMenu(item, categoryIdOverride = MUSIC_APPS_CATEGORY_ID, byTouch = byTouch); true
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
                    // A picker opened by a finger starts with no cursor; one opened with a pad
                    // starts on the Confirm row with it drawn, exactly as before.
                    cursorVisible = !it.lastInputWasTouch,
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

    /**
     * The picker's source transition from finger to pad, run once per controller press before the
     * press is given a meaning — the same rule the browser and SettingsScaffold follow, so the
     * first button after a scroll parks the cursor on what the user can see instead of moving
     * relative to a row that is off screen.
     *
     * Returns true when this press was the revival press (so a directional one must not also move).
     */
    private fun reanchorMusicTrackPickerAfterTouch(): Boolean {
        val picker = _uiState.value.musicTrackPicker ?: return false
        val reviving = pickerTouchUsed || !picker.cursorVisible
        pickerTouchUsed = false
        if (!reviving) return false
        val maxIndex = picker.tracks.size   // 0 = Confirm row, 1..size = tracks
        val nearest = nearestToViewportCentre(pickerViewportCentreY, pickerVisibleRowCentres)
            ?.coerceIn(0, maxIndex)
        _uiState.update { state ->
            val p = state.musicTrackPicker ?: return@update state
            state.copy(musicTrackPicker = p.copy(
                // No geometry yet (the list has not laid out) leaves the index alone rather than
                // yanking it to the Confirm row.
                selectedIndex = nearest ?: p.selectedIndex,
                cursorVisible = true,
            ))
        }
        return true
    }

    /** A finger on the picker: hides the cursor and arms the revival press. */
    fun onMusicTrackPickerTouchInput() {
        markTouchInput()
        if (_uiState.value.musicTrackPicker == null) return
        pickerTouchUsed = true
        _uiState.update { it.copy(musicTrackPicker = it.musicTrackPicker?.copy(cursorVisible = false)) }
    }

    /**
     * The picker's visible window, reported on every layout change. Only the visible rows matter to
     * a "nearest to the middle of the screen" query, and the list already computes them.
     */
    fun onMusicTrackPickerGeometry(offsets: Map<Int, Float>, viewportCentreY: Float) {
        if (_uiState.value.musicTrackPicker == null) return
        pickerVisibleRowCentres = offsets
        pickerViewportCentreY = viewportCentreY
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

    // Every one of these is reachable only by a finger — a pad drives the picker through
    // onGamepadAction. Each has to record that, or resolvedShowTouchButton never flips and the
    // controller cursor rides along through an entire touch session.
    fun onMusicTrackPickerActivatedAt(index: Int) {
        markTouchInput()
        _uiState.update { it.copy(musicTrackPicker = it.musicTrackPicker?.copy(
            selectedIndex = index,
            cursorVisible = false,
        )) }
        activateMusicTrackPicker()
    }

    fun onMusicTrackPickerConfirm() {
        markTouchInput()
        confirmMusicTrackPicker()
    }

    /**
     * Closes the picker. Input-source agnostic on purpose: the gamepad ladder and
     * [confirmMusicTrackPicker] both call it, so marking touch in here would tell the app a finger
     * was used every time a pad dismissed the picker — the exact inverse of the bug above.
     */
    fun closeMusicTrackPicker() {
        _uiState.update { it.copy(musicTrackPicker = null) }
    }

    /** The touch Cancel pill. */
    fun onMusicTrackPickerDismissed() {
        markTouchInput()
        closeMusicTrackPicker()
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

    private fun handleMusicTrackAction(trackId: String, itemId: String, playlistId: Long?) {
        when (itemId) {
            // Play in the in-app full player, queuing from the current on-screen list.
            "play" -> {
                val queue = activeMusicQueue()
                if (queue.isNotEmpty()) {
                    val startIndex = queue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                    musicPlayer.setQueue(queue, startIndex, currentQueueName())
                    showMusicPlayer()
                }
            }
            // Play in PFP and promote straight to the background media notification (no full
            // player UI), queuing from the current on-screen list.
            "play_background" -> {
                val queue = activeMusicQueue()
                if (queue.isNotEmpty()) {
                    val startIndex = queue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                    musicPlayer.setQueue(queue, startIndex, currentQueueName())
                    com.playfieldportal.feature.xmb.music.MusicPlaybackService.start(context)
                }
            }
            "add_to_playlist" -> openPlaylistPicker(trackId)
            "track_info" -> activeMusicQueue().firstOrNull { it.id == trackId }?.let { track ->
                _uiState.update {
                    it.copy(infoDialog = InfoDialogState(track.displayTitle, musicTrackInfoLines(track).joinToString("\n")))
                }
            }
            "remove_from_playlist" -> if (playlistId != null) {
                appAction { musicRepository.removeTrackFromPlaylist(playlistId, trackId) }
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
        }
    }


    private suspend fun removeSingleTrack(folderId: String, trackId: String) {
        // Read current tracks once, drop the removed one, and replace the folder set.
        val tracks = musicRepository.observeTracksByFolder(folderId).first().filterNot { it.id == trackId }
        musicRepository.replaceTracksForFolder(folderId, tracks, System.currentTimeMillis())
    }

    // ── Sort (X / Square) ─────────────────────────────────────────────────────

    // Delegates to the pure XMBUiState.activeSortModes() so the sort hint pill and this handler
    // can never disagree about whether the current list sorts.
    private fun activeSortContext(): List<XmbSortMode>? = _uiState.value.activeSortModes()

    /**
     * Touch: the status-bar chip. On Games it opens the Filter menu; everywhere else it still
     * cycles the sort, exactly as X/Square does — the two entry points share [cycleSort] precisely
     * so the chip and the button can never drift apart.
     */
    fun onSortLabelTapped() {
        markTouchInput()
        cycleSort()
    }

    /** Sorts the fullscreen browser's tracks by [mode] and returns the view to the top. */
    private fun applyBrowserSort(mode: XmbSortMode) {
        val browser = _uiState.value.musicBrowser ?: return
        menuSound.play(MenuSound.SYSTEM_BROWSE)
        _uiState.update { it.copy(
            musicSortMode = mode,
            musicBrowser = it.musicBrowser?.copy(
                selectedIndex = 0,
                scrollToTopToken = browser.scrollToTopToken + 1,
            ),
        )}
        rebuildBrowserTrackRows()
    }

    /** The browser's Sort row: a list of the track sorts with the active one checked. */
    private fun openBrowserSortMenu(parent: XMBContextMenu) {
        val items = browserSortMenuItems(MUSIC_SORTS, _uiState.value.musicSortMode)
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title = "Sort",
                items = items,
                selectedIndex = items.indexOfFirst { row -> row.checked }.coerceAtLeast(0),
                browserList = true,
                parent = parent,
            )
        )}
    }

    private fun cycleSort() {
        // The fullscreen music browser sorts its own track views (not the playlists list).
        _uiState.value.musicBrowser?.let { browser ->
            if (browser.view is MusicBrowserView.Playlists) return
            applyBrowserSort(MUSIC_SORTS[(MUSIC_SORTS.indexOf(_uiState.value.musicSortMode).coerceAtLeast(0) + 1) % MUSIC_SORTS.size])
            return
        }
        // A column's root and an app list have no search, so Sort opens their Sort picker directly.
        _uiState.value.arrangeableRootKind()?.let { kind ->
            val key = _uiState.value.currentListKey() ?: return
            openListSortMenu(key, kind, title = "Sort", withGlobalRow = kind == XmbListKind.APPS)
            return
        }
        val cycle = activeSortContext() ?: return
        // Games doesn't cycle any more: it opens the Filter menu, where Sort is one of two rows
        // and the active mode carries a checkmark. Music and Video keep the cycle — their lists
        // have no search and a menu for three modes would be ceremony.
        if (cycle === GAME_SORTS) { openGamesFilterMenu(); return }
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

    // ── Games Filter menu (Square / the status chip) ──────────────────────────

    /** Opens the Filter root on its first row. */
    private fun openGamesFilterMenu() {
        _uiState.update { it.copy(activeContextMenu = gamesFilterMenuFor(it, group = null)) }
    }

    /** Swaps the open menu to [group]'s list (or back to the root when null). */
    private fun openGamesFilterGroup(group: GamesFilterGroup?) {
        _uiState.update { state ->
            val menu = gamesFilterMenuFor(state, group)
            // Open a group on its active choice, and return to the root on the row that led here,
            // so a press of BACK lands the cursor where the eye already is.
            val cursor = when (group) {
                GamesFilterGroup.SORT -> menu.items.indexOfFirst { it.checked }.coerceAtLeast(0)
                null -> menu.items.indexOfFirst { it.id == GAMES_FILTER_SORT_ID }.coerceAtLeast(0)
            }
            state.copy(activeContextMenu = menu.copy(selectedIndex = cursor))
        }
    }

    private fun gamesFilterMenuFor(state: XMBUiState, group: GamesFilterGroup?) = XMBContextMenu(
        title = group?.title ?: "Filter",
        items = gamesFilterRows(state, group),
        gamesFilterMenu = true,
        gamesFilterGroup = group,
    )

    /**
     * Handles one activation inside the Filter menu. Returns false when the menu is not this one,
     * so [activateContextMenuItem] can fall through to every other menu unchanged.
     */
    private fun handleGamesFilterItem(menu: XMBContextMenu, itemId: String): Boolean {
        if (!menu.gamesFilterMenu) return false
        when {
            itemId == GAMES_FILTER_SORT_ID   -> openGamesFilterGroup(GamesFilterGroup.SORT)
            itemId == GAMES_FILTER_SEARCH_ID -> { closeContextMenu(); openGameSearchField() }
            itemId == GAMES_FILTER_CLEAR_ID  -> { closeContextMenu(); applyGameQuery("") }
            // The Sort group sets the sort of the list on screen, and only that list.
            itemId.startsWith(LIST_SORT_PREFIX) -> {
                val listKey = _uiState.value.currentListKey() ?: return true
                closeContextMenu()
                applyListSort(listKey, sortChoice(itemId))
            }
        }
        return true
    }

    /** The sort a Sort-picker row stands for; null is "Use Global Setting" / "Default Order". */
    private fun sortChoice(itemId: String): XmbSortMode? =
        if (itemId == LIST_SORT_DEFAULT_ID) null
        else XmbSortMode.entries.firstOrNull { it.name == itemId.removePrefix(LIST_SORT_PREFIX) }

    /**
     * Gives [listKey] a sort of its own ([mode] null clears it back to the global setting). When
     * the list is the one on screen it re-renders from the top at once; the write then lands in
     * the store, and the collector finds the state already matches.
     */
    private fun applyListSort(listKey: String, mode: XmbSortMode?) {
        val state = _uiState.value
        if (state.listSortOverrides[listKey] == mode) return
        menuSound.play(MenuSound.SYSTEM_BROWSE)
        val onScreen = state.currentListKey() == listKey
        _uiState.update {
            val overrides = if (mode == null) it.listSortOverrides - listKey else it.listSortOverrides + (listKey to mode)
            if (onScreen) it.copy(listSortOverrides = overrides, selectedItemIndex = 0, scrollToTopToken = it.scrollToTopToken + 1)
            else it.copy(listSortOverrides = overrides)
        }
        viewModelScope.launch { listStateRepository.setSort(listKey, mode?.toListSort()) }
        if (!onScreen) return
        // Second pass on purpose: currentSortLabel() reads _uiState.value, which inside the update
        // above is still the pre-change state — it would label the column with the old mode.
        _uiState.update { it.copy(sortLabel = currentSortLabel()) }
        if (state.currentListKind() == XmbListKind.GAMES) rebuildGameColumn()
        else loadItemsForCategory(currentCategory())
    }

    /**
     * Opens the Sort picker for [listKey]. [withGlobalRow] appends the row that opens the global
     * picker for lists of this kind — app lists have no All Games menu to carry it.
     */
    private fun openListSortMenu(
        listKey: String,
        kind: XmbListKind,
        title: String,
        withGlobalRow: Boolean = false,
        parent: XMBContextMenu? = null,
    ) {
        val state = _uiState.value
        val global = if (kind == XmbListKind.APPS) state.appSortMode else state.gameSortMode
        val rows = listSortMenuItems(kind, global, state.listSortOverrides[listKey])
        val items = if (!withGlobalRow) rows else rows + XMBContextMenuItem(
            id = GLOBAL_SORT_ROW_ID,
            label = "Global Sort",
            value = sortLabel(global, kind),
            header = "All Lists",
        )
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title = title,
                items = items,
                selectedIndex = rows.indexOfFirst { row -> row.checked }.coerceAtLeast(0),
                sortListKey = listKey,
                sortListKind = kind,
                parent = parent,
            )
        )}
    }

    /** Opens the global Sort picker for every list of [kind] that has no sort of its own. */
    private fun openGlobalSortMenu(kind: XmbListKind, parent: XMBContextMenu? = null) {
        val state = _uiState.value
        val current = if (kind == XmbListKind.APPS) state.appSortMode else state.gameSortMode
        val items = globalSortMenuItems(kind, current)
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title = "Global Sort",
                items = items,
                selectedIndex = items.indexOfFirst { row -> row.checked }.coerceAtLeast(0),
                globalSortKind = kind,
                parent = parent,
            )
        )}
    }

    /**
     * Handles a row of a Sort picker (a list's own, or the global one). Returns false when the
     * menu is neither, so [activateContextMenuItem] falls through to every other menu unchanged.
     */
    private fun handleSortMenuItem(menu: XMBContextMenu, itemId: String): Boolean {
        val globalKind = menu.globalSortKind
        if (globalKind != null && itemId.startsWith(GLOBAL_SORT_PREFIX)) {
            val mode = XmbSortMode.entries.firstOrNull { it.name == itemId.removePrefix(GLOBAL_SORT_PREFIX) }
            closeContextMenu()
            val listSort = mode?.toListSort() ?: return true
            menuSound.play(MenuSound.SYSTEM_BROWSE)
            viewModelScope.launch {
                if (globalKind == XmbListKind.APPS) sortPreferences.setAppsSort(listSort)
                else sortPreferences.setGamesSort(listSort)
            }
            return true
        }
        val listKey = menu.sortListKey ?: return false
        val kind = menu.sortListKind ?: return false
        if (itemId == GLOBAL_SORT_ROW_ID) {
            openGlobalSortMenu(kind, parent = menu)
            return true
        }
        if (!itemId.startsWith(LIST_SORT_PREFIX)) return false
        closeContextMenu()
        applyListSort(listKey, sortChoice(itemId))
        return true
    }

    /**
     * Sets the live query and rebuilds the column.
     *
     * The cursor goes back to the top rather than being kept by id: the list the cursor was
     * anchored in no longer exists, and publishGameItems' keep-the-row logic would land it on an
     * arbitrary neighbour of a row that has just been filtered out.
     */
    private fun applyGameQuery(query: String) {
        if (_uiState.value.gameQuery == query) return
        _uiState.update { it.copy(
            gameQuery = query,
            selectedItemIndex = 0,
            scrollToTopToken = it.scrollToTopToken + 1,
        )}
        // As in [applyListSort]: the label has to be computed from the state after the write.
        _uiState.update { it.copy(sortLabel = currentSortLabel()) }
        rebuildGameColumn()
    }

    /**
     * Re-renders the Games column from the rows already in hand, falling back to a full reload if
     * there is no builder (nothing has published a Games list yet).
     *
     * This is what keeps typing cheap: a per-keystroke [loadItemsForCategory] would cancel and
     * re-subscribe a database flow over the whole library on every character.
     */
    private fun rebuildGameColumn() {
        val rebuild = rebuildGameRows
        if (rebuild != null) rebuild() else loadItemsForCategory(currentCategory())
    }

    /**
     * Drops the query when the list it was typed against goes away — a different Memory Card, a
     * collection, another category. Carrying it across would leave a short column whose cause has
     * scrolled out of the user's memory.
     *
     * No reload here: every caller is already navigating, and does its own.
     */
    private fun clearGameQuery() = _uiState.update {
        if (it.gameQuery.isBlank()) it else it.copy(gameQuery = "")
    }

    fun openGameSearchField() = _uiState.update {
        it.copy(gameSearchField = GameSearchFieldState(text = it.gameQuery, textOnOpen = it.gameQuery))
    }

    /** Live: every keystroke re-filters the column behind the field. */
    fun onGameSearchChanged(text: String) {
        _uiState.update { it.copy(gameSearchField = it.gameSearchField?.copy(text = text)) }
        applyGameQuery(text)
    }

    /** Confirm dismisses the field. The query is already applied — there is nothing to commit. */
    fun onGameSearchConfirmed() = _uiState.update { it.copy(gameSearchField = null) }

    /** Cancel restores the query as it was when the field opened. */
    fun onGameSearchCancelled() {
        val restore = _uiState.value.gameSearchField?.textOnOpen ?: return
        _uiState.update { it.copy(gameSearchField = null) }
        applyGameQuery(restore)
    }

    // The parent label for the two-pane flyout, non-null whenever drilled into ANY sub-item — a Games
    // sub-item (platform card / All Games / Favorites / collection) or a Music sub-view (Music Apps /
    // a playlist / All Music). Null = top level, normal single-column list.
    private fun computeDrillTitle(): String? {
        val s = _uiState.value
        // Settings L1 flyout: the section name parents the L2 rows, like every other drill-in.
        val settingsTitle = s.settingsSectionNav?.title
        if (settingsTitle != null) return settingsTitle
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
            SocialNav.Voice            -> "Voice"
            SocialNav.VoiceSettings    -> "Voice Settings"
            SocialNav.VoiceInvites     -> "Invites"
            SocialNav.VoiceInviteFriends -> "Invite Friends"
            SocialNav.ActivitySettings -> "Activity Settings"
            SocialNav.DiscordSettings  -> "Discord Settings"
            SocialNav.Root             -> null
        }
        if (socialTitle != null) return socialTitle
        return when {
            s.selectedCollectionId != null ->
                s.collections.firstOrNull { it.id == s.selectedCollectionId }?.name ?: "Custom Card"
            s.selectedPlatformId == ALL_GAMES_PLATFORM_ID -> "All Games"
            s.selectedPlatformId == FAVORITES_PLATFORM_ID -> "Favorites"
            s.selectedPlatformId == MISSING_PLATFORM_ID   -> "Missing"
            s.selectedPlatformId == CATEGORY_CARD_PLATFORM_ID ->
                currentCategory()?.let { "${it.name} Memory Card" }
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
        // Settings L1 flyout: the left column is the six section rows, drilled-into one centred.
        if (s.settingsSectionNav != null) {
            // Android Settings is a real sibling of the section cards. Keep it in the left
            // column so Library (and every other L1 section) always has a visible parent icon
            // above the category bar, matching the established Music flyout geometry.
            val sibs = listOf(
                XMBItem(id = ANDROID_SETTINGS_ITEM_ID, title = "Android Settings", subtitle = "Opens device settings"),
            ) + SettingsSection.entries.map { XMBItem(id = it.id, title = it.title, subtitle = it.subtitle) }
            val idx = sibs.indexOfFirst { it.id == s.settingsSectionNav.id }.coerceAtLeast(0)
            return sibs to idx
        }
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
                SocialNav.Voice, SocialNav.VoiceSettings,
                SocialNav.VoiceInvites, SocialNav.VoiceInviteFriends -> {
                    val hub = socialHubSiblings()
                    hub to hub.indexOfFirst { it.type == XMBItemType.SOCIAL_VOICE }.coerceAtLeast(0)
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
                    it.type == XMBItemType.MISSING ||
                    it.type == XMBItemType.MEMORY_CARD || it.type == XMBItemType.COLLECTION
            }
            val idx = sibs.indexOfFirst { sib ->
                when {
                    s.selectedPlatformId == ALL_GAMES_PLATFORM_ID -> sib.type == XMBItemType.ALL_GAMES
                    s.selectedPlatformId == FAVORITES_PLATFORM_ID -> sib.type == XMBItemType.FAVORITES
                    s.selectedPlatformId == MISSING_PLATFORM_ID   -> sib.type == XMBItemType.MISSING
                    s.selectedCollectionId != null               -> sib.collectionId == s.selectedCollectionId
                    s.selectedPlatformId != null                 -> sib.platformId == s.selectedPlatformId
                    else -> false
                }
            }.coerceAtLeast(0)
            return sibs to idx
        }
        // Custom category drilled into its Memory Card or a custom memory card — just show the
        // single parent icon (both draw the memory-card glyph; an open card keeps its own icon).
        val openCard = s.collections.firstOrNull { it.id == s.selectedCollectionId }
        val parent = XMBItem(
            id = "drill_parent",
            title = computeDrillTitle().orEmpty(),
            iconKey = openCard?.iconKey,
            type = if (s.selectedPlatformId == CATEGORY_CARD_PLATFORM_ID) XMBItemType.CATEGORY_CARD else XMBItemType.COLLECTION,
        )
        return listOf(parent) to 0
    }

    // Status-bar hint for the current list ("Sort: Title"), or null when the list isn't sortable.
    private fun currentSortLabel(): String? {
        val state = _uiState.value
        // A column's root or an app list: named only once it has left its default, so the chip
        // does not appear on every root that has never been arranged.
        state.arrangeableRootKind()?.let { kind ->
            val key = state.currentListKey() ?: return null
            val mode = state.activeSortFor(key, kind)
            return when {
                kind == XmbListKind.ROOT -> if (state.isCustomSorted(key, kind)) "Sort: Custom" else null
                else -> "Sort: ${sortLabel(mode, kind)}"
            }
        }
        val cycle = activeSortContext() ?: return null
        // Games: the chip names the menu it opens, and carries the active search term. That term
        // is not decoration — a column missing four fifths of its games has nothing else on screen
        // explaining why, and this chip is the only thing the eye can find it in.
        if (cycle === GAME_SORTS) {
            val term = state.gameQuery.trim()
            val sort = sortLabel(state.activeGameSort, state.openListSortKind)
            return if (term.isBlank()) "Filter: $sort" else "Filter: \"$term\" · $sort"
        }
        val mode = if (cycle === MUSIC_SORTS) state.musicSortMode else state.videoSortMode
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

        // Missing sits under Favorites and only exists while something is actually missing, so a
        // healthy library never sees it. It disappears on its own once the files come back.
        val missingCount = _uiState.value.missingCount
        val missingItem = if (missingCount > 0) {
            XMBItem(
                id       = MISSING_ITEM_ID,
                title    = "Missing",
                subtitle = "$missingCount ${if (missingCount == 1) "Game" else "Games"}",
                type     = XMBItemType.MISSING,
            )
        } else null
        val header = listOfNotNull(allGamesItem, favoritesItem, missingItem)

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
                subtitle     = customCardSubtitle(collection.isPinned, games),
                collectionId = collection.id,
                iconKey      = collection.iconKey,
                pinned       = collection.isPinned,
                type         = XMBItemType.COLLECTION,
            )
        }

        if (enabledCards.isEmpty()) {
            return gamesRoot(
                movable = header + collectionItems,
                trailing = listOf(
                    XMBItem(
                        id       = NO_CONSOLES_ITEM_ID,
                        title    = "No consoles configured",
                        subtitle = "Open Library Manager to add a Memory Card",
                        type     = XMBItemType.EMPTY,
                    )
                ),
            )
        }

        // Windows is import-driven and belongs under Settings ▸ Library rather than the normal
        // console cards. Only surface it here when the Windows card actually contains game rows.
        val visibleCards = enabledCards.filter { card ->
            card.platformId != WINDOWS_PLATFORM_ID ||
                (_uiState.value.platformGameCounts[WINDOWS_PLATFORM_ID] ?: card.gameCount) > 0
        }

        return gamesRoot(
            movable = header + collectionItems + visibleCards.map { card ->
                val count = _uiState.value.platformGameCounts[card.platformId] ?: card.gameCount
                XMBItem(
                    id          = "card_${card.platformId}",
                    title       = if (card.platformId == WINDOWS_PLATFORM_ID) "Windows Games" else card.displayName,
                    subtitle    = "$count ${if (count == 1) "Game" else "Games"}",
                    platformId  = card.platformId,
                    accentColor = platformCache[card.platformId]?.accentColor,
                    pinned      = card.pinned,
                    type        = XMBItemType.MEMORY_CARD,
                )
            },
        )
    }

    // The Games root as it is shown: the UMD slot, then [movable] in its default order or the
    // user's Custom one, then [trailing].
    private fun gamesRoot(movable: List<XMBItem>, trailing: List<XMBItem> = emptyList()): List<XMBItem> {
        val rootKey = com.playfieldportal.core.domain.model.ListKeys.root(BuiltInCategory.GAMES)
        return assembleRoot(
            umd = umdSlotItem(BuiltInCategory.GAMES, allRealGames),
            movable = movable,
            trailing = trailing,
            listState = listState(rootKey),
            custom = _uiState.value.isCustomSorted(rootKey, XmbListKind.ROOT),
        )
    }

    // B3: the empty All Games row names the FIRST unmet setup step and confirms through to the
    // screen that fixes it. "No games imported yet" told a fresh install nothing actionable.
    private fun emptyAllGamesItem(): XMBItem {
        val gap = setupState.firstGap
        if (gap != com.playfieldportal.feature.launcher.SetupGap.NONE) {
            return XMBItem(
                id       = SETUP_GAP_ITEM_ID,
                title    = gap.message,
                subtitle = "Press confirm to open Settings and fix it.",
                type     = XMBItemType.EMPTY,
            )
        }
        return XMBItem(
            id       = NO_GAMES_ITEM_ID,
            title    = "No games imported yet",
            subtitle = "Open a Memory Card to scan your library.",
            type     = XMBItemType.EMPTY,
        )
    }

    private fun emptyCollectionItem(): XMBItem = XMBItem(
        id       = EMPTY_COLLECTION_ITEM_ID,
        title    = "This custom memory card is empty",
        subtitle = "Add games from any console with the options (△) menu.",
        type     = XMBItemType.EMPTY,
    )

    private fun emptyFavoritesItem(): XMBItem = XMBItem(
        id       = EMPTY_FAVORITES_ITEM_ID,
        title    = "No favorites yet",
        subtitle = "Mark a game as a favorite from its options (△) menu.",
        type     = XMBItemType.EMPTY,
    )

    // Only reachable in the gap between the last missing game being resolved and the Missing row
    // disappearing — worth having so the bucket never renders as a blank list.
    private fun emptyMissingItem(): XMBItem = XMBItem(
        id       = EMPTY_MISSING_ITEM_ID,
        title    = "Nothing missing",
        subtitle = "Every game's file was found on the last scan.",
        type     = XMBItemType.EMPTY,
    )

    // Shown when an opened Memory Card has no games yet. Keeps the platformId so the
    // context menu (Triangle) can still offer "Scan for Games".
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
        // B3: when setup is still incomplete, the card's empty row names the FIRST unmet step
        // instead of generic folder copy — most often "the root exists but no ROM folder yet".
        val gap = setupState.firstGap
        if (gap != com.playfieldportal.feature.launcher.SetupGap.NONE) {
            return XMBItem(
                id         = SETUP_GAP_ITEM_ID,
                title      = gap.message,
                subtitle   = "Press confirm to open Settings and fix it.",
                platformId = platformId,
                type       = XMBItemType.EMPTY,
            )
        }
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

    /**
     * Publishes a game list. With [keepCursorOnRow] the cursor follows its row by id
     * ([cursorAfterRefresh]); without it the cursor keeps its index, which is what a fresh drill-in
     * needs, since navigateRememberingCursor has already set the index it should land on.
     */
    /**
     * The rows for one Games list: [games] filtered by the live query and sorted, or a single
     * explanatory row when that comes out empty.
     *
     * Which empty row matters. A library with nothing in it gets [emptyItem] ("No games imported
     * yet"); a query that matched nothing gets [noGameMatchesItem], because those are different
     * problems and the second one is the user's own doing and instantly fixable. A null
     * [emptyItem] is the one list that has always drawn a bare column when empty — the Favorites
     * *category* — and it keeps doing so.
     *
     * [mapRows] is for the two lists that decorate their rows afterwards (Missing's reason
     * subtitle, a gaming category's "Pinned" marker). It runs on real rows only — an empty-state
     * row must never be given a game's decoration.
     */
    private fun gameRowsOrEmpty(
        games: List<Game>,
        emptyItem: (() -> XMBItem)? = null,
        mapRows: (List<XMBItem>) -> List<XMBItem> = { it },
        // When each game was added to this list, for lists that have such a moment.
        dateAdded: Map<Long, Long> = emptyMap(),
    ): List<XMBItem> {
        val state = _uiState.value
        val arrangement = listState(state.currentListKey())
        val visible = gamesForDisplay(games, state.gameQuery, state.activeGameSort, arrangement, dateAdded)
        return when {
            visible.isNotEmpty()         -> mapRows(
                visible.toXmbItems().map { row ->
                    val pinned = row.gameId != null &&
                        com.playfieldportal.core.domain.model.ListKeys.game(row.gameId) in arrangement.pinned
                    if (pinned) row.copy(pinned = true) else row
                }
            )
            state.gameQuery.isNotBlank() -> listOf(noGameMatchesItem(state.gameQuery.trim()))
            else                         -> listOfNotNull(emptyItem?.invoke())
        }
    }

    /** The Games column's "your search matched nothing" row. */
    private fun noGameMatchesItem(term: String): XMBItem = XMBItem(
        id       = EMPTY_CATEGORY_ITEM_ID,
        title    = "No games match \"$term\"",
        subtitle = "Open the Filter menu to change or clear it.",
        type     = XMBItemType.EMPTY,
    )

    /**
     * How to rebuild the Games column from the rows already in hand, or null when the list on
     * screen is not a Games list.
     *
     * Set by each Games branch of [loadItemsForCategory] to a lambda closing over the list that
     * branch last received, so a query or sort change re-runs [gameRowsOrEmpty] against the new
     * state without re-subscribing to the database. Typing is per-keystroke: without this, every
     * character cancelled and restarted a Room flow over the whole library.
     *
     * The same reasoning (and the same shape) as the music browser's cached `browserRawTracks`.
     */
    private var rebuildGameRows: (() -> Unit)? = null

    /** Publishes a Games list and remembers how to rebuild it. See [rebuildGameRows]. */
    private fun publishGames(keepCursorOnRow: Boolean, build: () -> List<XMBItem>) {
        rebuildGameRows = { publishGameItems(build(), keepCursorOnRow = false) }
        publishGameItems(build(), keepCursorOnRow)
    }

    private fun publishGameItems(items: List<XMBItem>, keepCursorOnRow: Boolean) {
        if (deferWhileMoving()) return
        _uiState.update {
            if (!keepCursorOnRow) it.copy(currentItems = items)
            else it.copy(
                currentItems = items,
                selectedItemIndex = cursorAfterRefresh(it.currentItems, it.selectedItemIndex, items),
            )
        }
    }

    private fun List<com.playfieldportal.core.domain.model.Game>.toXmbItems() = map { g ->
        XMBItem(
            id           = g.id.toString(),
            title        = g.displayTitle,
            artworkUri   = g.artworkUri,
            heroUri      = g.heroUri,
            iconUri      = g.iconUri,
            logoUri      = g.logoUri,
            boxArtUri    = g.boxArtUri,
            physicalMediaUri = g.physicalMediaUri,
            box3dUri     = g.box3dUri,
            iconDisplayModeOverride = g.iconDisplayMode,
            subtitle     = platformEmulatorLabel(g),
            gameId       = g.id,
            platformId   = g.platformId,
            accentColor  = platformCache[g.platformId]?.accentColor,
            isFavorite   = g.isFavorite,
            isAndroidApp = g.packageName != null,
            isRealGame   = g.contentType == GameContentType.GAME,
            packageName  = g.packageName,
            shortcutId   = g.shortcutId,
            launchIntentUri = g.launchIntentUri,
        )
    }

    // ── Shiba Coins hub ─────────────────────────────────────────────────────────

    private fun observeLibraryStanding() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                achievementRepository.observeLibraryStanding(),
                achievementCredentials.raUsernameFlow,
                achievementCredentials.steamId64Flow,
                localAchievementFolders.anyLinked,
            ) { standing, raUser, steamId, emulatorFolder ->
                // An emulator data folder (Vita3K, ARMSX3, X360 Mobile, XenDroid) is a connected
                // source too: its trophies and achievements need no account at all.
                standing to (!raUser.isNullOrBlank() || !steamId.isNullOrBlank() || emulatorFolder)
            }.collect { (standing, connected) ->
                _uiState.update { it.copy(libraryStanding = standing, achievementsConnected = connected) }
                // Refresh the hub in place when it is the visible category.
                if (currentCategory()?.id == BuiltInCategory.ACHIEVEMENTS) {
                    loadItemsForCategory(currentCategory())
                }
            }
        }
    }

    // The hub is a single root list (All Tracked and Untracked are fullscreen overlays).
    private fun achievementsItems(
        standing: com.playfieldportal.core.domain.achievement.LibraryStanding,
        nav: AchievementsNav,
        connected: Boolean,
        syncAll: Pair<Int, Int>? = null,
        matching: Boolean = false,
    ): List<XMBItem> = when (nav) {
        AchievementsNav.Root -> achievementsRootItems(standing, connected, syncAll, matching)
    }

    private fun achievementsRootItems(
        standing: com.playfieldportal.core.domain.achievement.LibraryStanding,
        connected: Boolean,
        syncAll: Pair<Int, Int>? = null,
        matching: Boolean = false,
    ): List<XMBItem> {
        val untrackedRow = if (standing.untracked.isEmpty()) emptyList() else listOf(
            XMBItem(id = ACH_UNTRACKED_ITEM_ID, title = "Untracked", subtitle = "${standing.untracked.size} games", type = XMBItemType.STANDARD),
        )
        // Nothing connected yet: the connect prompt is the only entry. Once RA or Steam credentials
        // are saved, or an emulator data folder is linked, the player card shows immediately
        // (Lv 1 / 0 coins) and fills in as syncs land.
        if (!connected && standing.gamesTracked == 0) {
            val connect = XMBItem(
                id = ACH_CONNECT_ITEM_ID,
                title = "Connect accounts",
                subtitle = "Set up Shiba Coins and auto-match your library",
                type = XMBItemType.STANDARD,
            )
            return listOf(connect) + untrackedRow
        }
        val w = standing.wallet
        val allTrackedRow = if (standing.gamesTracked == 0) emptyList() else listOf(
            XMBItem(
                id = ACH_ALL_ITEM_ID,
                title = "All Tracked Games",
                subtitle = syncAll?.let { (done, total) ->
                    if (matching) "Auto-matching…  $done / $total" else "Syncing coins…  $done / $total"
                } ?: "${standing.gamesTracked} games",
                type = XMBItemType.STANDARD,
            ),
        )
        // While nothing is tracked yet there is no All Tracked row to carry the sync progress,
        // so the Player Card itself shows it (its menu is where that first sync starts).
        // Coins read as earned/available COUNTS (user decision 2026-07-16); the weighted value
        // stays the level economy behind the Lv badge.
        val summarySubtitle = if (standing.gamesTracked == 0 && syncAll != null) {
            if (matching) "Auto-matching…  ${syncAll.first} / ${syncAll.second}"
            else "Syncing coins…  ${syncAll.first} / ${syncAll.second}"
        } else {
            "${"%,d".format(standing.coinsEarned)} / ${"%,d".format(standing.coinsAvailable)} coins  •  " +
                "${standing.gamesTracked} tracked  •  ${standing.gamesMastered} mastered"
        }
        return listOf(
            XMBItem(
                id = ACH_SUMMARY_ITEM_ID,
                title = w.rank.label,
                subtitle = summarySubtitle,
                levelBadge = "Lv ${w.level}",
                boneCount = w.bones,
                type = XMBItemType.STANDARD,
            ),
        ) + allTrackedRow + untrackedRow
    }

    private fun openAchievementsView(nav: AchievementsNav) =
        navigateRememberingCursor { it.copy(achievementsNav = nav) }

    private fun closeAchievementsView() = openAchievementsView(AchievementsNav.Root)

    // Handles a tap/select in the Shiba Coins category. Returns true when consumed; a game/coin row
    // inside a lens returns false so the shared game handler opens its Game Detail page.
    private fun handleAchievementsSelection(item: XMBItem): Boolean {
        when (item.id) {
            ACH_CONNECT_ITEM_ID -> {
                // Connect accounts lands straight on Provider Credentials (RetroAchievements &
                // Steam) rather than the achievements root — the only next step on first run.
                menuSound.play(MenuSound.SELECT)
                _uiState.update { it.copy(activeSettingsScreen = "settings_achievements_credentials") }
                return true
            }
            ACH_SUMMARY_ITEM_ID -> {
                // The player card opens the fullscreen player status view.
                menuSound.play(MenuSound.SELECT)
                openPlayerStatus()
                return true
            }
            ACH_ALL_ITEM_ID     -> { menuSound.play(MenuSound.SELECT); openShibaLibrary(ShibaLibraryMode.TRACKED); return true }
            ACH_UNTRACKED_ITEM_ID -> { menuSound.play(MenuSound.SELECT); openShibaLibrary(ShibaLibraryMode.UNTRACKED); return true }
            EMPTY_CATEGORY_ITEM_ID -> return true // placeholder, not selectable
        }
        return false
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
        viewModelScope.launch {
            controllerLayoutRepository.prefs.collect { prefs ->
                // Prompt glyphs are supplied ambiently by ProvideControllerPrompts at the
                // app root, so the display type no longer needs mirroring into UI state.
                gamepadInputHandler.scrollSpeed = prefs.scrollSpeed
                virtualKeyboard.setEnabled(prefs.virtualKeyboard)
                _uiState.update { it.copy(leftBacksOut = prefs.leftBacksOut) }
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

    // ── Idle hint pill ─────────────────────────────────────────────────────────
    // A configurable idle pause over an item that has a context menu, or on a list that sorts
    // (after controller input, with no overlay up), fades in the Sort / Options pill next to the
    // App Drawer button, drawn with the user's own controller glyphs.
    //
    // HIDING IS NOT THIS LOOP'S JOB for the common case: every input path clears the flag
    // synchronously (markTouchInput / markControllerInput / onUserInteraction), so the pill goes
    // the instant a button is pressed rather than up to IDLE_HINT_POLL_MS later. The loop still
    // clears it for causes that bypass those hooks (an overlay raised by a background task), and
    // it remains the only thing that RAISES it.
    //
    // Polls cheaply (every ~500ms) and only writes on a visibility transition, so it costs
    // nothing while idle.
    // The one seam every menu opening shares, whichever opener built it (controller, long-press,
    // touch): a menu appearing from nothing plays SELECT. Pickers opened from a menu replace it
    // rather than appear, and their row's own cue already sounded.
    private fun observeContextMenuOpening() {
        viewModelScope.launch {
            var previous: XMBContextMenu? = null
            _uiState.map { it.activeContextMenu }.distinctUntilChanged().collect { menu ->
                if (contextMenuOpened(previous, menu)) menuSound.play(MenuSound.SELECT)
                previous = menu
            }
        }
    }

    private fun observeContextMenuHintIdle() {
        viewModelScope.launch {
            // Only while the launcher is visible: this used to wake every 500 ms forever, behind
            // games and with the screen off.
            tickWhileVisible(hostVisible, IDLE_HINT_POLL_MS) {
                val s = _uiState.value
                val idleMs = SystemClock.elapsedRealtime() - lastInteractionMs
                val shouldShow = com.playfieldportal.feature.xmb.viewmodel.shouldShowContextMenuHint(
                    state = s,
                    idleMs = idleMs,
                )
                // Same clock, second consumer: the App Drawer's own pill. The two gates are mutually
                // exclusive (the drawer is a blocking overlay, so the XMB gate is false while it is
                // open), but both ride this poller so there is a single idle source of truth.
                val shouldShowDrawer = com.playfieldportal.feature.xmb.viewmodel.shouldShowAppDrawerHint(
                    state = s,
                    idleMs = idleMs,
                )
                val shouldShowSettings = com.playfieldportal.feature.xmb.viewmodel.shouldShowSettingsHint(
                    state = s,
                    idleMs = idleMs,
                )
                // Fourth consumer of the same clock: the notification panel's own pill.
                val shouldShowNotifications =
                    com.playfieldportal.feature.xmb.viewmodel.shouldShowNotificationHint(
                        state = s,
                        idleMs = idleMs,
                    )
                // Fifth: the media screens' prompt rows.
                val shouldShowMedia = com.playfieldportal.feature.xmb.viewmodel.shouldShowMediaHint(
                    state = s,
                    idleMs = idleMs,
                )
                if (shouldShow != s.showContextMenuHint ||
                    shouldShowDrawer != s.showAppDrawerHint ||
                    shouldShowSettings != s.showSettingsHint ||
                    shouldShowNotifications != s.showNotificationHint ||
                    shouldShowMedia != s.showMediaHint
                ) {
                    _uiState.update {
                        it.copy(
                            showContextMenuHint = shouldShow,
                            showAppDrawerHint = shouldShowDrawer,
                            showSettingsHint = shouldShowSettings,
                            showNotificationHint = shouldShowNotifications,
                            showMediaHint = shouldShowMedia,
                        )
                    }
                }
            }
        }
    }

    private fun dispatchGamepadAction(action: GamepadAction) {
        markControllerInput()
        val state = _uiState.value

        // ── The virtual keyboard captures ALL input while open ─────────────────
        //
        // Above every other tier, START included: while it is up the pad types, and nothing behind
        // it — a picker, a settings screen, the crossbar — may move.
        if (keyboardCaptures(virtualKeyboard, action)) return

        // ── Convert-detected-games panel captures ALL input while open ─────────
        //
        // Above everything, including the START branch below: inside this panel START means Install,
        // and letting it open the notification panel instead would take the user off a screen that
        // is about to write into their game folders.
        if (convertPickerController.picker.value != null) {
            if (onConvertGamepadAction(action)) return
        }

        // ── Installed-app picker captures ALL input when open ──────────────────
        if (state.appPicker != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP,
                GamepadAction.NAVIGATE_DOWN,
                GamepadAction.NAVIGATE_LEFT,
                GamepadAction.NAVIGATE_RIGHT -> moveAppPicker(action)
                // Confirm toggles the focused tile — never closes anything (§9).
                // While the removal-confirmation modal is up, SELECT activates the modal's
                // highlighted option (Cancel or Remove) instead of toggling a grid tile.
                GamepadAction.SELECT -> {
                    val picker = state.appPicker
                    if (picker.confirmingRemovals) {
                        if (picker.confirmFocusedOption == AppPickerState.CONFIRM_REMOVE) commitAppPicker()
                        else cancelConfirm()
                    } else toggleFocusedApp()
                }
                // Start applies the diff (with a confirmation pass when removals are pending).
                GamepadAction.HOME -> requestApplyAppPicker()
                GamepadAction.CHANGE_SORT -> _uiState.update { s ->
                    s.copy(appPicker = s.appPicker?.pressSearch()?.clampFocus())
                }
                // Back unwinds one layer: search → removal confirmation → picker.
                GamepadAction.BACK,
                GamepadAction.OPEN_CONTEXT_MENU -> handleAppPickerBack()
                else -> Unit
            }
            return
        }

        // ── "Add Tracks" music picker captures ALL input when open ─────────────
        if (state.musicTrackPicker != null) {
            // The same source-transition rule as the browser: the first press after a finger parks
            // the cursor on the track nearest the viewport centre instead of acting on the stale
            // pre-scroll row and dragging the list back to it.
            val revivalPress = reanchorMusicTrackPickerAfterTouch()
            when (action) {
                GamepadAction.NAVIGATE_UP   -> if (!revivalPress) moveMusicTrackPicker(-1)
                GamepadAction.NAVIGATE_DOWN -> if (!revivalPress) moveMusicTrackPicker(+1)
                GamepadAction.SELECT        -> activateMusicTrackPicker()
                GamepadAction.HOME          -> confirmMusicTrackPicker()
                GamepadAction.BACK,
                GamepadAction.OPEN_CONTEXT_MENU    -> closeMusicTrackPicker()
                else -> Unit
            }
            return
        }

        // ── Game picker captures ALL input when open ───────────────────────────
        if (state.gamePickerCategoryId != null || state.gamePickerCollectionId != null) {
            // Every action belongs to the picker: GamePickerScreen owns Done/Cancel and hands the
            // rest (grid, rail, shelf, whole-shelf and view) to GamePickerViewModel.
            _uiState.update { it.copy(pendingGamePickerAction = action) }
            return
        }

        // ── Context menu captures ALL input when open ──────────────────────────
        if (state.activeContextMenu != null) {
            val menu = state.activeContextMenu
            // The shared rules: the cursor clamps, BACK climbs one level (a Games Filter group to its
            // root, a picker to the menu that opened it) and closes at the root, Triangle closes from
            // any depth, and the sounds are PspMenuNav's.
            when (val outcome = menu.press(action) { menuSound.play(it) }) {
                is PspMenuOutcome.Moved ->
                    _uiState.update { it.copy(activeContextMenu = menu.copy(selectedIndex = outcome.index)) }
                PspMenuOutcome.Activate -> activateContextMenuItem()
                PspMenuOutcome.Up ->
                    if (menu.gamesFilterGroup != null) openGamesFilterGroup(null)
                    else _uiState.update { it.copy(activeContextMenu = menu.afterBack()) }
                PspMenuOutcome.Close -> closeContextMenu()
                PspMenuOutcome.Ignored -> Unit
            }
            return
        }

        // ── A menu's confirm captures ALL input, over the notification panel too ──
        //
        // Directly under the context-menu branch (whose row closed itself to raise this) and above
        // the panel's: Clear All is confirmed from inside the panel, and its presses must reach the
        // modal, not the list beneath it. Forwarded to the shell's shared Confirm modal.
        if (state.pendingConfirm != null) {
            forwardToShellModal(action)
            return
        }

        // ── START: open the panel, or close it ──
        //
        // Reached only after the pickers and the context menu above have had their turn, which is
        // what keeps START = Confirm inside them. Everything else falls through to whichever
        // overlay is actually on screen.
        if (action == GamepadAction.HOME) {
            when (startOutcome(state)) {
                StartOutcome.CLOSE_PANEL -> { closeNotificationPanel(); return }
                StartOutcome.OPEN_PANEL  -> { openNotificationPanel(); return }
                StartOutcome.FORWARD_TO_OVERLAY -> Unit
            }
        }

        // ── Notification panel captures ALL input while open ──
        //
        // Below the context-menu branch, so the △ menu it opens wins over the panel beneath it.
        // Running rows are skipped by the cursor — they are a readout, not a list you act on.
        if (state.notificationPanel != null) {
            // A Notes/Results sheet or the Stop confirm is up over the panel: it owns every press,
            // through the shell's modal host, and BACK there returns to the panel.
            if (state.notificationPanel.hasLayer) {
                forwardToShellModal(action)
                return
            }
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveNotificationCursor(-1)
                GamepadAction.NAVIGATE_DOWN -> moveNotificationCursor(+1)
                GamepadAction.SELECT        -> activateNotificationRow()
                // One menu, for the list. A row has a single meaning — open it — and Confirm
                // already carries that, so a per-row menu would be a submenu holding one real
                // entry plus two ways to say "read".
                GamepadAction.OPEN_CONTEXT_MENU,
                GamepadAction.CHANGE_SORT   -> openNotificationListMenu()
                GamepadAction.BACK          -> closeNotificationPanel()
                else -> Unit
            }
            return
        }

        // ── Live "Adjust XMB Layout" editor captures ALL input while open ──────
        if (state.xmbLayoutAdjust != null) {
            when (action) {
                GamepadAction.NAVIGATE_LEFT  -> nudgeXmbLayoutHorizontal(-1)
                GamepadAction.NAVIGATE_RIGHT -> nudgeXmbLayoutHorizontal(+1)
                GamepadAction.NAVIGATE_UP    -> nudgeXmbLayoutVertical(-1)
                GamepadAction.NAVIGATE_DOWN  -> nudgeXmbLayoutVertical(+1)
                GamepadAction.PREV_CATEGORY  -> nudgeXmbLayoutScale(-1)
                GamepadAction.NEXT_CATEGORY  -> nudgeXmbLayoutScale(+1)
                GamepadAction.OPEN_CONTEXT_MENU -> resetXmbLayoutAdjust()
                // Was a second OPEN_CONTEXT_MENU branch, so it never ran and the sliders could
                // only be reached by touch. The overlay's own hint always named Square/X for it.
                GamepadAction.CHANGE_SORT       -> toggleXmbLayoutSliders()
                GamepadAction.SELECT         -> saveXmbLayoutAdjust()
                GamepadAction.BACK           -> cancelXmbLayoutAdjust()
                else -> Unit
            }
            return
        }

        // ── In-app music player captures ALL input while open ──────────────────
        // (Below the context-menu branch so the player's own Y options menu wins when shown.)
        // The video player's ladder, binding for binding: A play/pause, ◀/▶ seek, L1/R1 across
        // the queue, Y options, B close. The two built-in players used opposite hands for seek
        // and track, which is the kind of difference a user only discovers by getting it wrong.
        if (state.musicPlayerVisible) {
            // The visualizer strip captures the horizontal axis while it is open — the same
            // capture pattern the context menu uses above — and B hands it straight back to seek.
            // "Seek is dead after closing the strip" is the regression this shape prevents: there
            // is exactly one `return` between the two ladders and no shared state to forget.
            val picker = state.musicPickerIndex
            if (picker != null) {
                when (action) {
                    GamepadAction.NAVIGATE_LEFT  -> moveMusicVisualizerFocus(-1)
                    GamepadAction.NAVIGATE_RIGHT -> moveMusicVisualizerFocus(+1)
                    GamepadAction.SELECT         -> applyMusicVisualizer(picker)
                    GamepadAction.BACK,
                    GamepadAction.OPEN_CONTEXT_MENU -> closeMusicVisualizerPicker()
                    else -> Unit
                }
                return
            }
            // Any press is "recently used", including the ones that fall through the when below.
            pokeMusicChrome()
            when (action) {
                GamepadAction.SELECT         -> musicPlayPause()
                GamepadAction.NAVIGATE_LEFT  -> musicSeekBy(-MUSIC_SEEK_STEP_MS)
                GamepadAction.NAVIGATE_RIGHT -> musicSeekBy(MUSIC_SEEK_STEP_MS)
                GamepadAction.PREV_CATEGORY  -> musicPrev()
                GamepadAction.NEXT_CATEGORY  -> musicNext()
                GamepadAction.OPEN_CONTEXT_MENU     -> openMusicPlayerOptions()
                GamepadAction.BACK           -> closeMusicPlayer()
                else -> Unit
            }
            return
        }

        // ── Color-scheme picker captures ALL input when open (sits above Settings) ──
        state.customColorPicker?.let { picker ->
            // The shared picker's own controller model moves the cursor and voices every press;
            // the dialog voices taps. Options backs out like ○, as it always has here.
            var closed = false
            val next = customColorNav.handle(
                state = picker,
                action = if (action == GamepadAction.OPEN_CONTEXT_MENU) GamepadAction.BACK else action,
                sounds = { menuSound.play(it) },
                onApply = { closed = true; confirmCustomColor() },
                onCancel = { closed = true; cancelCustomColor() },
            )
            if (!closed && next != picker) _uiState.update { it.copy(customColorPicker = next) }
            return
        }
        if (state.colorSchemePicker != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> moveColorSchemePicker(-1)
                GamepadAction.NAVIGATE_DOWN -> moveColorSchemePicker(+1)
                GamepadAction.SELECT        -> confirmColorSchemePicker()
                GamepadAction.BACK,
                GamepadAction.OPEN_CONTEXT_MENU    -> cancelColorSchemePicker()
                else -> Unit
            }
            return
        }

        // ── The shared name modals capture ALL input. The XMB behind must never move. The cursor
        //    and the text live in the modal's host in XMBShell (PfpModalHost), so the press is
        //    parked for it rather than interpreted here — the same hand-off the icon editor uses. ──
        if (state.renameAppTarget != null ||
            state.collectionNameDialog != null ||
            state.playlistNameDialog != null ||
            state.saveThemeNameDialog != null
        ) {
            forwardToShellModal(action)
            return
        }
        // The Games search field owns input while it is up, like the name dialogs above it. BACK
        // cancels (restoring the query it opened with); the keyboard's own Search key confirms.
        if (state.gameSearchField != null) {
            when (action) {
                GamepadAction.BACK   -> onGameSearchCancelled()
                GamepadAction.SELECT -> onGameSearchConfirmed()
                else -> Unit
            }
            return
        }
        // Read-only info notice (e.g. file location) — A or B closes it, up/down scroll a long
        // one. Forwarded to the shared notice modal's host, like the name modals above.
        if (state.infoDialog != null) {
            forwardToShellModal(action)
            return
        }
        // A playlist import's Results sheet — the same shared sheet the notification panel uses.
        if (state.playlistImportQueue != null) {
            forwardToShellModal(action)
            return
        }
        // Launch recovery sheet (B1) — A confirms the highlighted action, B dismisses.
        if (state.launchRecovery != null) {
            when (action) {
                GamepadAction.SELECT -> onLaunchRecoveryAction(LaunchRecoveryAction.RETRY)
                GamepadAction.BACK   -> onLaunchRecoveryAction(LaunchRecoveryAction.DISMISS)
                else                 -> Unit
            }
            return
        }
        // Windows Library setup prompt — opens on Set Up, so A sets up (Library Manager) and B
        // defers, as before; left/right now reach Later too. Forwarded to the shared confirm modal.
        if (state.showWindowsSetupPrompt) {
            forwardToShellModal(action)
            return
        }
        // A shortcut request's Add / Ignore — forwarded to the shared confirm modal.
        if (state.shortcutReview != null) {
            forwardToShellModal(action)
            return
        }

        // ── Fullscreen music browser captures input. Below the context-menu / player / dialog
        //    branches above, so a menu (Y) or the player opened from it wins. ─────────────────
        if (state.musicBrowser != null) {
            // First, and for every button: this press is the pad taking over from the finger. If it
            // is a revival press the cursor has already been parked on the row the user was
            // looking at, so the press is interpreted against THAT row and a directional one is
            // spent — it must not also move.
            val revivalPress = reanchorMusicBrowserAfterTouch()
            when (action) {
                GamepadAction.NAVIGATE_UP    -> if (!revivalPress) moveMusicBrowser(-1)
                GamepadAction.NAVIGATE_DOWN  -> if (!revivalPress) moveMusicBrowser(+1)
                GamepadAction.SELECT         -> activateMusicBrowser()
                // B leaves the search field before it leaves the screen: with the keyboard up,
                // backing out of the browser entirely is never what the press meant.
                GamepadAction.BACK           ->
                    if (state.musicBrowser.searchActive) setMusicBrowserSearchActive(false)
                    else onMusicBrowserBack()
                GamepadAction.OPEN_CONTEXT_MENU     -> openMusicBrowserContextMenu(byTouch = false)
                // X raises the search field rather than cycling the sort. Sort moved into the
                // Options menu: it is a setting you change occasionally, where search is the thing
                // you reach for constantly on a library of any size, and the field was previously
                // unreachable without a touchscreen.
                GamepadAction.CHANGE_SORT    ->
                    setMusicBrowserSearchActive(!state.musicBrowser.searchActive)
                else -> Unit
            }
            return
        }

        // ── Boot sequence overlay swallows input, except the skip ──────────────
        // Confirm or Back ends the presentation through the SAME completion path the animation,
        // the watchdog, and a player error use — so a user who does not want to watch a 10-second
        // custom boot video is never held by it. (There is no START action in this app's mapping
        // vocabulary; Back is the other button a user reaches for to get out of something.)
        if (state.showBootSequence) {
            if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
                onBootSequenceComplete()
            }
            return
        }

        // ── GameBoot presentation: same deal, and mashing Confirm must not launch twice ────
        // Every press lands here rather than on the game row underneath, so the extra presses a
        // user makes while the transition plays are absorbed, not queued into a second launch.
        if (state.activeGameBoot != null) {
            if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
                onGameBootComplete()
            }
            return
        }

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
            state.activeShibaCoinsTarget != null -> {
                // Forward everything so the coins screen can move focus, sync, and close on BACK.
                _uiState.update { it.copy(pendingShibaCoinsAction = action) }
                return
            }
            state.activeSearchOnline -> {
                // Forward everything so the search page can leave its preview before it closes.
                _uiState.update { it.copy(pendingSearchOnlineAction = action) }
                return
            }
            state.activeShibaLibrary != null -> {
                // Forward everything so the fullscreen library can move focus and close on BACK.
                _uiState.update { it.copy(pendingShibaLibraryAction = action) }
                return
            }
            state.activePlayerStatus -> {
                // Forward everything so the player status view can move focus, open a coin's
                // details, and close on BACK.
                _uiState.update { it.copy(pendingPlayerStatusAction = action) }
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
                if (forwardsToSettings(action)) _uiState.update { it.copy(pendingSettingsAction = action) }
                return
            }
            state.activeAppDrawerFilter != null -> {
                // Every action — including BACK — is forwarded to the drawer. The drawer resolves
                // BACK itself, the same way Game/App Detail do: while its options menu or
                // uninstall confirm is open, BACK pops that inner overlay (never the drawer);
                // only a BACK on the plain grid closes the drawer (via onBack → onCloseAppDrawer).
                _uiState.update { it.copy(pendingDrawerAction = action) }
                return
            }
            state.activeDiscordLogin -> {
                // Back cancels the QR overlay; its own Compose UI handles taps/buttons.
                if (action == GamepadAction.BACK) onDiscordLoginClosed()
                return
            }
            state.customIconSession != null -> {
                // The icon editor owns the pad: LEFT/RIGHT step the slot cursor through the tab's
                // strip, while the L/R shoulders cycle the tabs [Crossbar, Items, Consoles,
                // Physical Media]. UP/DOWN jump between XMB columns on the Items tab and mirror
                // LEFT/RIGHT elsewhere. SELECT opens the SAF picker (the overlay observes the
                // forwarded action), OPTIONS resets the focused slot, BACK exits.
                when (action) {
                    GamepadAction.NAVIGATE_LEFT -> onCustomIconSlotMove(-1)
                    GamepadAction.NAVIGATE_RIGHT -> onCustomIconSlotMove(+1)
                    GamepadAction.NAVIGATE_UP -> onCustomIconColumnMove(-1)
                    GamepadAction.NAVIGATE_DOWN -> onCustomIconColumnMove(+1)
                    GamepadAction.PREV_CATEGORY -> onCustomIconTabMove(-1)
                    GamepadAction.NEXT_CATEGORY -> onCustomIconTabMove(+1)
                    GamepadAction.SELECT,
                    GamepadAction.OPEN_CONTEXT_MENU,
                    GamepadAction.BACK -> _uiState.update { it.copy(pendingCustomIconsAction = action) }
                    else -> Unit
                }
                return
            }
        }

        // Defensive net: the main XMB navigation below must NEVER run while any overlay,
        // menu, or modal dialog is on screen. Each case above returns for its own handling;
        // this guards against a future overlay being added without its own branch.
        // A row being moved owns the D-pad: up / down slide it, confirm places it, back puts it
        // back. Everything else is swallowed until the move ends.
        // A lifted category owns left / right the same way: slide it, place it, or put it back.
        if (state.categoryMoveSession != null) {
            when (action) {
                GamepadAction.NAVIGATE_LEFT  -> if (!shiftMovingCategory(-1)) gamepadInputHandler.cancelRepeat()
                GamepadAction.NAVIGATE_RIGHT -> if (!shiftMovingCategory(+1)) gamepadInputHandler.cancelRepeat()
                GamepadAction.SELECT         -> placeMovingCategory()
                GamepadAction.BACK           -> cancelMovingCategory()
                else -> Unit
            }
            return
        }
        if (state.moveSession != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> if (!shiftMovingRow(-1)) gamepadInputHandler.cancelRepeat()
                GamepadAction.NAVIGATE_DOWN -> if (!shiftMovingRow(+1)) gamepadInputHandler.cancelRepeat()
                GamepadAction.SELECT        -> placeMovingRow()
                GamepadAction.BACK          -> cancelMovingRow()
                else -> Unit
            }
            return
        }
        if (state.hasBlockingOverlay) return

        when (action) {
            // Item cursor moves through the shared moveItemCursor() so touch swipes and the D-pad
            // drive identical logic; cancel auto-repeat when we hit a list boundary.
            GamepadAction.NAVIGATE_UP   -> if (!moveItemCursor(-1)) gamepadInputHandler.cancelRepeat()
            GamepadAction.NAVIGATE_DOWN -> if (!moveItemCursor(+1)) gamepadInputHandler.cancelRepeat()
            GamepadAction.NAVIGATE_LEFT -> {
                // While drilled into a sub-item, LEFT does not escape to another category — it
                // backs out one level, the direction the XMB's own drill-in metaphor implies. It
                // deliberately does NOT fall through to the App Drawer the way BACK does (that is
                // BACK's job, and LEFT would reach it by surprise), and it does not markTouchInput:
                // a controller press must not flip the contextual App Drawer button to touch mode.
                if (state.isInSubItem) {
                    gamepadInputHandler.cancelRepeat()
                    if (!state.leftBacksOut) return
                    menuSound.play(MenuSound.BACK)
                    backOutOfDrill(state)
                    return
                }
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
            // Multi-select: confirm marks the focused game instead of opening it.
            GamepadAction.SELECT     ->
                if (state.markMode) toggleMark(state.focusedItem) else onItemSelected(state.selectedItemIndex)
            GamepadAction.BACK       -> {
                menuSound.play(MenuSound.BACK)
                // Multi-select ends first, leaving the list exactly where it is.
                if (state.markMode) { exitMarkMode(); return }
                // One level up, or the App Drawer when there is no level left to leave.
                if (!backOutOfDrill(state)) onOpenAppDrawer()
            }
            // Y / Triangle — the same menu a long-press opens, through one resolver.
            GamepadAction.OPEN_CONTEXT_MENU -> openContextMenuFor(
                state.currentItems.getOrNull(state.selectedItemIndex), byTouch = false,
            )
            // START is handled above, before the overlay ladder, so it can mean the same thing
            // whether or not the panel is already open. Unreachable here.
            GamepadAction.HOME, GamepadAction.SHIFT, GamepadAction.CAPS_LOCK -> Unit
            // Cycle the sort order of the current list (PSP-style). Whichever face button
            // the user's X/Y layout assigns to sort dispatches this.
            GamepadAction.CHANGE_SORT -> cycleSort()
            GamepadAction.PREV_CATEGORY,
            GamepadAction.NEXT_CATEGORY -> Unit
        }
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun openPlatformContextMenu(platformId: String) {
        val card = enabledCards.firstOrNull { it.platformId == platformId } ?: return
        val cardListKey = com.playfieldportal.core.domain.model.ListKeys.card(platformId)
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title      = card.displayName,
                items      = platformCardMenuItems(
                    platformId       = platformId,
                    pinned           = card.pinned,
                    iconDisplayLabel = platformIconDisplayLabel(platformId),
                    sortLabel        = it.sortValueLabel(cardListKey, XmbListKind.GAMES),
                    canMove          = canMoveRootRows(),
                ),
                rowKey     = com.playfieldportal.core.domain.model.ListKeys.cardItem(platformId),
                platformId = platformId,
            )
        )}
    }

    // The "All Games" card isn't a real Memory Card; its menu is the whole-library version of one.
    private fun openAllGamesContextMenu() {
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title      = "All Games",
                items      = allGamesMenuItems(
                    iconDisplayLabel = it.iconDisplayMode.label,
                    sortLabel        = it.sortValueLabel(com.playfieldportal.core.domain.model.ListKeys.ALL_GAMES, XmbListKind.GAMES),
                    globalSortLabel  = it.gameSortMode.label,
                    canMove          = canMoveRootRows(),
                ),
                rowKey     = com.playfieldportal.core.domain.model.ListKeys.ROW_ALL_GAMES,
                isAllGames = true,
            )
        )}
    }

    // The menu of a root row that is not a record of its own: a custom category's Memory Card,
    // Favorites, Missing. Open it, sort the list behind it, move it while the root is Custom.
    private fun openRootRowContextMenu(item: XMBItem, byTouch: Boolean) {
        val state = _uiState.value
        val listKey = when (item.type) {
            XMBItemType.CATEGORY_CARD -> currentCategory()?.id?.let { com.playfieldportal.core.domain.model.ListKeys.categoryCard(it) }
            XMBItemType.FAVORITES -> com.playfieldportal.core.domain.model.ListKeys.FAVORITES
            // The Missing bucket is a holding pen: it is listed, never arranged.
            else -> null
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title            = item.title,
                items            = rootRowMenuItems(
                    sortValue = listKey?.let { key -> state.sortValueLabel(key, XmbListKind.GAMES) },
                    canMove   = canMoveRootRows(),
                    byTouch   = byTouch,
                ),
                rowKey           = item.rowKey(),
                sortListKey      = null,
                categoryCardMenu = item.type == XMBItemType.CATEGORY_CARD,
                rootRowMenu      = true,
            )
        )}
    }

    /** Whether the rows of the list on screen can be moved: only a root or app list in Custom. */
    private fun canMoveRootRows(): Boolean {
        val state = _uiState.value
        val kind = state.arrangeableRootKind() ?: return false
        return state.isCustomSorted(state.currentListKey(), kind)
    }

    // The list a container row's "Sort" row sets the sort of, and what that list holds.
    private fun sortTargetOf(menu: XMBContextMenu): Pair<String, XmbListKind>? {
        val keys = com.playfieldportal.core.domain.model.ListKeys
        // A custom card holds what its category holds: apps sort with app sorts.
        val cardKind = menu.collectionRowId?.let { id ->
            val categoryId = _uiState.value.collections.firstOrNull { it.id == id }?.categoryId
            collectionSortKind(_uiState.value.categories.firstOrNull { it.id == categoryId })
        } ?: XmbListKind.GAMES
        val key = when {
            menu.collectionRowId != null -> keys.collection(menu.collectionRowId)
            menu.isAllGames -> keys.ALL_GAMES
            menu.categoryCardMenu -> currentCategory()?.id?.let { keys.categoryCard(it) }
            menu.rootRowMenu && menu.rowKey == keys.ROW_FAVORITES -> keys.FAVORITES
            menu.platformId != null -> keys.card(menu.platformId)
            else -> null
        } ?: return null
        return key to cardKind
    }

    /**
     * The rows every arrangeable row's menu shares, whichever menu they are on: Move, the Sort of
     * the list behind a container row, a game's Pin to Top, its UMD slot, and Open on a root row.
     * Returns false for any other id, so the menu's own handler still sees it.
     */
    private fun handleArrangeMenuItem(menu: XMBContextMenu, itemId: String): Boolean {
        when (itemId) {
            MOVE_ROW_ID -> {
                closeContextMenu()
                menu.rowKey?.let(::startMove)
            }
            LIST_SORT_ROW_ID -> {
                val (listKey, kind) = sortTargetOf(menu) ?: return false
                openListSortMenu(listKey, kind, title = "Sort", parent = menu)
            }
            GLOBAL_SORT_ROW_ID -> {
                if (menu.sortListKey != null) return false   // the Sort picker's own Global row
                openGlobalSortMenu(XmbListKind.GAMES, parent = menu)
            }
            "pin_top", "unpin_top" -> {
                val gameKey = menu.gameId?.let { com.playfieldportal.core.domain.model.ListKeys.game(it) } ?: return false
                val listKey = _uiState.value.currentListKey() ?: return false
                closeContextMenu()
                appAction { listStateRepository.setPinned(listKey, gameKey, pinned = itemId == "pin_top") }
            }
            "insert_umd" -> {
                val gameId = menu.gameId ?: return false
                val column = currentCategory()?.takeIf { it.isGamingCategory }?.id ?: return false
                closeContextMenu()
                appAction { umdSlotRepository.insert(column, gameId) }
            }
            "eject_umd" -> {
                val column = currentCategory()?.takeIf { it.isGamingCategory }?.id ?: return false
                closeContextMenu()
                appAction { umdSlotRepository.eject(column) }
            }
            "select_multiple" -> {
                val gameId = menu.gameId ?: return false
                closeContextMenu()
                _uiState.update { it.copy(markMode = true, markedGameIds = setOf(gameId)) }
            }
            "open_row" -> {
                if (!menu.rootRowMenu) return false
                closeContextMenu()
                when (menu.rowKey) {
                    com.playfieldportal.core.domain.model.ListKeys.ROW_CATEGORY_CARD -> openCategoryCardFolder()
                    com.playfieldportal.core.domain.model.ListKeys.ROW_FAVORITES -> openFavoritesFolder()
                    com.playfieldportal.core.domain.model.ListKeys.ROW_MISSING -> openMissingFolder()
                }
            }
            else -> return false
        }
        return true
    }

    // ── Move mode ─────────────────────────────────────────────────────────────
    // One row, lifted. Up / down slide it among the rows it may pass, confirm saves the list's
    // whole order, back puts everything where it was.

    private fun startMove(rowKey: String) {
        val state = _uiState.value
        val listKey = state.currentListKey() ?: return
        val index = state.currentItems.indexOfFirst { it.rowKey() == rowKey }
        if (index < 0) return
        menuSound.play(MenuSound.SELECT)
        _uiState.update { it.copy(
            selectedItemIndex = index,
            moveSession = MoveSession(listKey, originalItems = it.currentItems, originalIndex = index),
        )}
    }

    private fun shiftMovingRow(delta: Int): Boolean {
        val state = _uiState.value
        if (state.moveSession == null) return false
        val (items, index) = moveRow(state.currentItems, state.selectedItemIndex, delta) ?: return false
        menuSound.play(MenuSound.SCROLL)
        _uiState.update { it.copy(currentItems = items, selectedItemIndex = index) }
        return true
    }

    /** Saves the list as it now stands as its Custom order. */
    fun placeMovingRow() {
        val state = _uiState.value
        val session = state.moveSession ?: return
        menuSound.play(MenuSound.CONFIRM)
        val keys = state.currentItems.orderKeys()
        // The order just made is already on screen; write it before the session ends so the
        // collector that follows finds the store agreeing with the list.
        _uiState.update {
            val saved = it.listStates[session.listKey] ?: com.playfieldportal.core.domain.model.ListState.EMPTY
            it.copy(
                moveSession = null,
                listStates = it.listStates + (session.listKey to saved.copy(
                    positions = keys.withIndex().associate { (index, key) -> key to index },
                )),
            )
        }
        viewModelScope.launch { listStateRepository.replaceOrder(session.listKey, keys) }
        reloadDeferredDuringMove()
    }

    private fun reloadDeferredDuringMove() {
        if (!reloadAfterMove) return
        reloadAfterMove = false
        loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
    }

    /** Puts the row — and the cursor — back where the move started. */
    fun cancelMovingRow() {
        val session = _uiState.value.moveSession ?: return
        menuSound.play(MenuSound.BACK)
        _uiState.update { it.copy(
            currentItems = session.originalItems,
            selectedItemIndex = session.originalIndex,
            moveSession = null,
        )}
        reloadDeferredDuringMove()
    }

    /** Touch: nudge the lifted row one place up (-1) or down (+1). */
    fun onMoveRowBy(delta: Int) {
        markTouchInput()
        shiftMovingRow(delta)
    }

    // ── Category move ─────────────────────────────────────────────────────────
    // Category Manager's Move closes Settings and lifts the category on the crossbar itself, so
    // the user sees the bar they are arranging. Left / right slide it, confirm saves the bar's
    // order, back puts it where it was.

    /** Long-press on the bar's [index]th category icon: its menu, unless something is already over the bar. */
    fun onCategoryLongPress(index: Int) {
        val menu = categoryLongPressMenu(_uiState.value, index) ?: return
        menuSound.play(MenuSound.SELECT)
        _uiState.update { it.copy(activeContextMenu = menu) }
    }

    private fun handleCategoryMenuItem(categoryId: String, itemId: String) {
        when (itemId) {
            // Rename and Change Icon are Category Manager's own screens, opened on this category.
            CATEGORY_MENU_RENAME_ID -> openCategoryManager(categoryId, CategoryManagerTargetAction.RENAME)
            CATEGORY_MENU_ICON_ID -> openCategoryManager(categoryId, CategoryManagerTargetAction.CHANGE_ICON)
            CATEGORY_MENU_MANAGE_ID -> openCategoryManager(categoryId, null)
            CATEGORY_MENU_MOVE_ID -> {
                closeContextMenu()
                startCategoryMove(categoryId)
            }
            CATEGORY_MENU_VISIBLE_ID -> {
                closeContextMenu()
                // The bar's collector drops the icon once the store says so.
                appAction { categoryRepository.setVisible(categoryId, false) }
            }
        }
    }

    private fun openCategoryManager(categoryId: String, action: CategoryManagerTargetAction?) {
        closeContextMenu()
        _uiState.update { it.copy(
            activeSettingsScreen = CATEGORY_MANAGER_SCREEN_ID,
            settingsCategoryTarget = action?.let { a -> CategoryManagerTarget(categoryId, a) },
        )}
    }

    fun onCategoryTargetConsumed() {
        _uiState.update { it.copy(settingsCategoryTarget = null) }
    }

    /** Lifts [categoryId] on the crossbar from the bar's own menu; placing it stays on the bar. */
    fun startCategoryMove(categoryId: String) = liftCategory(categoryId, returnToCategoryId = null)

    /** Lifts [categoryId] from Category Manager; placing it returns to the manager. */
    fun startCategoryMoveFromManager(categoryId: String) =
        liftCategory(categoryId, returnToCategoryId = currentCategory()?.id)

    // Closes Settings and lifts [categoryId] on the crossbar. A category not on the bar is ignored.
    private fun liftCategory(categoryId: String, returnToCategoryId: String?) {
        val index = _uiState.value.categories.indexOfFirst { it.id == categoryId }
        if (index < 0) return
        _uiState.update { it.copy(activeSettingsScreen = null, pendingSettingsAction = null) }
        onCategorySelected(index)
        menuSound.play(MenuSound.SELECT)
        _uiState.update { it.copy(
            categoryMoveSession = CategoryMoveSession(
                originalCategories = it.categories,
                originalIndex = index,
                returnToCategoryId = returnToCategoryId,
            ),
        )}
    }

    private fun shiftMovingCategory(delta: Int): Boolean {
        val state = _uiState.value
        if (state.categoryMoveSession == null) return false
        val (categories, index) = moveCategory(state.categories, state.selectedCategoryIndex, delta) ?: return false
        menuSound.play(MenuSound.SCROLL)
        // The same category stays selected, so its column stays on screen as it travels.
        _uiState.update { it.copy(categories = categories, selectedCategoryIndex = index) }
        return true
    }

    /** Saves the bar's order as it now stands. */
    fun placeMovingCategory() {
        val state = _uiState.value
        val session = state.categoryMoveSession ?: return
        menuSound.play(MenuSound.CONFIRM)
        val order = state.categories.map { it.id }
        // Whatever the store said mid-move is superseded: the save below makes it emit again.
        deferredCategoryBar = null
        _uiState.update { it.copy(categoryMoveSession = null) }
        viewModelScope.launch { categoryRepository.reorder(order) }
        returnFromMove(session, state.categories)
    }

    /** Puts the category — and the bar — back where the move started. */
    fun cancelMovingCategory() {
        val session = _uiState.value.categoryMoveSession ?: return
        menuSound.play(MenuSound.BACK)
        _uiState.update { it.copy(
            categories = session.originalCategories,
            selectedCategoryIndex = session.originalIndex,
            categoryMoveSession = null,
        )}
        deferredCategoryBar?.let { deferredCategoryBar = null; applyCategoryBar(it) }
        returnFromMove(session, _uiState.value.categories)
    }

    // A move begun in Category Manager — placed or cancelled — lands back in it, on the column it
    // was opened from. One begun on the crossbar stays there.
    private fun returnFromMove(session: CategoryMoveSession, categories: List<Category>) {
        session.returnAfterMove(categories)?.let { (index, screen) ->
            index?.let(::onCategorySelected)
            _uiState.update { it.copy(activeSettingsScreen = screen) }
        }
    }

    /** Touch: nudge the lifted category one slot left (-1) or right (+1). */
    fun onMoveCategoryBy(delta: Int) {
        markTouchInput()
        shiftMovingCategory(delta)
    }

    // ── Multi-select ──────────────────────────────────────────────────────────

    private fun toggleMark(item: XMBItem?) {
        val gameId = item?.gameId ?: return
        menuSound.play(MenuSound.SELECT)
        _uiState.update {
            val marked = if (gameId in it.markedGameIds) it.markedGameIds - gameId else it.markedGameIds + gameId
            it.copy(markedGameIds = marked)
        }
    }

    private fun exitMarkMode() = _uiState.update {
        if (!it.markMode && it.markedGameIds.isEmpty()) it else it.copy(markMode = false, markedGameIds = emptySet())
    }

    /** Touch: leave multi-select from the hint bar. */
    fun onExitMarkMode() {
        markTouchInput()
        exitMarkMode()
    }

    /** Touch: open "Add N Games to Card" from the hint bar. */
    fun onAddMarkedToCard() {
        markTouchInput()
        openMarkedCollectionPicker()
    }

    private fun openCategoryCardFolder() = navigateRememberingCursor {
        it.copy(selectedPlatformId = CATEGORY_CARD_PLATFORM_ID, selectedCollectionId = null)
    }

    // The label shown on a console's Icon Display row: its own override when it has one,
    // otherwise the global mode it is currently following.
    private fun platformIconDisplayLabel(platformId: String): String {
        val state = _uiState.value
        val override = state.iconDisplayModeByPlatform[platformId]
        return override?.label ?: "Global: ${state.iconDisplayMode.label}"
    }

    // Second-level menu: the icon display mode for ONE console. "Use Global Setting" clears the
    // override so the card follows the global mode again; per-game overrides still win.
    private fun openPlatformIconDisplayPickerMenu(platformId: String, parent: XMBContextMenu? = null) {
        val state = _uiState.value
        val override = state.iconDisplayModeByPlatform[platformId]
        val items = buildList {
            add(XMBContextMenuItem(
                id      = "picondisp_default",
                label   = "Use Global Setting (${state.iconDisplayMode.label})",
                checked = override == null,
            ))
            IconDisplayMode.entries.forEach { mode ->
                add(XMBContextMenuItem("picondisp_${mode.name}", mode.label, checked = override == mode))
            }
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title      = "Icon Display",
                items      = items,
                platformId = platformId,   // routes selection through the platform handler branch
                parent     = parent,
            )
        )}
    }

    // Second-level menu: the GLOBAL icon display mode (mirrors Artwork Settings ▸ Game Icon
    // Display). Per-game and per-console overrides keep winning; everything else follows live.
    private fun openGlobalIconDisplayPickerMenu(parent: XMBContextMenu? = null) {
        val current = _uiState.value.iconDisplayMode
        val items = IconDisplayMode.entries.map { mode ->
            XMBContextMenuItem("gicondisp_${mode.name}", mode.label, checked = mode == current)
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title      = "Icon Display",
                items      = items,
                isAllGames = true,   // routes selection through the All Games handler branch
                parent     = parent,
            )
        )}
    }

    // Opens the △ options menu for a Shiba Coins hub row — the Player Card, All Tracked Games,
    // and Untracked all carry Update Installed Achievements and Auto-Matching, so the first-ever match/sync
    // (nothing tracked yet, no All Tracked row) is reachable from the card. Returns true when
    // [item] is one it owns.
    private fun openAchievementsContextMenu(item: XMBItem): Boolean {
        if (currentCategory()?.id != BuiltInCategory.ACHIEVEMENTS) return false
        if (item.id != ACH_ALL_ITEM_ID && item.id != ACH_SUMMARY_ITEM_ID && item.id != ACH_UNTRACKED_ITEM_ID) return false
        val matching = _uiState.value.hubMatching
        val syncing = _uiState.value.hubSyncAll != null && !matching
        _uiState.update {
            it.copy(
                activeContextMenu = XMBContextMenu(
                    title = when (item.id) {
                        ACH_SUMMARY_ITEM_ID -> "Player Card"
                        ACH_UNTRACKED_ITEM_ID -> "Untracked"
                        else -> "All Tracked Games"
                    },
                    items = listOf(
                        XMBContextMenuItem(
                            "ach_auto_match",
                            if (matching) "Auto-Matching…" else "Auto-Matching",
                        ),
                        XMBContextMenuItem(
                            "ach_sync_all",
                            if (syncing) "Updating…" else "Update Installed Achievements",
                        ),
                    ),
                    achievementsHubMenu = true,
                ),
            )
        }
        return true
    }

    // Auto-matches every unlinked game on this device (RA hashes, Steam, Local Steam), then runs
    // "Update installed achievements" so fresh links land with their coins. One run at a time;
    // the hub rows show "Auto-matching… n / m", then the update's progress. The tray messages
    // ("Games recognized", the update's one result) come from the shared sequence itself.
    private fun autoMatchFromHub() {
        if (_uiState.value.hubSyncAll != null || _uiState.value.hubMatching) return
        viewModelScope.launch {
            _uiState.update { it.copy(hubMatching = true, hubSyncAll = 0 to 0) }
            refreshAchievementsHubIfVisible()
            try {
                achievementMatchAndUpdate.run(
                    onMatchProgress = { done, total ->
                        _uiState.update { it.copy(hubSyncAll = done to total) }
                        refreshAchievementsHubIfVisible()
                    },
                    onUpdateProgress = { done, total ->
                        _uiState.update { it.copy(hubMatching = false, hubSyncAll = done to total) }
                        refreshAchievementsHubIfVisible()
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Auto-match from the hub failed")
            } finally {
                _uiState.update { it.copy(hubMatching = false, hubSyncAll = null) }
                refreshAchievementsHubIfVisible()
            }
        }
    }

    // "Update installed achievements" from the hub: the same selective update Settings and Player
    // Status run (present, matched games only), with progress on the hub row. One run at a time;
    // re-triggering while active is a no-op (the menu label reads "Updating…" then).
    private fun syncAllCoinsFromHub() {
        if (_uiState.value.hubSyncAll != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(hubSyncAll = 0 to 0) }
            refreshAchievementsHubIfVisible()
            try {
                achievementRepository.updateInstalledAchievements { done, total ->
                    _uiState.update { it.copy(hubSyncAll = done to total) }
                    refreshAchievementsHubIfVisible()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Achievement update from the hub failed")
            } finally {
                _uiState.update { it.copy(hubSyncAll = null) }
                refreshAchievementsHubIfVisible()
            }
        }
    }

    private fun refreshAchievementsHubIfVisible() {
        if (currentCategory()?.id == BuiltInCategory.ACHIEVEMENTS) loadItemsForCategory(currentCategory())
    }

    private fun openGameContextMenu(item: XMBItem) {
        val gameId = item.gameId
        if (gameId == null) {
            openGameContextMenuCore(item, discCount = 0)
            return
        }
        // Whether the game belongs to a multi-disc set is a DB read, so the "Choose Disc" entry
        // (and only that) is decided asynchronously — the rest of the menu builds unchanged.
        viewModelScope.launch {
            val game = runCatching { gameRepository.getById(gameId) }.getOrNull()
            val discCount = runCatching {
                game?.discSetKey?.let { gameRepository.getDiscSetMembers(it).size } ?: 0
            }.getOrDefault(0)
            // The emulator row shows its override by name, which is a profile lookup.
            val emulatorLabel = game?.emulatorPackage?.let { override ->
                runCatching { emulatorProfileRepository.getProfilesForPlatform(game.platformId) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.id == override }?.name ?: override
            } ?: "Default"
            openGameContextMenuCore(item, discCount, emulatorLabel, game?.iconDisplayMode)
        }
    }

    private fun openGameContextMenuCore(
        item: XMBItem,
        discCount: Int,
        emulatorLabel: String = "Default",
        iconDisplayOverride: String? = null,
    ) {
        val currentCat = currentCategory()
        val inGamingCategory = currentCat?.isGamingCategory == true
        val state = _uiState.value
        val listKey = state.currentListKey()
        val isUmdRow = item.type == XMBItemType.UMD_SLOT
        // A games list that can be arranged: any drilled-in card or custom memory card. The
        // Missing bucket is a holding pen, not a list anyone arranges.
        val inGamesList = !isUmdRow && state.currentListKind() == XmbListKind.GAMES &&
            state.selectedPlatformId != MISSING_PLATFORM_ID
        val column = currentCat?.takeIf { it.isGamingCategory }?.id
        val umd = when {
            // UMD Slot Off: there is no slot to insert into or eject from.
            state.umdSlotMode == com.playfieldportal.core.domain.model.UmdSlotMode.OFF -> UmdMenuState.NONE
            column == null || item.gameId == null || !item.isRealGame -> UmdMenuState.NONE
            isUmdRow -> if (column in state.umdInserted) UmdMenuState.INSERTED else UmdMenuState.RECENT
            state.umdInserted[column] == item.gameId -> UmdMenuState.INSERTED
            else -> UmdMenuState.CAN_INSERT
        }
        // The icon mode in force: the game's own override, else its console's, else the global one.
        val iconMode = IconDisplayMode.fromName(iconDisplayOverride)
            ?: item.platformId?.let { state.iconDisplayModeByPlatform[it] }
            ?: state.iconDisplayMode
        val items = gameContextMenuItems(
            item            = item,
            discCount       = discCount,
            inCollection    = state.selectedCollectionId != null,
            currentCategory = currentCat,
            categories      = state.categories,
            inMissingBucket = state.selectedPlatformId == MISSING_PLATFORM_ID,
            hideLabel       = currentHideLocation()?.third,
            pinned          = if (inGamesList) item.pinned else null,
            // A search shows part of the list; saving that as the whole order would drop the rest.
            canMove         = inGamesList && state.isCustomSorted(listKey, XmbListKind.GAMES) &&
                state.gameQuery.isBlank(),
            umd             = umd,
            canSelectMultiple = inGamesList && !state.markMode,
            emulatorLabel   = emulatorLabel,
            iconDisplayLabel = iconMode.label,
        )

        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title       = item.title,
                items       = items,
                rowKey      = item.rowKey(),
                gameId      = item.gameId,
                packageName = item.packageName,
                shortcutId  = item.shortcutId,
                launchIntentUri = item.launchIntentUri,
                categoryContext = if (inGamingCategory) currentCat.id else null,
            )
        )}
    }

    // Second-level menu: the collections a game can be added to (checkmarks show current
    // membership), plus "Create New Collection". Opened from the game options menu. The menu
    // stays open while toggling so the user can add to several collections at once.
    private fun openCollectionPicker(gameId: Long, selectIndex: Int = 0, parent: XMBContextMenu? = null) {
        viewModelScope.launch {
            val memberOf = collectionRepository.getCollectionIdsForGame(gameId).toSet()
            val items = addToCardRows(memberOf)
            _uiState.update { it.copy(
                activeContextMenu = XMBContextMenu(
                    title            = "Add to Card",
                    items            = items,
                    selectedIndex    = selectIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
                    gameId           = gameId,
                    collectionGameId = gameId,
                    parent           = parent,
                )
            )}
        }
    }

    // The Add to Card rows for the column being browsed: its own custom memory cards first. Cards
    // of the other kind (game cards from an app column, and the reverse) are never offered.
    private suspend fun addToCardRows(memberOf: Set<Long>): List<XMBContextMenuItem> {
        val state = _uiState.value
        val current = currentCategory()
        val gamingByCategory = state.categories.associate { it.id to it.isGamingCategory }
        val wantGaming = current?.takeIf { categoryShowsCollections(it) }?.isGamingCategory ?: true
        val collections = collectionRepository.getAll()
            .filter { (gamingByCategory[it.categoryId] ?: (it.categoryId == BuiltInCategory.GAMES)) == wantGaming }
            .map { Triple(it.id, it.name, it.categoryId) }
        return addToCardMenuItems(
            collections = collections,
            currentCategoryId = collectionHomeCategoryId(),
            categoryNames = state.categories.associate { it.id to it.name },
            memberOf = memberOf,
        )
    }

    // "Add N Games to Card" for the games marked in multi-select: picking a card adds them all.
    private fun openMarkedCollectionPicker() {
        val marked = _uiState.value.markedGameIds
        if (marked.isEmpty()) return
        viewModelScope.launch {
            val items = addToCardRows(memberOf = emptySet())
            val noun = if (marked.size == 1) "Game" else "Games"
            _uiState.update { it.copy(
                activeContextMenu = XMBContextMenu(
                    title         = "Add ${marked.size} $noun to Card",
                    items         = items,
                    markedGameIds = marked,
                )
            )}
        }
    }

    private fun openAppContextMenu(item: XMBItem, categoryIdOverride: String? = null, byTouch: Boolean = false) {
        val pkg = item.packageName ?: return
        val categoryId = categoryIdOverride ?: currentCategory()?.id
        val state = _uiState.value
        val canMove = categoryId != null && state.currentListKind() == XmbListKind.APPS &&
            state.isCustomSorted(state.currentListKey(), XmbListKind.APPS)
        // Favorite is the app's launch-shortcut row's state, and a system app is not uninstallable:
        // both are read before the menu is published, so it never changes under the cursor.
        viewModelScope.launch {
            val isFavorite = gameRepository.getAppEntry(pkg)?.isFavorite == true
            val isSystemApp = appCategoryRepository.allInstalledApps().firstOrNull { it.packageName == pkg }?.isSystemApp == true
            val items = appMenuItems(AppMenuContext(
                byTouch      = byTouch,
                isFavorite   = isFavorite,
                categoryId   = categoryId,
                categoryName = categoryId?.let { categoryDisplayName(it) },
                pinned       = item.pinned,
                canMove      = canMove,
                isSystemApp  = isSystemApp,
            )).map { XMBContextMenuItem(it.id, it.label, it.isDestructive, value = it.value, header = it.header, opensMenu = it.opensMenu, silent = it.silent) }
            _uiState.update { it.copy(
                activeContextMenu = XMBContextMenu(
                    title           = item.title,
                    items           = items,
                    rowKey          = item.rowKey(),
                    gameId          = item.gameId,
                    packageName     = pkg,
                    categoryContext = categoryId,
                )
            )}
        }
    }

    // Options menu for a collection row (long-press / △), in any collection-rendering category.
    private fun openCollectionRowContextMenu(collectionId: Long, byTouch: Boolean) {
        val collection = _uiState.value.collections.firstOrNull { it.id == collectionId } ?: return
        // Move is only meaningful when there's another category of the same kind to move into
        // (game collections move between gaming categories, app collections between app ones).
        val hasOtherCategory = collectionMoveTargets(collection.categoryId).isNotEmpty()
        val state = _uiState.value
        val cardListKey = com.playfieldportal.core.domain.model.ListKeys.collection(collectionId)
        val items = customCardMenuItems(
            pinned = collection.isPinned,
            canMoveToCategory = hasOtherCategory,
            sortValue = state.sortValueLabel(cardListKey, collectionSortKind(state.categories.firstOrNull { it.id == collection.categoryId })),
            canMove = canMoveRootRows(),
            byTouch = byTouch,
            // A card's own picker follows what it holds: games in a gaming category, apps otherwise.
            holds = if (state.categories.firstOrNull { it.id == collection.categoryId }?.isGamingCategory == true) {
                CardContents.GAMES
            } else {
                CardContents.APPS
            },
        )
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title           = collection.name,
                items           = items,
                rowKey          = com.playfieldportal.core.domain.model.ListKeys.collectionItem(collectionId),
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
                title           = "Move Custom Card To",
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

    private fun activateContextMenuItem() {
        val menu   = _uiState.value.activeContextMenu ?: return
        val itemId = menu.items.getOrNull(menu.selectedIndex)?.id ?: return

        // ── Games Filter menu — owns its own rows, and its groups stay open ──
        if (handleGamesFilterItem(menu, itemId)) return

        // ── A list's Sort picker / the global Sort picker ──
        if (handleSortMenuItem(menu, itemId)) return

        // ── Rows every arrangeable row's menu shares: Move, Sort, Pin to Top, the UMD slot ──
        if (handleArrangeMenuItem(menu, itemId)) return

        // ── A category icon's menu (long-press on the bar) ──
        if (menu.categoryMenuId != null) {
            handleCategoryMenuItem(menu.categoryMenuId, itemId)
            return
        }

        // ── Notification panel menus (one row, or the list) ──
        if (handleNotificationMenuItem(menu, itemId)) return

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

        // ── "Add N Games to Card" (multi-select) — one pick adds every marked game ──
        if (menu.markedGameIds.isNotEmpty()) {
            val marked = menu.markedGameIds
            closeContextMenu()
            when {
                itemId == "col_new" -> promptCreateCollection(forGameIds = marked)
                itemId.startsWith("col_") -> {
                    val collectionId = itemId.removePrefix("col_").toLongOrNull() ?: return
                    appAction {
                        marked.forEach { collectionRepository.addGame(collectionId, it) }
                        exitMarkMode()
                    }
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
                        openCollectionPicker(gameId, keepIndex, menu.parent)
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

        // Every destructive row asks first; confirmPendingConfirm runs what the row used to.
        confirmFor(menu, itemId)?.let { confirm ->
            _uiState.update { it.copy(pendingConfirm = confirm) }
            return
        }

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
                itemId == ADD_GAMES_TO_CARD_ID -> {
                    closeContextMenu()
                    openCollectionGamePicker(collectionId)
                }
                itemId == ADD_APPS_TO_CARD_ID -> {
                    closeContextMenu()
                    val name = _uiState.value.collections.firstOrNull { it.id == collectionId }?.name
                    openAppPicker(AppPickerTarget.CardApps(collectionId), if (name != null) "Add Apps · $name" else "Add Apps")
                }
                itemId == "rename_collection" -> promptRenameCollection(collectionId)
                itemId == "move_collection_category" -> {
                    val from = _uiState.value.collections.firstOrNull { it.id == collectionId }?.categoryId
                        ?: BuiltInCategory.GAMES
                    openCollectionCategoryPicker(collectionId, from)
                }
                itemId == "pin_collection"   -> appAction { collectionRepository.setPinned(collectionId, true) }
                itemId == "unpin_collection" -> appAction { collectionRepository.setPinned(collectionId, false) }
                itemId == "manage_collections" -> _uiState.update { it.copy(activeSettingsScreen = "settings_collections") }
            }
            return
        }

        // Checked before the row-scoped branches below: these rows ride on a track or playlist
        // menu, whose handler would otherwise see an id it does not know and silently do nothing.
        // The id prefix is what separates them, so a track's own "play" still falls through.
        if (menu.browserList && itemId.startsWith("music_browser_")) {
            when (itemId) {
                "music_browser_resume" -> showMusicPlayer()
                BROWSER_SORT_MENU_ID   -> openBrowserSortMenu(parent = menu)
                BROWSER_IMPORT_MENU_ID -> requestPlaylistImportPick(PlaylistKind.MUSIC)
            }
            browserSortModeOf(itemId)?.let { applyBrowserSort(it) }
            return
        }

        when {
            menu.achievementsHubMenu -> when (itemId) {
                "ach_sync_all" -> syncAllCoinsFromHub()
                "ach_auto_match" -> autoMatchFromHub()
            }
            menu.musicTrackId == MUSIC_PLAYER_MENU_MARKER -> when (itemId) {
                "music_visualizer" -> openMusicVisualizerPicker()
                "music_background" -> musicPlayInBackground()
                "music_playpause"  -> musicPlayPause()
                "music_close"      -> stopAndCloseMusicPlayer()
            }
            menu.musicTrackId != null -> handleMusicTrackAction(menu.musicTrackId, itemId, menu.playlistId)
            menu.mediaCard != null -> handleMediaCardAction(menu.mediaCard, itemId)
            menu.isAllGames -> if (itemId.startsWith("gicondisp_")) {
                IconDisplayMode.fromName(itemId.removePrefix("gicondisp_"))?.let { mode ->
                    viewModelScope.launch { iconDisplayPreferences.setMode(mode) }
                }
            } else when (itemId) {
                "library_manager" -> _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
                // The same rows a platform card offers, over every card at once.
                "scan_all"               -> scanAllCards()
                "update_metadata"        -> updateMetadata(platformId = null)
                "scrape_missing_artwork" -> scrapeMissingArtwork(platformId = null)
                // Progress lands in the notification panel, not here.
                "relink_artwork" -> artworkRelinkLauncher.relinkAll()
                "icon_display_global" -> openGlobalIconDisplayPickerMenu(parent = menu)
            }
            menu.platformId != null -> if (itemId.startsWith("picondisp_")) {
                // Icon display picked for this console ("default" clears the console override so
                // the card follows the global setting again).
                val choice = itemId.removePrefix("picondisp_")
                val pid = menu.platformId
                viewModelScope.launch {
                    iconDisplayPreferences.setPlatformMode(pid, IconDisplayMode.fromName(choice))
                }
            } else when (itemId) {
                "find_games"       -> openAppPicker(AppPickerTarget.AndroidGames(menu.platformId), "Find Games")
                "import_pc_games"  -> _uiState.update { it.copy(activeSettingsScreen = "settings_import_pc") }
                // The shell owns the SAF launcher, so this only raises the request.
                "batch_match_local" -> _uiState.update { it.copy(requestLocalSteamFolderPick = true) }
                "icon_display_platform" -> openPlatformIconDisplayPickerMenu(menu.platformId, parent = menu)
                "scan_roms"        -> scanCard(menu.platformId)
                "scrape_missing_artwork" -> scrapeMissingArtwork(menu.platformId)
                "update_metadata"        -> updateMetadata(menu.platformId)
                "pin"              -> setCardPinned(menu.platformId, true)
                "unpin"            -> setCardPinned(menu.platformId, false)
                "library_manager"  -> _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
                "hide"             -> hideCard(menu.platformId)
            }
            menu.gameId != null -> if (itemId.startsWith("emu_pick_")) {
                // Emulator chosen from the Change Emulator submenu ("default" clears the override).
                val gid = menu.gameId
                val choice = itemId.removePrefix("emu_pick_")
                appAction {
                    gameRepository.setPreferredEmulator(gid, choice.takeIf { it != "default" })
                }
            } else if (itemId.startsWith("icondisp_")) {
                // Icon display mode picked from the Icon Display submenu ("default" clears the
                // per-game override so the game follows the global setting again).
                val gid = menu.gameId
                val choice = itemId.removePrefix("icondisp_")
                appAction {
                    gameRepository.setIconDisplayMode(gid, IconDisplayMode.fromName(choice)?.name)
                }
            } else if (itemId.startsWith("disc_pick_")) {
                // Disc chosen from the "Choose Disc" submenu — only remember the preferred disc.
                // Launching remains an explicit confirm action from the XMB entity.
                val discId = itemId.removePrefix("disc_pick_").toLongOrNull()
                if (discId != null) {
                    appAction { gameRepository.setPreferredDisc(menu.gameId, discId) }
                }
            } else when (itemId) {
                // Always opens the Game Detail screen (no auto-launch) — the edit surface for
                // artwork, title, notes, emulator when direct launch is the confirm behavior.
                "game_details"           -> _uiState.update {
                    it.copy(activeGameId = menu.gameId, activeGameAutoLaunch = false)
                }
                "choose_disc"             -> openDiscPickerMenu(menu.gameId, parent = menu)
                "view_shiba_coins"       -> _uiState.update {
                    it.copy(activeShibaCoinsTarget = com.playfieldportal.feature.xmb.ui.detail.ShibaCoinsTarget.LibraryGame(menu.gameId))
                }
                "install_goldberg"       -> installGoldbergForGame(menu.gameId)
                "export_game"            -> exportGameFromMenu(menu.gameId)
                "edit_app"               -> openAppDetail(menu.gameId, menu.packageName ?: return)
                "favorite_toggle"        -> {
                    val gid = menu.gameId
                    appAction { gameRepository.getById(gid)?.let { gameRepository.setFavorite(gid, !it.isFavorite) } }
                }
                // An app row's menu that carries a game id reaches this branch first; its Favorite
                // only ever sets (the app handler's own rule).
                "favorite"               -> toggleGameFavorite(menu.gameId, true)
                "add_to_collection"      -> openCollectionPicker(menu.gameId, parent = menu)
                "remove_from_collection" -> {
                    val gid = menu.gameId   // local val so it smart-casts inside the lambda
                    _uiState.value.selectedCollectionId?.let { cid ->
                        appAction { collectionRepository.removeGame(cid, gid) }
                    }
                }
                "manage_collections"     -> _uiState.update { it.copy(activeSettingsScreen = "settings_collections") }
                "add_category"           -> menu.categoryContext?.let { openGameCategoryPicker(menu.gameId, it, "add", menu) }
                "move_category"          -> menu.categoryContext?.let { openGameCategoryPicker(menu.gameId, it, "move", menu) }
                // Leaving a category means leaving its custom memory cards too — the game would
                // otherwise stay on the category's Memory Card through them. When it sits in
                // any, a confirm names them first; a loose game just goes.
                "remove_category"        -> menu.categoryContext?.let { cat ->
                    val gid = menu.gameId
                    val title = menu.title
                    appAction {
                        val cards = gameCategoryRepository.categoryCardGames(cat)
                            .firstOrNull { it.game.id == gid }?.cardNames.orEmpty()
                        if (cards.isEmpty()) {
                            gameCategoryRepository.removeGameFromCategoryEverywhere(gid, cat)
                            loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
                        } else {
                            _uiState.update { it.copy(pendingConfirm = XmbConfirm.RemoveFromCategory(
                                gameId       = gid,
                                categoryId   = cat,
                                categoryName = categoryDisplayName(cat),
                                title        = title,
                                cardNames    = cards,
                            ))}
                        }
                    }
                }
                "file_location"          -> showGameFileLocation(menu.gameId)
                "change_emulator"        -> openEmulatorPickerMenu(menu.gameId, parent = menu)
                "icon_display"           -> openIconDisplayPickerMenu(menu.gameId, parent = menu)
                "fetch_artwork"          -> fetchArtworkFromMenu(menu.gameId)
                "hide_here"              -> currentHideLocation()?.let { (type, id, label) ->
                    persistHide(HiddenPlacement.gameKey(menu.gameId), menu.title, type, id, label)
                }
                // Demote an Android-card game to a standard app: the row survives as a decoration
                // shortcut (art/favorites/collections intact) but leaves the card and All Games.
                "unmark_game"            -> {
                    val gid = menu.gameId
                    appAction {
                        gameRepository.getById(gid)?.let { g ->
                            gameRepository.upsert(g.copy(
                                platformId  = APP_SHORTCUT_PLATFORM_ID,
                                contentType = GameContentType.ANDROID_APP,
                            ))
                        }
                        memoryCardRepository.recountGames(ANDROID_PLATFORM_ID)
                        loadItemsForCategory(currentCategory())
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
                    // Promote a standard app to the Android card as a real game (reuses any
                    // existing decoration row so art/favorites/collections carry over).
                    "mark_game" -> appAction {
                        val existing = gameRepository.getAppEntry(pkg)
                        if (existing == null) {
                            gameRepository.upsert(Game(
                                title         = menu.title,
                                platformId    = ANDROID_PLATFORM_ID,
                                packageName   = pkg,
                                isManualEntry = true,
                                contentType   = GameContentType.GAME,
                            ))
                        } else {
                            gameRepository.upsert(existing.copy(
                                platformId  = ANDROID_PLATFORM_ID,
                                contentType = GameContentType.GAME,
                            ))
                        }
                        memoryCardRepository.recountGames(ANDROID_PLATFORM_ID)
                    }
                    AppMenuIds.FAVORITE -> toggleAppFavorite(pkg, menu.title)
                    AppMenuIds.APP_INFO -> installedAppRepository.openAppInfo(pkg)
                    "add_to_collection" -> addAppToCollection(pkg, menu.title)
                    "move"      -> openCategoryPicker(pkg, menu.categoryContext, "move")
                    "add"       -> openCategoryPicker(pkg, menu.categoryContext, "add")
                    "remove"    -> menu.categoryContext?.let { cat -> appAction { appCategoryRepository.removeFromCategory(pkg, cat) } }
                    "pin"       -> menu.categoryContext?.let { cat -> appAction { appCategoryRepository.pinToCategory(pkg, cat) } }
                    "unpin"     -> menu.categoryContext?.let { cat -> appAction { appCategoryRepository.unpinFromCategory(pkg, cat) } }
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

    // Submenu listing every installed emulator that supports the game's platform. Selecting a row
    // dispatches "emu_pick_<profileId>" (or "emu_pick_default" to clear the per-game override).
    // Second-level menu: how this game's XMB tile is drawn. Checkmark shows the current choice;
    // "Use Global Setting" clears the per-game override.
    private fun openIconDisplayPickerMenu(gameId: Long, parent: XMBContextMenu? = null) {
        viewModelScope.launch {
            val game = gameRepository.getById(gameId) ?: return@launch
            val override = IconDisplayMode.fromName(game.iconDisplayMode)
            // What the game falls back to: its console's override, else the global mode.
            val state = _uiState.value
            val inherited = state.iconDisplayModeByPlatform[game.platformId] ?: state.iconDisplayMode
            val items = buildList {
                add(XMBContextMenuItem(
                    id      = "icondisp_default",
                    label   = "Use Default (${inherited.label})",
                    checked = override == null,
                ))
                IconDisplayMode.entries.forEach { mode ->
                    add(XMBContextMenuItem("icondisp_${mode.name}", mode.label, checked = override == mode))
                }
            }
            _uiState.update { it.copy(activeContextMenu = XMBContextMenu(
                title  = "Icon Display",
                items  = items,
                gameId = gameId,
                parent = parent,
            ))}
        }
    }

    private fun openEmulatorPickerMenu(gameId: Long, parent: XMBContextMenu? = null) {
        viewModelScope.launch {
            val game = gameRepository.getById(gameId) ?: return@launch
            val profiles = emulatorProfileRepository.getProfilesForPlatform(game.platformId)
            val items = buildList {
                add(XMBContextMenuItem("emu_pick_default", "Use Platform Default", checked = game.emulatorPackage == null))
                profiles.forEach { add(XMBContextMenuItem("emu_pick_${it.id}", it.name, checked = game.emulatorPackage == it.id)) }
            }
            _uiState.update { it.copy(activeContextMenu = XMBContextMenu(
                title  = "Choose Emulator",
                items  = items,
                gameId = gameId,
                parent = parent,
            ))}
        }
    }

    // Second-level menu: the discs of a multi-disc set. Picking one only records it as the preferred
    // disc (see "disc_pick_" above); launching stays an explicit confirm on the row. The primary
    // row is marked, matching the detail page's default selection.
    private fun openDiscPickerMenu(gameId: Long, parent: XMBContextMenu? = null) {
        viewModelScope.launch {
            val game = gameRepository.getById(gameId) ?: return@launch
            val key = game.discSetKey ?: return@launch
            val members = gameRepository.getDiscSetMembers(key)
            if (members.size <= 1) return@launch
            val preferredDiscId = members.firstOrNull { it.isDiscPrimary }?.id
            val items = members
                .sortedWith(compareBy<Game> { it.discNumber == null }.thenBy { it.discNumber ?: Int.MAX_VALUE }.thenBy { it.id })
                .map { member ->
                    XMBContextMenuItem(
                        id      = "disc_pick_${member.id}",
                        label   = member.discNumber?.let { "Disc $it" } ?: "Playlist",
                        checked = member.id == preferredDiscId,
                    )
                }
            _uiState.update { it.copy(activeContextMenu = XMBContextMenu(
                title  = "Choose Disc",
                items  = items,
                gameId = gameId,
                parent = parent,
            ))}
        }
    }

    // Deletes a game row (never the file). "Remove from Library" is a plain removal: the file
    // stays on disk and is re-discovered by the next scan of any kind.
    private suspend fun removeGameFromLibrary(gameId: Long) {
        val game = gameRepository.getById(gameId) ?: return
        gameRepository.delete(gameId)
        memoryCardRepository.recountGames(game.platformId)
        loadItemsForCategory(currentCategory())
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

    private fun promptCreateCollection(forGameId: Long? = null, forGameIds: Set<Long> = emptySet()) {
        _uiState.update { it.copy(
            collectionNameDialog = CollectionNameDialogState(
                title = "New Custom Memory Card",
                forGameId = forGameId,
                forGameIds = forGameIds,
            )
        )}
    }

    private fun promptRenameCollection(collectionId: Long) {
        val name = _uiState.value.collections.firstOrNull { it.id == collectionId }?.name.orEmpty()
        _uiState.update { it.copy(
            collectionNameDialog = CollectionNameDialogState(
                title = "Rename Custom Memory Card",
                initialText = name,
                renameCollectionId = collectionId,
            )
        )}
    }

    fun onConfirmCollectionName(name: String) {
        val dialog = _uiState.value.collectionNameDialog ?: return
        _uiState.update { it.copy(collectionNameDialog = null) }
        // Game Edit Title / Edit Note targets: blank input clears the override/note.
        if (dialog.editTitleGameId != null) {
            viewModelScope.launch {
                gameRepository.updateUserTitleOverride(dialog.editTitleGameId, name.trim().ifBlank { null })
                // The new title re-sorts the list: follow the renamed game, not its old slot.
                loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
            }
            return
        }
        if (dialog.editNoteGameId != null) {
            viewModelScope.launch {
                gameRepository.updateNote(dialog.editNoteGameId, name.trim().ifBlank { null })
            }
            return
        }
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
                dialog.forGameIds.forEach { collectionRepository.addGame(id, it) }
                if (dialog.forGameIds.isNotEmpty()) exitMarkMode()
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

    // Per-game Goldberg conversion from the Shiba/game context menu. When the installer is off the
    // user is told there is no proper achievements.json (and how to enable generation); when it is
    // on, the game's emu folder is matched by title and its schema kit is written in place.
    /** Export Game from the XMB menu (C18 task X.7); the outcome shows like Install Goldberg's. */
    private fun exportGameFromMenu(gameId: Long) {
        viewModelScope.launch {
            val game = gameRepository.getById(gameId) ?: return@launch
            val report = runCatching { pcGameExporter.exportGame(gameId) }
                .onFailure { Timber.e(it, "Export Game failed for gameId=$gameId") }
                .getOrNull()
            _uiState.update {
                it.copy(infoDialog = InfoDialogState(title = game.displayTitle, message = report?.message ?: "Export failed — see the log."))
            }
        }
    }

    /** Fetch Artwork from the XMB game menu; progress and outcome go to the notification panel. */
    private fun fetchArtworkFromMenu(gameId: Long) {
        viewModelScope.launch {
            if (gameArtworkFetchRunner.run(gameId)) loadItemsForCategory(currentCategory())
        }
    }

    private fun installGoldbergForGame(gameId: Long) {
        viewModelScope.launch {
            val game = gameRepository.getById(gameId) ?: return@launch
            fun info(message: String) = _uiState.update {
                it.copy(infoDialog = InfoDialogState(title = game.displayTitle, message = message))
            }
            if (!achievementCredentials.goldbergInstallerEnabled()) {
                info(
                    "No proper achievements.json for this game. Turn on \"Install Goldberg & " +
                        "Convert Games\" in Achievement settings to generate one.",
                )
                return@launch
            }
            val key = normalizePcTitleKey(game.displayTitle)
            val folder = localSteamDiscovery.scanAll().firstOrNull { normalizePcTitleKey(it.folderName) == key }
            when {
                folder == null -> info("No Steam-emu setup (steam_settings) found for this game.")
                folder.hasSchema -> info("${game.displayTitle} already has achievement data.")
                else -> when (localSteamSchemaGenerator.generate(folder)) {
                    is com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaGenerator.Result.Written ->
                        info("Installed Goldberg achievements. Play it through the emulator to start earning coins.")
                    is com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaGenerator.Result.NoAchievements ->
                        info("This game has no achievements to install.")
                    is com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaGenerator.Result.NoKey ->
                        info("Add your Steam Web API key in Achievement settings first.")
                    is com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaGenerator.Result.Failed ->
                        info("Couldn't install achievements for this game.")
                }
            }
        }
    }

    // Folder-name/title match key, mirroring LocalSteamGameImporter.normalizeTitle.
    private fun normalizePcTitleKey(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    // Called from touch interaction on the overlay
    fun onContextMenuItemActivatedAt(index: Int) {
        _uiState.update { it.copy(activeContextMenu = it.activeContextMenu?.copy(selectedIndex = index)) }
        // A tap is the same press as SELECT on that row, so it sounds the same.
        _uiState.value.activeContextMenu?.press(GamepadAction.SELECT) { menuSound.play(it) }
        activateContextMenuItem()
    }

    fun closeContextMenu() {
        _uiState.update { it.copy(activeContextMenu = null) }
    }

    // ── Installed-app picker ────────────────────────────────────────────────────

    // The installed-app picker's cursor (grid and removal confirmation) on the unified engine.
    private val appPickerNav = AppPickerNav(NavigationLogger { Timber.w(it) })

    // Opens the picker with current membership pre-checked (both `selected` and
    // `initialSelected`), so Apply diffs against the state the picker opened with.
    private fun openAppPicker(target: AppPickerTarget, title: String) {
        viewModelScope.launch {
            val installed = appCategoryRepository.allInstalledApps()
            // Icons resolve once, here, on IO — never per-tile in composition.
            val entries = installed.map {
                AppPickerEntry(packageName = it.packageName, label = it.label, icon = it.icon)
            }   // already sorted by label
            val membership: Set<String> = when (target) {
                is AppPickerTarget.AndroidGames ->
                    gameRepository.observeByPlatform(target.platformId).first()
                        .mapNotNull { it.packageName }
                        .toSet()
                is AppPickerTarget.CategoryShortcuts ->
                    appCategoryRepository.packagesIn(target.categoryId)
                is AppPickerTarget.CardApps ->
                    collectionRepository.observeGames(target.collectionId).first()
                        .mapNotNullTo(mutableSetOf()) { it.packageName }
            }
            _uiState.update {
                it.copy(appPicker = AppPickerState(
                    title           = title,
                    target          = target,
                    apps            = entries,
                    selected        = membership,
                    initialSelected = membership,
                ))
            }
        }
    }

    // Touch: a tap on a tile parks the (hidden) cursor there and toggles it.
    fun onAppPickerTileTapped(index: Int) {
        markTouchInput()
        // While the confirmation modal is up, the grid behind the scrim is inert.
        if (_uiState.value.appPicker?.confirmingRemovals == true) return
        val before = _uiState.value.appPicker
        _uiState.update {
            val picker = it.appPicker ?: return@update it
            val visible = picker.visibleApps()
            val app = visible.getOrNull(index) ?: return@update it
            it.copy(appPicker = picker.copy(focusedIndex = index, usingTouch = true)
                .toggle(app.packageName))
        }
        appPickerSound(before, _uiState.value.appPicker)?.let(menuSound::play)
    }

    // Touch: finger-scroll settled (or drag started) on a tile — park the hidden cursor there.
    fun onAppPickerTouchBrowse(index: Int) {
        markTouchInput()
        if (_uiState.value.appPicker?.confirmingRemovals == true) return
        _uiState.update {
            val picker = it.appPicker ?: return@update it
            val lastIndex = (picker.visibleApps().size - 1).coerceAtLeast(0)
            it.copy(appPicker = picker.copy(
                focusedIndex = index.coerceIn(0, lastIndex),
                usingTouch = true,
            ))
        }
    }

    // Touch: the header's ‹ / title.
    fun onAppPickerHeaderBack() {
        markTouchInput()
        handleAppPickerBack()
    }

    // Touch: the confirmation panel's Remove / Cancel rows.
    fun onAppPickerConfirmRemoval() {
        markTouchInput()
        commitAppPicker()
    }

    fun onAppPickerCancelRemoval() {
        markTouchInput()
        cancelConfirm()
    }

    // Touch: an Apply affordance (footer taps); same two-pass path as gamepad HOME.
    fun onAppPickerApply() {
        markTouchInput()
        requestApplyAppPicker()
    }

    // Touch: toggling the search field on/off. Clearing the query on close matches the drawer.
    fun onAppPickerSearchToggle(active: Boolean) {
        markTouchInput()
        _uiState.update {
            val picker = it.appPicker ?: return@update it
            it.copy(appPicker = (if (active) picker.copy(searchActive = true) else closeAppPickerSearch(picker)).clampFocus())
        }
    }

    fun onAppPickerQueryChange(query: String) {
        _uiState.update {
            val picker = it.appPicker ?: return@update it
            // Filtering never moves the cursor by itself, but a shrunken list must not strand it.
            it.copy(appPicker = picker.copy(query = query).clampFocus())
        }
    }

    private fun closeAppPickerSearch(picker: AppPickerState): AppPickerState =
        picker.copy(searchActive = false, query = "")

    fun onAppPickerSearchDone() {
        // ImeAction.Search — keep the field open; the query is live. Nothing to commit.
    }

    private fun moveAppPicker(action: GamepadAction) {
        val before = _uiState.value.appPicker
        _uiState.update { state ->
            val picker = state.appPicker ?: return@update state
            // While the removal-confirmation modal is up, the dpad belongs to the modal's
            // Cancel/Remove cursor — the grid behind the scrim must not move.
            state.copy(appPicker = appPickerNav.move(picker, action))
        }
        appPickerSound(before, _uiState.value.appPicker)?.let(menuSound::play)
    }

    private fun toggleFocusedApp() {
        val before = _uiState.value.appPicker
        _uiState.update {
            val picker = it.appPicker ?: return@update it
            val app = picker.visibleApps().getOrNull(picker.focusedIndex) ?: return@update it
            it.copy(appPicker = picker.toggle(app.packageName))
        }
        appPickerSound(before, _uiState.value.appPicker)?.let(menuSound::play)
    }

    private fun cancelConfirm() {
        _uiState.update {
            val picker = it.appPicker ?: return@update it
            it.copy(appPicker = picker.cancelConfirm())
        }
    }

    fun closeAppPicker() {
        _uiState.update { it.copy(appPicker = null) }
    }

    // Apply (HOME) — a full sync: adds newly-checked apps, removes newly-unchecked ones.
    // Removals never run silently: the first pass raises the confirmation panel; the second
    // (confirmed) pass commits.
    private fun requestApplyAppPicker() {
        val picker = _uiState.value.appPicker ?: return
        val adds = picker.pendingAdds()
        val removals = picker.pendingRemovals()
        if (adds.isEmpty() && removals.isEmpty()) {
            closeAppPicker()
            return
        }
        if (removals.isNotEmpty() && !picker.confirmingRemovals) {
            _uiState.update { state ->
                state.copy(appPicker = state.appPicker?.openConfirm())
            }
            return
        }
        commitAppPicker()
    }

    private fun commitAppPicker() {
        val picker = _uiState.value.appPicker ?: return
        val adds = picker.pendingAdds()
        val removals = picker.pendingRemovals()
        if (adds.isEmpty() && removals.isEmpty()) {
            closeAppPicker()
            return
        }
        // A committed Apply — the point of no return, distinct from SELECT's descent into the
        // picker. closeAppPicker plays nothing, so this is a single chime.
        menuSound.play(MenuSound.CONFIRM)
        val target = picker.target
        closeAppPicker()

        viewModelScope.launch {
            when (target) {
                is AppPickerTarget.AndroidGames -> {
                    if (adds.isNotEmpty()) importAndroidGames(target.platformId, adds)
                    if (removals.isNotEmpty()) removeAndroidGames(target.platformId, removals)
                    // One recount after the whole batch, whichever half ran.
                    memoryCardRepository.recountGames(target.platformId)
                }
                is AppPickerTarget.CategoryShortcuts -> {
                    adds.forEach { pkg -> appCategoryRepository.addToCategory(pkg, target.categoryId) }
                    removals.forEach { pkg -> appCategoryRepository.removeFromCategory(pkg, target.categoryId) }
                }
                is AppPickerTarget.CardApps -> {
                    // A card holds library rows: an app joins through its shortcut row (made on
                    // first use, as Add to Card does) and leaves by that row.
                    adds.forEach { pkg -> collectionRepository.addGame(target.collectionId, ensureAppShortcut(pkg)) }
                    val members = collectionRepository.observeGames(target.collectionId).first()
                        .filter { it.packageName in removals }
                    members.forEach { collectionRepository.removeGame(target.collectionId, it.id) }
                }
            }
        }
    }

    // Reuses the exact path the Library Manager's Remove row uses: getAppEntry then delete.
    private suspend fun removeAndroidGames(platformId: String, packages: Set<String>) {
        packages.forEach { pkg ->
            val entry = gameRepository.getAppEntry(pkg) ?: return@forEach
            if (entry.platformId != platformId) return@forEach
            gameRepository.delete(entry.id)
        }
        Timber.i("Android library removal: ${packages.size} app(s) removed from $platformId")
    }

    // BACK / ‹ unwinds one layer at a time: search → confirmation → picker. Backing out of a
    // dirty picker must never touch the library.
    private fun handleAppPickerBack() {
        val picker = _uiState.value.appPicker ?: return
        when {
            picker.searchActive -> _uiState.update { state ->
                state.copy(appPicker = state.appPicker?.let(::closeAppPickerSearch)?.clampFocus())
            }
            picker.confirmingRemovals -> cancelConfirm()
            else -> closeAppPicker()
        }
    }

    // ── Game picker (for gaming categories) ────────────────────────────────────

    fun openGamePicker(categoryId: String) {
        // The games already put in the category open checked, so the picker shows membership
        // rather than a blank slate. Loaded first: the picker reads them as it appears.
        viewModelScope.launch {
            val preselected = runCatching { gameCategoryRepository.looseGameIds(categoryId).toSet() }
                .getOrDefault(emptySet())
            _uiState.update { it.copy(gamePickerCategoryId = categoryId, gamePickerPreselected = preselected) }
        }
    }

    /** A custom memory card's Add Games: the same picker, filling the card instead of a category. */
    fun openCollectionGamePicker(collectionId: Long) {
        viewModelScope.launch {
            val preselected = runCatching { collectionRepository.addedAtByGame(collectionId).keys }
                .getOrDefault(emptySet())
            _uiState.update { it.copy(gamePickerCollectionId = collectionId, gamePickerPreselected = preselected) }
        }
    }

    fun closeGamePicker() {
        _uiState.update { it.copy(
            gamePickerCategoryId = null,
            gamePickerCollectionId = null,
            gamePickerPreselected = emptySet(),
            pendingGamePickerAction = null,
        )}
    }

    fun consumeGamePickerAction() {
        _uiState.update { it.copy(pendingGamePickerAction = null) }
    }

    fun confirmGamePicker(selectedGameIds: Set<Long>, selectedCollectionIds: Set<Long>) {
        val preselectedGameIds = _uiState.value.gamePickerPreselected
        _uiState.value.gamePickerCollectionId?.let { collectionId ->
            menuSound.play(MenuSound.CONFIRM)
            closeGamePicker()
            appAction {
                // The card opened with its games checked; the same add/remove rule as a category.
                val already = collectionRepository.addedAtByGame(collectionId).keys
                val (added, removed) = gamePickerChanges(already, preselectedGameIds, selectedGameIds)
                added.forEach { collectionRepository.addGame(collectionId, it) }
                removed.forEach { collectionRepository.removeGame(collectionId, it) }
            }
            return
        }
        val categoryId = _uiState.value.gamePickerCategoryId ?: return
        menuSound.play(MenuSound.CONFIRM)
        closeGamePicker()

        viewModelScope.launch {
            // The picker opens with the category's games already checked; see gamePickerChanges.
            val already = gameCategoryRepository.looseGameIds(categoryId).toSet()
            val (added, removed) = gamePickerChanges(already, preselectedGameIds, selectedGameIds)
            added.forEach { gameId ->
                gameCategoryRepository.addGameToCategory(gameId, categoryId)
            }
            removed.forEach { gameId ->
                gameCategoryRepository.removeGameFromCategory(gameId, categoryId)
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
    private fun openGameCategoryPicker(
        gameId: Long,
        fromCategoryId: String,
        action: String,
        parent: XMBContextMenu? = null,
    ) {
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
                parent      = parent,
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
                        // Adding to the Android library is the user saying "this app is a game" —
                        // it counts in All Games and can join gaming categories/collections.
                        contentType   = com.playfieldportal.core.domain.model.GameContentType.GAME,
                    )
                )
                // A shortcut/decoration row exists — promote it into the library as a game,
                // keeping its artwork, favorites and collection memberships.
                existing.platformId != platformId ||
                    existing.contentType != com.playfieldportal.core.domain.model.GameContentType.GAME ->
                    gameRepository.upsert(existing.copy(
                        platformId  = platformId,
                        contentType = com.playfieldportal.core.domain.model.GameContentType.GAME,
                    ))
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

            // The Windows card runs the full PC pass (pin sweep incl. pins never added, the
            // <windows>/import exports, emu folder reconcile) — extension scanning means nothing
            // to it, and "Scan for Games" must behave exactly like the Library Manager action.
            if (platformId == WINDOWS_PLATFORM_ID) {
                val title = "${card.displayName.removeSuffix(" Memory Card")} scan finished"
                backgroundTasks.startStoppable(taskId, "Scanning ${card.displayName}…", TaskKind.SCAN,
                    stopNote = "Games found so far are kept.")
                var error: Throwable? = null
                val report = try {
                    runCatching { pcGameScanner.scan() }
                        .onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            Timber.e(it, "PC scan failed"); error = it
                        }
                        .getOrNull()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    backgroundTasks.settleCancelled(taskId, "Stopped before it finished",
                        NotificationAction.OpenMemoryCard(platformId), title = "${card.displayName} scan stopped")
                    throw e
                }
                if (report == null) {
                    backgroundTasks.fail(
                        taskId, "PC scan failed", NotificationAction.OpenMemoryCard(platformId),
                        detail = NotificationDetail.notes(PfpErrorCode.SC_9001, summary = error?.message,
                            diagnostic = error?.stackTraceToString()?.take(4_000)),
                        title = "${card.displayName} scan failed",
                    )
                } else {
                    memoryCardRepository.recordScan(platformId, System.currentTimeMillis())
                    backgroundTasks.complete(
                        taskId,
                        if (report.newGames == 0) "No new PC games found" else report.message,
                        NotificationAction.OpenMemoryCard(platformId),
                        title = title,
                    )
                    // No emulator work here, by design. Scanning for Steam-emu folders under the
                    // windows surfaces meant walking trees the game folders are no longer under;
                    // they are pointed at once through Match Achievements instead, which is
                    // its own item on this same context menu.
                }
                return@launch
            }

            // No counts: LibraryScanner reports completion only, never per platform or on
            // progress, so this bar is honestly indeterminate rather than faked. "n of m Memory
            // Cards" is the named upgrade path (plan section 4.3) — it needs no counting pre-pass,
            // which is the one thing the media-scan performance work must not pay for again.
            val name = card.displayName.removeSuffix(" Memory Card")
            backgroundTasks.startStoppable(taskId, "Scanning ${card.displayName}…", TaskKind.SCAN,
                stopNote = "Games found so far are kept.")
            val outcome = try {
                libraryScanner.scanPlatform(platformId, removeMissing = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                backgroundTasks.settleCancelled(taskId, "Stopped before it finished",
                    NotificationAction.OpenMemoryCard(platformId), title = "$name scan stopped")
                throw e
            }
            val body = scanOutcomeMessage(outcome, removeMissing = true).removePrefix("${outcome.displayName}: ")
            when (outcome.status) {
                ScanStatus.COMPLETED -> backgroundTasks.complete(
                    taskId, body, NotificationAction.OpenMemoryCard(platformId), title = "$name scan finished",
                )
                else -> backgroundTasks.fail(
                    taskId, body, NotificationAction.OpenMemoryCard(platformId),
                    detail = outcome.failureNotes(),
                    title = if (outcome.status == ScanStatus.FAILED) "$name scan failed" else "$name scan skipped",
                )
            }
        }
    }

    private fun cardName(platformId: String): String =
        enabledCards.firstOrNull { it.platformId == platformId }?.displayName ?: platformId.uppercase()

    // The scope a card job names in its tray row, and where tapping that row leads. A null
    // platform is the All Games card: every card at once, with no single card to open.
    private fun jobScopeName(platformId: String?): String = platformId?.let(::cardName) ?: "All Games"

    private fun jobScopeAction(platformId: String?): NotificationAction =
        platformId?.let { NotificationAction.OpenMemoryCard(it) } ?: NotificationAction.None

    // Scan All Cards: every enabled card that has something to scan, one after another, as ONE
    // tray row. A per-card row each would bury the tray and ring once per console.
    private fun scanAllCards() {
        viewModelScope.launch {
            val cards = enabledCards.filter { it.platformId == WINDOWS_PLATFORM_ID || it.isScannable() }
            val taskId = "scan_all"
            backgroundTasks.startStoppable(taskId, "Scanning all Memory Cards…", TaskKind.SCAN,
                stopNote = "Cards already scanned keep their new games.")
            if (cards.isEmpty()) {
                backgroundTasks.fail(taskId, "No Memory Card has a folder to scan",
                    detail = NotificationDetail.notes(PfpErrorCode.SC_1001), title = "Nothing to scan")
                return@launch
            }
            var added = 0
            var failed = 0
            // One outcome per card, kept for the Results sheet — which cards failed, and why.
            val outcomes = mutableListOf<PlatformScanOutcome>()
            try {
                cards.forEachIndexed { index, card ->
                    updateBackgroundTask(taskId, index + 1, cards.size, card.displayName)
                    // The same pass each card's own Scan for Games runs — see scanCard.
                    if (card.platformId == WINDOWS_PLATFORM_ID) {
                        var error: Throwable? = null
                        val report = runCatching { pcGameScanner.scan() }
                            .onFailure {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                Timber.e(it, "PC scan failed"); error = it
                            }
                            .getOrNull()
                        if (report == null) {
                            failed++
                            outcomes += PlatformScanOutcome(card.platformId, card.displayName, ScanStatus.FAILED,
                                errorMessage = error?.message ?: "PC scan failed")
                        } else {
                            memoryCardRepository.recordScan(card.platformId, System.currentTimeMillis())
                            added += report.newGames
                            outcomes += PlatformScanOutcome(card.platformId, card.displayName, ScanStatus.COMPLETED,
                                added = report.newGames)
                        }
                    } else {
                        val outcome = libraryScanner.scanPlatform(card.platformId, removeMissing = true)
                        outcomes += outcome
                        if (outcome.status == ScanStatus.COMPLETED) added += outcome.added else failed++
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                backgroundTasks.settleCancelled(
                    taskId, "Stopped after ${outcomes.size} of ${cards.size} cards",
                    detail = scanResults(outcomes), title = "Scan All Memory Cards stopped",
                )
                throw e
            }
            val summary = scanAllSummary(added, cards.size, failed)
            val detail = scanResults(outcomes)
            if (failed == cards.size) {
                backgroundTasks.fail(taskId, summary, detail = detail, title = "Scan All Memory Cards failed")
            } else {
                backgroundTasks.complete(taskId, summary, detail = detail, title = "Scan All Memory Cards finished")
            }
        }
    }

    // Scans this card's games — or, from All Games, every game — for missing/broken primary
    // artwork and fetches only those. Valid artwork is never re-downloaded or overwritten.
    private fun scrapeMissingArtwork(platformId: String?) {
        viewModelScope.launch {
            val taskId = "scrape_missing_${platformId ?: "all"}"
            backgroundTasks.startStoppable(taskId, "Fetching missing artwork: ${jobScopeName(platformId)}",
                TaskKind.ARTWORK, stopNote = "Artwork already fetched is kept.")
            // Every game's result, for the Results sheet — which games failed, and why.
            val outcomes = mutableListOf<GameScrapeOutcome>()
            runCatching {
                // ScrapeProgress is the richest producer in the app: counts, per-item tallies and
                // the title being fetched. All three reach the row now instead of being divided
                // into a fraction and dropped.
                val onProgress: (ScrapeProgress) -> Unit = { p ->
                    p.finished?.let(outcomes::add)
                    updateBackgroundTask(taskId, p.current, p.total, p.title)
                }
                if (platformId == null) artworkRepository.scrapeMissingOnly(onProgress)
                else artworkRepository.scrapeMissingForPlatform(platformId, onProgress)
            }.onSuccess { result ->
                backgroundTasks.complete(
                    taskId,
                    if (result.total == 0) "No games are missing artwork"
                    else "${result.succeeded} of ${result.total} game(s) updated",
                    jobScopeAction(platformId),
                    detail = outcomes.takeIf { it.isNotEmpty() }?.let(::scrapeResults),
                    title = "Artwork fetch finished: ${jobScopeName(platformId)}",
                )
                loadItemsForCategory(currentCategory())
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) {
                    backgroundTasks.settleCancelled(
                        taskId, "Stopped after ${outcomes.size} game(s)", jobScopeAction(platformId),
                        detail = scrapeResults(outcomes), title = "Artwork fetch stopped: ${jobScopeName(platformId)}",
                    )
                    throw it
                }
                backgroundTasks.fail(
                    taskId, "Artwork fetch failed", jobScopeAction(platformId),
                    detail = NotificationDetail.notes(PfpErrorCode.AR_9001, summary = it.message,
                        diagnostic = it.stackTraceToString().take(4_000)),
                    title = "Artwork fetch failed: ${jobScopeName(platformId)}",
                )
            }
        }
    }

    // Text-only metadata pass over the card's games — or, from All Games, every real game.
    // Artwork files and columns are untouched.
    private fun updateMetadata(platformId: String?) {
        viewModelScope.launch {
            val taskId = "update_metadata_${platformId ?: "all"}"
            backgroundTasks.startStoppable(taskId, "Updating metadata: ${jobScopeName(platformId)}",
                TaskKind.METADATA, stopNote = "Games already updated keep their details.")
            val outcomes = mutableListOf<GameScrapeOutcome>()
            runCatching {
                val onProgress: (ScrapeProgress) -> Unit = { p ->
                    p.finished?.let(outcomes::add)
                    updateBackgroundTask(taskId, p.current, p.total)
                }
                if (platformId == null) artworkRepository.updateMetadataForAllGames(onProgress)
                else artworkRepository.updateMetadataForPlatform(platformId, onProgress)
            }.onSuccess { result ->
                backgroundTasks.complete(
                    taskId,
                    when {
                        result.total > 0 -> "${result.succeeded} of ${result.total} game(s) updated"
                        platformId == null -> "No games in the library"
                        else -> "No games on this card"
                    },
                    jobScopeAction(platformId),
                    detail = outcomes.takeIf { it.isNotEmpty() }?.let(::scrapeResults),
                    title = "Metadata update finished: ${jobScopeName(platformId)}",
                )
                loadItemsForCategory(currentCategory())
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) {
                    backgroundTasks.settleCancelled(
                        taskId, "Stopped after ${outcomes.size} game(s)", jobScopeAction(platformId),
                        detail = scrapeResults(outcomes), title = "Metadata update stopped: ${jobScopeName(platformId)}",
                    )
                    throw it
                }
                backgroundTasks.fail(
                    taskId, "Metadata update failed", jobScopeAction(platformId),
                    detail = NotificationDetail.notes(PfpErrorCode.AR_9001, summary = it.message,
                        diagnostic = it.stackTraceToString().take(4_000)),
                    title = "Metadata update failed: ${jobScopeName(platformId)}",
                )
            }
        }
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

    // Silent by decision: favouriting is an operational toggle, not an event worth sonifying.
    private fun toggleGameFavorite(gameId: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            gameRepository.setFavorite(gameId, isFavorite)
        }
    }

    // ── Background task management ────────────────────────────────────────────

    // Background work is reported through the Android notification bar. We keep a
    // tiny in-memory label map so progress/complete updates can re-title the same
    // notification without the caller having to re-supply the label each time.
    // Running work is NOT owned here any more. It lives in the shared BackgroundTaskCenter, which
    // every producer in the app reports to — the artwork and metadata workers, the media scanners,
    // the Steam import — not just the tasks the XMB starts. Before that, anything begun outside
    // this ViewModel reached the Android shade and the panel never heard about it.
    //
    // These four stay as private helpers because the call sites below read better for them, and
    // because they are the seam the plan describes; each is now one line.

    private fun observeNotifications() {
        viewModelScope.launch {
            notificationRepository.observeAll().collect { rows ->
                _uiState.update { it.copy(notifications = rows).withClampedNotificationCursor() }
            }
        }
        viewModelScope.launch {
            notificationRepository.observeUnreadCount().collect { count ->
                _uiState.update { it.copy(unreadNotifications = count) }
            }
        }
        viewModelScope.launch {
            backgroundTasks.running.collect { tasks ->
                _uiState.update { it.copy(runningTasks = tasks).withClampedNotificationCursor() }
            }
        }
    }

    private fun addBackgroundTask(task: BackgroundTaskInfo) = backgroundTasks.start(task)

    private fun updateBackgroundTask(id: String, current: Int, total: Int, detail: String? = null) =
        backgroundTasks.progress(id, current, total, detail)

    private fun completeBackgroundTask(
        id: String,
        message: String? = null,
        action: NotificationAction = NotificationAction.None,
    ) = backgroundTasks.complete(id, message, action)

    private fun failBackgroundTask(
        id: String,
        message: String,
        action: NotificationAction = NotificationAction.None,
    ) = backgroundTasks.fail(id, message, action)

    // ── Notification panel ──────────────────────────────────────────────

    /**
     * Keeps the panel consistent when the lists change under it (a clear, a new post, a task
     * settling): the cursor stays on a selectable row and a sheet or Stop confirm for something
     * that is gone closes. See [NotificationPanelState.reconcile].
     */
    private fun XMBUiState.withClampedNotificationCursor(): XMBUiState {
        val panel = notificationPanel ?: return this
        return copy(notificationPanel = panel.reconcile(runningTasks, notifications))
    }

    private fun notificationRows(state: XMBUiState = _uiState.value) =
        buildNotificationRows(state.runningTasks, state.notifications)

    /** The status-strip bell. The same toggle START drives, so the two ways in cannot diverge. */
    fun onToggleNotificationPanel() {
        markTouchInput()
        if (_uiState.value.notificationPanel != null) closeNotificationPanel() else openNotificationPanel()
    }

    private fun openNotificationPanel() {
        menuSound.play(MenuSound.SELECT)
        val cursor = notificationRows().firstSelectableIndex()
        _uiState.update { it.copy(notificationPanel = NotificationPanelState(cursor = cursor)) }
    }

    fun closeNotificationPanel() {
        menuSound.play(MenuSound.BACK)
        _uiState.update { it.copy(notificationPanel = null) }
    }

    private fun moveNotificationCursor(delta: Int) {
        val panel = _uiState.value.notificationPanel ?: return
        val next = notificationRows().moveCursor(panel.cursor, delta)
        if (next == panel.cursor) {
            gamepadInputHandler.cancelRepeat()
            return
        }
        menuSound.play(MenuSound.SCROLL)
        _uiState.update { it.copy(notificationPanel = panel.copy(cursor = next)) }
    }

    fun onNotificationRowTapped(index: Int) {
        markTouchInput()
        val panel = _uiState.value.notificationPanel ?: return
        _uiState.update { it.copy(notificationPanel = panel.copy(cursor = index)) }
        activateNotificationRow()
    }

    /**
     * Confirm on the panel. Every row Confirm lands on is marked read (never deleted): a Notes or
     * Results row opens its sheet, a Simple row goes where it points, and a stoppable running row
     * asks before stopping. See [panelConfirm].
     */
    private fun activateNotificationRow() {
        val state = _uiState.value
        val panel = state.notificationPanel ?: return
        when (val confirm = panelConfirm(notificationRows(state), panel.cursor)) {
            is PanelConfirm.OpenSheet -> {
                menuSound.play(MenuSound.SELECT)
                viewModelScope.launch { notificationRepository.markRead(confirm.notificationId) }
                _uiState.update {
                    it.copy(notificationPanel = it.notificationPanel?.copy(sheetNotificationId = confirm.notificationId))
                }
            }
            is PanelConfirm.RunAction -> {
                menuSound.play(MenuSound.SELECT)
                viewModelScope.launch { notificationRepository.markRead(confirm.notification.id) }
                runNotificationAction(confirm.notification.action)
            }
            is PanelConfirm.AskStop -> {
                menuSound.play(MenuSound.SELECT)
                _uiState.update {
                    it.copy(notificationPanel = it.notificationPanel?.copy(stopConfirm = confirm.state))
                }
            }
            PanelConfirm.Nothing -> Unit
        }
    }

    /**
     * Where an action goes. Every real destination closes the panel first; a row with nowhere to
     * go ([NotificationAction.None], or the not-yet-implemented [NotificationAction.OpenUrl])
     * leaves the panel open — it was marked read, and anything it had to say is in its sheet.
     */
    private fun runNotificationAction(action: NotificationAction) {
        when (action) {
            NotificationAction.None, is NotificationAction.OpenUrl -> return
            else -> _uiState.update { it.copy(notificationPanel = null) }
        }
        when (action) {
            is NotificationAction.OpenCategory -> {
                val index = _uiState.value.categories.indexOfFirst { it.id == action.categoryId }
                if (index >= 0) onCategorySelected(index)
            }
            is NotificationAction.OpenMemoryCard -> openPlatformFolder(action.platformId)
            is NotificationAction.OpenGame ->
                _uiState.update { it.copy(activeGameId = action.gameId, activeGameAutoLaunch = false) }
            is NotificationAction.OpenSettingsScreen ->
                if (isSettingsRouteEnabled(action.routeId)) _uiState.update { it.copy(activeSettingsScreen = action.routeId) }
            is NotificationAction.ReviewShortcut -> openShortcutReview(action.requestId)
            NotificationAction.None, is NotificationAction.OpenUrl -> Unit
        }
    }

    // ── Shortcut requests (the Add / Ignore that used to live in the Android shade) ──

    /** Asks about waiting requests one at a time, oldest first, whenever the XMB is free. */
    private fun observeShortcutRequests() {
        viewModelScope.launch {
            shortcutRequests.requests.collect { maybeShowShortcutReview(it) }
        }
    }

    private suspend fun maybeShowShortcutReview(
        waiting: List<com.playfieldportal.core.data.repository.PendingShortcutRequest>? = null,
    ) {
        val state = _uiState.value
        if (state.shortcutReview != null || state.hasBlockingOverlay) return
        val next = (waiting ?: runCatching { shortcutRequests.requests.first() }.getOrDefault(emptyList()))
            .firstOrNull() ?: return
        _uiState.update { if (it.shortcutReview == null && !it.hasBlockingOverlay) it.copy(shortcutReview = next) else it }
    }

    /** The request's tray row was opened: ask about it now. */
    private fun openShortcutReview(requestId: String) {
        viewModelScope.launch {
            val request = shortcutRequests.get(requestId)
            _uiState.update {
                if (request != null) {
                    it.copy(shortcutReview = request)
                } else {
                    it.copy(infoDialog = InfoDialogState("Shortcut request", "This request was already handled."))
                }
            }
        }
    }

    fun confirmShortcutReview(requestId: String) = resolveShortcutReview { shortcutRequests.add(requestId) }

    fun ignoreShortcutReview(requestId: String) = resolveShortcutReview { shortcutRequests.ignore(requestId) }

    private fun resolveShortcutReview(resolve: suspend () -> Unit) {
        _uiState.update { it.copy(shortcutReview = null) }
        viewModelScope.launch {
            runCatching { resolve() }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Timber.e(it, "Resolving a shortcut request failed")
            }
            maybeShowShortcutReview()
        }
    }

    // ── Notification sheets and the Stop confirm (called from the shell's modal host) ──

    /** BACK on a Notes or Results sheet: back to the panel, cursor where it was. */
    fun closeNotificationSheet() {
        _uiState.update { it.copy(notificationPanel = it.notificationPanel?.copy(sheetNotificationId = null)) }
    }

    /** ✕ on a sheet with no item action: the row's own action. */
    fun onNotificationSheetAction(notificationId: Long) {
        val row = _uiState.value.notifications.firstOrNull { it.id == notificationId } ?: return
        runNotificationAction(row.action)
    }

    /** ✕ on a Results item that carries its own destination (a Memory Card, a game). */
    fun onNotificationSheetItemAction(action: DetailAction) {
        runNotificationAction(action.toNotificationAction())
    }

    /** △ Copy Details / Copy List. The sheet's hint reads "Copied" for a moment. */
    fun copyNotificationText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("PFP notification", text))
    }

    /** Stop on the Stop confirm: the task freezes as "Stopping…" until its producer settles. */
    fun confirmStopTask(taskId: String) {
        backgroundTasks.requestStop(taskId)
        cancelStopTask()
    }

    /** Keep Running (or BACK) on the Stop confirm. */
    fun cancelStopTask() {
        _uiState.update { it.copy(notificationPanel = it.notificationPanel?.copy(stopConfirm = null)) }
    }

    fun onNotificationOptionsTapped() {
        markTouchInput()
        openNotificationListMenu()
    }

    /**
     * Options for the list, from [notificationListMenuItems]: only rows that can act, and no menu
     * at all when the history is empty.
     */
    private fun openNotificationListMenu() {
        if (_uiState.value.notificationPanel == null) return
        val items = notificationListMenuItems(_uiState.value.notifications)
        if (items.isEmpty()) return
        _uiState.update {
            it.copy(
                activeContextMenu = XMBContextMenu(
                    title = "Notifications",
                    items = items,
                    notificationListMenu = true,
                ),
            )
        }
    }

    /** Routes a pick from the notification menu. Returns true when the menu was that one. */
    private fun handleNotificationMenuItem(menu: XMBContextMenu, itemId: String): Boolean {
        if (!menu.notificationListMenu) return false
        closeContextMenu()
        // Clear All asks first; the clear itself is in confirmPendingConfirm.
        confirmFor(menu, itemId)?.let { confirm ->
            _uiState.update { it.copy(pendingConfirm = confirm) }
            return true
        }
        viewModelScope.launch {
            when (itemId) {
                NotificationMenuIds.MARK_ALL_READ -> notificationRepository.markAllRead()
                NotificationMenuIds.CLEAR_READ -> notificationRepository.clearRead()
            }
        }
        return true
    }

    // ── Category / platform selection ─────────────────────────────────────────

    fun onCategorySelected(index: Int) {
        if (index != _uiState.value.selectedCategoryIndex) menuSound.play(MenuSound.SYSTEM_BROWSE)
        // Leaving the Social section (any path) must disarm a pending PTT-button capture, or a later
        // button press elsewhere would be bound as the PTT button.
        if (_uiState.value.capturingPttKey) cancelPttCapture()
        val category = _uiState.value.categories.getOrNull(index)
        exitMarkMode()
        // activeAppDrawerFilter is cleared as an invariant: landing on a category always shows the
        // plain XMB (the drawer can't normally be open here, but this keeps the contextual button
        // state correct no matter which path selected the category).
        // Every visit lands on the section's default row, PSP-style: the cursor is placed when the
        // section's rows arrive (landingPending — see landedFrom), never carried over or scrolled.
        _uiState.update { it.copy(selectedCategoryIndex = index, selectedItemIndex = 0, landingPending = true, selectedPlatformId = null, selectedCollectionId = null, musicNav = MusicNav.Root, videoNav = VideoNav.Root, photoNav = PhotoNav.Root, socialNav = SocialNav.Root, achievementsNav = AchievementsNav.Root, settingsSectionNav = null, activeAppDrawerFilter = null) }
        // Moving along the crossbar is the coarsest "different list" there is, and this path does
        // not go through navigateRememberingCursor, so it drops the Games query itself.
        clearGameQuery()
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
     *  of truth — the same one that drives the XMB's contextual App Drawer button. Also refreshes
     *  [lastInteractionMs] so the idle context-menu hint resets. */
    fun markTouchInput() {
        lastInteractionMs = SystemClock.elapsedRealtime()
        mirrorInputSource(virtualKeyboard, touch = true)
        // One write for both flags: the hints must clear on the SAME frame as the input (see
        // noteInteraction), and a second update() here would cost an extra recomposition.
        _uiState.update {
            if (it.lastInputWasTouch &&
                !it.showContextMenuHint &&
                !it.showAppDrawerHint &&
                !it.showSettingsHint &&
                !it.showNotificationHint &&
                !it.showMediaHint
            ) it
            else it.copy(
                lastInputWasTouch = true,
                showContextMenuHint = false,
                showAppDrawerHint = false,
                showSettingsHint = false,
                showNotificationHint = false,
                showMediaHint = false,
            )
        }
    }

    private fun markControllerInput() {
        lastInteractionMs = SystemClock.elapsedRealtime()
        mirrorInputSource(virtualKeyboard, touch = false)
        _uiState.update {
            if (!it.lastInputWasTouch &&
                !it.showContextMenuHint &&
                !it.showAppDrawerHint &&
                !it.showSettingsHint &&
                !it.showNotificationHint &&
                !it.showMediaHint
            ) it
            else it.copy(
                lastInputWasTouch = false,
                showContextMenuHint = false,
                showAppDrawerHint = false,
                showSettingsHint = false,
                showNotificationHint = false,
                showMediaHint = false,
            )
        }
    }

    /**
     * Unwinds exactly ONE level of home-screen drill-in, and reports whether it did. False means
     * the cursor was already at a category root — there was nothing to back out of.
     *
     * The single implementation of the ladder. It was written out twice (gamepad BACK and the
     * touch [onHomeBack]) before D-pad LEFT became a third caller, and each caller wants a
     * different thing at the root: BACK opens the App Drawer, LEFT steps the category bar. So the
     * ladder returns the fact and lets the caller decide — it plays no sound, marks no input
     * source, and has no fallback of its own.
     */
    private fun backOutOfDrill(s: XMBUiState): Boolean {
        when (s.drillOutStep) {
            DrillOutStep.SETTINGS_SECTION -> closeSettingsSection()
            DrillOutStep.MUSIC -> closeMusicView()
            // Two-level video paths back out through their own list first.
            DrillOutStep.VIDEO_LIBRARY -> openVideoView(VideoNav.Libraries)
            DrillOutStep.VIDEO_PLAYLIST -> openVideoView(VideoNav.Playlists)
            DrillOutStep.VIDEO_COLLECTION_CHILD -> openVideoView(VideoNav.Collections)
            DrillOutStep.VIDEO -> closeVideoView()
            // An album drill-in backs out via the Albums list first.
            DrillOutStep.PHOTO_LIBRARY -> openPhotoView(PhotoNav.Albums)
            DrillOutStep.PHOTO -> closePhotoView()
            DrillOutStep.SOCIAL -> socialBack()
            DrillOutStep.ACHIEVEMENTS -> closeAchievementsView()
            DrillOutStep.PLATFORM_FOLDER -> closePlatformFolder()
            null -> return false
        }
        return true
    }

    /** Touch: the left-edge-swipe Back — exit an open folder, or open the app drawer at the root
     *  (mirrors the gamepad BACK behaviour on the home screen). No-op while an overlay is up. */
    fun onHomeBack() {
        markTouchInput()
        val s = _uiState.value
        if (s.hasBlockingOverlay) return
        menuSound.play(MenuSound.BACK)
        // Multi-select ends first, as it does for the controller's BACK.
        if (s.markMode) { exitMarkMode(); return }
        if (!backOutOfDrill(s)) onOpenAppDrawer()
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
        XMBItem(id = "social_voice", title = "Voice", subtitle = "Talk in a shared room", type = XMBItemType.SOCIAL_VOICE),
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
                // Friends in a PFP lobby get a "friendjoin_" id so selecting them asks to join.
                id = if (f.inLobby) "friendjoin_${f.id}" else "friend_${f.id}",
                title = f.label,
                // Subtext under the name: in-lobby → Ask to Join prompt; else what they're playing,
                // else the presence word — prefixed by the colored presence dot in the row renderer.
                subtitle = when {
                    f.inLobby -> "In a voice lobby · Ask to Join"
                    f.activity != null -> "Playing ${f.activity}"
                    else -> socialPresenceLabel(f.presence).takeIf { it.isNotBlank() }
                },
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

    // ── Voice room (M4) ───────────────────────────────────────────────────────────
    // v1 = a shared room by code (defaults to the "party" room). Polls the SDK call snapshot while
    // the Voice screen is open; the call itself keeps running if the user browses elsewhere.
    private var voicePollJob: kotlinx.coroutines.Job? = null
    private var voiceSelfId: String? = null
    private var voiceWasInRoom: Boolean = false      // to apply audio settings on the entry transition
    private var voiceInvitedIds: Set<String> = emptySet()  // friends invited this picker session

    // L3 (Voice): the lobby UI. Idle → Create Lobby / Invites / Settings; in a lobby → participants,
    // Invite Friends, Invites, Mute, Settings, Leave.
    private suspend fun socialVoiceItems(): List<XMBItem> {
        if (!discordAuthRepository.isOnline()) {
            return listOf(XMBItem(id = "voice_offline", title = "You're offline", subtitle = "Reconnect to use voice", type = XMBItemType.EMPTY))
        }
        val vs = _uiState.value.voiceState
        val pending = discordVoice.invites().size
        val invitesRow = XMBItem(
            id = "voice_invites",
            title = "Invites",
            subtitle = if (pending == 0) "No pending invites" else "$pending waiting",
            type = XMBItemType.SOCIAL_VOICE_INVITES,
        )
        if (!vs.inRoom) {
            return listOf(
                XMBItem(id = "voice_create", title = "Create Lobby", subtitle = "Start a private room and invite friends", type = XMBItemType.SOCIAL_VOICE_CREATE),
                invitesRow,
                voiceSettingsEntry(),
            )
        }
        val participants = vs.participants.map { p ->
            val you = p.id == voiceSelfId
            XMBItem(
                id = "voice_p_${p.id}",
                title = p.displayName.ifBlank { "Someone" } + if (you) " (You)" else "",
                subtitle = when { p.muted -> "Muted"; p.speaking -> "Speaking"; else -> null },
                socialStatusArgb = if (p.speaking) 0xFF43B581 else null,   // green while talking
                type = XMBItemType.SOCIAL_FRIEND,   // reuses the avatar + status-dot row renderer
            )
        }
        val body = participants.ifEmpty {
            val note = if (vs.connecting) "Connecting…" else "Waiting for others to join"
            listOf(XMBItem(id = "voice_status", title = note, type = XMBItemType.EMPTY))
        }
        val controls = listOf(
            XMBItem(id = "voice_invite", title = "Invite Friends", subtitle = "Bring friends into this lobby", type = XMBItemType.SOCIAL_VOICE_INVITE),
            invitesRow,
            XMBItem(
                id = "voice_mute",
                title = if (vs.selfMuted) "Unmute" else "Mute",
                subtitle = if (vs.selfMuted) "Your mic is off" else "Your mic is on",
                type = XMBItemType.SOCIAL_VOICE_MUTE,
            ),
            voiceSettingsEntry(),
            XMBItem(id = "voice_leave", title = "Leave Voice", subtitle = "Disconnect from the room", type = XMBItemType.SOCIAL_VOICE_LEAVE),
        )
        return body + controls
    }

    // L4 (Voice ▸ Invites): pending invites (join a friend's lobby) + join requests (approve someone).
    private suspend fun socialVoiceInvitesItems(): List<XMBItem> {
        val invites = discordVoice.invites()
        if (invites.isEmpty()) {
            return listOf(XMBItem(id = "voice_noinvites", title = "No invites", subtitle = "Invites and join requests appear here", type = XMBItemType.EMPTY))
        }
        return invites.map { inv ->
            XMBItem(
                id = "vinv_${inv.index}_${if (inv.isJoinRequest) 1 else 0}",
                title = if (inv.isJoinRequest) "${inv.senderName} wants to join" else "Join ${inv.senderName}'s lobby",
                subtitle = if (inv.isJoinRequest) "Select to approve" else "Select to join",
                type = XMBItemType.SOCIAL_VOICE_INVITE_ROW,
            )
        }
    }

    // L4 (Voice ▸ Invite Friends): online friends to invite to your lobby.
    private suspend fun socialVoiceInviteFriendsItems(): List<XMBItem> {
        if (!discordAuthRepository.isOnline()) {
            return listOf(XMBItem(id = "voice_pick_offline", title = "You're offline", subtitle = "Reconnect to invite friends", type = XMBItemType.EMPTY))
        }
        val online = discordAuthRepository.friends().filter { it.presence.isOnline }
            .sortedBy { it.label.lowercase() }
        if (online.isEmpty()) {
            return listOf(XMBItem(id = "voice_pick_none", title = "No friends online", type = XMBItemType.EMPTY))
        }
        return online.map { f ->
            val invited = f.id in voiceInvitedIds
            XMBItem(
                id = "vpick_${f.id}",
                title = f.label,
                subtitle = when { invited -> "Invited ✓"; f.inLobby -> "Already in a lobby"; else -> "Select to invite" },
                coverUri = f.avatarUrl.takeIf { it.isNotBlank() },
                type = XMBItemType.SOCIAL_VOICE_FRIEND_PICK,
            )
        }
    }

    private fun voiceSettingsEntry() = XMBItem(
        id = "voice_settings",
        title = "Voice Settings",
        subtitle = "Mic sensitivity, noise filter, volume",
        type = XMBItemType.SOCIAL_VOICE_SETTINGS,
    )

    // L4 (Voice Settings): every audio knob the SDK exposes — booleans toggle, multi-value rows cycle.
    private suspend fun socialVoiceSettingsItems(): List<XMBItem> {
        val s = discordVoice.settings()
        fun toggle(id: String, title: String, on: Boolean, onText: String) = XMBItem(
            id = id, title = title,
            subtitle = if (on) "On · $onText" else "Off",
            type = XMBItemType.SOCIAL_VOICE_TOGGLE,
        )
        fun cycle(id: String, title: String, value: String) = XMBItem(
            id = id, title = title, subtitle = value, type = XMBItemType.SOCIAL_VOICE_CYCLE,
        )
        return buildList {
            add(cycle("vs_sensitivity", "Mic Sensitivity", "${s.micSensitivity.label} · lower filters clicks"))
            add(toggle("vs_noise", "Noise Cancellation", s.noiseCancellation, "Krisp removes background + button clicks"))
            add(toggle("vs_echo", "Echo Cancellation", s.echoCancellation, "stops speaker feedback"))
            add(toggle("vs_agc", "Auto Gain Control", s.automaticGainControl, "levels your mic volume"))
            add(cycle("vs_input", "Mic Volume", "${s.inputVolumePercent}%"))
            add(cycle("vs_balance", "Audio Balance", audioBalanceLabel(s.audioBalance)))
            add(toggle("vs_ptt", "Push-to-Talk", s.pushToTalk, "hold to talk — mic stays closed otherwise"))
            // Only meaningful with PTT on: the floating talk button that works over a running game.
            if (s.pushToTalk) {
                add(XMBItem(
                    id = "vs_ptt_overlay",
                    title = "Talk Button Overlay",
                    subtitle = when {
                        !pttOverlay.canDraw() -> "Off · needs \"Draw over other apps\""
                        s.pttOverlay -> "On · floating hold-to-talk button"
                        else -> "Off · hidden (use a controller button)"
                    },
                    type = XMBItemType.SOCIAL_VOICE_TOGGLE,
                ))
                // Controller hold-to-talk while PFP is the foreground app (a game in front routes the
                // button to the game, so the overlay covers that case).
                add(XMBItem(
                    id = "vs_ptt_button",
                    title = "PTT Button",
                    subtitle = when {
                        _uiState.value.capturingPttKey -> "Press a controller button…"
                        s.pttKeyCode != null -> "${pttButtonLabel(s.pttKeyCode)}  ·  hold to talk while in Playfield Portal"
                        else -> "Not set · select to map a controller button"
                    },
                    // beginPttCapture cancels on the east face button or hardware Back, matched by
                    // keycode, so the prompt names that position rather than the BACK action — the
                    // two disagree the moment Confirm/Back is reversed.
                    subtitleHintIcon =
                        if (_uiState.value.capturingPttKey) ControllerIcon.FACE_EAST else null,
                    subtitleHintLabel = if (_uiState.value.capturingPttKey) "cancel" else null,
                    type = XMBItemType.SOCIAL_VOICE_CYCLE,
                ))
            }
        }
    }

    // Friendly name for a mapped gamepad keycode (falls back to a trimmed KEYCODE_ name).
    private fun pttButtonLabel(code: Int?): String = when (code) {
        null -> "Not set"
        android.view.KeyEvent.KEYCODE_BUTTON_A -> "A"
        android.view.KeyEvent.KEYCODE_BUTTON_B -> "B"
        android.view.KeyEvent.KEYCODE_BUTTON_X -> "X"
        android.view.KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        android.view.KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        android.view.KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        android.view.KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        android.view.KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3 (left stick click)"
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3 (right stick click)"
        android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        android.view.KeyEvent.KEYCODE_BUTTON_START -> "Start"
        else -> android.view.KeyEvent.keyCodeToString(code)
            .removePrefix("KEYCODE_BUTTON_").removePrefix("KEYCODE_")
    }

    // A controller-friendly "slider": Game ◀ ──●── ▶ Voice with the marker at the current mix.
    private fun audioBalanceLabel(balance: Int): String {
        val slots = 9
        val pos = (balance / 100f * (slots - 1)).roundToInt().coerceIn(0, slots - 1)
        val bar = "─".repeat(pos) + "●" + "─".repeat(slots - 1 - pos)
        return "Game ◀$bar▶ Voice"
    }

    private fun toggleVoiceSetting(id: String) {
        viewModelScope.launch {
            val s = discordVoice.settings()
            when (id) {
                "vs_noise" -> discordVoice.setNoiseCancellation(!s.noiseCancellation)
                "vs_echo"  -> discordVoice.setEchoCancellation(!s.echoCancellation)
                "vs_agc"   -> discordVoice.setAutomaticGainControl(!s.automaticGainControl)
                "vs_ptt"   -> { discordVoice.setPushToTalk(!s.pushToTalk); syncPttControls() }
                "vs_ptt_overlay" -> {
                    if (!s.pttOverlay && !pttOverlay.canDraw()) {
                        // Enabling it but no permission yet → send the user to grant it.
                        _uiState.update { it.copy(requestOverlayPermission = true) }
                    } else {
                        discordVoice.setPttOverlay(!s.pttOverlay)
                        syncPttControls()
                    }
                }
            }
            if (_uiState.value.socialNav == SocialNav.VoiceSettings) loadItemsForCategory(currentCategory())
        }
    }

    // Keep both PTT controls in sync with the call + settings: the floating overlay (works over a
    // running game) and the controller hold-to-talk button (PFP-foreground only).
    private fun syncPttControls() {
        viewModelScope.launch {
            val s = discordVoice.settings()
            val inCall = _uiState.value.voiceState.inRoom
            val wantOverlay = inCall && s.pushToTalk && s.pttOverlay && pttOverlay.canDraw()
            if (wantOverlay) pttOverlay.show() else pttOverlay.hide()

            if (inCall && s.pushToTalk && s.pttKeyCode != null) {
                gamepadInputHandler.pttKeyCode = s.pttKeyCode
                gamepadInputHandler.onPttHold = { active ->
                    viewModelScope.launch { discordVoice.setPttActive(active) }
                }
            } else {
                gamepadInputHandler.pttKeyCode = null
                gamepadInputHandler.onPttHold = null
            }
        }
    }

    // Arm the shared remap capture: the next controller button becomes the PTT button (B / Back
    // cancels). Reuses RemapCoordinator, so it can't collide with normal navigation while armed.
    private fun beginPttCapture() {
        remapCoordinator.captureNextKey = { keyCode ->
            val cancel = keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == android.view.KeyEvent.KEYCODE_BACK
            viewModelScope.launch {
                if (!cancel) discordVoice.setPttKeyCode(keyCode)
                _uiState.update { it.copy(capturingPttKey = false) }
                syncPttControls()
                if (_uiState.value.socialNav == SocialNav.VoiceSettings) loadItemsForCategory(currentCategory())
            }
        }
        _uiState.update { it.copy(capturingPttKey = true) }
        viewModelScope.launch {
            if (_uiState.value.socialNav == SocialNav.VoiceSettings) loadItemsForCategory(currentCategory())
        }
    }

    private fun cancelPttCapture() {
        remapCoordinator.captureNextKey = null
        _uiState.update { it.copy(capturingPttKey = false) }
    }

    /** Shell has launched the overlay-permission settings — clear the one-shot flag. */
    fun onOverlayPermissionRequested() = _uiState.update { it.copy(requestOverlayPermission = false) }

    private fun cycleVoiceSetting(id: String) {
        viewModelScope.launch {
            when (id) {
                "vs_sensitivity" -> discordVoice.setMicSensitivity(discordVoice.micSensitivity().next())
                "vs_input"       -> discordVoice.cycleInputVolume()
                "vs_balance"     -> discordVoice.cycleAudioBalance()
            }
            if (_uiState.value.socialNav == SocialNav.VoiceSettings) loadItemsForCategory(currentCategory())
        }
    }

    // Hub "Voice" row → drill in; resume polling if a call is already live.
    private fun onVoiceHubSelected() {
        menuSound.play(MenuSound.SELECT)
        openSocialView(SocialNav.Voice)
        if (_uiState.value.voiceState.inRoom) startVoicePolling()
    }

    // Entering a lobby opens the mic, so create/accept run behind the RECORD_AUDIO grant. We stash the
    // pending action and let the shell prompt, then run it on grant.
    private var pendingMicAction: (() -> Unit)? = null

    private fun withMic(action: () -> Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pendingMicAction = action
            _uiState.update { it.copy(requestMicPermission = true) }
        }
    }

    /** Shell has launched the permission dialog — clear the one-shot request flag. */
    fun onMicPermissionRequested() = _uiState.update { it.copy(requestMicPermission = false) }

    /** Shell delivered the RECORD_AUDIO result. Run the pending action only on grant. */
    fun onMicPermissionResult(granted: Boolean) {
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke() else Timber.i("Voice: RECORD_AUDIO denied")
    }

    private fun requestCreateLobby() {
        menuSound.play(MenuSound.SELECT)
        withMic { createLobbyRoom() }
    }

    private fun createLobbyRoom() {
        viewModelScope.launch {
            voiceSelfId = discordAuthRepository.currentUser()?.id
            if (discordVoice.createLobby()) enterLobbyUi()
            else {
                Timber.w("Voice: create lobby failed (offline or SDK rejected)")
                if (_uiState.value.socialNav == SocialNav.Voice) loadItemsForCategory(currentCategory())
            }
        }
    }

    // After entering a lobby by any path (create/accept/Discord-join): show the room + start polling.
    private suspend fun enterLobbyUi() {
        if (_uiState.value.socialNav != SocialNav.Voice) openSocialView(SocialNav.Voice)
        refreshVoiceState()
        startVoicePolling()
    }

    private fun openVoiceInvites() {
        menuSound.play(MenuSound.SELECT)
        openSocialView(SocialNav.VoiceInvites)
        startVoicePolling()   // keep the list live as invites/requests arrive
    }

    private fun openVoiceInviteFriends() {
        menuSound.play(MenuSound.SELECT)
        voiceInvitedIds = emptySet()
        openSocialView(SocialNav.VoiceInviteFriends)
    }

    // A pending-invite / join-request row: accept (join their lobby) or approve (let them into ours).
    private fun onVoiceInviteRowSelected(rowId: String) {
        val parts = rowId.removePrefix("vinv_").split("_")
        val index = parts.getOrNull(0)?.toIntOrNull() ?: return
        val isRequest = parts.getOrNull(1) == "1"
        menuSound.play(MenuSound.SELECT)
        if (isRequest) {
            viewModelScope.launch {
                discordVoice.approveJoinRequest(index)
                loadItemsForCategory(currentCategory())
            }
        } else {
            withMic {
                viewModelScope.launch {
                    voiceSelfId = discordAuthRepository.currentUser()?.id
                    discordVoice.acceptInvite(index)   // native enters the lobby; the poll reflects it
                    enterLobbyUi()
                }
            }
        }
    }

    private fun onVoiceFriendPick(rowId: String) {
        val userId = rowId.removePrefix("vpick_")
        menuSound.play(MenuSound.SELECT)
        viewModelScope.launch {
            discordVoice.inviteFriend(userId)
            voiceInvitedIds = voiceInvitedIds + userId
            if (_uiState.value.socialNav == SocialNav.VoiceInviteFriends) loadItemsForCategory(currentCategory())
        }
    }

    private fun askToJoinFriend(friendId: String) {
        menuSound.play(MenuSound.SELECT)
        viewModelScope.launch { discordVoice.askToJoin(friendId) }
    }

    private fun toggleSelfMute() {
        viewModelScope.launch {
            discordVoice.setMuted(!_uiState.value.voiceState.selfMuted)
            refreshVoiceState()
        }
    }

    private fun leaveVoiceRoom() {
        stopVoicePolling()
        pttOverlay.hide()
        // Stop intercepting the controller PTT button once the call ends.
        gamepadInputHandler.pttKeyCode = null
        gamepadInputHandler.onPttHold = null
        voiceWasInRoom = false
        viewModelScope.launch {
            discordVoice.leave()
            _uiState.update { it.copy(voiceState = com.playfieldportal.core.domain.discord.DiscordVoiceState.Idle) }
            if (_uiState.value.socialNav == SocialNav.Voice) loadItemsForCategory(currentCategory())
        }
    }

    private suspend fun refreshVoiceState() {
        // Pick up a "Join" the user tapped in the Discord app — but only when we're not already in a
        // lobby, so accepting an invite can't re-enter the same call (which the SDK aborts on).
        if (!voiceWasInRoom && discordVoice.checkPendingJoin()) enterLobbyUi()
        val vs = discordVoice.state()
        // Apply the saved audio settings the first tick we're in a lobby — covers invite-accept and
        // Discord-app joins, which enter the lobby natively without going through create/join.
        if (vs.inRoom && !voiceWasInRoom) {
            discordVoice.applyAudioSettings()
            syncPttControls()   // raise the floating talk button if PTT + overlay are on
        }
        voiceWasInRoom = vs.inRoom
        _uiState.update { it.copy(voiceState = vs) }
        val nav = _uiState.value.socialNav
        if (nav == SocialNav.Voice || nav == SocialNav.VoiceInvites) {
            loadItemsForCategory(currentCategory())
            // A participant/invite leaving shrinks the list; keep the cursor in range so a poll can't
            // strand the highlight past the end.
            _uiState.update { st ->
                val max = (st.currentItems.size - 1).coerceAtLeast(0)
                if (st.selectedItemIndex > max) st.copy(selectedItemIndex = max) else st
            }
        }
    }

    // Poll the call + invites while the Voice or Invites screen is open. Self-terminates when the user
    // navigates away or the call ends, so the call keeps running in the background without a live poll.
    private fun startVoicePolling() {
        if (voicePollJob?.isActive == true) return
        voicePollJob = viewModelScope.launch {
            while (isActive) {
                val nav = _uiState.value.socialNav
                if (nav != SocialNav.Voice && nav != SocialNav.VoiceInvites) break
                refreshVoiceState()
                delay(1500)
            }
        }
    }

    private fun stopVoicePolling() {
        voicePollJob?.cancel()
        voicePollJob = null
    }

    private fun openSocialView(nav: SocialNav) {
        if (nav != SocialNav.Voice && nav != SocialNav.VoiceInvites) stopVoicePolling()
        // Leaving Voice Settings mid-capture cancels the PTT mapping (so a stray press can't bind).
        if (nav != SocialNav.VoiceSettings && _uiState.value.capturingPttKey) cancelPttCapture()
        navigateRememberingCursor { it.copy(socialNav = nav) }
    }

    // One level up: Friends / Discord Settings → Account → Root.
    private fun socialBack() {
        val parent = when (_uiState.value.socialNav) {
            SocialNav.Friends          -> SocialNav.Account
            SocialNav.Voice            -> SocialNav.Account
            SocialNav.VoiceSettings    -> SocialNav.Voice
            SocialNav.VoiceInvites     -> SocialNav.Voice
            SocialNav.VoiceInviteFriends -> SocialNav.Voice
            SocialNav.ActivitySettings -> SocialNav.Account
            SocialNav.DiscordSettings  -> SocialNav.Account
            SocialNav.Account          -> SocialNav.Root
            SocialNav.Root             -> SocialNav.Root
        }
        openSocialView(parent)
        // Returning to the Voice room screen from its settings resumes the participant poll.
        if (parent == SocialNav.Voice && _uiState.value.voiceState.inRoom) startVoicePolling()
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
    private fun openSocialAccountContextMenu(rowTitle: String?) {
        _uiState.update {
            it.copy(
                activeContextMenu = XMBContextMenu(
                    title = socialAccountMenuTitle(rowTitle),
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
            XMBItemType.SOCIAL_VOICE -> onVoiceHubSelected()
            XMBItemType.SOCIAL_VOICE_CREATE -> requestCreateLobby()
            XMBItemType.SOCIAL_VOICE_INVITE -> openVoiceInviteFriends()
            XMBItemType.SOCIAL_VOICE_INVITES -> openVoiceInvites()
            XMBItemType.SOCIAL_VOICE_INVITE_ROW -> onVoiceInviteRowSelected(item.id)
            XMBItemType.SOCIAL_VOICE_FRIEND_PICK -> onVoiceFriendPick(item.id)
            XMBItemType.SOCIAL_VOICE_MUTE -> { menuSound.play(MenuSound.SELECT); toggleSelfMute() }
            XMBItemType.SOCIAL_VOICE_SETTINGS -> { menuSound.play(MenuSound.SELECT); openSocialView(SocialNav.VoiceSettings) }
            XMBItemType.SOCIAL_VOICE_TOGGLE -> { menuSound.play(MenuSound.SELECT); toggleVoiceSetting(item.id) }
            XMBItemType.SOCIAL_VOICE_CYCLE -> {
                menuSound.play(MenuSound.SELECT)
                if (item.id == "vs_ptt_button") beginPttCapture() else cycleVoiceSetting(item.id)
            }
            XMBItemType.SOCIAL_VOICE_LEAVE -> { menuSound.play(MenuSound.SELECT); leaveVoiceRoom() }
            XMBItemType.SOCIAL_FRIEND -> {
                menuSound.play(MenuSound.SELECT)
                // A friend shown as "in a lobby" → tapping asks to join them; participants are no-op.
                if (item.id.startsWith("friendjoin_")) askToJoinFriend(item.id.removePrefix("friendjoin_"))
            }
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

        // Multi-select: activating a row marks it instead of opening it (a second tap on the
        // focused row arrives here too).
        if (_uiState.value.markMode) {
            toggleMark(item)
            return
        }

        // Music rows are handled together (static items, the All Music card, playlists, tracks,
        // and the various add/setup rows), each owning its sound and returning early.
        if (category?.id == BuiltInCategory.MUSIC && item != null && handleMusicSelection(item)) return

        // Video rows (static items, library cards, video files, app rows) are handled together.
        if (category?.id == BuiltInCategory.VIDEO && item != null && handleVideoSelection(item)) return

        // Photo rows (the All Photos card, Camera, Add Photo Library, Album cards, photo files).
        if (category?.id == BuiltInCategory.PHOTO && item != null && handlePhotoSelection(item)) return

        // Social rows (Sign in / connected account / Sign out).
        if (category?.id == BuiltInCategory.SOCIAL && item != null && handleSocialSelection(item)) return

        // Shiba Coins rows (summary → settings, lens rows → drill). Game/coin rows fall through to
        // the shared game handler below, which opens Game Detail.
        if (category?.id == BuiltInCategory.ACHIEVEMENTS && item != null && handleAchievementsSelection(item)) return

        // Sound: launch for items that boot something immediately; select for opening a folder,
        // detail, picker, or settings; silent for non-selectable placeholder rows.
        val silentRow = item?.id in setOf(NO_GAMES_ITEM_ID, EMPTY_COLLECTION_ITEM_ID, EMPTY_CATEGORY_ITEM_ID)
        // A real game opens the Game Detail page — which only boots the game immediately when
        // direct launch is on; without it, confirm just opens the detail "menu" (select).
        val opensGameDetail = item?.gameId != null && item.isRealGame
        val launches = if (opensGameDetail) {
            _uiState.value.directLaunch
        } else {
            item?.launchIntentUri != null ||
                (item?.shortcutId != null && item.packageName != null) ||
                item?.packageName != null
        }
        // Opening something is silent, whatever it is. A game booting immediately is scored by
        // the GameBoot presentation (or by nothing, when GameBoot is off — off means off); an app
        // opening has no cue at all, because the app taking the screen IS the feedback. There is
        // no longer a launch sound to wear either hat: the slot is retired and `sfx_launch`
        // survives only as the built-in GameBoot sequence's own track.
        val event = when {
            silentRow -> null
            launches -> null
            else -> MenuSound.SELECT
        }
        event?.let { menuSound.play(it) }

        // Empty-state rows
        when (item?.id) {
            NO_CONSOLES_ITEM_ID -> {
                _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
                return
            }
            SETUP_GAP_ITEM_ID -> {
                // B3: the setup-gap row deep-links to the screen that repairs the first gap.
                _uiState.update {
                    it.copy(activeSettingsScreen = setupState.firstGap.repairScreenId)
                }
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
            MISSING_ITEM_ID -> {
                openMissingFolder()
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
            CATEGORY_CARD_ITEM_ID -> {
                openCategoryCardFolder()
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

        // Real games — including package/shortcut-backed gaming apps (Android/Windows cards) —
        // Open the Game Detail page only when direct launch is disabled. Direct launch hands off
        // from the XMB itself, so returning from the emulator leaves the cursor on this entity.
        if (item?.gameId != null && item.isRealGame) {
            if (_uiState.value.directLaunch) {
                // Direct launch is intentionally a true XMB hand-off: do not compose Game Detail
                // at all. This keeps the transition seamless and leaves the cursor on the same
                // entity when PFP resumes after the emulator closes.
                launchGameDirectly(item.gameId)
            } else {
                _uiState.update { it.copy(activeGameId = item.gameId, activeGameAutoLaunch = false) }
            }
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

        // Standard (non-game) app — A/Cross launches it directly, no detail page.
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
                    val id = item?.id
                    if (id != null) {
                        val section = settingsSectionForId(id)
                        if (section != null) {
                            Timber.d("Opening settings section flyout: ${section.title}")
                            openSettingsSection(section)
                        } else {
                            Timber.d("Opening settings screen: $id")
                            _uiState.update { it.copy(activeSettingsScreen = id) }
                        }
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
        openContextMenuFor(_uiState.value.currentItems.getOrNull(index), byTouch = true)
    }

    /** Opens the menu [contextMenuTarget] resolves for [item]; the one dispatch for Triangle and long-press. */
    private fun openContextMenuFor(item: XMBItem?, byTouch: Boolean) {
        val target = contextMenuTarget(item, _uiState.value, byTouch) ?: return
        when (target.kind) {
            ContextMenuKind.MARK_MODE -> openMarkedCollectionPicker()
            else -> {
                if (item == null) return
                when (target.kind) {
                    ContextMenuKind.MUSIC -> openMusicContextMenu(item, target.byTouch)
                    ContextMenuKind.VIDEO -> openVideoContextMenu(item, target.byTouch)
                    ContextMenuKind.PHOTO -> openPhotoContextMenu(item, target.byTouch)
                    ContextMenuKind.ACHIEVEMENTS -> openAchievementsContextMenu(item)
                    ContextMenuKind.GAME -> openGameContextMenu(item)
                    ContextMenuKind.COLLECTION -> item.collectionId?.let { openCollectionRowContextMenu(it, byTouch = target.byTouch) }
                    ContextMenuKind.ALL_GAMES -> openAllGamesContextMenu()
                    ContextMenuKind.ROOT_ROW -> openRootRowContextMenu(item, byTouch = target.byTouch)
                    ContextMenuKind.SOCIAL_ACCOUNT -> openSocialAccountContextMenu(item.title)
                    ContextMenuKind.PLATFORM -> item.platformId?.let { openPlatformContextMenu(it) }
                    ContextMenuKind.APP -> openAppContextMenu(item, byTouch = target.byTouch)
                    ContextMenuKind.MARK_MODE -> Unit
                }
            }
        }
    }

    // A custom category's Memory Card, Favorites or Missing: container rows with no record.
    private fun XMBItem.isRootRow(): Boolean =
        type == XMBItemType.CATEGORY_CARD || type == XMBItemType.FAVORITES || type == XMBItemType.MISSING

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

    private fun openMissingFolder() {
        val gamesCategoryIndex = _uiState.value.categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
        navigateRememberingCursor {
            it.copy(
                selectedCategoryIndex = gamesCategoryIndex.takeIf { index -> index >= 0 } ?: it.selectedCategoryIndex,
                selectedPlatformId = MISSING_PLATFORM_ID,
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

    fun openShibaCoins(gameId: Long) =
        openShibaCoins(com.playfieldportal.feature.xmb.ui.detail.ShibaCoinsTarget.LibraryGame(gameId))

    fun openShibaCoins(target: com.playfieldportal.feature.xmb.ui.detail.ShibaCoinsTarget) {
        _uiState.update { it.copy(activeShibaCoinsTarget = target) }
    }

    fun onCloseShibaCoins() {
        _uiState.update { it.copy(activeShibaCoinsTarget = null, pendingShibaCoinsAction = null) }
    }

    fun onShibaCoinsActionConsumed() {
        _uiState.update { it.copy(pendingShibaCoinsAction = null) }
    }

    fun openShibaLibrary(mode: ShibaLibraryMode) {
        _uiState.update { it.copy(activeShibaLibrary = mode) }
    }

    fun onCloseShibaLibrary() {
        _uiState.update { it.copy(activeShibaLibrary = null, pendingShibaLibraryAction = null) }
    }

    fun onShibaLibraryActionConsumed() {
        _uiState.update { it.copy(pendingShibaLibraryAction = null) }
    }

    // ── Search online overlay ─────────────────────────────────────────────────

    fun openSearchOnline() {
        _uiState.update { it.copy(activeSearchOnline = true) }
    }

    fun onCloseSearchOnline() {
        _uiState.update { it.copy(activeSearchOnline = false, pendingSearchOnlineAction = null) }
    }

    fun onSearchOnlineActionConsumed() {
        _uiState.update { it.copy(pendingSearchOnlineAction = null) }
    }

    /** The not-connected state's Open Settings: leave the search and land on the credentials page. */
    fun openAchievementCredentialsFromSearchOnline() {
        _uiState.update {
            it.copy(
                activeSearchOnline = false,
                pendingSearchOnlineAction = null,
                activeShibaLibrary = null,
                pendingShibaLibraryAction = null,
                activeSettingsScreen = "settings_achievements_credentials",
            )
        }
    }

    fun openPlayerStatus() {
        _uiState.update { it.copy(activePlayerStatus = true) }
    }

    fun openPlayerStatusFromSettings() {
        _uiState.update {
            it.copy(activeSettingsScreen = null, pendingSettingsAction = null, activePlayerStatus = true)
        }
    }

    /** Custom Memory Cards ▸ View Game Details: Game Detail draws above Settings, which stays put beneath it. */
    fun openGameDetailFromSettings(gameId: Long) {
        _uiState.update { it.copy(activeGameId = gameId, activeGameAutoLaunch = false) }
    }

    fun onClosePlayerStatus() {
        _uiState.update { it.copy(activePlayerStatus = false, pendingPlayerStatusAction = null) }
    }

    fun onPlayerStatusActionConsumed() {
        _uiState.update { it.copy(pendingPlayerStatusAction = null) }
    }

    private fun launchGameDirectly(gameId: Long, discId: Long? = null) {
        // Keep the XMB selection untouched. The detail overlay is only an editing surface; direct
        // launch should never navigate through it, so onResume naturally returns to this row.
        _uiState.update { it.copy(activeGameId = null, activeGameAutoLaunch = false, activeGameDiscId = null) }
        viewModelScope.launch {
            val selected = gameRepository.getById(gameId) ?: run {
                Timber.w("Direct launch requested for missing game id=$gameId")
                return@launch
            }
            val game = if (discId != null) gameRepository.getById(discId) ?: selected else selected
            if (game.isMissing) {
                Timber.i("Direct launch refused for missing game: ${game.title}")
                return@launch
            }
            launchResolvedGame(game)
        }
    }

    private suspend fun launchResolvedGame(game: Game) {
        val shortcutId = game.shortcutId
        val packageName = game.packageName
        if (shortcutId != null && packageName != null) {
            // Through the dispatcher so GameBoot plays first and the hand-off is recorded.
            launchDispatcher.launchShortcut(game) { launcherShortcutRepository.launch(packageName, shortcutId) }
                .onFailure { e ->
                    Timber.w(e, "Direct shortcut launch failed")
                    launchDispatcher.recordPreflightFailure(game, null, "Couldn't launch: ${e.message}")
                }
            return
        }
        if (game.launchIntentUri != null) {
            runCatching {
                val parsed = Intent.parseUri(game.launchIntentUri, Intent.URI_INTENT_SCHEME)
                com.playfieldportal.core.common.security.ShortcutIntentSanitizer.sanitize(parsed, context.packageManager)
                    ?: error("Captured shortcut is not safe to launch")
            }.onSuccess { intent -> launchIntentFromXmb(intent, game, null) }
                .onFailure { e ->
                    Timber.w(e, "Direct stored-intent launch failed")
                    launchDispatcher.recordPreflightFailure(game, null, "Couldn't launch: ${e.message}")
                }
            return
        }
        if (game.romPath.isNullOrBlank() && !game.packageName.isNullOrBlank()) {
            intentResolver.resolveNativeApp(game).fold(
                onSuccess = { intent -> launchIntentFromXmb(intent, game, null) },
                onFailure = { e ->
                    Timber.w(e, "Direct native-app launch failed")
                    launchDispatcher.recordPreflightFailure(game, null, e.message ?: "Could not launch ${game.title}")
                },
            )
            return
        }
        // The same ladder Game Detail walks: the game's own choice, then the console's assigned
        // default, then the platform's, then the first installed emulator.
        val resolvedLaunch = gameLaunchLadder.resolve(game).getOrElse { e ->
            Timber.w(e, "No emulator resolved for direct launch: ${game.platformId}")
            launchDispatcher.recordPreflightFailure(
                game, null,
                e.message ?: ("No emulator is set up for ${game.platformId.uppercase()}. " +
                    "Assign one under Settings ▸ Emulators ▸ Per-System Defaults."),
                code = com.playfieldportal.core.domain.model.PfpErrorCode.LN_1001,
            )
            return
        }
        val profile = resolvedLaunch.profile
        // Preflight the same checks Game Detail's resolver applies, so a stale RetroArch core
        // mapping (or a dropped launch activity) refuses here with a repair, not at startActivity.
        val validation = runCatching { intentResolver.validateBeforeLaunch(game, profile) }
        if (validation.isFailure) {
            Timber.w(
                validation.exceptionOrNull(),
                "Direct emulator launch blocked by preflight: ${profile.name}",
            )
            launchDispatcher.recordPreflightFailure(
                game, resolvedLaunch, validation.exceptionOrNull()?.message ?: "Could not launch ${profile.name}",
            )
            return
        }
        intentResolver.resolve(game, profile).fold(
            onSuccess = { intent -> launchIntentFromXmb(intent, game, resolvedLaunch) },
            onFailure = { e ->
                Timber.w(e, "Direct emulator launch failed: ${profile.name}")
                launchDispatcher.recordPreflightFailure(game, resolvedLaunch, e.message ?: "Could not launch ${profile.name}")
            },
        )
    }

    // B1: the single XMB direct-launch hand-off. All three direct paths (stored intent, native
    // app, emulator) end here, so every game launch records an outcome and — when the emulator
    // never comes to the foreground — raises the recovery sheet instead of failing silently.
    private suspend fun launchIntentFromXmb(intent: Intent, game: Game, resolved: ResolvedLaunch?) {
        when (val result = launchDispatcher.launch(game, resolved, intent)) {
            is LaunchDispatchResult.Rejected -> Timber.w("Direct launch rejected: ${result.message}")
            LaunchDispatchResult.Accepted -> discordPresence.setCurrentGame(game.displayTitle)
        }
    }

    fun onCloseGameDetail() {
        _uiState.update {
            it.copy(
                activeGameId = null,
                activeGameAutoLaunch = false,
                activeGameDiscId = null,
                pendingGameDetailAction = null,
            )
        }
        // Rebuild the visible list: title/artwork edits made in the detail screen must show the
        // moment the overlay closes (the item build is one-shot, not reactive to those tables).
        // A rename re-sorts that list, so the cursor follows the game rather than its old slot.
        loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
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

    // Silent, same as toggleGameFavorite. The menu's Favorite row shows On/Off, so this flips the
    // shortcut row's current state rather than only ever setting it.
    private fun toggleAppFavorite(packageName: String, label: String) {
        viewModelScope.launch {
            runCatching {
                val id = ensureAppShortcut(packageName)
                val favorite = gameRepository.getById(id)?.isFavorite != true
                gameRepository.setFavorite(id, favorite)
                favorite
            }.onSuccess { favorite ->
                Timber.i("App shortcut favorite=$favorite: $packageName")
                backgroundTasks.report(
                    id = "shortcut_fav_$packageName",
                    label = label,
                    message = if (favorite) "Added to Favorites" else "Removed from Favorites",
                    severity = NotificationSeverity.SUCCESS,
                )
            }.onFailure { e ->
                Timber.e(e, "Failed to toggle app Favorite: $packageName")
                backgroundTasks.report(
                    id = "shortcut_fav_$packageName",
                    label = label,
                    message = "Couldn't update Favorites: ${e.message}",
                    severity = NotificationSeverity.ERROR,
                    detail = NotificationDetail.notes(PfpErrorCode.SY_9001, summary = e.message,
                        diagnostic = e.stackTraceToString().take(4_000)),
                )
            }
        }
    }

    private fun addAppToCollection(packageName: String, label: String) {
        viewModelScope.launch {
            runCatching { ensureAppShortcut(packageName) }
                .onSuccess { id -> openCollectionPicker(id) }
                .onFailure { e ->
                    Timber.e(e, "Failed to prepare app shortcut for collection: $packageName")
                    backgroundTasks.report(
                        id = "shortcut_col_$packageName",
                        label = label,
                        message = "Couldn't create shortcut: ${e.message}",
                        severity = NotificationSeverity.ERROR,
                        detail = NotificationDetail.notes(PfpErrorCode.SY_9001, summary = e.message,
                            diagnostic = e.stackTraceToString().take(4_000)),
                    )
                }
        }
    }

    private fun launchHarvestedShortcut(hostPackage: String?, shortcutId: String?) {
        if (hostPackage == null || shortcutId == null) return
        launcherShortcutRepository.launch(hostPackage, shortcutId).onFailure { e ->
            Timber.e(e, "Failed to launch shortcut $hostPackage/$shortcutId")
            backgroundTasks.report(
                id = "launch_sc_$shortcutId",
                label = hostPackage,
                message = "Couldn't launch: ${e.message}",
                severity = NotificationSeverity.ERROR,
                kind = com.playfieldportal.core.domain.model.NotificationKind.LAUNCH,
                detail = NotificationDetail.notes(
                    if (e is android.content.ActivityNotFoundException) PfpErrorCode.LN_4001 else PfpErrorCode.LN_9001,
                    summary = e.message, diagnostic = e.stackTraceToString().take(4_000),
                ),
            )
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
            backgroundTasks.report(
                id = "launch_intent_${label.hashCode()}",
                label = label,
                message = "Couldn't launch: ${e.message}",
                severity = NotificationSeverity.ERROR,
                kind = com.playfieldportal.core.domain.model.NotificationKind.LAUNCH,
                detail = NotificationDetail.notes(
                    if (e is android.content.ActivityNotFoundException) PfpErrorCode.LN_4001 else PfpErrorCode.LN_9001,
                    summary = e.message, diagnostic = e.stackTraceToString().take(4_000),
                ),
            )
        }
    }

    fun onCloseAppDetail() {
        _uiState.update { it.copy(activeAppId = null, pendingAppDetailAction = null) }
        // Rebuild the visible list so a freshly assigned background/icon (games-table row keyed by
        // package) reaches the XMB rows immediately — this is what puts artworkUri on app items.
        // App renames re-sort too, so the cursor follows the row by id.
        loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
    }

    fun consumeAppDetailAction() {
        _uiState.update { it.copy(pendingAppDetailAction = null) }
    }

    // ── Settings hierarchy (L1 section flyouts) ───────────────────────────────
    // Drilling into a section reuses the shared drill path (computeDrillTitle / computeDrillSiblings
    // → XmbDrillFlyout) with cursor memory, exactly like Music/Video/Photo/Social. Back from an L2
    // screen simply closes the overlay: settingsSectionNav survives underneath, so the owning
    // flyout is revealed — there is no extra stack to unwind.

    private fun openSettingsSection(section: SettingsSection) {
        // Rebuild immediately: the settings section changes the visible item list and drill
        // metadata. Waiting for a category reload can leave the old selection/index in place,
        // which may activate an invalid row and crash on nested Settings screens.
        navigateRememberingCursor { it.copy(settingsSectionNav = section) }
    }

    private fun closeSettingsSection() {
        navigateRememberingCursor { it.copy(settingsSectionNav = null) }
    }

    // ── Settings overlay ──────────────────────────────────────────────────────

    fun onCloseSettingsScreen() {
        Timber.d("Settings closed")
        // Leaving the setup wizard by any deliberate path (Skip, Finish, back-out on a re-run)
        // stamps it as seen — see markInitialSetupSeen for why open time is the wrong moment.
        val closing = _uiState.value.activeSettingsScreen
        if (closing == INITIAL_SETUP_SCREEN_ID || closing == INITIAL_SETUP_FIRST_RUN_SCREEN_ID) {
            markInitialSetupSeen()
        }
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

    // The App Drawer's menu rows that the XMB owns. Its own menu and pickers draw over the drawer
    // and take the pad first, so the drawer stays open underneath.
    fun onDrawerEditAppDetails(packageName: String) = openAppDetail(null, packageName)

    fun onDrawerAddAppToCard(packageName: String, label: String) = addAppToCollection(packageName, label)

    fun onDrawerToggleAppFavorite(packageName: String, label: String) = toggleAppFavorite(packageName, label)

    // ── Color-scheme picker ─────────────────────────────────────────────────────
    //
    // The picker previews schemes live: moving the cursor writes the highlighted
    // scheme to DataStore so observeColorScheme repaints the wave/background. BACK
    // restores whatever scheme was active when the picker opened; SELECT commits.

    /** The custom accent picker's cursor, on the navigation core. */
    private val customColorNav = com.playfieldportal.core.ui.components.HsvPickerNav()
    private var colorSchemeOriginal: XmbColorScheme? = null
    // A custom-theme accent active when the picker opened — restored on cancel (previews
    // temporarily clear it so presets are actually visible).
    private var accentOverrideOriginal: Long? = null

    fun openColorSchemePicker() {
        viewModelScope.launch {
            val prefs = context.pfpDataStore.data.first()
            val current = runCatching {
                XmbColorScheme.valueOf(prefs[KEY_COLOR_SCHEME] ?: XmbColorScheme.CLASSIC_BLUE.name)
            }.getOrDefault(XmbColorScheme.CLASSIC_BLUE)
            colorSchemeOriginal = current
            accentOverrideOriginal = prefs[KEY_ACCENT_OVERRIDE]

            val month = java.time.LocalDate.now().monthValue
            val options = XmbColorScheme.values().map { scheme ->
                ColorSchemeOption(
                    scheme   = scheme,
                    label    = scheme.displayLabel(),
                    sublabel = if (scheme == XmbColorScheme.ORIGINAL) "Changes with the month" else "Fixed color preset",
                    swatch   = scheme.resolve(month).waveColor,
                )
            }
            val custom = prefs[KEY_ACCENT_OVERRIDE]
            val pickerOptions = options + ColorSchemeOption(
                scheme = null,
                label = "Custom",
                sublabel = "Choose a custom accent color",
                swatch = custom ?: 0xFF888888L,
                isCustom = true,
            )
            val index = if (custom != null) pickerOptions.lastIndex else options.indexOfFirst { it.scheme == current }.coerceAtLeast(0)
            _uiState.update { it.copy(colorSchemePicker = ColorSchemePickerState(pickerOptions, index)) }
        }
    }

    private fun moveColorSchemePicker(delta: Int) {
        val picker = _uiState.value.colorSchemePicker ?: return
        val next = (picker.selectedIndex + delta).coerceIn(0, picker.options.lastIndex)
        if (next == picker.selectedIndex) { gamepadInputHandler.cancelRepeat(); return }
        // After the clamp guard, so an end of the list is silent — the same rule every other
        // cursor in the app follows.
        menuSound.play(MenuSound.SCROLL)
        _uiState.update { it.copy(colorSchemePicker = picker.copy(selectedIndex = next)) }
        picker.options[next].scheme?.let(::previewColorScheme)
    }

    /** Touch handler — highlight (and live-preview) the tapped row. */
    fun onColorSchemeHighlightedAt(index: Int) {
        val picker = _uiState.value.colorSchemePicker ?: return
        if (index !in picker.options.indices || index == picker.selectedIndex) return
        // Tapping a row moves the cursor there (and live-previews it), so it ticks like a move.
        menuSound.play(MenuSound.SCROLL)
        _uiState.update { it.copy(colorSchemePicker = picker.copy(selectedIndex = index)) }
        picker.options[index].scheme?.let(::previewColorScheme)
    }

    private fun previewColorScheme(scheme: XmbColorScheme) {
        viewModelScope.launch {
            context.pfpDataStore.edit {
                it[KEY_COLOR_SCHEME] = scheme.name
                // Suspend a custom-theme accent while previewing, or the preset preview would
                // be invisibly masked by the override. Cancel restores it (see below).
                it.remove(KEY_ACCENT_OVERRIDE)
            }
        }
    }

    fun confirmColorSchemePicker() {
        val picker = _uiState.value.colorSchemePicker ?: return
        val selected = picker.options.getOrNull(picker.selectedIndex)
        val chosen = selected?.scheme
        if (selected?.isCustom == true) {
            // Custom descends into the HSV picker rather than committing anything, so it takes the
            // ordinary activation cue and the commit cue waits for Apply in there.
            menuSound.play(MenuSound.SELECT)
            openCustomColorPicker()
            return
        }
        menuSound.play(MenuSound.CONFIRM)
        viewModelScope.launch {
            if (chosen != null) {
                // Explicitly choosing a preset exits custom-theme mode — otherwise the imported
                // theme's accent would keep overriding the pick invisibly. The theme's icons,
                // geometry and name leave with it (PfpThemeStore.clearThemeLook).
                pfpThemeStore.clearThemeLook()
                context.pfpDataStore.edit { it[KEY_COLOR_SCHEME] = chosen.name }
            }
            colorSchemeOriginal = null
            accentOverrideOriginal = null
            _uiState.update { it.copy(colorSchemePicker = null) }
        }
    }

    private fun openCustomColorPicker() {
        val argb = _uiState.value.themeColors.accentColor.toArgb().toLong() and 0xFFFFFFFFL
        _uiState.update { it.copy(customColorPicker = com.playfieldportal.core.ui.components.HsvPickerState.fromArgb(argb)) }
    }

    /** Touch and typing in the picker: a bar tapped or dragged, the Hex field tapped or typed into. */
    fun updateCustomColorPicker(picker: com.playfieldportal.core.ui.components.HsvPickerState) {
        _uiState.update { state -> if (state.customColorPicker == null) state else state.copy(customColorPicker = picker) }
    }

    fun confirmCustomColor() {
        val picker = _uiState.value.customColorPicker ?: return
        viewModelScope.launch {
            context.pfpDataStore.edit { it[KEY_ACCENT_OVERRIDE] = picker.argb }
            _uiState.update { it.copy(customColorPicker = null, colorSchemePicker = null) }
        }
    }

    fun cancelCustomColor() {
        _uiState.update { it.copy(customColorPicker = null) }
    }

    fun cancelColorSchemePicker() {
        menuSound.play(MenuSound.BACK)
        val original = colorSchemeOriginal
        val accentOriginal = accentOverrideOriginal
        viewModelScope.launch {
            if (original != null) {
                context.pfpDataStore.edit {
                    it[KEY_COLOR_SCHEME] = original.name
                    // Restore the custom-theme accent that previews temporarily cleared.
                    if (accentOriginal != null) it[KEY_ACCENT_OVERRIDE] = accentOriginal
                }
            }
            colorSchemeOriginal = null
            accentOverrideOriginal = null
            _uiState.update { it.copy(colorSchemePicker = null) }
        }
    }

    // ── Live "Adjust XMB Layout" editor ────────────────────────────────────────
    // Opens over the real XMB (settings closed, so the cross is visible and moves live). The
    // current form-factor bucket is read from the window config; the draft seeds from a saved
    // tuning for that bucket, else from the legacy scale + the theme's bar line.

    fun openXmbLayoutAdjust() {
        val swDp = context.resources.configuration.smallestScreenWidthDp
        val bucket = com.playfieldportal.themekit.XmbFormFactor.forSmallestWidthDp(swDp).key
        val s = _uiState.value
        val seed = s.xmbLayoutAdjustMap[bucket] ?: com.playfieldportal.themekit.XmbLayoutAdjust(
            scale = s.xmbScale,
            barLeftFraction = 0f,
            barTopFraction = s.layoutSpec.barTopFraction,
        )
        _uiState.update {
            it.copy(
                // Close any settings screen so the live XMB shows behind the editor.
                activeSettingsScreen = null,
                pendingSettingsAction = null,
                xmbLayoutAdjust = XmbLayoutAdjustSession(draft = seed, original = seed, bucketKey = bucket),
            )
        }
    }

    private fun updateAdjustDraft(transform: (com.playfieldportal.themekit.XmbLayoutAdjust) -> com.playfieldportal.themekit.XmbLayoutAdjust) {
        val session = _uiState.value.xmbLayoutAdjust ?: return
        val next = com.playfieldportal.themekit.XmbLayoutAdjustCodec.sanitize(transform(session.draft))
        _uiState.update { it.copy(xmbLayoutAdjust = session.copy(draft = next)) }
    }

    // Nudge steps for D-pad / shoulder-button control.
    fun nudgeXmbLayoutHorizontal(dir: Int) = updateAdjustDraft { it.copy(barLeftFraction = it.barLeftFraction + dir * 0.01f) }
    fun nudgeXmbLayoutVertical(dir: Int) = updateAdjustDraft { it.copy(barTopFraction = it.barTopFraction + dir * 0.01f) }
    fun nudgeXmbLayoutScale(dir: Int) = updateAdjustDraft { it.copy(scale = it.scale + dir * 0.02f) }

    // Absolute setters for the on-screen sliders.
    fun setXmbLayoutScale(v: Float) = updateAdjustDraft { it.copy(scale = v) }
    fun setXmbLayoutHorizontal(v: Float) = updateAdjustDraft { it.copy(barLeftFraction = v) }
    fun setXmbLayoutVertical(v: Float) = updateAdjustDraft { it.copy(barTopFraction = v) }

    fun toggleXmbLayoutSliders() {
        val session = _uiState.value.xmbLayoutAdjust ?: return
        _uiState.update { it.copy(xmbLayoutAdjust = session.copy(slidersVisible = !session.slidersVisible)) }
    }

    fun resetXmbLayoutAdjust() = updateAdjustDraft { com.playfieldportal.themekit.XmbLayoutAdjust() }

    fun saveXmbLayoutAdjust() {
        val session = _uiState.value.xmbLayoutAdjust ?: return
        val map = _uiState.value.xmbLayoutAdjustMap.toMutableMap()
        map[session.bucketKey] = session.draft
        viewModelScope.launch {
            context.pfpDataStore.edit {
                it[ThemeLook.KEY_XMB_LAYOUT_ADJUST] = com.playfieldportal.themekit.XmbLayoutAdjustCodec.encode(map)
            }
            _uiState.update { it.copy(xmbLayoutAdjust = null) }
        }
    }

    fun cancelXmbLayoutAdjust() {
        _uiState.update { it.copy(xmbLayoutAdjust = null) }
    }

    // ── Live "Customize XMB Icons" editor ────────────────────────────────────
    // Opens over the real XMB (settings closed, so the cross and columns are visible). Edits
    // apply immediately through CustomIconStore — there is no draft to discard; Reset /
    // Reset All are the undo. The session carries only cursor + message state.

    fun openCustomIcons() {
        _uiState.update {
            it.copy(
                // Close any settings screen so the live XMB shows behind the editor.
                activeSettingsScreen = null,
                pendingSettingsAction = null,
                customIconSession = customIconSessionFor(it.categories),
            )
        }
    }

    fun closeCustomIcons() {
        _uiState.update { it.copy(customIconSession = null) }
    }

    // Every cursor move drops [CustomIconSession.message]: it always describes what just
    // happened to ONE slot, so carrying it to the next one would attribute the outcome to a
    // slot it was never about.

    /** Moves the tab cursor (L1/R1); wraps so the ends loop. */
    fun onCustomIconTabMove(dir: Int) {
        val session = _uiState.value.customIconSession ?: return
        val next = (session.tabIndex + dir).mod(session.tabs.size)
        _uiState.update {
            it.copy(customIconSession = session.copy(tabIndex = next, slotIndex = 0, message = null))
        }
    }

    /** UP/DOWN: jumps between XMB columns on the Items tab; elsewhere steps like LEFT/RIGHT. */
    fun onCustomIconColumnMove(dir: Int) {
        val session = _uiState.value.customIconSession ?: return
        if (session.tab != com.playfieldportal.themekit.IconEditorTab.ITEMS) {
            onCustomIconSlotMove(dir)
            return
        }
        val target = session.columnJump(dir) ?: return
        _uiState.update { it.copy(customIconSession = session.copy(slotIndex = target, message = null)) }
    }

    /** Moves the slot cursor within the current tab (LEFT/RIGHT); clamps at the ends. */
    fun onCustomIconSlotMove(dir: Int) {
        val session = _uiState.value.customIconSession ?: return
        val count = session.slots().size
        val next = (session.slotIndex + dir).coerceIn(0, (count - 1).coerceAtLeast(0))
        _uiState.update { it.copy(customIconSession = session.copy(slotIndex = next, message = null)) }
    }

    /** Touch: focus a strip slot directly (same cursor as the pad moves). */
    fun onCustomIconSlotFocused(index: Int) {
        val session = _uiState.value.customIconSession ?: return
        _uiState.update { it.copy(customIconSession = session.copy(slotIndex = index, message = null)) }
    }

    /** SAF result: replace [slotKey]'s icon. The message lands in the session. */
    fun onIconPicked(slotKey: String, uri: android.net.Uri) {
        val session = _uiState.value.customIconSession ?: return
        viewModelScope.launch {
            val mime = context.contentResolver.getType(uri)
            val result = customIconStore.import(slotKey, uri, mime)
            // Committed (or refused) import: the same confirm/error pairing the sound pickers use.
            menuSound.play(if (result.ok) MenuSound.CONFIRM else MenuSound.ERROR)
            _uiState.update {
                val s = it.customIconSession ?: return@update it
                it.copy(customIconSession = s.copy(message = result.message, revision = s.revision + 1))
            }
        }
    }

    /**
     * Per-slot Reset: the user's pick goes; the built-in returns immediately UNLESS the
     * applied theme supplies this slot, in which case the theme's icon surfaces instead.
     *
     * Reset only ever clears the user tier, so on a themed slot — or one that was never
     * picked — it legitimately changes nothing on screen. That is precisely when it reads as
     * a dead button, so every outcome says what happened; only the unambiguous one (the
     * built-in visibly returns) stays silent.
     */
    fun onResetSlot(slotKey: String) {
        viewModelScope.launch {
            val removed = customIconStore.clear(slotKey)
            _uiState.update {
                val s = it.customIconSession ?: return@update it
                val themed = themeTiers.iconFile(com.playfieldportal.core.data.repository.ThemeTiers.Tier.THEME, slotKey) != null
                val message = when {
                    removed && themed -> context.getString(R.string.xmb_icons_reset_removed_themed)
                    removed -> null
                    themed -> context.getString(R.string.xmb_icons_reset_themed_slot)
                    else -> context.getString(R.string.xmb_icons_reset_builtin_slot)
                }
                it.copy(customIconSession = s.copy(message = message, revision = s.revision + 1))
            }
        }
    }

    /** Reset All: every user pick goes; the built-ins return except where the theme supplies a slot. */
    fun onResetAll() {
        viewModelScope.launch {
            val removed = customIconStore.clearAll()
            _uiState.update {
                val s = it.customIconSession ?: return@update it
                val message = when {
                    !removed -> context.getString(R.string.xmb_icons_reset_all_none)
                    themeTiers.iconKeys(com.playfieldportal.core.data.repository.ThemeTiers.Tier.THEME).isNotEmpty() ->
                        context.getString(R.string.xmb_icons_reset_all_themed)
                    else -> null
                }
                it.copy(customIconSession = s.copy(message = message, revision = s.revision + 1))
            }
        }
    }

    /** XMBShell calls once the share sheet has fired (or failed) for [pendingThemeShareFile]. */
    fun onThemeShareConsumed() {
        _uiState.update { it.copy(pendingThemeShareFile = null) }
    }

    /** "Save as Theme…" — opens the name dialog (reusing the playlist-dialog pattern). */
    fun requestSaveCurrentLookAsTheme() {
        _uiState.update {
            it.copy(saveThemeNameDialog = PlaylistNameDialogState(title = "Save Current Look as Theme"))
        }
    }

    fun confirmSaveCurrentLookAsTheme(name: String) {
        _uiState.update { it.copy(saveThemeNameDialog = null) }
        // No cue here: the shared modal voices its own Save, for a tap and for the pad alike.
        saveCurrentLookAsTheme(name)
    }

    fun dismissSaveThemeNameDialog() {
        _uiState.update { it.copy(saveThemeNameDialog = null) }
    }

    // Parks one press for the shell's shared modal (a name entry, the info notice, the Windows
    // setup prompt). XMBShell hands it to the modal's host and calls [onShellModalActionConsumed].
    private fun forwardToShellModal(action: GamepadAction) {
        _uiState.update { it.copy(pendingShellModalAction = action) }
    }

    /**
     * Confirm on the shared modal for a menu's [XmbConfirm]. Cleared first so a second press has
     * nothing to confirm; what each kind does is exactly what its menu row did before it asked.
     */
    fun confirmPendingConfirm(confirm: XmbConfirm) {
        _uiState.update { it.copy(pendingConfirm = null) }
        when (confirm) {
            is XmbConfirm.RemoveGame -> appAction { removeGameFromLibrary(confirm.gameId) }
            // Reuses the standard removal: deletes the row, recounts the card; the file is
            // untouched and a later scan re-discovers it.
            is XmbConfirm.RemoveMissing -> appAction { removeGameFromLibrary(confirm.gameId) }
            is XmbConfirm.DeleteCard -> appAction {
                collectionRepository.delete(confirm.collectionId)
                if (_uiState.value.selectedCollectionId == confirm.collectionId) closePlatformFolder()
            }
            is XmbConfirm.RemoveFromCategory -> appAction {
                gameCategoryRepository.removeGameFromCategoryEverywhere(confirm.gameId, confirm.categoryId)
                loadItemsForCategory(currentCategory(), keepCursorOnRow = true)
            }
            is XmbConfirm.RemoveAndroidGame -> appAction {
                gameRepository.delete(confirm.gameId)
                memoryCardRepository.recountGames(ANDROID_PLATFORM_ID)
            }
            is XmbConfirm.RemoveCard -> removeCard(confirm.platformId)
            is XmbConfirm.RemoveTrack -> appAction {
                val track = musicRepository.getTrack(confirm.trackId) ?: return@appAction
                removeSingleTrack(track.folderId, confirm.trackId)
            }
            is XmbConfirm.RemoveVideo -> appAction { videoRepository.removeVideo(confirm.videoId) }
            is XmbConfirm.RemovePhoto -> appAction { photoRepository.removePhoto(confirm.photoId) }
            is XmbConfirm.DeleteMusicPlaylist -> appAction {
                musicRepository.deletePlaylist(confirm.playlistId)
                if ((_uiState.value.musicNav as? MusicNav.Playlist)?.id == confirm.playlistId) closeMusicView()
            }
            is XmbConfirm.DeleteVideoPlaylist -> appAction {
                videoRepository.deletePlaylist(confirm.playlistId)
                if ((_uiState.value.videoNav as? VideoNav.Playlist)?.id == confirm.playlistId) openVideoView(VideoNav.Playlists)
            }
            // Empties the history and leaves runningTasks alone: clearing the tray can never
            // cancel a scan, because a scan was never a row.
            XmbConfirm.ClearNotifications -> appAction { notificationRepository.clearAll() }
            // Android shows its own dialog after this one; the list refreshes when the user returns.
            is XmbConfirm.Uninstall -> installedAppRepository.uninstallApp(confirm.packageName)
        }
    }

    /** Cancel (or Back) on a menu confirm: nothing happens. */
    fun cancelPendingConfirm() {
        _uiState.update { it.copy(pendingConfirm = null) }
    }

    /** XMBShell calls once the shared modal has handled a forwarded [pendingShellModalAction]. */
    fun onShellModalActionConsumed() {
        _uiState.update { it.copy(pendingShellModalAction = null) }
    }

    /** The overlay calls once it has handled a forwarded [pendingCustomIconsAction]. */
    fun onCustomIconsActionConsumed() {
        _uiState.update { it.copy(pendingCustomIconsAction = null) }
    }

    /**
     * Save as Theme…: writes the whole current look (picks + theme icons + wallpaper + colors
     * + motion) into the theme library, then opens the share sheet for the saved bundle.
     * Reuses PfpThemeStore.exportForShare — no new share plumbing.
     */
    fun saveCurrentLookAsTheme(name: String) {
        viewModelScope.launch {
            val saved = pfpThemeStore.saveCurrentLook(name)
            val message = when {
                saved == null -> "Couldn't save the theme"
                else -> "Theme saved — ${saved.name}"
            }
            val shareFile = saved?.let { pfpThemeStore.exportForShare(it.id) }
            _uiState.update {
                val s = it.customIconSession ?: return@update it
                it.copy(
                    customIconSession = s.copy(message = message, revision = s.revision + 1),
                    pendingThemeShareFile = shareFile,
                )
            }
        }
    }


    // The two Boot Sequence prefs, kept as fields rather than in UiState: nothing renders them,
    // and they are only read at the resume moment. Both were previously written by settings and
    // read by nothing at all — the animation always played.
    @Volatile
    private var bootEnabled: Boolean = true

    @Volatile
    private var bootOnResume: Boolean = false

    /**
     * Seeds [XMBUiState.showBootSequence] from `display_show_boot` and tracks both boot prefs.
     * The overlay holds on a black frame until startup permissions and the first-run check
     * resolve (see XMBShell), so this read lands well before anything animates — a boot the user
     * turned off never becomes visible.
     */
    private fun observeBootPreferences() {
        observeAmbienceBootGate()
        viewModelScope.launch {
            val prefs = context.pfpDataStore.data.first()
            bootEnabled = prefs[KEY_SHOW_BOOT] ?: true
            bootOnResume = prefs[KEY_BOOT_ON_RESUME] ?: false
            if (!bootEnabled) {
                _uiState.update { it.copy(showBootSequence = false) }
            }
        }
        viewModelScope.launch {
            context.pfpDataStore.data
                .map { (it[KEY_SHOW_BOOT] ?: true) to (it[KEY_BOOT_ON_RESUME] ?: false) }
                .distinctUntilChanged()
                .collect { (enabled, onResume) ->
                    bootEnabled = enabled
                    bootOnResume = onResume
                }
        }
        // The boot media (the user's clip, else the theme's, else the built-in animation and its
        // chime) arrive with the theme look — see observeThemeLook.
    }

    /**
     * PFP is in the foreground again after having been stopped — in practice, back from a game.
     * Replays the boot sequence when the user asked for it. Show Boot Sequence gates this too:
     * turning boot off skips BOTH of its media components, resume included (design rule 7).
     */
    /**
     * Whether the launcher is on screen (MainActivity onStart/onStop). Clocks that only feed the
     * UI — the idle-hint poll, music position ticks — sleep while it is false.
     */
    fun setHostVisible(visible: Boolean) {
        hostVisible.value = visible
    }

    fun onHostResumed() {
        scheduleAchievementCheck()
        viewModelScope.launch { maybeShowShortcutReview() }
        if (!bootEnabled || !bootOnResume) return
        _uiState.update { it.copy(showBootSequence = true) }
    }

    // PFP being used is the only trigger for the scheduled achievement check: it enqueues one
    // constrained job, and only when a provider is past its 24-hour window (never a daily wake-up).
    private fun scheduleAchievementCheck() {
        viewModelScope.launch {
            runCatching { achievementAutoUpdates.onAppUsed() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Timber.w(it, "Achievement check scheduling failed") }
        }
    }

    // ── GameBoot ──────────────────────────────────────────────────────────────

    private fun observeGameBoot() {
        // The gate raises a request from inside LaunchDispatcher and suspends the launch until the
        // overlay reports back. A preview is never overwritten by one: previews are only reachable
        // from Settings, where no launch is in flight.
        viewModelScope.launch {
            gameBootGate.active.collect { request ->
                _uiState.update {
                    if (request == null && it.gameBootIsPreview) it
                    else it.copy(activeGameBoot = request, gameBootIsPreview = false)
                }
            }
        }
    }

    /**
     * The overlay's presentation is over — naturally, skipped, failed, or watchdogged. A preview
     * just closes; a real one releases the launch that is waiting on the gate.
     */
    fun onGameBootComplete() {
        val wasPreview = _uiState.value.gameBootIsPreview
        _uiState.update { it.copy(activeGameBoot = null, gameBootIsPreview = false) }
        if (wasPreview) {
            // Nothing is launching, so nothing will take audio focus and cut the clip: a skipped
            // preview has to silence itself, or the sound plays on over the settings screen.
            uiMediaAudioPlayer.stop()
        } else {
            gameBootGate.onPresentationFinished()
        }
    }

    // ── Settings previews ─────────────────────────────────────────────────────

    /**
     * Settings ▸ Display ▸ Boot Sequence ▸ Preview. Re-shows the real overlay over the settings
     * screen (boot already draws above that layer); its normal completion path returns here.
     */
    fun previewBootSequence() {
        _uiState.update { it.copy(showBootSequence = true) }
    }

    /**
     * Settings ▸ Display ▸ GameBoot ▸ Preview. Composes the overlay directly and NEVER touches the
     * gate — a preview must not be able to launch anything.
     *
     * It does everything else the gate does, though, and that is the point: same media resolution,
     * same audio player, started at the same moment relative to the first frame. The preview and
     * the real presentation differ only in what is waiting on the other side.
     */
    /**
     * A boot or GameBoot clip would not play, so the built-in presentation stood in. Reported in the
     * tray rather than failing silently (an H.264 High 4:4:4 clip, say); keyed per presentation, so a
     * clip that fails on every launch stays one row.
     */
    fun onPresentationClipFailed(gameBoot: Boolean) {
        val failure = com.playfieldportal.feature.xmb.ui.PresentationClipFailure.of(gameBoot)
        backgroundTasks.report(
            id = failure.id,
            label = failure.label,
            message = failure.message,
            severity = com.playfieldportal.core.domain.model.NotificationSeverity.WARNING,
            action = NotificationAction.OpenSettingsScreen("settings_display"),
        )
    }

    fun previewGameBoot() {
        viewModelScope.launch {
            // The preview plays regardless of the switch, so the user can audition the
            // presentation before turning it on. Audio resolves exactly as the gate resolves it
            // — the built-in sound, or nothing when a custom clip carries its own track — so the
            // preview sounds like the real thing.
            val (video, audio) = withContext(Dispatchers.IO) {
                val customVideo = uiMediaStore.pathFor(com.playfieldportal.core.domain.model.UiMediaSlot.GAMEBOOT_VIDEO)
                customVideo to resolveGameBootAudio(
                    customVideoPath = customVideo,
                    defaultUri = com.playfieldportal.core.ui.media.gameBootDefaultAudioUri(context.packageName),
                )
            }
            // Started here, immediately before the overlay composes, exactly as GameBootGate
            // starts it before a real presentation — the sequence is beat-matched to this sound,
            // so a silent preview would show light landing on nothing.
            audio?.let {
                uiMediaAudioPlayer.play(
                    uri = it,
                    clipEndMs = com.playfieldportal.themekit.UiMediaLimits.GAMEBOOT_SEQUENCE_MS,
                    label = "gameboot-preview",
                    channel = com.playfieldportal.core.domain.model.AudioChannel.GAMEBOOT,
                )
            }
            _uiState.update {
                it.copy(
                    activeGameBoot = com.playfieldportal.feature.launcher.GameBootRequest(
                        gameTitle = "Preview",
                        videoPath = video,
                        audioPath = audio,
                    ),
                    gameBootIsPreview = true,
                )
            }
        }
    }

    // ── Boot sequence ─────────────────────────────────────────────────────────

    /**
     * Holds ambience until the boot sequence is off screen — chime first, then music.
     *
     * Driven off the STATE rather than from [onBootSequenceComplete], because that is only one of
     * the ways the boot ends: the pref can be off so it never runs, a resume can replay it, and a
     * skip can cut it short. Observing the flag covers all of them, where hooking the completion
     * callback would leave ambience silent forever on the install that has boot turned off.
     */
    private fun observeAmbienceBootGate() {
        viewModelScope.launch {
            _uiState
                .map { it.showBootSequence }
                .distinctUntilChanged()
                .collect { showing -> ambienceController.setBootFinished(!showing) }
        }
        // The boot chime's level. Collected for the process's lifetime rather than sampled when
        // the boot starts, so dragging Boot Sequence on the Sound screen is audible against a
        // replayed boot immediately rather than one boot later.
        viewModelScope.launch {
            audioLevels.gainFor(com.playfieldportal.core.domain.model.AudioChannel.BOOT)
                .distinctUntilChanged()
                .collect { gain -> _uiState.update { it.copy(bootAudioGain = gain) } }
        }
        // Same for a GameBoot clip that carries its own track.
        viewModelScope.launch {
            audioLevels.gainFor(com.playfieldportal.core.domain.model.AudioChannel.GAMEBOOT)
                .distinctUntilChanged()
                .collect { gain -> _uiState.update { it.copy(gameBootAudioGain = gain) } }
        }
    }

    fun onBootSequenceComplete() {
        Timber.d("StartupSeq: boot sequence complete")
        _uiState.update { it.copy(showBootSequence = false) }
    }

    /** MainActivity reports the notification-permission dialog resolved (or was never needed). */
    fun onStartupPermissionsSettled() {
        Timber.d("StartupSeq: notification permission settled")
        _uiState.update { it.copy(startupPermissionsSettled = true) }
    }

    // ── First-run setup wizard ────────────────────────────────────────────────

    /**
     * One-shot first-run check. A fresh install (nothing configured, wizard never shown) gets
     * the wizard opened immediately: it composes hidden beneath the opaque boot overlay (which
     * XMBShell draws on top of the settings layer and holds until this check resolves), so the
     * boot dissolve reveals the wizard — never the XMB. Installs that already carry
     * configuration are upgraders: the seen flag is written silently so the wizard never
     * appears for them.
     */
    private fun checkInitialSetup() {
        viewModelScope.launch {
            val prefs = context.pfpDataStore.data.first()
            when {
                prefs[KEY_INITIAL_SETUP_SEEN] == true ->
                    Timber.d("StartupSeq: initial setup already seen")
                hasExistingSetupConfig(prefs) || hasExistingLibrary() -> {
                    context.pfpDataStore.edit { it[KEY_INITIAL_SETUP_SEEN] = true }
                    Timber.i("StartupSeq: existing configuration found, wizard seeded as seen")
                }
                else -> {
                    Timber.i("StartupSeq: fresh install, opening first-run wizard")
                    _uiState.update { it.copy(activeSettingsScreen = INITIAL_SETUP_FIRST_RUN_SCREEN_ID) }
                }
            }
            // Releases the boot overlay's hold: the wizard (if due) is now composed beneath it,
            // so the XMB-before-wizard race is excluded by construction, not by timing.
            _uiState.update { it.copy(initialSetupDecided = true) }
        }
    }

    // Memory cards cover the modern flows that write none of the checked pref keys (e.g. an
    // Android-apps-only library) — any card means an established install, not a fresh one.
    private suspend fun hasExistingLibrary(): Boolean =
        runCatching { memoryCardRepository.getAll().isNotEmpty() }.getOrDefault(false)

    // Verbose trace of the startup choreography — one line per state change, so logcat shows
    // exactly what was on screen in what order (permission gate, boot overlay, wizard, XMB).
    // Bounded: the collector completes right after the emission that ends the boot sequence,
    // so the hot XMB state stream carries no permanent logging tax.
    private fun logStartupSequence() {
        viewModelScope.launch {
            _uiState
                .map { Triple(it.startupPermissionsSettled, it.showBootSequence, it.activeSettingsScreen) }
                .distinctUntilChanged()
                .transformWhile { emit(it); it.second }
                .collect { (settled, boot, screen) ->
                    Timber.v(
                        "StartupSeq: permissionsSettled=$settled showBootSequence=$boot " +
                            "activeSettingsScreen=$screen xmbForegroundVisible=${!boot && screen == null}"
                    )
                }
        }
    }

    /**
     * Stamps the wizard as seen. Called on deliberate exits (Skip, Finish, closing the overlay,
     * jumping to Library Manager) — not at open, so a process death mid-wizard re-opens it on
     * the next launch instead of silently cancelling first-run setup forever.
     */
    private fun markInitialSetupSeen() {
        viewModelScope.launch {
            context.pfpDataStore.edit { it[KEY_INITIAL_SETUP_SEEN] = true }
        }
    }

    /** Wizard finish page: jump straight into the Library Manager to add consoles and scan. */
    fun openLibraryManager() {
        markInitialSetupSeen()
        _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
    }

    /** Settings ▸ Artwork Folder & Import ▸ Unmatched Artwork (C22 task T4). */
    fun openArtworkOrphans() {
        _uiState.update { it.copy(activeSettingsScreen = "settings_artwork_orphans") }
    }

    /**
     * Wizard FINISH "Go to your library" (B3): close the wizard and land on the All Games folder
     * — cursor on the first playable game when one exists, so finishing setup ends on something
     * launchable instead of dropping the user back on a bare category bar.
     */
    fun goToLibrary() {
        markInitialSetupSeen()
        _uiState.update { it.copy(activeSettingsScreen = null, pendingSettingsAction = null) }
        openAllGamesFolder()
        viewModelScope.launch {
            val first = runCatching { gameRepository.observeGamesOnly().first() }
                .getOrDefault(emptyList())
                .filterNot { it.isMissing }
                .minByOrNull { it.title.lowercase() }
            if (first != null) {
                val idx = _uiState.value.currentItems.indexOfFirst { it.gameId == first.id }
                if (idx > 0) _uiState.update { it.copy(selectedItemIndex = idx) }
            }
        }
    }

    // ── Derived setup state (B3) ──────────────────────────────────────────────
    //
    // What's still missing between a fresh install and playing a game, derived from the live
    // stores (ROM roots, console cards, emulators) instead of the write-only
    // `library_setup_complete` pref flag — so empty XMB surfaces can name the FIRST unmet step
    // and deep-link to the screen that fixes it.
    private var setupState: com.playfieldportal.feature.launcher.SetupState =
        com.playfieldportal.feature.launcher.SetupState()

    private fun observeSetupState() {
        viewModelScope.launch {
            setupStateProvider.observe().collect { fresh ->
                if (fresh != setupState) {
                    setupState = fresh
                    // Re-render visible empty rows so a gap closing (wizard added a root) swaps
                    // the "Add a ROM folder" prompt for the normal empty-library copy.
                    if (_uiState.value.currentItems.any { it.type == XMBItemType.EMPTY }) {
                        loadItemsForCategory(currentCategory())
                    }
                }
            }
        }
    }

    // ── User interaction ──────────────────────────────────────────────────────

    /**
     * Hook invoked on every gamepad action (and from touch gestures via the activity). The
     * background mode and wave style are now explicit, user-controlled settings, so interaction
     * no longer mutates the wave — but it DOES reset the idle timer and drop the hint pill.
     *
     * The drop is done here rather than left to the idle loop: this comment used to promise the
     * hint "hides immediately on any activity" while the actual hide waited for the next poll
     * tick, leaving the pill up for as much as IDLE_HINT_POLL_MS after a press.
     */
    fun onUserInteraction() {
        lastInteractionMs = SystemClock.elapsedRealtime()
        if (_uiState.value.showContextMenuHint ||
            _uiState.value.showAppDrawerHint ||
            _uiState.value.showSettingsHint ||
            _uiState.value.showNotificationHint ||
            _uiState.value.showMediaHint
        ) {
            _uiState.update {
                it.copy(
                    showContextMenuHint = false,
                    showAppDrawerHint = false,
                    showSettingsHint = false,
                    showNotificationHint = false,
                    showMediaHint = false,
                )
            }
        }
    }

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

    // (The legacy display_icon_style pref is no longer observed — Artwork ▸ Game Icon Display
    // and its Physical Media mode replaced the old PSP Rectangle / Cartridge icon style.)

    // Global icon display mode — tiles resolve against it at render, so a change recomposes
    // every visible tile without rebuilding the item list.
    private fun observeIconDisplayMode() {
        viewModelScope.launch {
            iconDisplayPreferences.modeFlow.collect { mode ->
                _uiState.update { it.copy(iconDisplayMode = mode) }
            }
        }
        viewModelScope.launch {
            iconDisplayPreferences.platformModesFlow.collect { modes ->
                _uiState.update { it.copy(iconDisplayModeByPlatform = modes) }
            }
        }
        viewModelScope.launch {
            iconDisplayPreferences.animatedIconsFlow.collect { enabled ->
                animatedIconsEnabled = enabled
                if (!enabled) _uiState.update { it.copy(focusedGameVideo = null) }
            }
        }
        viewModelScope.launch {
            // User-adjustable rest gate (Artwork ▸ Art Preferences ▸ Video Snap Delay). Each new
            // rest starts from the current value; the in-flight delay simply runs out unchanged.
            iconDisplayPreferences.lingerDelaySecondsFlow.collect { seconds ->
                icon1LingerMs = (seconds * 1_000f).toLong()
            }
        }
        viewModelScope.launch {
            gameLaunchPreferences.directLaunchFlow.collect { direct ->
                _uiState.update { it.copy(directLaunch = direct) }
            }
        }
    }

    // ── ICON1 video snaps ─────────────────────────────────────────────────────

    @Volatile private var animatedIconsEnabled = true

    // Live rest-before-play gate, fed by IconDisplayPreferences.lingerDelaySecondsFlow; starts at
    // the PSP-faithful 1.5 s and tracks the user's Video Snap Delay setting.
    @Volatile private var icon1LingerMs = ICON1_LINGER_MS

    /**
     * PSP ICON1 choreography with a battery conscience: when the cursor RESTS on a game whose
     * tile is in ICON0 mode, wait out the linger gate (scrolling never spins up a decoder),
     * re-check the environment gates, then look up the game's video snap and publish it —
     * [com.playfieldportal.feature.xmb.ui.Icon1VideoOverlay] plays it in-slot. Any focus move,
     * overlay, or mode change clears it immediately (collectLatest cancels the pending linger).
     */
    private fun observeFocusedGameVideo() {
        viewModelScope.launch {
            _uiState
                .map { s ->
                    val item = s.currentItems.getOrNull(s.selectedItemIndex)
                    val eligible = item?.gameId != null && item.isRealGame && !s.hasBlockingOverlay &&
                        resolveIconDisplay(item, s.iconDisplayMode, s.iconDisplayModeByPlatform).mode ==
                            IconDisplayMode.ICON0
                    if (eligible) item.gameId else null
                }
                .distinctUntilChanged()
                .collectLatest { gameId ->
                    if (_uiState.value.focusedGameVideo?.gameId != gameId) {
                        _uiState.update { it.copy(focusedGameVideo = null) }
                    }
                    if (gameId == null) return@collectLatest
                    kotlinx.coroutines.delay(icon1LingerMs)
                    if (!videoSnapsAllowed()) {
                        Timber.d("ICON1: gates vetoed playback for game $gameId (toggle/battery/thermal)")
                        return@collectLatest
                    }
                    // ICON1 (short snap) is preferred; a full VIDEO is a valid fallback since the
                    // icon player clips to 60 s at playback anyway (covers pre-split data too).
                    val uri = artworkStore.find(gameId, com.playfieldportal.feature.artwork.store.ArtworkKind.ICON1)
                        ?: artworkStore.find(gameId, com.playfieldportal.feature.artwork.store.ArtworkKind.VIDEO)
                    if (uri == null) {
                        Timber.d("ICON1: no icon video stored for game $gameId (enable Download Video Snaps + rescrape)")
                        return@collectLatest
                    }
                    Timber.d("ICON1: playing snap for game $gameId from $uri")
                    _uiState.update {
                        it.copy(focusedGameVideo = com.playfieldportal.feature.xmb.ui.FocusedGameVideo(gameId, uri))
                    }
                }
        }
    }

    // Environment gates, checked after the linger: global toggle, Battery Saver, thermal
    // pressure, and low battery while unplugged all veto the decode before it starts.
    private fun videoSnapsAllowed(): Boolean {
        if (!animatedIconsEnabled) return false
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return false
        if ((pm?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_MODERATE) return false
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        if (level in 1 until 20 && bm?.isCharging != true) return false
        return true
    }

    // ── Touch-navigation button preference ────────────────────────────────────────

    private fun observeTouchNavButtonMode() {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                val mode = com.playfieldportal.core.domain.model.TouchNavButtonMode
                    .fromName(prefs[KEY_TOUCH_NAV_BUTTON])
                val sensitivity = com.playfieldportal.core.domain.model.TouchSensitivity
                    .fromName(prefs[KEY_TOUCH_SENSITIVITY])
                val hintEnabled = prefs[KEY_CONTEXT_MENU_HINT] ?: true
                val hintDelaySeconds =
                    (prefs[KEY_CONTEXT_MENU_HINT_DELAY_SECONDS] ?: 2.5f).coerceIn(1f, 5f)
                val legibility = com.playfieldportal.core.domain.model.IconLegibilityStyle
                    .fromName(prefs[KEY_ICON_LEGIBILITY])
                val solidUnfocused = prefs[KEY_SOLID_UNFOCUSED_ICONS] ?: false
                val textShadow = prefs[KEY_TEXT_SHADOW] ?: true
                val listMotion = com.playfieldportal.core.domain.model.XmbListMotion
                    .fromName(prefs[KEY_ITEM_LIST_MOTION])
                val umdMode = com.playfieldportal.core.domain.model.UmdSlotMode
                    .fromName(prefs[KEY_UMD_SLOT_MODE])
                val umdModeChanged = umdMode != _uiState.value.umdSlotMode
                _uiState.update {
                    it.copy(
                        touchNavButtonMode = mode,
                        touchSensitivity = sensitivity,
                        contextMenuHintEnabled = hintEnabled,
                        contextMenuHintDelaySeconds = hintDelaySeconds,
                        iconLegibility = legibility,
                        solidUnfocusedIcons = solidUnfocused,
                        textShadow = textShadow,
                        itemListMotion = listMotion,
                        umdSlotMode = umdMode,
                    )
                }
                // The slot appears or goes at once, on whichever gaming root is on screen.
                if (umdModeChanged && currentCategory()?.isGamingCategory == true &&
                    _uiState.value.currentListKind() == XmbListKind.ROOT
                ) {
                    refreshArrangedList()
                }
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
                // Motion is valid only with its poster (the freeze/failure fallback). Reading
                // the invalid state as "no motion" — never trying to recover it.
                val motionPath = prefs[KEY_MOTION_WALLPAPER]
                    ?.takeIf { validPath != null && java.io.File(it).exists() }
                val motionCrop = motionPath?.let { prefs[KEY_MOTION_CROP] }?.let(PfpThemeStore::decodeMotionCrop)
                _uiState.update {
                    it.copy(customWallpaperPath = validPath, motionWallpaperPath = motionPath, motionCrop = motionCrop)
                }
            }
        }
    }

    // ── Static data ───────────────────────────────────────────────────────────

    companion object {
        private val KEY_WAVE_STYLE        = ThemePrefKeys.WAVE_STYLE
        // Must match DisplaySettingsViewModel — both read/write these wave power-throttle prefs.
        private val KEY_RESPECT_BATTERY   = booleanPreferencesKey("display_battery_saver")
        private val KEY_THERMAL_AWARE     = booleanPreferencesKey("display_thermal_aware")
        private val KEY_COLOR_SCHEME      = ThemePrefKeys.COLOR_SCHEME
        // Custom-theme cascade (docs/theme-format.md): when set, this ARGB accent
        // overrides the preset scheme — wave, gradient, and cursor all derive from it.
        private val KEY_ACCENT_OVERRIDE   = ThemePrefKeys.ACCENT_OVERRIDE
        // Idle context-menu hint: how long to wait before showing, and how often to recheck.
        internal const val IDLE_HINT_DELAY_MS = 2_500L
        internal const val IDLE_HINT_POLL_MS  = 500L
        private val KEY_SETUP_COMPLETE    = booleanPreferencesKey("library_setup_complete")
        // First-run wizard: set the moment the wizard is shown (or silently seeded for installs
        // that already carry configuration), so it only ever auto-opens once.
        private val KEY_INITIAL_SETUP_SEEN = booleanPreferencesKey("initial_setup_seen")
        internal const val INITIAL_SETUP_SCREEN_ID = "settings_initial_setup"
        // The automatic first-run variant of the wizard: Back cannot exit from its first page.
        internal const val INITIAL_SETUP_FIRST_RUN_SCREEN_ID = "settings_initial_setup_first"

        // Pref keys that mean the install already has real configuration — roots, a finished
        // library setup, or connected service credentials. Such installs are upgraders and must
        // never see the first-run wizard. Complemented by hasExistingLibrary() for modern flows
        // (e.g. an Android-apps-only library) that write none of these keys.
        private val EXISTING_CONFIG_STRING_KEYS = listOf(
            stringPreferencesKey("library_rom_root_tree_uris"),
            stringPreferencesKey("library_rom_root_tree_uri"), // legacy single ROM root
            stringPreferencesKey("music_root_tree_uris"),
            stringPreferencesKey("video_root_tree_uris"),
            stringPreferencesKey("photo_root_tree_uris"),
            stringPreferencesKey("artwork_folder_tree_uri"),
            // Service identities/credentials (public parts only — the encrypted secrets always
            // travel with them, so presence of one implies a configured account).
            stringPreferencesKey("sgdb_api_key"),
            stringPreferencesKey("igdb_client_id"),
            stringPreferencesKey("ss_username"),
            stringPreferencesKey("ra_username"),
            stringPreferencesKey("steam_id64"),
        )

        /** Pure decision: does this preferences snapshot already carry user configuration? */
        internal fun hasExistingSetupConfig(prefs: androidx.datastore.preferences.core.Preferences): Boolean =
            prefs[KEY_SETUP_COMPLETE] == true ||
                EXISTING_CONFIG_STRING_KEYS.any { !prefs[it].isNullOrBlank() }
        private val KEY_CUSTOM_WALLPAPER  = ThemePrefKeys.CUSTOM_WALLPAPER
        // Motion is never set without the poster key (invariant enforced at the write sites).
        private val KEY_MOTION_WALLPAPER = ThemePrefKeys.MOTION_WALLPAPER
        private val KEY_MOTION_CROP = ThemePrefKeys.MOTION_CROP
        // Must match DisplaySettingsViewModel — the Boot Sequence toggles. Before this both keys
        // were written by settings and read by nothing: the boot animation always played.
        private val KEY_SHOW_BOOT       = booleanPreferencesKey("display_show_boot")
        private val KEY_BOOT_ON_RESUME  = booleanPreferencesKey("display_boot_on_resume")
        // Must match DisplaySettingsViewModel.KEY_TOUCH_NAV_BUTTON — both read/write this pref.
        private val KEY_TOUCH_NAV_BUTTON  = stringPreferencesKey("interface_touch_nav_button")
        // Must match DisplaySettingsViewModel.KEY_CONTEXT_MENU_HINT — both read/write this pref.
        private val KEY_CONTEXT_MENU_HINT = booleanPreferencesKey("interface_context_menu_hint")
        private val KEY_CONTEXT_MENU_HINT_DELAY_SECONDS =
            floatPreferencesKey("interface_context_menu_hint_delay_seconds")
        // Must match DisplaySettingsViewModel.KEY_TOUCH_SENSITIVITY — both read/write this pref.
        private val KEY_TOUCH_SENSITIVITY = stringPreferencesKey("interface_touch_sensitivity")
        private val KEY_ICON_LEGIBILITY = ThemePrefKeys.ICON_LEGIBILITY
        private val KEY_SOLID_UNFOCUSED_ICONS = ThemePrefKeys.SOLID_UNFOCUSED_ICONS
        // Must match DisplaySettingsViewModel.KEY_TEXT_SHADOW — both read/write this pref.
        private val KEY_TEXT_SHADOW = booleanPreferencesKey("display_text_shadow")
        // Must match DisplaySettingsViewModel.KEY_ITEM_LIST_MOTION — both read/write this pref.
        private val KEY_ITEM_LIST_MOTION = stringPreferencesKey("display_item_list_motion")
        // Must match DisplaySettingsViewModel.KEY_UMD_SLOT_MODE — both read/write this pref.
        private val KEY_UMD_SLOT_MODE = stringPreferencesKey("display_umd_slot_mode")
        // ICON1 linger default (1.5 s) — the user can adjust the delay under Artwork ▸ Art
        // Preferences ▸ Video Snap Delay. Rest-then-animate matches the PSP's choreography and
        // guarantees scrolling through the row never spins up a video decoder.
        private const val ICON1_LINGER_MS = 1_500L
        private const val SETUP_ITEM_ID = "library_setup"
        internal const val UMD_SLOT_ITEM_ID = "umd_slot"
        internal const val CATEGORY_CARD_ITEM_ID = "category_card"
        private const val NO_CONSOLES_ITEM_ID = "no_consoles"
        // B3: empty All Games row while setup still has an unmet step (names + fixes the gap).
        private const val SETUP_GAP_ITEM_ID = "setup_gap"
        private const val NO_GAMES_ITEM_ID    = "no_games"
        private const val EMPTY_COLLECTION_ITEM_ID = "empty_collection"
        private const val EMPTY_FAVORITES_ITEM_ID = "empty_favorites"
        private const val EMPTY_CATEGORY_ITEM_ID = "empty_category"
        // Shiba Coins hub root rows.
        private const val ACH_CONNECT_ITEM_ID = "ach_connect"
        internal const val ACH_SUMMARY_ITEM_ID = "ach_summary"
        internal const val ACH_ALL_ITEM_ID      = "ach_all"
        internal const val ACH_UNTRACKED_ITEM_ID = "ach_untracked"
        internal const val ALL_GAMES_ITEM_ID = "all_games"
        internal const val ALL_GAMES_PLATFORM_ID = "__all_games__"
        private const val FAVORITES_ITEM_ID = "favorites_folder"
        internal const val FAVORITES_PLATFORM_ID = "__favorites__"
        private const val MISSING_ITEM_ID = "missing_folder"
        internal const val MISSING_PLATFORM_ID = "__missing__"
        private const val EMPTY_MISSING_ITEM_ID = "empty_missing"
        // Shown as each missing row's subtitle. Phrased around the scan rather than the file
        // ("File not found" alone reads as permanent) because dropping the file back reactivates it.
        private const val MISSING_REASON = "File not found on last scan"
        private const val ADD_APPS_ITEM_ID = "add_apps"
        private const val ADD_GAMES_ITEM_ID = "add_games"
        private const val FIND_GAMES_ITEM_ID = "find_games"
        // Platform id whose library is built from installed apps (picker) instead of ROM scans.
        internal const val ANDROID_PLATFORM_ID = "android"
        // Sentinel platform for app rows that merely BACK a category app's artwork / favorite /
        // collection membership. They reference an app by package but are NOT in the Android
        // library, so they use this id instead of "android" to stay out of observeByPlatform.
        private const val APP_SHORTCUT_PLATFORM_ID = "app_shortcut"
        // Virtual card holding PC-launcher game imports (harvest / folder scan / add-by-ID).
        internal const val WINDOWS_PLATFORM_ID = "windows"

        // One step of the music player's seek, on the D-pad and on the touch transport alike.
        // The same 10s the video player takes.
        private const val MUSIC_SEEK_STEP_MS = 10_000

        // The video player's CONTROLS_TIMEOUT_MS value. The two built-in players must not idle out
        // at different speeds — a user who learns one learns the other.
        private const val MUSIC_CHROME_TIMEOUT_MS = 3_500L

        // Music category synthetic rows / drill ids.
        private const val ADD_MUSIC_FOLDER_ITEM_ID = "add_music_folder"
        internal const val ALL_MUSIC_ITEM_ID = "all_music"
        internal const val NOW_PLAYING_ITEM_ID = "now_playing"
        private const val PLAYLISTS_ITEM_ID = "playlists"
        private const val MUSIC_APPS_ITEM_ID = "music_apps_item"
        private const val ADD_MUSIC_APPS_ITEM_ID = "add_music_apps"
        internal const val CREATE_PLAYLIST_ITEM_ID = "create_playlist"
        private const val ADD_TRACKS_ITEM_ID = "add_tracks"
        private const val EMPTY_PLAYLIST_ITEM_ID = "empty_playlist"
        // The "Apps" sections (Music / Video / Photo) are backed by the REAL built-in media
        // categories — not hidden pseudo-categories. Apps auto-populate from the classifier
        // (installed music / video / photo apps) and any manual picks live in the same real
        // category, so there is nothing hidden for users to tamper with in Category settings.
        private const val MUSIC_APPS_CATEGORY_ID = "music"
        // Video root item ids.
        internal const val ALL_VIDEOS_ITEM_ID = "all_videos"
        private const val VIDEO_COLLECTIONS_ITEM_ID = "video_collections"
        private const val RECENTLY_WATCHED_ITEM_ID = "recently_watched"
        private const val FAVORITE_VIDEOS_ITEM_ID = "favorite_videos"
        private const val VIDEO_PLAYLISTS_ITEM_ID = "video_playlists"
        internal const val CREATE_VIDEO_PLAYLIST_ITEM_ID = "create_video_playlist"
        private const val VIDEO_LIBRARIES_ITEM_ID = "video_libraries"
        private const val VIDEO_APPS_ITEM_ID = "video_apps_item"
        private const val ADD_VIDEOS_ITEM_ID = "add_videos"
        private const val ADD_VIDEO_APPS_ITEM_ID = "add_video_apps"
        private const val VIDEO_APPS_CATEGORY_ID = "videos"
        // Photo root item ids.
        internal const val ALL_PHOTOS_ITEM_ID = "all_photos"
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

        // Settings root: the Android system-settings leaf plus the six nested L1 sections
        // (settingsSectionItems supplies each section's L2 rows). Section rows drill into the
        // two-pane flyout; any other id opens its screen overlay directly. `internal` so the
        // hierarchy unit tests can assert the exact root order.
        internal val SETTINGS_ROOT_ITEMS = listOf(
            XMBItem(id = ANDROID_SETTINGS_ITEM_ID, title = "Android Settings", subtitle = "Opens device settings"),
        ) + SettingsSection.entries.map { XMBItem(id = it.id, title = it.title, subtitle = it.subtitle) }
    }

    // Rebuilds the bar from the canonical built-in definitions (name/icon/position/order) merged with
    // per-row DB fields. [categories] is the *visible* set, so a built-in the user hid is simply
    // absent from [byId] and dropped here — that's what makes "Show on Bar" work for Main categories.
    // Settings is the one exception: it's always kept, since it's the only route back into category
    // management and hiding it would soft-lock the user out.

    private fun defaultXmbCategoryIndex(categories: List<Category>): Int =
        categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
            .takeIf { it >= 0 }
            ?: 0
}

/**
 * The presses an open settings screen receives. BACK is forwarded into the settings layer (not
 * handled here) so the active screen can do one-level-up navigation through its own back handler
 * — exactly like the on-screen Back button; the screen calls onCloseSettingsScreen() only at its
 * top level. Left/Right, the two secondary face buttons and the shoulders are ignored by the
 * scaffold's default nav but reachable via onInterceptAction — screens with horizontal strips,
 * per-row menus or shortcuts (Themes, Sound) consume them there, and Initial Setup's RB Skip.
 * Both X/Y presses arrive whichever way the layout binds them, so both are forwarded.
 */
internal fun forwardsToSettings(action: GamepadAction): Boolean = when (action) {
    GamepadAction.BACK,
    GamepadAction.NAVIGATE_UP,
    GamepadAction.NAVIGATE_DOWN,
    GamepadAction.NAVIGATE_LEFT,
    GamepadAction.NAVIGATE_RIGHT,
    GamepadAction.OPEN_CONTEXT_MENU,
    GamepadAction.CHANGE_SORT,
    GamepadAction.PREV_CATEGORY,
    GamepadAction.NEXT_CATEGORY,
    GamepadAction.SELECT -> true
    // HOME is the shell's; Shift and Caps belong to the virtual keyboard, which takes them first.
    GamepadAction.HOME, GamepadAction.SHIFT, GamepadAction.CAPS_LOCK -> false
}

/** True when the open virtual keyboard took [action] — every press, while one is open. */
internal fun keyboardCaptures(
    keyboard: com.playfieldportal.core.ui.keyboard.VirtualKeyboardController,
    action: GamepadAction,
): Boolean = keyboard.onGamepadAction(action)

/** The shell's single input-source owner, mirrored into the keyboard for fields an action opens. */
internal fun mirrorInputSource(
    keyboard: com.playfieldportal.core.ui.keyboard.VirtualKeyboardController,
    touch: Boolean,
) {
    keyboard.inputSource =
        if (touch) com.playfieldportal.core.ui.keyboard.InputSource.TOUCH
        else com.playfieldportal.core.ui.keyboard.InputSource.CONTROLLER
}
