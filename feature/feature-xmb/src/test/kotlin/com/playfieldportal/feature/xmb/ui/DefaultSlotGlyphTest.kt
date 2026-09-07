package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The customizer previews every slot's built-in art. A slot with no default degrades to a
 * placeholder letter in the strip — which is what these tests exist to prevent: add a slot to
 * [CustomizableIcons] without teaching [defaultGlyphFor] about it and the build fails here,
 * not silently in the UI.
 */
class DefaultSlotGlyphTest {

    @Test
    fun `every customizable slot has a built-in glyph`() {
        val missing = CustomizableIcons.ALL
            .filter { defaultGlyphFor(it) == SlotGlyphDefault.None }
            .map { it.key }
        assertEquals("slots with no built-in glyph", emptyList<String>(), missing)
    }

    @Test
    fun `console slots resolve to their platform art`() {
        val slot = CustomizableIcons.byKey("sysicon_snes")!!
        assertEquals(SlotGlyphDefault.Console("snes"), defaultGlyphFor(slot))
    }

    @Test
    fun `crossbar slots resolve to the catalog drawable`() {
        val slots = CustomizableIcons.ALL.filter { it.group == IconSlot.Group.CATEGORY_BAR }
        assertEquals(10, slots.size)
        for (slot in slots) {
            assertTrue(
                "${slot.key} should resolve to a drawable",
                defaultGlyphFor(slot) is SlotGlyphDefault.Drawable,
            )
        }
    }

    @Test
    fun `status slots resolve to the strip's own drawables`() {
        val slots = CustomizableIcons.ALL.filter { it.group == IconSlot.Group.STATUS }
        assertEquals(6, slots.size)
        for (slot in slots) {
            assertEquals(
                "${slot.key} should resolve to its status drawable",
                SlotGlyphDefault.Drawable(XmbStatusIcons.forSlotKey(slot.key)!!),
                defaultGlyphFor(slot),
            )
        }
    }

    @Test
    fun `memory card slots share the physical-media art`() {
        val keys = listOf(
            "item_memcard_games",
            "item_memcard_music",
            "item_memcard_video",
            "item_memcard_photos",
            "item_shiba_track",
        )
        for (key in keys) {
            assertEquals(
                key,
                SlotGlyphDefault.BundledAsset(MEMORY_CARD_DEFAULT_ART),
                defaultGlyphFor(CustomizableIcons.byKey(key)!!),
            )
        }
    }

    @Test
    fun `item slots resolve to a Material vector`() {
        val slot = CustomizableIcons.byKey("item_missing")!!
        assertTrue(defaultGlyphFor(slot) is SlotGlyphDefault.Vector)
    }

    @Test
    fun `the settings wrench is console art, not a Material glyph`() {
        val slot = CustomizableIcons.byKey("item_settings")!!
        assertTrue(defaultGlyphFor(slot) is SlotGlyphDefault.Drawable)
    }
}
