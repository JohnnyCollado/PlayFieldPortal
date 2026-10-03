package com.playfieldportal.core.ui.motion

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import kotlin.math.roundToInt

/**
 * Draws [DrawCrop]'s part of [inner] to fill its bounds: the whole image is laid out larger and
 * shifted, then clipped. Works the same for a still bitmap and a playing animation, which is the
 * point — nothing is decoded differently, so an animated crop keeps animating.
 */
class CropDrawable(inner: Drawable, private val crop: DrawCrop) : DrawableWrapper(inner) {

    override fun getIntrinsicWidth(): Int =
        drawable?.intrinsicWidth?.takeIf { it > 0 }?.let(crop::croppedWidth) ?: -1

    override fun getIntrinsicHeight(): Int =
        drawable?.intrinsicHeight?.takeIf { it > 0 }?.let(crop::croppedHeight) ?: -1

    // Not super: the wrapped drawable gets the enlarged, shifted bounds, not ours.
    override fun onBoundsChange(bounds: Rect) {
        val inner = crop.innerBounds(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
        )
        drawable?.setBounds(inner[0].roundToInt(), inner[1].roundToInt(), inner[2].roundToInt(), inner[3].roundToInt())
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipRect(bounds)
        super.draw(canvas)
        canvas.restoreToCount(saved)
    }
}

/**
 * An animated drawable that plays only while its [MotionGate] is open. Coil's painter calls
 * [start] when the image is shown; this remembers that it was asked and defers to the gate, which
 * re-decides as focus, position and the setting change. A drawable that is never allowed to start
 * holds its first frame; one that is stopped holds whatever frame it reached.
 *
 * [animatable] is the real animation inside [inner] (which may be wrapped in a [CropDrawable]).
 */
class GatedAnimationDrawable(
    inner: Drawable,
    private val animatable: Animatable,
    private val gate: MotionGate,
) : DrawableWrapper(inner), Animatable, MotionGate.Listener {

    private var requested = false

    init {
        gate.addListener(this)
    }

    override fun start() {
        requested = true
        sync()
    }

    override fun stop() {
        requested = false
        sync()
    }

    override fun isRunning(): Boolean = animatable.isRunning

    override fun onAnimateChanged(animate: Boolean) = sync()

    private fun sync() {
        val play = requested && gate.animate
        when {
            play && !animatable.isRunning -> animatable.start()
            !play && animatable.isRunning -> animatable.stop()
        }
    }
}
