package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.launcher.LaunchSource
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
            message                = state.message,
            onDismissMessage       = viewModel::dismissMessage,
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
        title           = "Emulators",
        subtitle        = "Per-System Defaults",
        onBack          = onBack,
        modifier        = modifier,
        restoreFocusKey = state.returnFocusKey,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup("Consoles With Games")
            if (state.platforms.isEmpty()) {
                EmulatorHint(
                    "No consoles with games yet — scan a console first, then set its default " +
                        "emulator and core here."
                )
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
                                            "${platform.overrideCount} overridden",
                                            color = SettingsAccent,
                                            fontSize = 11.sp,
                                        )
                                    platform.isMissingCore -> Badge("CORE MISSING", WarnColor)
                                    platform.defaultDisplayName == null ->
                                        Badge("NO EMULATOR", WarnColor)
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

private fun listSublabel(platform: PlatformAssignRow): String {
    val default = platform.defaultDisplayName?.let { "Default: $it" } ?: "No emulator"
    val core = platform.resolvedCoreName?.let { " · Core: $it" } ?: ""
    val games = "${platform.gameCount} game${if (platform.gameCount == 1) "" else "s"}"
    return "$default$core · $games"
}

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
    message: String?,
    onDismissMessage: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(
        title    = "Emulators",
        subtitle = row.platformName,
        onBack   = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup("Current Default")
            SettingsValueRow(
                label   = "Emulator",
                value   = row.defaultDisplayName ?: "None",
                sublabel = sourceLine(row),
            )
            row.resolvedCoreName?.let { core ->
                SettingsValueRow(label = "RetroArch Core", value = core)
            }

            SettingsGroup("Choose the Default")
            SettingsRow(
                label    = "Automatic (Recommended)",
                sublabel = "First installed emulator — a standalone before RetroArch cores",
                trailing = { if (row.isAutomatic) Badge("ACTIVE", DefaultBadgeColor) },
                onClick  = { onUseAutomatic(row.platformId) },
            )
            if (row.candidates.isEmpty()) {
                EmulatorHint(
                    "No installed emulator can run this platform. Install one — or link RetroArch " +
                        "and let core detection generate profiles — and launches resolve automatically."
                )
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
                                if (candidate.isDefault) Badge("DEFAULT", DefaultBadgeColor)
                                if (candidate.isRecommended) Badge("RECOMMENDED", RecommendedBadgeColor)
                            }
                        },
                        onClick = { onSelectDefault(row.platformId, profile.id) },
                    )
                }
            }

            SettingsGroup("Per-Game Overrides")
            SettingsValueRow(
                label    = "Games with a custom emulator",
                value    = "${row.overrideCount} of ${row.gameCount}",
                sublabel = if (row.overrideCount == 0) {
                    "None — every game follows the default above"
                } else {
                    "These games launch with their own pinned emulator and ignore the default"
                },
            )
            if (row.overrideCount > 0) {
                SettingsRow(
                    label    = "Clear All ${row.overrideCount} Override(s)",
                    sublabel = "Reset these games to follow the platform default",
                    onClick  = onRequestClearOverrides,
                )
            }

            message?.let { EmulatorHint(it) }
        }
    }

    if (confirmClearCount != null && confirmClearCount > 0) {
        ClearOverridesDialog(
            platformName = row.platformName,
            count        = confirmClearCount,
            onConfirm    = onConfirmClear,
            onCancel     = onCancelClear,
        )
    }
}

@Composable
private fun ClearOverridesDialog(
    platformName: String,
    count: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Clear $count override${if (count == 1) "" else "s"}?") },
        text = {
            Text(
                "These $platformName games have their own pinned emulator and ignore this " +
                    "platform's default. Clear them so every game on $platformName uses the default."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Clear", color = WarnColor) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
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

private fun sourceLine(row: PlatformAssignRow): String = when (row.source) {
    LaunchSource.MEMORY_CARD      -> "Console default — set here or in Library Manager"
    LaunchSource.PLATFORM_DEFAULT -> "Platform default"
    // Never produced on this screen (per-game overrides are excluded from the ladder inputs).
    LaunchSource.PER_GAME_OVERRIDE -> "Per-game override"
    LaunchSource.CATALOG_DEFAULT  -> "Automatic — first installed emulator"
    null -> when {
        row.storedDefaultName != null ->
            "Stored default (${row.storedDefaultName}) is not installed or can't run this platform"
        row.platformDefaultId != null ->
            "Platform default is not installed or can't run this platform"
        else -> "No emulator installed for this platform"
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
