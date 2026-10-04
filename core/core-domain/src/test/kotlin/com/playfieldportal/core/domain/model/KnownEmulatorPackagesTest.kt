package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownEmulatorPackagesTest {

    @Test
    fun `real emulator packages the old prefix list missed are tagged`() {
        listOf(
            "io.mgba",                          // was "com.mgba"
            "com.github.stenzek.duckstation",   // was "com.duckstation"
            "xyz.aethersx2.android",            // NetherSX2 — was "com.nethersx2"
            "com.armsx2",
            "com.nanodata.armsx",               // ARMSX1
            "com.armsx3",
            "com.izzy2lost.x1box",              // X1 BOX (xemu)
            "net.rpcsx",
            "org.scummvm.scummvm",
            "org.azahar_emu.azahar",
        ).forEach { assertTrue(KnownEmulatorPackages.isEmulator(it), it) }
    }

    @Test
    fun `Obtainium pack gaps are tagged under their published ids`() {
        // Obtainium Emulation Pack v7.18.0 (setup-wizard plan section 2.4).
        assertTrue(KnownEmulatorPackages.isEmulator("io.github.gopher64.gopher64"))
        assertTrue(KnownEmulatorPackages.isEmulator("emu.x360mobile.com"))
        assertTrue(KnownEmulatorPackages.isEmulator("com.winlator.ludashi"))
        assertTrue(KnownEmulatorPackages.isEmulator("com.winlator.vanilla"))
    }

    @Test
    fun `the unconfirmed X360 Mobile id is replaced, not kept beside the published one`() {
        assertFalse(KnownEmulatorPackages.isEmulator("emu.x360.mobile"))
    }

    @Test
    fun `held Obtainium entries stay untagged until their system is confirmed`() {
        assertFalse(KnownEmulatorPackages.isEmulator("io.navivani.swiff"))
    }

    @Test
    fun `XenDroid is tagged now that it is confirmed as an Xbox 360 emulator`() {
        assertTrue(KnownEmulatorPackages.isEmulator("xendroid.compose"))
        assertTrue(KnownEmulatorPackages.isEmulator("xendroid.compose.debug"))
    }

    @Test
    fun `families match their variants on a dot boundary only`() {
        assertTrue(KnownEmulatorPackages.isEmulator("com.retroarch"))
        assertTrue(KnownEmulatorPackages.isEmulator("com.retroarch.aarch64"))
        assertTrue(KnownEmulatorPackages.isEmulator("com.winlator.cmod"))
        assertTrue(KnownEmulatorPackages.isEmulator("xyz.aethersx2.tturnip"))
        // A shared stem is not a family member.
        assertFalse(KnownEmulatorPackages.isEmulator("com.retroarchive.reader"))
    }

    @Test
    fun `streaming clients and frontends are not emulators`() {
        listOf(
            "com.limelight",                    // Moonlight
            "com.limelight.noir",               // Artemis
            "com.magneticchen.daijishou",
            "org.es_de.frontend",
            "com.android.chrome",
        ).forEach { assertFalse(KnownEmulatorPackages.isEmulator(it), it) }
    }
}
