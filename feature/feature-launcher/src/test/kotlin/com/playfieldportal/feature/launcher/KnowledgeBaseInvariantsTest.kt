package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.KnownEmulatorPackages
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbLaunch
import com.playfieldportal.core.domain.model.emulatorkb.KbItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Structural invariants for the built-in emulator knowledge base (`emulator_kb/emulators.json`), the
 * only emulator knowledge in the APK. Every rule here encodes an assumption the detector or resolver
 * relies on; a violation would produce a profile that cannot launch. They used to guard the
 * Kotlin catalog and now guard the asset, including each per-package launch override.
 */
@RunWith(RobolectricTestRunner::class)
class KnowledgeBaseInvariantsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Seeded ids plus every alias expansion (AD-3).
    private val knownPlatformIds: Set<String> =
        PlatformSeeder.DEFAULT_PLATFORMS.map { it.id }.flatMap(::platformAliases).toSet()

    // Ids with no seeded platform that the asset still carries; the validator drops them (AD-3).
    // Recorded so a new unknown id is a deliberate decision.
    private val knownDroppedPlatformIds = setOf("nx", "fam", "arcade", "xbox360")

    private val entries: List<EmulatorKbEmulator> by lazy {
        val text = context.assets.open("emulator_kb/emulators.json").bufferedReader().use { it.readText() }
        val decoded = EmulatorKbDecoder.decode(text) as EmulatorKbDecode.Decoded
        decoded.document.emulators.map {
            assertTrue("every item must decode: $it", it is KbItem.Ok)
            (it as KbItem.Ok).value
        }
    }

    // The base launch plus each per-package override, labelled for failure messages.
    private fun EmulatorKbEmulator.launches(): List<Pair<String, EmulatorKbLaunch>> =
        listOf(name to launch) + launchByPackage.map { (pkg, l) -> "$name [$pkg]" to l }

    private fun entry(pkg: String): EmulatorKbEmulator? = entries.singleOrNull { pkg in it.packageNames }

    @Test
    fun `every package name belongs to exactly one entry`() {
        val duplicates = entries
            .flatMap { entry -> entry.packageNames.map { it to entry.name } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertTrue(
            "Packages claimed by multiple entries (detector ids would collide): $duplicates",
            duplicates.isEmpty(),
        )
    }

    @Test
    fun `every KB package is tagged as an emulator in the app drawer`() {
        val untagged = entries
            .flatMap { it.packageNames }
            .filterNot { KnownEmulatorPackages.isEmulator(it) }
        assertTrue("Add to KnownEmulatorPackages (no EMU badge): $untagged", untagged.isEmpty())
    }

    @Test
    fun `X360 Mobile is recognised under its published package id`() {
        val packages = entries.flatMap { it.packageNames }
        assertTrue("emu.x360mobile.com" in packages)
        assertTrue("emu.x360.mobile" !in packages)
    }

    @Test
    fun `X360 Mobile launches through its frontend deep link`() {
        // A plain file open only adds the game to X360 Mobile's library; its own launch link boots it.
        val launch = entry("emu.x360mobile.com")!!.launch
        assertEquals(IntentType.COMPONENT, launch.intentType)
        assertEquals("emu.x360mobile.com.MainActivity", launch.activityClass)
        assertEquals(Intent.ACTION_VIEW, launch.action)
        assertEquals("x360mobile://launch?uri={rom_file_uri_encoded}", launch.dataUri)
        assertEquals(mapOf("x360mobile_frontend" to true), launch.boolExtras)
        // Its own home shortcuts start MainActivity in a cleared task, so a backgrounded instance
        // can't swallow the launch.
        assertEquals(listOf("CLEAR_TASK"), launch.flags)
    }

    @Test
    fun `XenDroid launches its emulator host with the game path in game_uri`() {
        val xenDroid = entry("xendroid.compose")!!
        assertEquals(xenDroid, entry("xendroid.compose.debug"))
        assertEquals(listOf("x360"), xenDroid.platformIds)
        with(xenDroid.launch) {
            assertEquals(IntentType.COMPONENT, intentType)
            assertEquals("xendroid.compose.EmulatorHostActivity", activityClass)
            assertEquals("xendroid.intent.action.xendroid", action)
            // A plain path, as XenDroid prefers (its frontend-integration doc) and as tested on device.
            assertEquals("{rom_path}", extras["game_uri"])
        }
    }

    @Test
    fun `component launches pin an activity`() {
        val missing = entries.flatMap { it.launches() }
            .filter { (_, l) -> l.intentType == IntentType.COMPONENT && l.activityClass == null }
            .map { it.first }
        assertTrue("COMPONENT launches without activityClass: $missing", missing.isEmpty())
    }

    @Test
    fun `component launches deliver the rom somehow`() {
        val silent = entries.flatMap { it.launches() }
            .filter { (_, l) -> l.intentType == IntentType.COMPONENT }
            .filterNot { (_, l) ->
                l.attachRomData || l.extras.values.any {
                    it.contains("{rom_uri}") || it.contains("{rom_path}")
                } ||
                    // Deep-link entries (e.g. X360 Mobile) carry the game inside their data URI.
                    l.dataUri?.contains("{rom_file_uri") == true ||
                    // ID-launch entries (e.g. Vita3K) boot an installed title by {title_id} and
                    // deliver no ROM file by design.
                    (l.extras.values + l.arrayExtras.values.flatten()).any { it.contains("{title_id}") }
            }
            .map { it.first }
        assertTrue(
            "COMPONENT launches with no ROM extra, no attachRomData, and no {title_id} (game cannot boot): $silent",
            silent.isEmpty(),
        )
    }

    @Test
    fun `attachRomData is only used on component launches`() {
        val misused = entries.flatMap { it.launches() }
            .filter { (_, l) -> l.attachRomData && l.intentType != IntentType.COMPONENT }
            .map { it.first }
        assertTrue("attachRomData outside COMPONENT launches: $misused", misused.isEmpty())
    }

    @Test
    fun `activity classes are fully qualified`() {
        // ComponentName(pkg, cls) does not expand manifest-style ".Relative" names.
        val relative = entries.flatMap { it.launches() }
            .mapNotNull { (label, l) -> l.activityClass?.let { label to it } }
            .filter { (_, cls) -> !cls.contains('.') || cls.startsWith('.') }
        assertTrue("Activity classes that are not FQCNs: $relative", relative.isEmpty())
    }

    @Test
    fun `launch overrides name one of the entry's own packages`() {
        val stray = entries.filter { e -> e.launchByPackage.keys.any { it !in e.packageNames } }.map { it.id }
        assertTrue("launchByPackage keys outside packageNames: $stray", stray.isEmpty())
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
            val entry = entry(pkg)
            assertTrue("No KB entry for $pkg", entry != null)
            val launch = entry!!.launch
            assertEquals("$pkg: expected ACTION_VIEW", IntentType.ACTION_VIEW, launch.intentType)
            assertEquals("$pkg: activity", activity, launch.activityClass)
            assertTrue("$pkg: must hand over a content:// uri", launch.useSafUri)
            assertEquals("$pkg: filter declares no MIME type", null, launch.mimeType)
            assertTrue("$pkg: missing platform $platform", platform in entry.platformIds)
        }
    }

    @Test
    fun `X1 BOX boots via ACTION_VIEW into its exported LauncherActivity`() {
        // Pinned against the X1 BOX 1.2.5 manifest: LauncherActivity is the only exported
        // activity (MainActivity is not), with a scheme-only VIEW filter (content/file).
        val entry = entry("com.izzy2lost.x1box")
        assertTrue("No KB entry for X1 BOX", entry != null)
        val launch = entry!!.launch
        assertEquals("X1 BOX: expected ACTION_VIEW", IntentType.ACTION_VIEW, launch.intentType)
        assertEquals("com.izzy2lost.x1box.LauncherActivity", launch.activityClass)
        assertTrue("X1 BOX: must hand over a content:// uri", launch.useSafUri)
        assertEquals("X1 BOX: filter declares no MIME type", null, launch.mimeType)
        assertTrue("X1 BOX: missing platform xbox", "xbox" in entry.platformIds)
    }

    @Test
    fun `entries are non-empty and reference a seeded platform`() {
        for (entry in entries) {
            assertTrue("${entry.name}: no packages", entry.packageNames.isNotEmpty())
            assertTrue("${entry.name}: no platforms", entry.platformIds.isNotEmpty())
            assertTrue("Entry with blank name: $entry", entry.name.isNotBlank())
            assertTrue(
                "${entry.name}: none of ${entry.platformIds} is a seeded platform id " +
                    "(entry would never be offered for any library game)",
                entry.platformIds.any { it in knownPlatformIds },
            )
        }
    }

    @Test
    fun `every platform id is seeded, an alias, or a recorded dropped id`() {
        val unexplained = entries.flatMap { e -> e.platformIds.map { e.name to it } }
            .filter { (_, id) -> id !in knownPlatformIds && id !in knownDroppedPlatformIds }
        assertTrue("Platform ids neither seeded, aliased nor recorded as dropped: $unexplained", unexplained.isEmpty())
    }
}
