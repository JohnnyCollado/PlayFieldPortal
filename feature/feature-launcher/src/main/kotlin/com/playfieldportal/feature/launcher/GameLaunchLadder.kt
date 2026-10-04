package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import javax.inject.Inject

/**
 * Which emulator (and RetroArch core) launches a game: gathers the ladder's inputs from their
 * stores — the per-game choice, the console's assigned default (its memory card), the platform
 * record's default, and the installed pool in launch preference — and hands them to
 * [EmulatorLaunchResolver], which owns the precedence.
 *
 * Every game launch path asks here, Game Detail and the XMB's direct launch alike, so a console
 * default assigned in Settings decides both the same way. (Direct launch once skipped the ladder
 * and took the first installed emulator, whatever the console's card said.)
 */
class GameLaunchLadder @Inject constructor(
    private val profiles: EmulatorProfileRepository,
    private val memoryCards: MemoryCardRepository,
    private val platforms: PlatformDao,
    private val autoCoreMemory: AutoCoreMemory,
) {

    /** Resolves [game]; [platform] saves a lookup when the caller already holds its record. */
    suspend fun resolve(game: Game, platform: PlatformEntity? = null): Result<ResolvedLaunch> {
        val platformId = game.platformId
        val installed = profiles.getInstalledProfiles()
        // Ordered so the automatic fallback picks a standalone emulator over a RetroArch core when
        // both support the console. Unavailable profiles (e.g. a RetroArch core the SAF link
        // detected as not installed) are excluded so the fallback never lands on one. The console's
        // remembered RetroArch core is then lifted to the front of the core tier, so the core (and
        // its RetroArch configs) stays stable even as the detected core set changes.
        val platformProfiles =
            installed.filter { it.isAvailable && it.supportsPlatform(platformId) }
                .byLaunchPreference()
                .stabilizeCore(autoCoreMemory.rememberedProfileId(platformId))
        return EmulatorLaunchResolver.resolve(
            platformId           = platformId,
            installedProfiles    = installed,
            platformProfiles     = platformProfiles,
            perGameOverride      = game.emulatorPackage?.takeIf { it.isNotBlank() },
            memoryCardEmulatorId = memoryCards.getById(platformId)?.emulatorId?.takeIf { it.isNotBlank() },
            platformDefault      = (platform ?: platforms.getById(platformId))
                ?.preferredEmulatorPackage?.takeIf { it.isNotBlank() },
        )
    }
}
