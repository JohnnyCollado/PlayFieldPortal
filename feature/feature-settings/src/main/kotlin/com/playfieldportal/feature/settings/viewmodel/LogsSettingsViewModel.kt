package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class LogFileItem(
    val name: String,
    val size: String,
    /** "Started today, 3:18 AM" — read from the file name; null for a name the tree didn't write. */
    val startedLabel: String?,
    /** The newest file — the one this session is still writing into. */
    val isCurrent: Boolean,
    val bytes: Long,
)

// Logs open in an external viewer (Confirm) or share via the system sheet — no in-app viewer.
data class LogsSettingsUiState(
    /** False until the directory has been read once. The screen composes no rows before this,
     *  so Clear All Logs can never claim the screen's first focus while the list is still empty. */
    val loaded: Boolean = false,
    val logFiles: List<LogFileItem> = emptyList(),
    /** "5 files · 650 KB", shown beside Clear All Logs. */
    val totalSummary: String = "No files",
    /** "650 KB" — the total alone, for the Clear All Logs confirmation. */
    val totalSize: String = "",
    val confirmClearVisible: Boolean = false,
    /** Resolves the north-face Share shortcut to the action the user's X/Y layout emits. */
    val xyLayout: XYLayout = XYLayout.STANDARD,
)

@HiltViewModel
class LogsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controllerLayout: ControllerLayoutRepository,
    private val tasks: BackgroundTaskCenter,
) : ViewModel() {

    private val logsDir get() = File(context.filesDir, "logs")

    private val _uiState = MutableStateFlow(LogsSettingsUiState())
    val uiState: StateFlow<LogsSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            controllerLayout.prefs.collect { prefs ->
                _uiState.update { it.copy(xyLayout = prefs.xyLayout) }
            }
        }
        refresh()
    }

    /** Re-reads the directory — on entry, on returning to the screen, and after a clear. */
    fun refresh() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { readLogFiles() }
            val totalBytes = files.sumOf { it.bytes }
            _uiState.update {
                it.copy(
                    loaded = true,
                    logFiles = files,
                    totalSummary = summaryOf(files.size, totalBytes),
                    totalSize = formatSize(totalBytes),
                )
            }
        }
    }

    /** Clear All Logs asks first — and only when there is something to clear. */
    fun requestClear() {
        if (_uiState.value.logFiles.isEmpty()) return
        _uiState.update { it.copy(confirmClearVisible = true) }
    }

    fun dismissClear() {
        _uiState.update { it.copy(confirmClearVisible = false) }
    }

    fun confirmClear() {
        _uiState.update { it.copy(confirmClearVisible = false) }
        viewModelScope.launch {
            // Sizes are read before each delete — a deleted file reports 0.
            val deletedSizes = withContext(Dispatchers.IO) {
                logsDir.listFiles()?.filter { it.isFile }.orEmpty().mapNotNull { f ->
                    val bytes = f.length()
                    if (f.delete()) bytes else null
                }
            }
            val summary = "Deleted ${filesLabel(deletedSizes.size)} (${formatSize(deletedSizes.sum())})"
            Timber.i("Logs cleared")
            // The outcome goes to the tray, not the screen — the list below simply re-reads.
            tasks.report(
                id = "logs_clear",
                label = "Logs",
                message = summary,
                severity = NotificationSeverity.SUCCESS,
                kind = NotificationKind.SYSTEM,
                action = NotificationAction.OpenSettingsScreen("settings_logs"),
            )
            // Re-read rather than blank the list: the running session writes straight back into a
            // fresh file (the "Logs cleared" line above is its first), and the screen should show it.
            refresh()
        }
    }

    private fun readLogFiles(): List<LogFileItem> {
        val now = LocalDateTime.now()
        return logsDir
            .takeIf { it.exists() }
            ?.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.mapIndexed { index, f ->
                LogFileItem(
                    name = f.name,
                    size = formatSize(f.length()),
                    startedLabel = startedLabel(f.name, now),
                    isCurrent = index == 0,
                    bytes = f.length(),
                )
            }
            ?: emptyList()
    }

    companion object {
        // PfpFileLoggingTree names each session file "pfp-yyyyMMdd-HHmmss.log".
        private val FILE_NAME = Regex("""pfp-(\d{8})-(\d{6})\.log""")
        private val NAME_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

        /** "Started today, 3:18 AM" / "Started yesterday, …" / "Started Sep 1, …". */
        fun startedLabel(fileName: String, now: LocalDateTime): String? {
            val match = FILE_NAME.matchEntire(fileName) ?: return null
            val started = runCatching {
                LocalDateTime.parse(match.groupValues[1] + match.groupValues[2], NAME_STAMP)
            }.getOrNull() ?: return null
            val day = when (started.toLocalDate()) {
                now.toLocalDate() -> "today"
                now.toLocalDate().minusDays(1) -> "yesterday"
                else -> started.format(DATE)
            }
            return "Started $day, ${started.format(TIME)}"
        }

        /** Whole KB (a small file reads "<1 KB", never "0 KB"), one decimal of MB above that. */
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "<1 KB"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

        private fun filesLabel(count: Int) = if (count == 1) "1 file" else "$count files"

        private fun summaryOf(count: Int, bytes: Long): String =
            if (count == 0) "No files" else "${filesLabel(count)} · ${formatSize(bytes)}"
    }
}
