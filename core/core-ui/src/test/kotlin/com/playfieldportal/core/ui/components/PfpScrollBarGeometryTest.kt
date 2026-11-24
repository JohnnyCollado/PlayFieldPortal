package com.playfieldportal.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scroll bar's geometry, as pure arithmetic: no composition needed. */
class PfpScrollBarGeometryTest {

    @Test
    fun `content that fits shows no bar`() {
        assertFalse(scrollBarShown(maxValue = 0f))
        assertFalse(scrollBarShown(maxValue = -10f))
        assertEquals(0f, scrollThumbLength(track = 400f, viewport = 600f, maxValue = 0f, minThumb = 24f), 0f)
    }

    @Test
    fun `thumb length is the viewport share of the content`() {
        // viewport 500, max 500 -> content 1000 -> half the track
        assertEquals(200f, scrollThumbLength(track = 400f, viewport = 500f, maxValue = 500f, minThumb = 24f), 0.001f)
    }

    @Test
    fun `huge content keeps a minimum thumb`() {
        assertEquals(24f, scrollThumbLength(track = 400f, viewport = 500f, maxValue = 500_000f, minThumb = 24f), 0.001f)
    }

    @Test
    fun `minimum thumb never exceeds the track`() {
        assertEquals(10f, scrollThumbLength(track = 10f, viewport = 500f, maxValue = 500_000f, minThumb = 24f), 0.001f)
    }

    @Test
    fun `barely scrollable content gives a nearly full thumb`() {
        val len = scrollThumbLength(track = 400f, viewport = 1000f, maxValue = 1f, minThumb = 24f)
        assertTrue(len > 399f && len <= 400f)
    }

    @Test
    fun `thumb offset runs from zero to the end of the track`() {
        val track = 400f
        val thumb = 100f
        assertEquals(0f, scrollThumbOffset(track, thumb, value = 0f, maxValue = 900f), 0f)
        assertEquals(150f, scrollThumbOffset(track, thumb, value = 450f, maxValue = 900f), 0.001f)
        assertEquals(300f, scrollThumbOffset(track, thumb, value = 900f, maxValue = 900f), 0.001f)
    }

    @Test
    fun `thumb offset clamps an overscrolled value`() {
        assertEquals(0f, scrollThumbOffset(400f, 100f, value = -50f, maxValue = 900f), 0f)
        assertEquals(300f, scrollThumbOffset(400f, 100f, value = 1200f, maxValue = 900f), 0.001f)
    }

    @Test
    fun `thumb offset is zero when nothing scrolls`() {
        assertEquals(0f, scrollThumbOffset(400f, 400f, value = 0f, maxValue = 0f), 0f)
    }

    @Test
    fun `end arrows appear only where there is more content`() {
        assertFalse(canScrollBack(value = 0f))
        assertTrue(canScrollForward(value = 0f, maxValue = 100f))
        assertTrue(canScrollBack(value = 40f))
        assertTrue(canScrollForward(value = 40f, maxValue = 100f))
        assertTrue(canScrollBack(value = 100f))
        assertFalse(canScrollForward(value = 100f, maxValue = 100f))
        assertFalse(canScrollForward(value = 0f, maxValue = 0f))
    }

    @Test
    fun `dragging the thumb the full free track scrolls the full range`() {
        assertEquals(900f, thumbDragToScroll(dragPx = 300f, track = 400f, thumb = 100f, maxValue = 900f), 0.001f)
        assertEquals(450f, thumbDragToScroll(dragPx = 150f, track = 400f, thumb = 100f, maxValue = 900f), 0.001f)
    }

    @Test
    fun `dragging with no free track does nothing`() {
        assertEquals(0f, thumbDragToScroll(dragPx = 50f, track = 400f, thumb = 400f, maxValue = 900f), 0f)
    }

    @Test
    fun `lazy list estimate uses the average visible item`() {
        // 100 items of ~50px, viewport 500, scrolled to item 10 plus 20px
        val m = estimateLazyMetrics(
            firstIndex = 10, firstOffset = 20, totalItems = 100,
            visibleItems = 10, visibleExtent = 500, viewport = 500,
        )
        assertEquals(520f, m.value, 0.001f)
        assertEquals(4500f, m.maxValue, 0.001f)
        assertEquals(500f, m.viewport, 0f)
    }

    @Test
    fun `lazy estimate with nothing laid out shows no bar`() {
        val m = estimateLazyMetrics(0, 0, 0, 0, 0, 500)
        assertFalse(scrollBarShown(m.maxValue))
    }

    @Test
    fun `lazy estimate whose items all fit shows no bar`() {
        val m = estimateLazyMetrics(0, 0, 5, 5, 250, 500)
        assertFalse(scrollBarShown(m.maxValue))
    }
}
