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
import com.playfieldportal.feature.artwork.api.ArtworkRepository
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
import com.playfieldportal.feature.launcher.supportsPlatform
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

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
    // Steam-style MEDIA PREVIEW strip: videos first, then images. -1 = strip not focused.
    val detailMedia: List<DetailMedia> = emptyList(),
    val mediaFocus: Int = -1,
    val imageViewerUri: String? = null,     // fullscreen image preview overlay

    // ── Minimal one-screen navigation ─────────────────────────────────────
    val mainFocus: Int = 0,
    // D-pad page scrolling: DOWN past the button row scrolls the page in steps so gamepad
    // users can read the full info/description area; UP unwinds before refocusing Play.
    val pageScrollSteps: Int = 0,
    // -1 means the main button row is focused; otherwise this is the focused disc member.
    val discFocusIndex: Int = -1,
    val showOptions: Boolean = false,
    val optionsIndex: Int = 0,
    val mediaUris: List<String> = emptyList(),
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

    // The options rows actually shown: the emulator picker is meaningless for package-backed
    // entries, so its row is hidden there. Index-based navigation must use THIS list.
    val visibleActions: List<DetailAction>
        get() = if (isPackageBacked) DetailAction.entries.filter { it != DetailAction.EMULATOR }
                else DetailAction.entries
}

// ── Options menu ──────────────────────────────────────────────────────────────
enum class DetailAction(val label: String) {
    FAVORITE("Favorite"),
    COLLECTIONS("Collections"),
    ARTWORK("Artwork"),
    SAVES("Saves"),
    EMULATOR("Emulator"),
    MANUAL("Manual"),
    REFRESH("Refresh"),
    RENAME("Edit Title"),
    EDIT("Edit Note"),
    LOCATION("Open Location"),
    REMOVE("Remove"),
}

// 0 = Launch, then the square row: 1 = Options, 2 = Artwork, 3 = Manual.
const val MAIN_FOCUS_LAST = 3
// The Shiba Coins strip sits below the button row as focus index 4.
const val MAIN_FOCUS_COINS = 4

// Upper bound for D-pad page scrolling — generous enough for the longest descriptions; the
// screen clamps to the real content height, so overshoot is harmless.
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
    private val intentResolver: EmulatorIntentResolver,
    private val artworkRepository: ArtworkRepository,
    private val artworkStore: ArtworkStore,
    private val artworkRecordDao: com.playfieldportal.core.data.database.dao.ArtworkRecordDao,
    private val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
    private val launcherShortcutRepository: com.playfieldportal.feature.appbar.LauncherShortcutRepository,
    private val achievementRepository: com.playfieldportal.feature.achievements.AchievementController,
    private val launchDispatcher: com.playfieldportal.feature.launcher.LaunchDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

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
            )
        }
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
            _uiState.update {
                it.copy(
                    isLoading = true,
                    closed = false,
                    showOptions = false,
                    confirmRemove = false,
                    isEditingNote = false,
                    isEditingTitle = false,
                )
            }
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
            _uiState.update {
                it.copy(
                    game              = game,
                    platform          = platform,
                    discMembers       = discMembers,
                    selectedDiscId    = selectedDisc?.id,
                    noteText          = game?.userNote ?: "",
                    mediaUris         = mediaOf(game),
                    resolvedLaunch    = resolvedLaunch,
                    // Media strip plays the full VIDEO; ICON1 (icon snap) is a fallback so a game
                    // that only has a snap still shows a video card.
                    videoUri          = game?.let { g ->
                        artworkStore.find(g.id, ArtworkKind.VIDEO) ?: artworkStore.find(g.id, ArtworkKind.ICON1)
                    },
                    detailMedia       = if (game == null) emptyList() else buildList {
                        val vid = artworkStore.find(game.id, ArtworkKind.VIDEO) ?: artworkStore.find(game.id, ArtworkKind.ICON1)
                        vid?.let { add(DetailMedia(it, isVideo = true)) }
                        artworkStore.find(game.id, ArtworkKind.SCREENSHOT)?.let { add(DetailMedia(it, isVideo = false)) }
                        artworkStore.find(game.id, ArtworkKind.TITLESCREEN)?.let { add(DetailMedia(it, isVideo = false)) }
                    },
                    hasManual         = game?.let { g -> artworkStore.find(g.id, ArtworkKind.MANUAL) } != null,
                    showVideoPlayer   = false,
                    mediaFocus        = -1,
                    imageViewerUri    = null,
                    isLoading          = false,
                    mainFocus          = 0,
                    pageScrollSteps    = 0,
                    discFocusIndex     = -1,
                    manualViewerUri    = null,
                    showOptions        = false,
                    optionsIndex       = 0,
                    confirmRemove      = false,
                    isEditingNote      = false,
                    isEditingTitle     = false,
                    actionMessage      = null,
                    launchError        = null,
                    closed             = false,
                )
            }
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

    fun selectDiscAt(index: Int) {
        _uiState.value.discMembers.getOrNull(index)?.id?.let(::selectDisc)
    }

    // ── Controller input ──────────────────────────────────────────────────

    fun handleGamepadAction(action: GamepadAction) {
        val s = _uiState.value

        // The manual viewer is the topmost overlay — it owns all input while open.
        if (s.imageViewerUri != null) {
            if (action == GamepadAction.BACK || action == GamepadAction.SELECT) {
                _uiState.update { it.copy(imageViewerUri = null) }
            }
            return
        }
        if (s.showArtworkStudio) return   // actions are forwarded to the Studio's own VM
        if (s.showVideoPlayer) {
            // Fullscreen snap player: Back (or Confirm) closes; everything else is consumed.
            if (action == GamepadAction.BACK || action == GamepadAction.SELECT) closeVideoPlayer()
            return
        }
        if (s.manualViewerUri != null) {
            handleManualViewerInput(action)
            return
        }

        if (s.confirmRemove) {
            when (action) {
                GamepadAction.SELECT -> confirmRemoveGame()
                GamepadAction.BACK   -> _uiState.update { it.copy(confirmRemove = false) }
                else -> Unit
            }
            return
        }

        if (s.isEditingNote) {
            if (action == GamepadAction.BACK) cancelNote()
            return
        }

        if (s.isEditingTitle) {
            if (action == GamepadAction.BACK) cancelTitleEdit()
            return
        }

        if (s.showEmulatorPicker) {
            handleEmulatorPickerInput(action)
            return
        }

        if (s.collectionPicker.visible) {
            handleCollectionPickerInput(action)
            return
        }

        if (s.showOptions) {
            val actions = s.visibleActions
            val count = actions.size
            when (action) {
                GamepadAction.NAVIGATE_UP   -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex - 1).coerceIn(0, count - 1)) }
                GamepadAction.NAVIGATE_DOWN -> _uiState.update { it.copy(optionsIndex = (it.optionsIndex + 1).coerceIn(0, count - 1)) }
                GamepadAction.SELECT        -> activateAction(actions[s.optionsIndex.coerceIn(0, count - 1)])
                GamepadAction.BACK          -> closeOptions()
                else -> Unit
            }
            return
        }

        // Main page focus: 0 = Play, 1 = Options (gear), 2 = Artwork (brush).
        when (action) {
            GamepadAction.NAVIGATE_LEFT  -> _uiState.update {
                when {
                    it.mediaFocus >= 0 -> it.copy(mediaFocus = (it.mediaFocus - 1).coerceAtLeast(0), actionMessage = null)
                    it.discFocusIndex >= 0 -> it.copy(discFocusIndex = (it.discFocusIndex - 1).coerceAtLeast(0), actionMessage = null)
                    else -> it.copy(mainFocus = (it.mainFocus - 1).coerceIn(0, MAIN_FOCUS_LAST), actionMessage = null)
                }
            }
            GamepadAction.NAVIGATE_RIGHT -> _uiState.update {
                when {
                    it.mediaFocus >= 0 -> it.copy(mediaFocus = (it.mediaFocus + 1).coerceAtMost(it.detailMedia.lastIndex), actionMessage = null)
                    it.discFocusIndex >= 0 -> it.copy(discFocusIndex = (it.discFocusIndex + 1).coerceAtMost(it.discMembers.lastIndex), actionMessage = null)
                    else -> it.copy(mainFocus = (it.mainFocus + 1).coerceIn(0, MAIN_FOCUS_LAST), actionMessage = null)
                }
            }
            GamepadAction.NAVIGATE_UP    -> _uiState.update {
                if (it.mediaFocus >= 0) return@update it.copy(mediaFocus = -1, pageScrollSteps = 0, actionMessage = null)
                if (it.discFocusIndex >= 0) return@update it.copy(discFocusIndex = -1, mainFocus = 1, actionMessage = null)
                // One press rewinds the whole page scroll; the next lands on Launch — no more
                // unwinding step by step before focus comes back.
                if (it.pageScrollSteps > 0) return@update it.copy(pageScrollSteps = 0, actionMessage = null)
                // From the coin strip, UP returns to the button row rather than jumping to Launch.
                if (it.mainFocus == MAIN_FOCUS_COINS) return@update it.copy(mainFocus = 1, actionMessage = null)
                it.copy(mainFocus = 0, actionMessage = null)
            }
            GamepadAction.NAVIGATE_DOWN  -> _uiState.update {
                when {
                    // First DOWN moves to the button row.
                    it.mainFocus == 0 -> it.copy(mainFocus = 1, actionMessage = null)
                    // From the button row, enter the disc picker before the lower strips.
                    it.discFocusIndex < 0 && it.mainFocus in 1..MAIN_FOCUS_LAST && it.showDiscPicker ->
                        it.copy(discFocusIndex = 0, actionMessage = null)
                    // From the button row, DOWN lands on the Shiba Coins strip — except for
                    // Android games, which never have achievements and render no strip.
                    it.mainFocus in 1..MAIN_FOCUS_LAST && it.game?.platformId != "android" ->
                        it.copy(mainFocus = MAIN_FOCUS_COINS, actionMessage = null)
                    // From the disc picker, move across members and then continue down.
                    it.discFocusIndex >= 0 && it.discFocusIndex < it.discMembers.lastIndex ->
                        it.copy(discFocusIndex = it.discFocusIndex + 1, actionMessage = null)
                    it.discFocusIndex >= 0 && it.game?.platformId != "android" ->
                        it.copy(discFocusIndex = -1, mainFocus = MAIN_FOCUS_COINS, actionMessage = null)
                    // From the strip, DOWN enters the media strip when there is one
                    // (page scrolls to the bottom so the strip is visible)...
                    it.mediaFocus < 0 && it.detailMedia.isNotEmpty() ->
                        it.copy(mediaFocus = 0, pageScrollSteps = MAX_PAGE_SCROLL_STEPS, actionMessage = null)
                    // ...otherwise (or once in the strip) further DOWNs scroll the page.
                    else -> it.copy(pageScrollSteps = (it.pageScrollSteps + 1).coerceAtMost(MAX_PAGE_SCROLL_STEPS), actionMessage = null)
                }
            }
            GamepadAction.SELECT        -> if (s.mediaFocus >= 0) {
                openMediaAt(s.mediaFocus)
            } else if (s.discFocusIndex >= 0) {
                selectDiscAt(s.discFocusIndex)
            } else when (s.mainFocus) {
                0 -> { Timber.d("Controller SELECT activated Launch"); launch() }
                1 -> openOptions()
                2 -> openArtworkManager()
                MAIN_FOCUS_COINS -> requestOpenCoins()
                else -> onManualClicked()
            }
            // Y / Triangle opens the Options context menu directly, from anywhere on the page.
            GamepadAction.OPEN_CONTEXT_MENU -> openOptions()
            GamepadAction.BACK          -> _uiState.update { it.copy(closed = true) }
            else -> Unit
        }
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
            DetailAction.REFRESH   -> fetchArtwork()
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
            // Internal store first (scraped manuals), then the portable media library
            // ({platform}/manuals/{name}.pdf, tracked by the game's artwork record).
            val path = artworkStore.find(game.id, ArtworkKind.MANUAL)
                ?: artworkRecordDao.get(game.id, ArtworkKind.MANUAL.name)?.documentUri
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

    // playSound is false for direct-launch auto-fire: the XMB icon confirm already played the
    // launch sfx, so replaying it here would double it. Manual Play (button / controller SELECT)
    // leaves it true.
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
        if (playSound) menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.LAUNCH)
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
        // detected as not installed) are excluded so the fallback never lands on one.
        val platformProfiles =
            installed.filter { it.isAvailable && it.supportsPlatform(platformId) }.byLaunchPreference()
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

    fun onEmulatorPickerMove(delta: Int) {
        val last = _uiState.value.emulatorPickerOptions.lastIndex
        if (last < 0) return
        _uiState.update { it.copy(emulatorPickerIndex = (it.emulatorPickerIndex + delta).coerceIn(0, last)) }
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

    private fun handleEmulatorPickerInput(action: GamepadAction) {
        when (action) {
            GamepadAction.NAVIGATE_UP   -> onEmulatorPickerMove(-1)
            GamepadAction.NAVIGATE_DOWN -> onEmulatorPickerMove(+1)
            GamepadAction.SELECT        -> {
                val s = _uiState.value
                val profile = s.emulatorPickerOptions.getOrNull(s.emulatorPickerIndex) ?: return
                confirmEmulatorPick(profile.id)
            }
            GamepadAction.BACK          -> closeEmulatorPicker()
            else -> Unit
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

    fun onCollectionRowClick(index: Int) {
        _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(selectedIndex = index)) }
        activateCollectionRow()
    }

    private fun moveCollectionPicker(delta: Int) {
        _uiState.update {
            val cp = it.collectionPicker
            // rowCount can be 0 while the picker's options load — no-op rather than an
            // IllegalArgumentException from coercing into the empty range 0..-1.
            if (cp.rowCount <= 0) return@update it
            it.copy(collectionPicker = cp.copy(
                selectedIndex = (cp.selectedIndex + delta).coerceIn(0, cp.rowCount - 1),
            ))
        }
    }

    private fun activateCollectionRow() {
        val cp = _uiState.value.collectionPicker
        val gameId = _uiState.value.game?.id ?: return
        if (cp.isCreateRow) {
            _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(showCreateDialog = true, createText = "")) }
            return
        }
        val option = cp.options.getOrNull(cp.selectedIndex) ?: return
        viewModelScope.launch {
            collectionRepository.toggleGame(option.id, gameId)
            _uiState.update { it.copy(collectionPicker = it.collectionPicker.copy(options = buildCollectionOptions(gameId))) }
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

    private fun handleCollectionPickerInput(action: GamepadAction) {
        if (_uiState.value.collectionPicker.showCreateDialog) {
            if (action == GamepadAction.BACK) cancelCreateCollection()
            return // text entry needs the keyboard; SELECT is handled by the dialog button
        }
        when (action) {
            GamepadAction.NAVIGATE_UP   -> moveCollectionPicker(-1)
            GamepadAction.NAVIGATE_DOWN -> moveCollectionPicker(+1)
            GamepadAction.SELECT        -> activateCollectionRow()
            GamepadAction.BACK          -> closeCollectionPicker()
            else -> Unit
        }
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

    // ── Artwork — scraper refresh ─────────────────────────────────────────

    fun fetchArtwork() {
        val game = _uiState.value.game ?: return
        if (_uiState.value.isFetchingArtwork) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingArtwork = true, artworkMessage = null) }
            val result = artworkRepository.fetchArtworkForGame(game.id, game.title)
            val updated = gameRepository.getById(game.id)
            // Re-scraped files reuse stable names, so evict only THIS game's refs (old and new)
            // from the image cache. Never clearCache() here — that is the library-wide reset
            // behind Settings > Artwork > Clear All Artwork.
            artworkRepository.evictFromImageCache((artRefsOf(game) + artRefsOf(updated)).toSet())
            _uiState.update {
                it.copy(
                    game              = updated ?: it.game,
                    mediaUris         = mediaOf(updated ?: it.game),
                    isFetchingArtwork = false,
                    artworkMessage    = when {
                        result.success -> "Artwork updated"
                        result.skipped -> "Already has artwork"
                        else           -> result.errorMessage ?: "Artwork fetch failed"
                    },
                )
            }
        }
    }

    fun dismissArtworkMessage() = _uiState.update { it.copy(artworkMessage = null) }
    fun dismissLaunchError()    = _uiState.update { it.copy(launchError = null) }

    private fun mediaOf(game: Game?): List<String> =
        listOfNotNull(game?.heroUri, game?.artworkUri, game?.logoUri).distinct()

    // Every artwork column a scrape can rewrite — the eviction set for a single-game refresh.
    private fun artRefsOf(game: Game?): List<String> = listOfNotNull(
        game?.artworkUri, game?.heroUri, game?.logoUri, game?.iconUri,
        game?.boxArtUri, game?.physicalMediaUri, game?.box3dUri,
    )
}
