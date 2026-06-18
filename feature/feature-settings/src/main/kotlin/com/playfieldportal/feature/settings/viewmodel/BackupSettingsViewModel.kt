package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.playfieldportal.feature.backup.BackupManager
import com.playfieldportal.feature.backup.BackupWorker
import com.playfieldportal.feature.backup.RestoreWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class BackupSettingsUiState(
    val lastBackupDate: String? = null,
    val backupFolder: String = "/storage/emulated/0/PlayFieldPortal/backups/",
    val backupFiles: List<String> = emptyList(),
    val isWorking: Boolean = false,
    val workingMessage: String = "",
    val errorMessage: String? = null,
)

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val _uiState = MutableStateFlow(BackupSettingsUiState())
    val uiState: StateFlow<BackupSettingsUiState> = _uiState.asStateFlow()

    init {
        refreshBackupList()
    }

    fun backupNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(BackupWorker.TAG)
            .setConstraints(Constraints.NONE)
            .build()

        workManager.enqueue(request)

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, workingMessage = "Creating backup…", errorMessage = null) }
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val path = info.outputData.getString(BackupWorker.KEY_OUTPUT_PATH)
                        Timber.i("Backup succeeded: $path")
                        _uiState.update { it.copy(isWorking = false, workingMessage = "") }
                        refreshBackupList()
                        return@collect
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(BackupWorker.KEY_ERROR) ?: "Unknown error"
                        _uiState.update { it.copy(isWorking = false, workingMessage = "", errorMessage = err) }
                        return@collect
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(isWorking = false, workingMessage = "") }
                        return@collect
                    }
                    else -> { /* ENQUEUED / RUNNING — keep showing progress */ }
                }
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
            _uiState.update { it.copy(isWorking = true, workingMessage = "Restoring backup…", errorMessage = null) }
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        Timber.i("Restore succeeded")
                        _uiState.update { it.copy(isWorking = false, workingMessage = "") }
                        return@collect
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(RestoreWorker.KEY_ERROR) ?: "Unknown error"
                        _uiState.update { it.copy(isWorking = false, workingMessage = "", errorMessage = err) }
                        return@collect
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(isWorking = false, workingMessage = "") }
                        return@collect
                    }
                    else -> {}
                }
            }
        }
    }

    // Kept for legacy composable call sites that haven't wired SAF yet
    fun restoreFromFile() {
        Timber.d("restoreFromFile — SAF picker launched by composable")
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    private fun refreshBackupList() {
        val files = backupManager.listBackupFiles()
        val fmt   = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
        _uiState.update {
            it.copy(
                backupFiles   = files.map { f -> f.name },
                lastBackupDate = files.firstOrNull()?.let { f -> fmt.format(Date(f.lastModified())) },
                backupFolder   = backupManager.backupDir().absolutePath,
            )
        }
    }
}
