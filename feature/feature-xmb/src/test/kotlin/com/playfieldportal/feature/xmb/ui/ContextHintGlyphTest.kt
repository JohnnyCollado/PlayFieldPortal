package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.ui.R
import com.playfieldportal.core.ui.icons.contextHintButtonDrawable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the controller-style → button-glyph mapping for the idle context-menu hint.
 *
 *   PLAYSTATION → △ Triangle   (PS labels the trigger button △)
 *   XBOX        → Y            (Xbox labels it Y)
 *   NINTENDO    → X            (Nintendo uses X as its Y button — same physical button)
 *   GENERIC     → Y            (sensible default)
 */
class ContextHintGlyphTest {

    @Test
    fun `PlayStation maps to the triangle glyph`() {
        assertEquals(
            R.drawable.btn_hint_triangle,
            ControllerDisplayType.PLAYSTATION.contextHintButtonDrawable(),
        )
    }

    @Test
    fun `Xbox maps to the Y glyph`() {
        assertEquals(
            R.drawable.btn_hint_y,
            ControllerDisplayType.XBOX.contextHintButtonDrawable(),
        )
    }

    @Test
    fun `Nintendo maps to the X glyph`() {
        assertEquals(
            R.drawable.btn_hint_x,
            ControllerDisplayType.NINTENDO.contextHintButtonDrawable(),
        )
    }

    @Test
    fun `Generic falls back to the Y glyph`() {
        assertEquals(
            R.drawable.btn_hint_y,
            ControllerDisplayType.GENERIC.contextHintButtonDrawable(),
        )
    }

    @Test
    fun `all four styles resolve to a distinct-but-valid triangle-or-Y-or-X set`() {
        // PS=triangle, XB=Y, NS=X, GENERIC=Y — never collides PS with the others.
        val ps = ControllerDisplayType.PLAYSTATION.contextHintButtonDrawable()
        val xb = ControllerDisplayType.XBOX.contextHintButtonDrawable()
        val ns = ControllerDisplayType.NINTENDO.contextHintButtonDrawable()
        assertEquals(R.drawable.btn_hint_triangle, ps)
        assertEquals(R.drawable.btn_hint_y, xb)
        assertEquals(R.drawable.btn_hint_x, ns)
        // PS must differ from XB and NS.
        assert(ps != xb) { "PS and XB glyphs must differ" }
        assert(ps != ns) { "PS and NS glyphs must differ" }
    }
}
