package com.playfieldportal.feature.settings.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.artwork.api.ArtworkFolderPickerUris
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.ArtworkImportUiState
import com.playfieldportal.feature.settings.viewmodel.ArtworkImportViewModel
import java.util.Locale

/**
 * Artwork Folder & Import — links the user-owned artwork library folder and imports existing
 * artwork dropped under `import/<Launcher>` (ES-DE in V1). PSP-minimal: plain rows, no wizardry;
 * every destructive step is explicit.
 */
@Composable
fun ArtworkImportScreen(
    onBack: () -> Unit,
    // Opens the Unmatched Artwork picker. Routed by the host, like every other cross-screen jump.
    onOpenOrphans: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ArtworkImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.onFolderPicked(it) } }

    val unavailable = state.folderLinked && !state.grantAlive

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.startExport(it) } }

    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.setd_import_title),
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
            // ── Artwork folder ────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.setd_import_group_folder))

            SettingsRow(
                label    = stringResource(if (state.folderLinked) R.string.setd_import_change_folder else R.string.setd_import_choose_folder),
                sublabel = when {
                    !state.folderLinked -> stringResource(R.string.setd_import_folder_not_set)
                    unavailable         -> stringResource(R.string.setd_artwork_folder_unavailable_sublabel)
                    else                -> state.folderDisplay
                },
                labelTrailing = if (unavailable) ({
                    KnowledgeBadge(stringResource(R.string.setd_unavailable), WarningAmber)
                }) else null,
                // A dead folder reopens the picker on itself; every other pick starts at the device root.
                onClick  = {
                    val start = state.folderTreeUri?.takeIf { unavailable }
                        ?.let { ArtworkFolderPickerUris.forRelink(Uri.parse(it)) }
                    folderPicker.launch(start ?: ArtworkFolderPickerUris.deviceRoot())
                },
            )

            // ── Import sources ────────────────────────────────────────────────
            if (state.folderLinked && state.grantAlive) {
                SettingsGroup(stringResource(R.string.setd_import_group_existing))

                if (state.sources.isEmpty() && !state.scanning) {
                    SettingsRow(
                        label    = stringResource(R.string.setd_import_no_launchers),
                        sublabel = stringResource(R.string.setd_import_no_launchers_sublabel),
                    )
                }

                state.sources.forEach { source ->
                    SettingsRow(
                        label    = source.label,
                        sublabel = pluralStringResource(R.plurals.setd_import_source_systems, source.systems, source.systems),
                        onClick  = if (state.planning || state.importRunning) null
                        else ({ viewModel.buildPreview(source) }),
                    )
                }

                state.unrecognized.forEach { name ->
                    SettingsRow(
                        label    = name,
                        sublabel = stringResource(R.string.setd_import_unrecognized),
                    )
                }

                SettingsRow(
                    label    = stringResource(if (state.scanning) R.string.setd_import_scanning else R.string.setd_import_rescan),
                    sublabel = stringResource(R.string.setd_import_rescan_sublabel),
                    onClick  = if (state.scanning) null else ({ viewModel.rescan() }),
                )

                SettingsRow(
                    label    = stringResource(if (state.relinking) R.string.setd_import_relinking else R.string.setd_import_relink),
                    sublabel = stringResource(R.string.setd_import_relink_sublabel),
                    onClick  = if (state.relinking || state.importRunning) null else ({ viewModel.relinkLibrary() }),
                )

                // Whatever the last scan could not place. Hidden when there is nothing to resolve,
                // so the row is a to-do list rather than a permanent piece of furniture.
                if (state.orphanCount > 0) {
                    SettingsRow(
                        label    = stringResource(R.string.setd_import_unmatched, state.orphanCount),
                        sublabel = stringResource(R.string.setd_import_unmatched_sublabel),
                        onClick  = onOpenOrphans,
                    )
                }

                // ── Internal-storage migration (M-F2) ────────────────────────
                if (state.internalFiles > 0 || state.migrationRunning) {
                    SettingsGroup(stringResource(R.string.setd_import_group_storage))
                    if (state.migrationRunning) {
                        SettingsRow(
                            label    = stringResource(R.string.setd_import_moving),
                            sublabel = if (state.migrationTotal > 0)
                                stringResource(R.string.setd_import_moving_progress, state.migrationDone, state.migrationTotal)
                            else stringResource(R.string.setd_import_moving_background),
                        )
                        SettingsRow(
                            label    = stringResource(R.string.setd_import_cancel_move),
                            sublabel = stringResource(R.string.setd_import_cancel_move_sublabel),
                            onClick  = { viewModel.cancelInternalMigration() },
                        )
                    } else {
                        SettingsRow(
                            label    = stringResource(R.string.setd_import_move_in),
                            sublabel = pluralStringResource(
                                R.plurals.setd_import_move_in_sublabel,
                                state.internalFiles,
                                state.internalFiles,
                                formatBytes(state.internalBytes),
                            ),
                            onClick  = if (state.importRunning) null else ({ viewModel.startInternalMigration() }),
                        )
                    }
                }

                SettingsGroup(stringResource(R.string.setd_import_group_export))

                SettingsRow(
                    label    = stringResource(R.string.setd_import_export),
                    sublabel = stringResource(R.string.setd_import_export_sublabel),
                    onClick  = if (state.importRunning) null else ({ exportPicker.launch(null) }),
                )
            }

            // ── Preview ───────────────────────────────────────────────────────
            if (state.planning) {
                SettingsGroup(stringResource(R.string.setd_import_group_preview))
                SettingsRow(label = stringResource(R.string.setd_import_building), sublabel = stringResource(R.string.setd_import_building_sublabel))
            }

            state.plan?.let { plan ->
                SettingsGroup(stringResource(R.string.setd_import_preview_group, plan.sourceLabel))

                SettingsValueRow(label = stringResource(R.string.setd_import_games_matched),     value = plan.games.size.toString())
                SettingsValueRow(label = stringResource(R.string.setd_import_files_to_import),   value = plan.itemCount.toString())
                plan.countsByKind().entries.sortedBy { it.key }.forEach { (kind, count) ->
                    SettingsValueRow(label = "   " + kindLabel(kind), value = count.toString())
                }
                SettingsValueRow(label = stringResource(R.string.setd_import_estimated_size),    value = formatBytes(plan.totalBytes))
                SettingsValueRow(
                    label    = stringResource(R.string.setd_import_details_found),
                    sublabel = if (plan.metadataUpdates.isEmpty())
                        stringResource(R.string.setd_import_details_none_sublabel)
                    else stringResource(R.string.setd_import_details_some_sublabel),
                    value    = plan.metadataUpdates.size.toString(),
                )
                SettingsValueRow(label = stringResource(R.string.setd_import_already_present),   value = plan.skippedExistingCount.toString())
                SettingsValueRow(label = stringResource(R.string.setd_import_needs_review),      value = plan.ambiguous.size.toString())
                SettingsValueRow(label = stringResource(R.string.setd_import_unmatched_artwork), value = plan.unmatchedCount.toString())

                if (plan.unknownSystemFolders.isNotEmpty()) {
                    SettingsRow(
                        label    = stringResource(R.string.setd_import_skipped_folders),
                        sublabel = plan.unknownSystemFolders.joinToString(", "),
                    )
                }

                if (plan.platformsWithoutGames.isNotEmpty()) {
                    SettingsRow(
                        label    = stringResource(R.string.setd_import_no_games),
                        sublabel = stringResource(R.string.setd_import_no_games_sublabel, plan.platformsWithoutGames.joinToString(", ")),
                    )
                }

                SettingsToggleRow(
                    label    = stringResource(R.string.setd_import_move_files),
                    sublabel = stringResource(R.string.setd_import_move_files_sublabel),
                    checked  = state.moveFiles,
                    onToggle = { viewModel.toggleMoveFiles(it) },
                )

                // ── Ambiguous review ─────────────────────────────────────────
                if (plan.ambiguous.isNotEmpty()) {
                    SettingsGroup(stringResource(R.string.setd_import_review_group, plan.ambiguous.size))
                    plan.ambiguous.forEachIndexed { index, entry ->
                        SettingsRow(
                            label    = entry.candidate.displayName,
                            sublabel = pluralStringResource(R.plurals.setd_import_possible_games, entry.gameTitles.size, entry.gameTitles.size),
                            onClick  = { viewModel.toggleAmbiguous(index) },
                        )
                        if (state.expandedAmbiguousIndex == index) {
                            entry.gameIds.forEachIndexed { gi, gameId ->
                                SettingsRow(
                                    label   = "   " + (entry.gameTitles.getOrNull(gi) ?: stringResource(R.string.setd_import_game_number, gameId)),
                                    sublabel = stringResource(R.string.setd_import_assign_sublabel),
                                    onClick = { viewModel.assignAmbiguous(index, gameId) },
                                )
                            }
                            SettingsRow(
                                label   = "   " + stringResource(R.string.setd_import_skip_file),
                                onClick = { viewModel.skipAmbiguous(index) },
                            )
                        }
                    }
                }

                if (!state.importRunning) {
                    SettingsRow(
                        label    = stringResource(R.string.setd_import_start),
                        sublabel = pluralStringResource(
                            if (state.moveFiles) R.plurals.setd_import_start_move else R.plurals.setd_import_start_copy,
                            plan.itemCount,
                            plan.itemCount,
                        ),
                        onClick  = if (plan.itemCount > 0) ({ viewModel.startImport() }) else null,
                    )
                    SettingsRow(label = stringResource(R.string.setd_import_cancel_preview), onClick = { viewModel.dismissPreview() })
                }
            }

            // ── Running import ────────────────────────────────────────────────
            if (state.importRunning) {
                SettingsGroup(stringResource(R.string.setd_import_group_importing))
                Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 10.dp)) {
                    LinearProgressIndicator(
                        // Coerced: a per-game failure counts its remaining items in bulk, which
                        // can briefly overshoot the per-item counter.
                        progress = {
                            if (state.importTotal > 0)
                                (state.importDone.toFloat() / state.importTotal).coerceIn(0f, 1f)
                            else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = SettingsAccent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = stringResource(R.string.setd_scrape_progress, state.importDone, state.importTotal, state.importLabel),
                        color = SettingsText,
                    )
                    Text(
                        text  = stringResource(R.string.setd_scrape_background),
                        color = SettingsSubtext,
                    )
                }
                SettingsRow(
                    label    = stringResource(R.string.setd_import_cancel),
                    sublabel = stringResource(R.string.setd_import_cancel_sublabel),
                    onClick  = { viewModel.cancelImport() },
                )
            }

            // ── Reports ───────────────────────────────────────────────────────
            if (state.reports.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.setd_import_group_report))
                state.reports.forEach { report ->
                    SettingsRow(label = report.title.asString(), sublabel = report.detail.asString())
                }
                SettingsRow(
                    label    = stringResource(R.string.setd_import_clear_reports),
                    onClick  = { viewModel.clearReports() },
                )
            }

            // ── Notices ───────────────────────────────────────────────────────
            state.notice?.let {
                SettingsRow(label = it.asString(), sublabel = stringResource(R.string.setd_tap_to_dismiss), onClick = { viewModel.dismissError() })
            }
            state.error?.let {
                SettingsRow(
                    label = stringResource(R.string.setd_error),
                    sublabel = stringResource(R.string.setd_error_tap_to_dismiss, it.asString()),
                    onClick = { viewModel.dismissError() },
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "ICON" -> stringResource(R.string.setd_kind_icon)
    "HERO" -> stringResource(R.string.setd_kind_hero)
    "BACKGROUND" -> stringResource(R.string.setd_kind_background)
    "LOGO" -> stringResource(R.string.setd_kind_logo)
    "SCREENSHOT" -> stringResource(R.string.setd_kind_screenshot)
    "TITLESCREEN" -> stringResource(R.string.setd_kind_titlescreen)
    "PHYSICAL_MEDIA" -> stringResource(R.string.setd_kind_physical_media)
    "MANUAL" -> stringResource(R.string.setd_kind_manual)
    else -> kind.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
