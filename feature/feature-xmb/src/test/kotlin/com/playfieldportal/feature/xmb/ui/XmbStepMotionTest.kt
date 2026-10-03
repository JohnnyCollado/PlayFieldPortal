package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure step-motion rules (Item List Step Animation plan). This class grows with tasks 1.2 and 1.4. */
class XmbStepMotionTest {

    @Test
    fun `step spring constants are the category bar's slide values`() {
        assertEquals(Spring.StiffnessMediumLow, XmbStepSpring.STIFFNESS, 0f)
        assertEquals(Spring.DampingRatioNoBouncy, XmbStepSpring.DAMPING, 0f)
    }

    @Test
    fun `step spring factory carries the shared stiffness and damping`() {
        val spec = XmbStepSpring.spec<Float>()
        assertEquals(Spring.StiffnessMediumLow, spec.stiffness, 0f)
        assertEquals(Spring.DampingRatioNoBouncy, spec.dampingRatio, 0f)
    }

    @Test
    fun `step spring factory passes the visibility threshold through`() {
        assertEquals(0.01f, XmbStepSpring.spec(0.01f).visibilityThreshold)
        val dp: Dp = 0.1.dp
        assertEquals(dp, XmbStepSpring.spec(dp).visibilityThreshold)
        assertEquals(null, XmbStepSpring.spec<Float>().visibilityThreshold)
    }

    // --- Position math (px) ---

    /** Today's pinned at-rest anchors, from the rest-layout characterization (task 1.1). */
    private class Density(val belowTopPx: Int, val winTopPx: Int, val winPx: Int, val rowPx: Int) {
        val prevTopPx get() = winTopPx + (winPx - rowPx) / 2

        fun top(index: Int, p: Float, umdIndex: Int = -1, umdExtraPx: Int = 0) =
            itemRowTopPx(index, p, belowTopPx, winTopPx, winPx, rowPx, umdIndex, umdExtraPx)
    }

    private val at369 = Density(belowTopPx = 351, winTopPx = -9, winPx = 101, rowPx = 203)
    private val atTvdpi = Density(belowTopPx = 202, winTopPx = -5, winPx = 59, rowPx = 117)

    @Test
    fun `rest offsets equal today's for every index at both densities`() {
        for (density in listOf(at369, atTvdpi)) {
            for (sel in 0 until 12) {
                val window = itemRowWindow(rawSelectedIndex = sel, itemCount = 12, rowsBelow = 3, position = sel.toFloat())
                for (i in window) {
                    val expected = if (i < sel) density.prevTopPx else density.belowTopPx + (i - sel) * density.rowPx
                    assertEquals("sel=$sel i=$i", expected.toFloat(), density.top(i, sel.toFloat()), 0f)
                }
            }
        }
    }

    @Test
    fun `pinned previous row tops`() {
        assertEquals(-60, at369.prevTopPx)
        assertEquals(-34, atTvdpi.prevTopPx)
    }

    @Test
    fun `between the slots the top is strictly monotonic and continuous at both ends`() {
        val d = at369
        assertEquals(d.belowTopPx.toFloat(), d.top(5, 5f), 0f)
        assertEquals(d.prevTopPx.toFloat(), d.top(5, 6f), 0f)
        assertEquals((d.belowTopPx + d.prevTopPx) / 2f, d.top(5, 5.5f), 0.001f)
        var last = d.top(5, 5f)
        for (step in 1..20) {
            val y = d.top(5, 5f + step / 20f)
            assertTrue("step $step", y < last)
            last = y
        }
    }

    @Test
    fun `beyond the slots rows move linearly at rowPx per row and join the middle segment`() {
        val d = at369
        // Below the selected slot (d > 0).
        assertEquals(d.belowTopPx + 2.5f * d.rowPx, d.top(7, 4.5f), 0.001f)
        // Above the previous slot (d < -1).
        assertEquals(d.prevTopPx - 1.5f * d.rowPx, d.top(3, 5.5f), 0.001f)
        // Continuous across d = 0 and d = -1 from both sides.
        assertEquals(d.top(5, 5f), d.top(5, 5f - 0.001f), 0.5f)
        assertEquals(d.top(5, 6f), d.top(5, 6f + 0.001f), 0.5f)
    }

    @Test
    fun `rows after the UMD row get the extra and the others do not`() {
        val d = at369
        assertEquals(d.top(5, 4f) + 40, d.top(5, 4f, umdIndex = 4, umdExtraPx = 40), 0f)
        assertEquals(d.top(4, 4f), d.top(4, 4f, umdIndex = 4, umdExtraPx = 40), 0f)
        assertEquals(d.top(3, 4f), d.top(3, 4f, umdIndex = 4, umdExtraPx = 40), 0f)
        assertEquals(d.top(6, 4f), d.top(6, 4f, umdIndex = 4, umdExtraPx = 0), 0f)
    }

    @Test
    fun `a row after the UMD row carries the extra through the middle segment, so crossing d = 0 does not jump`() {
        val d = at369
        // Row 5 follows the UMD row at 4. Just inside the below column and just inside the middle
        // segment it must be at the same place; at d = -1 it settles on the previous slot.
        assertEquals(d.top(5, 5f, umdIndex = 4, umdExtraPx = 40), d.top(5, 5.001f, umdIndex = 4, umdExtraPx = 40), 0.5f)
        assertEquals(d.belowTopPx + 40f, d.top(5, 5f, umdIndex = 4, umdExtraPx = 40), 0f)
        assertEquals(d.prevTopPx.toFloat(), d.top(5, 6f, umdIndex = 4, umdExtraPx = 40), 0f)
        // The UMD row itself never carries it.
        assertEquals(d.top(4, 4.5f), d.top(4, 4.5f, umdIndex = 4, umdExtraPx = 40), 0f)
    }

    // --- Window ---

    @Test
    fun `window at rest is today's set`() {
        assertEquals(0..2, itemRowWindow(0, 12, 3, 0f))
        assertEquals(4..7, itemRowWindow(5, 12, 3, 5f))
        assertEquals(10..11, itemRowWindow(11, 12, 3, 11f))
        assertEquals(0..2, itemRowWindow(-1, 12, 3, 0f))
    }

    @Test
    fun `window has no previous row when the raw index is past the last item`() {
        assertEquals(11..11, itemRowWindow(40, 12, 3, 11f))
        assertEquals(10..11, itemRowWindow(11, 12, 3, 11f))
        assertEquals(11..11, itemRowWindow(12, 12, 3, 11f))
    }

    @Test
    fun `window in transit covers floor minus one to ceil plus rowsBelow minus one`() {
        assertEquals(4..8,itemRowWindow(6, 20, 3, 5.4f))
        assertEquals(0..3, itemRowWindow(1, 20, 3, 0.5f))
        assertEquals(17..19, itemRowWindow(19, 20, 3, 18.5f))
        assertEquals(IntRange.EMPTY, itemRowWindow(0, 0, 3, 0f))
    }

    // --- Clip ---

    @Test
    fun `clip is none at and below the selected slot`() {
        assertEquals(ItemRowClip.None, itemRowClip(0f, rowsBelow = 3, atRest = true))
        assertEquals(ItemRowClip.None, itemRowClip(2f, rowsBelow = 3, atRest = true))
    }

    @Test
    fun `clip is the window at and above the previous slot`() {
        assertEquals(ItemRowClip.Window, itemRowClip(-1f, rowsBelow = 3, atRest = true))
        assertEquals(ItemRowClip.Window, itemRowClip(-1.5f, rowsBelow = 3, atRest = false))
    }

    @Test
    fun `clip between the slots is below-or-window`() {
        assertEquals(ItemRowClip.BelowOrWindow, itemRowClip(-0.5f, rowsBelow = 3, atRest = false))
    }

    @Test
    fun `clip limits rows past the last whole slot only in transit`() {
        assertEquals(ItemRowClip.None, itemRowClip(2f, rowsBelow = 3, atRest = false))
        assertEquals(ItemRowClip.BottomLimit, itemRowClip(2.5f, rowsBelow = 3, atRest = false))
        assertEquals(ItemRowClip.BottomLimit, itemRowClip(3f, rowsBelow = 3, atRest = false))
        assertEquals(ItemRowClip.None, itemRowClip(2.5f, rowsBelow = 3, atRest = true))
    }

    // --- Snap rules ---

    private fun inputs(
        raw: Int = 5,
        count: Int = 12,
        token: Int = 0,
        key: Any? = "root",
        moving: Boolean = false,
    ) = StepInputs(raw, count, token, key, moving)

    @Test
    fun `first composition snaps`() {
        assertEquals(StepMotion.Snap(5), xmbStepMotion(null, inputs(raw = 5)))
    }

    @Test
    fun `target is clamped into the list`() {
        assertEquals(StepMotion.Snap(11), xmbStepMotion(null, inputs(raw = 40)))
        assertEquals(StepMotion.Snap(0), xmbStepMotion(null, inputs(raw = -1)))
        assertEquals(StepMotion.Snap(0), xmbStepMotion(null, inputs(raw = 0, count = 0)))
    }

    @Test
    fun `outgoing copy and re-entry snap`() {
        assertEquals(StepMotion.Snap(0), xmbStepMotion(inputs(raw = 5), inputs(raw = -1)))
        assertEquals(StepMotion.Snap(5), xmbStepMotion(inputs(raw = -1), inputs(raw = 5)))
    }

    @Test
    fun `token, column key, moving and an empty list each snap on a one-row step`() {
        val prev = inputs(raw = 5)
        assertEquals(StepMotion.Snap(6), xmbStepMotion(prev, inputs(raw = 6, token = 1)))
        assertEquals(StepMotion.Snap(6), xmbStepMotion(prev, inputs(raw = 6, key = "other")))
        assertEquals(StepMotion.Snap(6), xmbStepMotion(prev, inputs(raw = 6, moving = true)))
        assertEquals(StepMotion.Snap(6), xmbStepMotion(inputs(raw = 5, moving = true), inputs(raw = 6)))
        assertEquals(StepMotion.Snap(8), xmbStepMotion(inputs(raw = 0, count = 0), inputs(raw = 8, count = 9)))
        assertEquals(StepMotion.Snap(0), xmbStepMotion(inputs(raw = 5), inputs(raw = 0, count = 0)))
    }

    @Test
    fun `jump boundary is three rows`() {
        assertEquals(3, LONG_JUMP_ROWS)
        assertEquals(StepMotion.Glide(6), xmbStepMotion(inputs(raw = 5), inputs(raw = 6)))
        assertEquals(StepMotion.Glide(8), xmbStepMotion(inputs(raw = 5), inputs(raw = 8)))
        assertEquals(StepMotion.Glide(2), xmbStepMotion(inputs(raw = 5), inputs(raw = 2)))
        assertEquals(StepMotion.SnapThenGlide(from = 8, target = 9), xmbStepMotion(inputs(raw = 5), inputs(raw = 9)))
        assertEquals(StepMotion.SnapThenGlide(from = 2, target = 1), xmbStepMotion(inputs(raw = 5), inputs(raw = 1)))
    }

    @Test
    fun `jump is measured between clamped targets`() {
        // 11 -> 40 clamps to 11 -> 11, no jump at all.
        assertEquals(StepMotion.Glide(11), xmbStepMotion(inputs(raw = 11), inputs(raw = 40)))
    }

    @Test
    fun `held repeat never snaps`() {
        var prev = inputs(raw = 0, count = 40)
        for (raw in 1..20) {
            val next = inputs(raw = raw, count = 40)
            assertEquals(StepMotion.Glide(raw), xmbStepMotion(prev, next))
            prev = next
        }
    }

    // --- Section landing ---

    private fun inputs(index: Int, count: Int = 8, landing: Int = 0) = StepInputs(
        rawSelectedIndex = index,
        itemCount = count,
        scrollToTopToken = 0,
        columnKey = "games/root",
        moving = false,
        landingToken = landing,
    )

    @Test
    fun `landing on a section's default row snaps instead of gliding`() {
        assertEquals(StepMotion.Snap(3), xmbStepMotion(inputs(0), inputs(3, landing = 1)))
        // A one-row landing would glide without the token; it still snaps.
        assertEquals(StepMotion.Snap(1), xmbStepMotion(inputs(0), inputs(1, landing = 1)))
    }

    @Test
    fun `steps after a landing glide as before`() {
        assertEquals(StepMotion.Glide(4), xmbStepMotion(inputs(3, landing = 1), inputs(4, landing = 1)))
    }

    // --- Rewind: the hand-off across the catbar ---

    private val now = 10_000L

    @Test
    fun `down hands the focused row up across the bar at once, and the column follows after its lag`() {
        val c = handOffFor(fromIndex = 3, toIndex = 4, nowMs = now, lastStepMs = null, fromD = 0f)!!
        assertEquals(Crossing(row = 3, down = true, fromD = 0f, toD = -1f, startMs = now), c)
        assertEquals(now + XmbHandOff.COLUMN_LAG_MS, columnStartMs(c, now))
        // No row is focused until the column moves: the leaving row dims at once.
        assertEquals(now + XmbHandOff.COLUMN_LAG_MS, focusStartMs(c, now))
    }

    @Test
    fun `up rewinds - the column moves at once and the row above drops in after its delay`() {
        val c = handOffFor(fromIndex = 4, toIndex = 3, nowMs = now, lastStepMs = null, fromD = -1f)!!
        assertEquals(Crossing(row = 3, down = false, fromD = -1f, toD = 0f, startMs = now + XmbHandOff.DROP_DELAY_MS), c)
        assertEquals(now, columnStartMs(c, now))
        assertEquals(now + XmbHandOff.DROP_DELAY_MS, focusStartMs(c, now))
    }

    @Test
    fun `held repeats, jumps and plain glides hand nothing off`() {
        assertNull(handOffFor(3, 4, now, lastStepMs = now - XmbHandOff.HOLD_GAP_MS + 1, fromD = 0f))
        assertNull(handOffFor(3, 6, now, lastStepMs = null, fromD = 0f))
        assertNull(handOffFor(3, 3, now, lastStepMs = null, fromD = 0f))
        assertEquals(now, columnStartMs(null, now))
        assertEquals(now, focusStartMs(null, now))
        // A pause longer than the repeat gap is a fresh press again.
        assertNotNull(handOffFor(3, 4, now, lastStepMs = now - XmbHandOff.HOLD_GAP_MS, fromD = 0f))
    }

    @Test
    fun `the crossing row is above the bar within the crossing time, whatever the column does`() {
        val c = handOffFor(3, 4, now, null, fromD = 0f)!!
        assertEquals(0f, crossingD(c, now, columnD = 0f), 1e-4f)
        // Constant speed: exactly halfway at half time.
        assertEquals(-0.5f, crossingD(c, now + XmbHandOff.CROSS_MS / 2, columnD = 0f), 1e-4f)
        assertEquals(-1f, crossingD(c, now + XmbHandOff.CROSS_MS, columnD = 0f), 1e-4f)
    }

    @Test
    fun `a risen row rides on up with the column when the next step carries it further`() {
        val c = handOffFor(3, 4, now, null, fromD = 0f)!!
        assertEquals(-1.6f, crossingD(c, now + XmbHandOff.CROSS_MS, columnD = -1.6f), 1e-4f)
        assertTrue(crossingDone(c, now + XmbHandOff.CROSS_MS, columnD = -1f))
        assertFalse(crossingDone(c, now + XmbHandOff.CROSS_MS, columnD = -0.4f))
        assertFalse(crossingDone(c, now + XmbHandOff.CROSS_MS / 2, columnD = -1f))
    }

    @Test
    fun `on up the row above holds its place until it drops, then never trails the column`() {
        val c = handOffFor(4, 3, now, null, fromD = -1f)!!
        // The column is already moving it down, but it waits above the bar.
        assertEquals(-1f, crossingD(c, now + 30, columnD = -0.8f), 1e-4f)
        assertEquals(0f, crossingD(c, c.startMs + XmbHandOff.CROSS_MS, columnD = -0.3f), 1e-4f)
        assertTrue(crossingDone(c, c.startMs + XmbHandOff.CROSS_MS, columnD = 0f))
        assertFalse(crossingDone(c, c.startMs + XmbHandOff.CROSS_MS, columnD = -0.3f))
    }

    @Test
    fun `a crossing row shows in the catbar band only while it crosses, settling back to the half split`() {
        assertEquals(0f, transitBandAlpha(0f), 0f)
        assertEquals(0f, transitBandAlpha(-1f), 0f)
        assertEquals(1f, transitBandAlpha(-0.5f), 0f)
        assertTrue("fades in the last stretch", transitBandAlpha(-0.95f) in 0.01f..0.99f)
        assertTrue("fades in as it leaves the slot", transitBandAlpha(-0.02f) in 0.01f..0.99f)
        assertEquals(0f, transitBandAlpha(0.5f), 0f)
        assertEquals(0f, transitBandAlpha(-1.5f), 0f)
    }

    // --- The item list's glide: constant speed, faster than the bar's spring ---

    @Test
    fun `a glide takes the same time per row - one row, half a row`() {
        assertEquals(XmbGlide.MS_PER_ROW.toInt(), glideDurationMs(from = 3f, to = 4f))
        assertEquals((XmbGlide.MS_PER_ROW / 2).toInt(), glideDurationMs(from = 3.5f, to = 4f))
        assertEquals(XmbGlide.MS_PER_ROW.toInt(), glideDurationMs(from = 5f, to = 4f))
        assertEquals(0, glideDurationMs(from = 4f, to = 4f))
    }

    @Test
    fun `a held run that falls behind catches up within the cap instead of trailing the thumb`() {
        assertEquals(XmbGlide.MAX_MS.toInt(), glideDurationMs(from = 0f, to = 6f))
    }

    @Test
    fun `the glide is faster than the spring it replaces and the hand-off is faster still`() {
        assertTrue(XmbGlide.MS_PER_ROW <= 120)
        assertTrue(XmbHandOff.CROSS_MS < XmbGlide.MS_PER_ROW)
        assertTrue(XmbHandOff.COLUMN_LAG_MS < XmbHandOff.CROSS_MS)
    }

    @Test
    fun `every row crossing the catbar draws in the band, not only a hand-off`() {
        // Glide and held repeats move rows through the band too; at rest no row is in it.
        assertEquals(1f, transitBandAlpha(-0.5f), 0f)
        assertEquals(0f, transitBandAlpha(0f), 0f)
        assertEquals(0f, transitBandAlpha(-1f), 0f)
    }
}
