package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.feature.settings.viewmodel.ChangelogEntry
import com.playfieldportal.feature.settings.viewmodel.SystemUpdateUiState
import com.playfieldportal.feature.settings.viewmodel.SystemUpdateViewModel
import com.playfieldportal.feature.settings.viewmodel.UpdateDownload

/** Settings ▸ System ▸ System Update (`settings_system_update`). */
@Composable
fun SystemUpdateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SystemUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SystemUpdateContent(
        state = state,
        onBack = onBack,
        onCheckNow = viewModel::checkNow,
        onDownloadAndInstall = viewModel::downloadAndInstall,
        onSetNotifications = viewModel::setNotificationsEnabled,
        onToggleRelease = viewModel::toggleRelease,
        onOpenNotes = viewModel::openNotes,
        modifier = modifier,
    )
}

/**
 * The stateless screen body. Every line is an ordinary settings row, so the controller cursor walks
 * the changelog like any other list and touch gets the same targets: a release row opens and closes
 * on Confirm or a tap, and its notes link is a row of its own rather than touch-only text.
 */
@Composable
internal fun SystemUpdateContent(
    state: SystemUpdateUiState,
    onBack: () -> Unit,
    onCheckNow: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onSetNotifications: (Boolean) -> Unit,
    onToggleRelease: (String) -> Unit,
    onOpenNotes: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = "Settings",
        subtitle = "System Update",
        onBack = onBack,
        modifier = modifier.fillMaxSize(),
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup("Update")
            SettingsValueRow(label = "Installed version", value = state.installedLabel, focusKey = "update_installed")
            SettingsValueRow(label = "Latest version", value = state.latestLabel, focusKey = "update_latest")
            state.availableVersion?.let { version -> InstallRow(version, state, onDownloadAndInstall) }
            SettingsRow(
                label = "Check for updates",
                sublabel = state.checkMessage ?: state.lastCheckedLabel,
                focusKey = "update_check",
                trailing = if (state.isChecking) {
                    { UpdateValueText("Checking…") }
                } else {
                    null
                },
                enabled = !state.isChecking,
                onClick = onCheckNow,
            )
            SettingsToggleRow(
                label = "Update notifications",
                sublabel = "Remind me in Notifications when a new version is out, at most once a day",
                focusKey = "update_notifications",
                checked = state.notificationsEnabled,
                onToggle = onSetNotifications,
            )

            SettingsGroup("Changelog")
            if (state.changelog.isEmpty()) {
                SettingsRow(
                    label = "No releases loaded yet",
                    sublabel = "Check for updates to load the changelog",
                    focusKey = "update_changelog_empty",
                )
            }
            state.changelog.forEach { entry -> ChangelogRows(entry, onToggleRelease, onOpenNotes) }
        }
    }
}

@Composable
private fun InstallRow(version: String, state: SystemUpdateUiState, onDownloadAndInstall: () -> Unit) {
    val download = state.download
    val downloading = download as? UpdateDownload.Downloading
    SettingsRow(
        label = "Download and install $version",
        sublabel = when {
            !state.canInstall -> "This build can't install updates from here"
            download is UpdateDownload.Failed -> download.message
            download is UpdateDownload.HandedToInstaller -> "Confirm in Android's installer to finish"
            downloading != null -> "Downloading…"
            else -> "Android asks you to confirm before anything is installed"
        },
        focusKey = "update_install",
        trailing = if (downloading != null) {
            { UpdateValueText("${downloading.percent}%") }
        } else {
            null
        },
        enabled = state.canInstall && downloading == null,
        onClick = onDownloadAndInstall,
    )
    if (downloading != null) {
        LinearProgressIndicator(
            progress = { downloading.percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 6.dp),
            color = SettingsAccent,
        )
    }
}

@Composable
private fun ChangelogRows(entry: ChangelogEntry, onToggleRelease: (String) -> Unit, onOpenNotes: (String) -> Unit) {
    SettingsRow(
        label = "${if (entry.expanded) "▼" else "▶"}  ${entry.version}",
        sublabel = entry.publishedOn.ifBlank { null },
        focusKey = "update_release_${entry.version}",
        trailing = if (entry.tag != null) {
            { UpdateValueText(entry.tag) }
        } else {
            null
        },
        onClick = { onToggleRelease(entry.version) },
    )
    if (!entry.expanded) return
    if (entry.summary.isEmpty()) {
        SettingsRow(
            label = "$CHILD_INDENT No summary for this version",
            focusKey = "update_release_${entry.version}_none",
        )
    }
    entry.summary.forEachIndexed { index, line ->
        SettingsRow(
            label = "$CHILD_INDENT• $line",
            focusKey = "update_release_${entry.version}_$index",
        )
    }
    SettingsRow(
        label = "$CHILD_INDENT Full release notes",
        sublabel = "$CHILD_INDENT Opens GitHub in your browser",
        focusKey = "update_release_${entry.version}_notes",
        onClick = { onOpenNotes(entry.notesUrl) },
    )
}

/** Sets a release's own lines in from its heading, as the Artwork import's nested rows do. */
private const val CHILD_INDENT = "     "

// Same styling as SettingsValueRow's value.
@Composable
private fun UpdateValueText(text: String) {
    Text(text = text, color = SettingsText, fontSize = 13.sp, style = TextStyle(shadow = SettingsTextShadow))
}
