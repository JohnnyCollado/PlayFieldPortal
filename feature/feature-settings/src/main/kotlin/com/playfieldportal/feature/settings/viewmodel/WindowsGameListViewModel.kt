package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.WindowsGameLabel
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.provider.localsteam.AppIdSource
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamFolderLinker
import com.playfieldportal.feature.achievements.provider.steam.WindowsGameLabeler
import com.playfieldportal.feature.achievements.provider.steam.WindowsGameLabeler.LabelOutcome
import com.playfieldportal.feature.achievements.provider.steam.WindowsSteamGate
import com.playfieldportal.feature.library.scanner.safDocumentIdToRawPath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── Screen models ─────────────────────────────────────────────────────────────

/** One Windows game on the Game List. */
data class WindowsGameRow(
    val gameId: Long,
    val title: String,
    /** The user's label; null = Auto. */
    val label: WindowsGameLabel?,
    /** What PFP would guess ignoring [label]; null when it can't tell. */
    val autoLabel: WindowsGameLabel?,
    /** Where the game's linked folder is, when the registry still knows. */
    val folderPath: String?,
    /** True while a LOCAL_STEAM link exists, even one whose registry row is gone ([folderPath] null). */
    val folderLinked: Boolean,
    /** A label change or folder link is running for this row. */
    val busy: Boolean = false,
)

/**
 * A picked folder that carries no achievement list: link it as is, or write the emulator kit first.
 * Carried whole so the follow-up needs nothing from the original pick.
 */
data class KitChoice(
    val gameId: Long,
    val appId: String,
    val folderName: String,
    val source: AppIdSource,
    val treeUri: Uri,
    val installerEnabled: Boolean,
)

/** One option in a row's label picker. [label] null is Auto. */
data class LabelPickerItem(val label: WindowsGameLabel?, val text: String, val isCurrent: Boolean)

data class WindowsGameListUiState(
    val rows: List<WindowsGameRow> = emptyList(),
    /** False until the first load finishes, so the screen doesn't flash its empty state. */
    val loaded: Boolean = false,
    /** A message for a Notice modal. */
    val notice: String? = null,
    val kitChoice: KitChoice? = null,
    /** The screen should launch the folder picker; cleared by [WindowsGameListViewModel.onFolderPickLaunched]. */
    val requestFolderPick: Boolean = false,
)

// ── Pure helpers ──────────────────────────────────────────────────────────────

/** The right-aligned value on a row: the label, else the automatic guess, else "Not set". */
internal fun labelValueText(row: WindowsGameRow): String = row.label?.displayName()
    ?: row.autoLabel?.let { "Auto: ${it.displayName()}" }
    ?: "Not set"

/** The picker's four options, the row's current one marked. */
internal fun labelPickerItems(row: WindowsGameRow): List<LabelPickerItem> = buildList {
    add(LabelPickerItem(null, row.autoLabel?.let { "Auto (${it.displayName()})" } ?: "Auto", row.label == null))
    WindowsGameLabel.entries.forEach { add(LabelPickerItem(it, it.displayName(), row.label == it)) }
}

/** Only a Local game has a folder to link. */
internal fun showsFolderAction(row: WindowsGameRow): Boolean = row.label == WindowsGameLabel.LOCAL

private fun WindowsGameLabel.displayName(): String = when (this) {
    WindowsGameLabel.STEAM -> "Steam"
    WindowsGameLabel.NON_STEAM -> "Non-Steam (owned)"
    WindowsGameLabel.LOCAL -> "Local"
}

/**
 * State and actions for Windows ▸ Game List: every Windows game, its label, and its linked folder.
 *
 * Labels are applied by [WindowsGameLabeler], folders by [LocalSteamFolderLinker]; this class only
 * keeps the rows current and turns linker outcomes into a modal's worth of state.
 */
@HiltViewModel
class WindowsGameListViewModel @Inject constructor(
    private val games: GameRepository,
    private val gate: WindowsSteamGate,
    private val labeler: WindowsGameLabeler,
    private val folderLinker: LocalSteamFolderLinker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WindowsGameListUiState())
    val uiState: StateFlow<WindowsGameListUiState> = _uiState.asStateFlow()

    /** The game the pending folder pick is for. */
    private var pickingFor: Long? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val folders = labeler.linkedFolders()
            val rows = games.getByPlatform(WINDOWS_PLATFORM_ID)
                .sortedBy { it.displayTitle.lowercase() }
                .map { rowFor(it, folders[it.id]) }
            _uiState.update { it.copy(rows = rows, loaded = true) }
        }
    }

    /** Saves [label] (null = Auto) through the labeler, then re-reads that row. */
    fun setLabel(gameId: Long, label: WindowsGameLabel?) {
        viewModelScope.launch {
            setBusy(gameId, true)
            val outcome = runCatching { labeler.setLabel(gameId, label) }
                .onFailure { Timber.w(it, "Could not label game %d", gameId) }
                .getOrNull()
            refreshRow(gameId)
            if (outcome == null) {
                notify("The label could not be saved.")
            } else if (outcome == LabelOutcome.SteamNotFound) {
                notify("Labelled Steam, but no matching Steam game was found, so nothing was linked.")
            }
        }
    }

    // ── Folder pick ───────────────────────────────────────────────────────────

    /** Asks the screen to open the tree picker for [gameId]. */
    fun beginFolderPick(gameId: Long) {
        pickingFor = gameId
        _uiState.update { it.copy(requestFolderPick = true) }
    }

    /** The screen has launched the picker; don't launch it again on the next recomposition. */
    fun onFolderPickLaunched() = _uiState.update { it.copy(requestFolderPick = false) }

    /** The user picked a folder, or dismissed the picker (null: nothing happens). */
    fun onFolderPicked(uri: Uri?) {
        val gameId = pickingFor
        if (uri == null || gameId == null) {
            pickingFor = null
            return
        }
        pickingFor = null
        runLink(gameId, uri) { folderLinker.link(gameId, uri) }
    }

    /** Link the folder as is, without writing the emulator kit into it. */
    fun linkOnly() = takeKitChoice()?.let { c ->
        runLink(c.gameId, c.treeUri) { folderLinker.linkWithoutKit(c.gameId, c.treeUri, c.appId, c.source) }
    }

    /** Write the emulator kit, then link. Only offered when [KitChoice.installerEnabled]. */
    fun installKitAndLink() = takeKitChoice()?.let { c ->
        runLink(c.gameId, c.treeUri) { folderLinker.installKitAndLink(c.gameId, c.treeUri, c.appId, c.source) }
    }

    fun dismissKitChoice() = _uiState.update { it.copy(kitChoice = null) }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun takeKitChoice(): KitChoice? {
        val choice = _uiState.value.kitChoice ?: return null
        _uiState.update { it.copy(kitChoice = null) }
        return choice
    }

    private fun runLink(gameId: Long, treeUri: Uri, link: suspend () -> LocalSteamFolderLinker.LinkOutcome) {
        viewModelScope.launch {
            setBusy(gameId, true)
            val outcome = runCatching { link() }
                .onFailure { Timber.w(it, "Local Steam folder link failed for game %d", gameId) }
                .getOrNull()
            applyLinkOutcome(gameId, treeUri, outcome)
            refreshRow(gameId)
        }
    }

    // The one place every LinkOutcome becomes state (D6). Linked is refreshed by the caller.
    private fun applyLinkOutcome(gameId: Long, treeUri: Uri, outcome: LocalSteamFolderLinker.LinkOutcome?) {
        when (outcome) {
            null -> notify("That folder could not be read. Pick it again, or remount the storage it is on.")
            is LocalSteamFolderLinker.LinkOutcome.Linked -> Unit
            is LocalSteamFolderLinker.LinkOutcome.NeedsKit -> _uiState.update {
                it.copy(
                    kitChoice = KitChoice(
                        gameId = gameId,
                        appId = outcome.appId,
                        folderName = outcome.folderName,
                        source = outcome.source,
                        treeUri = treeUri,
                        installerEnabled = outcome.installerEnabled,
                    )
                )
            }
            is LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation -> notify(
                "Several Steam games match \"${outcome.folderName}\". Finish linking this folder on the " +
                    "game's Shiba Coins page, where you can choose the right one."
            )
            is LocalSteamFolderLinker.LinkOutcome.NoEmuData -> notify(outcome.reason)
            is LocalSteamFolderLinker.LinkOutcome.Failed -> notify(outcome.reason)
        }
    }

    private suspend fun refreshRow(gameId: Long) {
        val game = games.getById(gameId)
        val folder = labeler.linkedFolders()[gameId]
        val fresh = game?.let { rowFor(it, folder) }
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.gameId == gameId) fresh ?: it.copy(busy = false) else it })
        }
    }

    private suspend fun rowFor(game: Game, folder: WindowsGameLabeler.LinkedFolder?) = WindowsGameRow(
        gameId = game.id,
        title = game.displayTitle,
        label = game.windowsLabel,
        autoLabel = gate.autoLabel(game),
        folderPath = folder?.let { f -> f.folderDocId?.let(::safDocumentIdToRawPath) ?: f.folderName },
        folderLinked = folder != null,
    )

    private fun setBusy(gameId: Long, busy: Boolean) = _uiState.update { state ->
        state.copy(rows = state.rows.map { if (it.gameId == gameId) it.copy(busy = busy) else it })
    }

    private fun notify(message: String) = _uiState.update { it.copy(notice = message) }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
