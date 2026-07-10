package com.playfieldportal.core.data.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Verifies the ES-DE folder-name → platform mapping that drives single-scan autoload.
class PlatformFolderHintResolverTest {

    private val resolver = PlatformFolderHintResolver()

    @Test
    fun `ES-DE canonical folder names resolve to platform ids`() {
        val expected = mapOf(
            "gba" to "gba",
            "snes" to "snes",
            "nes" to "nes",
            "psx" to "psx",
            "n3ds" to "n3ds",
            "gc" to "gc",
            "megadrive" to "megadrive",
            "atari2600" to "atari2600",
            "pcengine" to "pcengine",
            "wonderswancolor" to "wonderswancolor",
        )
        expected.forEach { (folder, platform) ->
            assertEquals("folder '$folder'", platform, resolver.detectFromFolderName(folder))
        }
    }

    @Test
    fun `common aliases resolve to the same platform`() {
        assertEquals("psx", resolver.detectFromFolderName("ps1"))
        assertEquals("n3ds", resolver.detectFromFolderName("3ds"))
        assertEquals("megadrive", resolver.detectFromFolderName("genesis"))
        assertEquals("segacd", resolver.detectFromFolderName("megacd"))
        assertEquals("pcengine", resolver.detectFromFolderName("tg16"))
        assertEquals("mame", resolver.detectFromFolderName("arcade"))
        assertEquals("atarilynx", resolver.detectFromFolderName("lynx"))
    }

    @Test
    fun `folder matching is case-insensitive and trimmed`() {
        assertEquals("gba", resolver.detectFromFolderName("GBA"))
        assertEquals("snes", resolver.detectFromFolderName("  SNES  "))
    }

    @Test
    fun `newly added Microsoft and PC gaps resolve`() {
        assertEquals("x360", resolver.detectFromFolderName("xbox360"))
        assertEquals("x360", resolver.detectFromFolderName("x360"))
        assertEquals("windows", resolver.detectFromFolderName("windows"))
        assertEquals("windows", resolver.detectFromFolderName("winlator"))
    }

    @Test
    fun `esDeFolderName is identity except for ES-DE divergences`() {
        assertEquals("gba", resolver.esDeFolderName("gba"))
        assertEquals("snes", resolver.esDeFolderName("snes"))
        assertEquals("psx", resolver.esDeFolderName("psx"))
        // Xbox 360: our id is x360, ES-DE folder is xbox360.
        assertEquals("xbox360", resolver.esDeFolderName("x360"))
    }

    @Test
    fun `esDeFolderName round-trips back through detection`() {
        // Every created folder name must be re-detected as the same platform by autoload.
        listOf("gba", "snes", "psx", "n3ds", "megadrive", "x360").forEach { platformId ->
            val folder = resolver.esDeFolderName(platformId)
            assertEquals(platformId, resolver.detectFromFolderName(folder))
        }
    }

    @Test
    fun `unrecognised folders return null`() {
        assertNull(resolver.detectFromFolderName("savestates"))
        assertNull(resolver.detectFromFolderName("bios"))
        assertNull(resolver.detectFromFolderName(""))
    }
}
