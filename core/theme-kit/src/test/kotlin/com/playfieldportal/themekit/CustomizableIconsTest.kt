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
    fun `every console slot key derives from the platform ids then the extras`() {
        // A4 (Theme Studio restructure): the console group is the platform ids followed by
        // SYSICON_EXTRA_IDS. This assertion used to be platform ids only.
        val consoleSlots = CustomizableIcons.ALL.drop(IconSlots.ALL.size)
        assertEquals(SYSICON_PLATFORM_IDS + SYSICON_EXTRA_IDS, consoleSlots.map { it.key.removePrefix("sysicon_") })
    }

    @Test
    fun `console group has 47 slots and the platform list stays at 40`() {
        assertEquals(47, CustomizableIcons.group(IconSlot.Group.CONSOLE).size)
        assertEquals(40, SYSICON_PLATFORM_IDS.size)
        assertEquals(
            listOf("cps1", "cps2", "cps3", "xbox", "favorites", "desktop", "default"),
            SYSICON_EXTRA_IDS,
        )
        assertTrue(SYSICON_PLATFORM_IDS.intersect(SYSICON_EXTRA_IDS.toSet()).isEmpty())
    }

    @Test
    fun `isValidKey accepts every extra console key`() {
        for (id in SYSICON_EXTRA_IDS) assertTrue(CustomizableIcons.isValidKey("sysicon_$id"), id)
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
    }

    @Test
    fun `console display names are non-blank and differ from the raw id`() {
        for (id in SYSICON_PLATFORM_IDS + SYSICON_EXTRA_IDS) {
            val name = consoleDisplayName(id)
            assertTrue(name.isNotBlank(), "blank display name for $id")
            assertNotEquals(id, name, "display name for $id should be humanized, not the raw id")
        }
    }

    // -- TS-06: 29 new icons/ slots in 5 new groups (the plan's "36" miscounts; 52 + 29 + 47 = 128) --

    @Test
    fun `registry totals 128 slots, 81 of them icons slots`() {
        assertEquals(81, IconSlots.ALL.size)
        assertEquals(128, CustomizableIcons.ALL.size)
    }

    @Test
    fun `the original 52 slots keep key, order and group`() {
        val original = IconSlots.ALL.take(52)
        assertEquals(V3EraReader.ICON_KEYS, original.map { it.key }.toSet())
        // A3: existing groups are contiguous runs in this order; none of it may shift.
        assertEquals(
            List(10) { IconSlot.Group.CATEGORY_BAR } + List(36) { IconSlot.Group.ITEMS } + List(6) { IconSlot.Group.STATUS },
            original.map { it.group },
        )
        assertEquals("catbar_games", original.first().key)
        assertEquals("item_shiba_untracked", original[45].key)
        assertEquals("status_bluetooth", original.last().key)
    }

    @Test
    fun `group counts follow the plan`() {
        fun n(g: IconSlot.Group) = CustomizableIcons.group(g).size
        assertEquals(10, n(IconSlot.Group.CATEGORY_BAR))
        assertEquals(36, n(IconSlot.Group.ITEMS))
        assertEquals(10, n(IconSlot.Group.STATUS))
        assertEquals(4, n(IconSlot.Group.SHIBA))
        assertEquals(6, n(IconSlot.Group.MEDIA))
        assertEquals(5, n(IconSlot.Group.GAME_DETAIL))
        assertEquals(8, n(IconSlot.Group.NOTIFICATIONS))
        assertEquals(2, n(IconSlot.Group.MENUS))
        assertEquals(47, n(IconSlot.Group.CONSOLE))
    }

    @Test
    fun `new slot keys are the fixed forever-stable names`() {
        fun keys(g: IconSlot.Group) = CustomizableIcons.group(g).map { it.key }
        assertEquals(
            listOf("status_battery_full", "status_battery_high", "status_battery_medium", "status_battery_low",
                "status_battery_charging", "status_bluetooth",
                "status_notifications", "status_controller", "status_wifi", "status_signal"),
            keys(IconSlot.Group.STATUS),
        )
        assertEquals(
            listOf("shiba_coin_bronze", "shiba_coin_silver", "shiba_coin_gold", "shiba_coin_platinum"),
            keys(IconSlot.Group.SHIBA),
        )
        assertEquals(
            listOf("media_play", "media_pause", "media_prev", "media_next", "media_back10", "media_fwd10"),
            keys(IconSlot.Group.MEDIA),
        )
        assertEquals(
            listOf("detail_play", "detail_favorite", "detail_artwork", "detail_manual", "detail_more"),
            keys(IconSlot.Group.GAME_DETAIL),
        )
        assertEquals(
            listOf("notif_album", "notif_image", "notif_tag", "notif_coin", "notif_blocked",
                "notif_settings", "notif_download", "notif_feed"),
            keys(IconSlot.Group.NOTIFICATIONS),
        )
        assertEquals(listOf("menu_check", "menu_back"), keys(IconSlot.Group.MENUS))
    }

    @Test
    fun `new slots use 128 px templates for status and 256 for the rest`() {
        for (slot in IconSlots.ALL.drop(52)) {
            val expected = if (slot.group == IconSlot.Group.STATUS) 128 else 256
            assertEquals(expected, slot.templateSizePx, slot.key)
        }
    }

    @Test
    fun `the codec's icons gate accepts every new slot`() {
        for (slot in IconSlots.ALL.drop(52)) assertTrue(IconSlots.isValidKey(slot.key), slot.key)
    }
}
