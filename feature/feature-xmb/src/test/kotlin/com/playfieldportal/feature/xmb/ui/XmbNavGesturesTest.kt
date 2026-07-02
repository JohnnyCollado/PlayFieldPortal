package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit coverage for the pure swipe → discrete-step math (see [verticalSteps]). */
class XmbNavGesturesTest {

    private val stepPx = 72f
    private val flingPx = 420f
    private fun steps(distance: Float, velocity: Float) = verticalSteps(distance, velocity, stepPx, flingPx)

    @Test fun `tiny slow drag does not commit`() {
        // Below 0.6*step (=43.2) and below fling → no navigation.
        assertEquals(0, steps(distance = 10f, velocity = 0f))
        assertEquals(0, steps(distance = -12f, velocity = 100f))
    }

    @Test fun `one-row up swipe steps one down the list`() {
        // Up-swipe (negative distance) → positive step (move DOWN the list).
        assertEquals(1, steps(distance = -80f, velocity = 0f))
    }

    @Test fun `one-row down swipe steps one up the list`() {
        assertEquals(-1, steps(distance = 80f, velocity = 0f))
    }

    @Test fun `short but fast swipe still commits with a velocity bonus`() {
        // 30px < commit, but velocity above fling → base 1 + bonus 1 = 2 (up → +2).
        assertEquals(2, steps(distance = -30f, velocity = -1000f))
    }

    @Test fun `very fast swipe earns the larger bonus`() {
        // velocity > 3*fling (1260) → bonus 2; base 1 → 3.
        assertEquals(3, steps(distance = -80f, velocity = -1500f))
    }

    @Test fun `large swipe is capped at MAX_ITEM_SKIP`() {
        // 400/72 ≈ 6 rows, capped to 4.
        assertEquals(MAX_ITEM_SKIP, steps(distance = -400f, velocity = 0f))
        assertEquals(-MAX_ITEM_SKIP, steps(distance = 400f, velocity = 0f))
    }
}
