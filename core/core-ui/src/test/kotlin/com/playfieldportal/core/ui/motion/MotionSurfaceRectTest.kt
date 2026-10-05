package com.playfieldportal.core.ui.motion

import com.playfieldportal.themekit.MotionCrop
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A SurfaceView cannot take a transform matrix, so the motion wallpaper sizes and places the view
 * itself and lets the screen clip it. [motionSurfaceRect] must put every frame pixel exactly where
 * the TextureView matrix ([motionTransform]) put it.
 */
class MotionSurfaceRectTest {

    private fun assertMatchesMatrix(viewW: Float, viewH: Float, videoW: Float, videoH: Float, crop: MotionCrop?) {
        val t = motionTransform(viewW, viewH, videoW, videoH, crop)
        val r = motionSurfaceRect(viewW, viewH, videoW, videoH, crop)
        // Under the matrix, frame corner (0,0) lands at (cx - cx*sx + tx, cy - cy*sy + ty), and the
        // frame spans viewW*sx by viewH*sy.
        val cx = viewW / 2f
        val cy = viewH / 2f
        assertEquals(cx - cx * t.scaleX + t.translateX, r.left, 0.01f)
        assertEquals(cy - cy * t.scaleY + t.translateY, r.top, 0.01f)
        assertEquals(viewW * t.scaleX, r.width, 0.01f)
        assertEquals(viewH * t.scaleY, r.height, 0.01f)
    }

    @Test
    fun `a matching aspect fills the screen exactly`() {
        val r = motionSurfaceRect(1920f, 1080f, 1920f, 1080f, null)
        assertEquals(MotionSurfaceRect(0f, 0f, 1920f, 1080f), r)
    }

    @Test
    fun `a wider video is center-cropped, overflowing left and right equally`() {
        val r = motionSurfaceRect(1920f, 1200f, 1920f, 1080f, null)
        assertEquals(1200f / 1080f * 1920f, r.width, 0.01f)
        assertEquals(1200f, r.height, 0.01f)
        assertEquals(-(r.width - 1920f) / 2f, r.left, 0.01f)
        assertEquals(0f, r.top, 0.01f)
    }

    @Test
    fun `it matches the TextureView matrix with and without a crop`() {
        assertMatchesMatrix(1920f, 1080f, 1280f, 720f, null)
        assertMatchesMatrix(1080f, 1920f, 1920f, 1080f, null)
        assertMatchesMatrix(1920f, 1080f, 1920f, 1080f, MotionCrop(0.1f, 0.2f, 0.5f, 0.5f))
        assertMatchesMatrix(1920f, 1200f, 1920f, 1080f, MotionCrop(0.6f, 0.0f, 0.4f, 0.6f))
    }
}
