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

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val authRepository: DiscordAuthRepository,
) : ViewModel() {

    // null = still loading; empty list = no friends.
    private val _friends = MutableStateFlow<List<DiscordFriend>?>(null)
    val friends: StateFlow<List<DiscordFriend>?> = _friends.asStateFlow()

    init { refresh() }

    fun refresh() {
        _friends.value = null
        viewModelScope.launch {
            _friends.value = authRepository.friends()
                .sortedWith(
                    compareByDescending<DiscordFriend> { it.presence.isOnline }
                        .thenBy { it.label.lowercase() },
                )
        }
    }
}
