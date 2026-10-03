package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSlotKeyTest {

    @Test
    fun `every kind maps to a registered notifications slot`() {
        for (kind in NotificationKind.entries) {
            val slot = CustomizableIcons.byKey(notificationSlotKey(kind))
            assertTrue("$kind -> ${notificationSlotKey(kind)} must be a registered slot", slot != null)
            assertEquals(IconSlot.Group.NOTIFICATIONS, slot!!.group)
        }
    }

    @Test
    fun `kinds map one to one and cover the whole group`() {
        val keys = NotificationKind.entries.map { notificationSlotKey(it) }
        assertEquals("two kinds share a slot", keys.size, keys.toSet().size)
        val group = CustomizableIcons.ALL.filter { it.group == IconSlot.Group.NOTIFICATIONS }.map { it.key }.toSet()
        assertEquals(group, keys.toSet())
    }

    @Test
    fun `mapping is frozen`() {
        assertEquals("notif_album", notificationSlotKey(NotificationKind.SCAN))
        assertEquals("notif_image", notificationSlotKey(NotificationKind.ARTWORK))
        assertEquals("notif_tag", notificationSlotKey(NotificationKind.METADATA))
        assertEquals("notif_coin", notificationSlotKey(NotificationKind.ACHIEVEMENT))
        assertEquals("notif_blocked", notificationSlotKey(NotificationKind.LAUNCH))
        assertEquals("notif_settings", notificationSlotKey(NotificationKind.SYSTEM))
        assertEquals("notif_download", notificationSlotKey(NotificationKind.DOWNLOAD))
        assertEquals("notif_feed", notificationSlotKey(NotificationKind.FEED))
    }

    @Test
    fun `the slot's default glyph is the kind's own glyph`() {
        for (kind in NotificationKind.entries) {
            val slot = CustomizableIcons.byKey(notificationSlotKey(kind))!!
            assertEquals(SlotGlyphDefault.Vector(notificationGlyph(kind)), defaultGlyphFor(slot))
        }
    }
}
