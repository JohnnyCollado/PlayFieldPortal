package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared colour picker's model: four stops (Hue, Saturation, Brightness, Hex) on the navigation
 * core, the channel steps, ✕ applying on a bar and opening the keyboard on Hex, and hex typing that
 * repaints the colour the moment a whole code is in.
 */
class HsvPickerNavTest {

    private val sounds = mutableListOf<MenuSound>()
    private var applied: Long? = null
    private var cancelled = false
    private val nav = HsvPickerNav()

    private fun press(state: HsvPickerState, action: GamepadAction): HsvPickerState =
        nav.handle(state, action, { sounds += it }, onApply = { applied = it }, onCancel = { cancelled = true })

    private fun blue() = HsvPickerState.fromArgb(0xFF3A7BD5L)

    // ── Colour ───────────────────────────────────────────────────────────────

    @Test
    fun `a colour survives the trip through hsv`() {
        for (argb in listOf(0xFF3A7BD5L, 0xFFFFFFFFL, 0xFF000000L, 0xFFE8C64AL, 0xFF7FD8D8L, 0xFF010203L)) {
            assertEquals(argb, HsvPickerState.fromArgb(argb).argb)
        }
        assertEquals("#3A7BD5", blue().hexText)
    }

    @Test
    fun `alpha in the seed is ignored - the picker is opaque`() {
        assertEquals(0xFF3A7BD5L, HsvPickerState.fromArgb(0x803A7BD5L).argb)
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    @Test
    fun `the picker opens on hue and down walks the bars without wrapping`() {
        var s = blue()
        assertEquals(HsvPickerField.HUE, s.focus)
        s = press(s, GamepadAction.NAVIGATE_DOWN); assertEquals(HsvPickerField.SATURATION, s.focus)
        s = press(s, GamepadAction.NAVIGATE_DOWN); assertEquals(HsvPickerField.BRIGHTNESS, s.focus)
        s = press(s, GamepadAction.NAVIGATE_DOWN); assertEquals(HsvPickerField.BRIGHTNESS, s.focus)
        assertEquals(listOf(MenuSound.SCROLL, MenuSound.SCROLL), sounds)
    }

    @Test
    fun `hex sits above the bars - up from hue reaches it and stops there`() {
        var s = press(blue(), GamepadAction.NAVIGATE_UP)
        assertEquals(HsvPickerField.HEX, s.focus)
        s = press(s, GamepadAction.NAVIGATE_UP)
        assertEquals(HsvPickerField.HEX, s.focus)
        assertEquals("one cue for the one move", listOf(MenuSound.SCROLL), sounds)
        assertEquals(HsvPickerField.HUE, press(s, GamepadAction.NAVIGATE_DOWN).focus)
    }

    @Test
    fun `after touch the first d-pad press only brings the cursor back`() {
        val touched = HsvPickerNav.touch(blue(), HsvPickerField.SATURATION)
        assertFalse(touched.cursorVisible)
        val back = press(touched, GamepadAction.NAVIGATE_DOWN)
        assertTrue(back.cursorVisible)
        assertEquals(HsvPickerField.SATURATION, back.focus)
        assertEquals(HsvPickerField.BRIGHTNESS, press(back, GamepadAction.NAVIGATE_DOWN).focus)
    }

    // ── Adjusting ────────────────────────────────────────────────────────────

    @Test
    fun `left and right step the focused bar - hue wraps, the others clamp`() {
        val red = HsvPickerState(hue = 0f, saturation = 1f, brightness = 0.02f)
        assertEquals(354f, press(red, GamepadAction.NAVIGATE_LEFT).hue, 0.001f)
        assertEquals(6f, press(red, GamepadAction.NAVIGATE_RIGHT).hue, 0.001f)
        val sat = red.copy(focus = HsvPickerField.SATURATION)
        assertEquals(1f, press(sat, GamepadAction.NAVIGATE_RIGHT).saturation, 0.001f)
        assertEquals(0.96f, press(sat, GamepadAction.NAVIGATE_LEFT).saturation, 0.001f)
        val bri = red.copy(focus = HsvPickerField.BRIGHTNESS)
        assertEquals(0f, press(bri, GamepadAction.NAVIGATE_LEFT).brightness, 0.001f)
        assertEquals(MenuSound.SCROLL, sounds.last())
    }

    @Test
    fun `left and right do nothing on the hex field`() {
        val hex = blue().copy(focus = HsvPickerField.HEX)
        assertEquals(hex, press(hex, GamepadAction.NAVIGATE_LEFT))
        assertEquals(hex, press(hex, GamepadAction.NAVIGATE_RIGHT))
        assertTrue(sounds.isEmpty())
    }

    @Test
    fun `a touch on a bar focuses it and sets its value`() {
        val s = HsvPickerNav.touchBar(blue(), HsvPickerField.SATURATION, 0.25f)
        assertEquals(HsvPickerField.SATURATION, s.focus)
        assertEquals(0.25f, s.saturation, 0.0001f)
        assertFalse(s.cursorVisible)
        assertEquals(180f, HsvPickerNav.touchBar(blue(), HsvPickerField.HUE, 0.5f).hue, 0.001f)
    }

    // ── ✕ and ○ ──────────────────────────────────────────────────────────────

    @Test
    fun `cross on a bar applies the colour`() {
        val s = press(blue().copy(focus = HsvPickerField.SATURATION), GamepadAction.SELECT)
        assertEquals(0xFF3A7BD5L, applied)
        assertFalse(s.editingHex)
        assertEquals(listOf(MenuSound.CONFIRM), sounds)
    }

    @Test
    fun `cross on the hex field opens the keyboard instead of applying`() {
        val s = press(blue().copy(focus = HsvPickerField.HEX), GamepadAction.SELECT)
        assertTrue(s.editingHex)
        assertNull(applied)
        assertEquals("3A7BD5", s.hexDigits)
        assertEquals(listOf(MenuSound.SELECT), sounds)
    }

    @Test
    fun `circle cancels from any stop`() {
        press(blue().copy(focus = HsvPickerField.HEX), GamepadAction.BACK)
        assertTrue(cancelled)
        assertNull(applied)
        assertEquals(listOf(MenuSound.BACK), sounds)
    }

    // ── Hex typing ───────────────────────────────────────────────────────────

    @Test
    fun `typing keeps hex digits only, upper-cased, at most six`() {
        assertEquals("3A7BD5", HsvPickerNav.typeHex(blue(), "#3a7bd5").hexDigits)
        assertEquals("12", HsvPickerNav.typeHex(blue(), "zz1 2").hexDigits)
        assertEquals("ABCDEF", HsvPickerNav.typeHex(blue(), "abcdef12").hexDigits)
    }

    @Test
    fun `a whole code repaints the colour at once - a partial one leaves it alone`() {
        val partial = HsvPickerNav.typeHex(blue(), "E8C")
        assertEquals(0xFF3A7BD5L, partial.argb)
        assertEquals("#E8C", partial.hexText)
        val whole = HsvPickerNav.typeHex(partial, "E8C64A")
        assertEquals(0xFFE8C64AL, whole.argb)
        assertEquals("#E8C64A", whole.hexText)
    }

    @Test
    fun `moving a bar replaces a half-typed code with the colour`() {
        val partial = HsvPickerNav.typeHex(blue(), "E8").copy(focus = HsvPickerField.BRIGHTNESS)
        val moved = press(partial, GamepadAction.NAVIGATE_LEFT)
        assertEquals(moved.hexText, HsvPickerState.fromArgb(moved.argb).hexText)
    }

    @Test
    fun `closing the keyboard keeps the colour and drops a half-typed code`() {
        val typing = HsvPickerNav.typeHex(blue().copy(focus = HsvPickerField.HEX, editingHex = true), "E8C")
        val closed = HsvPickerNav.endHexEntry(typing)
        assertFalse(closed.editingHex)
        assertEquals("#3A7BD5", closed.hexText)
        assertEquals(HsvPickerField.HEX, closed.focus)
    }
}
