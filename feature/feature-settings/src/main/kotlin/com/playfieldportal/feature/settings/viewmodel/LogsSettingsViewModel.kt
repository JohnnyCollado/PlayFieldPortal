package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

data class LogFileItem(
    val name: String,
    val sizeKb: String,
)

data class LogsSettingsUiState(
    val logFiles: List<LogFileItem> = emptyList(),
    val selectedFile: String? = null,
    val logContent: String = "",
)

@HiltViewModel
class LogsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsSettingsUiState())
    val uiState: StateFlow<LogsSettingsUiState> = _uiState.asStateFlow()

    init {
        loadLogList()
    }

    private fun loadLogList() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                File(context.filesDir, "logs")
                    .takeIf { it.exists() }
                    ?.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { f ->
                        LogFileItem(
                            name   = f.name,
                            sizeKb = "${f.length() / 1024} KB",
                        )
                    }
                    ?: emptyList()
            }
            _uiState.update { it.copy(logFiles = files) }
        }
    }

    fun selectFile(name: String) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                File(context.filesDir, "logs/$name")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?: "File not found."
            }
            _uiState.update { it.copy(selectedFile = name, logContent = content) }
        }
    }

    /** Closes the viewer (controller Back) — keeps the file list, drops the open content. */
    fun closeViewer() {
        _uiState.update { it.copy(selectedFile = null, logContent = "") }
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(context.filesDir, "logs").listFiles()?.forEach { it.delete() }
            }
            Timber.i("Logs cleared")
            _uiState.update { it.copy(logFiles = emptyList(), selectedFile = null, logContent = "") }
        }
    }
}
