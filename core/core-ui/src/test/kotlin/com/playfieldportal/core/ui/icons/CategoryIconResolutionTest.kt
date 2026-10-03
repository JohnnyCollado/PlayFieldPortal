package com.playfieldportal.core.ui.icons

import androidx.compose.ui.graphics.ImageBitmap
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The tier order behind [CategoryIconGlyph]: a user category's own image beats the shared
 * `catbar_*` pick and the theme, and a category id of null reproduces the pre-feature result.
 */
class CategoryIconResolutionTest {

    // Never drawn — only identity matters, so a mock avoids needing an Android bitmap on the JVM.
    private fun still(): CustomIcon = CustomIcon.Still(mockk<ImageBitmap>())

    private val categoryId = "custom_retro_shelf_9"
    private val categoryKey = "usercat_custom_retro_shelf_9"

    @Test
    fun `category image wins over the catbar pick and the theme icon`() {
        val image = still()
        val resolved = resolveCategoryCustomIcon(
            iconKey = "ic_favorites",
            categoryId = categoryId,
            userIcons = mapOf(categoryKey to image, "catbar_favorites" to still()),
            themeIcons = mapOf("catbar_favorites" to still()),
        )
        assertSame(image, resolved)
    }

    @Test
    fun `without a category image the existing chain applies`() {
        val pick = still()
        val theme = still()
        assertSame(
            pick,
            resolveCategoryCustomIcon(
                "ic_favorites", categoryId,
                userIcons = mapOf("catbar_favorites" to pick),
                themeIcons = mapOf("catbar_favorites" to theme),
            ),
        )
        assertSame(
            theme,
            resolveCategoryCustomIcon(
                "ic_favorites", categoryId,
                userIcons = emptyMap(),
                themeIcons = mapOf("catbar_favorites" to theme),
            ),
        )
        assertNull(resolveCategoryCustomIcon("ic_favorites", categoryId, emptyMap(), emptyMap()))
    }

    @Test
    fun `null category id gives exactly the pre-feature result`() {
        val pick = still()
        val userIcons = mapOf(categoryKey to still(), "catbar_favorites" to pick)
        assertSame(pick, resolveCategoryCustomIcon("ic_favorites", null, userIcons, emptyMap()))
        assertNull(resolveCategoryCustomIcon("ic_favorites", null, mapOf(categoryKey to still()), emptyMap()))
    }

    @Test
    fun `a usercat key in the theme map is ignored`() {
        // Theme bundles never carry these keys; if a stray one appeared it must not draw.
        assertNull(
            resolveCategoryCustomIcon("ic_favorites", categoryId, emptyMap(), mapOf(categoryKey to still())),
        )
    }

    @Test
    fun `a category id that is not a user category gets no image tier`() {
        assertNull(
            resolveCategoryCustomIcon("ic_favorites", "games", mapOf("usercat_games" to still()), emptyMap()),
        )
    }

    @Test
    fun `console art keys still resolve to null so the ConsoleIcon path runs`() {
        assertNull(resolveCategoryCustomIcon("ic_ps1", categoryId, emptyMap(), emptyMap()))
        assertNull(resolveCategoryCustomIcon("ic_ps1", null, emptyMap(), emptyMap()))
    }
}
