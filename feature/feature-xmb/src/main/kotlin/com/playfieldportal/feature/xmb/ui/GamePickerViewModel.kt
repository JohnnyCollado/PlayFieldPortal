package com.playfieldportal.feature.xmb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
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

    /** Every controller action except Done (Start) and B, which the screen routes. */
    fun onAction(action: GamepadAction) {
        transition { state ->
            when (action) {
                GamepadAction.NAVIGATE_UP,
                GamepadAction.NAVIGATE_DOWN,
                GamepadAction.NAVIGATE_LEFT,
                GamepadAction.NAVIGATE_RIGHT -> state.move(action)
                GamepadAction.SELECT -> state.activate()
                GamepadAction.OPEN_CONTEXT_MENU -> state.toggleWholeShelf()
                GamepadAction.CHANGE_SORT -> state.cycleView()
                else -> state
            }
        }
    }

    /** B. Returns false when there is no level left to climb, and the picker should close. */
    fun back(): Boolean {
        if (_state.value.back() == null) return false
        transition { it.back() ?: it }
        return true
    }

    fun tapTile(index: Int) = transition { it.tapTile(index) }

    fun tapShelf(index: Int) = transition { it.tapShelf(index) }

    // Settling a finger scroll only parks the hidden cursor, so it stays silent.

    fun touchBrowse(index: Int) = _state.update { it.touchBrowse(index) }

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
            )
        }
    }
}
