package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first unmet condition standing between a fresh install and playing a game (B3 — onboarding).
 *
 * The wizard writes prefs, but the XMB needs to know what's *actually* still missing right now:
 * an empty-ROM-root install and a no-console-card install ask for different repairs. Ordered by
 * the user's critical path — a ROM root comes first (nothing can be scanned without one), then a
 * console Memory Card (scans need somewhere to land), then an emulator (games would launch into
 * nothing). [firstGap] names the one thing to fix next; [isPlayable] installs are done.
 */
enum class SetupGap(val repairScreenId: String, val message: String) {
    NO_ROM_ROOT(
        repairScreenId = "settings_library",
        message = "Add a ROM folder to start your library",
    ),
    NO_CONSOLES(
        repairScreenId = "settings_library",
        message = "Add a console Memory Card to scan into",
    ),
    NO_EMULATORS(
        repairScreenId = "settings_emulators",
        message = "Install an emulator to play your games",
    ),
    /** Everything needed is in place — the library can actually launch a game. */
    NONE(repairScreenId = "", message = ""),
}

/**
 * A snapshot of onboarding completeness. Pure data: [firstGap] derives from the three booleans so
 * tests pin the ordering without touching stores.
 */
data class SetupState(
    val hasRomRoot: Boolean = false,
    val hasConsoleCard: Boolean = false,
    val hasEmulator: Boolean = false,
) {
    val firstGap: SetupGap
        get() = when {
            !hasRomRoot      -> SetupGap.NO_ROM_ROOT
            !hasConsoleCard  -> SetupGap.NO_CONSOLES
            !hasEmulator     -> SetupGap.NO_EMULATORS
            else             -> SetupGap.NONE
        }

    /** True when nothing blocks playing a game. */
    val isPlayable: Boolean get() = firstGap == SetupGap.NONE
}

/**
 * Derives [SetupState] from the live stores (B3). The XMB watches this instead of the
 * write-only `library_setup_complete` pref flag, so empty surfaces can name the *actual*
 * first unmet step and deep-link to the screen that fixes it.
 */
@Singleton
class SetupStateProvider @Inject constructor(
    private val romRootRepository: RomRootRepository,
    private val memoryCardRepository: MemoryCardRepository,
    private val emulatorProfileRepository: EmulatorProfileRepository,
) {
    /** One-shot snapshot for imperative checks (item builders, menu decisions). */
    suspend fun current(): SetupState {
        val romRoots = runCatching { romRootRepository.roots.first() }.getOrDefault(emptyList())
        val cards = runCatching { memoryCardRepository.getAll() }.getOrDefault(emptyList())
        val emulators = runCatching { emulatorProfileRepository.getInstalledProfiles() }
            .getOrDefault(emptyList())
        return SetupState(
            hasRomRoot = romRoots.isNotEmpty(),
            hasConsoleCard = cards.isNotEmpty(),
            hasEmulator = emulators.isNotEmpty(),
        )
    }

    /** Reactive stream for surfaces that should re-render as setup progresses. */
    fun observe(): Flow<SetupState> = combine(
        romRootRepository.roots,
        memoryCardRepository.observeAll(),
        emulatorProfileRepository.profiles,
    ) { romRoots, cards, emulators ->
        SetupState(
            hasRomRoot = romRoots.isNotEmpty(),
            hasConsoleCard = cards.isNotEmpty(),
            hasEmulator = emulators.isNotEmpty(),
        )
    }
}
