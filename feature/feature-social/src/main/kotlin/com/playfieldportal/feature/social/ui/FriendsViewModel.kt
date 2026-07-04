package com.playfieldportal.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.discord.DiscordAuthRepository
import com.playfieldportal.core.domain.discord.DiscordFriend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface FriendsUiState {
    data object Loading : FriendsUiState
    /** No connectivity — prompt the user to reconnect and retry. */
    data object Offline : FriendsUiState
    data class Loaded(val friends: List<DiscordFriend>) : FriendsUiState
}

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val authRepository: DiscordAuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<FriendsUiState>(FriendsUiState.Loading)
    val state: StateFlow<FriendsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = FriendsUiState.Loading
        viewModelScope.launch {
            _state.value = if (!authRepository.isOnline()) {
                FriendsUiState.Offline
            } else {
                FriendsUiState.Loaded(
                    authRepository.friends().sortedWith(
                        compareByDescending<DiscordFriend> { it.presence.isOnline }
                            .thenBy { it.label.lowercase() },
                    ),
                )
            }
        }
    }
}
