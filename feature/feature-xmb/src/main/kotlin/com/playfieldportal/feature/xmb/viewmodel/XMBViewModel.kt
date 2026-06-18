package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.icons.GameIconStyle
import com.playfieldportal.core.ui.theme.DefaultPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.wave.WaveRenderMode
import com.playfieldportal.feature.xmb.gamepad.ControllerMappingRepository
import com.playfieldportal.feature.xmb.gamepad.GamepadAction
import com.playfieldportal.feature.xmb.gamepad.GamepadInputHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
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

data class XMBUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategoryIndex: Int = 0,
    val currentItems: List<XMBItem> = emptyList(),
    val selectedItemIndex: Int = 0,
    val waveRenderMode: WaveRenderMode = WaveRenderMode.FULL,
    val showBootSequence: Boolean = true,
    val showTaskTray: Boolean = false,
    val activeBackgroundTasks: Int = 0,
    val backgroundTasks: List<BackgroundTaskInfo> = emptyList(),
    // Non-null when a settings sub-screen is open (matches XMBItem.id from SETTINGS_ITEMS)
    val activeSettingsScreen: String? = null,
    // Non-null when the app drawer is open; value is the AppFilter name to pre-select
    val activeAppDrawerFilter: String? = null,
    // Non-null when a game detail overlay is open
    val activeGameId: Long? = null,
    val iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    val librarySetupComplete: Boolean = false,
    val themeColors: PFPColors = DefaultPFPColors,
)

data class XMBItem(
    val id: String,
    val title: String,
    val artworkUri: String? = null,
    val subtitle: String? = null,
    // Non-null when this item represents a game — triggers game detail overlay on select
    val gameId: Long? = null,
    // Platform accent color (packed Long from PlatformEntity.accentColor)
    val accentColor: Long? = null,
    // True when the game is a native Android app — renders with round-square icon
    val isAndroidApp: Boolean = false,
    // Package name for loading the Android app icon via PackageManager
    val packageName: String? = null,
)

data class BackgroundTaskInfo(
    val id: String,
    val label: String,
    val progress: Float?,       // null = indeterminate
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class XMBViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val categoryRepository: CategoryRepositoryImpl,
    private val platformDao: PlatformDao,
    private val themeDao: ThemeDao,
    @ApplicationContext private val context: Context,
    private val gamepadInputHandler: GamepadInputHandler,
    private val mappingRepository: ControllerMappingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(XMBUiState())
    val uiState: StateFlow<XMBUiState> = _uiState.asStateFlow()

    private var idleJob: Job? = null

    // Live platform lookup for accent colors and ROM extension info
    private var platformCache: Map<String, PlatformEntity> = emptyMap()

    init {
        observePlatforms()
        observeIconStyle()
        observeLibrarySetupState()
        observeActiveTheme()
        observeCategories()
        observeGamepadMappings()
        collectGamepadActions()
    }

    private fun observeActiveTheme() {
        viewModelScope.launch {
            themeDao.observeAll().collect { themes ->
                val active = themes.firstOrNull { it.isActive }
                val colors = active?.toPFPColors() ?: DefaultPFPColors
                _uiState.update { it.copy(themeColors = colors) }
            }
        }
    }

    private fun observePlatforms() {
        viewModelScope.launch {
            platformDao.observeAll().collect { platforms ->
                platformCache = platforms.associateBy { it.id }
            }
        }
    }

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

    private fun observeLibrarySetupState() {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                val complete = prefs[KEY_SETUP_COMPLETE] ?: false
                _uiState.update { it.copy(librarySetupComplete = complete) }
                // Refresh GAMES items when setup state changes so the prompt appears/disappears live
                val state = _uiState.value
                if (state.categories.getOrNull(state.selectedCategoryIndex)?.id == BuiltInCategory.GAMES) {
                    loadItemsForCategory(state.categories.getOrNull(state.selectedCategoryIndex))
                }
            }
        }
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

        // Overlays consume navigation — handle back first (innermost first)
        when {
            state.activeGameId != null -> {
                if (action == GamepadAction.BACK) onCloseGameDetail()
                return
            }
            state.activeSettingsScreen != null -> {
                if (action == GamepadAction.BACK) onCloseSettingsScreen()
                return
            }
            state.activeAppDrawerFilter != null -> {
                if (action == GamepadAction.BACK) onCloseAppDrawer()
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
                if (next != state.selectedItemIndex) onItemSelected(next)
                else gamepadInputHandler.cancelRepeat()  // hit top — stop repeat
            }
            GamepadAction.NAVIGATE_DOWN -> {
                val max  = (state.currentItems.size - 1).coerceAtLeast(0)
                val next = (state.selectedItemIndex + 1).coerceAtMost(max)
                if (next != state.selectedItemIndex) onItemSelected(next)
                else gamepadInputHandler.cancelRepeat()  // hit bottom — stop repeat
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
            GamepadAction.SELECT      -> onItemSelected(state.selectedItemIndex)
            GamepadAction.BACK        -> { /* no overlay open — nothing to go back to */ }
            GamepadAction.LONG_PRESS  -> onItemLongPress(state.selectedItemIndex)
            GamepadAction.HOME        -> _uiState.update { it.copy(showBootSequence = true) }
            GamepadAction.OPEN_TASK_TRAY -> onTaskTrayVisibility(true)
        }
    }

    // Wired from MainActivity.dispatchKeyEvent — starts repeat via viewModelScope
    fun onKeyDown(action: GamepadAction) {
        if (action.isDirectional()) {
            gamepadInputHandler.startRepeating(action, viewModelScope)
        }
    }

    fun onKeyUp(action: GamepadAction) {
        if (action.isDirectional()) gamepadInputHandler.cancelRepeat()
    }

    private fun GamepadAction.isDirectional() = this in setOf(
        GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN,
        GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT,
    )

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun observeCategories() {
        viewModelScope.launch {
            categoryRepository.observeVisible().collect { categories ->
                val selectedIndex = _uiState.value.selectedCategoryIndex
                    .coerceIn(0, (categories.size - 1).coerceAtLeast(0))

                _uiState.update { it.copy(categories = categories, selectedCategoryIndex = selectedIndex) }

                // Load items for whichever category is currently selected
                loadItemsForCategory(categories.getOrNull(selectedIndex))
            }
        }
    }

    private fun loadItemsForCategory(category: Category?) {
        if (category == null) {
            _uiState.update { it.copy(currentItems = emptyList()) }
            return
        }

        viewModelScope.launch {
            when (category.id) {
                BuiltInCategory.FAVORITES -> {
                    gameRepository.observeFavorites().collect { games ->
                        _uiState.update {
                            it.copy(currentItems = games.map { g ->
                                XMBItem(
                                    id           = g.id.toString(),
                                    title        = g.title,
                                    artworkUri   = g.artworkUri,
                                    subtitle     = g.platformId.uppercase(),
                                    gameId       = g.id,
                                    accentColor  = platformCache[g.platformId]?.accentColor,
                                    isAndroidApp = g.packageName != null,
                                    packageName  = g.packageName,
                                )
                            })
                        }
                    }
                }

                BuiltInCategory.RECENTLY_PLAYED -> {
                    gameRepository.observeRecentPlatforms(limit = 5).collect { platforms ->
                        _uiState.update {
                            it.copy(currentItems = platforms.map { p ->
                                XMBItem(
                                    id       = p.platformId,
                                    title    = p.platformName,
                                    subtitle = "${p.gameCount} games",
                                )
                            })
                        }
                    }
                }

                BuiltInCategory.GAMES -> {
                    gameRepository.observeAll().collect { games ->
                        val items = mutableListOf<XMBItem>()

                        // First-run prompt when library has never been configured
                        if (!_uiState.value.librarySetupComplete) {
                            items.add(XMBItem(
                                id       = SETUP_ITEM_ID,
                                title    = "Set up ROM Library",
                                subtitle = "Choose a folder and start adding games  →",
                            ))
                        }

                        val platforms = games.map { it.platformId }.distinct().sorted()
                        items.addAll(platforms.map { platformId ->
                            XMBItem(id = platformId, title = platformId.uppercase())
                        })

                        _uiState.update { it.copy(currentItems = items) }
                    }
                }

                BuiltInCategory.ANDROID -> {
                    // Android category: filter shortcuts that open the drawer directly
                    _uiState.update { it.copy(currentItems = ANDROID_ITEMS) }
                }

                BuiltInCategory.APP_DRAWER -> {
                    // App Drawer category opens the full drawer immediately on selection
                    _uiState.update {
                        it.copy(
                            currentItems        = emptyList(),
                            activeAppDrawerFilter = "ALL",
                        )
                    }
                }

                BuiltInCategory.SETTINGS -> {
                    _uiState.update {
                        it.copy(currentItems = SETTINGS_ITEMS)
                    }
                }

                else -> {
                    // Platform category (pinned) or user category — load by platform
                    gameRepository.observeByPlatform(category.id).collect { games ->
                        _uiState.update {
                            it.copy(currentItems = games.map { g ->
                                XMBItem(
                                    id           = g.id.toString(),
                                    title        = g.title,
                                    artworkUri   = g.artworkUri,
                                    subtitle     = g.releaseYear?.toString(),
                                    gameId       = g.id,
                                    accentColor  = platformCache[g.platformId]?.accentColor,
                                    isAndroidApp = g.packageName != null,
                                    packageName  = g.packageName,
                                )
                            })
                        }
                    }
                }
            }
        }
    }

    // ── User interaction ──────────────────────────────────────────────────────

    fun onCategorySelected(index: Int) {
        resetIdleTimer()
        val categories = _uiState.value.categories
        _uiState.update { it.copy(selectedCategoryIndex = index, selectedItemIndex = 0) }
        loadItemsForCategory(categories.getOrNull(index))
    }

    fun onItemSelected(index: Int) {
        resetIdleTimer()
        _uiState.update { it.copy(selectedItemIndex = index) }

        val selectedCategory = _uiState.value.categories
            .getOrNull(_uiState.value.selectedCategoryIndex)
        val item = _uiState.value.currentItems.getOrNull(index)

        // Game items open the detail overlay regardless of which category they came from
        if (item?.gameId != null) {
            _uiState.update { it.copy(activeGameId = item.gameId) }
            return
        }

        val itemId = item?.id
        when (selectedCategory?.id) {
            BuiltInCategory.SETTINGS -> {
                if (itemId != null) _uiState.update { it.copy(activeSettingsScreen = itemId) }
            }
            BuiltInCategory.GAMES -> {
                if (itemId == SETUP_ITEM_ID) {
                    _uiState.update { it.copy(activeSettingsScreen = "settings_library") }
                }
                // Platform items: future pass adds per-platform drill-down
            }
            BuiltInCategory.ANDROID -> {
                if (itemId?.startsWith("drawer_") == true) {
                    val filter = itemId.removePrefix("drawer_").uppercase()
                    _uiState.update { it.copy(activeAppDrawerFilter = filter) }
                }
            }
            else -> Unit
        }
    }

    fun onCloseGameDetail() {
        _uiState.update { it.copy(activeGameId = null) }
    }

    fun onCloseSettingsScreen() {
        _uiState.update { it.copy(activeSettingsScreen = null) }
    }

    fun onCloseAppDrawer() {
        _uiState.update { it.copy(activeAppDrawerFilter = null) }
    }

    fun onItemLongPress(index: Int) {
        resetIdleTimer()
        val selectedCategory = _uiState.value.categories
            .getOrNull(_uiState.value.selectedCategoryIndex)
        val item = _uiState.value.currentItems.getOrNull(index) ?: return

        if (selectedCategory?.id == BuiltInCategory.GAMES) {
            pinPlatformFromGames(item.id)
        }
    }

    private fun pinPlatformFromGames(platformId: String) {
        if (platformId == SETUP_ITEM_ID || platformId in RESERVED_CATEGORY_IDS) return

        viewModelScope.launch {
            val platform = platformDao.getById(platformId) ?: run {
                Timber.w("Could not pin unknown platform: $platformId")
                return@launch
            }

            if (!platform.isPinnedToBar) {
                val nextPosition = platformCache.values
                    .filter { it.isPinnedToBar }
                    .maxOfOrNull { it.barPosition }
                    ?.plus(1)
                    ?: 0
                platformDao.setPinned(platformId, pinned = true, position = nextPosition)
                Timber.i("Pinned platform to XMB bar: $platformId")
            }

            createPlatformFolder(platformId)
        }
    }

    private suspend fun createPlatformFolder(platformId: String) {
        val rootPath = context.pfpDataStore.data.first()[KEY_LIBRARY_ROOT]?.takeIf { it.isNotBlank() }
            ?: return

        withContext(Dispatchers.IO) {
            val folder = File(rootPath, platformId.lowercase())
            if (!folder.exists() && !folder.mkdirs()) {
                Timber.w("Could not create platform folder: ${folder.absolutePath}")
            } else {
                Timber.i("Platform folder ready: ${folder.absolutePath}")
            }
        }
    }

    fun onUserInteraction() {
        if (_uiState.value.waveRenderMode != WaveRenderMode.FULL) {
            _uiState.update { it.copy(waveRenderMode = WaveRenderMode.FULL) }
        }
        resetIdleTimer()
    }

    fun onBootSequenceComplete() {
        _uiState.update { it.copy(showBootSequence = false) }
    }

    fun onTaskTrayVisibility(visible: Boolean) {
        _uiState.update { it.copy(showTaskTray = visible) }
    }

    fun onDismissTaskTray() {
        _uiState.update { it.copy(showTaskTray = false) }
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
        private val KEY_LIBRARY_ROOT   = stringPreferencesKey("library_root_path")
        private const val SETUP_ITEM_ID = "library_setup"

        private val RESERVED_CATEGORY_IDS = setOf(
            BuiltInCategory.FAVORITES,
            BuiltInCategory.RECENTLY_PLAYED,
            BuiltInCategory.GAMES,
            BuiltInCategory.ANDROID,
            BuiltInCategory.APP_DRAWER,
            BuiltInCategory.SETTINGS,
        )

        private val ANDROID_ITEMS = listOf(
            XMBItem(id = "drawer_all",       title = "All Apps",         subtitle = "Browse every installed app"),
            XMBItem(id = "drawer_games",     title = "Games",            subtitle = "Apps categorized as games"),
            XMBItem(id = "drawer_emulators", title = "Emulators",        subtitle = "RetroArch, PPSSPP, Dolphin and more"),
            XMBItem(id = "drawer_recent",    title = "Recently Used",    subtitle = "Apps you've used lately"),
        )

        private val SETTINGS_ITEMS = listOf(
            XMBItem(id = "settings_library",    title = "Library",          subtitle = "ROM sources & scanning"),
            XMBItem(id = "settings_artwork",     title = "Artwork",          subtitle = "SteamGridDB & cache"),
            XMBItem(id = "settings_emulators",   title = "Emulators",        subtitle = "Launch profiles"),
            XMBItem(id = "settings_themes",      title = "Themes",           subtitle = "XMB appearance"),
            XMBItem(id = "settings_display",     title = "Display",          subtitle = "Wave, boot animation"),
            XMBItem(id = "settings_controller",  title = "Controller",       subtitle = "Button mapping"),
            XMBItem(id = "settings_backup",      title = "Backup & Restore", subtitle = "Export & import"),
            XMBItem(id = "settings_logs",        title = "Logs",             subtitle = "Debug & error log viewer"),
            XMBItem(id = "settings_about",       title = "About",            subtitle = "Play Field Portal"),
        )
    }
}
