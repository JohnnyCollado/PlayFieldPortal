package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.EmulatorsSettingsViewModel

@Composable
fun EmulatorsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmulatorsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Emulators",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("Installed Profiles")

            state.installedProfiles.forEach { profile ->
                SettingsRow(
                    label    = profile.name,
                    sublabel = "${profile.packageName}  ·  ${profile.intentType}",
                    onClick  = { viewModel.editProfile(profile.packageName) },
                )
            }

            SettingsGroup("Available (Not Installed)")

            state.availableProfiles.forEach { profile ->
                SettingsRow(
                    label    = profile.name,
                    sublabel = "Not installed — ${profile.packageName}",
                )
            }

            SettingsGroup("Custom Profiles")

            state.customProfiles.forEach { profile ->
                SettingsRow(
                    label    = profile.name,
                    sublabel = profile.packageName,
                    onClick  = { viewModel.editProfile(profile.packageName) },
                )
            }

            SettingsRow(
                label    = "Add Custom Emulator",
                sublabel = "Define a launch profile for any app",
                onClick  = { viewModel.addCustomProfile() },
            )

            SettingsGroup("Remote Profiles")

            SettingsRow(
                label    = "Check for Profile Updates",
                sublabel = state.lastUpdateCheck ?: "Last checked: never",
                onClick  = { viewModel.checkForUpdates() },
            )
        }
    }
}
