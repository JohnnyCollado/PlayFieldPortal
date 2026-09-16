package com.playfieldportal.feature.artwork.store

import com.playfieldportal.core.domain.model.GameRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropProfilesTest {

    @Test
    fun `shipped kinds resolve to today's exact ratios`() {
        val registry = CropProfileRegistry.Default

        assertEquals(144f / 80f, registry.resolve(ArtworkKind.ICON, null, null).aspect)
        assertEquals(144f / 80f, registry.resolve(ArtworkKind.ICON1, null, null).aspect)
        assertEquals(920f / 430f, registry.resolve(ArtworkKind.HERO, null, null).aspect)
        assertEquals(16f / 9f, registry.resolve(ArtworkKind.BACKGROUND, null, null).aspect)
    }

    @Test
    fun `every other kind is Original Image`() {
        val registry = CropProfileRegistry.Default
        val everyOther = ArtworkKind.entries.filterNot {
            it == ArtworkKind.ICON || it == ArtworkKind.ICON1 || it == ArtworkKind.HERO || it == ArtworkKind.BACKGROUND
        }
        for (kind in everyOther) {
            val profile = registry.resolve(kind, null, null)
            assertNull(profile.aspect, "$kind should resolve to Original Image")
            assertEquals(CropProfileRegistry.ORIGINAL_KEY, profile.key)
        }
    }

    @Test
    fun `a platform row beats the kind default`() {
        val registry = CropProfileRegistry(
            mapOf(
                ArtworkKind.ICON.name to 144f / 80f,
                "${ArtworkKind.ICON.name}:psx" to 4f / 3f,
            )
        )
        val profile = registry.resolve(ArtworkKind.ICON, "psx", null)
        assertEquals(4f / 3f, profile.aspect)
        assertEquals("ICON:psx", profile.key)
    }

    @Test
    fun `a region row beats the platform row`() {
        val registry = CropProfileRegistry(
            mapOf(
                ArtworkKind.ICON.name to 144f / 80f,
                "${ArtworkKind.ICON.name}:psx" to 4f / 3f,
                "${ArtworkKind.ICON.name}:psx:NTSC_U" to 3f / 2f,
            )
        )
        val profile = registry.resolve(ArtworkKind.ICON, "psx", GameRegion.NTSC_U)
        assertEquals(3f / 2f, profile.aspect)
        assertEquals("ICON:psx:NTSC_U", profile.key)
    }

    @Test
    fun `unknown platform falls back to the kind default`() {
        val registry = CropProfileRegistry(
            mapOf(
                ArtworkKind.ICON.name to 144f / 80f,
                "${ArtworkKind.ICON.name}:psx" to 4f / 3f,
            )
        )
        val profile = registry.resolve(ArtworkKind.ICON, "saturn", null)
        assertEquals(144f / 80f, profile.aspect)
        assertEquals(ArtworkKind.ICON.name, profile.key)
    }

    @Test
    fun `null region with a known platform falls back to the platform row`() {
        val registry = CropProfileRegistry(
            mapOf(
                ArtworkKind.ICON.name to 144f / 80f,
                "${ArtworkKind.ICON.name}:psx" to 4f / 3f,
                "${ArtworkKind.ICON.name}:psx:NTSC_U" to 3f / 2f,
            )
        )
        val profile = registry.resolve(ArtworkKind.ICON, "psx", null)
        assertEquals(4f / 3f, profile.aspect)
        assertEquals("ICON:psx", profile.key)
    }

    @Test
    fun `an empty table falls back to Original Image without throwing`() {
        val registry = CropProfileRegistry(emptyMap())
        val profile = registry.resolve(ArtworkKind.HERO, "unknown-platform", GameRegion.PAL)
        assertNull(profile.aspect)
        assertEquals(CropProfileRegistry.ORIGINAL_KEY, profile.key)
    }

    @Test
    fun `every resolution returns a non-empty key`() {
        val registries = listOf(
            CropProfileRegistry.Default,
            CropProfileRegistry(
                mapOf(
                    ArtworkKind.ICON.name to 144f / 80f,
                    "${ArtworkKind.ICON.name}:psx" to 4f / 3f,
                    "${ArtworkKind.ICON.name}:psx:NTSC_U" to 3f / 2f,
                )
            ),
        )
        val platforms = listOf(null, "psx", "unknown")
        val regions = listOf(null, GameRegion.NTSC_U, GameRegion.PAL, GameRegion.NTSC_J)

        for (registry in registries) {
            for (kind in ArtworkKind.entries) {
                for (platformId in platforms) {
                    for (region in regions) {
                        val profile = registry.resolve(kind, platformId, region)
                        assertTrue(profile.key.isNotEmpty(), "resolve($kind, $platformId, $region) returned an empty key")
                    }
                }
            }
        }
    }
}
