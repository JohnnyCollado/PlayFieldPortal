package com.playfieldportal.studio.preview

import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Studio previews every slot the launcher customizes; a slot added to [CustomizableIcons]
 * without Studio art would silently render [StudioIconSet.defaultPainter]'s play-arrow stand-in.
 * This is the same guard `DefaultSlotGlyphTest` provides on the launcher side: the build fails
 * here instead of the gap shipping.
 *
 * Guards COVERAGE (key sets) only — the two modules share just pure-JVM theme-kit, so the
 * glyph VALUES cannot be cross-checked here; keeping a vector in lockstep with the launcher's
 * render site stays a review concern.
 */
class StudioIconSetTest {

    @Test
    fun `every non-console customizable slot has studio art`() {
        // Console and physical-media art resolve by platform id (consoleResource / physicalMediaResource).
        val studioKeys = StudioIconSet.RESOURCE_SLOTS.keys + StudioIconSet.ITEM_VECTORS.keys
        val expected = CustomizableIcons.ALL
            .filter { it.group != IconSlot.Group.CONSOLE && it.group != IconSlot.Group.PHYSICAL_MEDIA }
            .map { it.key }
        assertEquals(
            expected.sorted(),
            studioKeys.sorted(),
            "slots missing Studio art — or Studio keys with no slot — must be resolved",
        )
    }

    @Test
    fun `resource slots and vector slots do not overlap`() {
        val overlap = StudioIconSet.RESOURCE_SLOTS.keys intersect StudioIconSet.ITEM_VECTORS.keys
        assertTrue(overlap.isEmpty(), "a slot must have exactly one default, found in both maps: $overlap")
    }

    @Test
    fun `every resource slot points at a classpath resource`() {
        val missing = StudioIconSet.RESOURCE_SLOTS.filterValues {
            StudioIconSet::class.java.classLoader.getResource(it) == null
        }
        assertTrue(missing.isEmpty(), "resource slots with no classpath file: $missing")
    }

    @Test
    fun `the UMD slot previews the PSP UMD the preview row draws`() {
        assertEquals("xmb/umd_psp.png", StudioIconSet.RESOURCE_SLOTS["item_umd"])
        assertTrue(!StudioIconSet.isFullColour("item_umd"), "the UMD is a silhouette in the icon colour")
    }

    @Test
    fun `the four shiba coin slots have medallion art`() {
        for (tier in listOf("bronze", "silver", "gold", "platinum")) {
            assertTrue("shiba_coin_$tier" in StudioIconSet.RESOURCE_SLOTS, tier)
        }
    }

    @Test
    fun `every console slot resolves to a bundled png with the launcher's fallbacks`() {
        val consoles = CustomizableIcons.ALL.filter { it.group == IconSlot.Group.CONSOLE }.map { it.key }
        assertEquals(47, consoles.size)
        for (key in consoles) {
            val path = assertNotNull(StudioIconSet.consoleResource(key), key)
            assertTrue(StudioIconSet::class.java.classLoader.getResource(path) != null, "$key -> $path missing on classpath")
        }
        assertEquals("xmb/sysicon_x360.png", StudioIconSet.consoleResource("sysicon_xbox"))
        for (id in listOf("cps1", "cps2", "cps3")) {
            assertEquals("xmb/sysicon_cps.png", StudioIconSet.consoleResource("sysicon_$id"), id)
        }
        assertEquals("xmb/sysicon_ps3.png", StudioIconSet.consoleResource("sysicon_ps3"))
        assertEquals(null, StudioIconSet.consoleResource("catbar_games"))
    }

    @Test
    fun `only the coin medallions are drawn as authored - silhouettes take the icon colour`() {
        // Console art, memory cards and All Tracked Games are white silhouettes the launcher tints (PortalIcon).
        listOf("sysicon_ps3", "sysicon_allgames", "item_memcard_video", "item_shiba_track", "item_add").forEach {
            assertTrue(!StudioIconSet.isFullColour(it), it)
        }
        assertTrue(StudioIconSet.isFullColour("shiba_coin_gold"))
    }
}
