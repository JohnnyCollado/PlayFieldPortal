package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionsSettingsViewModel @Inject constructor(
    private val collectionRepository: CollectionRepository,
) : ViewModel() {

    val collections: StateFlow<List<GameCollection>> =
        collectionRepository.observeCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun gamesIn(collectionId: Long): Flow<List<Game>> =
        collectionRepository.observeGames(collectionId)

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { collectionRepository.create(name) }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { collectionRepository.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { collectionRepository.delete(id) }
    }

    fun moveUp(id: Long) {
        viewModelScope.launch { collectionRepository.move(id, up = true) }
    }

    fun moveDown(id: Long) {
        viewModelScope.launch { collectionRepository.move(id, up = false) }
    }

    fun removeGame(collectionId: Long, gameId: Long) {
        viewModelScope.launch { collectionRepository.removeGame(collectionId, gameId) }
    }
}
