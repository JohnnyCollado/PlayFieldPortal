package com.playfieldportal.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the options panel's warning colour and its "more rows" arrows. */
class PspContextMenuLogicTest {

    private val deep = Color(0xFF0A1A4A)
    private val mid = Color(0xFF1E4FA0)
    // The shape of menuCursorFillFor: the accent lightened 20%, at 34% alpha.
    private val cursor = Color(0x576E99D9)

    // ── Contrast ────────────────────────────────────────────────────────────────

    @Test
    fun `contrast of black on white is 21 and of a colour on itself is 1`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(mid, mid), 0.0001)
    }

    @Test
    fun `the old red fails on the blue panel and the new warning colour passes in both states`() {
        val old = Color(0xFFFF7070)
        val panel = listOf(deep, mid)
        val states = panel + panel.map { cursor.compositeOver(it) }
        assertTrue("old red should fail somewhere", states.any { contrastRatio(old, it) < MinTextContrast })

        val warning = destructiveRowColor(states)
        states.forEach { background ->
            assertTrue(
                "$warning on $background = ${contrastRatio(warning, background)}",
                contrastRatio(warning, background) >= MinTextContrast,
            )
        }
    }

    @Test
    fun `the warning colour is opaque and stays reddish when the panel allows it`() {
        val warning = destructiveRowColor(listOf(deep))
        assertEquals(1f, warning.alpha, 0f)
        assertTrue("still leans red: $warning", warning.red > warning.blue)
    }

    @Test
    fun `a light panel gets a darkened warning colour`() {
        val light = Color(0xFFF2F2F2)
        val warning = destructiveRowColor(listOf(light))
        assertTrue(contrastRatio(warning, light) >= MinTextContrast)
        assertTrue("darker than the panel", contrastRatio(warning, Color.Black) < contrastRatio(light, Color.Black))
    }

    @Test
    fun `when nothing passes the answer is white on a dark panel`() {
        // White on this grey is 4.49:1, just under the bar, so no lightening of the base can pass.
        assertEquals(Color.White, destructiveRowColor(listOf(Color(0xFF777777))))
    }

    // ── More rows ───────────────────────────────────────────────────────────────

    @Test
    fun `a list that fits shows no arrows`() {
        val cue = menuMoreCue(firstIndex = 0, firstOffset = 0, lastIndex = 4, lastEnd = 200, viewportEnd = 260, total = 5)
        assertEquals(MenuMoreCue(above = false, below = false), cue)
    }

    @Test
    fun `at the top only the down arrow shows`() {
        val cue = menuMoreCue(firstIndex = 0, firstOffset = 0, lastIndex = 5, lastEnd = 300, viewportEnd = 300, total = 12)
        assertEquals(MenuMoreCue(above = false, below = true), cue)
    }

    @Test
    fun `in the middle both arrows show`() {
        val cue = menuMoreCue(firstIndex = 3, firstOffset = 0, lastIndex = 8, lastEnd = 300, viewportEnd = 300, total = 12)
        assertEquals(MenuMoreCue(above = true, below = true), cue)
    }

    @Test
    fun `at the bottom only the up arrow shows`() {
        val cue = menuMoreCue(firstIndex = 6, firstOffset = 0, lastIndex = 11, lastEnd = 300, viewportEnd = 300, total = 12)
        assertEquals(MenuMoreCue(above = true, below = false), cue)
    }

    @Test
    fun `a first row scrolled partly off the top counts as more above`() {
        val cue = menuMoreCue(firstIndex = 0, firstOffset = 10, lastIndex = 4, lastEnd = 250, viewportEnd = 300, total = 5)
        assertTrue(cue.above)
        assertTrue(!cue.below)
    }

    @Test
    fun `a last row cut off by the viewport counts as more below`() {
        val cue = menuMoreCue(firstIndex = 0, firstOffset = 0, lastIndex = 4, lastEnd = 340, viewportEnd = 300, total = 5)
        assertTrue(cue.below)
    }

    @Test
    fun `an empty list shows nothing`() {
        assertEquals(MenuMoreCue(false, false), menuMoreCue(0, 0, -1, 0, 300, 0))
    }
}
