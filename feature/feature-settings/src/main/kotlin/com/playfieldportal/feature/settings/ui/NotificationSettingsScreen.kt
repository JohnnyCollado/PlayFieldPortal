package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.NotificationSettingsViewModel

/**
 * Settings ▸ Interface ▸ Notifications.
 *
 * Deliberately small. The panel's real controls live in the panel itself (Mark All Read, Clear
 * Read, Clear All are one button press away there); what belongs here is the handful of choices a
 * user makes once: whether to record at all, whether to keep mirroring to the Android shade, and
 * how long the history is kept.
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title = "Settings",
        subtitle = "Notifications",
        onBack = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SettingsGroup("Notifications")

            SettingsToggleRow(
                label = "Record Notifications",
                sublabel = "Keep a history of scans, artwork, scraping and achievement work. " +
                    "Off stops new entries; running work still shows its progress.",
                focusKey = "notifications_enabled",
                checked = state.enabled,
                onToggle = { viewModel.setEnabled(it) },
            )

            // On by default. The shade is not being replaced — it simply cannot offer history,
            // actions or a clear on a device whose home screen is this launcher.
            SettingsToggleRow(
                label = "Also Show in Android Shade",
                sublabel = "Mirror background work to the system notification bar, as it has always been",
                focusKey = "notifications_mirror",
                checked = state.mirrorToShade,
                onToggle = { viewModel.setMirrorToShade(it) },
            )

            SettingsGroup("History")

            SettingsValueRow(
                label = "Keep Entries For",
                sublabel = "Older entries are dropped the next time something is recorded. " +
                    "A cap of 200 entries applies either way, and read entries go first.",
                value = NotificationSettingsViewModel.autoClearLabel(state.autoClearDays),
                focusKey = "notifications_auto_clear",
                onClick = { viewModel.cycleAutoClearDays() },
            )

            SettingsValueRow(
                label = "Mark All Read",
                sublabel = "Clears the unread count on the status bar",
                value = if (state.unreadCount > 0) "${state.unreadCount} unread" else "None unread",
                focusKey = "notifications_mark_all_read",
                onClick = { viewModel.markAllRead() },
            )

            // Always available, and it can never cancel anything: running work is in-flight state,
            // not a stored entry, so there is nothing here for a clear to reach.
            SettingsValueRow(
                label = "Clear All",
                sublabel = "Empties the history. Work that is still running is not affected.",
                value = "${state.storedCount} stored",
                focusKey = "notifications_clear_all",
                onClick = { viewModel.clearAll() },
            )
        }
    }
}
