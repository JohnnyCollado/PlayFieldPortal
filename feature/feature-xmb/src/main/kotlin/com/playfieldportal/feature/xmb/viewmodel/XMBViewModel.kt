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
import com.playfieldportal.core.data.repository.CategoryRepositoryImpl
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.feature.appbar.AppCategoryRepository
import com.playfieldportal.feature.appbar.CategorizedApp
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
    // Identifies the source of the menu (platform card, game, or app)
    val platformId: String? = null,
    val gameId: Long? = null,
    val packageName: String? = null,
    // The category the app is being acted on from (for remove/pin)
    val categoryContext: String? = null,
    // Set on the category-picker submenu: "move" or "add"
    val pendingAppAction: String? = null,
)

data class XMBContextMenuItem(
    val id: String,
    val label: String,
    val isDestructive: Boolean = false,
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

    // ── App rename dialog ─────────────────────────────────────────────────
    val renameAppTarget: String? = null,    // package name being renamed
    val renameAppCurrent: String? = null,   // current label, prefills the field

    // ── Installed-app picker (Android Library / Video / Music) ─────────────
    val appPicker: AppPickerState? = null,

    // ── Misc ──────────────────────────────────────────────────────────────
    val iconStyle: GameIconStyle = GameIconStyle.PSP_RECTANGLE,
    val librarySetupComplete: Boolean = false,
    val themeColors: PFPColors = DefaultPFPColors,
)

enum class XMBItemType {
    STANDARD,
    ALL_GAMES,
    MEMORY_CARD,
    EMPTY,
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
    val accentColor: Long? = null,
    val isFavorite: Boolean = false,
    val isAndroidApp: Boolean = false,
    val packageName: String? = null,
    val type: XMBItemType = XMBItemType.STANDARD,
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
    private val memoryCardRepository: MemoryCardRepository,
    private val categoryRepository: CategoryRepositoryImpl,
    private val appCategoryRepository: AppCategoryRepository,
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
    private var enabledCards: List<MemoryCard> = emptyList()
    private var baseThemeColors: PFPColors = DefaultPFPColors

    init {
        gamepadInputHandler.scope = viewModelScope
        observeIconStyle()
        observeLibrarySetupState()
        observeActiveTheme()
        observeCategoryBar()
        observeCategories()
        observeAppChanges()
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
            ) { cards, games, platforms -> Triple(cards, games, platforms) }
                .collect { (cards, games, platforms) ->
                    platformCache = platforms.associateBy { it.id }
                    enabledCards  = cards
                    val counts = games.groupBy { it.platformId }.mapValues { it.value.size }

                    // Drop a stale platform folder if its card was removed or disabled.
                    val validPlatformId = _uiState.value.selectedPlatformId
                        ?.takeIf { id -> id == ALL_GAMES_PLATFORM_ID || cards.any { c -> c.platformId == id } }

                    _uiState.update { it.copy(
                        platformGameCounts = counts,
                        selectedPlatformId = validPlatformId,
                    )}

                    // Refresh the Games view live as cards/counts change.
                    if (currentCategory()?.id == BuiltInCategory.GAMES) {
                        loadItemsForCategory(currentCategory())
                    }
                }
        }
    }

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
                    if (platformId == ALL_GAMES_PLATFORM_ID) {
                        gameRepository.observeAll().collect { games ->
                            val items = if (games.isEmpty()) listOf(emptyAllGamesItem())
                                        else games.toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                    } else if (platformId != null) {
                        gameRepository.observeByPlatform(platformId).collect { games ->
                            val items = if (games.isEmpty()) listOf(emptyFolderItem(platformId))
                                        else games.toXmbItems()
                            _uiState.update { it.copy(currentItems = items) }
                        }
                    } else {
                        _uiState.update { it.copy(currentItems = memoryCardItems()) }
                    }
                }
                // All other categories (Photo / Music / Video / Network / App Store / custom)
                // are populated by classified + user-assigned Android apps.
                else -> {
                    val apps = appCategoryRepository.appsForCategory(category.id)
                    val appItems = if (apps.isEmpty()) listOf(emptyCategoryItem(category))
                                   else apps.map { it.toXmbItem() }
                    // "Add Apps" is offered on every app section so the same picker serves
                    // Video, Music, Network, App Store and custom categories alike.
                    _uiState.update { it.copy(currentItems = appItems + addAppsItem()) }
                }
            }
        }
    }

    private fun CategorizedApp.toXmbItem(): XMBItem = XMBItem(
        id           = "app_$packageName",
        title        = label,
        subtitle     = if (pinned) "Pinned" else null,
        packageName  = packageName,
        isAndroidApp = true,
    )

    private fun addAppsItem(): XMBItem = XMBItem(
        id       = ADD_APPS_ITEM_ID,
        title    = "Add Apps",
        subtitle = "Pick installed apps to add to this section",
    )

    private fun emptyCategoryItem(category: Category): XMBItem {
        val message = when (category.id) {
            "videos"    -> "No video apps found."
            "network"   -> "No browser apps found."
            "app_store" -> "No app stores found."
            "music"     -> "No music apps found."
            "photos"    -> "No photo apps found."
            else        -> "No apps assigned."
        }
        return XMBItem(
            id       = EMPTY_CATEGORY_ITEM_ID,
            title    = message,
            subtitle = "Install some apps to get started.",
            type     = XMBItemType.EMPTY,
        )
    }

    // Games root: one item per enabled Memory Card (already ordered pinned-first by the DAO).
    private fun memoryCardItems(): List<XMBItem> {
        val totalGames = if (_uiState.value.platformGameCounts.isNotEmpty()) {
            _uiState.value.platformGameCounts.values.sum()
        } else {
            enabledCards.sumOf { it.gameCount }
        }
        val allGamesItem = XMBItem(
            id       = ALL_GAMES_ITEM_ID,
            title    = "All Games",
            subtitle = "Total Games $totalGames",
            type     = XMBItemType.ALL_GAMES,
        )

        if (enabledCards.isEmpty()) {
            return listOf(allGamesItem, XMBItem(
                id       = NO_CONSOLES_ITEM_ID,
                title    = "No consoles configured",
                subtitle = "Open Library Manager to add a Memory Card",
                type     = XMBItemType.EMPTY,
            ))
        }

        return listOf(allGamesItem) + enabledCards.map { card ->
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
            title        = g.title,
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
                // Forward everything (incl. BACK) so the Details page can close its own inner
                // overlays first and only then pop back to the XMB (via onCloseGameDetail).
                _uiState.update { it.copy(pendingGameDetailAction = action) }
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
                if (state.selectedPlatformId != null) closePlatformFolder()
                else onOpenAppDrawer()
            }
            GamepadAction.LONG_PRESS -> {
                // Y / Triangle — open context menu for whichever item type has focus
                val item = state.currentItems.getOrNull(state.selectedItemIndex)
                when {
                    item?.gameId != null -> openGameContextMenu(item)
                    item?.platformId != null -> openPlatformContextMenu(item.platformId)
                    item?.packageName != null -> openAppContextMenu(item)
                }
            }
            // Start button no longer restarts / shows the boot screen.
            GamepadAction.HOME          -> Unit
            GamepadAction.OPEN_TASK_TRAY -> onTaskTrayVisibility(true)
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
        val items = buildList {
            add(XMBContextMenuItem("launch", "Launch Game"))
            add(XMBContextMenuItem(
                id    = if (item.isFavorite) "unfavorite" else "favorite",
                label = if (item.isFavorite) "Remove from Favorites" else "Add to Favorites",
            ))
            add(XMBContextMenuItem("refresh_metadata", "Refresh Metadata"))
            add(XMBContextMenuItem("refresh_artwork",  "Refresh Artwork"))
            add(XMBContextMenuItem("file_location",    "View File Location"))
        }

        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title  = item.title,
                items  = items,
                gameId = item.gameId,
            )
        )}
    }

    private fun openAppContextMenu(item: XMBItem) {
        val pkg = item.packageName ?: return
        val categoryId = currentCategory()?.id
        val items = buildList {
            add(XMBContextMenuItem("launch",  "Launch"))
            add(XMBContextMenuItem("move",    "Move To Category"))
            add(XMBContextMenuItem("add",     "Add To Category"))
            if (categoryId != null) add(XMBContextMenuItem("remove", "Remove From Category"))
            if (categoryId != null) add(XMBContextMenuItem("pin",    "Pin To Category"))
            add(XMBContextMenuItem("hide",    "Hide App"))
            add(XMBContextMenuItem("rename",  "Rename Shortcut"))
        }
        _uiState.update { it.copy(
            activeContextMenu = XMBContextMenu(
                title           = item.title,
                items           = items,
                packageName     = pkg,
                categoryContext = categoryId,
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
        closeContextMenu()

        when {
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
                "launch"           -> _uiState.update { it.copy(activeGameId = menu.gameId) }
                "favorite"         -> toggleGameFavorite(menu.gameId, true)
                "unfavorite"       -> toggleGameFavorite(menu.gameId, false)
                "refresh_artwork"  -> refreshGameArtwork(menu.gameId)
                "refresh_metadata" -> refreshGameArtwork(menu.gameId)
                "file_location"    -> showGameFileLocation(menu.gameId)
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
                    "launch" -> appCategoryRepository.launch(pkg)
                    "move"   -> openCategoryPicker(pkg, menu.categoryContext, "move")
                    "add"    -> openCategoryPicker(pkg, menu.categoryContext, "add")
                    "remove" -> menu.categoryContext?.let { cat -> appAction { appCategoryRepository.removeFromCategory(pkg, cat) } }
                    "pin"    -> menu.categoryContext?.let { cat -> appAction { appCategoryRepository.pinToCategory(pkg, cat) } }
                    "hide"   -> appAction { appCategoryRepository.setHidden(pkg, true) }
                    "rename" -> _uiState.update { it.copy(renameAppTarget = pkg, renameAppCurrent = menu.title) }
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

    private fun showGameFileLocation(gameId: Long) {
        viewModelScope.launch {
            val game   = gameRepository.getById(gameId) ?: return@launch
            val taskId = "location_$gameId"
            addBackgroundTask(BackgroundTaskInfo(id = taskId, label = game.title, progress = null))
            completeBackgroundTask(taskId, game.romPath ?: "No file path on record")
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
                is AppPickerTarget.CategoryShortcuts ->
                    packages.forEach { pkg -> appCategoryRepository.addToCategory(pkg, target.categoryId) }
            }
        }
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
            ADD_APPS_ITEM_ID -> {
                category?.id?.let { openAppPicker(AppPickerTarget.CategoryShortcuts(it), "Add Apps") }
                return
            }
            FIND_GAMES_ITEM_ID -> {
                (item.platformId ?: _uiState.value.selectedPlatformId)?.let {
                    openAppPicker(AppPickerTarget.AndroidGames(it), "Find Games")
                }
                return
            }
            NO_GAMES_ITEM_ID,
            EMPTY_CATEGORY_ITEM_ID -> return   // not selectable
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
        resetIdleTimer()
        val item = _uiState.value.currentItems.getOrNull(index)
        when {
            item?.gameId != null -> openGameContextMenu(item)
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
        private const val NO_CONSOLES_ITEM_ID = "no_consoles"
        private const val NO_GAMES_ITEM_ID    = "no_games"
        private const val EMPTY_CATEGORY_ITEM_ID = "empty_category"
        private const val ALL_GAMES_ITEM_ID = "all_games"
        private const val ALL_GAMES_PLATFORM_ID = "__all_games__"
        private const val ADD_APPS_ITEM_ID = "add_apps"
        private const val FIND_GAMES_ITEM_ID = "find_games"
        // Platform id whose library is built from installed apps (picker) instead of ROM scans.
        private const val ANDROID_PLATFORM_ID = "android"

        // Used only if the categories table hasn't been seeded yet (first frame on first run).
        // The main XMB always presents these seven categories in this order.
        val FALLBACK_CATEGORIES = listOf(
            Category(id = BuiltInCategory.SETTINGS, name = "Settings",  iconKey = "ic_settings", type = CategoryType.BUILT_IN, position = 0),
            Category(id = "photos",                 name = "Photo",     iconKey = "ic_photos",   type = CategoryType.BUILT_IN, position = 1),
            Category(id = "music",                  name = "Music",     iconKey = "ic_music",    type = CategoryType.BUILT_IN, position = 2),
            Category(id = "videos",                 name = "Video",     iconKey = "ic_videos",   type = CategoryType.BUILT_IN, position = 3),
            Category(id = BuiltInCategory.GAMES,    name = "Game",      iconKey = "ic_games",    type = CategoryType.BUILT_IN, position = 4),
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
            XMBItem(id = "settings_categories", title = "Categories",       subtitle = "Manage XMB categories"),
            XMBItem(id = "settings_artwork",    title = "Artwork",          subtitle = "SteamGridDB & cache"),
            XMBItem(id = "settings_emulators",  title = "Emulators",        subtitle = "Launch profiles"),
            XMBItem(id = "settings_themes",     title = "Themes",           subtitle = "XMB appearance"),
            XMBItem(id = "settings_display",    title = "Display",          subtitle = "Wave, boot animation"),
            XMBItem(id = "settings_controller", title = "Controller",       subtitle = "Button mapping"),
            XMBItem(id = "settings_backup",     title = "Backup & Restore", subtitle = "Export & import"),
            XMBItem(id = "settings_logs",       title = "Logs",             subtitle = "Debug & error log viewer"),
            XMBItem(id = "settings_about",      title = "About",            subtitle = "Play Field Portal"),
            XMBItem(id = "settings_credits",    title = "Credits",          subtitle = "Artwork & attributions"),
        )
    }

    private fun canonicalXmbCategories(categories: List<Category>): List<Category> {
        val byId = categories.associateBy { it.id }
        return FALLBACK_CATEGORIES.map { fallback ->
            val stored = byId[fallback.id]
            fallback.copy(
                accentColor   = stored?.accentColor,
                customIconUri = stored?.customIconUri,
                filterRules   = stored?.filterRules,
            )
        }
    }

    private fun defaultXmbCategoryIndex(categories: List<Category>): Int =
        categories.indexOfFirst { it.id == BuiltInCategory.GAMES }
            .takeIf { it >= 0 }
            ?: 0
}
