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

    init {
        start()
    }

    /** (Re)start the login flow — used on first open and on retry after Expired/Error. */
    fun start() {
        loginJob?.cancel()
        _state.value = DeviceLoginState.Requesting
        loginJob = viewModelScope.launch {
            authRepository.loginWithDeviceQr().collect { _state.value = it }
        }
    }

    fun cancel() {
        loginJob?.cancel()
    }

    override fun onCleared() {
        loginJob?.cancel()
    }
}
