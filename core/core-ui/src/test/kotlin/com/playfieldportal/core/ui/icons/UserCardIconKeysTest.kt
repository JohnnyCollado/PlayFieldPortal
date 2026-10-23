package com.playfieldportal.core.ui.icons

import androidx.compose.ui.graphics.ImageBitmap
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store keys for a custom memory card's own image: `usercard_<collectionId>`, device-local like a
 * category's own image, and never something a theme can supply.
 */
class UserCardIconKeysTest {

    @Test
    fun `a card's key is its id behind the prefix, and back`() {
        assertEquals("usercard_42", UserCardIconKeys.keyFor(42L))
        assertEquals(42L, UserCardIconKeys.collectionIdFor("usercard_42"))
        assertTrue(UserCardIconKeys.isValidKey("usercard_42"))
    }

    @Test
    fun `anything that is not a positive id is not a key - no path can sneak in`() {
        assertNull(UserCardIconKeys.keyFor(0L))
        assertNull(UserCardIconKeys.keyFor(-3L))
        listOf("usercard_", "usercard_x", "usercard_-1", "usercard_1/../a", "usercard_01", "usercat_custom_a", "item_memcard_games")
            .forEach { assertFalse(it, UserCardIconKeys.isValidKey(it)) }
    }

    @Test
    fun `a theme cannot supply a card's own image`() {
        val still = CustomIcon.Still(mockk<ImageBitmap>(relaxed = true))
        val icons = XmbIcons(theme = mapOf("usercard_7" to still))
        assertNull(icons["usercard_7"])
        assertNull(icons.tierOf("usercard_7"))

        val mine = XmbIcons(user = mapOf("usercard_7" to still))
        assertSame(still, mine["usercard_7"])
    }
}
