package com.playfieldportal.studio.preview

import kotlin.math.abs
import kotlin.math.sign

/**
 * Mouse input on the live preview, read the way the device reads touch. Pure, so the rules are
 * unit-tested; [XmbPreviewCanvas] feeds it pointer events and dispatches what it returns.
 *
 * - The first press only [engaged] the preview (it takes the keyboard); it never reaches a row.
 *   Every later press goes through, until [release].
 * - Wheel notches step rows (down for a downward scroll); Shift or a sideways scroll steps
 *   categories. Touchpad fractions add up to whole steps.
 * - A press-and-drag is a swipe: it locks to the axis it starts on and steps once per `stepPx`,
 *   dragging up moving down the list and dragging left moving to the next category, as a finger does.
 */
class PreviewPointer {

    var engaged: Boolean = false
        private set

    /** True once the current press has moved far enough to be a swipe rather than a click. */
    val dragged: Boolean get() = axis != null

    private var wheelX = 0f
    private var wheelY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var axis: Axis? = null

    private enum class Axis { HORIZONTAL, VERTICAL }

    /** A press on the preview. False when it only engaged the preview and must not reach the rows. */
    fun press(): Boolean {
        if (!engaged) {
            engaged = true
            return false
        }
        return true
    }

    /** The preview gave the keyboard back (Cancel, Esc at the root, a click elsewhere). */
    fun release() {
        engaged = false
        wheelX = 0f
        wheelY = 0f
        dragStart()
    }

    fun wheel(deltaX: Float, deltaY: Float, shift: Boolean): List<PreviewNavAction> {
        if (!engaged) return emptyList()
        // Shift turns a vertical wheel sideways, as browsers and editors do.
        val (x, y) = if (shift && deltaX == 0f) deltaY to 0f else deltaX to deltaY
        wheelX += x
        wheelY += y
        return steps(wheelY, PreviewNavAction.Down, PreviewNavAction.Up).also { wheelY -= it.size * sign(wheelY) } +
            steps(wheelX, PreviewNavAction.Right, PreviewNavAction.Left).also { wheelX -= it.size * sign(wheelX) }
    }

    /** A new press: any earlier swipe is over. */
    fun dragStart() {
        dragX = 0f
        dragY = 0f
        axis = null
    }

    /** The pointer moved by ([dx], [dy]) px while pressed; [stepPx] is one row (or category) of travel. */
    fun drag(dx: Float, dy: Float, stepPx: Float): List<PreviewNavAction> {
        if (!engaged) return emptyList()
        dragX += dx
        dragY += dy
        if (axis == null) {
            val slop = stepPx * SLOP_SHARE
            axis = when {
                abs(dragY) > slop && abs(dragY) >= abs(dragX) -> Axis.VERTICAL
                abs(dragX) > slop -> Axis.HORIZONTAL
                else -> return emptyList()
            }
        }
        // Content follows the finger: dragging up (negative y) brings the next row in.
        return when (axis) {
            Axis.VERTICAL -> {
                val n = (abs(dragY) / stepPx).toInt()
                val sign = sign(dragY)
                dragY -= n * stepPx * sign
                List(n) { if (sign < 0) PreviewNavAction.Down else PreviewNavAction.Up }
            }
            else -> {
                val n = (abs(dragX) / stepPx).toInt()
                val sign = sign(dragX)
                dragX -= n * stepPx * sign
                List(n) { if (sign < 0) PreviewNavAction.Right else PreviewNavAction.Left }
            }
        }
    }

    private fun steps(total: Float, forward: PreviewNavAction, back: PreviewNavAction): List<PreviewNavAction> =
        List(abs(total).toInt()) { if (total > 0) forward else back }

    private companion object {
        /** Movement under this share of a step is a shaky click, not a swipe. */
        const val SLOP_SHARE = 0.2f
    }
}
