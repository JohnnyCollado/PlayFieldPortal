package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.stringResource
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
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_update_title),
        onBack = onBack,
        modifier = modifier.fillMaxSize(),
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(stringResource(R.string.sets_update_group_update))
            SettingsValueRow(
                label = stringResource(R.string.sets_update_installed),
                value = state.installedLabel.asString(),
                focusKey = "update_installed",
            )
            SettingsValueRow(
                label = stringResource(R.string.sets_update_latest),
                value = state.latestLabel.asString(),
                focusKey = "update_latest",
            )
            state.availableVersion?.let { version -> InstallRow(version, state, onDownloadAndInstall) }
            SettingsRow(
                label = stringResource(R.string.sets_update_check),
                sublabel = (state.checkMessage ?: state.lastCheckedLabel).asString(),
                focusKey = "update_check",
                trailing = if (state.isChecking) {
                    { UpdateValueText(stringResource(R.string.sets_update_checking)) }
                } else {
                    null
                },
                enabled = !state.isChecking,
                onClick = onCheckNow,
            )
            SettingsToggleRow(
                label = stringResource(R.string.sets_update_notifications),
                sublabel = stringResource(R.string.sets_update_notifications_help),
                focusKey = "update_notifications",
                checked = state.notificationsEnabled,
                onToggle = onSetNotifications,
            )

            SettingsGroup(stringResource(R.string.sets_update_group_changelog))
            if (state.changelog.isEmpty()) {
                SettingsRow(
                    label = stringResource(R.string.sets_update_no_releases),
                    sublabel = stringResource(R.string.sets_update_no_releases_help),
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
        label = stringResource(R.string.sets_update_install, version),
        sublabel = when {
            !state.canInstall -> stringResource(R.string.sets_update_cannot_install)
            download is UpdateDownload.Failed -> download.message.asString()
            download is UpdateDownload.HandedToInstaller -> stringResource(R.string.sets_update_handed_over)
            downloading != null -> stringResource(R.string.sets_update_downloading)
            else -> stringResource(R.string.sets_update_install_help)
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
            { UpdateValueText(entry.tag.asString()) }
        } else {
            null
        },
        onClick = { onToggleRelease(entry.version) },
    )
    if (!entry.expanded) return
    if (entry.summary.isEmpty()) {
        SettingsRow(
            label = "$CHILD_INDENT ${stringResource(R.string.sets_update_no_summary)}",
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
        label = "$CHILD_INDENT ${stringResource(R.string.sets_update_full_notes)}",
        sublabel = "$CHILD_INDENT ${stringResource(R.string.sets_update_full_notes_help)}",
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
