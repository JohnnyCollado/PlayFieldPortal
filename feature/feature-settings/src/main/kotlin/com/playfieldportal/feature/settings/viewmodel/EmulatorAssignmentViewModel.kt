package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.EmulatorLaunchResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.LaunchSource
import com.playfieldportal.feature.launcher.byLaunchPreference
import com.playfieldportal.feature.launcher.supportsPlatform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Platforms whose "games" launch through packages/PC launchers, never an emulator profile. */
private val NON_EMULATOR_PLATFORMS = setOf("android", "windows")

private const val MEMORY_CARD_SUFFIX = " Memory Card"

// ── Screen models ─────────────────────────────────────────────────────────────

/** One selectable emulator inside a platform's assignment detail. */
data class PlatformEmulatorCandidate(
    val profile: EmulatorProfile,
    /** True when this is the catalog's first pick (standalone before RetroArch cores). */
    val isRecommended: Boolean,
    /** True when this is the emulator games currently resolve to. */
    val isDefault: Boolean,
)

/**
 * One platform row on the assignment screen: what its games resolve to today, how many games it
 * covers, and how many of those carry a per-game override (the classic "why does only THIS game
 * launch wrong" — overrides are invisible from the platform level until they are counted).
 */
data class PlatformAssignRow(
    val platformId: String,
    val platformName: String,
    val gameCount: Int,
    val overrideCount: Int,
    /** The memory-card level's stored emulator id/package, if any (the user-set console default). */
    val storedDefaultId: String? = null,
    /** Display name of [storedDefaultId] even when that profile is no longer installed. */
    val storedDefaultName: String? = null,
    /** Platform record's preferred emulator, if any (usually null — set by restore/seed). */
    val platformDefaultId: String? = null,
    /** The emulator games would launch with (full ladder, no per-game override), if resolvable. */
    val resolvedProfile: EmulatorProfile? = null,
    val resolvedCoreName: String? = null,
    /** True when the resolved RetroArch profile maps no core for this platform. */
    val isMissingCore: Boolean = false,
    /** Which ladder level decided [resolvedProfile]; null when nothing resolved. */
    val source: LaunchSource? = null,
    /** Installed emulators that can run this platform, preference-ordered. */
    val candidates: List<PlatformEmulatorCandidate> = emptyList(),
) {
    val resolvedProfileName: String? get() = resolvedProfile?.name

    /** True when the default currently resolves automatically (no stored card/platform choice). */
    val isAutomatic: Boolean get() = source == LaunchSource.CATALOG_DEFAULT

    val defaultDisplayName: String?
        get() = resolvedProfile?.name
            ?: storedDefaultName
            ?: platformDefaultId
}

data class EmulatorAssignmentUiState(
    val platforms: List<PlatformAssignRow> = emptyList(),
    val detailPlatformId: String? = null,
    /** Row to restore focus to when returning from a platform's detail. */
    val returnFocusKey: String? = null,
    val confirmClearPlatformId: String? = null,
    val message: String? = null,
) {
    val detailRow: PlatformAssignRow?
        get() = platforms.firstOrNull { it.platformId == detailPlatformId }
}

@HiltViewModel
class EmulatorAssignmentViewModel @Inject constructor(
    private val memoryCardRepository: MemoryCardRepository,
    private val platformDao: PlatformDao,
    private val gameRepository: GameRepository,
    private val profileRepository: EmulatorProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmulatorAssignmentUiState())
    val uiState: StateFlow<EmulatorAssignmentUiState> = _uiState.asStateFlow()

    init {
        // Reactive like the rest of Settings: cards/games/platforms/profile-set changes anywhere
        // (a restore, an auto-config pass, a Game Detail override pick) re-derive the rows.
        viewModelScope.launch {
            combine(
                memoryCardRepository.observeAll(),
                platformDao.observeAll(),
                gameRepository.observeAllGames(),
                profileRepository.profiles,
            ) { cards, platforms, games, allProfiles ->
                buildRows(cards, platforms, games, allProfiles)
            }.collect { rows ->
                _uiState.update { it.copy(platforms = rows) }
            }
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    fun openDetail(platformId: String) = _uiState.update {
        it.copy(detailPlatformId = platformId, returnFocusKey = platformId, confirmClearPlatformId = null, message = null)
    }

    fun closeDetail() = _uiState.update {
        it.copy(detailPlatformId = null, confirmClearPlatformId = null)
    }

    /** Returns true when the back press was consumed (detail → list). */
    fun onBack(): Boolean {
        if (_uiState.value.detailPlatformId == null) return false
        closeDetail()
        return true
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    // ── Default assignment ────────────────────────────────────────────────────
    //
    // Writing the memory-card emulator is the per-system default PFP already edits everywhere
    // else (Library Manager's console Emulator row): it wins over the platform record's
    // preferred package and over the catalog fallback, so "set default" here takes effect for
    // every game on the console that has no per-game override.

    fun selectDefault(platformId: String, profileId: String) {
        val row = _uiState.value.platforms.firstOrNull { it.platformId == platformId } ?: return
        val name = row.candidates.firstOrNull { it.profile.id == profileId }?.profile?.name
            ?: row.defaultDisplayName
        viewModelScope.launch {
            memoryCardRepository.setEmulator(platformId, profileId)
            _uiState.update {
                it.copy(message = "${row.platformName}: default emulator set to ${name ?: profileId}")
            }
        }
    }

    /** Clears the stored console default so games follow the automatic (catalog) pick again. */
    fun useAutomaticDefault(platformId: String) {
        val row = _uiState.value.platforms.firstOrNull { it.platformId == platformId } ?: return
        viewModelScope.launch {
            memoryCardRepository.setEmulator(platformId, null)
            _uiState.update {
                it.copy(message = "${row.platformName}: using the recommended emulator")
            }
        }
    }

    // ── Per-game override bulk clear ──────────────────────────────────────────
    // Destructive to user configuration, so it is always confirmed and scoped to ONE platform.

    fun requestClearOverrides() {
        val platformId = _uiState.value.detailPlatformId ?: return
        _uiState.update { it.copy(confirmClearPlatformId = platformId) }
    }

    fun cancelClearOverrides() = _uiState.update { it.copy(confirmClearPlatformId = null) }

    fun confirmClearOverrides() {
        val platformId = _uiState.value.confirmClearPlatformId ?: return
        val row = _uiState.value.platforms.firstOrNull { it.platformId == platformId }
        viewModelScope.launch {
            gameRepository.clearPreferredEmulatorForPlatform(platformId)
            _uiState.update {
                it.copy(
                    confirmClearPlatformId = null,
                    message = "${row?.platformName ?: platformId}: cleared ${row?.overrideCount ?: 0} " +
                        "per-game override(s) — those games now follow the platform default",
                )
            }
        }
    }

    // ── Row derivation ────────────────────────────────────────────────────────

    private fun buildRows(
        cards: List<MemoryCard>,
        platforms: List<com.playfieldportal.core.data.database.entity.PlatformEntity>,
        games: List<com.playfieldportal.core.domain.model.Game>,
        allProfiles: List<EmulatorProfile>,
    ): List<PlatformAssignRow> {
        // The pool the ladder resolves against must match Game Detail exactly, so the attribution
        // here can never disagree with a launch. profiles flow still supplies names for stored
        // defaults whose profile is currently uninstalled.
        val installed = profileRepository.getInstalledProfiles()
        val namesById = allProfiles.associate { it.id to it.name }
        val namesByPackage = allProfiles.associate { it.packageName to it.name }
        val platformById = platforms.associateBy { it.id }
        val cardByPlatform = cards.associateBy { it.platformId }
        val gamesByPlatform = games.groupBy { it.platformId }

        return gamesByPlatform
            .filterKeys { it !in NON_EMULATOR_PLATFORMS }
            .mapNotNull { (platformId, platformGames) ->
                if (platformGames.isEmpty()) return@mapNotNull null
                val card = cardByPlatform[platformId]
                val platformEntity = platformById[platformId]
                val platformName = platformEntity?.name
                    ?: card?.displayName?.removeSuffix(MEMORY_CARD_SUFFIX)
                    ?: platformId.uppercase()

                val installedForPlatform =
                    installed.filter { it.isAvailable && it.supportsPlatform(platformId) }
                val platformProfiles = installedForPlatform.byLaunchPreference()
                val stored = card?.emulatorId?.takeIf { it.isNotBlank() }
                val platformPref =
                    platformEntity?.preferredEmulatorPackage?.takeIf { it.isNotBlank() }
                val resolved = EmulatorLaunchResolver.resolve(
                    platformId = platformId,
                    installedProfiles = installed,
                    platformProfiles = platformProfiles,
                    memoryCardEmulatorId = stored,
                    platformDefault = platformPref,
                ).getOrNull()
                val recommendedId = platformProfiles.firstOrNull()?.id

                PlatformAssignRow(
                    platformId = platformId,
                    platformName = platformName,
                    gameCount = platformGames.size,
                    overrideCount = platformGames.count { !it.emulatorPackage.isNullOrBlank() },
                    storedDefaultId = stored,
                    storedDefaultName = stored?.let { namesById[it] ?: namesByPackage[it] },
                    platformDefaultId = platformPref,
                    resolvedProfile = resolved?.profile,
                    resolvedCoreName = resolved?.coreName,
                    isMissingCore = resolved?.isMissingCore == true,
                    source = resolved?.source,
                    candidates = platformProfiles.map { profile ->
                        PlatformEmulatorCandidate(
                            profile = profile,
                            isRecommended = profile.id == recommendedId,
                            isDefault = resolved != null &&
                                (resolved.profile.id == profile.id ||
                                    resolved.profile.packageName == profile.packageName),
                        )
                    },
                )
            }
            .sortedBy { it.platformName.lowercase() }
    }
}
