package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpModalSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.feature.launcher.LaunchSource
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.EmulatorAssignmentUiState
import com.playfieldportal.feature.settings.viewmodel.EmulatorAssignmentViewModel
import com.playfieldportal.feature.settings.viewmodel.PlatformAssignRow

// The per-system half of B4 ("emulator and core assignment clarity"): one row per platform with
// games, showing which emulator + core its games resolve to today and how many games override
// that choice. Drilling in lists every installed emulator that can run the platform (catalog
// recommendation flagged), lets the user set the console default without touching any game, and
// bulk-clears per-game overrides — scoped to this one platform and always confirmed.

private val RecommendedBadgeColor = Color(0xFF4A9EFF)
private val DefaultBadgeColor = Color(0xFF45C46A)
private val WarnColor = Color(0xFFFF6B6B)

@Composable
fun EmulatorAssignmentScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmulatorAssignmentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val row = state.detailRow

    if (row == null) {
        AssignmentListContent(
            state        = state,
            onBack       = onBack,
            onOpenDetail = viewModel::openDetail,
            modifier     = modifier,
        )
    } else {
        AssignmentDetailContent(
            row                    = row,
            onBack                 = { if (!viewModel.onBack()) onBack() },
            onSelectDefault        = viewModel::selectDefault,
            onUseAutomatic         = viewModel::useAutomaticDefault,
            confirmClearCount      = state.confirmClearPlatformId?.let { id ->
                state.platforms.firstOrNull { it.platformId == id }?.overrideCount
            },
            onRequestClearOverrides = viewModel::requestClearOverrides,
            onCancelClear          = viewModel::cancelClearOverrides,
            onConfirmClear         = viewModel::confirmClearOverrides,
            modifier               = modifier,
        )
    }
}

// ── Platform list ─────────────────────────────────────────────────────────────

@Composable
private fun AssignmentListContent(
    state: EmulatorAssignmentUiState,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(
        title           = stringResource(R.string.lib_emu_title),
        subtitle        = stringResource(R.string.lib_assign_title),
        onBack          = onBack,
        modifier        = modifier,
        restoreFocusKey = state.returnFocusKey,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(stringResource(R.string.lib_assign_group_consoles))
            if (state.platforms.isEmpty()) {
                EmulatorHint(stringResource(R.string.lib_assign_none))
            } else {
                state.platforms.forEach { platform ->
                    SettingsRow(
                        label    = platform.platformName,
                        sublabel = listSublabel(platform),
                        focusKey = platform.platformId,
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                            ) {
                                when {
                                    platform.overrideCount > 0 ->
                                        Text(
                                            stringResource(R.string.lib_assign_overridden, platform.overrideCount),
                                            color = SettingsAccent,
                                            fontSize = 11.sp,
                                        )
                                    platform.isMissingCore -> Badge(stringResource(R.string.lib_assign_badge_core_missing), WarnColor)
                                    platform.defaultDisplayName == null ->
                                        Badge(stringResource(R.string.lib_assign_badge_no_emulator), WarnColor)
                                }
                            }
                        },
                        onClick = { onOpenDetail(platform.platformId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun listSublabel(platform: PlatformAssignRow): String = listOfNotNull(
    platform.defaultDisplayName?.let { stringResource(R.string.lib_assign_default_is, it) }
        ?: stringResource(R.string.lib_assign_no_emulator),
    platform.resolvedCoreName?.let { stringResource(R.string.lib_assign_core_is, it) },
    pluralStringResource(R.plurals.lib_assign_game_count, platform.gameCount, platform.gameCount),
).joinToString(" · ")

// ── Per-platform detail ───────────────────────────────────────────────────────

@Composable
private fun AssignmentDetailContent(
    row: PlatformAssignRow,
    onBack: () -> Unit,
    onSelectDefault: (platformId: String, profileId: String) -> Unit,
    onUseAutomatic: (platformId: String) -> Unit,
    // Non-null while the bulk-clear confirm dialog is up (the count it will clear).
    confirmClearCount: Int?,
    onRequestClearOverrides: () -> Unit,
    onCancelClear: () -> Unit,
    onConfirmClear: () -> Unit,
    modifier: Modifier,
) {
    val modal = rememberSettingsModal(
        if (confirmClearCount != null && confirmClearCount > 0) {
            PfpModalSpec.Confirm(
                key = "clear_overrides:${row.platformId}",
                title = pluralStringResource(R.plurals.lib_assign_clear_title, confirmClearCount, confirmClearCount),
                message = stringResource(R.string.lib_assign_clear_message, row.platformName),
                confirmLabel = stringResource(R.string.lib_assign_clear),
                destructive = true,
                onConfirm = onConfirmClear,
                onCancel = onCancelClear,
            )
        } else {
            null
        },
    )

    SettingsScaffold(
        title    = stringResource(R.string.lib_emu_title),
        subtitle = row.platformName,
        onBack   = onBack,
        modifier = modifier,
        modalOpen = modal.open,
        onInterceptAction = modal.intercept,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(stringResource(R.string.lib_assign_group_current))
            SettingsValueRow(
                label   = stringResource(R.string.lib_assign_emulator),
                value   = row.defaultDisplayName ?: stringResource(R.string.lib_assign_none_value),
                sublabel = sourceLine(row),
            )
            row.resolvedCoreName?.let { core ->
                SettingsValueRow(label = stringResource(R.string.lib_assign_ra_core), value = core)
            }

            SettingsGroup(stringResource(R.string.lib_assign_group_choose))
            SettingsRow(
                label    = stringResource(R.string.lib_assign_automatic),
                sublabel = stringResource(R.string.lib_assign_automatic_sub),
                trailing = { if (row.isAutomatic) Badge(stringResource(R.string.lib_assign_badge_active), DefaultBadgeColor) },
                onClick  = { onUseAutomatic(row.platformId) },
            )
            if (row.candidates.isEmpty()) {
                EmulatorHint(stringResource(R.string.lib_assign_no_candidates))
            } else {
                row.candidates.forEach { candidate ->
                    val profile = candidate.profile
                    SettingsRow(
                        label    = profile.name,
                        sublabel = profile.packageName,
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                            ) {
                                if (candidate.isDefault) Badge(stringResource(R.string.lib_assign_badge_default), DefaultBadgeColor)
                                if (candidate.isRecommended) Badge(stringResource(R.string.lib_assign_badge_recommended), RecommendedBadgeColor)
                            }
                        },
                        onClick = { onSelectDefault(row.platformId, profile.id) },
                    )
                }
            }

            SettingsGroup(stringResource(R.string.lib_assign_group_overrides))
            SettingsValueRow(
                label    = stringResource(R.string.lib_assign_overrides_label),
                value    = stringResource(R.string.lib_assign_overrides_value, row.overrideCount, row.gameCount),
                sublabel = stringResource(
                    if (row.overrideCount == 0) R.string.lib_assign_overrides_none else R.string.lib_assign_overrides_some,
                ),
            )
            if (row.overrideCount > 0) {
                SettingsRow(
                    label    = pluralStringResource(R.plurals.lib_assign_clear_all, row.overrideCount, row.overrideCount),
                    sublabel = stringResource(R.string.lib_assign_clear_all_sub),
                    onClick  = onRequestClearOverrides,
                )
            }
        }
    }

    modal.Content()
}

@Composable
private fun Badge(label: String, color: Color) {
    Text(
        text     = label,
        color    = color,
        fontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun sourceLine(row: PlatformAssignRow): String = when (row.source) {
    LaunchSource.MEMORY_CARD      -> stringResource(R.string.lib_assign_source_card)
    LaunchSource.PLATFORM_DEFAULT -> stringResource(R.string.lib_assign_source_platform)
    // Never produced on this screen (per-game overrides are excluded from the ladder inputs).
    LaunchSource.PER_GAME_OVERRIDE -> stringResource(R.string.lib_assign_source_game)
    LaunchSource.CATALOG_DEFAULT  -> stringResource(R.string.lib_assign_source_auto)
    null -> when {
        row.storedDefaultName != null ->
            stringResource(R.string.lib_assign_source_stored_missing, row.storedDefaultName)
        row.platformDefaultId != null ->
            stringResource(R.string.lib_assign_source_platform_missing)
        else -> stringResource(R.string.lib_assign_source_none)
    }
}

@Composable
private fun EmulatorHint(text: String) {
    Text(
        text     = text,
        color    = SettingsSubtext,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
    )
}
