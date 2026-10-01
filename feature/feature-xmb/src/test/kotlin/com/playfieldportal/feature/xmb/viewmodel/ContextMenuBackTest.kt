package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Back inside a second-level menu climbs to the menu that opened it; Back on a root closes. */
class ContextMenuBackTest {

    private val card = XMBContextMenu(
        title = "PSP Memory Card",
        items = platformCardMenuItems("psp", pinned = false, iconDisplayLabel = "Global: Box Art"),
        selectedIndex = 3,
        platformId = "psp",
    )

    @Test
    fun `Back on a root menu closes it`() {
        assertNull(card.afterBack())
    }

    @Test
    fun `Back in a picker returns to the menu that opened it, cursor where it was left`() {
        val picker = XMBContextMenu(
            title = "Icon Display",
            items = listOf(XMBContextMenuItem("picondisp_default", "Use Global Setting")),
            platformId = "psp",
            parent = card,
        )

        val back = picker.afterBack()

        assertEquals(card, back)
        assertEquals("icon_display_platform", back?.items?.get(back.selectedIndex)?.id)
    }
}
