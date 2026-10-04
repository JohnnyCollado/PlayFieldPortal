package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** PspContextMenu's rule, mirrored: the shadow under a dimmed label is dimmed by the same amount. */
class PreviewFlyoutShadowTest {
    @Test
    fun `an opaque fill keeps the full-strength shadow`() {
        assertEquals(MENU_SHADOW_ALPHA, menuTextShadowFor(Color.White).color.alpha, 1f / 255f)
    }

    @Test
    fun `a dimmed fill dims its shadow by the same amount`() {
        assertEquals(MENU_SHADOW_ALPHA * 0.45f, menuTextShadowFor(Color.White.copy(alpha = 0.45f)).color.alpha, 1f / 255f)
    }

    @Test
    fun `offset and blur never change with the fill`() {
        val dim = menuTextShadowFor(Color.White.copy(alpha = 0.2f))
        val full = menuTextShadowFor(Color.White)
        assertEquals(full.offset, dim.offset)
        assertEquals(full.blurRadius, dim.blurRadius)
    }
}
