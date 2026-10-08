package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.themekit.ThemeIconChoices
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where Pick goes: the source chooser when the applied theme has icons, else the file picker. */
class CustomIconPickRouteTest {

    @Test
    fun `no theme icons at all goes straight to the file picker`() {
        assertEquals(CustomIconPickRoute.FILE_PICKER, customIconPickRoute(themeSlotIcons = 0, ptfIcons = 0))
    }

    @Test
    fun `slot icons alone offer the chooser`() {
        assertEquals(CustomIconPickRoute.CHOOSER, customIconPickRoute(themeSlotIcons = 3, ptfIcons = 0))
    }

    @Test
    fun `extras without any slot icon still offer the chooser`() {
        assertEquals(CustomIconPickRoute.CHOOSER, customIconPickRoute(themeSlotIcons = 0, ptfIcons = 2))
    }

    @Test
    fun `a grid with no decodable tile falls back to the file picker instead of failing silently`() {
        assertEquals(CustomIconPickRoute.FILE_PICKER, themeGridRoute(null))
    }

    @Test
    fun `a built grid is shown`() {
        val grid = ThemeIconGridState(
            slotKey = "catbar_music", slotName = "Music", themeName = "T",
            sections = listOf(ThemeIconChoices.Section("Theme icons", listOf(ThemeIconChoices.Choice(ThemeIconChoices.Source.Slot("catbar_music"), "Music")))),
            icons = emptyList(),
            files = emptyList(),
        )
        assertEquals(CustomIconPickRoute.GRID, themeGridRoute(grid))
    }

    @Test
    fun `the chooser detail counts icons with the singular for one`() {
        assertEquals("ModNation · 1 icon", sourceChooserFor("catbar_music", "Music", "ModNation", 1).themeDetail)
        assertEquals("ModNation · 3 icons", sourceChooserFor("catbar_music", "Music", "ModNation", 3).themeDetail)
    }
}
