package com.playfieldportal.core.ui.components

import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.playfieldportal.core.ui.icons.CustomIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MenuGlyphTest {

    // A pixel-less stand-in: the resolver only compares identity, and a real Bitmap needs Robolectric's native graphics.
    private fun icon() = CustomIcon.Still(object : ImageBitmap {
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
        override val hasAlpha: Boolean = true
        override val height: Int = 1
        override val width: Int = 1
        override fun prepareToDraw() = Unit
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
    })

    @Test
    fun `slot keys match the theme-kit menu slots`() {
        assertEquals("menu_check", MENU_CHECK_KEY)
        assertEquals("menu_back", MENU_BACK_KEY)
    }

    @Test
    fun `no override anywhere keeps the built-in look`() {
        assertNull(menuGlyphOverride(MENU_CHECK_KEY, emptyMap(), emptyMap()))
    }

    @Test
    fun `theme icon is used when the user has none`() {
        val themed = icon()
        assertSame(themed, menuGlyphOverride(MENU_BACK_KEY, emptyMap(), mapOf(MENU_BACK_KEY to themed)))
    }

    @Test
    fun `user pick beats the theme icon`() {
        val user = icon()
        val themed = icon()
        assertSame(
            user,
            menuGlyphOverride(MENU_CHECK_KEY, mapOf(MENU_CHECK_KEY to user), mapOf(MENU_CHECK_KEY to themed)),
        )
    }

    @Test
    fun `an override for the other menu slot is ignored`() {
        assertNull(menuGlyphOverride(MENU_CHECK_KEY, emptyMap(), mapOf(MENU_BACK_KEY to icon())))
    }
}
