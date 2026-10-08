package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one icon list both editors show — Customize XMB Icons on the device and the Theme Studio's
 * picker: four tabs, every slot in the order the XMB shows it, item slots grouped by the column
 * they sit in.
 */
class IconEditorLayoutTest {

    private fun keys(slots: List<IconSlot>) = slots.map { it.key }

    @Test
    fun `tabs are crossbar items consoles and physical media`() {
        assertEquals(
            listOf("Crossbar", "Items", "Consoles", "Physical Media"),
            IconEditorTab.entries.map { it.label },
        )
    }

    // ── Crossbar ─────────────────────────────────────────────────────────────

    @Test
    fun `crossbar follows the default bar order`() {
        assertEquals(
            listOf(
                "catbar_settings", "catbar_photos", "catbar_music", "catbar_video", "catbar_games",
                "catbar_network", "catbar_appstore", "catbar_social", "catbar_achievements",
            ),
            keys(IconEditorLayout.crossbar()),
        )
    }

    @Test
    fun `crossbar follows a live bar order and appends the slots it leaves out`() {
        val bar = listOf("catbar_games", "catbar_settings", "not_a_slot", "catbar_music")
        assertEquals(
            listOf(
                "catbar_games", "catbar_settings", "catbar_music",
                "catbar_photos", "catbar_video", "catbar_network", "catbar_appstore", "catbar_social", "catbar_achievements",
            ),
            keys(IconEditorLayout.crossbar(bar)),
        )
    }

    @Test
    fun `favorites is a Game column item, never a crossbar icon`() {
        val withFavorites = IconEditorLayout.crossbar(listOf("catbar_favorites", "catbar_games"))
        assertTrue("catbar_favorites" !in keys(withFavorites))
        assertNull(IconEditorLayout.tabOf("catbar_favorites"))
        assertEquals(IconEditorTab.ITEMS, IconEditorLayout.tabOf("sysicon_favorites"))
    }

    // ── Items ────────────────────────────────────────────────────────────────

    @Test
    fun `item runs follow the default bar with each column top to bottom`() {
        val runs = IconEditorLayout.itemRuns()
        assertEquals(
            listOf("Settings", "Photo", "Music", "Video", "Game", "Network · App Store", "Social", "Shiba Coins"),
            runs.map { it.label },
        )
        val byLabel = runs.associate { it.label to keys(it.slots) }
        assertEquals(listOf("item_settings"), byLabel["Settings"])
        assertEquals(
            listOf("item_camera", "item_photo_albums", "item_photo_folder", "item_photo_file", "item_photo_apps", "item_memcard_photos"),
            byLabel["Photo"],
        )
        assertEquals(listOf("item_music_track", "item_playlist", "item_music_apps", "item_memcard_music"), byLabel["Music"])
        assertEquals(
            listOf(
                "item_video_collections", "item_video_recent", "item_video_favorites", "item_video_library",
                "item_video_folder", "item_video_file", "item_video_apps", "item_memcard_video",
            ),
            byLabel["Video"],
        )
        assertEquals(listOf("item_add"), byLabel["Network · App Store"])
        assertEquals(
            listOf(
                "item_social_account", "item_social_friends", "item_social_add", "item_social_voice",
                "item_social_voice_invite", "item_social_voice_mute", "item_social_voice_settings",
                "item_social_voice_leave", "item_social_activity", "item_social_discord_settings", "item_social_signout",
            ),
            byLabel["Social"],
        )
        assertEquals(listOf("item_shiba_connect", "item_shiba_track", "item_shiba_untracked"), byLabel["Shiba Coins"])
    }

    @Test
    fun `the game run holds the UMD slot, All Games and Favorites`() {
        val game = IconEditorLayout.itemRuns().single { it.label == "Game" }
        assertEquals(
            listOf("item_umd", "sysicon_allgames", "sysicon_favorites", "item_missing", "item_memcard_games"),
            keys(game.slots),
        )
        assertEquals("All Games", game.slots[1].displayName)
        assertEquals("Favorites", game.slots[2].displayName)
        assertEquals(IconEditorTab.ITEMS, IconEditorLayout.tabOf("sysicon_allgames"))
        assertEquals(IconEditorTab.ITEMS, IconEditorLayout.tabOf("sysicon_favorites"))
    }

    @Test
    fun `item runs follow a live bar order`() {
        val bar = listOf("catbar_games", "catbar_appstore", "catbar_settings")
        val labels = IconEditorLayout.itemRuns(bar).map { it.label }
        // Network · App Store sits where the first of its two columns does; the rest keep default order.
        assertEquals(
            listOf("Game", "Network · App Store", "Settings", "Photo", "Music", "Video", "Social", "Shiba Coins"),
            labels,
        )
    }

    @Test
    fun `items flatten the runs`() {
        assertEquals(
            IconEditorLayout.itemRuns().flatMap { keys(it.slots) },
            keys(IconEditorLayout.slots(IconEditorTab.ITEMS)),
        )
    }

    // ── Consoles and physical media ──────────────────────────────────────────

    @Test
    fun `consoles are the console cards in registry order without All Games and Favorites`() {
        val consoles = keys(IconEditorLayout.slots(IconEditorTab.CONSOLES))
        val expected = keys(CustomizableIcons.group(IconSlot.Group.CONSOLE)) - setOf("sysicon_allgames", "sysicon_favorites")
        assertEquals(expected, consoles)
        assertEquals("sysicon_android", consoles.first())
    }

    @Test
    fun `physical media is the registry's physical media group`() {
        val media = IconEditorLayout.slots(IconEditorTab.PHYSICAL_MEDIA)
        assertEquals(CustomizableIcons.group(IconSlot.Group.PHYSICAL_MEDIA), media)
        assertEquals(IconEditorTab.PHYSICAL_MEDIA, IconEditorLayout.tabOf("physmedia_psp"))
    }

    // ── The whole list ───────────────────────────────────────────────────────

    @Test
    fun `tab counts`() {
        assertEquals(
            mapOf(
                IconEditorTab.CROSSBAR to 9,
                IconEditorTab.ITEMS to 39,
                IconEditorTab.CONSOLES to 45,
                IconEditorTab.PHYSICAL_MEDIA to 42,
            ),
            IconEditorTab.entries.associateWith { IconEditorLayout.slots(it).size },
        )
        assertEquals(135, IconEditorLayout.ALL.size)
    }

    @Test
    fun `every editable registry slot is listed exactly once`() {
        val notEditable = setOf(
            IconSlot.Group.STATUS, IconSlot.Group.SHIBA, IconSlot.Group.MEDIA,
            IconSlot.Group.GAME_DETAIL, IconSlot.Group.NOTIFICATIONS, IconSlot.Group.MENUS,
        )
        val expected = CustomizableIcons.ALL
            .filter { it.group !in notEditable }
            .map { it.key }
            .sorted()
        val listed = keys(IconEditorLayout.ALL)
        assertEquals(listed.size, listed.toSet().size, "a slot is listed twice")
        assertEquals(expected, listed.sorted())
    }

    @Test
    fun `parts that follow the theme's colours are not listed`() {
        for (key in listOf("status_wifi", "status_battery_full", "shiba_coin_gold", "media_play", "detail_more", "notif_coin", "menu_check")) {
            assertNull(IconEditorLayout.tabOf(key), key)
        }
    }

    @Test
    fun `slots are the registry's own`() {
        for (slot in IconEditorLayout.ALL) assertEquals(CustomizableIcons.byKey(slot.key), slot, slot.key)
    }
}
