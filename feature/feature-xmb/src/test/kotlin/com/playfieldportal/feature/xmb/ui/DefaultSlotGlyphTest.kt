package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.feature.xmb.ui.detail.shibaCoinRes
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import com.playfieldportal.themekit.SharedIconArt
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
        assertEquals(9, slots.size)
        for (slot in slots) {
            assertTrue(
                "${slot.key} should resolve to a drawable",
                defaultGlyphFor(slot) is SlotGlyphDefault.Drawable,
            )
        }
    }

    @Test
    fun `every item slot sharing the memory-card art previews the memory card`() {
        // sysicon_allgames is console art: its default is the same picture through systemIconRes.
        val items = SharedIconArt.MEMORY_CARD.mapNotNull { CustomizableIcons.byKey(it) }
            .filter { it.group != IconSlot.Group.CONSOLE }
        assertEquals(5, items.size)
        for (slot in items) {
            assertEquals(slot.key, SlotGlyphDefault.BundledAsset(MEMORY_CARD_DEFAULT_ART), defaultGlyphFor(slot))
        }
    }

    @Test
    fun `status slots resolve to the strip's own drawables or a vector`() {
        val slots = CustomizableIcons.ALL.filter { it.group == IconSlot.Group.STATUS }
        assertEquals(10, slots.size)
        val levelAware = setOf("status_notifications", "status_controller", "status_wifi", "status_signal")
        for (slot in slots) {
            if (slot.key in levelAware) {
                // The strip draws these as vectors / Canvas meters; the slot previews a vector.
                assertTrue("${slot.key} should resolve to a vector", defaultGlyphFor(slot) is SlotGlyphDefault.Vector)
            } else {
                assertEquals(
                    "${slot.key} should resolve to its status drawable",
                    SlotGlyphDefault.Drawable(XmbStatusIcons.forSlotKey(slot.key)!!),
                    defaultGlyphFor(slot),
                )
            }
        }
    }

    @Test
    fun `shiba coin slots resolve to the tier medallions`() {
        val expected = mapOf(
            "shiba_coin_bronze" to ShibaTier.BRONZE,
            "shiba_coin_silver" to ShibaTier.SILVER,
            "shiba_coin_gold" to ShibaTier.GOLD,
            "shiba_coin_platinum" to ShibaTier.PLATINUM,
        )
        for ((key, tier) in expected) {
            assertEquals(
                key,
                SlotGlyphDefault.Drawable(shibaCoinRes(tier)),
                defaultGlyphFor(CustomizableIcons.byKey(key)!!),
            )
        }
    }

    @Test
    fun `media, detail, notification and menu slots resolve to vectors`() {
        val groups = setOf(
            IconSlot.Group.MEDIA, IconSlot.Group.GAME_DETAIL,
            IconSlot.Group.NOTIFICATIONS, IconSlot.Group.MENUS,
        )
        val slots = CustomizableIcons.ALL.filter { it.group in groups }
        assertEquals(21, slots.size)
        for (slot in slots) {
            assertTrue("${slot.key} should resolve to a vector", defaultGlyphFor(slot) is SlotGlyphDefault.Vector)
        }
    }

    @Test
    fun `notification slots reuse the notification kind glyph table`() {
        val kinds = mapOf(
            "notif_album" to NotificationKind.SCAN,
            "notif_image" to NotificationKind.ARTWORK,
            "notif_tag" to NotificationKind.METADATA,
            "notif_coin" to NotificationKind.ACHIEVEMENT,
            "notif_blocked" to NotificationKind.LAUNCH,
            "notif_settings" to NotificationKind.SYSTEM,
            "notif_download" to NotificationKind.DOWNLOAD,
            "notif_feed" to NotificationKind.FEED,
        )
        for ((key, kind) in kinds) {
            assertEquals(
                key,
                SlotGlyphDefault.Vector(notificationGlyph(kind)),
                defaultGlyphFor(CustomizableIcons.byKey(key)!!),
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
    fun `the UMD slot defaults to the PSP UMD art the row draws`() {
        assertEquals(
            SlotGlyphDefault.BundledAsset(UMD_SLOT_ART),
            defaultGlyphFor(CustomizableIcons.byKey("item_umd")!!),
        )
    }

    @Test
    fun `physical media slots resolve to the platform's media art`() {
        val slots = CustomizableIcons.group(IconSlot.Group.PHYSICAL_MEDIA)
        assertEquals(42, slots.size)
        assertEquals(SlotGlyphDefault.PhysicalMedia("psp"), defaultGlyphFor(CustomizableIcons.byKey("physmedia_psp")!!))
        assertEquals(SlotGlyphDefault.PhysicalMedia("x360"), defaultGlyphFor(CustomizableIcons.byKey("physmedia_x360")!!))
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
