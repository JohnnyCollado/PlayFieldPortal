package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The superset registry the icon customizer and the v3 codec gate on. `IconSlots.ALL` is the
 * bundle contract (keys are zip entry names) and must be untouched inside the superset; the
 * console slots extend it under `sysicon_<platformId>` keys.
 */
class CustomizableIconsTest {

    @Test
    fun `all keys are unique`() {
        val keys = CustomizableIcons.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate slot key in CustomizableIcons.ALL")
    }

    @Test
    fun `contains every IconSlots slot in order`() {
        val themeSlots = CustomizableIcons.ALL.take(IconSlots.ALL.size)
        assertEquals(IconSlots.ALL, themeSlots, "IconSlots.ALL must be a prefix of CustomizableIcons.ALL, unchanged")
    }

    @Test
    fun `console slots come after the theme slots`() {
        val consoleSlots = CustomizableIcons.ALL.drop(IconSlots.ALL.size)
        assertTrue(consoleSlots.isNotEmpty(), "no console slots registered")
        assertTrue(consoleSlots.all { it.group == IconSlot.Group.CONSOLE })
        assertTrue(consoleSlots.all { it.key.startsWith("sysicon_") })
        assertTrue(consoleSlots.all { it.templateSizePx == 256 })
    }

    @Test
    fun `every console slot key derives from the platform id list`() {
        val consoleSlots = CustomizableIcons.ALL.drop(IconSlots.ALL.size)
        assertEquals(SYSICON_PLATFORM_IDS, consoleSlots.map { it.key.removePrefix("sysicon_") })
    }

    @Test
    fun `isValidKey accepts a theme slot and a console slot`() {
        assertTrue(CustomizableIcons.isValidKey("catbar_games"))
        assertTrue(CustomizableIcons.isValidKey("status_battery_full"))
        assertTrue(CustomizableIcons.isValidKey("sysicon_snes"))
    }

    @Test
    fun `isValidKey rejects traversal, separators, empty and unknown keys`() {
        assertFalse(CustomizableIcons.isValidKey(""))
        assertFalse(CustomizableIcons.isValidKey(".."))
        assertFalse(CustomizableIcons.isValidKey("../evil"))
        assertFalse(CustomizableIcons.isValidKey("catbar_games/../../x"))
        assertFalse(CustomizableIcons.isValidKey("sysicon_snes/"))
        assertFalse(CustomizableIcons.isValidKey("/etc/passwd"))
        assertFalse(CustomizableIcons.isValidKey("not_a_slot"))
        // sysicon_default is the built-in fallback art, not a platform slot — the customizer
        // must not offer it (nothing renders through it but the fallback itself).
        assertFalse(CustomizableIcons.isValidKey("sysicon_default"))
    }

    @Test
    fun `console display names are non-blank and differ from the raw id`() {
        for (id in SYSICON_PLATFORM_IDS) {
            val name = consoleDisplayName(id)
            assertTrue(name.isNotBlank(), "blank display name for $id")
            assertNotEquals(id, name, "display name for $id should be humanized, not the raw id")
        }
    }
}
