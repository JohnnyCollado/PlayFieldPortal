package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.artwork.importer.ArtworkImportWorker
import com.playfieldportal.feature.artwork.importer.DetectedImportSource
import com.playfieldportal.feature.artwork.importer.ImportPlan
import com.playfieldportal.feature.artwork.importer.ImportSummary
import com.playfieldportal.feature.artwork.migrate.InternalArtworkMigrationWorker
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ArtworkImportUiState(
    // Folder link
    val folderDisplay: String? = null,
    val folderLinked: Boolean = false,
    val grantAlive: Boolean = true,
    val confirmForget: Boolean = false,
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
    val error: String? = null,
    val notice: String? = null,
) {
    data class SourceUi(val detected: DetectedImportSource, val label: String, val systems: Int)
    data class ReportUi(val id: Long, val title: String, val detail: String)
}

@HiltViewModel
class ArtworkImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importManager: ArtworkImportManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtworkImportUiState())
    val uiState: StateFlow<ArtworkImportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // distinctUntilChanged is load-bearing: the underlying DataStore flow emits on EVERY
            // preference write, and each emission here triggers a SAF rescan of the import tree.
            importManager.folderTreeUri.distinctUntilChanged().collect { uri ->
                val alive = uri != null && importManager.hasLiveGrant()
                _uiState.value = _uiState.value.copy(
                    folderDisplay = uri?.let { RomRootRepository.rawPathOfTree(it) ?: it },
                    folderLinked = uri != null,
                    grantAlive = alive,
                )
                if (uri != null && alive) rescan()
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
        viewModelScope.launch { refreshInternalFootprint() }
    }

    // ── Folder link ───────────────────────────────────────────────────────────

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            val result = importManager.linkFolder(uri)
            if (result == null) {
                _uiState.value = _uiState.value.copy(
                    error = "Could not set up the artwork library in that folder — it may be read-only.",
                )
                return@launch
            }
            // Zero-copy adoption: any ES-DE-shaped media already in the folder (a PFP library
            // OR a plain downloaded_media tree) is linked to games right now — nothing copied.
            val scan = runCatching { importManager.relinkLibrary() }.getOrNull()
            refreshInternalFootprint()
            _uiState.value = _uiState.value.copy(
                notice = buildString {
                    append(
                        if (result.existingLibrary) "Existing PFP artwork library reconnected."
                        else "Artwork library created.",
                    )
                    if (scan != null && scan.gamesLinked > 0) {
                        append(" ${scan.gamesLinked} games linked from ${scan.entriesScanned} files already in the folder.")
                    } else if (!result.existingLibrary) {
                        append(" Place other launchers' media under import/.")
                    }
                    if (_uiState.value.internalFiles > 0) {
                        append(" ${_uiState.value.internalFiles} artwork files are still in app storage — see Move Into Folder below.")
                    }
                },
                plan = null,
            )
        }
    }

    fun forgetFolder() {
        val state = _uiState.value
        if (!state.confirmForget) {
            _uiState.value = state.copy(confirmForget = true)
            return
        }
        viewModelScope.launch {
            importManager.forgetFolder()
            _uiState.value = ArtworkImportUiState(reports = _uiState.value.reports)
        }
    }

    fun dismissForgetConfirm() {
        _uiState.value = _uiState.value.copy(confirmForget = false)
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
                        notice = "Artwork library upgraded to the new layout — $migrated files reorganized.",
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
                _uiState.value = _uiState.value.copy(scanning = false, error = "Could not scan the import folder.")
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
                        error = if (plan == null) "Could not read this source." else null,
                    )
                }
                .onFailure {
                    Timber.e(it, "Import planning failed")
                    _uiState.value = _uiState.value.copy(planning = false, error = "Preview failed — see logs.")
                }
        }
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(plan = null, expandedAmbiguousIndex = null)
    }

    fun toggleMoveFiles(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(moveFiles = enabled)
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
            _uiState.value = state.copy(notice = "Nothing to import — everything is already present.")
            return
        }
        val transfer = if (state.moveFiles) PortableArtworkLibrary.Transfer.MOVE
        else PortableArtworkLibrary.Transfer.COPY
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
            notice = "Export started — progress in the notification; the result lands under Import Report.",
        )
    }

    fun relinkLibrary() {
        if (_uiState.value.relinking) return
        _uiState.value = _uiState.value.copy(relinking = true)
        viewModelScope.launch {
            runCatching { importManager.relinkLibrary() }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        relinking = false,
                        notice = if (result == null) "No artwork folder linked (or access was lost)."
                        else buildString {
                            append("Scan: ${result.entriesScanned} files · ${result.gamesLinked} games linked")
                            if (result.missingFiles > 0) append(" · ${result.missingFiles} missing removed")
                            if (result.changedFiles > 0) append(" · ${result.changedFiles} changed")
                            if (result.duplicateNames > 0) append(" · ${result.duplicateNames} duplicate names")
                            if (result.orphanEntries > 0) append(" · ${result.orphanEntries} unmatched")
                        },
                    )
                }
                .onFailure {
                    Timber.e(it, "Library scan failed")
                    _uiState.value = _uiState.value.copy(relinking = false, error = "Library scan failed — see logs.")
                }
        }
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
        val date = DATE_FORMAT.format(Date(entity.startedAt))
        val s = summary
        val detail = if (s == null) "Report unreadable" else buildString {
            append("${s.imported} imported · ${s.skipped} skipped · ${s.failed} failed")
            if (s.metadataApplied > 0) append(" · ${s.metadataApplied} game details")
            if (s.ambiguous > 0) append(" · ${s.ambiguous} need review")
            if (s.unmatched > 0) append(" · ${s.unmatched} unmatched")
            if (s.cancelled) append(" · cancelled")
        }
        return ArtworkImportUiState.ReportUi(entity.id, "${entity.source} — $date", detail)
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
    }
}
