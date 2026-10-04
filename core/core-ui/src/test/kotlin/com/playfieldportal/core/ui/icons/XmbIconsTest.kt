package com.playfieldportal.core.ui.icons

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.core.ui.components.MENU_BACK_KEY
import com.playfieldportal.core.ui.components.MENU_CHECK_KEY
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The one place the render sites ask "which icon draws for this slot": the user's pick, else the
 * applied theme's icon, else nothing (the site's built-in art). Every themeable glyph reads it.
 */
class XmbIconsTest {

    // Never drawn — only identity matters, so a mock avoids needing an Android bitmap on the JVM.
    private fun still(): CustomIcon = CustomIcon.Still(mockk<ImageBitmap>())

    @Test
    fun `menu slot keys match the theme-kit menu slots`() {
        assertEquals("menu_check", MENU_CHECK_KEY)
        assertEquals("menu_back", MENU_BACK_KEY)
    }

    @Test
    fun `no icon in either tier keeps the built-in look`() {
        assertNull(XmbIcons.EMPTY[MENU_CHECK_KEY])
        assertNull(XmbIcons.EMPTY.tierOf(MENU_CHECK_KEY))
    }

    @Test
    fun `the theme icon draws when the user has none`() {
        val themed = still()
        val icons = XmbIcons(theme = mapOf(MENU_BACK_KEY to themed))
        assertSame(themed, icons[MENU_BACK_KEY])
        assertEquals(IconTier.THEME, icons.tierOf(MENU_BACK_KEY))
    }

    @Test
    fun `the user's pick beats the theme icon`() {
        val user = still()
        val icons = XmbIcons(user = mapOf(MENU_CHECK_KEY to user), theme = mapOf(MENU_CHECK_KEY to still()))
        assertSame(user, icons[MENU_CHECK_KEY])
        assertEquals(IconTier.USER, icons.tierOf(MENU_CHECK_KEY))
    }

    @Test
    fun `an icon for another slot is never borrowed`() {
        assertNull(XmbIcons(theme = mapOf(MENU_BACK_KEY to still()))[MENU_CHECK_KEY])
    }

    @Test
    fun `a user category image only comes from the user tier`() {
        // Theme bundles never carry usercat keys; a stray one in the theme tier must not draw.
        val key = "usercat_custom_retro_shelf_9"
        assertNull(XmbIcons(theme = mapOf(key to still()))[key])
        val mine = still()
        assertSame(mine, XmbIcons(user = mapOf(key to mine))[key])
    }

    @Test
    fun `the user tier's own keys are listed for the editor`() {
        val icons = XmbIcons(user = mapOf("catbar_games" to still()), theme = mapOf("catbar_music" to still()))
        assertEquals(setOf("catbar_games"), icons.userKeys)
        assertEquals(setOf("catbar_music"), icons.themeKeys)
    }
}
