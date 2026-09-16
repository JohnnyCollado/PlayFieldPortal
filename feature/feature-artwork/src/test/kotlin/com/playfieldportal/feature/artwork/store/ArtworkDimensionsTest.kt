package com.playfieldportal.feature.artwork.store

import com.playfieldportal.core.data.database.seeder.PlatformSeeder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Artwork Dimension & Aspect Ratio Policy's suggested test list, plus the two properties the
 * policy states as rules rather than rows: source dimensions beat the preset, and the variable
 * platforms are marked as such.
 */
class ArtworkDimensionsTest {

    private val tolerance = 0.001f

    // ── The policy's suggested platform list ─────────────────────────────────

    @Test
    fun `the policy's suggested platforms carry their stated canvases`() {
        val expected = mapOf(
            "psx" to (600 to 600),
            "ps2" to (430 to 600),
            "psp" to (354 to 600),
            "psvita" to (468 to 600),
            "snes" to (600 to 438),
            "n64" to (600 to 438),
            "nds" to (540 to 600),
            "n3ds" to (540 to 600),
            "switch" to (366 to 600),
            "dreamcast" to (600 to 600),
            "x360" to (430 to 600),
            "windows" to (600 to 600),
            "android" to (600 to 600),
        )
        for ((platformId, canvas) in expected) {
            val (width, height) = canvas
            val resolved = ArtworkDimensions.boxArt(platformId)
            assertEquals(width, resolved.width, "$platformId width")
            assertEquals(height, resolved.height, "$platformId height")
            assertEquals(
                width.toFloat() / height.toFloat(),
                resolved.aspectRatio,
                tolerance,
                "$platformId ratio",
            )
        }
    }

    @Test
    fun `an unknown platform gets the generic DVD-style keep case, not a square`() {
        val unknown = ArtworkDimensions.boxArt("jaguar")
        assertEquals(430, unknown.width)
        assertEquals(600, unknown.height)
        assertEquals(0.72f, unknown.aspectRatio, 0.005f)
        assertEquals(ArtworkDimensions.GenericBoxArt, unknown)
        assertEquals(ArtworkDimensions.GenericBoxArt, ArtworkDimensions.boxArt(null))
        assertFalse(ArtworkDimensions.hasBoxArtPreset("jaguar"))
        assertFalse(ArtworkDimensions.hasBoxArtPreset(null))
    }

    // ── The corrections the policy calls out by name ─────────────────────────

    @Test
    fun `PSP and Vita no longer share a ratio`() {
        val psp = ArtworkDimensions.boxArt("psp").aspectRatio
        val vita = ArtworkDimensions.boxArt("psvita").aspectRatio
        assertNotEquals(psp, vita)
        assertEquals(354f / 600f, psp, tolerance)
        assertEquals(468f / 600f, vita, tolerance)
        assertTrue(vita > psp, "the Vita case is the wider of the two")
    }

    @Test
    fun `SNES and N64 render landscape, Switch narrow and vertical`() {
        assertTrue(ArtworkDimensions.boxArt("snes").aspectRatio > 1f)
        assertTrue(ArtworkDimensions.boxArt("n64").aspectRatio > 1f)
        assertTrue(ArtworkDimensions.boxArt("switch").aspectRatio < 0.7f)
    }

    @Test
    fun `the platforms that used to fall through now have their own rows`() {
        // Every one of these resolved to the generic 0.70 branch in the pre-6_5 table.
        val formerlyGeneric = listOf(
            "ps2", "ps3", "gc", "wii", "wiiu", "nes", "megadrive", "mastersystem", "gamegear",
            "sega32x", "atari2600", "atari5200", "atari7800", "atarilynx", "neogeo", "x360",
            "virtualboy", "mame", "cps1", "cps2", "cps3", "windows", "android", "c64", "pcengine",
        )
        for (platformId in formerlyGeneric) {
            assertTrue(
                ArtworkDimensions.hasBoxArtPreset(platformId),
                "$platformId should have its own row",
            )
        }
        // Several of those rows ARE 430x600, the same canvas the generic fallback carries. That is
        // the policy's value for them, not a fall-through — which is why this asserts on the row's
        // presence rather than on its dimensions differing.
        assertEquals(ArtworkDimensions.GenericBoxArt.aspectRatio, ArtworkDimensions.boxArt("ps2").aspectRatio)
    }

    // ── Aliases already in the tree ──────────────────────────────────────────

    @Test
    fun `every alias resolves to its canonical platform's canvas`() {
        val aliases = mapOf(
            "ps1" to "psx",
            "sfc" to "snes",
            "dc" to "dreamcast",
            "nx" to "switch",
            "ds" to "nds",
            "3ds" to "n3ds",
            "ngpc" to "ngp",
        )
        for ((alias, canonical) in aliases) {
            assertEquals(
                ArtworkDimensions.boxArt(canonical),
                ArtworkDimensions.boxArt(alias),
                "$alias should resolve like $canonical",
            )
        }
    }

    @Test
    fun `platform ids are matched without case or padding`() {
        assertEquals(ArtworkDimensions.boxArt("psx"), ArtworkDimensions.boxArt("PSX"))
        assertEquals(ArtworkDimensions.boxArt("psx"), ArtworkDimensions.boxArt("  psx  "))
        assertEquals(ArtworkDimensions.GenericBoxArt, ArtworkDimensions.boxArt("   "))
    }

    // ── Source dimensions beat the preset ────────────────────────────────────

    @Test
    fun `a known source ratio beats the platform preset`() {
        // The policy's own worked example: SNES preset is landscape, the downloaded scan is tall.
        assertEquals(
            500f / 700f,
            ArtworkDimensions.boxArtAspect("snes", sourceWidth = 500, sourceHeight = 700),
            tolerance,
        )
    }

    @Test
    fun `unusable source dimensions fall back to the preset`() {
        val preset = ArtworkDimensions.boxArt("ps2").aspectRatio
        assertEquals(preset, ArtworkDimensions.boxArtAspect("ps2", null, null), tolerance)
        assertEquals(preset, ArtworkDimensions.boxArtAspect("ps2", 430, null), tolerance)
        assertEquals(preset, ArtworkDimensions.boxArtAspect("ps2", 0, 600), tolerance)
        assertEquals(preset, ArtworkDimensions.boxArtAspect("ps2", 430, 0), tolerance)
        assertEquals(preset, ArtworkDimensions.boxArtAspect("ps2", -430, -600), tolerance)
    }

    // ── Variable platforms ───────────────────────────────────────────────────

    @Test
    fun `the policy's source-aware platforms are marked source-preferred`() {
        val sourceAware = listOf(
            "snes", "n64", "saturn", "segacd", "atarilynx", "pcengine", "c64",
            "mame", "cps1", "cps2", "cps3", "windows", "android",
        )
        for (platformId in sourceAware) {
            assertTrue(
                ArtworkDimensions.boxArt(platformId).sourceAspectPreferred,
                "$platformId should be source-preferred",
            )
        }
    }

    @Test
    fun `a fixed-packaging platform is not source-preferred`() {
        assertFalse(ArtworkDimensions.boxArt("ps2").sourceAspectPreferred)
        assertFalse(ArtworkDimensions.boxArt("psp").sourceAspectPreferred)
        assertFalse(ArtworkDimensions.boxArt("gc").sourceAspectPreferred)
    }

    // ── Coverage of the shipped platform list ────────────────────────────────

    @Test
    fun `every seeded platform has its own box-art canvas`() {
        val missing = PlatformSeeder.DEFAULT_PLATFORMS
            .map { it.id }
            .filterNot { ArtworkDimensions.hasBoxArtPreset(it) }
        assertTrue(missing.isEmpty(), "platforms with no box-art row: $missing")
    }
}
