package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType

/**
 * Which level of the configuration ladder decided a game's emulator.
 *
 * These are the four decisions that can hand a game its launch profile, in precedence order.
 * Keeping them an enum (instead of the free-text strings the ladder used to return) means the
 * caller can attribute the choice on screen and log it without string matching.
 */
enum class LaunchSource(
    /** Short, user-facing attribution (shown under the emulator on Game Detail). */
    val label: String,
    /** Lowercase phrase used when a configured emulator can't be resolved (message continuity). */
    val configuredErrorPhrase: String?,
) {
    /** The game itself is pinned to this emulator (Options ▸ Emulator / the XMB Change Emulator menu). */
    PER_GAME_OVERRIDE("Per-game override", "per-game override"),

    /** The console's Memory Card entry picks this emulator. */
    MEMORY_CARD("Memory card default", "memory card emulator"),

    /** The platform record's preferred emulator (set per system). */
    PLATFORM_DEFAULT("Platform default", "platform default"),

    /** No explicit choice anywhere — the first valid installed emulator was picked automatically. */
    CATALOG_DEFAULT("Recommended", null),
}

/**
 * The emulator a game will actually launch with, plus why: the winning [profile], the ladder level
 * ([source]) that decided it, and the RetroArch core path the profile maps for the resolved
 * platform (null when the profile has no core mapping — e.g. a standalone emulator).
 */
data class ResolvedLaunch(
    val profile: EmulatorProfile,
    val source: LaunchSource,
    /** Normalized RetroArch core path mapped for the resolved platform; null when none is mapped. */
    val corePath: String? = null,
) {
    /** Human label of [corePath]'s core file when one is mapped (e.g. "Beetle PSX HW"). */
    val coreName: String?
        get() = corePath?.let { RetroArchCoreScanner.labelForPath(it) }

    /**
     * True when the winning profile is a core-launching RetroArch setup with no core mapped for the
     * platform — the exact condition [EmulatorIntentResolver.validateBeforeLaunch] refuses at launch.
     * Surfacing it here lets screens mark such a setup incomplete before the user hits Play.
     */
    val isMissingCore: Boolean
        get() = corePath == null &&
            profile.intentType == IntentType.COMPONENT &&
            profile.coreMap.isNotEmpty()
}

/**
 * Walks the emulator configuration ladder for a game and reports the winning profile AND the level
 * that decided it — the explainability half of B4 ("emulator and core assignment clarity").
 *
 * Pure by design: every input (the installed profiles, the platform-ordered candidate list, and the
 * three configured ids from game / memory card / platform) is passed in, so this object has no
 * Android, database, or Hilt dependency and its precedence is unit-testable in isolation. Callers
 * (Game Detail, and later the per-platform assignment screen) gather those inputs and delegate here,
 * keeping one copy of the load-bearing ladder.
 *
 * Precedence, pinned by EmulatorLaunchResolverTest — do not reorder without re-pinning them:
 *   1. per-game override  (game.emulatorPackage)
 *   2. memory-card emulator (the console library card's emulatorId)
 *   3. platform default   (PlatformEntity.preferredEmulatorPackage)
 *   4. first valid installed emulator for the platform (standalone before RetroArch)
 */
object EmulatorLaunchResolver {

    /**
     * @param platformId the game's canonical platform id (e.g. "psx").
     * @param installedProfiles every installed profile, in catalog/detection order; the pool a
     *   configured id may resolve to. Unavailable entries are filtered out here too — a stored
     *   choice must clear the same bar as an automatic one (see [resolve]'s availability note).
     * @param platformProfiles the profiles that can run [platformId], already filtered to
     *   available+installed and ordered by [byLaunchPreference] — the automatic fallback pool.
     * @param perGameOverride the game's stored override id/package, or null.
     * @param memoryCardEmulatorId the console library card's stored emulator id/package, or null.
     * @param platformDefault the platform record's preferred id/package, or null.
     * @return [ResolvedLaunch] with the winning profile + its mapped core, or a [Result.failure]
     *   with a user-readable message mirroring the historic launch-block texts.
     */
    fun resolve(
        platformId: String,
        installedProfiles: List<EmulatorProfile>,
        platformProfiles: List<EmulatorProfile>,
        perGameOverride: String? = null,
        memoryCardEmulatorId: String? = null,
        platformDefault: String? = null,
    ): Result<ResolvedLaunch> {

        // Why a configured choice is explained, not silently replaced: the ladder's whole job is
        // to be attributable, so a dead platform default surfaces as "this is broken, here's why"
        // and routes to recovery, rather than quietly launching something the user did not pick.
        fun unresolvableMessage(configuredIdOrPackage: String, source: LaunchSource): String {
            val shelved = installedProfiles.firstOrNull {
                it.id == configuredIdOrPackage || it.packageName == configuredIdOrPackage
            }
            return when {
                shelved == null ->
                    "The ${source.configuredErrorPhrase} emulator is not installed or " +
                        "available: $configuredIdOrPackage"
                shelved.isRetroArchProfile() ->
                    "${shelved.name} is set as the ${source.label.lowercase()}, but that core is " +
                        "not installed in RetroArch. Install it, or choose another emulator."
                else ->
                    "${shelved.name} is set as the ${source.label.lowercase()}, but is no longer " +
                        "available. Choose another emulator."
            }
        }

        fun resolveConfigured(
            configuredIdOrPackage: String,
            source: LaunchSource,
        ): Result<ResolvedLaunch> {
            // Availability is checked on the configured rungs too, not just the automatic pool.
            // It was once filtered only into [platformProfiles], so a platform default or memory
            // card pointing at an unavailable RetroArch core still resolved and still launched —
            // the profile was excluded from the fallback list it would never have reached anyway,
            // while the stored id that actually decided the launch sailed past unchecked.
            val available = installedProfiles.filter { it.isAvailable }
            val profile = available.firstOrNull { it.id == configuredIdOrPackage }
                ?: available.firstOrNull {
                    it.packageName == configuredIdOrPackage && it.supportsPlatform(platformId)
                }
                ?: available.firstOrNull { it.packageName == configuredIdOrPackage }
                ?: return Result.failure(
                    IllegalStateException(unresolvableMessage(configuredIdOrPackage, source))
                )

            if (!profile.supportsPlatform(platformId)) {
                return Result.failure(
                    IllegalStateException(
                        "${profile.name} is not configured for ${platformId.uppercase()}"
                    )
                )
            }

            return Result.success(
                ResolvedLaunch(profile = profile, source = source, corePath = profile.corePathFor(platformId))
            )
        }

        perGameOverride?.let { return resolveConfigured(it, LaunchSource.PER_GAME_OVERRIDE) }
        memoryCardEmulatorId?.let { return resolveConfigured(it, LaunchSource.MEMORY_CARD) }
        platformDefault?.let { return resolveConfigured(it, LaunchSource.PLATFORM_DEFAULT) }

        platformProfiles.firstOrNull()?.let {
            return Result.success(
                ResolvedLaunch(profile = it, source = LaunchSource.CATALOG_DEFAULT, corePath = it.corePathFor(platformId))
            )
        }

        return Result.failure(
            IllegalStateException(
                "No emulator configured for ${platformId.uppercase()}. Choose an emulator for " +
                    "this game or set a platform default."
            )
        )
    }
}
