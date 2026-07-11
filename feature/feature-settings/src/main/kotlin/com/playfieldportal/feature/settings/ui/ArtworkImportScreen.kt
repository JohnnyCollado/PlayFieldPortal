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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
    modifier: Modifier = Modifier,
    viewModel: ArtworkImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.onFolderPicked(it) } }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.startExport(it) } }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Artwork Folder & Import",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Artwork folder ────────────────────────────────────────────────
            SettingsGroup("Artwork Folder")

            SettingsRow(
                label    = "Folder",
                sublabel = when {
                    !state.folderLinked -> "Not set — tap to choose where PFP keeps artwork"
                    !state.grantAlive   -> "${state.folderDisplay}  —  access lost, tap to re-link"
                    else                -> "${state.folderDisplay}  (tap to change)"
                },
                onClick  = { folderPicker.launch(null) },
            )

            if (state.folderLinked) {
                SettingsRow(
                    label    = if (state.confirmForget) "Tap again to confirm" else "Forget Folder",
                    sublabel = "Releases PFP's access. Nothing on disk is deleted — the artwork " +
                        "stays yours in the folder.",
                    onClick  = { viewModel.forgetFolder() },
                )
            }

            // ── Import sources ────────────────────────────────────────────────
            if (state.folderLinked && state.grantAlive) {
                SettingsGroup("Import Existing Artwork")

                if (state.sources.isEmpty() && !state.scanning) {
                    SettingsRow(
                        label    = "No launchers found",
                        sublabel = "Copy or move another launcher's media into the folder first, " +
                            "e.g. import/ES-DE/downloaded_media. Moving frees space but removes " +
                            "the artwork from that launcher.",
                    )
                }

                state.sources.forEach { source ->
                    SettingsRow(
                        label    = source.label,
                        sublabel = "${source.systems} system folders recognized — tap to preview import",
                        onClick  = if (state.planning || state.importRunning) null
                        else ({ viewModel.buildPreview(source) }),
                    )
                }

                state.unrecognized.forEach { name ->
                    SettingsRow(
                        label    = name,
                        sublabel = "Unrecognized layout — expected ES-DE downloaded_media structure",
                    )
                }

                SettingsRow(
                    label    = if (state.scanning) "Scanning…" else "Rescan Import Folder",
                    sublabel = "Re-check import/ for launcher folders",
                    onClick  = if (state.scanning) null else ({ viewModel.rescan() }),
                )

                SettingsRow(
                    label    = if (state.relinking) "Scanning Library…" else "Scan & Relink Library",
                    sublabel = "Reconnects artwork in the folder to your games, refreshes moved or " +
                        "changed files, and cleans up references to deleted ones",
                    onClick  = if (state.relinking || state.importRunning) null else ({ viewModel.relinkLibrary() }),
                )

                // ── Internal-storage migration (M-F2) ────────────────────────
                if (state.internalFiles > 0 || state.migrationRunning) {
                    SettingsGroup("App Storage")
                    if (state.migrationRunning) {
                        SettingsRow(
                            label    = "Moving artwork into your folder…",
                            sublabel = if (state.migrationTotal > 0)
                                "${state.migrationDone} / ${state.migrationTotal} — runs in the background"
                            else "Runs in the background — you can leave this screen",
                        )
                        SettingsRow(
                            label    = "Cancel Move",
                            sublabel = "Already-moved artwork stays in the folder",
                            onClick  = { viewModel.cancelInternalMigration() },
                        )
                    } else {
                        SettingsRow(
                            label    = "Move Into Folder",
                            sublabel = "${state.internalFiles} artwork files (${formatBytes(state.internalBytes)}) " +
                                "were saved before this folder was linked and live in app storage. " +
                                "Moving them makes them portable and frees app space. Locked and " +
                                "hand-picked artwork already in the folder is never overwritten.",
                            onClick  = if (state.importRunning) null else ({ viewModel.startInternalMigration() }),
                        )
                    }
                }

                SettingsGroup("Export")

                SettingsRow(
                    label    = "Export for ES-DE",
                    sublabel = "Copies your artwork into a folder you pick (e.g. ES-DE's " +
                        "downloaded_media) — incremental, your library is never modified",
                    onClick  = if (state.importRunning) null else ({ exportPicker.launch(null) }),
                )
            }

            // ── Preview ───────────────────────────────────────────────────────
            if (state.planning) {
                SettingsGroup("Import Preview")
                SettingsRow(label = "Building preview…", sublabel = "Matching artwork to your games")
            }

            state.plan?.let { plan ->
                SettingsGroup("Import Preview — ${plan.sourceLabel}")

                SettingsValueRow(label = "Games Matched",     value = plan.games.size.toString())
                SettingsValueRow(label = "Files To Import",   value = plan.itemCount.toString())
                plan.countsByKind().entries.sortedBy { it.key }.forEach { (kind, count) ->
                    SettingsValueRow(label = "   ${kindLabel(kind)}", value = count.toString())
                }
                SettingsValueRow(label = "Estimated Size",    value = formatBytes(plan.totalBytes))
                SettingsValueRow(
                    label    = "Game Details Found",
                    sublabel = if (plan.metadataUpdates.isEmpty())
                        "Copy ES-DE's gamelists folder into the launcher's import folder to bring " +
                            "descriptions, developers and release years along"
                    else "Descriptions and details from gamelist.xml — never overwrites existing data",
                    value    = plan.metadataUpdates.size.toString(),
                )
                SettingsValueRow(label = "Already Present",   value = plan.skippedExistingCount.toString())
                SettingsValueRow(label = "Needs Review",      value = plan.ambiguous.size.toString())
                SettingsValueRow(label = "Unmatched Artwork", value = plan.unmatchedCount.toString())

                if (plan.unknownSystemFolders.isNotEmpty()) {
                    SettingsRow(
                        label    = "Skipped System Folders",
                        sublabel = plan.unknownSystemFolders.joinToString(", "),
                    )
                }

                if (plan.platformsWithoutGames.isNotEmpty()) {
                    SettingsRow(
                        label    = "No Games In Library",
                        sublabel = "Artwork found for ${plan.platformsWithoutGames.joinToString(", ")} " +
                            "but your library has no games on these systems — add the games first, " +
                            "then re-run the import.",
                    )
                }

                SettingsToggleRow(
                    label    = "Move Files (faster, frees space)",
                    sublabel = "Moves artwork out of the launcher's folder instead of copying. " +
                        "The other launcher loses these files.",
                    checked  = state.moveFiles,
                    onToggle = { viewModel.toggleMoveFiles(it) },
                )

                // ── Ambiguous review ─────────────────────────────────────────
                if (plan.ambiguous.isNotEmpty()) {
                    SettingsGroup("Review Ambiguous Matches (${plan.ambiguous.size})")
                    plan.ambiguous.forEachIndexed { index, entry ->
                        SettingsRow(
                            label    = entry.candidate.displayName,
                            sublabel = "${entry.gameTitles.size} possible games — tap to choose",
                            onClick  = { viewModel.toggleAmbiguous(index) },
                        )
                        if (state.expandedAmbiguousIndex == index) {
                            entry.gameIds.forEachIndexed { gi, gameId ->
                                SettingsRow(
                                    label   = "   ${entry.gameTitles.getOrElse(gi) { "Game #$gameId" }}",
                                    sublabel = "Assign this artwork to this game",
                                    onClick = { viewModel.assignAmbiguous(index, gameId) },
                                )
                            }
                            SettingsRow(
                                label   = "   Skip This File",
                                onClick = { viewModel.skipAmbiguous(index) },
                            )
                        }
                    }
                }

                if (!state.importRunning) {
                    SettingsRow(
                        label    = "Start Import",
                        sublabel = if (state.moveFiles) "Moves ${plan.itemCount} files into the artwork library"
                        else "Copies ${plan.itemCount} files into the artwork library",
                        onClick  = if (plan.itemCount > 0) ({ viewModel.startImport() }) else null,
                    )
                    SettingsRow(label = "Cancel Preview", onClick = { viewModel.dismissPreview() })
                }
            }

            // ── Running import ────────────────────────────────────────────────
            if (state.importRunning) {
                SettingsGroup("Importing")
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
                        text  = "${state.importDone} / ${state.importTotal}  —  ${state.importLabel}",
                        color = SettingsText,
                    )
                    Text(
                        text  = "Runs in the background — you can leave this screen.",
                        color = SettingsSubtext,
                    )
                }
                SettingsRow(
                    label    = "Cancel Import",
                    sublabel = "Already-imported artwork is kept",
                    onClick  = { viewModel.cancelImport() },
                )
            }

            // ── Reports ───────────────────────────────────────────────────────
            if (state.reports.isNotEmpty()) {
                SettingsGroup("Import Report")
                state.reports.forEach { report ->
                    SettingsRow(label = report.title, sublabel = report.detail)
                }
                SettingsRow(
                    label    = "Clear Reports",
                    onClick  = { viewModel.clearReports() },
                )
            }

            // ── Notices ───────────────────────────────────────────────────────
            state.notice?.let {
                SettingsRow(label = it, sublabel = "Tap to dismiss", onClick = { viewModel.dismissError() })
            }
            state.error?.let {
                SettingsRow(label = "Error", sublabel = "$it  (tap to dismiss)", onClick = { viewModel.dismissError() })
            }
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "ICON" -> "Covers"
    "HERO" -> "Mix Images"
    "BACKGROUND" -> "Fanart"
    "LOGO" -> "Marquees"
    "SCREENSHOT" -> "Screenshots"
    "TITLESCREEN" -> "Title Screens"
    "PHYSICAL_MEDIA" -> "Physical Media"
    "MANUAL" -> "Manuals"
    else -> kind.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
