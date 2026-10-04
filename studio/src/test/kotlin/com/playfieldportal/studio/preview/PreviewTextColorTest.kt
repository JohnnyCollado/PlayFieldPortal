package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.Color
import com.playfieldportal.core.ui.theme.ThemeTokens
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.TextColorChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The theme's Main and Sub text colours reach the preview by the launcher's own rule
 * (PFPColors.textOr / subTextOr): a picked colour repaints a label at that label's built-in weight,
 * the sub colour wins for sub text and the main colour stands in for it when only that one is set,
 * and with nothing picked every label keeps its built-in colour.
 */
class PreviewTextColorTest {

    private val orange = 0xFFFF8800.toInt()
    private val teal = 0xFF00AA88.toInt()

    // The launcher's built-in label colours (XMBCategoryBar / XMBItemList / XmbStatusStrip).
    private val selected = Color.White
    private val inactive = ThemeTokens.XmbInactiveLabel
    private val secondary = ThemeTokens.XmbSecondaryLabel

    private fun model(main: TextColorChoice, sub: TextColorChoice = TextColorChoice.Auto) =
        StudioState(textColor = main, subTextColor = sub).toPreviewModel()

    @Test
    fun `with nothing picked every label keeps its built-in colour`() {
        val m = model(TextColorChoice.Auto)
        assertNull(m.textOverride)
        assertNull(m.subTextOverride)
        assertEquals(selected, m.textOr(selected))
        assertEquals(inactive, m.textOr(inactive))
        assertEquals(secondary, m.subTextOr(secondary))
    }

    @Test
    fun `the main colour repaints titles and labels at their own weight`() {
        val m = model(TextColorChoice.Custom(orange))
        assertEquals(Color(orange), m.textOr(selected))
        assertEquals(Color(orange).copy(alpha = 0xCC / 255f), m.textOr(inactive))
    }

    @Test
    fun `sub text follows the main colour until a sub colour is picked`() {
        assertEquals(Color(orange).copy(alpha = 0xAA / 255f), model(TextColorChoice.Custom(orange)).subTextOr(secondary))
        val both = model(TextColorChoice.Custom(orange), TextColorChoice.Custom(teal))
        assertEquals(Color(teal).copy(alpha = 0xAA / 255f), both.subTextOr(secondary))
        assertEquals(Color(orange), both.textOr(selected), "the sub colour never touches main text")
    }

    @Test
    fun `a sub colour alone repaints only sub text`() {
        val m = model(TextColorChoice.Auto, TextColorChoice.Custom(teal))
        assertEquals(selected, m.textOr(selected))
        assertEquals(Color(teal).copy(alpha = 0xAA / 255f), m.subTextOr(secondary))
    }

    @Test
    fun `status strip icons and meters take the main colour at their own weight`() {
        val m = model(TextColorChoice.Custom(orange))
        assertEquals(Color(orange).copy(alpha = 0xAA / 255f), StatusStripTints.icon(m))
        assertEquals(Color(orange), StatusStripTints.meter(m, lit = true))
        assertEquals(Color(orange).copy(alpha = 0x40 / 255f), StatusStripTints.meter(m, lit = false))
        // The sub colour never reaches the icons.
        val subOnly = model(TextColorChoice.Auto, TextColorChoice.Custom(teal))
        assertEquals(Color(0xAAEEEEEE), StatusStripTints.icon(subOnly))
        assertEquals(Color(0xFFEEEEEE), StatusStripTints.meter(subOnly, lit = true))
        assertEquals(Color(0x40EEEEEE), StatusStripTints.meter(subOnly, lit = false))
    }

    @Test
    fun `the status strip date matches the time in the main colour`() {
        assertEquals(Color(orange), StatusStripTints.date(model(TextColorChoice.Custom(orange))))
        assertEquals(Color(0xAAEEEEEE), StatusStripTints.date(model(TextColorChoice.Auto)), "unthemed, the date keeps its quieter grey")
        val subOnly = model(TextColorChoice.Auto, TextColorChoice.Custom(teal))
        assertEquals(Color(0xAAEEEEEE), StatusStripTints.date(subOnly), "the Sub colour no longer reaches the date")
    }

    @Test
    fun `the screens built on PFPColors still read the main colour`() {
        assertEquals(Color(orange), model(TextColorChoice.Custom(orange)).textPrimary)
        assertEquals(Color.White, model(TextColorChoice.Auto).textPrimary)
    }
}
