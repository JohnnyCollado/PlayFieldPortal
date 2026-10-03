package com.playfieldportal.studio

import com.playfieldportal.themekit.MotionCrop

/** An integer pixel rectangle inside the source image. */
data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The Background crop frame: a ratio-locked rectangle over a source image or video frame,
 * in SOURCE PIXELS. One model drives the image bake, the video poster bake and the manifest's
 * normalized `motionCrop`, so they always describe the same region.
 *
 * Pure and immutable (no Compose): every operation returns a new, normalized frame. The ratio
 * lock is structural — only [x], [y] and [w] are stored and [h] is derived — so no operation
 * can break it. The frame is always clamped inside the source and between [minW] and [maxW].
 *
 * Build frames with [centered] / [fromMotionCrop]; the operations below always normalize.
 */
data class CropFrame(
    val sourceW: Int,
    val sourceH: Int,
    val fit: WallpaperPreset,
    val x: Float,
    val y: Float,
    val w: Float,
) {
    init {
        require(sourceW > 0 && sourceH > 0) { "source must be non-empty" }
    }

    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** Width / height the frame is locked to; ORIGINAL locks to the source's own ratio. */
    val ratio: Float
        get() = if (fit == WallpaperPreset.ORIGINAL) sourceW.toFloat() / sourceH
        else fit.width.toFloat() / fit.height

    val h: Float get() = w / ratio

    /** Largest frame of this ratio that fits the source. */
    val maxW: Float get() = minOf(sourceW.toFloat(), sourceH * ratio)

    /** Smallest frame: 5% of the source on both axes, matching [MotionCrop.MIN_SIZE]. */
    val minW: Float
        get() = minOf(maxW, maxOf(MotionCrop.MIN_SIZE * sourceW, MotionCrop.MIN_SIZE * sourceH * ratio))

    /** Slides the frame by ([dx], [dy]) source pixels, stopping at the source edges. */
    fun moved(dx: Float, dy: Float): CropFrame =
        if (!dx.isFinite() || !dy.isFinite()) this else normalized(x + dx, y + dy, w)

    /**
     * Zooms by [factor] (> 1 zooms in, i.e. a smaller frame) around ([anchorX], [anchorY]) in
     * source pixels — default the frame's center — which stays at the same spot in the frame.
     */
    fun zoomed(factor: Float, anchorX: Float = x + w / 2, anchorY: Float = y + h / 2): CropFrame {
        if (!factor.isFinite() || factor <= 0f || !anchorX.isFinite() || !anchorY.isFinite()) return this
        val newW = (w / factor).coerceIn(minW, maxW)
        val u = (anchorX - x) / w
        val v = (anchorY - y) / h
        val newH = newW / ratio
        return normalized(anchorX - u * newW, anchorY - v * newH, newW)
    }

    /**
     * Drags [corner] to the pointer at ([px], [py]) in source pixels; the opposite corner stays
     * pinned and the ratio holds (the larger of the two axis pulls wins).
     */
    fun resizedFromCorner(corner: Corner, px: Float, py: Float): CropFrame {
        if (!px.isFinite() || !py.isFinite()) return this
        val right = corner == Corner.TOP_RIGHT || corner == Corner.BOTTOM_RIGHT
        val bottom = corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT
        val ox = if (right) x else x + w
        val oy = if (bottom) y else y + h
        val roomW = if (right) sourceW - ox else ox
        val roomH = if (bottom) sourceH - oy else oy
        val limit = minOf(roomW, roomH * ratio, maxW)
        val wanted = maxOf(kotlin.math.abs(px - ox), kotlin.math.abs(py - oy) * ratio)
        val newW = wanted.coerceIn(minOf(minW, limit), limit)
        val newH = newW / ratio
        return normalized(if (right) ox else ox - newW, if (bottom) oy else oy - newH, newW)
    }

    /** Re-centers the frame in the source, keeping its size. */
    fun centeredInSource(): CropFrame = normalized((sourceW - w) / 2, (sourceH - h) / 2, w)

    /** Back to the largest centered frame of the current fit. */
    fun reset(): CropFrame = centered(sourceW, sourceH, fit)

    /** Switches the fit: the ratio re-locks, the frame keeps its center and (clamped) width. */
    fun withFit(newFit: WallpaperPreset): CropFrame {
        val cx = x + w / 2
        val cy = y + h / 2
        val next = copy(fit = newFit)
        val newW = w.coerceIn(next.minW, next.maxW)
        return next.normalized(cx - newW / 2, cy - newW / next.ratio / 2, newW)
    }

    /** The integer rect to cut from the source: inside its bounds and at least 1×1. */
    fun pixelRect(): CropRect {
        val left = Math.round(x).coerceIn(0, sourceW - 1)
        val top = Math.round(y).coerceIn(0, sourceH - 1)
        return CropRect(
            x = left,
            y = top,
            width = Math.round(w).coerceIn(1, sourceW - left),
            height = Math.round(h).coerceIn(1, sourceH - top),
        )
    }

    /** Size of the baked output: the fit's own size, or the cut pixels for ORIGINAL. */
    fun outputSize(): Pair<Int, Int> =
        if (fit == WallpaperPreset.ORIGINAL) pixelRect().let { it.width to it.height }
        else fit.width to fit.height

    /** The same region, normalized to the source frame — what the manifest's `motionCrop` carries. */
    fun toMotionCrop(): MotionCrop = MotionCrop(
        x = x / sourceW,
        y = y / sourceH,
        w = w / sourceW,
        h = h / sourceH,
    )

    private fun normalized(nx: Float, ny: Float, nw: Float): CropFrame {
        val cw = nw.coerceIn(minW, maxW)
        val ch = cw / ratio
        return copy(
            x = nx.coerceIn(0f, maxOf(0f, sourceW - cw)),
            y = ny.coerceIn(0f, maxOf(0f, sourceH - ch)),
            w = cw,
        )
    }

    companion object {
        /** The largest frame of [fit] centered in the source — the starting point of every crop. */
        fun centered(sourceW: Int, sourceH: Int, fit: WallpaperPreset): CropFrame {
            val base = CropFrame(sourceW, sourceH, fit, 0f, 0f, 0f)
            return base.normalized((sourceW - base.maxW) / 2, (sourceH - base.maxW / base.ratio) / 2, base.maxW)
        }

        /**
         * Rebuilds a frame from a saved normalized rect (a re-opened theme). A rect that doesn't
         * match [fit]'s ratio is repaired to the largest matching frame inside it, same center.
         */
        fun fromMotionCrop(sourceW: Int, sourceH: Int, fit: WallpaperPreset, crop: MotionCrop): CropFrame {
            val safe = crop.sanitized() ?: return centered(sourceW, sourceH, fit)
            val base = CropFrame(sourceW, sourceH, fit, 0f, 0f, 0f)
            val rw = safe.w * sourceW
            val rh = safe.h * sourceH
            val newW = minOf(rw, rh * base.ratio)
            val cx = safe.x * sourceW + rw / 2
            val cy = safe.y * sourceH + rh / 2
            return base.normalized(cx - newW / 2, cy - newW / base.ratio / 2, newW)
        }
    }
}
