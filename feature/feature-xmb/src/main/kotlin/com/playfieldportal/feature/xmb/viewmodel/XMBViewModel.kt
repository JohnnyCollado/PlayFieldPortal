package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.LibrarySourceDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.theme.DefaultPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.wave.WaveRenderMode
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.library.scanner.RomScanner
import com.playfieldportal.feature.library.scanner.ScanResult
import com.playfieldportal.feature.library.scanner.ScanType
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private fun com.playfieldportal.core.data.database.entity.ThemeEntity.toPFPColors() = PFPColors(
    waveColor         = androidx.compose.ui.graphics.Color(waveColor),
    accentColor       = androidx.compose.ui.graphics.Color(accentColor),
    textPrimary       = androidx.compose.ui.graphics.Color(textColor),
    textSecondary     = androidx.compose.ui.graphics.Color(textColor).copy(alpha = 0.7f),
    backgroundOverlay = androidx.compose.ui.graphics.Color(0x88000000),
    selectedItem      = androidx.compose.ui.graphics.Color(accentColor),
    categoryBar       = androidx.compose.ui.graphics.Color(0x00000000),
)

// ── Context menu types ────────────────────────────────────────────────────────

data class XMBContextMenu(
    val title: String,
    val items: List<XMBContextMenuItem>,
    val selectedIndex: Int = 0,
    // Exactly one of these is non-null, identifies the source of the menu
    val platformId: String? = null,
    val gameId: Long? = null,
)

data class XMBContextMenuItem(
    val id: String,
    val label: String,
    val isDestructive: Boolean = false,
)

// ── Main XMB state ────────────────────────────────────────────────────────────

data class XMBUiState(
    // ── Horizontal axis: platforms (SD cards) + utility tabs ──────────────
    val categories: List<Category> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val platformGameCounts: Map<String, Int> = emptyMap(),
    val selectedPlatformId: String? = null,

    // ── Vertical axis: games / settings items ─────────────────────────────
    val currentItems: List<XMBItem> = emptyList(),
    val selectedItemIndex: Int = 0,

    // ── Background + rendering ────────────────────────────────────────────
    val waveRenderMode: WaveRenderMode = WaveRenderMode.FULL,
    val showBootSequence: Boolean = true,
    val showTaskTray: Boolean = false,
    val activeBackgroundTasks: Int = 0,
    val backgroundTasks: List<BackgroundTaskInfo> = emptyList(),

    // ── Overlay screens ───────────────────────────────────────────────────
    val activeSettingsScreen: String? = null,
    val pendingSettingsAction: GamepadAction? = null,
    val activeAppDrawerFilter: String? = null,
    val pendingDrawerAction: GamepadAction? = null,
    val pendingGameDetailAction: GamepadAction? = null,
    val activeGameId: Long? = null,

    // ── Context menu (Y/Triangle) ─────────────────────────────────────────
    val activeContextMenu: XMBContextMenu? = null,

    // ── Misc ──────────────────────────────────────────────────────────────
    val iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    val librarySetupComplete: Boolean = false,
    val themeColors: PFPColors = DefaultPFPColors,
)

data class XMBItem(
    val id: String,
    val title: String,
    val artworkUri: String? = null,
    val subtitle: String? = null,
    val gameId: Long? = null,
    val platformId: String? = null,
    val accentColor: Long? = null,
    val isFavorite: Boolean = false,
    val isAndroidApp: Boolean = false,
    val packageName: String? = null,
)

data class BackgroundTaskInfo(
    val id: String,
    val label: String,
    val progress: Float?,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class XMBViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val platformDao: PlatformDao,
    private val themeDao: ThemeDao,
    private val librarySourceDao: LibrarySourceDao,
    private val romScanner: RomScanner,
    private val artworkRepository: ArtworkRepository,
    @ApplicationContext private val context: Context,
    private val gamepadInputHandler: GamepadInputHandler,
    private val mappingRepository: ControllerMappingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(XMBUiState())
    val uiState: StateFlow<XMBUiState> = _uiState.asStateFlow()

    private var idleJob: Job? = null
    private var currentItemsJob: Job? = null
    private var platformCache: Map<String, PlatformEntity> = emptyMap()
    private var baseThemeColors: PFPColors = DefaultPFPColors

    init {
        gamepadInputHandler.scope = viewModelScope
        observeIconStyle()
        observeLibrarySetupState()
        observeActiveTheme()
        observeCategories()
        observeGamepadMappings()
        collectGamepadActions()
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private fun observeActiveTheme() {
        viewModelScope.launch {
            themeDao.observeAll().collect { themes ->
                val active = themes.firstOrNull { it.isActive }
                val colors = active?.toPFPColors() ?: DefaultPFPColors
                baseThemeColors = colors
                val accentFromPlatform = _uiState.value.categories
                    .getOrNull(_uiState.value.selectedCategoryIndex)?.accentColor
                val waveColor = accentFromPlatform
                    ?.let { androidx.compose.ui.graphics.Color(it) }
                    ?: colors.waveColor
                _uiState.update { it.copy(themeColors = colors.copy(waveColor = waveColor)) }
            }
        }
    }

    // ── Categories ────────────────────────────────────────────────────────────

    private fun observeCategories() {
        viewModelScope.launch {
            combine(
                platformDao.observeAll(),
                gameRepository.observeAll(),
            ) { platforms, games -> Pair(platforms, games) }
                .collect { (platforms, games) ->
                    platformCache = platforms.associateBy { it.id }
                    val counts = games.groupBy { it.platformId }.mapValues { it.value.size }

                    val allCategories = PSP_CATEGORIES
                    val prevIndex     = _uiState.value.selectedCategoryIndex
                    val newIndex      = prevIndex.coerceIn(0, (allCategories.size - 1).coerceAtLeast(0))
                    val prevId        = _uiState.value.categories.getOrNull(prevIndex)?.id
                    val newId         = allCategories.getOrNull(newIndex)?.id

                    _uiState.update { it.copy(
                        categories            = allCategories,
                        selectedCategoryIndex = newIndex,
                        platformGameCounts    = counts,
                        selectedPlatformId    = it.selectedPlatformId?.takeIf { id -> id in platformCache },
                    )}

                    if (newId != prevId || _uiState.value.currentItems.isEmpty()) {
                        tintWaveForCategory(allCategories.getOrNull(newIndex))
                        loadItemsForCategory(allCategories.getOrNull(newIndex))
                    }
                }
        }
    }

    private fun loadItemsForCategory(category: Category?) {
        currentItemsJob?.cancel()
        if (category == null) { _uiState.update { it.copy(currentItems = emptyList()) }; return }

        currentItemsJob = viewModelScope.launch {
            when (category.id) {
                BuiltInCategory.FAVORITES -> {
                    gameRepository.observeFavorites().collect { games ->
                        _uiState.update { it.copy(currentItems = games.toXmbItems()) }
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
                    if (platformId != null) {
                        gameRepository.observeByPlatform(platformId).collect { games ->
                            _uiState.update { it.copy(currentItems = games.toXmbItems()) }
                        }
                    } else {
                        _uiState.update { it.copy(currentItems = platformFolderItems()) }
                    }
                }
                else -> {
                    if (!_uiState.value.librarySetupComplete) {
                        _uiState.update {
                            it.copy(currentItems = listOf(XMBItem(
                                id       = SETUP_ITEM_ID,
                                title    = "Set up ROM Library",
                                subtitle = "Open Settings → Library to choose your ROM folder  →",
                            )))
                        }
                        return@launch
                    }
                    _uiState.update { it.copy(currentItems = emptyList()) }
                }
            }
        }
    }

    private fun platformFolderItems(): List<XMBItem> {
        if (!_uiState.value.librarySetupComplete) {
            return listOf(XMBItem(
                id       = SETUP_ITEM_ID,
                title    = "Set up ROM Library",
                subtitle = "Open Settings > Library to choose your ROM folder",
            ))
        }

        return platformCache.values
            .sortedWith(compareBy({ it.name }, { it.id }))
            .map { platform ->
                val count = _uiState.value.platformGameCounts[platform.id] ?: 0
                XMBItem(
                    id          = "platform_${platform.id}",
                    title       = platform.name,
                    subtitle    = "$count game${if (count == 1) "" else "s"}",
                    platformId  = platform.id,
                    accentColor = platform.accentColor,
                )
            }
    }

    private fun List<com.playfieldportal.core.domain.model.Game>.toXmbItems() = map { g ->
        XMBItem(
            id           = g.id.toString(),
            title        = g.title,
            artworkUri   = g.artworkUri,
            subtitle     = g.releaseYear?.toString(),
            gameId       = g.id,
            accentColor  = platformCache[g.platformId]?.accentColor,
            isFavorite   = g.isFavorite,
            isAndroidApp = g.packageName != null,
            packageName  = g.packageName,
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

        // ── Context menu captures ALL input when open ──────────────────────────
        if (state.activeContextMenu != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP   -> shiftContextMenu(-1)
                GamepadAction.NAVIGATE_DOWN -> shiftContextMenu(+1)
                GamepadAction.SELECT        -> activateContextMenuItem()
                GamepadAction.BACK,
                GamepadAction.LONG_PRESS    -> closeContextMenu()
                else -> Unit
            }
            return
        }

        // ── Overlays (innermost wins) ──────────────────────────────────────────
        when {
            state.activeGameId != null -> {
                if (action == GamepadAction.BACK) onCloseGameDetail()
                else _uiState.update { it.copy(pendingGameDetailAction = action) }
                return
            }
            state.activeSettingsScreen != null -> {
                Timber.d("Gamepad → settings(${state.activeSettingsScreen}): $action")
                when (action) {
                    GamepadAction.BACK -> onCloseSettingsScreen()
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
            state.showTaskTray -> {
                if (action == GamepadAction.BACK) onDismissTaskTray()
                return
            }
        }

        when (action) {
            GamepadAction.NAVIGATE_UP -> {
                val next = (state.selectedItemIndex - 1).coerceAtLeast(0)
                if (next != state.selectedItemIndex) { resetIdleTimer(); _uiState.update { it.copy(selectedItemIndex = next) } }
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.NAVIGATE_DOWN -> {
                val max  = (state.currentItems.size - 1).coerceAtLeast(0)
                val next = (state.selectedItemIndex + 1).coerceAtMost(max)
                if (next != state.selectedItemIndex) { resetIdleTimer(); _uiState.update { it.copy(selectedItemIndex = next) } }
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.NAVIGATE_LEFT, GamepadAction.PREV_CATEGORY -> {
                val next = (state.selectedCategoryIndex - 1).coerceAtLeast(0)
                if (next != state.selectedCategoryIndex) onCategorySelected(next)
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.NAVIGATE_RIGHT, GamepadAction.NEXT_CATEGORY -> {
                val max  = (state.categories.size - 1).coerceAtLeast(0)
                val next = (state.selectedCategoryIndex + 1).coerceAtMost(max)
                if (next != state.selectedCategoryIndex) onCategorySelected(next)
                else gamepadInputHandler.cancelRepeat()
            }
            GamepadAction.SELECT     -> onItemSelected(state.selectedItemIndex)
            GamepadAction.BACK       -> {
                if (state.selectedPlatformId != null) closePlatformFolder()
                else onOpenAppDrawer()
            }
            GamepadAction.LONG_PRESS -> {
                // Y / Triangle — open context menu for whichever item type has focus
                val item = state.currentItems.getOrNull(state.selectedItemIndex)
                when {
                    item?.gameId != null -> openGameContextMenu(item)
                    item?.platformId != null -> openPlatformContextMenu(item.platformId)
                }
            }
            GamepadAction.HOME          -> _uiState.update { it.copy(showBootSequence = true) }
            GamepadAction.OPEN_TASK_TRAY -> onTaskTrayVisibility(true)
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
        val platform = platformCache[platformId] ?: return
        // No context menu for utility tabs — they have their own dedicated screens
        val items = buildList {
            add(XMBContextMenuItem("scan_roms",        "Scan for New ROMs"))
            add(XMBContextMenuItem("refresh_artwork",  "Refresh Artwork"))
            add(XMBContextMenuItem("refresh_metadata", "Refresh Metadata"))
        }

        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title      = platform.name,
                items      = items,
                platformId = platformId,
            )
        )}
    }

    private fun openGameContextMenu(item: XMBItem) {
        val items = buildList {
            add(XMBContextMenuItem(
                id    = if (item.isFavorite) "unfavorite" else "favorite",
                label = if (item.isFavorite) "Remove from Favorites" else "Add to Favorites",
            ))
            add(XMBContextMenuItem("refresh_artwork",  "Refresh Artwork"))
            add(XMBContextMenuItem("refresh_metadata", "Refresh Metadata"))
        }

        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title  = item.title,
                items  = items,
                gameId = item.gameId,
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
        closeContextMenu()

        when {
            menu.platformId != null -> when (itemId) {
                "scan_roms"        -> scanPlatformRoms(menu.platformId)
                "refresh_artwork"  -> refreshPlatformArtwork(menu.platformId)
                "refresh_metadata" -> refreshPlatformArtwork(menu.platformId) // same path for now
            }
            menu.gameId != null -> when (itemId) {
                "favorite"         -> toggleGameFavorite(menu.gameId, true)
                "unfavorite"       -> toggleGameFavorite(menu.gameId, false)
                "refresh_artwork"  -> refreshGameArtwork(menu.gameId)
                "refresh_metadata" -> refreshGameArtwork(menu.gameId)
            }
        }
    }

    // Called from touch interaction on the overlay
    fun onContextMenuItemActivatedAt(index: Int) {
        _uiState.update { it.copy(activeContextMenu = it.activeContextMenu?.copy(selectedIndex = index)) }
        activateContextMenuItem()
    }

    fun closeContextMenu() {
        _uiState.update { it.copy(activeContextMenu = null) }
    }

    // ── Platform actions ──────────────────────────────────────────────────────

    fun onPlatformLongPress(categoryIndex: Int) {
        _uiState.value.currentItems.getOrNull(categoryIndex)?.platformId?.let(::openPlatformContextMenu)
    }

    private fun scanPlatformRoms(platformId: String) {
        viewModelScope.launch {
            val allSources  = librarySourceDao.getEnabled()
            // Prefer platform-locked sources; fall back to all sources if none are locked
            val scanSources = allSources.filter { it.platformId == platformId }
                .ifEmpty { allSources }

            if (scanSources.isEmpty()) {
                Timber.w("No library sources configured for $platformId scan")
                return@launch
            }

            val platformName = platformCache[platformId]?.name ?: platformId.uppercase()
            val taskId       = "scan_$platformId"
            val existingPaths = runCatching {
                gameRepository.observeByPlatform(platformId).first().mapNotNull { it.romPath }.toSet()
            }.getOrDefault(emptySet())

            addBackgroundTask(BackgroundTaskInfo(id = taskId, label = "Scanning $platformName ROMs…", progress = null))

            romScanner.scan(
                folders          = scanSources.map { it.path },
                scanType         = ScanType.NEW_FILES_ONLY,
                existingRomPaths = existingPaths,
            ).collect { result ->
                when (result) {
                    is ScanResult.Progress -> updateBackgroundTask(
                        taskId, result.progress.filesFound.toFloat() /
                                (result.progress.totalEstimated.coerceAtLeast(1))
                    )
                    is ScanResult.Complete -> {
                        result.newGames.forEach { game -> gameRepository.upsert(game) }
                        completeBackgroundTask(taskId,
                            if (result.newGames.isEmpty()) "No new ROMs found"
                            else "${result.newGames.size} new ROM(s) added"
                        )
                        Timber.i("Platform scan complete: ${result.newGames.size} new games for $platformId")
                    }
                    is ScanResult.Error -> {
                        failBackgroundTask(taskId, result.message)
                        Timber.e("Platform scan error for $platformId: ${result.message}")
                    }
                }
            }
        }
    }

    private fun refreshPlatformArtwork(platformId: String) {
        // Route to artwork settings screen for now; bulk per-platform refresh is a future feature
        _uiState.update { it.copy(activeSettingsScreen = "settings_artwork") }
    }

    // ── Game actions ──────────────────────────────────────────────────────────

    private fun toggleGameFavorite(gameId: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            gameRepository.setFavorite(gameId, isFavorite)
        }
    }

    private fun refreshGameArtwork(gameId: Long) {
        viewModelScope.launch {
            val game   = gameRepository.getById(gameId) ?: return@launch
            val taskId = "artwork_$gameId"
            addBackgroundTask(BackgroundTaskInfo(id = taskId, label = "Fetching artwork: ${game.title}", progress = null))
            val result = artworkRepository.fetchArtworkForGame(gameId, game.title)
            if (result.success) {
                completeBackgroundTask(taskId, "Artwork updated")
            } else {
                failBackgroundTask(taskId, result.errorMessage ?: "Artwork fetch failed")
            }
        }
    }

    // ── Background task management ────────────────────────────────────────────

    private fun addBackgroundTask(task: BackgroundTaskInfo) {
        _uiState.update { it.copy(
            backgroundTasks      = it.backgroundTasks + task,
            activeBackgroundTasks = it.activeBackgroundTasks + 1,
        )}
    }

    private fun updateBackgroundTask(id: String, progress: Float) {
        _uiState.update { state ->
            state.copy(backgroundTasks = state.backgroundTasks.map { t ->
                if (t.id == id) t.copy(progress = progress.coerceIn(0f, 1f)) else t
            })
        }
    }

    private fun completeBackgroundTask(id: String, message: String? = null) {
        _uiState.update { state ->
            state.copy(
                backgroundTasks      = state.backgroundTasks.map { t ->
                    if (t.id == id) t.copy(isCompleted = true, errorMessage = message) else t
                },
                activeBackgroundTasks = (state.activeBackgroundTasks - 1).coerceAtLeast(0),
            )
        }
        viewModelScope.launch {
            delay(4_000)
            _uiState.update { it.copy(backgroundTasks = it.backgroundTasks.filterNot { t -> t.id == id }) }
        }
    }

    private fun failBackgroundTask(id: String, message: String) {
        _uiState.update { state ->
            state.copy(
                backgroundTasks      = state.backgroundTasks.map { t ->
                    if (t.id == id) t.copy(isFailed = true, errorMessage = message) else t
                },
                activeBackgroundTasks = (state.activeBackgroundTasks - 1).coerceAtLeast(0),
            )
        }
    }

    // ── Category / platform selection ─────────────────────────────────────────

    fun onCategorySelected(index: Int) {
        resetIdleTimer()
        val category = _uiState.value.categories.getOrNull(index)
        _uiState.update { it.copy(selectedCategoryIndex = index, selectedItemIndex = 0, selectedPlatformId = null) }
        tintWaveForCategory(category)
        loadItemsForCategory(category)
    }

    // ── Item selection ────────────────────────────────────────────────────────

    fun onItemSelected(index: Int) {
        resetIdleTimer()
        _uiState.update { it.copy(selectedItemIndex = index) }
        val category = _uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex)
        val item     = _uiState.value.currentItems.getOrNull(index)

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
        resetIdleTimer()
        val item = _uiState.value.currentItems.getOrNull(index)
        when {
            item?.gameId != null -> openGameContextMenu(item)
            item?.platformId != null -> openPlatformContextMenu(item.platformId)
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

    private fun closePlatformFolder() {
        _uiState.update { it.copy(selectedPlatformId = null, selectedItemIndex = 0) }
        loadItemsForCategory(_uiState.value.categories.getOrNull(_uiState.value.selectedCategoryIndex))
    }

    // ── Game detail overlay ───────────────────────────────────────────────────

    fun onCloseGameDetail() {
        _uiState.update { it.copy(activeGameId = null, pendingGameDetailAction = null) }
    }

    fun consumeGameDetailAction() {
        _uiState.update { it.copy(pendingGameDetailAction = null) }
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

    // ── Task tray ─────────────────────────────────────────────────────────────

    fun onTaskTrayVisibility(visible: Boolean) {
        _uiState.update { it.copy(showTaskTray = visible) }
    }

    fun onDismissTaskTray() {
        _uiState.update { it.copy(showTaskTray = false) }
    }

    // ── Boot sequence ─────────────────────────────────────────────────────────

    fun onBootSequenceComplete() {
        _uiState.update { it.copy(showBootSequence = false) }
    }

    // ── User interaction ──────────────────────────────────────────────────────

    fun onUserInteraction() {
        if (_uiState.value.waveRenderMode != WaveRenderMode.FULL) {
            _uiState.update { it.copy(waveRenderMode = WaveRenderMode.FULL) }
        }
        resetIdleTimer()
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

    // ── Idle wave timer ───────────────────────────────────────────────────────

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(3_000)
            _uiState.update { it.copy(waveRenderMode = WaveRenderMode.REDUCED) }
            delay(7_000)
            _uiState.update { it.copy(waveRenderMode = WaveRenderMode.STATIC) }
        }
    }

    // ── Static data ───────────────────────────────────────────────────────────

    companion object {
        private val KEY_ICON_STYLE     = stringPreferencesKey("display_icon_style")
        private val KEY_SETUP_COMPLETE = booleanPreferencesKey("library_setup_complete")
        private const val SETUP_ITEM_ID = "library_setup"

        val PSP_CATEGORIES = listOf(
            Category(id = BuiltInCategory.SETTINGS, name = "Settings", iconKey = "ic_settings", type = CategoryType.BUILT_IN, position = 0),
            Category(id = "photos",                 name = "Photos",   iconKey = "ic_photos",   type = CategoryType.BUILT_IN, position = 1),
            Category(id = "music",                  name = "Music",    iconKey = "ic_music",    type = CategoryType.BUILT_IN, position = 2),
            Category(id = "videos",                 name = "Videos",   iconKey = "ic_videos",   type = CategoryType.BUILT_IN, position = 3),
            Category(id = BuiltInCategory.GAMES,    name = "Games",    iconKey = "ic_games",    type = CategoryType.BUILT_IN, position = 4),
            Category(id = "network",                name = "Network",  iconKey = "ic_network",  type = CategoryType.BUILT_IN, position = 5),
        )

        private val ANDROID_ITEMS = listOf(
            XMBItem(id = "drawer_all",       title = "All Apps",      subtitle = "Browse every installed app"),
            XMBItem(id = "drawer_games",     title = "Games",         subtitle = "Apps categorized as games"),
            XMBItem(id = "drawer_emulators", title = "Emulators",     subtitle = "RetroArch, PPSSPP, Dolphin and more"),
            XMBItem(id = "drawer_recent",    title = "Recently Used", subtitle = "Apps you've used lately"),
        )

        private val SETTINGS_ITEMS = listOf(
            XMBItem(id = "settings_library",    title = "Library",          subtitle = "ROM sources & scanning"),
            XMBItem(id = "settings_artwork",    title = "Artwork",          subtitle = "SteamGridDB & cache"),
            XMBItem(id = "settings_emulators",  title = "Emulators",        subtitle = "Launch profiles"),
            XMBItem(id = "settings_themes",     title = "Themes",           subtitle = "XMB appearance"),
            XMBItem(id = "settings_display",    title = "Display",          subtitle = "Wave, boot animation"),
            XMBItem(id = "settings_controller", title = "Controller",       subtitle = "Button mapping"),
            XMBItem(id = "settings_backup",     title = "Backup & Restore", subtitle = "Export & import"),
            XMBItem(id = "settings_logs",       title = "Logs",             subtitle = "Debug & error log viewer"),
            XMBItem(id = "settings_about",      title = "About",            subtitle = "Play Field Portal"),
        )
    }
}
