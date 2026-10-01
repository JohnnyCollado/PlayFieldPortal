package com.playfieldportal.feature.settings.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.AutoCoreMemory
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.isRetroArchProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One console on the Emulators page: its name and the emulator its games launch with. */
@Immutable
data class SetupEmulatorRow(
    val platformId: String,
    val consoleName: String,
    /** The current emulator's Android label (a RetroArch core keeps its profile name). */
    val emulatorLabel: String?,
    /** Installed candidates in launch preference, by profile id; confirm cycles through them. */
    val candidateIds: List<String>,
    /** Index of the current emulator in [candidateIds]. */
    val currentIndex: Int,
)

@Immutable
data class SetupPagesUiState(
    val emulatorRows: List<SetupEmulatorRow> = emptyList(),
    /** Every verified PC launcher's label, joined for the Windows Games page's Detected row. */
    val detectedLaunchers: String = "",
    val windowsFolderName: String? = null,
    val hints: InterfaceHints = InterfaceHints(),
    val isHomeApp: Boolean = false,
)

// Read once per pass; `isHomeApp` changes only when the page resumes from Android's chooser.
private data class DeviceFacts(val detectedLaunchers: String, val isHomeApp: Boolean)

/**
 * Initial Setup's device pages — Emulators, Windows Games, Hints & Touch and Home App. Pure glue:
 * each writes through the store its Settings screen uses (Emulator Assignment's memory-card
 * default, the Windows library, Display's hint prefs, the Home role request), so a value set here
 * shows there. The Controller page drives ControllerSettingsViewModel directly for the same reason.
 */
@HiltViewModel
class SetupPagesViewModel @Inject constructor(
    private val environment: SetupEnvironment,
    private val hintPrefs: InterfaceHintPrefs,
    private val memoryCardRepository: MemoryCardRepository,
    platformDao: PlatformDao,
    gameRepository: GameRepository,
    profileRepository: EmulatorProfileRepository,
    autoCoreMemory: AutoCoreMemory,
    private val romRootRepository: RomRootRepository,
    private val windowsLibrarySetup: WindowsLibrarySetup,
) : ViewModel() {

    private val rowsBuilder = PlatformAssignRowsBuilder(profileRepository, autoCoreMemory)

    private val facts = MutableStateFlow(
        DeviceFacts(
            detectedLaunchers = environment.pcLaunchers().joinToString(" · ") { it.label },
            isHomeApp = environment.isHomeApp(),
        ),
    )

    private val emulatorRows = combine(
        memoryCardRepository.observeAll(),
        platformDao.observeAll(),
        gameRepository.observeAllGames(),
        profileRepository.profiles,
    ) { cards, platforms, games, allProfiles ->
        rowsBuilder.build(cards, platforms, games, allProfiles).mapNotNull(::toSetupRow)
    }

    private val windowsFolder = memoryCardRepository.observeAll().map { cards ->
        cards.windowsTreeUri()?.let(::rootDisplayName)
    }

    val uiState: StateFlow<SetupPagesUiState> = combine(
        emulatorRows, windowsFolder, hintPrefs.hints, facts,
    ) { rows, folder, hints, device ->
        SetupPagesUiState(
            emulatorRows = rows,
            detectedLaunchers = device.detectedLaunchers,
            windowsFolderName = folder,
            hints = hints,
            isHomeApp = device.isHomeApp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupPagesUiState(isHomeApp = facts.value.isHomeApp))

    // A console earns a row only when a standalone emulator can run it: RetroArch cores stay in
    // the cycle but alone don't make a console "have a known emulator installed".
    private fun toSetupRow(row: PlatformAssignRow): SetupEmulatorRow? {
        val profiles = row.candidates.map { it.profile }
        if (profiles.none { !it.isRetroArchProfile() }) return null
        val current = row.candidates.indexOfFirst { it.isDefault }.coerceAtLeast(0)
        return SetupEmulatorRow(
            platformId = row.platformId,
            consoleName = row.platformName,
            emulatorLabel = labelOf(profiles[current]),
            candidateIds = profiles.map { it.id },
            currentIndex = current,
        )
    }

    private fun labelOf(profile: EmulatorProfile): String =
        if (profile.isRetroArchProfile()) profile.name
        else environment.appLabel(profile.packageName) ?: profile.name

    private fun List<MemoryCard>.windowsTreeUri(): String? =
        firstOrNull { it.platformId == WindowsLibrarySetup.PLATFORM_ID }?.treeUri?.takeIf { it.isNotBlank() }

    // ── Emulators ─────────────────────────────────────────────────────────────

    /** Confirm on a console row: the next installed candidate becomes its default. */
    fun cycleEmulator(platformId: String) {
        val row = uiState.value.emulatorRows.firstOrNull { it.platformId == platformId } ?: return
        if (row.candidateIds.size < 2) return
        val next = row.candidateIds[(row.currentIndex + 1) % row.candidateIds.size]
        // The same write as Emulator Assignment's "set default".
        viewModelScope.launch { memoryCardRepository.setEmulator(platformId, next) }
    }

    // ── Windows Games ─────────────────────────────────────────────────────────

    /**
     * Grants the picked folder (read+write, so `import/` can be created) and points the Windows
     * library at it. That settles the XMB's "Finish your Windows Library" prompt condition, so a
     * prompt already pending is dropped too.
     */
    fun linkWindowsFolder(uri: Uri) {
        romRootRepository.persist(uri, writable = true)
        viewModelScope.launch {
            windowsLibrarySetup.usePickedFolder(uri.toString())
            windowsLibrarySetup.clearSetupPrompt()
        }
    }

    // ── Hints & Touch ─────────────────────────────────────────────────────────

    fun toggleHints() {
        val enabled = uiState.value.hints.enabled
        viewModelScope.launch { hintPrefs.setEnabled(!enabled) }
    }

    fun cycleHintDelay() {
        val next = nextHintDelay(uiState.value.hints.delaySeconds)
        viewModelScope.launch { hintPrefs.setDelaySeconds(next) }
    }

    fun cycleTouchButton() {
        val modes = TouchNavButtonMode.entries
        val next = modes[(modes.indexOf(uiState.value.hints.touchButton) + 1) % modes.size]
        viewModelScope.launch { hintPrefs.setTouchButton(next) }
    }

    // ── Home App ──────────────────────────────────────────────────────────────

    /** Re-reads the Home role — called when the page resumes from Android's chooser. */
    fun refreshHomeApp() = facts.update { it.copy(isHomeApp = environment.isHomeApp()) }

    fun homeRoleIntent(): Intent = environment.homeRoleIntent()
}
