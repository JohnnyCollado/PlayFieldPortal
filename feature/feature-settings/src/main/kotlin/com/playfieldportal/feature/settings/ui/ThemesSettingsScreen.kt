package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.ThemeListItem
import com.playfieldportal.feature.settings.viewmodel.ThemesSettingsViewModel

@Composable
fun ThemesSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemesSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // SAF picker — any file type; user must pick a .xmbtheme ZIP
    val themePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.installTheme(it) } }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Themes",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Active theme ──────────────────────────────────────────────
            SettingsGroup("Active Theme")

            SettingsValueRow(
                label = "Current Theme",
                value = state.activeThemeName,
            )

            // ── Installed themes list ─────────────────────────────────────
            SettingsGroup("Installed Themes")

            if (state.themes.isEmpty()) {
                Text(
                    text     = "No themes installed.",
                    color    = SettingsSubtext,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            } else {
                state.themes.forEach { theme ->
                    ThemeRow(
                        theme       = theme,
                        isActive    = theme.id == state.activeThemeId,
                        onApply     = { viewModel.applyTheme(theme.id) },
                        onUninstall = if (!theme.isBuiltIn) ({ viewModel.uninstallTheme(theme.id) }) else null,
                    )
                }
            }

            // ── Install ───────────────────────────────────────────────────
            SettingsGroup("Install")

            if (state.isInstalling) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            state.installMessage?.let { msg ->
                SettingsRow(
                    label    = msg,
                    sublabel = "Tap to dismiss",
                    onClick  = { viewModel.dismissMessage() },
                )
            }

            SettingsRow(
                label    = "Install from File",
                sublabel = "Browse to a .xmbtheme package",
                onClick  = if (state.isInstalling) null else ({ themePicker.launch(arrayOf("*/*")) }),
            )

            SettingsRow(
                label    = "Theme Folder",
                sublabel = "/storage/emulated/0/PlayFieldPortal/themes/",
            )
        }
    }
}

@Composable
private fun ThemeRow(
    theme: ThemeListItem,
    isActive: Boolean,
    onApply: () -> Unit,
    onUninstall: (() -> Unit)?,
) {
    SettingsRow(
        label    = theme.name,
        sublabel = if (theme.isBuiltIn) "Built-in" else "Installed",
        trailing = {
            if (isActive) {
                Text("Active", color = SettingsAccent)
            } else if (onUninstall != null) {
                Text(
                    text  = "Remove",
                    color = SettingsAccent,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .let { m -> m.then(androidx.compose.foundation.clickable { onUninstall() }) },
                )
            }
        },
        onClick = if (!isActive) onApply else null,
    )
}
