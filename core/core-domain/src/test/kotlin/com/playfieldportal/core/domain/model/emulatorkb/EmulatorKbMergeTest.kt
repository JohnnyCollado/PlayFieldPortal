package com.playfieldportal.core.domain.model.emulatorkb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorKbMergeTest {

    private fun emu(
        id: String,
        vararg packages: String,
        name: String = id,
        legacyIds: List<String> = emptyList(),
    ) = EmulatorKbEmulator(
        id = id,
        name = name,
        packageNames = packages.toList(),
        legacyIds = legacyIds,
        platformIds = listOf("psx"),
    )

    private fun layer(
        vararg emulators: EmulatorKbEmulator,
        platforms: List<EmulatorKbPlatform> = emptyList(),
        version: Long = 1,
    ) = EmulatorKbLayer(emulators.toList(), platforms, version)

    private fun user(fileId: String, vararg emulators: EmulatorKbEmulator, platforms: List<EmulatorKbPlatform> = emptyList()) =
        UserKbLayer(fileId, EmulatorKbLayer(emulators.toList(), platforms, 0))

    @Test
    fun `built-in alone is the effective set with BUILT_IN source`() {
        val result = EmulatorKbMerge.merge(layer(emu("a", "p.a"), emu("b", "p.b")), null, emptyList())
        assertEquals(listOf("a", "b"), result.emulators.map { it.emulator.id })
        assertTrue(result.emulators.all { it.source == KbSource.BuiltIn && it.replaced == null })
    }

    @Test
    fun `official entry overrides a built-in entry with the same id`() {
        val builtIn = layer(emu("a", "p.a", name = "Old"), version = 1)
        val official = layer(emu("a", "p.a", name = "New"), version = 2)
        val result = EmulatorKbMerge.merge(builtIn, official, emptyList())
        val entry = result.emulators.single()
        assertEquals("New", entry.emulator.name)
        assertEquals(KbSource.Official, entry.source)
        assertEquals(KbSource.BuiltIn, entry.replaced)
        assertTrue(result.officialApplied)
    }

    @Test
    fun `user entry matching an official one by package overrides it`() {
        val builtIn = layer(emu("a", "p.a"), version = 1)
        val official = layer(emu("x", "p.x"), version = 2)
        val result = EmulatorKbMerge.merge(builtIn, official, listOf(user("f1", emu("mine", "p.x"))))
        val mine = result.emulators.single { it.emulator.id == "mine" }
        assertEquals(KbSource.User("f1"), mine.source)
        assertEquals(KbSource.Official, mine.replaced)
        assertEquals(setOf("a", "mine"), result.emulators.map { it.emulator.id }.toSet())
    }

    @Test
    fun `override matches by legacy id`() {
        val builtIn = layer(emu("old_id", "p.a"))
        val official = layer(emu("new_id", "p.z", legacyIds = listOf("old_id")), version = 2)
        val result = EmulatorKbMerge.merge(builtIn, official, emptyList())
        val entry = result.emulators.single()
        assertEquals("new_id", entry.emulator.id)
        assertEquals(KbSource.BuiltIn, entry.replaced)
    }

    @Test
    fun `later user file wins over an earlier one`() {
        val result = EmulatorKbMerge.merge(
            layer(),
            null,
            listOf(user("f1", emu("a", "p.a", name = "First")), user("f2", emu("a", "p.a", name = "Second"))),
        )
        val entry = result.emulators.single()
        assertEquals("Second", entry.emulator.name)
        assertEquals(KbSource.User("f2"), entry.source)
        assertEquals(KbSource.User("f1"), entry.replaced)
    }

    @Test
    fun `stale or equal official version is ignored`() {
        val builtIn = layer(emu("a", "p.a", name = "Built"), version = 5)
        for (v in listOf(5L, 4L)) {
            val official = layer(emu("a", "p.a", name = "Stale"), emu("extra", "p.e"), version = v)
            val result = EmulatorKbMerge.merge(builtIn, official, emptyList())
            assertEquals("Built", result.emulators.single().emulator.name)
            assertFalse(result.officialApplied)
        }
    }

    @Test
    fun `package claim strips the package from a lower entry that survives`() {
        val builtIn = layer(emu("a", "p.a1", "p.a2"))
        val result = EmulatorKbMerge.merge(builtIn, null, listOf(user("f1", emu("mine", "p.a2"))))
        val a = result.emulators.single { it.emulator.id == "a" }
        assertEquals(listOf("p.a1"), a.emulator.packageNames)
        assertNull(a.replaced)
        assertEquals(KbSource.BuiltIn, a.source)
    }

    @Test
    fun `package strip also drops launch overrides for the stripped package`() {
        val withOverride = emu("a", "p.a1", "p.a2").copy(
            launchByPackage = mapOf("p.a1" to EmulatorKbLaunch(), "p.a2" to EmulatorKbLaunch(useSafUri = true)),
        )
        val result = EmulatorKbMerge.merge(layer(withOverride), null, listOf(user("f1", emu("mine", "p.a2"))))
        assertEquals(setOf("p.a1"), result.emulators.single { it.emulator.id == "a" }.emulator.launchByPackage.keys)
    }

    @Test
    fun `package strip removes a lower entry left with no packages`() {
        val builtIn = layer(emu("a", "p.a"), emu("b", "p.b"))
        val result = EmulatorKbMerge.merge(builtIn, null, listOf(user("f1", emu("mine", "p.a"))))
        assertEquals(setOf("b", "mine"), result.emulators.map { it.emulator.id }.toSet())
        assertEquals(KbSource.BuiltIn, result.emulators.single { it.emulator.id == "mine" }.replaced)
    }

    @Test
    fun `entries within one layer do not strip each other`() {
        val result = EmulatorKbMerge.merge(layer(emu("a", "p.a"), emu("b", "p.b")), null, emptyList())
        assertEquals(2, result.emulators.size)
    }

    @Test
    fun `platform items follow the same precedence`() {
        val builtIn = layer(platforms = listOf(EmulatorKbPlatform("psx", listOf("bin")), EmulatorKbPlatform("ps2", listOf("iso"))), version = 1)
        val official = layer(platforms = listOf(EmulatorKbPlatform("psx", listOf("bin", "chd"))), version = 2)
        val users = listOf(
            user("f1", platforms = listOf(EmulatorKbPlatform("psx", listOf("pbp")))),
            user("f2", platforms = listOf(EmulatorKbPlatform("psx", listOf("cue")))),
        )
        val result = EmulatorKbMerge.merge(builtIn, official, users)
        val psx = result.platforms.single { it.platform.id == "psx" }
        assertEquals(listOf("cue"), psx.platform.romExtensions)
        assertEquals(KbSource.User("f2"), psx.source)
        assertEquals(KbSource.User("f1"), psx.replaced)
        val ps2 = result.platforms.single { it.platform.id == "ps2" }
        assertEquals(KbSource.BuiltIn, ps2.source)
        assertNull(ps2.replaced)
    }

    @Test
    fun `stale official platform items are ignored too`() {
        val builtIn = layer(platforms = listOf(EmulatorKbPlatform("psx", listOf("bin"))), version = 3)
        val official = layer(platforms = listOf(EmulatorKbPlatform("psx", listOf("chd"))), version = 3)
        val result = EmulatorKbMerge.merge(builtIn, official, emptyList())
        assertEquals(listOf("bin"), result.platforms.single().platform.romExtensions)
    }

    @Test
    fun `a user entry listing another entry id in legacyIds does not remove it`() {
        val builtIn = layer(emu("a", "p.a"), emu("b", "p.b"), version = 1)
        val result = EmulatorKbMerge.merge(
            builtIn,
            null,
            listOf(user("f1", emu("mine", "p.mine", legacyIds = listOf("a")))),
        )
        assertEquals(setOf("a", "b", "mine"), result.emulators.map { it.emulator.id }.toSet())
    }

    @Test
    fun `a lower entry listing a user entry id in legacyIds does not lose to it`() {
        val builtIn = layer(emu("a", "p.a", legacyIds = listOf("mine")), version = 1)
        val result = EmulatorKbMerge.merge(builtIn, null, listOf(user("f1", emu("mine", "p.mine"))))
        assertEquals(setOf("a", "mine"), result.emulators.map { it.emulator.id }.toSet())
    }
}
