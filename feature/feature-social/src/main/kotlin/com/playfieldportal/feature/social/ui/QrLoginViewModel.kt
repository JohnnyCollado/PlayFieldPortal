package com.playfieldportal.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.discord.DiscordAuthRepository
import com.playfieldportal.core.domain.discord.DeviceLoginState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the QR login screen: collects [DiscordAuthRepository.loginWithDeviceQr] into UI state.
 * Cancelling the screen (or a retry) cancels the collecting job, which stops the device-code poll.
 */
@HiltViewModel
class QrLoginViewModel @Inject constructor(
    private val authRepository: DiscordAuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<DeviceLoginState>(DeviceLoginState.Requesting)
    val state: StateFlow<DeviceLoginState> = _state.asStateFlow()

    private var loginJob: Job? = null

    // No start() in init: this view model is activity-scoped and outlives the overlay, so the
    // screen starts a login each time it opens and cancels it when it closes. Starting once in
    // init made a reopen show the previous Success or a dead QR (survey L7).

    /** (Re)start the login flow — on every open of the overlay, and on retry after Expired/Error. */
    fun start() {
        loginJob?.cancel()
        _state.value = DeviceLoginState.Requesting
        loginJob = viewModelScope.launch {
            authRepository.loginWithDeviceQr().collect { _state.value = it }
        }
    }

    /** Stops polling and forgets the result, so the next open never sees this one's state. */
    fun cancel() {
        loginJob?.cancel()
        loginJob = null
        _state.value = DeviceLoginState.Requesting
    }

    override fun onCleared() {
        loginJob?.cancel()
    }
}
