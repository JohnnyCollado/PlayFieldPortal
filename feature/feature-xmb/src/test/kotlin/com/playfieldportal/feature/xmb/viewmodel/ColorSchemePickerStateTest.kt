package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.domain.model.XmbColorScheme
import com.playfieldportal.core.domain.model.XmbSchemeVariant
import com.playfieldportal.core.domain.model.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the colour-scheme picker's tint and depth steppers: which rows offer them, where they stop,
 * and that moving to another preset starts from that preset's own standard colour.
 */
class ColorSchemePickerStateTest {

    private fun option(scheme: XmbColorScheme) = ColorSchemeOption(
        scheme = scheme,
        label = UiText.raw(scheme.name),
        sublabel = UiText.raw(""),
        swatch = scheme.resolve(1).waveColor,
    )

    private val custom = ColorSchemeOption(scheme = null, label = UiText.raw("Custom"), sublabel = UiText.raw(""), swatch = 0xFF888888L, isCustom = true)

    private val picker = ColorSchemePickerState(
        options = listOf(option(XmbColorScheme.ORIGINAL), option(XmbColorScheme.ROYAL_PURPLE), option(XmbColorScheme.CHARCOAL), custom),
        selectedIndex = 1,
    )

    @Test
    fun `a fixed preset offers the steppers, the monthly scheme and custom do not`() {
        assertTrue(picker.variantsAvailable)
        assertFalse(picker.copy(selectedIndex = 0).variantsAvailable)
        assertFalse(picker.copy(selectedIndex = 3).variantsAvailable)
    }

    @Test
    fun `a step changes the previewed colour`() {
        val stepped = picker.stepped(tint = -1)!!
        assertEquals(XmbSchemeVariant(tint = -1), stepped.variant)
        assertEquals(XmbSchemeVariant(tint = -1).applyTo(0xFF7A4DD6L), stepped.previewArgb)
        assertEquals(0xFF7A4DD6L, picker.previewArgb)
    }

    @Test
    fun `tint and depth step independently`() {
        val stepped = picker.stepped(tint = 1)!!.stepped(depth = -1)!!
        assertEquals(XmbSchemeVariant(tint = 1, depth = -1), stepped.variant)
    }

    @Test
    fun `a step past the end of the range changes nothing`() {
        val end = picker.copy(variant = XmbSchemeVariant(tint = 2, depth = -2))
        assertNull(end.stepped(tint = 1))
        assertNull(end.stepped(depth = -1))
    }

    @Test
    fun `a row without steppers does not step`() {
        assertNull(picker.copy(selectedIndex = 0).stepped(tint = 1))
        assertNull(picker.copy(selectedIndex = 3).stepped(depth = 1))
        assertEquals(0xFF888888L, picker.copy(selectedIndex = 3).previewArgb)
    }

    @Test
    fun `moving to another preset starts from its standard colour`() {
        val moved = picker.stepped(tint = 2)!!.movedTo(2)
        assertEquals(2, moved.selectedIndex)
        assertEquals(XmbSchemeVariant.STANDARD, moved.variant)
    }

    @Test
    fun `moving to the row already selected keeps its steps`() {
        val stepped = picker.stepped(depth = 1)!!
        assertEquals(stepped, stepped.movedTo(1))
    }
}
