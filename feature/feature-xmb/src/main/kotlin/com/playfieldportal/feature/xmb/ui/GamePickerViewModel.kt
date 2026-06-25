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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GamePickerState(
    val platformGroups: List<PlatformGameGroup> = emptyList(),
    val pcShortcuts: List<GameCollection> = emptyList(),
    val selectedGameIds: Set<Long> = emptySet(),
    val selectedCollectionIds: Set<Long> = emptySet(),
    val platformExpandedStates: Map<String, Boolean> = emptyMap(),
    val isLoading: Boolean = false,
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

    private val _state = MutableStateFlow(GamePickerState(isLoading = true))
    val state: StateFlow<GamePickerState> = _state.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val cards = memoryCardRepository.observeEnabled().first()
                val allGames = gameRepository.observeAll().first()
                val collections = collectionRepository.getAll()

                // Group games by platform
                val platformGroups = cards.map { card ->
                    val platformGames = allGames.filter { it.platformId == card.platformId }
                    PlatformGameGroup(
                        platform = card,
                        games = platformGames,
                        isExpanded = true,
                    )
                }

                // Filter for PC shortcut collections
                val pcShortcuts = collections.filter {
                    it.name.contains("PC", ignoreCase = true) ||
                    it.name.contains("Shortcut", ignoreCase = true)
                }

                _state.update {
                    it.copy(
                        platformGroups = platformGroups,
                        pcShortcuts = pcShortcuts,
                        isLoading = false,
                        platformExpandedStates = platformGroups.associate { group ->
                            group.platform.platformId to true
                        },
                    )
                }
            } catch (e: Exception) {
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
}
