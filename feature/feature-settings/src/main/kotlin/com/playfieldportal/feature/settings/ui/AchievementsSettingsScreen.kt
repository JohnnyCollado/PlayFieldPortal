package com.playfieldportal.feature.settings.ui

import android.content.res.Resources
import com.playfieldportal.core.ui.components.PfpModalSpec
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.ui.achievement.ShibaPlayerCard
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.AchievementsSettingsViewModel

enum class AchievementsSettingsSection { PROVIDER_CREDENTIALS, LOCAL_WINDOWS, UPDATE }

@Composable
fun AchievementsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlayerStatus: () -> Unit = {},
    section: AchievementsSettingsSection? = null,
    viewModel: AchievementsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var raUsernameDraft by remember(state.raUsername) { mutableStateOf("") }
    var raKeyDraft by remember(state.hasRetroAchievements) { mutableStateOf("") }
    var steamIdDraft by remember(state.steamId64) { mutableStateOf("") }
    var steamKeyDraft by remember(state.hasSteam) { mutableStateOf("") }
    var showLocalSteamWarning by remember { mutableStateOf(false) }
    var showGoldbergWarning by remember { mutableStateOf(false) }

    val credentialsEnabled = state.enabled
    val credentialsAlpha = if (credentialsEnabled) 1f else 0.45f

    val modal = rememberSettingsModal(
        when {
            state.confirmClearVisible -> PfpModalSpec.Confirm(
                key = "clear_all",
                title = stringResource(R.string.setd_ach_clear_title),
                message = stringResource(R.string.setd_ach_clear_message),
                confirmLabel = stringResource(R.string.setd_ach_clear_confirm),
                // Opens on Cancel, so a stray Confirm press on the controller dismisses.
                destructive = true,
                onConfirm = viewModel::confirmClearAll,
                onCancel = viewModel::dismissClearAll,
            )
            showLocalSteamWarning -> PfpModalSpec.Confirm(
                key = "local_steam_warning",
                title = stringResource(R.string.setd_ach_backup_title),
                message = stringResource(R.string.setd_ach_local_steam_message),
                confirmLabel = stringResource(R.string.setd_ach_backup_confirm),
                openOnCancel = true,
                onConfirm = {
                    viewModel.setLocalSteamTracking(true)
                    showLocalSteamWarning = false
                },
                onCancel = { showLocalSteamWarning = false },
            )
            showGoldbergWarning -> goldbergWarningSpec(
                onConfirm = {
                    viewModel.setGoldbergInstaller(true)
                    showGoldbergWarning = false
                },
                onCancel = { showGoldbergWarning = false },
            )
            else -> null
        },
    )

    SettingsScaffold(
        modalOpen = modal.open,
        onInterceptAction = modal.intercept,
        title    = stringResource(R.string.settings_title),
        subtitle = when (section) {
            AchievementsSettingsSection.PROVIDER_CREDENTIALS -> stringResource(R.string.setd_ach_subtitle_credentials)
            AchievementsSettingsSection.LOCAL_WINDOWS -> stringResource(R.string.setd_ach_subtitle_local_windows)
            AchievementsSettingsSection.UPDATE -> stringResource(R.string.setd_ach_subtitle_update)
            null -> stringResource(R.string.setd_ach_subtitle_coins)
        },
        onBack   = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {

            // Account-wide standing: level, rank, and the running coin wallet. Confirm or tap opens
            // the fullscreen player status view. It is part of the combined legacy screen only;
            // the dedicated Player Card L2 route is handled by the host overlay.
            if (section == null) SettingsFocusable(
                onClick = onOpenPlayerStatus,
                focusKey = "player_card",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) { focused ->
                ShibaPlayerCard(
                    wallet = state.wallet,
                    modifier = if (focused) {
                        Modifier.border(2.dp, menuCursorEdge(), RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                )
            }

            if (section == null || section == AchievementsSettingsSection.PROVIDER_CREDENTIALS) {
            SettingsToggleRow(
                label    = stringResource(R.string.setd_ach_enable),
                sublabel = stringResource(R.string.setd_ach_enable_sublabel),
                checked  = state.enabled,
                onToggle = { viewModel.setEnabled(it) },
            )

            }

            if (section == null || section == AchievementsSettingsSection.PROVIDER_CREDENTIALS) {
            // ── RetroAchievements ─────────────────────────────────────────────
            SettingsGroup("RetroAchievements")

            if (state.hasRetroAchievements) {
                SettingsValueRow(label = stringResource(R.string.setd_ach_connected_as), value = state.raUsername)
            }
            Column(modifier = Modifier.alpha(credentialsAlpha)) {
            SettingsTextFieldRow(
                label         = stringResource(R.string.setd_ach_username),
                value         = raUsernameDraft,
                onValueChange = { raUsernameDraft = it },
                placeholder   = state.raUsername.ifBlank { stringResource(R.string.setd_ach_ra_username_placeholder) },
                enabled       = credentialsEnabled,
            )
            SettingsTextFieldRow(
                label         = stringResource(if (state.hasRetroAchievements) R.string.setd_ach_web_api_key_saved else R.string.setd_ach_web_api_key),
                value         = raKeyDraft,
                onValueChange = { raKeyDraft = it },
                placeholder   = stringResource(if (state.hasRetroAchievements) R.string.setd_key_replace_placeholder else R.string.setd_ach_ra_key_placeholder),
                isPassword    = true,
                helper        = stringResource(R.string.setd_ach_ra_key_helper),
                enabled       = credentialsEnabled,
            )
            if (credentialsEnabled && raUsernameDraft.isNotBlank() && raKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = stringResource(R.string.setd_ach_ra_connect),
                    onClick = {
                        viewModel.connectRetroAchievements(raUsernameDraft, raKeyDraft)
                        raUsernameDraft = ""
                        raKeyDraft = ""
                    },
                )
            }
            if (state.hasRetroAchievements) {
                SettingsRow(
                    label    = stringResource(R.string.setd_ach_ra_disconnect),
                    sublabel = stringResource(R.string.setd_ach_ra_disconnect_sublabel),
                    onClick  = { viewModel.disconnectRetroAchievements() },
                )
            }
            }

            // ── Steam ──────────────────────────────────────────────────────────
            SettingsGroup("Steam")

            if (state.hasSteam) {
                SettingsValueRow(label = "SteamID64", value = state.steamId64)
            }
            Column(modifier = Modifier.alpha(credentialsAlpha)) {
            SettingsTextFieldRow(
                label         = stringResource(R.string.setd_ach_steam_id),
                value         = steamIdDraft,
                onValueChange = { steamIdDraft = it },
                placeholder   = state.steamId64.ifBlank { stringResource(R.string.setd_ach_steam_id_placeholder) },
                helper        = stringResource(R.string.setd_ach_steam_id_helper),
                enabled       = credentialsEnabled,
            )
            SettingsTextFieldRow(
                label         = stringResource(if (state.hasSteam) R.string.setd_api_key_saved else R.string.setd_api_key),
                value         = steamKeyDraft,
                onValueChange = { steamKeyDraft = it },
                placeholder   = stringResource(if (state.hasSteam) R.string.setd_key_replace_placeholder else R.string.setd_ach_steam_key_placeholder),
                isPassword    = true,
                helper        = stringResource(R.string.setd_ach_steam_key_helper),
                enabled       = credentialsEnabled,
            )
            if (credentialsEnabled && steamIdDraft.isNotBlank() && steamKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = stringResource(R.string.setd_ach_steam_connect),
                    onClick = {
                        viewModel.connectSteam(steamIdDraft, steamKeyDraft)
                        steamIdDraft = ""
                        steamKeyDraft = ""
                    },
                )
            }
            if (state.hasSteam) {
                SettingsRow(
                    label    = stringResource(R.string.setd_ach_steam_disconnect),
                    sublabel = stringResource(R.string.setd_ach_steam_disconnect_sublabel),
                    onClick  = { viewModel.disconnectSteam() },
                )
            }
            }
            }

            // ── Local Steam (emulated) ─────────────────────────────────────────
            if (section == null || section == AchievementsSettingsSection.LOCAL_WINDOWS) {
            SettingsGroup(stringResource(R.string.setd_ach_group_local_steam))
            SettingsToggleRow(
                label    = stringResource(R.string.setd_ach_track_local),
                sublabel = stringResource(R.string.setd_ach_track_local_sublabel),
                checked  = state.localSteamTrackingEnabled,
                onToggle = { on ->
                    // Enabling is gated behind the save-backup warning; disabling is immediate.
                    if (on) showLocalSteamWarning = true else viewModel.setLocalSteamTracking(false)
                },
            )
            SettingsToggleRow(
                label    = stringResource(R.string.setd_ach_goldberg),
                sublabel = stringResource(R.string.setd_ach_goldberg_sublabel),
                checked  = state.goldbergInstallerEnabled,
                onToggle = { on ->
                    // Converting rewrites game folders, so enabling is gated behind the same
                    // save-backup warning; disabling is immediate.
                    if (on) showGoldbergWarning = true else viewModel.setGoldbergInstaller(false)
                },
            )

            }

            // Update Achievements: the selective update (present, matched games only — never an
            // account-wide import), Auto-match, and, in its own group below them, the confirmed
            // Clear all tracked achievements. Outcomes go to the tray; only live progress and the
            // paused state are shown here.
            if (section == null || section == AchievementsSettingsSection.UPDATE) {
                SettingsGroup(stringResource(R.string.setd_ach_group_update))
                SettingsValueRow(label = stringResource(R.string.setd_ach_last_updated), value = state.lastSyncedLabel.asString())
                if (state.updatesPaused) {
                    SettingsValueRow(label = stringResource(R.string.setd_ach_auto_updates), value = stringResource(R.string.setd_ach_updates_paused))
                }
                if (state.isSyncing) {
                    SettingsValueRow(label = stringResource(R.string.setd_ach_updating), value = "${state.syncDone} / ${state.syncTotal}")
                } else {
                    SettingsRow(
                        label = stringResource(R.string.setd_ach_update_installed),
                        sublabel = when {
                            state.isMatching -> stringResource(R.string.setd_ach_update_after_match)
                            state.updatesPaused -> stringResource(R.string.setd_ach_update_resume)
                            else -> stringResource(R.string.setd_ach_update_check)
                        },
                        enabled = !state.isClearing,
                        onClick = { viewModel.updateInstalledAchievements() },
                    )
                }

                SettingsGroup(stringResource(R.string.setd_ach_group_auto_match))
                if (state.isMatching) {
                    SettingsValueRow(label = stringResource(R.string.setd_ach_matching), value = "${state.matchDone} / ${state.matchTotal}")
                } else {
                    SettingsRow(
                        label = stringResource(R.string.setd_ach_auto_match),
                        sublabel = stringResource(R.string.setd_ach_auto_match_sublabel),
                        enabled = !state.isClearing,
                        onClick = { viewModel.autoMatch() },
                    )
                }

                // Kept apart from the update actions so it is never pressed in passing, and never
                // a one-press action: it only opens the confirmation below.
                SettingsGroup(stringResource(R.string.setd_ach_group_reset))
                if (state.isClearing) {
                    SettingsValueRow(label = stringResource(R.string.setd_ach_clearing), value = "")
                } else {
                    SettingsRow(
                        label = stringResource(R.string.setd_ach_clear_row),
                        sublabel = stringResource(R.string.setd_ach_clear_row_sublabel),
                        focusKey = "achievements_clear_all",
                        onClick = { viewModel.requestClearAll() },
                    )
                }
            }
        }
    }

    modal.Content()
}

/**
 * The save-backup warning that turning on the Goldberg installer always shows first — here and in
 * Library Manager, which calls itself the same switch (survey U32). Opens on Cancel.
 */
@Composable
internal fun goldbergWarningSpec(onConfirm: () -> Unit, onCancel: () -> Unit): PfpModalSpec.Confirm {
    // Read so a language change rebuilds the text, as stringResource does.
    LocalConfiguration.current
    return goldbergWarningSpec(LocalContext.current.resources, onConfirm, onCancel)
}

/** The same warning for callers outside composition (and the tests), resolved from [resources]. */
internal fun goldbergWarningSpec(
    resources: Resources,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.Confirm = PfpModalSpec.Confirm(
    key = "goldberg_warning",
    title = resources.getString(R.string.setd_ach_backup_title),
    message = resources.getString(R.string.setd_ach_goldberg_message),
    confirmLabel = resources.getString(R.string.setd_ach_backup_confirm),
    openOnCancel = true,
    onConfirm = onConfirm,
    onCancel = onCancel,
)
