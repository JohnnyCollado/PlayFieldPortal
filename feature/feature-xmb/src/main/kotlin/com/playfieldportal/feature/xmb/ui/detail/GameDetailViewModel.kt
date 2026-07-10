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
import com.playfieldportal.feature.artwork.TheGamesDbApi
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.SgdbArtType
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import com.playfieldportal.feature.launcher.EmulatorIntentResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

// ── Artwork Manager focus zones ───────────────────────────────────────────────
enum class ArtworkManagerFocus { TYPE_TABS, SOURCE_ROW, ART_GRID }

// Source row indices
const val SOURCE_SGDB  = 0
const val SOURCE_TGDB  = 1
const val SOURCE_IGDB  = 2
const val SOURCE_LOCAL = 3
const val SOURCE_CLEAR = 4
const val SOURCE_COUNT = 5

// True for sources that populate the art grid; false for action-only sources.
fun isDataSource(index: Int) = index !in listOf(SOURCE_LOCAL, SOURCE_CLEAR)

// ── Unified artwork picker item ───────────────────────────────────────────────
//
// Used by all grid-based sources (SGDB, IGDB, TheGamesDB).
// SGDB items carry a thumbUrl for faster thumbnail loading; other sources use
// the full URL as thumb since no separate thumbnail endpoint is available.
data class ArtPickerItem(
    val url: String,
    val thumbUrl: String? = null,
    val label: String? = null,
)

// ── UI state ──────────────────────────────────────────────────────────────────
data class GameDetailUiState(
    val game: Game? = null,
    val platform: PlatformEntity? = null,
    val isLoading: Boolean = true,
    val isEditingNote: Boolean = false,
    val noteText: String = "",
    val isFetchingArtwork: Boolean = false,
    val artworkMessage: String? = null,
    val launchError: String? = null,

    // ── Minimal one-screen navigation ─────────────────────────────────────
    val mainFocus: Int = 0,
    // D-pad page scrolling: DOWN past the button row scrolls the page in steps so gamepad
    // users can read the full info/description area; UP unwinds before refocusing Play.
    val pageScrollSteps: Int = 0,
    val showOptions: Boolean = false,
    val optionsIndex: Int = 0,
    val mediaUris: List<String> = emptyList(),
    val emulatorName: String? = null,
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

    // ── XMB Artwork Manager ───────────────────────────────────────────────
    val showArtworkManager: Boolean = false,
    val artworkTab: ArtworkType = ArtworkType.ICON,
    val artworkFocus: ArtworkManagerFocus = ArtworkManagerFocus.TYPE_TABS,
    val artworkSourceFocus: Int = SOURCE_SGDB,
    val artworkPickerItems: List<ArtPickerItem> = emptyList(),   // shared grid for all data sources
    val artworkPickerIndex: Int = 0,
    val artworkPickerLoading: Boolean = false,
    val artworkPickerError: String? = null,
    val artworkPendingLocal: ArtworkType? = null,
    val artworkIsProcessing: Boolean = false,

    // ── Emulator picker ───────────────────────────────────────────────────
    val showEmulatorPicker: Boolean = false,
    val emulatorPickerOptions: List<EmulatorProfile> = emptyList(),
    val emulatorPickerIndex: Int = 0,

    // ── Add-to-collection picker ──────────────────────────────────────────
    val collectionPicker: CollectionPickerUi = CollectionPickerUi(),
) {
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

// Keep old field names as transparent aliases so existing code compiles without changes.
val GameDetailUiState.artworkSgdbItems   get() = artworkPickerItems
val GameDetailUiState.artworkSgdbIndex   get() = artworkPickerIndex
val GameDetailUiState.artworkSgdbLoading get() = artworkPickerLoading
val GameDetailUiState.artworkSgdbError   get() = artworkPickerError

private data class ResolvedLaunchProfile(
    val profile: EmulatorProfile,
    val source: String,
)

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

// Last focusable index on the main page (0 = Play, 1 = Options gear, 2 = Artwork brush).
const val MAIN_FOCUS_LAST = 2

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
    private val steamGridDb: SteamGridDbApi,
    private val igdbApi: IgdbApi,
    private val theGamesDb: TheGamesDbApi,
    private val artworkStore: ArtworkStore,
    private val artworkIndexDao: com.playfieldportal.core.data.database.dao.ArtworkIndexDao,
    private val menuSound: com.playfieldportal.core.ui.sound.MenuSoundPlayer,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
    private val launcherShortcutRepository: com.playfieldportal.feature.appbar.LauncherShortcutRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    private val _launchEffect = Channel<Intent>(capacity = Channel.BUFFERED)
    val launchEffect = _launchEffect.receiveAsFlow()

    fun prepareForOpen() {
        _uiState.update {
            it.copy(
                closed = false,
                showOptions = false,
                confirmRemove = false,
                isEditingNote = false,
                isEditingTitle = false,
                showArtworkManager = false,
                actionMessage = null,
                launchError = null,
            )
        }
    }

    fun loadGame(id: Long) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    closed = false,
                    showOptions = false,
                    confirmRemove = false,
                    isEditingNote = false,
                    isEditingTitle = false,
                    showArtworkManager = false,
                )
            }
            val game     = gameRepository.getById(id)
            val platform = game?.let { platformDao.getById(it.platformId) }
            val emulator = game?.let { resolveLaunchProfile(it, platform).getOrNull()?.profile?.name }
            _uiState.update {
                it.copy(
                    game              = game,
                    platform          = platform,
                    noteText          = game?.userNote ?: "",
                    mediaUris         = mediaOf(game),
                    emulatorName      = emulator,
                    isLoading          = false,
                    mainFocus          = 0,
                    pageScrollSteps    = 0,
                    manualViewerUri    = null,
                    showOptions        = false,
                    optionsIndex       = 0,
                    confirmRemove      = false,
                    isEditingNote      = false,
                    isEditingTitle     = false,
                    showArtworkManager = false,
                    actionMessage      = null,
                    launchError        = null,
                    closed             = false,
                )
            }
        }
    }

    // ── Controller input ──────────────────────────────────────────────────

    fun handleGamepadAction(action: GamepadAction) {
        val s = _uiState.value

        // The manual viewer is the topmost overlay — it owns all input while open.
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

        if (s.showArtworkManager) {
            handleArtworkManagerInput(action)
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
            GamepadAction.NAVIGATE_LEFT  -> _uiState.update { it.copy(mainFocus = (it.mainFocus - 1).coerceIn(0, MAIN_FOCUS_LAST), actionMessage = null) }
            GamepadAction.NAVIGATE_RIGHT -> _uiState.update { it.copy(mainFocus = (it.mainFocus + 1).coerceIn(0, MAIN_FOCUS_LAST), actionMessage = null) }
            GamepadAction.NAVIGATE_UP    -> _uiState.update {
                if (it.pageScrollSteps > 0) it.copy(pageScrollSteps = it.pageScrollSteps - 1, actionMessage = null)
                else it.copy(mainFocus = 0, actionMessage = null)
            }
            GamepadAction.NAVIGATE_DOWN  -> _uiState.update {
                // First DOWN moves to the button row; further DOWNs scroll the page.
                if (it.mainFocus == 0) it.copy(mainFocus = 1, actionMessage = null)
                else it.copy(pageScrollSteps = (it.pageScrollSteps + 1).coerceAtMost(MAX_PAGE_SCROLL_STEPS), actionMessage = null)
            }
            GamepadAction.SELECT        -> when (s.mainFocus) {
                0 -> { Timber.d("Controller SELECT activated Play"); launch() }
                1 -> openOptions()
                else -> openArtworkManager()
            }
            // Y / Triangle opens the Options context menu directly, from anywhere on the page.
            GamepadAction.BUTTON_Y, GamepadAction.LONG_PRESS -> openOptions()
            GamepadAction.BACK          -> _uiState.update { it.copy(closed = true) }
            else -> Unit
        }
    }

    // ── Artwork Manager input routing ─────────────────────────────────────

    private fun handleArtworkManagerInput(action: GamepadAction) {
        val s = _uiState.value
        when (s.artworkFocus) {
            ArtworkManagerFocus.TYPE_TABS -> when (action) {
                GamepadAction.NAVIGATE_LEFT  -> cycleArtworkTab(-1)
                GamepadAction.NAVIGATE_RIGHT -> cycleArtworkTab(+1)
                GamepadAction.NAVIGATE_DOWN,
                GamepadAction.SELECT         -> artworkManagerFocusSourceRow()
                GamepadAction.BACK           -> closeArtworkManager()
                else -> Unit
            }
            ArtworkManagerFocus.SOURCE_ROW -> when (action) {
                GamepadAction.NAVIGATE_LEFT  -> _uiState.update {
                    it.copy(artworkSourceFocus = (it.artworkSourceFocus - 1).coerceIn(0, SOURCE_COUNT - 1))
                }
                GamepadAction.NAVIGATE_RIGHT -> _uiState.update {
                    it.copy(artworkSourceFocus = (it.artworkSourceFocus + 1).coerceIn(0, SOURCE_COUNT - 1))
                }
                GamepadAction.NAVIGATE_UP    -> _uiState.update { it.copy(artworkFocus = ArtworkManagerFocus.TYPE_TABS) }
                GamepadAction.NAVIGATE_DOWN  -> {
                    if (isDataSource(s.artworkSourceFocus) && s.artworkPickerItems.isNotEmpty()) {
                        _uiState.update { it.copy(artworkFocus = ArtworkManagerFocus.ART_GRID) }
                    } else if (isDataSource(s.artworkSourceFocus)) {
                        loadSourceForCurrentTab(s.artworkSourceFocus)
                    }
                }
                GamepadAction.SELECT -> activateArtworkSource()
                GamepadAction.BACK   -> _uiState.update { it.copy(artworkFocus = ArtworkManagerFocus.TYPE_TABS) }
                else -> Unit
            }
            ArtworkManagerFocus.ART_GRID -> when (action) {
                GamepadAction.NAVIGATE_LEFT  -> moveArtworkGridFocus(-1)
                GamepadAction.NAVIGATE_RIGHT -> moveArtworkGridFocus(+1)
                GamepadAction.NAVIGATE_UP    -> _uiState.update { it.copy(artworkFocus = ArtworkManagerFocus.SOURCE_ROW) }
                GamepadAction.SELECT         -> pickFocusedArtwork()
                GamepadAction.BACK           -> _uiState.update { it.copy(artworkFocus = ArtworkManagerFocus.SOURCE_ROW) }
                else -> Unit
            }
        }
    }

    private fun cycleArtworkTab(delta: Int) {
        val types = ArtworkType.entries
        val current = _uiState.value.artworkTab
        val next = types[(types.indexOf(current) + delta + types.size) % types.size]
        _uiState.update {
            it.copy(
                artworkTab           = next,
                artworkPickerItems   = emptyList(),
                artworkPickerIndex   = 0,
                artworkPickerError   = null,
                artworkPickerLoading = false,
                artworkFocus         = ArtworkManagerFocus.TYPE_TABS,
            )
        }
    }

    private fun artworkManagerFocusSourceRow() {
        _uiState.update { it.copy(artworkFocus = ArtworkManagerFocus.SOURCE_ROW, artworkSourceFocus = SOURCE_SGDB) }
    }

    private fun moveArtworkGridFocus(delta: Int) {
        val last = _uiState.value.artworkPickerItems.lastIndex
        if (last < 0) return
        _uiState.update { it.copy(artworkPickerIndex = (it.artworkPickerIndex + delta).coerceIn(0, last)) }
    }

    private fun activateArtworkSource() {
        val s = _uiState.value
        when (s.artworkSourceFocus) {
            SOURCE_LOCAL -> _uiState.update { it.copy(artworkPendingLocal = it.artworkTab) }
            SOURCE_CLEAR -> clearArtwork(s.artworkTab)
            else         -> loadSourceForCurrentTab(s.artworkSourceFocus)
        }
    }

    fun activateSourceAt(index: Int) {
        _uiState.update { it.copy(artworkSourceFocus = index, artworkFocus = ArtworkManagerFocus.SOURCE_ROW) }
        when (index) {
            SOURCE_LOCAL -> _uiState.update { it.copy(artworkPendingLocal = it.artworkTab) }
            SOURCE_CLEAR -> clearArtwork(_uiState.value.artworkTab)
            else         -> loadSourceForCurrentTab(index)
        }
    }

    private fun pickFocusedArtwork() {
        val s = _uiState.value
        if (s.artworkPickerItems.isEmpty()) return
        val url = s.artworkPickerItems[s.artworkPickerIndex.coerceIn(0, s.artworkPickerItems.lastIndex)].url
        pickSgdbArtwork(url, s.artworkTab)
    }

    // ── Artwork Manager open / close ──────────────────────────────────────

    fun openArtworkManager() {
        _uiState.update {
            it.copy(
                showArtworkManager   = true,
                artworkTab           = ArtworkType.ICON,
                artworkFocus         = ArtworkManagerFocus.TYPE_TABS,
                artworkSourceFocus   = SOURCE_SGDB,
                artworkPickerItems   = emptyList(),
                artworkPickerIndex   = 0,
                artworkPickerLoading = false,
                artworkPickerError   = null,
                artworkPendingLocal  = null,
                artworkIsProcessing  = false,
                artworkMessage       = null,
            )
        }
    }

    fun closeArtworkManager() {
        _uiState.update { it.copy(showArtworkManager = false, artworkPendingLocal = null) }
    }

    fun consumeArtworkLocalPick() {
        _uiState.update { it.copy(artworkPendingLocal = null) }
    }

    fun setArtworkTab(type: ArtworkType) {
        if (_uiState.value.artworkTab == type) return
        _uiState.update {
            it.copy(
                artworkTab           = type,
                artworkFocus         = ArtworkManagerFocus.TYPE_TABS,
                artworkPickerItems   = emptyList(),
                artworkPickerIndex   = 0,
                artworkPickerError   = null,
                artworkPickerLoading = false,
            )
        }
    }

    fun setArtworkSourceFocus(index: Int) {
        _uiState.update { it.copy(artworkSourceFocus = index, artworkFocus = ArtworkManagerFocus.SOURCE_ROW) }
    }

    // ── Source loading ────────────────────────────────────────────────────

    private fun loadSourceForCurrentTab(sourceIndex: Int) {
        when (sourceIndex) {
            SOURCE_SGDB -> loadSgdbForCurrentTab()
            SOURCE_TGDB -> loadTgdbForCurrentTab()
            SOURCE_IGDB -> loadIgdbForCurrentTab()
        }
    }

    fun loadSgdbForCurrentTab() {
        val game = _uiState.value.game ?: return
        val type = _uiState.value.artworkTab
        if (_uiState.value.artworkPickerLoading) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    artworkPickerLoading = true,
                    artworkPickerItems   = emptyList(),
                    artworkPickerError   = null,
                    artworkFocus         = ArtworkManagerFocus.ART_GRID,
                )
            }
            val matches = steamGridDb.searchGame(game.displayTitle).getOrElse { e ->
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "SteamGridDB: Search failed: ${e.message}",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }
            val sgdbGame = matches.firstOrNull() ?: run {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "SteamGridDB: No results for \"${game.displayTitle}\"",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }
            val arts = steamGridDb.getArt(sgdbGame.id, type.toSgdbArtType()).getOrElse { e ->
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "SteamGridDB: Could not load artwork: ${e.message}",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }
            val items = arts.map { ArtPickerItem(url = it.url, thumbUrl = it.thumb) }
            _uiState.update {
                it.copy(artworkPickerLoading = false, artworkPickerItems = items, artworkPickerIndex = 0)
            }
        }
    }

    private fun loadIgdbForCurrentTab() {
        val game = _uiState.value.game ?: return
        val type = _uiState.value.artworkTab
        if (_uiState.value.artworkPickerLoading) return
        viewModelScope.launch {
            if (!igdbApi.hasCredentials()) {
                _uiState.update {
                    it.copy(
                        artworkPickerError   = "IGDB: No credentials configured — add them in Artwork Settings",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    artworkPickerLoading = true,
                    artworkPickerItems   = emptyList(),
                    artworkPickerError   = null,
                    artworkFocus         = ArtworkManagerFocus.ART_GRID,
                )
            }

            val info = runCatching {
                igdbApi.fetchGameInfo(game.platformId, game.displayTitle)
            }.onFailure { Timber.w(it, "IGDB fetch failed for '${game.displayTitle}'") }.getOrNull()

            if (info == null) {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "IGDB: No results for \"${game.displayTitle}\"",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }

            val items = buildList {
                when (type) {
                    ArtworkType.ICON, ArtworkType.BACKGROUND -> {
                        info.artworkUrl?.let { add(ArtPickerItem(it, label = "Cover Art")) }
                        info.heroUrl?.let    { add(ArtPickerItem(it, label = "Screenshot")) }
                    }
                    ArtworkType.HERO -> {
                        info.heroUrl?.let    { add(ArtPickerItem(it, label = "Screenshot")) }
                        info.artworkUrl?.let { add(ArtPickerItem(it, label = "Cover Art")) }
                    }
                }
            }

            if (items.isEmpty()) {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "IGDB: No artwork available for \"${game.displayTitle}\"",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(artworkPickerLoading = false, artworkPickerItems = items, artworkPickerIndex = 0)
            }
        }
    }

    private fun loadTgdbForCurrentTab() {
        val game = _uiState.value.game ?: return
        val type = _uiState.value.artworkTab
        if (_uiState.value.artworkPickerLoading) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    artworkPickerLoading = true,
                    artworkPickerItems   = emptyList(),
                    artworkPickerError   = null,
                    artworkFocus         = ArtworkManagerFocus.ART_GRID,
                )
            }

            val info = runCatching {
                theGamesDb.fetchGameInfo(platformId = game.platformId, title = game.displayTitle)
            }.onFailure { Timber.w(it, "TheGamesDB fetch failed for '${game.displayTitle}'") }.getOrNull()

            if (info == null) {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "TheGamesDB: No results for \"${game.displayTitle}\"",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }

            val items = buildList {
                when (type) {
                    ArtworkType.ICON, ArtworkType.BACKGROUND -> {
                        info.artworkUrl?.let { add(ArtPickerItem(it, label = "Box Art")) }
                        info.heroUrl?.let    { add(ArtPickerItem(it, label = "Fan Art")) }
                        info.logoUrl?.let    { add(ArtPickerItem(it, label = "Clear Logo")) }
                    }
                    ArtworkType.HERO -> {
                        info.heroUrl?.let    { add(ArtPickerItem(it, label = "Fan Art")) }
                        info.artworkUrl?.let { add(ArtPickerItem(it, label = "Box Art")) }
                        info.logoUrl?.let    { add(ArtPickerItem(it, label = "Clear Logo")) }
                    }
                }
            }

            if (items.isEmpty()) {
                _uiState.update {
                    it.copy(
                        artworkPickerLoading = false,
                        artworkPickerError   = "TheGamesDB: No artwork available for \"${game.displayTitle}\"",
                        artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(artworkPickerLoading = false, artworkPickerItems = items, artworkPickerIndex = 0)
            }
        }
    }

    // ── Artwork apply ─────────────────────────────────────────────────────

    fun onArtworkLocalPicked(uri: Uri, type: ArtworkType) {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(artworkIsProcessing = true, artworkMessage = null) }
            val localPath = artworkStore.saveVersionedFromUri(gameId, type.toKind(), uri)
            if (localPath == null) {
                _uiState.update { it.copy(artworkIsProcessing = false, artworkMessage = "Could not copy image") }
                return@launch
            }
            saveArtwork(gameId, type, localPath)
            val updated = gameRepository.getById(gameId)
            _uiState.update {
                it.copy(
                    game                = updated ?: it.game,
                    mediaUris           = mediaOf(updated ?: it.game),
                    artworkIsProcessing = false,
                    artworkMessage      = "${type.displayLabel} updated",
                )
            }
        }
    }

    fun onCustomArtworkPicked(uri: Uri, type: ArtworkType) = onArtworkLocalPicked(uri, type)

    fun pickSgdbArtwork(url: String, type: ArtworkType) {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    artworkIsProcessing  = true,
                    artworkPickerItems   = emptyList(),
                    artworkFocus         = ArtworkManagerFocus.SOURCE_ROW,
                )
            }
            val localPath = artworkStore.saveVersionedFromUrl(game.id, type.toKind(), url)
            if (localPath == null) {
                _uiState.update {
                    it.copy(artworkIsProcessing = false, artworkMessage = "Could not import ${type.displayLabel}")
                }
                return@launch
            }
            saveArtwork(game.id, type, localPath)
            val updated = gameRepository.getById(game.id)
            _uiState.update {
                it.copy(
                    game                = updated ?: it.game,
                    mediaUris           = mediaOf(updated ?: it.game),
                    artworkIsProcessing = false,
                    artworkMessage      = "${type.displayLabel} updated",
                )
            }
        }
    }

    fun clearArtwork(type: ArtworkType) {
        val gameId = _uiState.value.game?.id ?: return
        viewModelScope.launch {
            when (type) {
                ArtworkType.ICON       -> gameRepository.updateIconArt(gameId, null)
                ArtworkType.HERO       -> gameRepository.updateHeroArt(gameId, null)
                ArtworkType.BACKGROUND -> gameRepository.updateBoxArt(gameId, null)
            }
            val updated = gameRepository.getById(gameId)
            _uiState.update {
                it.copy(
                    game          = updated ?: it.game,
                    mediaUris     = mediaOf(updated ?: it.game),
                    artworkMessage = "${type.displayLabel} cleared",
                )
            }
        }
    }

    private suspend fun saveArtwork(gameId: Long, type: ArtworkType, path: String) {
        when (type) {
            ArtworkType.ICON       -> gameRepository.updateIconArt(gameId, path)
            ArtworkType.HERO       -> gameRepository.updateHeroArt(gameId, path)
            ArtworkType.BACKGROUND -> gameRepository.updateBoxArt(gameId, path)
        }
    }

    private fun ArtworkType.toSgdbArtType() = when (this) {
        ArtworkType.ICON       -> SgdbArtType.GRID
        ArtworkType.HERO       -> SgdbArtType.HERO
        ArtworkType.BACKGROUND -> SgdbArtType.HERO
    }

    private fun ArtworkType.toKind() = when (this) {
        ArtworkType.ICON       -> ArtworkKind.ICON
        ArtworkType.HERO       -> ArtworkKind.HERO
        ArtworkType.BACKGROUND -> ArtworkKind.BACKGROUND
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
    private fun openManual() {
        val game = _uiState.value.game ?: return
        viewModelScope.launch {
            // Internal store first (scraped manuals), then the portable artwork library
            // (imported manuals live at games/{platform}/{slug}/manual.pdf, tracked by the index).
            val path = artworkStore.find(game.id, ArtworkKind.MANUAL)
                ?: game.artworkKey?.let { key ->
                    artworkIndexDao.get(key, ArtworkKind.MANUAL.name)?.docUriOrPath
                }
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

    fun launch() {
        val selectedGame = _uiState.value.game ?: run {
            Timber.w("Play requested before game detail state was loaded")
            _uiState.update { it.copy(actionMessage = null, launchError = "Game is still loading") }
            return
        }
        menuSound.play(com.playfieldportal.core.ui.sound.MenuSound.LAUNCH)
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
                    sendLaunchIntent(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), game.title)
                }.onFailure { e ->
                    Timber.e(e, "Stored-intent launch failed for gameId=${game.id}")
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
                sendLaunchIntent(nativeIntent, game.title)
                return@launch
            }

            val resolved = resolveLaunchProfile(game, platform)
            if (resolved.isFailure) {
                val reason = resolved.exceptionOrNull()?.message ?: "Could not resolve emulator for ${game.title}"
                Timber.w(
                    "Launch blocked: gameId=${game.id}, title=${game.title}, platform=${game.platformId}, reason=$reason"
                )
                _uiState.update { it.copy(actionMessage = null, launchError = reason) }
                return@launch
            }
            val (profile, source) = resolved.getOrThrow()
            Timber.d(
                "Launch emulator resolved: gameId=${game.id}, platform=${game.platformId}, emulatorId=${profile.id}, emulatorName=${profile.name}, source=$source"
            )

            val result = intentResolver.resolve(game, profile)
            result.onFailure { e ->
                Timber.w(
                    e,
                    "Launch failed before startActivity: gameId=${game.id}, platform=${game.platformId}, emulatorId=${profile.id}, source=$source"
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
                "Launching gameId=${game.id}, title=${game.title}, platform=${game.platformId}, emulatorId=${profile.id}, emulator=${profile.name}, source=$source, core=${profile.corePathFor(game.platformId).orEmpty()}, rom=${game.romPath.orEmpty()}, intent=${intent.toUri(Intent.URI_INTENT_SCHEME)}"
            )
            sendLaunchIntent(intent, game.title)
        }
    }

    private fun sendLaunchIntent(intent: Intent, gameTitle: String) {
        val result = _launchEffect.trySend(intent)
        if (result.isSuccess) {
            Timber.d("Launch intent queued for UI collector")
            // We're about to background PFP for a game — reflect it in the opt-in Discord presence
            // (no-op unless the user connected Discord and enabled sharing).
            viewModelScope.launch { discordPresence.setCurrentGame(gameTitle) }
        } else {
            val cause = result.exceptionOrNull()
            Timber.w(cause, "Could not queue launch intent for UI collector")
            _uiState.update {
                it.copy(
                    actionMessage = null,
                    launchError = "Could not send launch request",
                )
            }
        }
    }

    fun onLaunchIntentCollected()    { Timber.d("Launch intent collected by GameDetailScreen") }
    fun onLaunchStartActivityReached() {
        Timber.d("Calling context.startActivity for launch intent")
        _uiState.update { it.copy(actionMessage = null) }
    }
    fun onLaunchFailed(message: String) {
        _uiState.update { it.copy(actionMessage = null, launchError = message) }
    }

    private suspend fun resolveLaunchProfile(
        game: Game,
        platform: PlatformEntity? = null,
    ): Result<ResolvedLaunchProfile> {
        val platformId = game.platformId
        val installed = profileRepository.getInstalledProfiles()
        val platformProfiles = installed.filter { it.supportsPlatform(platformId) }

        fun resolveConfigured(configuredIdOrPackage: String, source: String): Result<ResolvedLaunchProfile> {
            val profile = installed.firstOrNull { it.id == configuredIdOrPackage }
                ?: installed.firstOrNull { it.packageName == configuredIdOrPackage && it.supportsPlatform(platformId) }
                ?: installed.firstOrNull { it.packageName == configuredIdOrPackage }
                ?: return Result.failure(
                    IllegalStateException("The $source emulator is not installed or available: $configuredIdOrPackage")
                )

            if (!profile.supportsPlatform(platformId)) {
                return Result.failure(
                    IllegalStateException("${profile.name} is not configured for ${platformId.uppercase()}")
                )
            }

            return Result.success(ResolvedLaunchProfile(profile, source))
        }

        game.emulatorPackage?.takeIf { it.isNotBlank() }?.let {
            return resolveConfigured(it, "per-game override")
        }

        val memoryCardEmulator = memoryCardRepository.getById(platformId)
            ?.emulatorId
            ?.takeIf { it.isNotBlank() }
        memoryCardEmulator?.let {
            return resolveConfigured(it, "memory card emulator")
        }

        val platformDefault = platform?.preferredEmulatorPackage
            ?: platformDao.getById(platformId)?.preferredEmulatorPackage
        platformDefault?.takeIf { it.isNotBlank() }?.let {
            return resolveConfigured(it, "platform default")
        }

        platformProfiles.firstOrNull()?.let {
            return Result.success(ResolvedLaunchProfile(it, "first valid platform emulator"))
        }

        return Result.failure(
            IllegalStateException("No emulator configured for ${platformId.uppercase()}. Choose an emulator for this game or set a platform default.")
        )
    }

    // ── Emulator picker ───────────────────────────────────────────────────

    private fun openEmulatorPicker() {
        val game = _uiState.value.game ?: return
        val options = profileRepository.getInstalledProfiles().filter { it.supportsPlatform(game.platformId) }
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
            _uiState.update {
                it.copy(
                    game               = updated ?: it.game,
                    emulatorName       = profile?.name,
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

    private fun EmulatorProfile.supportsPlatform(platformId: String): Boolean {
        val aliases = platformAliases(platformId)
        return supportedPlatformIds.any { it in aliases }
    }

    private fun EmulatorProfile.corePathFor(platformId: String): String? {
        for (alias in platformAliases(platformId)) {
            coreMap[alias]?.let { return it }
        }
        return null
    }

    private fun platformAliases(platformId: String): Set<String> = when (platformId) {
        "psx"          -> setOf("psx", "ps1")
        "ps1"          -> setOf("ps1", "psx")
        "n3ds"         -> setOf("n3ds", "3ds")
        "3ds"          -> setOf("3ds", "n3ds")
        "gc"           -> setOf("gc", "gamecube")
        "gamecube"     -> setOf("gamecube", "gc")
        "nds"          -> setOf("nds", "ds")
        "ds"           -> setOf("ds", "nds")
        "pcengine"     -> setOf("pcengine", "pce", "tgfx16")
        "pce"          -> setOf("pce", "pcengine", "tgfx16")
        "tgfx16"       -> setOf("tgfx16", "pce", "pcengine")
        "mastersystem" -> setOf("mastersystem", "sms")
        "sms"          -> setOf("sms", "mastersystem")
        "genesis"      -> setOf("genesis", "megadrive", "md")
        "megadrive"    -> setOf("megadrive", "genesis", "md")
        "md"           -> setOf("md", "genesis", "megadrive")
        "dreamcast"    -> setOf("dreamcast", "dc")
        "dc"           -> setOf("dc", "dreamcast")
        "virtualboy"   -> setOf("virtualboy", "vb")
        "vb"           -> setOf("vb", "virtualboy")
        "atarilynx"    -> setOf("atarilynx", "lynx")
        "lynx"         -> setOf("lynx", "atarilynx")
        "wonderswan"   -> setOf("wonderswan", "ws")
        "ws"           -> setOf("ws", "wonderswan")
        "wonderswancolor" -> setOf("wonderswancolor", "wsc")
        "wsc"          -> setOf("wsc", "wonderswancolor")
        "ngp"          -> setOf("ngp", "ngpc")
        "ngpc"         -> setOf("ngpc", "ngp")
        else           -> setOf(platformId)
    }

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
            artworkRepository.clearCache()
            val updated = gameRepository.getById(game.id)
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
}
