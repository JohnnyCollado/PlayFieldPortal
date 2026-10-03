package com.playfieldportal.core.ui.motion

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A crop applied while drawing rather than baked into the file — how animated artwork keeps its
 * animation in a cropped slot (ICON0, HERO, …), since baking would flatten it to one still frame.
 * Normalized to the source image, 0..1, the same "left,top,right,bottom" the artwork record stores.
 */
data class DrawCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    private val widthFraction get() = right - left
    private val heightFraction get() = bottom - top

    /** The memory-cache key part: one entry per distinct framing. */
    val key: String get() = "%.4f,%.4f,%.4f,%.4f".format(Locale.US, left, top, right, bottom)

    fun asArray(): FloatArray = floatArrayOf(left, top, right, bottom)

    /**
     * Where the WHOLE image is laid out so the crop rect lands exactly on the given bounds; the
     * caller clips to those bounds. Returned as left, top, right, bottom.
     */
    fun innerBounds(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val innerWidth = (right - left) / widthFraction
        val innerHeight = (bottom - top) / heightFraction
        val innerLeft = left - this.left * innerWidth
        val innerTop = top - this.top * innerHeight
        return floatArrayOf(innerLeft, innerTop, innerLeft + innerWidth, innerTop + innerHeight)
    }

    fun croppedWidth(sourceWidth: Int): Int = (sourceWidth * widthFraction).roundToInt()
    fun croppedHeight(sourceHeight: Int): Int = (sourceHeight * heightFraction).roundToInt()

    /** How wide the source must decode for the cropped part to fill [displayWidth] pixels. */
    fun sourceWidthFor(displayWidth: Int): Int = (displayWidth / widthFraction).roundToInt()
    fun sourceHeightFor(displayHeight: Int): Int = (displayHeight / heightFraction).roundToInt()

    companion object {
        /** Reads a stored rect; null for anything malformed, inverted, out of range, or uncropped. */
        fun parse(rect: String?): DrawCrop? {
            val parts = rect?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
            if (parts.size != 4) return null
            val (l, t, r, b) = parts
            if (l < 0f || t < 0f || r > 1f || b > 1f || l >= r || t >= b) return null
            if (l == 0f && t == 0f && r == 1f && b == 1f) return null
            return DrawCrop(l, t, r, b)
        }
    }
}
