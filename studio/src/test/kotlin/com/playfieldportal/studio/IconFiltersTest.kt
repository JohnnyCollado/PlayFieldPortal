package com.playfieldportal.studio

import com.playfieldportal.studio.io.IconPackReport
import com.playfieldportal.studio.io.PackRejected
import com.playfieldportal.studio.io.PackUnmatched
import com.playfieldportal.studio.preview.PreviewNav
import com.playfieldportal.studio.preview.PreviewNavAction
import com.playfieldportal.studio.preview.PreviewNavState
import com.playfieldportal.studio.preview.SampleContent
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.SYSICON_PLATFORM_IDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TS-30: the pure Icons-picker model (search, chips, filters, on-screen sets, slot card, pack report). */
class IconFiltersTest {

    private fun keys(query: PickerQuery, customized: Set<String> = emptySet(), onScreen: Set<String> = emptySet()) =
        IconPicker.filter(query, customized, onScreen).map { it.key }

    // ── Groups ───────────────────────────────────────────────────────────────

    @Test
    fun `group counts match the approved chips and sum to 128`() {
        val counts = IconPicker.counts()
        assertEquals(
            mapOf(
                PickerGroup.CROSSBAR to 10,
                PickerGroup.ITEMS to 33,
                PickerGroup.CONSOLES to 47,
                PickerGroup.STATUS to 10,
                PickerGroup.SHIBA to 7,
                PickerGroup.MEDIA to 6,
                PickerGroup.GAME_DETAIL to 5,
                PickerGroup.NOTIFICATIONS to 8,
                PickerGroup.MENUS to 2,
            ),
            counts,
        )
        assertEquals(128, counts.values.sum())
        assertEquals(128, CustomizableIcons.ALL.size)
    }

    @Test
    fun `shiba coins chip holds the three item_shiba rows and the four medallions`() {
        val shiba = keys(PickerQuery(group = PickerGroup.SHIBA)).toSet()
        assertEquals(
            setOf(
                "item_shiba_connect", "item_shiba_track", "item_shiba_untracked",
                "shiba_coin_bronze", "shiba_coin_silver", "shiba_coin_gold", "shiba_coin_platinum",
            ),
            shiba,
        )
        assertTrue(keys(PickerQuery(group = PickerGroup.ITEMS)).none { it.startsWith("item_shiba_") })
    }

    @Test
    fun `every slot belongs to exactly one chip`() {
        val seen = PickerGroup.entries.flatMap { keys(PickerQuery(group = it)) }
        assertEquals(CustomizableIcons.ALL.map { it.key }.sorted(), seen.sorted())
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `search matches display name key and platform names`() {
        assertEquals(5, keys(PickerQuery(search = "battery")).size)
        assertTrue("sysicon_ps3" in keys(PickerQuery(search = "PS3")))
        assertTrue("sysicon_ps3" in keys(PickerQuery(search = "playstation 3")))
        assertTrue("item_social_voice_mute" in keys(PickerQuery(search = "voice")))
        // key-only match: the display name never contains the underscore form
        assertTrue("status_battery_low" in keys(PickerQuery(search = "battery_low")))
        assertTrue("sysicon_cps2" in keys(PickerQuery(search = "capcom")))
    }

    @Test
    fun `search is case and spacing tolerant and blank matches everything`() {
        assertEquals(keys(PickerQuery(search = "ps3")), keys(PickerQuery(search = "  PS 3 ")))
        assertEquals(128, keys(PickerQuery(search = "   ")).size)
        assertTrue(keys(PickerQuery(search = "zzzz-nothing")).isEmpty())
    }

    // ── Filters ──────────────────────────────────────────────────────────────

    @Test
    fun `new set is exactly the 36 slots added in v4`() {
        val v3Statuses = setOf(
            "status_battery_full", "status_battery_high", "status_battery_medium",
            "status_battery_low", "status_battery_charging", "status_bluetooth",
        )
        assertEquals(36, IconPicker.NEW_KEYS.size)
        assertTrue(IconPicker.NEW_KEYS.none { it in v3Statuses })
        assertTrue(IconPicker.NEW_KEYS.none { it.startsWith("catbar_") })
        assertTrue(SYSICON_PLATFORM_IDS.none { "sysicon_$it" in IconPicker.NEW_KEYS })
        assertTrue(listOf("status_wifi", "shiba_coin_gold", "menu_back", "sysicon_cps1", "sysicon_default").all { it in IconPicker.NEW_KEYS })
        assertFalse("item_shiba_connect" in IconPicker.NEW_KEYS)
        assertEquals(IconPicker.NEW_KEYS, keys(PickerQuery(newOnly = true)).toSet())
    }

    @Test
    fun `customized filter keeps only overridden slots`() {
        val custom = setOf("catbar_games", "sysicon_psx")
        assertEquals(custom, keys(PickerQuery(customizedOnly = true), customized = custom).toSet())
        assertTrue(keys(PickerQuery(customizedOnly = true)).isEmpty())
    }

    @Test
    fun `filters combine with AND`() {
        val custom = setOf("sysicon_cps1", "sysicon_psx", "catbar_games")
        val query = PickerQuery(search = "c", group = PickerGroup.CONSOLES, customizedOnly = true, newOnly = true)
        assertEquals(listOf("sysicon_cps1"), keys(query, customized = custom))
        val onScreen = setOf("catbar_games", "sysicon_psx")
        assertEquals(
            listOf("sysicon_psx"),
            keys(PickerQuery(onScreen = true, customizedOnly = true, group = PickerGroup.CONSOLES), custom, onScreen),
        )
    }

    // ── On screen ────────────────────────────────────────────────────────────

    @Test
    fun `home shows the crossbar, status strip and the sample rows`() {
        val home = IconPicker.onScreenKeys()
        val groups = home.mapNotNull(CustomizableIcons::byKey).map(IconPicker::groupOf).toSet()
        assertTrue(PickerGroup.CROSSBAR in groups && PickerGroup.STATUS in groups)
        assertEquals(10, home.count { it.startsWith("catbar_") })
        assertTrue(SampleContent.rows.all { it.slotKey in home })
        assertTrue(home.all { CustomizableIcons.isValidKey(it) })
    }

    @Test
    fun `category changes which item rows are on screen`() {
        val music = IconPicker.onScreenKeys("catbar_music")
        val video = IconPicker.onScreenKeys("catbar_video")
        assertTrue("item_music_track" in music && "item_music_track" !in video)
        assertTrue("item_video_recent" in video && "item_video_recent" !in music)
    }

    @Test
    fun `an open menu adds the menu check to what is on screen`() {
        val closed = PreviewNavState()
        val open = PreviewNav.reduce(closed, PreviewNavAction.OpenOptions)
        val menu = IconPicker.onScreenKeys(PreviewNav.categoryKey(open), PreviewNav.shownSlotKeys(open))
        assertTrue("menu_check" in menu && "catbar_video" in menu)
        assertTrue("menu_check" !in IconPicker.onScreenKeys(PreviewNav.categoryKey(closed), PreviewNav.shownSlotKeys(closed)))
        assertTrue("menu_back" !in menu)
    }

    // ── Slot card ────────────────────────────────────────────────────────────

    @Test
    fun `card status reads Built-in, New slot or Custom`() {
        val games = CustomizableIcons.byKey("catbar_games")!!
        val card = IconPicker.cardModel(games, null, null)
        assertEquals("Built-in", card.status)
        assertFalse(card.isCustom)
        assertEquals("catbar_games · Crossbar · ${games.templateSizePx} px", card.detail)

        val wifi = CustomizableIcons.byKey("status_wifi")!!
        assertEquals("New slot", IconPicker.cardModel(wifi, null, null).status)
        assertTrue(IconPicker.cardModel(wifi, null, null).isNew)

        assertEquals("Custom", IconPicker.cardModel(games, "png", byteArrayOf(1, 2, 3)).status)
        assertTrue(IconPicker.cardModel(games, "png", byteArrayOf()).isCustom)
        // a customized new slot is Custom, still flagged new
        val custom = IconPicker.cardModel(wifi, "png", byteArrayOf())
        assertEquals("Custom", custom.status)
        assertTrue(custom.isNew)
    }

    @Test
    fun `card status counts gif frames`() {
        val gif = IconGifTestMedia.animatedGif(frames = 3)
        val slot = CustomizableIcons.byKey("catbar_games")!!
        val card = IconPicker.cardModel(slot, "gif", gif)
        assertEquals("Custom (GIF 3 frames)", card.status)
        assertEquals(3, card.frameCount)
    }

    @Test
    fun `preview sizes follow the launcher render sites`() {
        val catbar = IconPicker.previewSizes(CustomizableIcons.byKey("catbar_games")!!)
        assertEquals(listOf(72, 56, 40), catbar.map { it.dp })
        assertEquals(0.58f, catbar[1].alpha)
        assertEquals(1f, catbar.first().alpha)
        assertTrue(IconPicker.previewSizes(CustomizableIcons.byKey("item_add")!!).any { it.label == "In list" })
        CustomizableIcons.ALL.forEach { assertTrue(IconPicker.previewSizes(it).isNotEmpty(), it.key) }
    }

    // ── Pack report ──────────────────────────────────────────────────────────

    @Test
    fun `pack report summarizes counts, unmatched suggestions and rejects`() {
        val report = IconPackReport(
            source = "pack.zip",
            added = listOf("a", "b"),
            replaced = listOf("c"),
            unmatched = listOf(PackUnmatched("catbar_gamez.png", "catbar_games"), PackUnmatched("logo.png", null)),
            rejected = listOf(PackRejected("big.png", "over the limit")),
        )
        val lines = IconPicker.packSummary(report)
        assertEquals("pack.zip: 2 added, 1 replaced, 2 unmatched, 1 rejected", lines.first())
        assertTrue("catbar_gamez.png - did you mean catbar_games?" in lines)
        assertTrue("logo.png - no matching slot" in lines)
        assertTrue("big.png - over the limit" in lines)
        // A refused pack reports only why; nothing was applied.
        assertEquals(listOf("Pack refused: x"), IconPicker.packSummary(report.copy(error = "Pack refused: x")))
    }

    @Test
    fun `template rasterizer draws every kind of default art`() {
        // png, webp, android-vector xml, material vector, and the console stand-in
        listOf("catbar_games", "shiba_coin_gold", "status_battery_full", "catbar_social", "media_play", "sysicon_psx").forEach { key ->
            val png = com.playfieldportal.studio.preview.PreviewRenderer.rasterizeDefaultIcon(key, 64)
            assertTrue(png.size > 8 && png[1] == 'P'.code.toByte(), key)
        }
    }
}
