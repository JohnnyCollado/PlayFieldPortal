package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.settings.R
import com.playfieldportal.core.domain.text.UiText
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.update.ApkDownload
import com.playfieldportal.core.data.update.AppUpdateCheckResult
import com.playfieldportal.core.data.update.AppUpdateChecker
import com.playfieldportal.core.data.update.AppUpdateDownloader
import com.playfieldportal.core.data.update.AppUpdateSnapshot
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.appupdate.AppEdition
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import com.playfieldportal.feature.settings.update.AppUpdateReminder
import com.playfieldportal.feature.settings.update.InstallStart
import com.playfieldportal.feature.settings.update.UpdateInstaller
import com.playfieldportal.core.ui.text.PfpDateFormat
import com.playfieldportal.core.ui.text.PfpDateStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/** One release in the changelog. [summary] is empty for a release published without one. */
data class ChangelogEntry(
    val version: String,
    val publishedOn: String,
    val summary: List<String>,
    val notesUrl: String,
    /** "New" for the release on offer, "Installed" for the running one, else null. */
    val tag: UiText?,
    val expanded: Boolean,
)

sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    /** [percent] is 0..100. */
    data class Downloading(val percent: Int) : UpdateDownload
    data object HandedToInstaller : UpdateDownload
    data class Failed(val message: UiText) : UpdateDownload
}

data class SystemUpdateUiState(
    /** "1.3.3 · Full". */
    val installedLabel: UiText = UiText.raw(""),
    /** "1.3.4 available", "Up to date" or "Not checked yet". */
    val latestLabel: UiText = UiText.res(R.string.sets_update_not_checked),
    /** The newer release on offer, null when there is none. */
    val availableVersion: String? = null,
    /** An update is on offer and this build can install it from here. */
    val canInstall: Boolean = false,
    val isChecking: Boolean = false,
    /** The outcome of the manual check that just finished. */
    val checkMessage: UiText? = null,
    val lastCheckedLabel: UiText = UiText.res(R.string.sets_update_never_checked),
    val notificationsEnabled: Boolean = true,
    val changelog: List<ChangelogEntry> = emptyList(),
    val download: UpdateDownload = UpdateDownload.Idle,
)

/** Settings ▸ System ▸ System Update (`settings_system_update`). */
@HiltViewModel
class SystemUpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: AppUpdateChecker,
    private val downloader: AppUpdateDownloader,
    private val installer: UpdateInstaller,
    private val tasks: BackgroundTaskCenter,
) : ViewModel() {

    private val edition = AppEdition.fromPackageName(context.packageName)

    /** What only this screen knows: its own actions in flight and which releases were toggled. */
    private data class Local(
        val isChecking: Boolean = false,
        val checkMessage: UiText? = null,
        /** The snapshot's `lastCheckAt` when [checkMessage] was written; a later check retires it. */
        val checkMessageAt: Long = 0,
        val download: UpdateDownload = UpdateDownload.Idle,
        /** Releases whose open/closed state the user flipped from its default. */
        val toggled: Set<String> = emptySet(),
    )

    private val local = MutableStateFlow(Local())
    private val _uiState = MutableStateFlow(SystemUpdateUiState())
    val uiState: StateFlow<SystemUpdateUiState> = _uiState.asStateFlow()

    private var latestSnapshot: AppUpdateSnapshot? = null

    init {
        viewModelScope.launch {
            combine(checker.snapshot, checker.notificationsEnabled, local) { snapshot, notify, mine ->
                latestSnapshot = snapshot
                toUiState(snapshot, notify, mine)
            }.collect { _uiState.value = it }
        }
    }

    private fun toUiState(snapshot: AppUpdateSnapshot, notify: Boolean, mine: Local): SystemUpdateUiState {
        val available = snapshot.available
        val installedVersion = snapshot.installed?.toString()
        return SystemUpdateUiState(
            installedLabel = UiText.res(
                R.string.sets_update_installed_label,
                installedVersion?.let { UiText.raw(it) } ?: UiText.res(R.string.sets_update_unknown_version),
                edition.displayText(),
            ),
            latestLabel = when {
                available != null -> UiText.res(R.string.sets_update_available, available.version)
                snapshot.releases.isEmpty() -> UiText.res(R.string.sets_update_not_checked)
                else -> UiText.res(R.string.sets_update_up_to_date)
            },
            availableVersion = available?.version,
            canInstall = available != null && installer.supported,
            isChecking = mine.isChecking,
            checkMessage = mine.checkMessage?.takeIf { mine.checkMessageAt == snapshot.lastCheckAt },
            lastCheckedLabel = lastCheckedLabel(snapshot.lastCheckAt),
            notificationsEnabled = notify,
            changelog = snapshot.releases.map { release ->
                val isNew = release.version == available?.version
                ChangelogEntry(
                    version = release.version,
                    publishedOn = release.publishedOn,
                    summary = release.summary,
                    notesUrl = release.notesUrl,
                    tag = when {
                        isNew -> UiText.res(R.string.sets_update_tag_new)
                        release.version == installedVersion -> UiText.res(R.string.sets_update_tag_installed)
                        else -> null
                    },
                    // The release on offer starts open; a toggle flips whatever the default was.
                    expanded = isNew != (release.version in mine.toggled),
                )
            },
            download = mine.download,
        )
    }

    fun checkNow() {
        if (local.value.isChecking) return
        local.update { it.copy(isChecking = true, checkMessage = null) }
        viewModelScope.launch {
            val message = when (checker.check(manual = true)) {
                AppUpdateCheckResult.UpdateAvailable -> UiText.res(R.string.sets_update_msg_available)
                AppUpdateCheckResult.UpToDate -> UiText.res(R.string.sets_update_msg_latest)
                AppUpdateCheckResult.Offline -> UiText.res(R.string.sets_update_msg_offline)
                AppUpdateCheckResult.InvalidResponse -> UiText.res(R.string.sets_update_msg_invalid)
                AppUpdateCheckResult.Skipped -> null
            }
            // Stamped with the check it describes, so the next check (the daily automatic one
            // included) retires it instead of leaving it beside a newer answer.
            val at = checker.snapshot.first().lastCheckAt
            local.update { it.copy(isChecking = false, checkMessage = message, checkMessageAt = at) }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { checker.setNotificationsEnabled(enabled) }
    }

    fun toggleRelease(version: String) {
        local.update { it.copy(toggled = if (version in it.toggled) it.toggled - version else it.toggled + version) }
    }

    fun downloadAndInstall() {
        if (local.value.download is UpdateDownload.Downloading) return
        val release = latestSnapshot?.available ?: return
        val apk = release.apkFor(edition) ?: run {
            setDownload(UpdateDownload.Failed(UiText.res(R.string.sets_update_no_apk, release.version, edition.displayText())))
            return
        }
        setDownload(UpdateDownload.Downloading(0))
        viewModelScope.launch {
            // One fixed name, so each download replaces the last and nothing piles up in the cache.
            val target = File(File(context.cacheDir, APK_DIR), APK_FILE)
            val result = downloader.downloadApk(apk.url, target, apk.sizeBytes) { fraction ->
                val percent = (fraction * 100).toInt()
                // Called from the download thread for every buffer; only a new percent is a new state.
                local.update { if (it.download == UpdateDownload.Downloading(percent)) it else it.copy(download = UpdateDownload.Downloading(percent)) }
            }
            setDownload(
                when (result) {
                    is ApkDownload.Failure -> {
                        Timber.w("App update download failed: %s", result.reason)
                        UpdateDownload.Failed(UiText.res(R.string.sets_update_download_failed))
                    }
                    ApkDownload.Done -> when (installer.install(target)) {
                        InstallStart.Started -> UpdateDownload.HandedToInstaller
                        InstallStart.NotAnUpdate ->
                            UpdateDownload.Failed(UiText.res(R.string.sets_update_not_newer))
                        InstallStart.NoInstaller -> UpdateDownload.Failed(UiText.res(R.string.sets_update_no_installer))
                    }
                },
            )
        }
    }

    private fun setDownload(download: UpdateDownload) = local.update { it.copy(download = download) }

    /**
     * Opens a release's full notes in the browser. A launcher device may have none; that is reported
     * in the tray with the address, so it can still be typed in somewhere else.
     */
    fun openNotes(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app can open %s", url)
            tasks.report(
                id = LINK_TRAY_ID,
                label = context.getString(R.string.sets_update_title),
                message = context.getString(R.string.sets_credits_no_browser, url),
                severity = NotificationSeverity.WARNING,
                action = NotificationAction.OpenSettingsScreen(AppUpdateReminder.SCREEN_ROUTE),
            )
        }
    }

    private fun lastCheckedLabel(at: Long): UiText =
        if (at <= 0L) {
            UiText.res(R.string.sets_update_never_checked)
        } else {
            UiText.res(R.string.sets_update_checked, PfpDateFormat.format(context, PfpDateStyle.ShortDateTime, at))
        }

    companion object {
        const val LINK_TRAY_ID = "system_update_link"
        private const val APK_DIR = "app_update"
        private const val APK_FILE = "PlayFieldPortal-update.apk"
    }
}

/** The edition as the user reads it ("Full" or "Lite"). */
internal fun AppEdition.displayText(): UiText = when (this) {
    AppEdition.FULL -> UiText.res(R.string.sets_edition_full)
    AppEdition.LITE -> UiText.res(R.string.sets_edition_lite)
}
