package com.playfieldportal.core.ui.icons

import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.core.ui.R
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
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
            iconKey = "ic_music",
            categoryId = categoryId,
            icons = XmbIcons(
                user = mapOf(categoryKey to image, "catbar_music" to still()),
                theme = mapOf("catbar_music" to still()),
            ),
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
                "ic_music", categoryId,
                XmbIcons(user = mapOf("catbar_music" to pick), theme = mapOf("catbar_music" to theme)),
            ),
        )
        assertSame(
            theme,
            resolveCategoryCustomIcon(
                "ic_music", categoryId,
                XmbIcons(theme = mapOf("catbar_music" to theme)),
            ),
        )
        assertNull(resolveCategoryCustomIcon("ic_music", categoryId, XmbIcons.EMPTY))
    }

    @Test
    fun `null category id gives exactly the pre-feature result`() {
        val pick = still()
        val userIcons = mapOf(categoryKey to still(), "catbar_music" to pick)
        assertSame(pick, resolveCategoryCustomIcon("ic_music", null, XmbIcons(user = userIcons)))
        assertNull(resolveCategoryCustomIcon("ic_music", null, XmbIcons(user = mapOf(categoryKey to still()))))
    }

    @Test
    fun `a usercat key in the theme map is ignored`() {
        // Theme bundles never carry these keys; if a stray one appeared it must not draw.
        assertNull(
            resolveCategoryCustomIcon("ic_music", categoryId, XmbIcons(theme = mapOf(categoryKey to still()))),
        )
    }

    @Test
    fun `a category id that is not a user category gets no image tier`() {
        assertNull(
            resolveCategoryCustomIcon("ic_music", "games", XmbIcons(user = mapOf("usercat_games" to still()))),
        )
    }

    @Test
    fun `console art keys still resolve to null so the ConsoleIcon path runs`() {
        assertNull(resolveCategoryCustomIcon("ic_ps1", categoryId, XmbIcons.EMPTY))
        assertNull(resolveCategoryCustomIcon("ic_ps1", null, XmbIcons.EMPTY))
    }

    // Favorites is the Game column's Favorites card (sysicon_favorites), not a crossbar category.

    @Test
    fun `the favorites icon has no crossbar slot and resolves as console art`() {
        assertNull(catbarSlotKeyFor("ic_favorites"))
        assertNull(catbarIconKeyFor("catbar_favorites"))
        assertEquals("favorites", consolePlatformIdFor("ic_favorites"))
        assertEquals(R.drawable.sysicon_favorites, categoryIconFor("ic_favorites").resId)
        assertEquals("Favorites", categoryIconFor("ic_favorites").label)
    }

    @Test
    fun `a category with the favorites icon follows the Favorites card's art, not a crossbar pick`() {
        // No crossbar slot, so null here: CategoryIconGlyph then draws ConsoleIcon("favorites"),
        // which reads the user's or the theme's sysicon_favorites.
        assertNull(
            resolveCategoryCustomIcon(
                "ic_favorites", categoryId,
                XmbIcons(user = mapOf("catbar_favorites" to still()), theme = mapOf("catbar_favorites" to still())),
            ),
        )
    }
}
