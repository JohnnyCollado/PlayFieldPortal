package com.playfieldportal.core.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The menu's drop shadow follows the fill it sits under: a group header drawn at 45% must not carry
 * a shadow darker than its own letters, which is what made the dimmed rows read as smudges.
 */
class PspContextMenuShadowTest {
    @Test
    fun `an opaque fill keeps the full-strength shadow`() {
        assertEquals(MENU_SHADOW_ALPHA, menuTextShadowFor(Color.White).color.alpha, 1f / 255f)
    }

    @Test
    fun `a dimmed fill dims its shadow by the same amount`() {
        val shadow = menuTextShadowFor(Color.White.copy(alpha = 0.45f))
        assertEquals(MENU_SHADOW_ALPHA * 0.45f, shadow.color.alpha, 1f / 255f)
        assertEquals(0f, shadow.color.red, 0f)
    }

    @Test
    fun `offset and blur never change with the fill`() {
        val dim = menuTextShadowFor(Color.White.copy(alpha = 0.2f))
        val full = menuTextShadowFor(Color.White)
        assertEquals(full.offset, dim.offset)
        assertEquals(full.blurRadius, dim.blurRadius, 0f)
    }
}
