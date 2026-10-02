package com.playfieldportal.feature.xmb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.navigation.NavigationLogger
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.ui.components.PspMenuCue
import com.playfieldportal.core.ui.components.PspMenuNav
import com.playfieldportal.core.ui.components.PspMenuOutcome
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import com.playfieldportal.core.ui.sound.MenuSoundSink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the library-shelf game picker. The rules live in GamePickerLogic; this class owns the
 * library inputs, rebuilds the shelves when they or the category change, and keeps the cursor
 * and checkmarks across library updates.
 */
@HiltViewModel
class GamePickerViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val collectionRepository: CollectionRepository,
    private val memoryCardRepository: MemoryCardRepository,
    private val menuSound: MenuSoundPlayer,
) : ViewModel() {

    private val _state = MutableStateFlow(GamePickerState(isLoading = true))
    // The cursor runs on the unified navigation engine; see GamePickerNav.
    private val nav = GamePickerNav(NavigationLogger { android.util.Log.w("GamePickerNav", it) })
    val state: StateFlow<GamePickerState> = _state.asStateFlow()

    // Library inputs and the category being filled; the shelves are rebuilt from these.
    private var cards: List<MemoryCard> = emptyList()
    private var games: List<Game> = emptyList()
    private var collections: List<GameCollection> = emptyList()
    private var movableCollectionIds: Set<Long>? = null
    private var categoryTitle: String = ""

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                memoryCardRepository.observeEnabled()
                    .combine(gameRepository.observeAll()) { cards, games -> cards to games }
                    .collect { (newCards, newGames) ->
                        cards = newCards
                        games = newGames
                        collections = runCatching { collectionRepository.getAll() }.getOrDefault(emptyList())
                        rebuild()
                    }
            } catch (e: Exception) {
                android.util.Log.e("GamePickerViewModel", "Error loading picker data", e)
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    // What the user has checked survives a rebuild: the collector fires on any games-table write.
    private fun rebuild() {
        _state.update { current ->
            current.copy(
                shelves = buildShelves(
                    cards = cards,
                    games = games,
                    collections = collections,
                    preselectedGameIds = current.preselectedGameIds,
                    movableCollectionIds = movableCollectionIds,
                    categoryTitle = categoryTitle,
                ),
                isLoading = false,
            ).clampFocus()
        }
    }

    /**
     * Readies the picker for one category: [preselectedGameIds] (the games already in it) open
     * checked, only [movableCollectionIds] are offered as custom memory cards to move in, and
     * every tile starts in [initialView] (the user's global icon display mode).
     */
    fun prepare(
        preselectedGameIds: Set<Long>,
        movableCollectionIds: Set<Long>,
        categoryTitle: String,
        initialView: IconDisplayMode,
    ) {
        this.movableCollectionIds = movableCollectionIds
        this.categoryTitle = categoryTitle
        _state.update {
            it.copy(
                selectedGameIds = preselectedGameIds,
                selectedCollectionIds = emptySet(),
                preselectedGameIds = preselectedGameIds,
                viewMode = initialView,
            )
        }
        rebuild()
    }

    /**
     * Every controller action except Done (Start) and B, which the screen routes. While Y's menu is
     * open it takes every press, by the shared PSP-panel rules.
     */
    fun onAction(action: GamepadAction) {
        if (_state.value.menu != null) {
            menuAction(action)
            return
        }
        when (action) {
            GamepadAction.OPEN_CONTEXT_MENU -> {
                menuSound.play(MenuSound.SELECT)
                _state.update { it.openMenu() }
            }
            GamepadAction.CHANGE_SORT -> _state.update { it.pressSearch() }
            else -> transition { state ->
                when (action) {
                    GamepadAction.NAVIGATE_UP,
                    GamepadAction.NAVIGATE_DOWN,
                    GamepadAction.NAVIGATE_LEFT,
                    GamepadAction.NAVIGATE_RIGHT -> nav.move(state, action)
                    GamepadAction.SELECT -> nav.activate(state)
                    else -> state
                }
            }
        }
    }

    /**
     * B. Closes the menu (or climbs out of View), then an open search, then climbs from a shelf to
     * the list. Returns false when there is no level left, and the picker should close.
     */
    fun back(): Boolean {
        val state = _state.value
        if (state.menu != null) {
            menuAction(GamepadAction.BACK)
            return true
        }
        if (state.searchActive) {
            menuSound.play(MenuSound.BACK)
            _state.update { it.closeSearch() }
            return true
        }
        if (nav.back(state) == null) return false
        transition { nav.back(it) ?: it }
        return true
    }

    // Y's menu on the shared PSP-panel rules: clamp, B climbs then closes, Y closes, standard cues.
    private fun menuAction(action: GamepadAction) {
        val state = _state.value
        val menu = state.menu ?: return
        val rows = state.menuRows()
        val depth = if (menu.level == PickerMenuLevel.ROOT) 0 else 1
        val cue = rows.getOrNull(menu.selectedIndex)?.cue ?: PspMenuCue.NONE
        when (val outcome = PspMenuNav.handle(action, menu.selectedIndex, rows.size, depth, cue, MenuSoundSink { menuSound.play(it) })) {
            is PspMenuOutcome.Moved -> _state.update { it.copy(menu = menu.copy(selectedIndex = outcome.index)) }
            PspMenuOutcome.Activate -> _state.update { it.activateMenuRow() }
            PspMenuOutcome.Up -> _state.update { it.menuUp() }
            PspMenuOutcome.Close -> _state.update { it.closeMenu() }
            PspMenuOutcome.Ignored -> Unit
        }
    }

    /** Touch: a menu row tapped — it takes the cursor and activates. */
    fun tapMenuRow(index: Int) {
        val menu = _state.value.menu ?: return
        _state.update { it.copy(menu = menu.copy(selectedIndex = index)) }
        menuAction(GamepadAction.SELECT)
    }

    /** Touch: the scrim behind the menu. */
    fun dismissMenu() {
        if (_state.value.menu == null) return
        menuSound.play(MenuSound.BACK)
        _state.update { it.closeMenu() }
    }

    /** The search field's text changed (keyboard or touch). The cursor is re-clamped to what is left. */
    fun onSearchChange(query: String) = _state.update { it.copy(query = query) }

    /** Touch: the search affordance in the shelf heading. */
    fun onSearchToggle(active: Boolean) = _state.update { if (active) it.copy(searchActive = true) else it.closeSearch() }

    fun tapTile(index: Int) = transition { nav.tapTile(it, index) }

    fun tapShelf(index: Int) = transition { nav.tapShelf(it, index) }

    // Settling a finger scroll only parks the hidden cursor, so it stays silent.

    fun touchBrowse(index: Int) = _state.update { nav.touchBrowse(it, index) }

    /** The shelf pane laid out at [widthDp]: rows re-pack to it. */
    fun onShelfMeasured(widthDp: Float) = _state.update { it.copy(shelfWidthDp = widthDp) }

    // Applies one user input and plays its sound through the user's assigned Navigation slot.
    private fun transition(transform: (GamePickerState) -> GamePickerState) {
        val before = _state.value
        _state.update(transform)
        gamePickerSound(before, _state.value)?.let { menuSound.play(it) }
    }

    fun getSelectedItems(): Pair<Set<Long>, Set<Long>> =
        _state.value.selectedGameIds to _state.value.selectedCollectionIds

    // Resets the picker to a fresh state. The ViewModel is retained across open/close cycles,
    // so this must run when the picker is cancelled or its selection confirmed — otherwise the
    // previous checkmarks, cursor, shelf and view carry over the next time it opens.
    fun clearSelection() {
        _state.update {
            it.copy(
                selectedGameIds = emptySet(),
                selectedCollectionIds = emptySet(),
                preselectedGameIds = emptySet(),
                shelfIndex = 0,
                focusZone = PickerZone.RAIL,
                focusByShelf = emptyMap(),
                usingTouch = false,
                menu = null,
                searchActive = false,
                query = "",
            )
        }
    }
}
