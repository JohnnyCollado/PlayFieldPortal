package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.artwork.api.ArtworkFolderState
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.artwork.api.ArtworkRelinkWorker
import com.playfieldportal.feature.artwork.importer.ArtworkImportWorker
import com.playfieldportal.feature.artwork.importer.DetectedImportSource
import com.playfieldportal.feature.artwork.importer.ImportPlan
import com.playfieldportal.feature.artwork.importer.ImportSummary
import com.playfieldportal.feature.artwork.migrate.InternalArtworkMigrationWorker
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.settings.R
import com.playfieldportal.core.ui.text.PfpDateFormat
import com.playfieldportal.core.ui.text.PfpDateStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// Sticky Move/Copy choice for artwork imports — survives process restarts.
private val KEY_MOVE_FILES = booleanPreferencesKey("artwork_import_move_files")

data class ArtworkImportUiState(
    // Files the last relink could not place (C22 task T4). Drives the Unmatched Artwork row,
    // which is hidden at zero.
    val orphanCount: Int = 0,
    // Folder link
    val folderDisplay: String? = null,
    // The stored tree URI string; the picker for an Unavailable folder opens on it.
    val folderTreeUri: String? = null,
    val folderLinked: Boolean = false,
    val grantAlive: Boolean = true,
    // Detected sources under import/
    val scanning: Boolean = false,
    val sources: List<SourceUi> = emptyList(),
    val unrecognized: List<String> = emptyList(),
    // Preview
    val planning: Boolean = false,
    val plan: ImportPlan? = null,
    val moveFiles: Boolean = false,
    val expandedAmbiguousIndex: Int? = null,
    // Running import
    val importRunning: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importLabel: String = "",
    // Maintenance
    val relinking: Boolean = false,
    // Internal-storage migration (M-F2)
    val internalFiles: Int = 0,
    val internalBytes: Long = 0L,
    val migrationRunning: Boolean = false,
    val migrationDone: Int = 0,
    val migrationTotal: Int = 0,
    // Reports
    val reports: List<ReportUi> = emptyList(),
    val error: UiText? = null,
    val notice: UiText? = null,
) {
    data class SourceUi(val detected: DetectedImportSource, val label: String, val systems: Int)
    data class ReportUi(val id: Long, val title: UiText, val detail: UiText)
}

@HiltViewModel
class ArtworkImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importManager: ArtworkImportManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtworkImportUiState())
    val uiState: StateFlow<ArtworkImportUiState> = _uiState.asStateFlow()

    init {
        // "Move Files" is a persisted preference, not per-visit state — an ephemeral toggle
        // silently reset to Copy on every process restart, which read as "Move doesn't work".
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                _uiState.value = _uiState.value.copy(moveFiles = prefs[KEY_MOVE_FILES] ?: false)
            }
        }
        viewModelScope.launch {
            // A StateFlow only emits on change, which matters: a Ready emission triggers a SAF
            // rescan of the import tree.
            importManager.folderState.collect { folder ->
                when (folder) {
                    // Not probed yet: keep what is shown rather than flash "not set".
                    ArtworkFolderState.Unknown -> Unit
                    is ArtworkFolderState.NotLinked -> _uiState.value = _uiState.value.copy(
                        folderDisplay = null,
                        folderTreeUri = null,
                        folderLinked = false,
                        grantAlive = true,
                    )
                    is ArtworkFolderState.Unavailable -> _uiState.value = _uiState.value.copy(
                        folderDisplay = folder.name,
                        folderTreeUri = folder.treeUri,
                        folderLinked = true,
                        grantAlive = false,
                    )
                    is ArtworkFolderState.Ready -> {
                        _uiState.value = _uiState.value.copy(
                            folderDisplay = folder.name,
                            folderTreeUri = folder.treeUri,
                            folderLinked = true,
                            grantAlive = true,
                        )
                        rescan()
                    }
                }
            }
        }
        viewModelScope.launch {
            importManager.orphanFiles.collect { rows ->
                _uiState.value = _uiState.value.copy(orphanCount = rows.size)
            }
        }
        viewModelScope.launch {
            importManager.reports.collect { rows ->
                _uiState.value = _uiState.value.copy(reports = rows.map { it.toUi() })
            }
        }
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(ArtworkImportWorker.UNIQUE_NAME)
                .collect { infos -> onWorkInfos(infos) }
        }
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(InternalArtworkMigrationWorker.UNIQUE_NAME)
                .collect { infos -> onMigrationWorkInfos(infos) }
        }
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(ArtworkRelinkWorker.UNIQUE_NAME)
                .collect { infos -> onRelinkWorkInfos(infos) }
        }
        viewModelScope.launch { refreshInternalFootprint() }
    }

    // ── Folder link ───────────────────────────────────────────────────────────

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            val result = when (val link = importManager.linkFolder(uri)) {
                is ArtworkImportManager.LinkResult.Linked -> link
                // The shell's "another artwork library" prompt takes over from here.
                ArtworkImportManager.LinkResult.ForeignLibrary -> return@launch
                ArtworkImportManager.LinkResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        error = UiText.res(R.string.setd_import_link_failed),
                    )
                    return@launch
                }
            }
            // Zero-copy adoption: any ES-DE-shaped media already in the folder (a PFP library
            // OR a plain downloaded_media tree) is linked to games right now — nothing copied.
            val scan = runCatching { importManager.relinkLibrary() }.getOrNull()
            refreshInternalFootprint()
            _uiState.value = _uiState.value.copy(
                notice = UiText.Joined(
                    buildList<UiText> {
                        add(
                            UiText.res(
                                if (result.existingLibrary) R.string.setd_import_library_reconnected
                                else R.string.setd_import_library_created,
                            ),
                        )
                        if (scan != null && scan.gamesLinked > 0) {
                            add(UiText.plural(R.plurals.setd_import_games_linked, scan.gamesLinked, scan.gamesLinked, scan.entriesScanned))
                        } else if (!result.existingLibrary) {
                            add(UiText.res(R.string.setd_import_place_media))
                        }
                        val internal = _uiState.value.internalFiles
                        if (internal > 0) {
                            add(UiText.plural(R.plurals.setd_import_internal_files, internal))
                        }
                    },
                    " ",
                ),
                plan = null,
            )
        }
    }

    // ── Sources & preview ─────────────────────────────────────────────────────

    fun rescan() {
        if (_uiState.value.scanning) return
        _uiState.value = _uiState.value.copy(scanning = true)
        viewModelScope.launch {
            runCatching {
                // One-time in-place upgrade of v1 (per-game-folder) libraries to the ES-DE
                // layout — same-tree moves, lossless, no-op once migrated.
                val migrated = importManager.migrateV1IfNeeded()
                if (migrated > 0) {
                    _uiState.value = _uiState.value.copy(
                        notice = UiText.plural(R.plurals.setd_import_upgraded, migrated),
                    )
                }
                val detected = importManager.detectSources()
                val unrecognized = importManager.unrecognizedFolders(detected)
                _uiState.value = _uiState.value.copy(
                    scanning = false,
                    sources = detected.map {
                        ArtworkImportUiState.SourceUi(it, it.label, it.systems.size)
                    },
                    unrecognized = unrecognized,
                )
            }.onFailure {
                Timber.e(it, "Import source scan failed")
                _uiState.value = _uiState.value.copy(scanning = false, error = UiText.res(R.string.setd_import_scan_failed))
            }
        }
    }

    fun buildPreview(source: ArtworkImportUiState.SourceUi) {
        if (_uiState.value.planning) return
        _uiState.value = _uiState.value.copy(planning = true, plan = null)
        viewModelScope.launch {
            runCatching { importManager.buildPlan(source.detected) }
                .onSuccess { plan ->
                    _uiState.value = _uiState.value.copy(
                        planning = false,
                        plan = plan,
                        error = if (plan == null) UiText.res(R.string.setd_import_source_unreadable) else null,
                    )
                }
                .onFailure {
                    Timber.e(it, "Import planning failed")
                    _uiState.value = _uiState.value.copy(planning = false, error = UiText.res(R.string.setd_import_preview_failed))
                }
        }
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(plan = null, expandedAmbiguousIndex = null)
    }

    fun toggleMoveFiles(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(moveFiles = enabled)
        viewModelScope.launch {
            context.pfpDataStore.edit { it[KEY_MOVE_FILES] = enabled }
        }
    }

    // ── Ambiguous review ──────────────────────────────────────────────────────

    fun toggleAmbiguous(index: Int) {
        val current = _uiState.value.expandedAmbiguousIndex
        _uiState.value = _uiState.value.copy(expandedAmbiguousIndex = if (current == index) null else index)
    }

    fun assignAmbiguous(index: Int, gameId: Long) {
        val plan = _uiState.value.plan ?: return
        viewModelScope.launch {
            val updated = importManager.assignAmbiguous(plan, index, gameId)
            _uiState.value = _uiState.value.copy(plan = updated, expandedAmbiguousIndex = null)
        }
    }

    fun skipAmbiguous(index: Int) {
        val plan = _uiState.value.plan ?: return
        _uiState.value = _uiState.value.copy(
            plan = importManager.skipAmbiguous(plan, index),
            expandedAmbiguousIndex = null,
        )
    }

    // ── Import run ────────────────────────────────────────────────────────────

    fun startImport() {
        val state = _uiState.value
        val plan = state.plan ?: return
        if (plan.itemCount == 0) {
            _uiState.value = state.copy(notice = UiText.res(R.string.setd_import_nothing))
            return
        }
        val transfer = if (state.moveFiles) PortableArtworkLibrary.Transfer.MOVE
        else PortableArtworkLibrary.Transfer.COPY
        // File-logged so a "Move didn't move" report can be checked against what was requested.
        Timber.i("Import starting: transfer=$transfer, ${plan.itemCount} items from '${plan.sourceLabel}'")
        importManager.startImport(plan, transfer)
        _uiState.value = state.copy(plan = null, importRunning = true, importDone = 0, importTotal = plan.itemCount)
    }

    fun cancelImport() = importManager.cancelImport()

    fun clearReports() {
        viewModelScope.launch { importManager.clearReports() }
    }

    fun startExport(destUri: Uri) {
        importManager.startExport(destUri)
        _uiState.value = _uiState.value.copy(
            notice = UiText.res(R.string.setd_import_export_started),
        )
    }

    /**
     * Starts a full-library Scan & Relink (C22 task T3b).
     *
     * This used to run on `viewModelScope`, so navigating away mid-scan killed it. It now enqueues
     * the shared worker; `relinking` is driven by the worker's state below, not by a local boolean.
     *
     * Deliberately NOT migrated: `onFolderPicked`, `InitialSetupViewModel` and `PcGameScanner` all
     * call `relinkLibrary` as a step *inside* a larger operation that already owns its own progress
     * and reports a combined result. Those must stay synchronous.
     */
    fun relinkLibrary() {
        if (_uiState.value.relinking) return
        _uiState.value = _uiState.value.copy(relinking = true)
        ArtworkRelinkWorker.enqueue(context)
    }

    private fun onRelinkWorkInfos(infos: List<WorkInfo>) {
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            _uiState.value = _uiState.value.copy(relinking = true)
            return
        }
        if (!_uiState.value.relinking) return
        // Unique work, so this is the run that just finished.
        val last = infos.lastOrNull()
        val notice = when (last?.state) {
            WorkInfo.State.SUCCEEDED -> {
                val scanned = last.outputData.getInt(ArtworkRelinkWorker.KEY_SCANNED, 0)
                val linked = last.outputData.getInt(ArtworkRelinkWorker.KEY_LINKED, 0)
                val orphans = last.outputData.getInt(ArtworkRelinkWorker.KEY_ORPHANS, 0)
                UiText.Joined(
                    buildList<UiText> {
                        add(UiText.plural(R.plurals.setd_relink_files, scanned))
                        add(UiText.plural(R.plurals.setd_relink_linked, linked))
                        if (orphans > 0) add(UiText.res(R.string.setd_relink_orphans, orphans))
                    },
                    " · ",
                )
            }
            WorkInfo.State.FAILED -> UiText.res(R.string.setd_relink_failed)
            WorkInfo.State.CANCELLED -> UiText.res(R.string.setd_relink_cancelled)
            else -> null
        }
        _uiState.value = _uiState.value.copy(relinking = false, notice = notice ?: _uiState.value.notice)
    }

    // ── Internal-storage migration (M-F2) ─────────────────────────────────────

    fun startInternalMigration() {
        if (_uiState.value.migrationRunning) return
        importManager.startInternalMigration()
        _uiState.value = _uiState.value.copy(
            migrationRunning = true,
            migrationDone = 0,
            migrationTotal = 0,
        )
    }

    fun cancelInternalMigration() = importManager.cancelInternalMigration()

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null, notice = null)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun onWorkInfos(infos: List<WorkInfo>) {
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            val done = active.progress.getInt(ArtworkImportWorker.KEY_PROGRESS_DONE, 0)
            val total = active.progress.getInt(ArtworkImportWorker.KEY_PROGRESS_TOTAL, 0)
            val label = active.progress.getString(ArtworkImportWorker.KEY_PROGRESS_LABEL) ?: ""
            _uiState.value = _uiState.value.copy(
                importRunning = true,
                importDone = done,
                importTotal = if (total > 0) total else _uiState.value.importTotal,
                importLabel = label,
            )
        } else if (_uiState.value.importRunning) {
            _uiState.value = _uiState.value.copy(importRunning = false, importLabel = "")
            rescan()
        }
    }

    private fun onMigrationWorkInfos(infos: List<WorkInfo>) {
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            _uiState.value = _uiState.value.copy(
                migrationRunning = true,
                migrationDone = active.progress.getInt(InternalArtworkMigrationWorker.KEY_PROGRESS_DONE, 0),
                migrationTotal = active.progress.getInt(InternalArtworkMigrationWorker.KEY_PROGRESS_TOTAL, 0),
            )
        } else if (_uiState.value.migrationRunning) {
            _uiState.value = _uiState.value.copy(migrationRunning = false)
            viewModelScope.launch { refreshInternalFootprint() }
        }
    }

    private suspend fun refreshInternalFootprint() {
        runCatching { importManager.internalArtworkFootprint() }.onSuccess { (files, bytes) ->
            _uiState.value = _uiState.value.copy(internalFiles = files, internalBytes = bytes)
        }
    }

    private fun ArtworkImportManager.ReportRow.toUi(): ArtworkImportUiState.ReportUi {
        val date = PfpDateFormat.format(context, PfpDateStyle.DateTime, entity.startedAt)
        val s = summary
        val detail = if (s == null) UiText.res(R.string.setd_report_unreadable) else UiText.Joined(
            buildList<UiText> {
                add(UiText.res(R.string.setd_report_imported, s.imported))
                add(UiText.res(R.string.setd_report_skipped, s.skipped))
                add(UiText.res(R.string.setd_report_failed, s.failed))
                if (s.metadataApplied > 0) add(UiText.plural(R.plurals.setd_report_game_details, s.metadataApplied))
                if (s.ambiguous > 0) add(UiText.res(R.string.setd_report_need_review, s.ambiguous))
                if (s.unmatched > 0) add(UiText.res(R.string.setd_report_unmatched, s.unmatched))
                if (s.cancelled) add(UiText.res(R.string.setd_report_cancelled))
            },
            " · ",
        )
        return ArtworkImportUiState.ReportUi(entity.id, UiText.res(R.string.setd_report_title, entity.source, date), detail)
    }

}
