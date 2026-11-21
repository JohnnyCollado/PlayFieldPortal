package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.settings.R
import com.playfieldportal.core.domain.text.UiText
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import com.playfieldportal.core.ui.text.PfpDateFormat
import com.playfieldportal.core.ui.text.PfpDateStyle
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class LogFileItem(
    val name: String,
    val size: String,
    /** "Started today, 3:18 AM" — read from the file name; null for a name the tree didn't write. */
    val startedLabel: UiText?,
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
    val totalSummary: UiText = UiText.res(R.string.sets_logs_no_files),
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
            val summary = context.resources.getQuantityString(
                R.plurals.sets_logs_deleted,
                deletedSizes.size,
                deletedSizes.size,
                formatSize(deletedSizes.sum()),
            )
            Timber.i("Logs cleared")
            // The outcome goes to the tray, not the screen — the list below simply re-reads.
            tasks.report(
                id = "logs_clear",
                label = context.getString(R.string.sets_logs_title),
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
                    startedLabel = startedLabel(context, f.name, now),
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

        /** "Started today, 3:18 AM" / "Started yesterday, …" / "Started Sep 1, …". */
        fun startedLabel(context: Context, fileName: String, now: LocalDateTime): UiText? {
            val match = FILE_NAME.matchEntire(fileName) ?: return null
            val started = runCatching {
                LocalDateTime.parse(match.groupValues[1] + match.groupValues[2], NAME_STAMP)
            }.getOrNull() ?: return null
            val startedAt = started.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val time = PfpDateFormat.format(context, PfpDateStyle.Time, startedAt)
            return when (started.toLocalDate()) {
                now.toLocalDate() -> UiText.res(R.string.sets_logs_started_today, time)
                now.toLocalDate().minusDays(1) -> UiText.res(R.string.sets_logs_started_yesterday, time)
                else -> UiText.res(R.string.sets_logs_started_on, PfpDateFormat.format(context, PfpDateStyle.ShortDate, startedAt), time)
            }
        }

        /** Whole KB (a small file reads "<1 KB", never "0 KB"), one decimal of MB above that. */
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "<1 KB"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

        private fun summaryOf(count: Int, bytes: Long): UiText =
            if (count == 0) UiText.res(R.string.sets_logs_no_files)
            else UiText.plural(R.plurals.sets_logs_summary, count, count, formatSize(bytes))
    }
}
