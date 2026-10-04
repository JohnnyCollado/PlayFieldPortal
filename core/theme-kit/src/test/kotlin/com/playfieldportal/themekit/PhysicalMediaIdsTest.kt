package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One table answers both questions a platform id asks of its physical media: which bundled art
 * file draws it (regional art included: a Genesis game shows the Genesis cart) and which
 * `physmedia_` slot themes it (the console's own: Genesis and Mega Drive share one slot).
 */
class PhysicalMediaIdsTest {

    @Test
    fun `aliases name their art file and their console's slot`() {
        assertEquals("psx" to "physmedia_psx", PhysicalMediaIds.artFile("ps1") to PhysicalMediaIds.slotKey("ps1"))
        assertEquals("genesis" to "physmedia_megadrive", PhysicalMediaIds.artFile("genesis") to PhysicalMediaIds.slotKey("genesis"))
        assertEquals("sfc" to "physmedia_snes", PhysicalMediaIds.artFile("sfc") to PhysicalMediaIds.slotKey("sfc"))
        assertEquals("tg16" to "physmedia_pcengine", PhysicalMediaIds.artFile("tgfx16") to PhysicalMediaIds.slotKey("tgfx16"))
        assertEquals("arcade" to "physmedia_mame", PhysicalMediaIds.artFile("naomi") to PhysicalMediaIds.slotKey("naomi"))
        assertEquals("xbox360" to "physmedia_x360", PhysicalMediaIds.artFile("x360") to PhysicalMediaIds.slotKey("x360"))
        assertEquals("xbox360" to "physmedia_x360", PhysicalMediaIds.artFile("xbox360") to PhysicalMediaIds.slotKey("xbox360"))
    }

    @Test
    fun `a console id is its own art and its own slot`() {
        assertEquals("psp", PhysicalMediaIds.artFile("psp"))
        assertEquals("physmedia_psp", PhysicalMediaIds.slotKey("psp"))
    }

    @Test
    fun `digital-only platforms have neither`() {
        for (id in listOf("android", "steam", "gog", "default")) {
            assertNull(PhysicalMediaIds.artFile(id), id)
            assertNull(PhysicalMediaIds.slotKey(id), id)
        }
        assertNull(PhysicalMediaIds.artFile(null))
        assertNull(PhysicalMediaIds.slotKey(null))
    }

    @Test
    fun `an unregistered platform keeps its art file but has no slot`() {
        assertEquals("amiga", PhysicalMediaIds.artFile("amiga"))
        assertNull(PhysicalMediaIds.slotKey("amiga"))
    }

    @Test
    fun `every alias lands on a registered slot`() {
        for (alias in PhysicalMediaIds.ALIASES.keys) {
            assertEquals(true, PhysicalMediaIds.slotKey(alias)?.let(CustomizableIcons::isValidKey), alias)
        }
    }
}
