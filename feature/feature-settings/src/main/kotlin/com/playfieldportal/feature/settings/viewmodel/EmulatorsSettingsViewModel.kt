package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.core.domain.model.EmulatorProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ProfileListItem(
    val name: String,
    val packageName: String,
    val intentType: String,
)

data class EmulatorsSettingsUiState(
    val installedProfiles: List<ProfileListItem> = emptyList(),
    val availableProfiles: List<ProfileListItem> = emptyList(),
    val customProfiles: List<ProfileListItem> = emptyList(),
    val lastUpdateCheck: String? = null,
)

@HiltViewModel
class EmulatorsSettingsViewModel @Inject constructor(
    private val profileRepository: EmulatorProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmulatorsSettingsUiState())
    val uiState: StateFlow<EmulatorsSettingsUiState> = _uiState.asStateFlow()

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            val installed  = profileRepository.getInstalledProfiles()
            val all        = profileRepository.getAllProfiles()
            val custom     = profileRepository.getCustomProfiles()
            val available  = all.filter { p -> installed.none { it.packageName == p.packageName } && custom.none { it.packageName == p.packageName } }

            _uiState.value = EmulatorsSettingsUiState(
                installedProfiles = installed.map { it.toListItem() },
                availableProfiles = available.map { it.toListItem() },
                customProfiles    = custom.map { it.toListItem() },
            )
        }
    }

    fun editProfile(packageName: String) {
        // Opens EmulatorProfileEditor composable — wired in next pass
        Timber.d("Edit profile: $packageName")
    }

    fun addCustomProfile() {
        Timber.d("Add custom profile")
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            profileRepository.checkForRemoteUpdates()
            loadProfiles()
        }
    }

    private fun EmulatorProfile.toListItem() = ProfileListItem(
        name        = name,
        packageName = packageName,
        intentType  = intentType.name,
    )
}
