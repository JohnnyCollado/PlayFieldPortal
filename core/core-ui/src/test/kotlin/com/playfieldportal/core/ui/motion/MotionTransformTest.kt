package com.playfieldportal.core.ui.motion

import com.playfieldportal.themekit.MotionCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins [motionTransform], the pure math behind the crop-aware TextureView matrix. The matrix the
 * launcher builds is `setScale(scaleX, scaleY, viewW/2, viewH/2)` then `postTranslate(tx, ty)`,
 * applied to a frame the TextureView has already stretched to the view bounds.
 */
class MotionTransformTest {

    /** Where a source-frame fraction (fx, fy) lands in view coordinates under [t]. */
    private fun map(t: MotionTransform, viewW: Float, viewH: Float, fx: Float, fy: Float): Pair<Float, Float> {
        val u = fx * viewW
        val v = fy * viewH
        return Pair(
            (u - viewW / 2f) * t.scaleX + viewW / 2f + t.translateX,
            (v - viewH / 2f) * t.scaleY + viewH / 2f + t.translateY,
        )
    }

    @Test
    fun `null crop equals the legacy center-crop numbers`() {
        // 1920x1080 video in a 1000x1000 view: scale = max(1000/1920, 1000/1080) = 1000/1080.
        val t = motionTransform(1000f, 1000f, 1920f, 1080f, null)
        assertEquals(1920f * (1000f / 1080f) / 1000f, t.scaleX, 1e-5f)
        assertEquals(1080f * (1000f / 1080f) / 1000f, t.scaleY, 1e-5f)
        assertEquals(0f, t.translateX, 0f)
        assertEquals(0f, t.translateY, 0f)
    }

    @Test
    fun `full-frame crop on matching aspect is identity`() {
        val t = motionTransform(1920f, 1080f, 1920f, 1080f, MotionCrop(0f, 0f, 1f, 1f))
        assertEquals(1f, t.scaleX, 1e-5f)
        assertEquals(1f, t.scaleY, 1e-5f)
        assertEquals(0f, t.translateX, 1e-3f)
        assertEquals(0f, t.translateY, 1e-3f)
    }

    @Test
    fun `full-frame crop equals null crop`() {
        val a = motionTransform(1000f, 1000f, 1920f, 1080f, MotionCrop(0f, 0f, 1f, 1f))
        val b = motionTransform(1000f, 1000f, 1920f, 1080f, null)
        assertEquals(b.scaleX, a.scaleX, 1e-5f)
        assertEquals(b.scaleY, a.scaleY, 1e-5f)
        assertEquals(0f, a.translateX, 1e-3f)
        assertEquals(0f, a.translateY, 1e-3f)
    }

    @Test
    fun `off-center crop with the view's aspect maps rect corners to view corners`() {
        val crop = MotionCrop(0.5f, 0.25f, 0.5f, 0.5f) // square region of a square video
        val t = motionTransform(500f, 500f, 1000f, 1000f, crop)
        val (l, top) = map(t, 500f, 500f, crop.x, crop.y)
        val (r, bottom) = map(t, 500f, 500f, crop.x + crop.w, crop.y + crop.h)
        assertEquals(0f, l, 1e-2f)
        assertEquals(0f, top, 1e-2f)
        assertEquals(500f, r, 1e-2f)
        assertEquals(500f, bottom, 1e-2f)
    }

    @Test
    fun `wide crop of a wide video fills a wide view exactly`() {
        // 2000x1000 video, crop the right 60% x full height -> 1200x1000 region; view 1200x1000.
        val crop = MotionCrop(0.4f, 0f, 0.6f, 1f)
        val t = motionTransform(1200f, 1000f, 2000f, 1000f, crop)
        val (l, top) = map(t, 1200f, 1000f, crop.x, crop.y)
        val (r, bottom) = map(t, 1200f, 1000f, 1f, 1f)
        assertEquals(0f, l, 1e-2f)
        assertEquals(0f, top, 1e-2f)
        assertEquals(1200f, r, 1e-2f)
        assertEquals(1000f, bottom, 1e-2f)
    }

    @Test
    fun `mismatched aspect covers the view and never exposes outside the frame`() {
        // Tall crop region into a wide view: the region is cover-fitted, overflow would need pixels
        // outside the frame at the left edge, so the translate is clamped to keep the frame covering.
        val t = motionTransform(1000f, 500f, 1000f, 1000f, MotionCrop(0f, 0f, 0.1f, 1f))
        val (l, top) = map(t, 1000f, 500f, 0f, 0f)
        val (r, bottom) = map(t, 1000f, 500f, 1f, 1f)
        assert(l <= 1e-2f) { "left edge $l must not leave a gap" }
        assert(top <= 1e-2f) { "top edge $top must not leave a gap" }
        assert(r >= 1000f - 1e-2f) { "right edge $r must not leave a gap" }
        assert(bottom >= 500f - 1e-2f) { "bottom edge $bottom must not leave a gap" }
    }

    @Test
    fun `crop is ignored for non mp4 or webm paths`() {
        val crop = MotionCrop(0.1f, 0.1f, 0.5f, 0.5f)
        assertNull(cropForMotionPath("/d/wallpaper_1.gif", crop))
        assertNull(cropForMotionPath("/d/wallpaper_1.webp", crop))
        assertNull(cropForMotionPath("/d/wallpaper_1", crop))
        assertSame(crop, cropForMotionPath("/d/wallpaper_1.MP4", crop))
        assertSame(crop, cropForMotionPath("/d/wallpaper_1.webm", crop))
        assertNull(cropForMotionPath("/d/wallpaper_1.mp4", null))
    }
}
