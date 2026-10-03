package com.playfieldportal.studio

import com.playfieldportal.studio.io.IconPackReport
import com.playfieldportal.studio.io.PackRejected
import com.playfieldportal.studio.io.PackUnmatched
import com.playfieldportal.studio.preview.PreviewNav
import com.playfieldportal.studio.preview.PreviewNavAction
import com.playfieldportal.studio.preview.PreviewNavState
import com.playfieldportal.studio.preview.SampleContent
import com.playfieldportal.themekit.CustomizableIcons
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
    fun `group counts cover the editable slots only`() {
        val counts = IconPicker.counts()
        assertEquals(
            mapOf(PickerGroup.CROSSBAR to 9, PickerGroup.ITEMS to 34, PickerGroup.CONSOLES to 47),
            counts,
        )
        assertEquals(EditableSlots.ALL.size, counts.values.sum())
    }

    @Test
    fun `the parts themes keep stock are not editable`() {
        // Status strip, Shiba Coins, media controls, Game Detail, notifications and menus keep the launcher's art.
        listOf(
            "status_wifi", "status_battery_full", "item_shiba_track", "shiba_coin_gold",
            "media_play", "detail_more", "notif_coin", "menu_check",
        ).forEach { key ->
            assertFalse(EditableSlots.isEditable(key), key)
            assertTrue(CustomizableIcons.isValidKey(key), key) // still a registry slot: the launcher keeps reading it
            assertTrue(key !in keys(PickerQuery()), key)
        }
    }

    @Test
    fun `every editable slot belongs to exactly one chip`() {
        val seen = PickerGroup.entries.flatMap { keys(PickerQuery(group = it)) }
        assertEquals(EditableSlots.ALL.map { it.key }.sorted(), seen.sorted())
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `search matches display name key and platform names`() {
        assertTrue(keys(PickerQuery(search = "battery")).isEmpty()) // the status strip is not editable
        assertTrue("sysicon_ps3" in keys(PickerQuery(search = "PS3")))
        assertTrue("sysicon_ps3" in keys(PickerQuery(search = "playstation 3")))
        assertTrue("item_social_voice_mute" in keys(PickerQuery(search = "voice")))
        // key-only match: the display name never contains the underscore form
        assertTrue("item_video_recent" in keys(PickerQuery(search = "video_recent")))
        assertTrue("sysicon_cps2" in keys(PickerQuery(search = "capcom")))
    }

    @Test
    fun `search is case and spacing tolerant and blank matches everything`() {
        assertEquals(keys(PickerQuery(search = "ps3")), keys(PickerQuery(search = "  PS 3 ")))
        assertEquals(EditableSlots.ALL.size, keys(PickerQuery(search = "   ")).size)
        assertTrue(keys(PickerQuery(search = "zzzz-nothing")).isEmpty())
    }

    // ── Filters ──────────────────────────────────────────────────────────────

    @Test
    fun `customized filter keeps only overridden slots`() {
        val custom = setOf("catbar_games", "sysicon_psx")
        assertEquals(custom, keys(PickerQuery(customizedOnly = true), customized = custom).toSet())
        assertTrue(keys(PickerQuery(customizedOnly = true)).isEmpty())
    }

    @Test
    fun `filters combine with AND`() {
        val custom = setOf("sysicon_cps1", "sysicon_psx", "catbar_games")
        val query = PickerQuery(search = "cps", group = PickerGroup.CONSOLES, customizedOnly = true)
        assertEquals(listOf("sysicon_cps1"), keys(query, customized = custom))
        val onScreen = setOf("catbar_games", "sysicon_psx")
        assertEquals(
            listOf("sysicon_psx"),
            keys(PickerQuery(onScreen = true, customizedOnly = true, group = PickerGroup.CONSOLES), custom, onScreen),
        )
    }

    // ── On screen ────────────────────────────────────────────────────────────

    @Test
    fun `home shows the crossbar and the sample rows`() {
        val home = IconPicker.onScreenKeys()
        assertTrue(home.all(EditableSlots::isEditable))
        // The nine seeded categories; Favorites is a custom-category icon, listed with the items.
        assertEquals(9, home.count { it.startsWith("catbar_") })
        assertTrue("catbar_favorites" !in home)
        assertEquals(PickerGroup.ITEMS, IconPicker.groupOf(CustomizableIcons.byKey("catbar_favorites")!!))
        assertTrue(home.all { CustomizableIcons.isValidKey(it) })
        // With the preview's Home frame fed in, every themeable row on screen is listed.
        val frame = IconPicker.onScreenKeys(PreviewNav.categoryKey(PreviewNavState.HOME), PreviewNav.shownSlotKeys(PreviewNavState.HOME))
        assertTrue(SampleContent.rows.mapNotNull { it.slotKey }.all { it in frame })
    }

    @Test
    fun `category changes which item rows are on screen`() {
        val music = IconPicker.onScreenKeys("catbar_music")
        val video = IconPicker.onScreenKeys("catbar_video")
        assertTrue("item_music_track" in music && "item_music_track" !in video)
        assertTrue("item_video_recent" in video && "item_video_recent" !in music)
    }

    @Test
    fun `on screen lists only editable slots, so an open menu adds nothing`() {
        val open = PreviewNav.reduce(PreviewNavState(), PreviewNavAction.OpenOptions)
        val menu = IconPicker.onScreenKeys(PreviewNav.categoryKey(open), PreviewNav.shownSlotKeys(open))
        assertTrue("menu_check" !in menu && "catbar_video" in menu)
    }

    // ── Slot card ────────────────────────────────────────────────────────────

    @Test
    fun `card status reads Built-in or Custom`() {
        val games = CustomizableIcons.byKey("catbar_games")!!
        val card = IconPicker.cardModel(games, null, null)
        assertEquals("Built-in", card.status)
        assertFalse(card.isCustom)
        assertEquals("catbar_games · Crossbar · ${games.templateSizePx} px", card.detail)

        // Every slot reads Built-in until customized; there is no "new" marking.
        val add = CustomizableIcons.byKey("item_add")!!
        assertEquals("Built-in", IconPicker.cardModel(add, null, null).status)

        assertEquals("Custom", IconPicker.cardModel(games, "png", byteArrayOf(1, 2, 3)).status)
        assertTrue(IconPicker.cardModel(games, "png", byteArrayOf()).isCustom)
        assertEquals("Custom", IconPicker.cardModel(add, "png", byteArrayOf()).status)
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
        EditableSlots.ALL.forEach { assertTrue(IconPicker.previewSizes(it).isNotEmpty(), it.key) }
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
