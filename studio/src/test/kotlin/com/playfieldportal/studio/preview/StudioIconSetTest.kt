package com.playfieldportal.studio.preview

import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val studioKeys = StudioIconSet.RESOURCE_SLOTS.keys + StudioIconSet.ITEM_VECTORS.keys
        val expected = CustomizableIcons.ALL
            .filter { it.group != IconSlot.Group.CONSOLE }
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
}
