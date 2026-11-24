package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.settings.R
import com.playfieldportal.core.domain.text.UiText
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.playfieldportal.core.data.repository.BackupFolderRepository
import com.playfieldportal.feature.backup.BackupInfo
import com.playfieldportal.feature.backup.BackupManager
import com.playfieldportal.feature.backup.BackupWorker
import com.playfieldportal.feature.backup.RestoreWorker
import com.playfieldportal.core.ui.text.PfpDateFormat
import com.playfieldportal.core.ui.text.PfpDateStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class BackupSettingsUiState(
    val lastBackupDate: String? = null,
    val backupFolder: String? = null,   // display name of the chosen SAF folder; null = not set
    val backups: List<BackupInfo> = emptyList(),
    val isWorking: Boolean = false,
    val workingMessage: UiText = UiText.raw(""),
    val errorMessage: UiText? = null,
) {
    val backupFolderSet: Boolean get() = backupFolder != null
}

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val backupFolderRepository: BackupFolderRepository,
) : ViewModel() {

    /**
     * How a backup's date is shown: the device's language and 12/24-hour setting, read at the moment
     * of use. A seam so plain JVM tests, which have no Android date formatting, can pin it.
     */
    internal var formatBackupDate: (Long) -> String =
        { millis -> PfpDateFormat.format(context, PfpDateStyle.DateTime, millis) }

    private val workManager = WorkManager.getInstance(context)
    private val _uiState = MutableStateFlow(BackupSettingsUiState())
    val uiState: StateFlow<BackupSettingsUiState> = _uiState.asStateFlow()

    init {
        refreshBackupList()
    }

    /** Persists the chosen SAF backup folder (read+write) and refreshes the saved-backups list. */
    fun setBackupFolder(uri: android.net.Uri) {
        viewModelScope.launch {
            backupFolderRepository.persist(uri)
            backupFolderRepository.set(uri.toString())
            refreshBackupList()
        }
    }

    fun backupNow() {
        if (!_uiState.value.backupFolderSet) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.sets_backup_choose_folder_first_error)) }
            return
        }
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(BackupWorker.TAG)
            .setConstraints(Constraints.NONE)
            .build()

        workManager.enqueue(request)

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, workingMessage = UiText.res(R.string.sets_backup_creating), errorMessage = null) }
            // Stops at the first finished state: `return@collect` only skipped one emission, so the
            // watcher outlived its job and a later re-emission could undo the result shown.
            val info = workManager.getWorkInfoByIdFlow(request.id).firstOrNull { it?.state?.isFinished == true }
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val path = info.outputData.getString(BackupWorker.KEY_OUTPUT_PATH)
                    Timber.i("Backup succeeded: $path")
                    _uiState.update { it.copy(isWorking = false, workingMessage = UiText.raw("")) }
                    refreshBackupList()
                }
                WorkInfo.State.FAILED -> {
                    val err = info.outputData.getString(BackupWorker.KEY_ERROR) ?.let { UiText.raw(it) } ?: UiText.res(R.string.sets_backup_unknown_error)
                    _uiState.update { it.copy(isWorking = false, workingMessage = UiText.raw(""), errorMessage = err) }
                }
                else -> _uiState.update { it.copy(isWorking = false, workingMessage = UiText.raw("")) }
            }
        }
    }

    fun restoreFromUri(uri: Uri) {
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .addTag(RestoreWorker.TAG)
            .setInputData(workDataOf(RestoreWorker.KEY_URI to uri.toString()))
            .build()

        workManager.enqueue(request)

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, workingMessage = UiText.res(R.string.sets_backup_restoring), errorMessage = null) }
            // Stops at the first finished state (see backupNow).
            val info = workManager.getWorkInfoByIdFlow(request.id).firstOrNull { it?.state?.isFinished == true }
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    Timber.i("Restore succeeded")
                    _uiState.update { it.copy(isWorking = false, workingMessage = UiText.raw("")) }
                }
                WorkInfo.State.FAILED -> {
                    val err = info.outputData.getString(RestoreWorker.KEY_ERROR) ?.let { UiText.raw(it) } ?: UiText.res(R.string.sets_backup_unknown_error)
                    _uiState.update { it.copy(isWorking = false, workingMessage = UiText.raw(""), errorMessage = err) }
                }
                else -> _uiState.update { it.copy(isWorking = false, workingMessage = UiText.raw("")) }
            }
        }
    }

    /** Deletes a saved backup's document from the backup folder, then refreshes the list. */
    fun deleteBackup(info: BackupInfo) {
        viewModelScope.launch {
            val deleted = runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, info.uri)
            }.getOrDefault(false)
            if (!deleted) {
                Timber.w("Could not delete backup %s", info.name)
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.sets_backup_delete_failed, info.name)) }
            }
            refreshBackupList()
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    private fun refreshBackupList() {
        viewModelScope.launch {
            val folderUri = backupFolderRepository.get()
            val backups   = backupManager.listBackups()
            _uiState.update {
                it.copy(
                    backups        = backups,
                    lastBackupDate = backups.firstOrNull()?.let { b -> formatBackupDate(b.lastModified) },
                    backupFolder   = folderUri?.let(::backupFolderDisplayName),
                )
            }
        }
    }

    // A human-readable label for the chosen SAF tree (the last path segment of its document id).
    private fun backupFolderDisplayName(treeUri: String): String =
        runCatching {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(treeUri))
            val tail  = docId.substringAfterLast(':').substringAfterLast('/')
            tail.ifBlank { docId }
        }.getOrDefault(treeUri)
}
