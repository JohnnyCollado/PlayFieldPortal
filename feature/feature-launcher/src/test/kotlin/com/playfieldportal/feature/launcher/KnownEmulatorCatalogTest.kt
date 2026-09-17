package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.KnownEmulatorPackages
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
        "c64", "mame", "cps1", "cps2", "cps3", "xbox", "x360", "windows", "android",
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
    fun `every catalog package is tagged as an emulator in the app drawer`() {
        val untagged = KnownEmulatorCatalog.entries
            .flatMap { it.packageNames }
            .filterNot { KnownEmulatorPackages.isEmulator(it) }
        assertTrue(untagged.isEmpty(), "Add to KnownEmulatorPackages (no EMU badge): $untagged")
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
                } ||
                    // ID-launch entries (e.g. Vita3K) boot an installed title by {title_id} and
                    // deliver no ROM file by design.
                    (entry.intentExtras.values + entry.intentArrayExtras.values.flatten())
                        .any { it.contains("{title_id}") }
            }
            .map { it.suggestedName }
        assertTrue(
            silent.isEmpty(),
            "COMPONENT entries with no ROM extra, no attachRomData, and no {title_id} (game cannot boot): $silent",
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
    fun `ARMSX family boots via ACTION_VIEW content uri into the manifest activity`() {
        // Pinned against the shipped manifests (ARMSX1 0.1.3, ARMSX2, ARMSX3 0.9.7.3): the
        // exported activity has a scheme-only VIEW filter (content/file, no MIME) and no
        // extras. All three share the com.armsx2.* frontend classes despite their packages.
        val expected = mapOf(
            "com.nanodata.armsx" to ("com.armsx2.Main" to "psx"),
            "com.armsx2"         to ("com.armsx2.MainActivity" to "ps2"),
            "com.armsx3"         to ("com.armsx2.Main" to "ps3"),
        )
        for ((pkg, recipe) in expected) {
            val (activity, platform) = recipe
            val entry = KnownEmulatorCatalog.entries.singleOrNull { pkg in it.packageNames }
            assertTrue(entry != null, "No catalog entry for $pkg")
            assertTrue(entry.intentType == IntentType.ACTION_VIEW, "$pkg: expected ACTION_VIEW")
            assertTrue(entry.activityClass == activity, "$pkg: activity ${entry.activityClass}")
            assertTrue(entry.useSafUri, "$pkg: must hand over a content:// uri")
            assertTrue(entry.mimeType == null, "$pkg: filter declares no MIME type")
            assertTrue(platform in entry.platformIds, "$pkg: missing platform $platform")
        }
    }

    @Test
    fun `X1 BOX boots via ACTION_VIEW into its exported LauncherActivity`() {
        // Pinned against the X1 BOX 1.2.5 manifest: LauncherActivity is the only exported
        // activity (MainActivity is not), with a scheme-only VIEW filter (content/file).
        val entry = KnownEmulatorCatalog.entries.singleOrNull { "com.izzy2lost.x1box" in it.packageNames }
        assertTrue(entry != null, "No catalog entry for X1 BOX")
        assertTrue(entry.intentType == IntentType.ACTION_VIEW, "X1 BOX: expected ACTION_VIEW")
        assertTrue(
            entry.activityClass == "com.izzy2lost.x1box.LauncherActivity",
            "X1 BOX: activity ${entry.activityClass}",
        )
        assertTrue(entry.useSafUri, "X1 BOX: must hand over a content:// uri")
        assertTrue(entry.mimeType == null, "X1 BOX: filter declares no MIME type")
        assertTrue("xbox" in entry.platformIds, "X1 BOX: missing platform xbox")
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
