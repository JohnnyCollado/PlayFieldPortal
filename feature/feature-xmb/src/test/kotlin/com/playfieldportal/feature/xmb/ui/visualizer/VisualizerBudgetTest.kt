package com.playfieldportal.feature.xmb.ui.visualizer

import com.playfieldportal.feature.xmb.ui.codecLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget table and the codec chip: the two bits of the visualizer work that are plain data and
 * would otherwise only be checked by looking at a screen.
 */
class VisualizerBudgetTest {

    @Test
    fun `off is first in the picker so the cheapest option is under the cursor by default`() {
        assertEquals(VisualizerIds.OFF, VisualizerIds.ALL.first())
        assertEquals(listOf("off", "portal", "ripple"), VisualizerIds.ALL)
    }

    @Test
    fun `off costs no draw at either size`() {
        assertEquals(0, heroBudget(VisualizerIds.OFF, stripOpen = false))
        assertEquals(0, heroBudget(VisualizerIds.OFF, stripOpen = true))
        assertEquals(0, tileBudget(VisualizerIds.OFF))
    }

    @Test
    fun `an unknown id is treated as off rather than crashing a draw`() {
        assertEquals(0, heroBudget("spectrum", stripOpen = false))
        assertEquals(0, tileBudget("spectrum"))
    }

    @Test
    fun `the hero drops when the strip opens, for every field`() {
        for (id in listOf(VisualizerIds.PORTAL, VisualizerIds.RIPPLE)) {
            val closed = heroBudget(id, stripOpen = false)
            val open = heroBudget(id, stripOpen = true)
            assertTrue("$id did not drop when the strip opened", open < closed)
            assertTrue("$id has no budget at all", open > 0)
        }
    }

    @Test
    fun `hero budgets never exceed the arrays backing them`() {
        assertTrue(heroBudget(VisualizerIds.PORTAL, false) <= PORTAL_MAX_PARTICLES)
        assertTrue(heroBudget(VisualizerIds.RIPPLE, false) <= RIPPLE_MAX_RINGS)
    }

    @Test
    fun `a tile is cheaper than an open-strip hero`() {
        for (id in listOf(VisualizerIds.PORTAL, VisualizerIds.RIPPLE)) {
            assertTrue(tileBudget(id) < heroBudget(id, stripOpen = true))
            assertTrue(tileBudget(id) > 0)
        }
    }

    @Test
    fun `labels are the ones the strip prints`() {
        assertEquals("Off", VisualizerIds.labelFor(VisualizerIds.OFF))
        assertEquals("Portal", VisualizerIds.labelFor(VisualizerIds.PORTAL))
        assertEquals("Ripple", VisualizerIds.labelFor(VisualizerIds.RIPPLE))
        assertEquals("Off", VisualizerIds.labelFor("nonsense"))
    }

    // ── Codec chip ────────────────────────────────────────────────────────────

    @Test
    fun `codec chip names the common formats`() {
        assertEquals("MP3", codecLabel("audio/mpeg"))
        assertEquals("FLAC", codecLabel("audio/flac"))
        assertEquals("FLAC", codecLabel("audio/x-flac"))
        assertEquals("AAC", codecLabel("audio/mp4"))
        assertEquals("OGG", codecLabel("audio/ogg"))
        assertEquals("OPUS", codecLabel("audio/opus"))
        assertEquals("WAV", codecLabel("audio/x-wav"))
    }

    @Test
    fun `an unscanned or malformed mime type shows no chip at all`() {
        assertNull(codecLabel(null))
        assertNull(codecLabel(""))
        assertNull(codecLabel("audio/"))
        assertNull(codecLabel("audio"))
    }

    @Test
    fun `an unknown subtype falls back to a short uppercase label`() {
        assertEquals("AIFF", codecLabel("audio/aiff"))
        assertEquals("SOMETH", codecLabel("audio/something-very-long"))
    }
}
