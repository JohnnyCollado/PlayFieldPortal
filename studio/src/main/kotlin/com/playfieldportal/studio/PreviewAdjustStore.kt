package com.playfieldportal.studio

import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutAdjustCodec
import com.playfieldportal.themekit.XmbLayoutPreset
import java.util.prefs.Preferences
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Preview with my layout": the launcher's Adjust XMB Layout values (scale, horizontal, vertical),
 * applied to the Studio PREVIEW only. They are device settings, not theme content, so this holder
 * is deliberately outside [StudioState]: no edit, undo step, or export can ever see them.
 * Remembered per Studio install through [Preferences] (a unique node in tests).
 *
 * Preview code (TS-31/32) reads [enabled] and [adjust]; when [enabled] is false the preview uses
 * the theme's own geometry alone.
 */
class PreviewAdjustStore(private val prefs: Preferences = Preferences.userRoot().node(NODE)) {

    private val _adjust = MutableStateFlow(load())
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    val adjust: StateFlow<XmbLayoutAdjust> = _adjust.asStateFlow()
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        persist { putBoolean(KEY_ENABLED, on) }
    }

    /** Scale 0.6-1.8 in steps of 0.02. A non-finite value is ignored. */
    fun setScale(value: Float) = update { it.copy(scale = snap(value, SCALE_MIN, SCALE_MAX, SCALE_STEP) ?: it.scale) }

    /** Horizontal shift -25..35 % of the width in steps of 1 %. */
    fun setLeft(value: Float) =
        update { it.copy(barLeftFraction = snap(value, LEFT_MIN, LEFT_MAX, POSITION_STEP) ?: it.barLeftFraction) }

    /** Crossbar top 5..45 % of the height in steps of 1 %. */
    fun setTop(value: Float) =
        update { it.copy(barTopFraction = snap(value, TOP_MIN, TOP_MAX, POSITION_STEP) ?: it.barTopFraction) }

    /** The "Biblically Accurate PSP XMB" fit, for the AYN Thor reference screen by default. */
    fun applyBiblicallyAccurate(widthPx: Float = 1920f, heightPx: Float = 1080f, densityDpi: Float = 369f) =
        update { XmbLayoutPreset.computeForWindow(widthPx, heightPx, densityDpi) }

    fun reset() = update { XmbLayoutAdjust.DEFAULT }

    private fun update(transform: (XmbLayoutAdjust) -> XmbLayoutAdjust) {
        val next = XmbLayoutAdjustCodec.sanitize(transform(_adjust.value))
        _adjust.value = next
        persist {
            putFloat(KEY_SCALE, next.scale)
            putFloat(KEY_LEFT, next.barLeftFraction)
            putFloat(KEY_TOP, next.barTopFraction)
        }
    }

    private fun load(): XmbLayoutAdjust = XmbLayoutAdjustCodec.sanitize(
        XmbLayoutAdjust(
            scale = prefs.getFloat(KEY_SCALE, XmbLayoutAdjust.DEFAULT.scale),
            barLeftFraction = prefs.getFloat(KEY_LEFT, XmbLayoutAdjust.DEFAULT.barLeftFraction),
            barTopFraction = prefs.getFloat(KEY_TOP, XmbLayoutAdjust.DEFAULT.barTopFraction),
        ),
    )

    /** A read-only or unavailable backing store must not break the editor; the session still works. */
    private fun persist(write: Preferences.() -> Unit) {
        runCatching { prefs.write(); prefs.flush() }
    }

    companion object {
        const val NODE = "com/playfieldportal/studio/preview-adjust"
        private const val KEY_SCALE = "scale"
        private const val KEY_LEFT = "left"
        private const val KEY_TOP = "top"
        private const val KEY_ENABLED = "enabled"

        const val SCALE_MIN = XmbLayoutAdjust.SCALE_MIN
        const val SCALE_MAX = XmbLayoutAdjust.SCALE_MAX
        const val LEFT_MIN = XmbLayoutAdjust.LEFT_MIN
        const val LEFT_MAX = XmbLayoutAdjust.LEFT_MAX
        const val TOP_MIN = XmbLayoutAdjust.TOP_MIN
        const val TOP_MAX = XmbLayoutAdjust.TOP_MAX
        const val SCALE_STEP = 0.02f
        const val POSITION_STEP = 0.01f

        /** [value] clamped into [min]..[max] and snapped to the grid starting at [min]; null if not finite. */
        fun snap(value: Float, min: Float, max: Float, step: Float): Float? {
            if (!value.isFinite()) return null
            val steps = ((value.coerceIn(min, max) - min) / step).roundToInt()
            val snapped = min + steps * step
            // Round away float noise (1.0200001) so the value reads and stores cleanly.
            return ((snapped * 10_000f).roundToInt() / 10_000f).coerceIn(min, max)
        }
    }
}
