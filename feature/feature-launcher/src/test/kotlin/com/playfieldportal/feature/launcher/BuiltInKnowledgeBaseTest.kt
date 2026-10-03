package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecode
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDecoder
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbDocument
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbEmulator
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidation
import com.playfieldportal.core.domain.model.emulatorkb.EmulatorKbValidator
import com.playfieldportal.core.domain.model.emulatorkb.KbItem
import com.playfieldportal.core.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Invariants of the built-in KB asset (`emulator_kb/emulators.json`), the only emulator knowledge in
 * the APK. Adding an entry must not break these; the entry list itself is deliberately not pinned.
 * Per-entry structural rules live in [KnowledgeBaseInvariantsTest].
 */
@RunWith(RobolectricTestRunner::class)
class BuiltInKnowledgeBaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Seeded ids plus every alias expansion (AD-3).
    private val knownPlatformIds: Set<String> =
        PlatformSeeder.DEFAULT_PLATFORMS.map { it.id }.flatMap(::platformAliases).toSet()

    // Platform ids with no seeded platform that the old catalog carried (AD-3). The asset omits them.
    private val removedPlatformIds = setOf("naomi", "atomiswave", "symbian")

    // Merged bundled profiles: retired id -> the entry that absorbed it (AD-6).
    private val mergedLegacyIds = mapOf(
        "ppsspp" to listOf("ppsspp_gold"),
        "flycast" to listOf("flycast_gles2"),
        "m64pfz" to listOf("m64pfz_pro"),
    )

    // The two entries that carry a per-build launch override (AD-15).
    private val overrideEntryIds = setOf("citra", "sudachi")

    private fun decode(): EmulatorKbDocument {
        val text = context.assets.open("emulator_kb/emulators.json").bufferedReader().use { it.readText() }
        val decoded = EmulatorKbDecoder.decode(text)
        assertTrue("asset must decode: $decoded", decoded is EmulatorKbDecode.Decoded)
        val document = (decoded as EmulatorKbDecode.Decoded).document
        // A release may bump the built-in version; it only has to stay positive and labelled.
        assertTrue(document.version >= 1L)
        assertTrue(document.label.isNotBlank())
        assertTrue(document.platforms.isEmpty())
        return document
    }

    private fun load(): EmulatorKbValidation =
        EmulatorKbValidator.validate(decode(), knownPlatformIds, context.packageName)

    // The asset as written, before the validator drops platform ids this app does not know (AD-3).
    private fun rawEmulators(): List<EmulatorKbEmulator> = decode().emulators.map {
        assertTrue("every item must decode: $it", it is KbItem.Ok)
        (it as KbItem.Ok).value
    }

    @Test
    fun `asset validates with zero refusals`() {
        val result = load()
        assertTrue("refused emulators: ${result.refusedEmulators}", result.refusedEmulators.isEmpty())
        assertTrue("refused platforms: ${result.refusedPlatforms}", result.refusedPlatforms.isEmpty())
    }

    @Test
    fun `ids are unique and well formed`() {
        val ids = load().emulators.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        val bad = ids.filterNot { Regex("[a-z0-9_]{2,48}").matches(it) }
        assertTrue("malformed ids: $bad", bad.isEmpty())
    }

    @Test
    fun `every entry has a name, a package, no signer pin, and an override only where approved`() {
        for (e in rawEmulators()) {
            assertTrue("${e.id}: blank name", e.name.isNotBlank())
            assertTrue("${e.id}: no packages", e.packageNames.isNotEmpty())
            assertTrue("${e.id} signerSha256 stays empty", e.signerSha256.isEmpty())
            if (e.id !in overrideEntryIds) {
                assertTrue("${e.id} launchByPackage", e.launchByPackage.isEmpty())
            }
        }
    }

    @Test
    fun `legacy ids exist only on the merged entries`() {
        val actual = rawEmulators().filter { it.legacyIds.isNotEmpty() }.associate { it.id to it.legacyIds }
        assertEquals(mergedLegacyIds, actual)
    }

    @Test
    fun `platform ids the app cannot match and the omitted Symbian entry stay out of the asset`() {
        val kb = rawEmulators()
        assertTrue(kb.flatMap { it.platformIds }.none { it in removedPlatformIds })
        assertTrue("EKA2L1 (Symbian only) must stay omitted", kb.none { "com.github.eka2l1" in it.packageNames })
    }

    @Test
    fun `validation keeps every entry and only drops platform ids this app does not know`() {
        val raw = rawEmulators()
        val validated = load().emulators
        assertEquals(raw.size, validated.size)
        val dropped = raw.flatMap { it.platformIds }.filter { it !in knownPlatformIds }.toSet()
        // Recorded so a new unknown id in the asset is a deliberate decision, not an accident.
        assertEquals(setOf("nx", "fam", "arcade", "xbox360"), dropped)
    }

    @Test
    fun `entries reuse the bundled ids where a bundled profile describes the same emulator`() {
        val byPackage = load().emulators.associateBy { it.packageNames.first() }
        assertEquals("duckstation", byPackage["com.github.stenzek.duckstation"]?.id)
        assertEquals("dolphin", byPackage["org.dolphinemu.dolphinemu"]?.id)
        assertEquals("nethersx2", byPackage["xyz.aethersx2.android"]?.id)
        assertEquals("ppsspp", byPackage["org.ppsspp.ppssppgold"]?.id)
        assertNotNull(byPackage["org.sudachi.sudachi_emu"])
    }

    // The 36 retired bundled ids (8.1). winlator and gamehub are SHORTCUT profiles, not emulators: they
    // stay in bundled_profiles.json and out of the KB.
    private val retiredBundledIds = listOf(
        "ppsspp", "ppsspp_gold", "dolphin", "duckstation", "nethersx2", "armsx2", "armsx1", "armsx3",
        "melonds", "drastic", "azahar", "citra", "lime3ds", "azaharplus", "mgba", "myboy", "myboy_free",
        "myoldboy", "gbaemu", "gbcemu", "nesemu", "mdemu", "snes9xex", "pceemu", "neoemu", "swanemu",
        "lynxemu", "m64pfz", "m64pfz_pro", "mupen64plusae", "flycast", "flycast_gles2", "redream", "eden",
        "sudachi", "x1box",
    )

    private fun entry(id: String): EmulatorKbEmulator =
        rawEmulators().single { it.id == id }

    @Test
    fun `every retired bundled id appears exactly once as an entry id or a legacy id`() {
        assertEquals(36, retiredBundledIds.size)
        val kb = rawEmulators()
        val claimed = kb.map { it.id } + kb.flatMap { it.legacyIds }
        for (id in retiredBundledIds) {
            assertEquals("$id must appear exactly once", 1, claimed.count { it == id })
        }
        for (id in listOf("winlator", "gamehub")) {
            assertEquals("$id must stay out of the KB", 0, claimed.count { it == id })
        }
    }

    @Test
    fun `merged bundled profiles are legacy ids of their multi-package entry`() {
        assertTrue("ppsspp_gold" in entry("ppsspp").legacyIds)
        assertTrue("flycast_gles2" in entry("flycast").legacyIds)
        assertTrue("m64pfz_pro" in entry("m64pfz").legacyIds)
    }

    @Test
    fun `catalog recipe wins the settled drift rows`() {
        for (id in listOf("dolphin", "duckstation", "nethersx2")) {
            val extras = entry(id).launch.extras
            assertTrue("$id uses {rom_uri}: $extras", extras.values.all { it == "{rom_uri}" } && extras.isNotEmpty())
        }
        val eden = entry("eden").launch
        assertEquals(IntentType.COMPONENT, eden.intentType)
        assertEquals("android.nfc.action.TECH_DISCOVERED", eden.action)
        assertTrue(eden.attachRomData)
        val azaharPlus = entry("azaharplus").launch
        assertEquals(listOf("CLEAR_TASK", "CLEAR_TOP"), azaharPlus.flags)
        assertEquals(null, azaharPlus.mimeType)
    }

    @Test
    fun `citra is one entry that includes the second package and launches it with the bundled recipe`() {
        val citra = entry("citra")
        assertEquals(
            listOf("org.citra.citra_emu", "org.citra.citra_emu.canary", "org.citra_emu.citra"),
            citra.packageNames,
        )
        assertEquals(setOf("org.citra_emu.citra"), citra.launchByPackage.keys)
        val override = citra.launchByPackage.getValue("org.citra_emu.citra")
        assertEquals(IntentType.ACTION_VIEW, override.intentType)
        assertEquals("org.citra_emu.citra.activities.EmulationActivity", override.activityClass)
        assertEquals("application/octet-stream", override.mimeType)
        assertTrue(override.useSafUri)
        assertTrue(override.flags.isEmpty())
    }

    @Test
    fun `sudachi is one entry with three packages and an ACTION_VIEW override for org sudachi android`() {
        val sudachi = entry("sudachi")
        assertEquals(
            listOf("org.sudachi.sudachi_emu", "org.sudachi.sudachi_emu.ea", "org.sudachi.android"),
            sudachi.packageNames,
        )
        assertEquals(IntentType.COMPONENT, sudachi.launch.intentType)
        assertEquals(setOf("org.sudachi.android"), sudachi.launchByPackage.keys)
        val override = sudachi.launchByPackage.getValue("org.sudachi.android")
        assertEquals(IntentType.ACTION_VIEW, override.intentType)
        assertEquals(null, override.activityClass)
        assertEquals("application/octet-stream", override.mimeType)
        assertTrue(override.useSafUri)
    }
}
