package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.feature.launcher.AutoCoreMemory
import com.playfieldportal.feature.launcher.EmulatorLaunchResolver
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.byLaunchPreference
import com.playfieldportal.feature.launcher.stabilizeCore
import com.playfieldportal.feature.launcher.supportsPlatform

/** Platforms whose "games" launch through packages/PC launchers, never an emulator profile. */
private val NON_EMULATOR_PLATFORMS = setOf("android", "windows")

private const val MEMORY_CARD_SUFFIX = " Memory Card"

/**
 * Derives each console's emulator assignment row — what its games resolve to, and the installed
 * candidates — exactly as a launch would. Shared by Settings ▸ Emulator Assignment and Initial
 * Setup's Emulators page, so the two can never disagree.
 */
class PlatformAssignRowsBuilder(
    private val profileRepository: EmulatorProfileRepository,
    private val autoCoreMemory: AutoCoreMemory,
) {
    suspend fun build(
        cards: List<MemoryCard>,
        platforms: List<com.playfieldportal.core.data.database.entity.PlatformEntity>,
        games: List<com.playfieldportal.core.domain.model.Game>,
        allProfiles: List<EmulatorProfile>,
    ): List<PlatformAssignRow> {
        // The console's remembered RetroArch core per platform, read once for the whole pass.
        val rememberedCores = autoCoreMemory.rememberedIds()
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
                // Same stabilization as a launch: the console's remembered core leads the core
                // tier, so this screen can never disagree with what actually launches.
                val platformProfiles =
                    installedForPlatform.byLaunchPreference().stabilizeCore(rememberedCores[platformId])
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
