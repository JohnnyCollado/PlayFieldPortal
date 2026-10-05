package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.NavigationCommand
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.NavigationEngine
import com.playfieldportal.core.navigation.NavigationNode
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink
import kotlin.math.abs
import kotlin.math.roundToInt

// ── The colour picker's model and controller navigation ───────────────────────
//
// [HsvPickerState] is everything [HsvColorPickerDialog] draws: the colour, which of the four stops
// has the cursor, and the Hex field's text while it is being typed. Hosts keep one in their own
// state and pass every press through [HsvPickerNav.handle]; the dialog reports touch through the
// companion helpers. Pure, JVM-testable: no Android colour classes.

/** The picker's four stops, top to bottom: Hex first, so it stays clear of any keyboard. */
enum class HsvPickerField { HEX, HUE, SATURATION, BRIGHTNESS }

data class HsvPickerState(
    /** 0..360. */
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
    val focus: HsvPickerField = HsvPickerField.HUE,
    /** False after a touch, until the next controller press brings the cursor back. */
    val cursorVisible: Boolean = true,
    /** PFP's keyboard (or the system one) is up on the Hex field. */
    val editingHex: Boolean = false,
    /** Hex digits typed so far, while they are not yet a whole code; null = the field shows the colour. */
    val hexDraft: String? = null,
) {
    /** The colour as opaque 0xFFRRGGBB. */
    val argb: Long get() = hsvToArgb(hue, saturation, brightness)

    /** What the Hex field holds, without the `#`. */
    val hexDigits: String get() = hexDraft ?: String.format("%06X", argb and 0xFFFFFFL)

    /** What the Hex field shows. */
    val hexText: String get() = "#$hexDigits"

    companion object {
        /** A picker seeded with [argb]; any alpha is dropped. */
        fun fromArgb(argb: Long): HsvPickerState {
            val (h, s, v) = argbToHsv(argb)
            return HsvPickerState(h, s, v)
        }
    }
}

class HsvPickerNav {

    private val engine = NavigationEngine(CONTEXT).apply {
        markReady()
        replaceNodes(HsvPickerField.entries.map { NavigationNode(it.name) })
    }

    /**
     * One controller press. UP/DOWN move between the stops (no wrap), LEFT/RIGHT step the focused
     * bar, ✕ applies the colour on a bar and opens the keyboard on Hex, ○ cancels. Returns the new
     * state; [onApply] and [onCancel] are the host's to close the picker with.
     */
    fun handle(
        state: HsvPickerState,
        action: GamepadAction,
        sounds: MenuSoundSink,
        onApply: (Long) -> Unit,
        onCancel: () -> Unit,
    ): HsvPickerState {
        val direction = action.direction()
        if (direction != null && !state.cursorVisible) return state.copy(cursorVisible = true)
        return when (action) {
            GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN -> {
                engine.setFocused(state.focus.name)
                engine.dispatch(NavigationCommand.Direction(direction!!))
                val next = engine.focusedKey?.let(HsvPickerField::valueOf) ?: state.focus
                if (next == state.focus) state else state.copy(focus = next).also { sounds.play(MenuSound.SCROLL) }
            }
            GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT -> {
                val sign = if (action == GamepadAction.NAVIGATE_LEFT) -1 else 1
                step(state, sign)?.also { sounds.play(MenuSound.SCROLL) } ?: state
            }
            GamepadAction.SELECT -> if (state.focus == HsvPickerField.HEX) {
                sounds.play(MenuSound.SELECT)
                state.copy(editingHex = true)
            } else {
                sounds.play(MenuSound.CONFIRM)
                onApply(state.argb)
                state
            }
            GamepadAction.BACK -> {
                sounds.play(MenuSound.BACK)
                onCancel()
                state
            }
            else -> state
        }
    }

    private fun step(state: HsvPickerState, sign: Int): HsvPickerState? = when (state.focus) {
        HsvPickerField.HUE -> state.copy(hue = ((state.hue + sign * HUE_STEP) % 360f + 360f) % 360f, hexDraft = null)
        HsvPickerField.SATURATION -> state.copy(saturation = (state.saturation + sign * CHANNEL_STEP).coerceIn(0f, 1f), hexDraft = null)
        HsvPickerField.BRIGHTNESS -> state.copy(brightness = (state.brightness + sign * CHANNEL_STEP).coerceIn(0f, 1f), hexDraft = null)
        HsvPickerField.HEX -> null
    }

    private fun GamepadAction.direction(): NavigationDirection? = when (this) {
        GamepadAction.NAVIGATE_UP -> NavigationDirection.UP
        GamepadAction.NAVIGATE_DOWN -> NavigationDirection.DOWN
        GamepadAction.NAVIGATE_LEFT -> NavigationDirection.LEFT
        GamepadAction.NAVIGATE_RIGHT -> NavigationDirection.RIGHT
        else -> null
    }

    companion object {
        private const val CONTEXT = "hsv_picker"
        const val HUE_STEP = 6f
        const val CHANNEL_STEP = 0.04f
        private const val HEX_LENGTH = 6

        /** A tap on [field]: the cursor moves there and hides until the next controller press. */
        fun touch(state: HsvPickerState, field: HsvPickerField): HsvPickerState =
            state.copy(focus = field, cursorVisible = false)

        /** A tap or drag on a bar at [fraction] of its width. */
        fun touchBar(state: HsvPickerState, field: HsvPickerField, fraction: Float): HsvPickerState {
            val f = fraction.coerceIn(0f, 1f)
            val moved = when (field) {
                HsvPickerField.HUE -> state.copy(hue = f * 360f)
                HsvPickerField.SATURATION -> state.copy(saturation = f)
                HsvPickerField.BRIGHTNESS -> state.copy(brightness = f)
                HsvPickerField.HEX -> state
            }
            return touch(moved, field).copy(hexDraft = null)
        }

        /**
         * The Hex field's text changed. Keeps hex digits only, upper-cased, at most six; six of them
         * repaint the colour immediately, fewer leave it as it was until the code is whole.
         */
        fun typeHex(state: HsvPickerState, text: String): HsvPickerState {
            val digits = text.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(HEX_LENGTH)
            if (digits.length < HEX_LENGTH) return state.copy(hexDraft = digits)
            val (h, s, v) = argbToHsv(digits.toLong(16))
            return state.copy(hue = h, saturation = s, brightness = v, hexDraft = digits)
        }

        /** The keyboard closed: the field goes back to showing the colour. */
        fun endHexEntry(state: HsvPickerState): HsvPickerState = state.copy(editingHex = false, hexDraft = null)
    }
}

// ── Colour maths (java.awt-free, android.graphics-free) ───────────────────────

/** Opaque 0xFFRRGGBB for an HSV colour; the same rounding as android.graphics.Color.HSVToColor. */
fun hsvToArgb(hue: Float, saturation: Float, brightness: Float): Long {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val s = saturation.coerceIn(0f, 1f)
    val v = brightness.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1 - abs(h % 2f - 1))
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
    return 0xFF000000L or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/** (hue 0..360, saturation, brightness) of [argb]; alpha is ignored. */
fun argbToHsv(argb: Long): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Triple(hue, if (max == 0f) 0f else delta / max, max)
}
