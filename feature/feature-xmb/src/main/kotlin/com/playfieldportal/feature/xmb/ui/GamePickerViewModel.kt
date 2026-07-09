package com.playfieldportal.feature.xmb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.MemoryCardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Stable, namespaced identities for picker rows. Games and collections come from separate
// tables with overlapping numeric IDs, so they MUST be namespaced — otherwise a game and a
// collection that share an id collide, highlighting two rows at once and trapping the cursor.
internal const val PICKER_COLLECTIONS_HEADER = "COLLECTIONS_HEADER"
internal fun pickerPlatformId(platformId: String) = "platform_$platformId"
internal fun pickerGameId(gameId: Long) = "game_$gameId"
internal fun pickerCollectionId(collectionId: Long) = "collection_$collectionId"

data class GamePickerState(
    val platformGroups: List<PlatformGameGroup> = emptyList(),
    val pcShortcuts: List<GameCollection> = emptyList(),
    val selectedGameIds: Set<Long> = emptySet(),
    val selectedCollectionIds: Set<Long> = emptySet(),
    val platformExpandedStates: Map<String, Boolean> = emptyMap(),
    val isLoading: Boolean = false,
    val selectedItemId: String? = null,  // Identity of selected item (platform/game/collection ID)
)

data class PlatformGameGroup(
    val platform: MemoryCard,
    val games: List<Game>,
    val isExpanded: Boolean = true,
    val selectedCount: Int = 0,
) {
    val isAllSelected: Boolean get() = selectedCount == games.size && games.isNotEmpty()
}

@HiltViewModel
class GamePickerViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val collectionRepository: CollectionRepository,
    private val memoryCardRepository: MemoryCardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GamePickerState(isLoading = true, selectedItemId = null))
    val state: StateFlow<GamePickerState> = _state.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                // Combine memory cards and games streams to listen for updates
                memoryCardRepository.observeEnabled().combine(gameRepository.observeAll()) { cards, allGames ->
                    Pair(cards, allGames)
                }.collect { (cards, allGames) ->
                    try {
                        val collections = try {
                            collectionRepository.getAll()
                        } catch (e: Exception) {
                            emptyList()
                        }

                        // Group games by platform, excluding empty platforms. Real games only —
                        // standard (unmarked) apps can't join gaming categories/collections.
                        val platformGroups = cards.mapNotNull { card ->
                            val platformGames = allGames.filter {
                                it.platformId == card.platformId &&
                                    it.contentType == com.playfieldportal.core.domain.model.GameContentType.GAME
                            }
                            if (platformGames.isNotEmpty()) {
                                PlatformGameGroup(
                                    platform = card,
                                    games = platformGames,
                                    isExpanded = true,
                                )
                            } else {
                                null
                            }
                        }

                        // All user collections available to add
                        val allCollections = collections

                        val newExpandedStates = platformGroups.associate { group ->
                            group.platform.platformId to false
                        }

                        // Initialize selectedItemId to first item if not already set
                        val newState = GamePickerState(
                            platformGroups = platformGroups,
                            pcShortcuts = allCollections,
                            isLoading = false,
                            platformExpandedStates = newExpandedStates,
                            selectedItemId = if (_state.value.selectedItemId == null) {
                                platformGroups.firstOrNull()?.platform?.platformId?.let { pickerPlatformId(it) }
                            } else {
                                _state.value.selectedItemId
                            }
                        )

                        _state.update { newState }
                    } catch (e: Exception) {
                        android.util.Log.e("GamePickerViewModel", "Error processing picker data", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GamePickerViewModel", "Error loading picker data", e)
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleGameSelection(gameId: Long) {
        _state.update { state ->
            val newSelected = if (gameId in state.selectedGameIds) {
                state.selectedGameIds - gameId
            } else {
                state.selectedGameIds + gameId
            }
            state.copy(selectedGameIds = newSelected)
        }
        updateGroupCounts()
    }

    fun toggleCollectionSelection(collectionId: Long) {
        _state.update { state ->
            val newSelected = if (collectionId in state.selectedCollectionIds) {
                state.selectedCollectionIds - collectionId
            } else {
                state.selectedCollectionIds + collectionId
            }
            state.copy(selectedCollectionIds = newSelected)
        }
    }

    fun togglePlatformAllSelection(platformId: String, selectAll: Boolean) {
        val group = _state.value.platformGroups.firstOrNull { it.platform.platformId == platformId } ?: return

        _state.update { state ->
            val newSelected = if (selectAll) {
                state.selectedGameIds + group.games.map { it.id }
            } else {
                state.selectedGameIds - group.games.map { it.id }.toSet()
            }
            state.copy(selectedGameIds = newSelected)
        }
        updateGroupCounts()
    }

    fun togglePlatformExpanded(platformId: String) {
        _state.update { state ->
            val newExpandedStates = state.platformExpandedStates.toMutableMap()
            newExpandedStates[platformId] = !(newExpandedStates[platformId] ?: true)
            state.copy(platformExpandedStates = newExpandedStates)
        }
    }

    private fun updateGroupCounts() {
        _state.update { state ->
            val updatedGroups = state.platformGroups.map { group ->
                val count = group.games.count { it.id in state.selectedGameIds }
                group.copy(selectedCount = count)
            }
            state.copy(platformGroups = updatedGroups)
        }
    }

    fun getSelectedItems(): Pair<Set<Long>, Set<Long>> {
        return _state.value.selectedGameIds to _state.value.selectedCollectionIds
    }

    // Resets the picker to a fresh state. The ViewModel is retained across open/close cycles,
    // so this must run when the picker is cancelled or its selection confirmed — otherwise the
    // previous checkmarks, cursor, and expanded groups carry over the next time it opens.
    fun clearSelection() {
        _state.update { state ->
            state.copy(
                selectedGameIds = emptySet(),
                selectedCollectionIds = emptySet(),
                platformGroups = state.platformGroups.map { it.copy(selectedCount = 0) },
                platformExpandedStates = state.platformGroups.associate { it.platform.platformId to false },
                selectedItemId = state.platformGroups.firstOrNull()?.platform?.platformId?.let { pickerPlatformId(it) },
            )
        }
    }

    fun addNewCollection(name: String) {
        viewModelScope.launch {
            try {
                val newCollection = com.playfieldportal.core.domain.model.GameCollection(
                    name = name,
                    gameCount = 0,
                )
                // Note: This assumes collectionRepository has a method to create collections
                // If not, this will need to be implemented
            } catch (e: Exception) {
                android.util.Log.e("GamePickerViewModel", "Error creating collection", e)
            }
        }
    }

    fun moveSelection(delta: Int) {
        val state = _state.value

        // Build a list of all selectable item IDs in order
        val itemIds = buildPickerItemIds(state)
        if (itemIds.isEmpty()) return

        // Find current position
        val currentId = state.selectedItemId
        val currentIndex = if (currentId != null) itemIds.indexOf(currentId) else -1

        // Calculate new position
        val newIndex = if (currentIndex < 0) {
            // No selection yet, start at first item
            0
        } else {
            (currentIndex + delta).coerceIn(0, itemIds.size - 1)
        }

        if (newIndex >= 0 && newIndex < itemIds.size) {
            _state.update { it.copy(selectedItemId = itemIds[newIndex]) }
        }
    }

    fun activateSelection() {
        val state = _state.value
        val selectedId = state.selectedItemId ?: return

        // Determine what type of item was selected
        for (group in state.platformGroups) {
            if (pickerPlatformId(group.platform.platformId) == selectedId) {
                // Platform header selected
                val selectAll = !group.isAllSelected
                togglePlatformAllSelection(group.platform.platformId, selectAll)
                return
            }

            for (game in group.games) {
                if (pickerGameId(game.id) == selectedId) {
                    // Game selected
                    toggleGameSelection(game.id)
                    return
                }
            }
        }

        // Check collections
        if (selectedId == PICKER_COLLECTIONS_HEADER) {
            // Header selected, no action
            return
        }

        for (collection in state.pcShortcuts) {
            if (pickerCollectionId(collection.id) == selectedId) {
                toggleCollectionSelection(collection.id)
                return
            }
        }
    }

    fun toggleSelectedPlatform() {
        val state = _state.value
        val selectedId = state.selectedItemId ?: return

        // If the selected item is a platform header, toggle its expanded state
        for (group in state.platformGroups) {
            if (pickerPlatformId(group.platform.platformId) == selectedId) {
                togglePlatformExpanded(group.platform.platformId)
                return
            }
        }
    }

}

// Ordered, namespaced list of every navigable picker row ID — the single source of truth for
// cursor movement (ViewModel.moveSelection) and scroll positioning (GamePickerScreen). The IDs
// here MUST match the per-row identities rendered by the screen (pickerPlatformId/GameId/etc.).
internal fun buildPickerItemIds(state: GamePickerState): List<String> {
    val ids = mutableListOf<String>()

    for (group in state.platformGroups) {
        ids.add(pickerPlatformId(group.platform.platformId))
        if (state.platformExpandedStates[group.platform.platformId] == true) {
            for (game in group.games) {
                ids.add(pickerGameId(game.id))
            }
        }
    }

    if (state.pcShortcuts.isNotEmpty()) {
        ids.add(PICKER_COLLECTIONS_HEADER)
        for (collection in state.pcShortcuts) {
            ids.add(pickerCollectionId(collection.id))
        }
    }

    return ids
}
