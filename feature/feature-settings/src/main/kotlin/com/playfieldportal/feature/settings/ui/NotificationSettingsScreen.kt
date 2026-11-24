package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
 * user makes once: whether to record at all and how long the history is kept. (There is no shade
 * mirror any more: PFP's notifications are launcher-only.)
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_notif_title),
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
            SettingsGroup(stringResource(R.string.sets_notif_title))

            SettingsToggleRow(
                label = stringResource(R.string.sets_notif_record),
                sublabel = stringResource(R.string.sets_notif_record_help),
                focusKey = "notifications_enabled",
                checked = state.enabled,
                onToggle = { viewModel.setEnabled(it) },
            )

            SettingsGroup(stringResource(R.string.sets_notif_group_history))

            SettingsValueRow(
                label = stringResource(R.string.sets_notif_keep_for),
                sublabel = stringResource(R.string.sets_notif_keep_for_help),
                value = NotificationSettingsViewModel.autoClearLabel(state.autoClearDays).asString(),
                focusKey = "notifications_auto_clear",
                onClick = { viewModel.cycleAutoClearDays() },
            )

            SettingsValueRow(
                label = stringResource(R.string.sets_notif_mark_read),
                sublabel = stringResource(R.string.sets_notif_mark_read_help),
                value = if (state.unreadCount > 0) {
                    pluralStringResource(R.plurals.sets_notif_unread, state.unreadCount, state.unreadCount)
                } else {
                    stringResource(R.string.sets_notif_none_unread)
                },
                focusKey = "notifications_mark_all_read",
                onClick = { viewModel.markAllRead() },
            )

            // Always available, and it can never cancel anything: running work is in-flight state,
            // not a stored entry, so there is nothing here for a clear to reach.
            SettingsValueRow(
                label = stringResource(R.string.sets_notif_clear_all),
                sublabel = stringResource(R.string.sets_notif_clear_all_help),
                value = pluralStringResource(R.plurals.sets_notif_stored, state.storedCount, state.storedCount),
                focusKey = "notifications_clear_all",
                onClick = { viewModel.clearAll() },
            )
        }
    }
}
