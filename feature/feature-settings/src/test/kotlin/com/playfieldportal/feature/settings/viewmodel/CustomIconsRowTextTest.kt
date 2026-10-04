package com.playfieldportal.feature.settings.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings ▸ Themes ▸ Customize XMB Icons: the row's value counts the user's own picks among the
 * icons the editor lists, the same "N custom" the Theme Studio's Icons rail shows.
 */
class CustomIconsRowTextTest {

    @Test
    fun `no picks reads None custom`() {
        assertEquals("None custom", CustomIconsRowText.value(emptySet()))
    }

    @Test
    fun `picks the editor lists are counted, user category images included`() {
        assertEquals(
            "3 custom",
            CustomIconsRowText.value(setOf("catbar_games", "physmedia_psp", "usercat_custom_ff_5")),
        )
        assertEquals("1 custom", CustomIconsRowText.value(setOf("sysicon_favorites")))
    }

    @Test
    fun `stored keys the editor does not list are not counted`() {
        // A pick left behind on a slot that follows the theme's colours now, and the hidden category icon.
        assertEquals("None custom", CustomIconsRowText.value(setOf("status_wifi", "catbar_favorites")))
    }
}
