package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the emulator resolution ladder — the load-bearing precedence every game launch and the
 * Game Detail attribution line walk. Do NOT reorder or extend the ladder without re-pinning these:
 *
 *   1. per-game override → 2. memory-card emulator → 3. platform default → 4. first valid.
 */
class EmulatorLaunchResolverTest {

    private fun profile(
        id: String,
        packageName: String = id,
        platforms: List<String> = listOf("psx"),
        intentType: IntentType = IntentType.ACTION_VIEW,
        coreMap: Map<String, String> = emptyMap(),
        available: Boolean = true,
        autoSource: String? = null,
    ) = EmulatorProfile(
        id                   = id,
        name                 = id,
        packageName          = packageName,
        intentType           = intentType,
        supportedPlatformIds = platforms,
        coreMap              = coreMap,
        isAvailable          = available,
        autoSource           = autoSource,
    )

    private val duckstation = profile("duckstation", packageName = "com.github.stenzek.duckstation")
    // A RetroArch entry listed FIRST on purpose: the automatic fallback must never pick it over a
    // standalone, and platform-default resolution must still honor the configured id.
    private val retroarch = profile(
        id = "retroarch",
        packageName = "com.retroarch.aarch64",
        intentType = IntentType.COMPONENT,
    )

    // ── Precedence ─────────────────────────────────────────────────────────

    @Test
    fun `per-game override wins over memory card and platform default and reports its source`() {
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(retroarch, duckstation),
            platformProfiles = listOf(retroarch, duckstation),
            perGameOverride = "duckstation",
            memoryCardEmulatorId = "retroarch",
            platformDefault = "retroarch",
        ).getOrThrow()

        assertEquals("duckstation", resolved.profile.id)
        assertEquals(LaunchSource.PER_GAME_OVERRIDE, resolved.source)
    }

    @Test
    fun `memory card emulator wins when the game has no override`() {
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(retroarch, duckstation),
            platformProfiles = listOf(retroarch, duckstation),
            perGameOverride = null,
            memoryCardEmulatorId = "duckstation",
            platformDefault = "retroarch",
        ).getOrThrow()

        assertEquals("duckstation", resolved.profile.id)
        assertEquals(LaunchSource.MEMORY_CARD, resolved.source)
    }

    @Test
    fun `platform default wins even when it is not the first valid emulator`() {
        // The automatic pick WOULD be retroarch (listed first), but an explicit platform default
        // must win over the catalog fallback — including when the default is a later entry.
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(retroarch, duckstation),
            platformProfiles = listOf(retroarch, duckstation),
            perGameOverride = null,
            memoryCardEmulatorId = null,
            platformDefault = "duckstation",
        ).getOrThrow()

        assertEquals("duckstation", resolved.profile.id)
        assertEquals(LaunchSource.PLATFORM_DEFAULT, resolved.source)
    }

    @Test
    fun `clearing an override falls back to the platform default not the catalog pick`() {
        // A game whose override was just cleared resolves exactly like a game that never had one:
        // platform default first, and only then the first-valid catalog candidate.
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(retroarch, duckstation),
            platformProfiles = listOf(retroarch, duckstation),
            perGameOverride = null,
            memoryCardEmulatorId = null,
            platformDefault = "duckstation",
        ).getOrThrow()

        assertEquals("duckstation", resolved.profile.id)
        assertEquals(LaunchSource.PLATFORM_DEFAULT, resolved.source)
        assertTrue(resolved.source != LaunchSource.CATALOG_DEFAULT)
    }

    @Test
    fun `first valid platform emulator is the catalog fallback when nothing is configured`() {
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(duckstation),
            platformProfiles = listOf(duckstation),
        ).getOrThrow()

        assertEquals("duckstation", resolved.profile.id)
        assertEquals(LaunchSource.CATALOG_DEFAULT, resolved.source)
    }

    @Test
    fun `catalog fallback prefers a standalone over retroarch through profile order`() {
        // Mirrors the real caller: platformProfiles come pre-ordered by byLaunchPreference, so a
        // standalone wins the automatic pick even when RetroArch was detected first.
        val installed = listOf(retroarch, duckstation)
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = installed,
            platformProfiles = installed.byLaunchPreference(),
        ).getOrThrow()

        assertEquals("duckstation", resolved.profile.id)
        assertEquals(LaunchSource.CATALOG_DEFAULT, resolved.source)
    }

    @Test
    fun `unavailable profiles never win the catalog fallback`() {
        val uninstalled = profile("duckstation", packageName = "com.github.stenzek.duckstation", available = false)
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(uninstalled),
            platformProfiles = emptyList(),   // caller excludes unavailable profiles up front
        ).exceptionOrNull()

        assertTrue(resolved != null, "An unavailable-only platform must not resolve")
        assertTrue(resolved!!.message!!.contains("No emulator configured for PSX"))
    }

    // ── Failures ───────────────────────────────────────────────────────────

    @Test
    fun `configured emulator that is not installed fails with a usable message`() {
        val failure = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(duckstation),
            platformProfiles = listOf(duckstation),
            perGameOverride = "ghost_emulator",
        ).exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("per-game override emulator is not installed or available: ghost_emulator"))
    }

    @Test
    fun `configured emulator that cannot run the platform is rejected`() {
        val dsOnly = profile("melonds", packageName = "me.magnum.melonds", platforms = listOf("nds"))
        val failure = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(dsOnly),
            platformProfiles = emptyList(),
            perGameOverride = "melonds",
        ).exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("melonds is not configured for PSX"))
    }

    @Test
    fun `no emulator anywhere fails with guidance`() {
        val failure = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = emptyList(),
            platformProfiles = emptyList(),
        ).exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("No emulator configured for PSX"))
    }

    // ── Core visibility ────────────────────────────────────────────────────

    @Test
    fun `retroarch core path is normalized to the profile package and given a curated label`() {
        val ra = profile(
            id = "retroarch",
            packageName = "com.retroarch",
            intentType = IntentType.COMPONENT,
            // Core stored under the alias "ps1" while the game's canonical id is "psx".
            coreMap = mapOf("ps1" to "/data/data/com.retroarch.aarch64/cores/mednafen_psx_hw_libretro_android.so"),
        )
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(ra),
            platformProfiles = listOf(ra),
        ).getOrThrow()

        assertEquals(
            "/data/data/com.retroarch/cores/mednafen_psx_hw_libretro_android.so",
            resolved.corePath,
            "Alias lookup must find the core and rewrite the canonical dir to the profile's package",
        )
        assertEquals("Beetle PSX HW", resolved.coreName)
        assertFalse(resolved.isMissingCore)
    }

    @Test
    fun `standalone profiles carry no core and are never missing-core`() {
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(duckstation),
            platformProfiles = listOf(duckstation),
        ).getOrThrow()

        assertNull(resolved.corePath)
        assertNull(resolved.coreName)
        assertFalse(resolved.isMissingCore)
    }

    @Test
    fun `retroarch component profile with no mapped core reports missing core`() {
        val ra = profile(
            id = "retroarch",
            packageName = "com.retroarch.aarch64",
            intentType = IntentType.COMPONENT,
            // Cores mapped for another system — none for psx.
            coreMap = mapOf("snes" to "/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so"),
        )
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(ra),
            platformProfiles = listOf(ra),
        ).getOrThrow()

        assertNull(resolved.corePath)
        assertTrue(resolved.isMissingCore)
    }

    @Test
    fun `resolve always returns the profile object from the installed pool`() {
        val resolved = EmulatorLaunchResolver.resolve(
            platformId = "psx",
            installedProfiles = listOf(retroarch, duckstation),
            platformProfiles = listOf(duckstation),
            perGameOverride = "duckstation",
        ).getOrThrow()

        // Reference identity — callers (Game Detail) log and launch the SAME profile the ladder
        // resolved, so a resolver copy could never silently launch a stale config.
        assertTrue(resolved.profile === duckstation, "Resolver must return the pool instance")
    }
}
