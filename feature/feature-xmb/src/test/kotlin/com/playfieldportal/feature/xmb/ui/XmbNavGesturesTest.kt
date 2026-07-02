package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit coverage for the pure scrub/fling step math (see [consumeWholeSteps] / [flingBonusSteps]). */
class XmbNavGesturesTest {

    private val stepPx = 64f
    private val flingPx = 420f

    // ── Live scrubbing: whole steps per accumulated travel ─────────────────────

    @Test fun `travel below one step yields nothing`() {
        assertEquals(0, consumeWholeSteps(30f, stepPx))
        assertEquals(0, consumeWholeSteps(-63f, stepPx))
    }

    @Test fun `each step distance crossed yields one step, remainder carries`() {
        assertEquals(1, consumeWholeSteps(64f, stepPx))
        assertEquals(1, consumeWholeSteps(120f, stepPx))   // 1 step + 56px remainder
        assertEquals(-2, consumeWholeSteps(-130f, stepPx)) // opposite direction
    }

    @Test fun `long continuous slide yields many steps`() {
        // A 5-row drag scrubs 5 steps — the "smooth slide" behaviour (no per-gesture cap).
        assertEquals(5, consumeWholeSteps(5 * stepPx, stepPx))
    }

    @Test fun `remainder pattern ticks continuously across events`() {
        // Simulate incremental drag deltas the way the detector consumes them.
        var acc = 0f
        var steps = 0
        listOf(40f, 40f, 40f, 40f).forEach { d ->   // 160px total = 2 steps + 32 remainder
            acc += d
            val whole = consumeWholeSteps(acc, stepPx)
            steps += whole
            acc -= whole * stepPx
        }
        assertEquals(2, steps)
        assertEquals(32f, acc)
    }

    // ── Release fling bonus ─────────────────────────────────────────────────────

    @Test fun `slow release grants no bonus`() {
        assertEquals(0, flingBonusSteps(200f, flingPx))
        assertEquals(0, flingBonusSteps(-300f, flingPx))
    }

    @Test fun `fast up-flick grants downward bonus, capped at two`() {
        assertEquals(1, flingBonusSteps(-800f, flingPx))    // > fling
        assertEquals(2, flingBonusSteps(-1500f, flingPx))   // > 3×fling
        assertEquals(2, flingBonusSteps(-9999f, flingPx))   // still capped
    }

    @Test fun `fast down-flick grants upward bonus`() {
        assertEquals(-1, flingBonusSteps(800f, flingPx))
        assertEquals(-2, flingBonusSteps(1500f, flingPx))
    }
}
