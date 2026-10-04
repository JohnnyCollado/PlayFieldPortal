package com.playfieldportal.studio.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mouse input on the preview, read the way the device reads touch: wheel notches and drag swipes become steps. */
class PreviewPointerTest {

    // ── Engaging ─────────────────────────────────────────────────────────────

    @Test
    fun `the first click only engages the preview - later clicks go through`() {
        val p = PreviewPointer()
        assertFalse(p.engaged)
        assertFalse(p.press(), "the engaging click must not reach the rows")
        assertTrue(p.engaged)
        assertTrue(p.press())
        assertTrue(p.press())
    }

    @Test
    fun `releasing hands the next click back to engaging`() {
        val p = PreviewPointer()
        p.press()
        p.release()
        assertFalse(p.engaged)
        assertFalse(p.press())
    }

    @Test
    fun `nothing scrolls or swipes until the preview is engaged`() {
        val p = PreviewPointer()
        assertEquals(emptyList(), p.wheel(0f, 3f, shift = false))
        assertEquals(emptyList(), p.drag(0f, -500f, stepPx = 50f))
    }

    // ── Wheel ────────────────────────────────────────────────────────────────

    private fun engaged() = PreviewPointer().also { it.press() }

    @Test
    fun `each wheel notch steps one row - down the list for a downward scroll`() {
        val p = engaged()
        assertEquals(listOf(PreviewNavAction.Down), p.wheel(0f, 1f, shift = false))
        assertEquals(listOf(PreviewNavAction.Up, PreviewNavAction.Up), p.wheel(0f, -2f, shift = false))
    }

    @Test
    fun `touchpad fractions add up to whole steps`() {
        val p = engaged()
        assertEquals(emptyList(), p.wheel(0f, 0.4f, shift = false))
        assertEquals(emptyList(), p.wheel(0f, 0.4f, shift = false))
        assertEquals(listOf(PreviewNavAction.Down), p.wheel(0f, 0.4f, shift = false))
    }

    @Test
    fun `shift or a sideways scroll steps categories`() {
        val p = engaged()
        assertEquals(listOf(PreviewNavAction.Right), p.wheel(0f, 1f, shift = true))
        assertEquals(listOf(PreviewNavAction.Left), p.wheel(0f, -1f, shift = true))
        assertEquals(listOf(PreviewNavAction.Right), p.wheel(1f, 0f, shift = false))
    }

    // ── Drag swipes ──────────────────────────────────────────────────────────

    @Test
    fun `dragging up moves down the list, one row per step, like a finger`() {
        val p = engaged()
        p.dragStart()
        assertEquals(emptyList(), p.drag(0f, -30f, stepPx = 50f))
        assertEquals(listOf(PreviewNavAction.Down), p.drag(0f, -30f, stepPx = 50f))
        assertEquals(listOf(PreviewNavAction.Down, PreviewNavAction.Down), p.drag(0f, -100f, stepPx = 50f))
        assertTrue(p.dragged)
    }

    @Test
    fun `dragging left moves to the next category`() {
        val p = engaged()
        p.dragStart()
        assertEquals(listOf(PreviewNavAction.Right), p.drag(-60f, 5f, stepPx = 50f))
        // 10 px left over from the first step: 60 back is one step the other way.
        assertEquals(listOf(PreviewNavAction.Left), p.drag(60f, 0f, stepPx = 50f))
    }

    @Test
    fun `a swipe locks to the axis it started on`() {
        val p = engaged()
        p.dragStart()
        p.drag(0f, -60f, stepPx = 50f)
        assertEquals(emptyList(), p.drag(-200f, 0f, stepPx = 50f), "a vertical swipe never changes category")
    }

    @Test
    fun `a click that barely moves is still a click`() {
        val p = engaged()
        p.dragStart()
        p.drag(2f, 3f, stepPx = 50f)
        assertFalse(p.dragged)
        p.dragStart()
        assertFalse(p.dragged, "each press starts a fresh swipe")
    }
}
