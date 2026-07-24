package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.IntentType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural invariants for the curated catalog. Every rule here encodes an assumption the
 * detector or resolver relies on; a violation would produce a profile that cannot launch.
 */
class KnownEmulatorCatalogTest {

    // Mirrors PlatformSeeder.kt — update together. "symbian" is a documented exception:
    // EKA2L1 predates a seeded Symbian platform.
    private val seededPlatformIds = setOf(
        "psx", "ps2", "psp", "ps3", "psvita",
        "nes", "snes", "n64", "gb", "gbc", "gba", "nds", "n3ds", "gc", "wii", "wiiu",
        "switch", "virtualboy",
        "megadrive", "mastersystem", "gamegear", "saturn", "dreamcast", "segacd", "sega32x",
        "atari2600", "atari5200", "atari7800", "atarilynx",
        "pcengine", "neogeo", "ngp", "wonderswan", "wonderswancolor",
        "c64", "mame", "x360", "windows", "android",
    )
    private val platformExceptions = setOf("symbian")

    @Test
    fun `every package name belongs to exactly one entry`() {
        val duplicates = KnownEmulatorCatalog.entries
            .flatMap { entry -> entry.packageNames.map { it to entry.suggestedName } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "Packages claimed by multiple entries (detector ids would collide): $duplicates",
        )
    }

    @Test
    fun `component entries pin an activity`() {
        val missing = KnownEmulatorCatalog.entries
            .filter { it.intentType == IntentType.COMPONENT && it.activityClass == null }
            .map { it.suggestedName }
        assertTrue(missing.isEmpty(), "COMPONENT entries without activityClass: $missing")
    }

    @Test
    fun `component entries deliver the rom somehow`() {
        val silent = KnownEmulatorCatalog.entries
            .filter { it.intentType == IntentType.COMPONENT }
            .filterNot { entry ->
                entry.attachRomData || entry.intentExtras.values.any {
                    it.contains("{rom_uri}") || it.contains("{rom_path}")
                }
            }
            .map { it.suggestedName }
        assertTrue(
            silent.isEmpty(),
            "COMPONENT entries with no ROM extra and no attachRomData (game cannot boot): $silent",
        )
    }

    @Test
    fun `attachRomData is only used on component entries`() {
        val misused = KnownEmulatorCatalog.entries
            .filter { it.attachRomData && it.intentType != IntentType.COMPONENT }
            .map { it.suggestedName }
        assertTrue(misused.isEmpty(), "attachRomData outside COMPONENT entries: $misused")
    }

    @Test
    fun `activity classes are fully qualified`() {
        // ComponentName(pkg, cls) does not expand manifest-style ".Relative" names.
        val relative = KnownEmulatorCatalog.entries
            .mapNotNull { entry -> entry.activityClass?.let { entry.suggestedName to it } }
            .filter { (_, cls) -> !cls.contains('.') || cls.startsWith('.') }
        assertTrue(relative.isEmpty(), "Activity classes that are not FQCNs: $relative")
    }

    @Test
    fun `entries are non-empty and reference a seeded platform`() {
        for (entry in KnownEmulatorCatalog.entries) {
            assertTrue(entry.packageNames.isNotEmpty(), "${entry.suggestedName}: no packages")
            assertTrue(entry.platformIds.isNotEmpty(), "${entry.suggestedName}: no platforms")
            assertTrue(entry.suggestedName.isNotBlank(), "Entry with blank name: $entry")
            val reachable = entry.platformIds.any {
                it in seededPlatformIds || it in platformExceptions
            }
            assertTrue(
                reachable,
                "${entry.suggestedName}: none of ${entry.platformIds} is a seeded platform id " +
                    "(entry would never be offered for any library game)",
            )
        }
    }
}
