package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.kb.PlatformGain
import com.playfieldportal.core.data.repository.SafeMedia
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.emulatorkb.CantShare
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbExport
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbImportPlan
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidator
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.kb.EmulatorKbUpdater
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeRefresher
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeStore
import com.playfieldportal.feature.launcher.kb.KbUpdateResult
import com.playfieldportal.feature.launcher.kb.KbUpdateStatus
import com.playfieldportal.feature.launcher.kb.KbUserFile
import com.playfieldportal.feature.launcher.kb.KnownPlatformIds
import com.playfieldportal.feature.launcher.kb.SignerProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Settings > Emulators > Emulator knowledge. [statusText] is the status card's line ("Official
 * v2026.10.02 · verified · 87 emulators · checked today 09:14", "Built-in only", "Built-in · updates
 * not configured"); [errorText] is the last failed check's message, shown under it.
 */
data class EmulatorKnowledgeUiState(
    val statusText: String = "Built-in only",
    val errorText: String? = null,
    val autoUpdate: Boolean = true,
    /** False while this build has no release key pinned: checking and automatic updates cannot work. */
    val updatesConfigured: Boolean = true,
    /** A manual check is running. */
    val isChecking: Boolean = false,
    /** The message of the manual check that just finished, null while checking or before the first one. */
    val checkResult: String? = null,
    val userFiles: List<KbUserFile> = emptyList(),
    /** Consoles that gained file types since the app started; a rescan is suggested for these. */
    val gainedFileTypes: List<PlatformGain> = emptyList(),
    val confirmResetVisible: Boolean = false,
    /** The picked file's per-entry review, open until confirmed or cancelled; null when no import is under way. */
    val review: KbImportReview? = null,
    /** A picked file is being read or planned; the review is not open yet. */
    val isImporting: Boolean = false,
    /** Why the last import attempt ended without a review or a stored file; shown in a Notice modal. */
    val importNotice: String? = null,
    /** The export picker (AD-12), open until exported or cancelled; null when no export is under way. */
    val export: KbExportPicker? = null,
    /** The outcome of the last export attempt, or why there was nothing to export; shown in a Notice modal. */
    val exportNotice: KbExportNotice? = null,
    // Raw inputs of statusText / errorText, kept so any one of them changing recomputes both.
    val status: KbUpdateStatus? = null,
    val lastCheckAt: Long = 0L,
    val officialApplied: Boolean = false,
)

/** A picked file under review (AD-11). [confirmEnabled] is true when anything selectable is ticked, emulators or consoles. */
data class KbImportReview(val displayName: String, val plan: EmulatorKbImportPlan) {
    val confirmEnabled: Boolean get() = plan.selectedCount + plan.selectedPlatformCount > 0

    /** "Import 2 emulators", "Import 1 emulator and 1 console update", "Update 1 console". */
    val confirmLabel: String
        get() {
            val emulators = plan.selectedCount
            val consoles = plan.selectedPlatformCount
            val emulatorText = "$emulators ${if (emulators == 1) "emulator" else "emulators"}"
            return when {
                consoles == 0 -> "Import $emulatorText"
                emulators == 0 -> "Update $consoles ${if (consoles == 1) "console" else "consoles"}"
                else -> "Import $emulatorText and $consoles console ${if (consoles == 1) "update" else "updates"}"
            }
        }
}

/** The export picker: the shareable [entries] (custom first, then edited official), all ticked by default. */
data class KbExportPicker(
    val entries: List<EmulatorProfile>,
    val cantShare: List<CantShare>,
    val selectedIds: Set<String> = entries.mapTo(mutableSetOf()) { it.id },
) {
    val selectedCount: Int get() = selectedIds.size
    val allSelected: Boolean get() = selectedCount == entries.size
    val exportEnabled: Boolean get() = selectedCount > 0

    /** "Export 2 emulators". */
    val confirmLabel: String get() = "Export $selectedCount ${if (selectedCount == 1) "emulator" else "emulators"}"

    /** The ticked profiles, in picker order. */
    val selected: List<EmulatorProfile> get() = entries.filter { it.id in selectedIds }
}

data class KbExportNotice(val title: String, val message: String)

/** What reading a picked document produced. [Content.displayName] is the provider's name, uncapped. */
internal sealed interface KbPick {
    class Content(val displayName: String?, val bytes: ByteArray) : KbPick
    data object TooLarge : KbPick
    data object Unreadable : KbPick
}

internal fun interface KbFileReader {
    suspend fun read(uri: Uri): KbPick
}

/** Reads a SAF document, capped at the knowledge file limit, and its OpenableColumns display name. */
internal class ContentResolverKbFileReader(private val context: Context) : KbFileReader {
    override suspend fun read(uri: Uri): KbPick = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri) ?: return@withContext KbPick.Unreadable
            val bytes = stream.use { with(SafeMedia) { it.readCapped(MAX_BYTES) } } ?: return@withContext KbPick.TooLarge
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            KbPick.Content(name, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Providers throw more than IOException and SecurityException (IllegalStateException, RuntimeException).
            Timber.w(e, "Failed to read the picked knowledge file")
            KbPick.Unreadable
        }
    }

    private companion object {
        const val MAX_BYTES = 1L * 1024 * 1024
    }
}

/** Writes the exported file to the SAF document the user created; false when it could not be written. */
internal fun interface KbFileWriter {
    suspend fun write(uri: Uri, bytes: ByteArray): Boolean
}

/** Writes through the content resolver; a failed write deletes the partial document where the provider allows. */
internal class ContentResolverKbFileWriter(private val context: Context) : KbFileWriter {
    override suspend fun write(uri: Uri, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            val stream = resolver.openOutputStream(uri, "wt") ?: return@withContext false
            stream.use { it.write(bytes) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Providers throw more than IOException and SecurityException (an unsupported "wt"
            // mode is an IllegalArgumentException); any of them is a failed export, not a crash.
            Timber.w(e, "Failed to write the exported knowledge file")
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            false
        }
    }
}

@HiltViewModel
class EmulatorKnowledgeViewModel internal constructor(
    private val updater: EmulatorKbUpdater,
    private val store: EmulatorKnowledgeStore,
    private val refresher: EmulatorKnowledgeRefresher,
    private val fileReader: KbFileReader,
    private val fileWriter: KbFileWriter,
    private val signerProbe: SignerProbe,
    private val profiles: EmulatorProfileRepository,
    private val knownPlatformIds: Set<String>,
    private val selfPackage: String,
    private val clock: () -> Long,
    private val timeZone: TimeZone,
    /** Decoding and validating the picked file is CPU work; tests substitute their own. */
    private val planDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject constructor(
        updater: EmulatorKbUpdater,
        store: EmulatorKnowledgeStore,
        refresher: EmulatorKnowledgeRefresher,
        signerProbe: SignerProbe,
        profiles: EmulatorProfileRepository,
        @ApplicationContext context: Context,
    ) : this(
        updater, store, refresher, ContentResolverKbFileReader(context), ContentResolverKbFileWriter(context), signerProbe, profiles,
        KnownPlatformIds.ALL, context.packageName,
        System::currentTimeMillis, TimeZone.getDefault(), Dispatchers.Default,
    )

    private val _uiState = MutableStateFlow(EmulatorKnowledgeUiState(updatesConfigured = updater.isConfigured))
    val uiState: StateFlow<EmulatorKnowledgeUiState> = _uiState.asStateFlow()

    init {
        // effective stays empty until the store has loaded once.
        viewModelScope.launch { store.current() }
        viewModelScope.launch { updater.autoUpdateEnabled.collect { v -> _uiState.update { it.copy(autoUpdate = v) } } }
        viewModelScope.launch { updater.lastCheckAt.collect { v -> _uiState.update { it.copy(lastCheckAt = v).withStatusText() } } }
        viewModelScope.launch { updater.status.collect { v -> _uiState.update { it.copy(status = v).withStatusText() } } }
        viewModelScope.launch {
            store.effective.collect { v -> _uiState.update { it.copy(officialApplied = v.officialApplied).withStatusText() } }
        }
        viewModelScope.launch { store.userFiles.collect { v -> _uiState.update { it.copy(userFiles = v) } } }
        viewModelScope.launch { refresher.gainedFileTypes.collect { v -> _uiState.update { it.copy(gainedFileTypes = v) } } }
    }

    fun checkNow() {
        if (_uiState.value.isChecking) return
        _uiState.update { it.copy(isChecking = true, checkResult = null) }
        viewModelScope.launch {
            val message = try {
                updater.check(manual = true).message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "The knowledge update check failed")
                "The check could not be completed."
            } finally {
                _uiState.update { it.copy(isChecking = false) }
            }
            _uiState.update { it.copy(checkResult = message) }
        }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { updater.setAutoUpdate(enabled) }
    }

    /** One step. Detection and platform extensions follow once the file's layer is gone. */
    fun removeFile(id: String) {
        viewModelScope.launch {
            try {
                if (store.removeUserFile(id)) refresher.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove the knowledge file")
                failed("Couldn't remove the file", "The file could not be removed.")
            }
        }
    }

    /**
     * Reads, decodes, validates and plans the picked file. Ends in [EmulatorKnowledgeUiState.review] or a notice;
     * stores nothing. Ignored while another import is in flight or the export picker is open.
     */
    fun importFrom(uri: Uri) {
        val state = _uiState.value
        if (state.isImporting || state.export != null) return
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            try {
                when (val picked = fileReader.read(uri)) {
                    KbPick.TooLarge -> notice("That file is too large to be an emulator knowledge file.")
                    KbPick.Unreadable -> notice("That file could not be read.")
                    is KbPick.Content -> plan(picked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to review the picked knowledge file")
                notice("That file could not be read.")
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    private suspend fun plan(picked: KbPick.Content) {
        // Decoding and validating up to 1 MiB, then the plan, stay off Main.
        val planned = withContext(planDispatcher) {
            val document = when (val decoded = EmulatorKbDecoder.decode(String(picked.bytes, Charsets.UTF_8))) {
                is EmulatorKbDecode.Rejected -> return@withContext Planned.Notice("That file ${decoded.reason}.")
                is EmulatorKbDecode.Decoded -> decoded.document
            }
            val validated = EmulatorKbValidator.validate(document, knownPlatformIds, selfPackage)
            val userEdited = profiles.getAllPersistedProfiles()
                .filter { it.userModified }
                .mapNotNullTo(mutableSetOf()) { it.knowledgeId }
            val plan = EmulatorKbImportPlan.build(store.current(), validated, userEdited, signerProbe::probe)
            if (plan.items.isEmpty()) return@withContext Planned.Notice("That file has nothing to import on this device.")
            Planned.Review(KbImportReview(displayName(picked.displayName), plan))
        }
        when (planned) {
            is Planned.Notice -> notice(planned.message)
            is Planned.Review -> _uiState.update { it.copy(review = planned.review, importNotice = null) }
        }
    }

    private sealed interface Planned {
        class Notice(val message: String) : Planned
        class Review(val review: KbImportReview) : Planned
    }

    /** The provider's name without control or bidi characters (they could disguise it), capped. */
    private fun displayName(raw: String?): String = raw.orEmpty()
        .filterNot { it.isISOControl() || Character.getType(it).toByte() in HIDDEN_CHAR_TYPES }
        .trim()
        .take(MAX_NAME_CHARS)
        .ifEmpty { FALLBACK_NAME }

    private fun notice(message: String) = _uiState.update { it.copy(review = null, importNotice = message) }

    private fun failed(title: String, message: String) = _uiState.update { it.copy(exportNotice = KbExportNotice(title, message)) }

    fun toggleReviewItem(key: String) =
        _uiState.update { state -> state.review?.let { state.copy(review = it.copy(plan = it.plan.toggle(key))) } ?: state }

    fun cancelImport() = _uiState.update { it.copy(review = null) }

    fun dismissImportNotice() = _uiState.update { it.copy(importNotice = null) }

    /** Stores only the ticked entries as one user file, then refreshes. Does nothing while nothing is ticked. */
    fun confirmImport() {
        val review = _uiState.value.review?.takeIf { it.confirmEnabled } ?: return
        _uiState.update { it.copy(review = null) }
        viewModelScope.launch {
            try {
                store.addUserFile(review.displayName, review.plan.selectedDocument())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to store the imported knowledge file")
                notice("The file could not be saved on this device.")
                return@launch
            }
            try {
                refresher.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh after importing a knowledge file")
                notice("The file was saved, but refreshing emulator detection failed.")
            }
        }
    }

    /** Opens the export picker on the profiles that can be shared, or says there are none. Writes nothing. */
    fun openExport() {
        val state = _uiState.value
        if (state.isImporting || state.review != null || state.export != null) return
        viewModelScope.launch {
            val candidates = EmulatorKbExport.candidates(profiles.getAllPersistedProfiles())
            val cantShare = EmulatorKbExport.build(candidates, knownPlatformIds, selfPackage).cantShare
            val blocked = cantShare.mapTo(mutableSetOf()) { it.profileId }
            val shareable = candidates.filter { it.id !in blocked }
            if (candidates.isEmpty()) {
                _uiState.update {
                    it.copy(exportNotice = KbExportNotice("Nothing to export", "You have no custom or edited emulators to share yet."))
                }
            } else if (shareable.isEmpty()) {
                val why = cantShare.firstOrNull()?.reason?.let { ": $it" }.orEmpty()
                _uiState.update { it.copy(exportNotice = KbExportNotice("Nothing to export", "None of your emulators can be shared$why")) }
            } else {
                // Custom emulators first, then the edited official ones, as the picker groups them.
                val ordered = shareable.filter { it.isCustom } + shareable.filterNot { it.isCustom }
                _uiState.update { it.copy(export = KbExportPicker(ordered, cantShare)) }
            }
        }
    }

    fun toggleExportItem(profileId: String) = _uiState.update { state ->
        val picker = state.export?.takeIf { p -> p.entries.any { it.id == profileId } } ?: return@update state
        val next = if (profileId in picker.selectedIds) picker.selectedIds - profileId else picker.selectedIds + profileId
        state.copy(export = picker.copy(selectedIds = next))
    }

    /** Ticks everything, or clears everything when everything is already ticked. */
    fun toggleExportAll() = _uiState.update { state ->
        val picker = state.export ?: return@update state
        val next = if (picker.allSelected) emptySet() else picker.entries.mapTo(mutableSetOf()) { it.id }
        state.copy(export = picker.copy(selectedIds = next))
    }

    fun cancelExport() = _uiState.update { it.copy(export = null) }

    fun dismissExportNotice() = _uiState.update { it.copy(exportNotice = null) }

    /** Writes the ticked profiles to [uri] (null when the file picker was cancelled, which keeps the picker open). Nothing at 0 ticked. */
    fun exportTo(uri: Uri?) {
        val picker = _uiState.value.export?.takeIf { it.exportEnabled } ?: return
        if (uri == null) return
        viewModelScope.launch {
            val result = EmulatorKbExport.build(picker.selected, knownPlatformIds, selfPackage)
            val text = EmulatorKbExport.encode(result.document)
            val ok = result.exportedCount > 0 && fileWriter.write(uri, text.toByteArray(Charsets.UTF_8))
            val notice = if (ok) {
                val n = result.exportedCount
                KbExportNotice("Exported", "Exported $n ${if (n == 1) "emulator" else "emulators"}")
            } else {
                KbExportNotice("Couldn't export", "The file could not be saved.")
            }
            _uiState.update { it.copy(export = null, exportNotice = notice) }
        }
    }

    fun requestReset() = _uiState.update { it.copy(confirmResetVisible = true) }

    fun dismissReset() = _uiState.update { it.copy(confirmResetVisible = false) }

    fun confirmReset() {
        if (!_uiState.value.confirmResetVisible) return
        _uiState.update { it.copy(confirmResetVisible = false) }
        viewModelScope.launch {
            try {
                store.resetToBuiltIn()
                refresher.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to reset the knowledge base")
                failed("Couldn't reset", "The emulator knowledge could not be reset.")
            }
        }
    }

    private fun EmulatorKnowledgeUiState.withStatusText(): EmulatorKnowledgeUiState {
        val result = status?.result
        val failure = result?.takeIf { it !in NOT_FAILURES }
        val text = when {
            officialApplied -> officialLine(status, lastCheckAt)
            result == KbUpdateResult.NotConfigured -> "Built-in · updates not configured"
            else -> "Built-in only"
        }
        return copy(statusText = text, errorText = failure?.message)
    }

    private fun officialLine(status: KbUpdateStatus?, lastCheckAt: Long): String {
        val version = status?.label?.ifBlank { null } ?: status?.version?.takeIf { it > 0 }?.toString()
        val parts = buildList {
            add(if (version == null) "Official" else "Official v$version")
            add("verified")
            if (status != null && status.emulatorCount > 0) {
                add("${status.emulatorCount} ${if (status.emulatorCount == 1) "emulator" else "emulators"}")
            }
            if (lastCheckAt > 0) add("checked ${checkedLabel(lastCheckAt)}")
        }
        return parts.joinToString(" · ")
    }

    private fun checkedLabel(epochMillis: Long): String {
        val zone = timeZone.toZoneId()
        val checked = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val time = checked.format(TIME)
        return when (checked.toLocalDate()) {
            today -> "today $time"
            today.minusDays(1) -> "yesterday $time"
            else -> "${checked.format(DATE)} $time"
        }
    }

    private companion object {
        const val MAX_NAME_CHARS = 64
        const val FALLBACK_NAME = "Knowledge file"
        val HIDDEN_CHAR_TYPES = setOf(Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR)
        val NOT_FAILURES = setOf(KbUpdateResult.Installed, KbUpdateResult.NotConfigured, KbUpdateResult.Skipped)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    }
}
