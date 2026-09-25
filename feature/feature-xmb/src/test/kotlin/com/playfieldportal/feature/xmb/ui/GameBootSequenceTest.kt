package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GameBoot sequence's timing, tested without composing it.
 *
 * The drawing is not the interesting part — the claim worth defending is that the light lands on
 * the sound. Every light event's brightness is [loudnessAt] tapered across its window, so these
 * tests pin the envelope shape and the crest positions against the measured sample rather than
 * against how the animation happens to look on one device.
 */
class GameBootSequenceTest {

    private val tolerance = 0.0005f

    // --- The curtain the screen comes up through -------------------------------------------

    @Test
    fun `screen is black across the sound's leading silence`() {
        assertEquals(0f, rampAt(0f), tolerance)
        assertEquals(0f, rampAt(350f), tolerance)
        assertEquals(0f, rampAt(899f), tolerance)
    }

    @Test
    fun `screen is fully up by the time the first sweep starts`() {
        assertEquals(1f, rampAt(1_700f), tolerance)
        assertEquals(1f, rampAt(5_000f), tolerance)
        assertEquals(SWEEPS.first().startMs.toFloat(), 1_700f, tolerance)
    }

    @Test
    fun `ramp is half way up at its own midpoint`() {
        assertEquals(0.5f, rampAt(1_300f), tolerance)
    }

    @Test
    fun `ramp never leaves zero to one`() {
        var ms = -500f
        while (ms <= 6_000f) {
            val v = rampAt(ms)
            assertTrue("rampAt($ms) = $v is outside 0..1", v in 0f..1f)
            ms += 10f
        }
    }

    // --- The measured loudness -----------------------------------------------------------

    @Test
    fun `loudness is silent at both ends of the sample`() {
        assertEquals(0f, loudnessAt(0f), tolerance)
        assertEquals(0f, loudnessAt(SEQUENCE_MS.toFloat()), tolerance)
    }

    @Test
    fun `loudness clamps rather than wrapping past the end of the table`() {
        assertEquals(0f, loudnessAt(-1_000f), tolerance)
        assertEquals(0f, loudnessAt(9_999f), tolerance)
    }

    @Test
    fun `loudness returns the measured value at a table point`() {
        assertEquals(0.86f, loudnessAt(2_050f), tolerance)
        assertEquals(1.00f, loudnessAt(3_100f), tolerance)
        assertEquals(0.80f, loudnessAt(4_250f), tolerance)
    }

    @Test
    fun `loudness interpolates linearly between table points`() {
        // Half way from 900 ms (0.06) to 1 400 ms (0.26).
        assertEquals(0.16f, loudnessAt(1_150f), tolerance)
    }

    @Test
    fun `the sample's peak is the table's only maximum`() {
        var ms = 0f
        while (ms <= SEQUENCE_MS) {
            assertTrue("loudnessAt($ms) exceeds the normalized peak", loudnessAt(ms) <= 1f + tolerance)
            ms += 5f
        }
        assertEquals(1f, loudnessAt(3_100f), tolerance)
    }

    // --- The light events ------------------------------------------------------------------

    @Test
    fun `sweep windows are ordered, disjoint, and inside the sequence`() {
        SWEEPS.forEach { sweep ->
            assertTrue("${sweep} is inverted", sweep.startMs < sweep.endMs)
            assertTrue("${sweep} starts before the sequence", sweep.startMs >= 0)
            assertTrue("${sweep} outlives the sequence", sweep.endMs <= SEQUENCE_MS)
        }
        SWEEPS.zipWithNext { a, b ->
            assertTrue("$a and $b overlap", a.endMs <= b.startMs)
        }
    }

    @Test
    fun `the last light event clears before the screen sinks to black`() {
        // DECAY_START_MS is 4 400 and the sink runs 600 ms; the final sweep must be done before
        // the frame is fully black or its tail is drawn into something nobody sees.
        assertTrue(SWEEPS.last().endMs <= 5_000)
    }

    @Test
    fun `every sweep is dark at both edges of its window`() {
        SWEEPS.forEach { sweep ->
            assertEquals("$sweep is lit at its start", 0f, sweep.amplitudeAt(sweep.startMs.toFloat()), tolerance)
            assertEquals("$sweep is lit at its end", 0f, sweep.amplitudeAt(sweep.endMs.toFloat()), tolerance)
        }
    }

    @Test
    fun `every sweep is dark outside its own window`() {
        SWEEPS.forEach { sweep ->
            assertEquals(0f, sweep.amplitudeAt(sweep.startMs - 1f), tolerance)
            assertEquals(0f, sweep.amplitudeAt(sweep.endMs + 1f), tolerance)
        }
    }

    @Test
    fun `no light is drawn during the sound's leading silence`() {
        var ms = 0f
        while (ms <= 350f) {
            SWEEPS.forEach { assertEquals(0f, it.amplitudeAt(ms), tolerance) }
            ms += 10f
        }
    }

    @Test
    fun `each sweep crests on the attack it was placed for`() {
        // The scout sits exactly on the 2 050 ms hit. The other two are pulled a little off their
        // attacks by the window taper, which is the intended trade: the light arrives with the
        // sound rather than switching on at full brightness the instant the attack lands.
        assertEquals(2_050f, crestOf(SWEEPS[0]), 20f)
        assertEquals(3_066f, crestOf(SWEEPS[1]), 40f)
        assertEquals(4_259f, crestOf(SWEEPS[2]), 30f)
    }

    @Test
    fun `the main pass is the brightest of the three`() {
        val peaks = SWEEPS.map { sweep -> sweep.amplitudeAt(crestOf(sweep)) }
        assertTrue("the scout outshines the main pass", peaks[1] > peaks[0])
        assertTrue("the late lift outshines the main pass", peaks[1] > peaks[2])
    }

    @Test
    fun `the late lift stays faint`() {
        val main = SWEEPS[1].amplitudeAt(crestOf(SWEEPS[1]))
        val late = SWEEPS[2].amplitudeAt(crestOf(SWEEPS[2]))
        assertTrue("the late lift reads as a second event, not an answer", late < main * 0.5f)
    }

    /** The millisecond at which [sweep] is at its brightest, found by sampling its own window. */
    private fun crestOf(sweep: Sweep): Float {
        var best = sweep.startMs.toFloat()
        var bestAmp = -1f
        var ms = sweep.startMs.toFloat()
        while (ms <= sweep.endMs) {
            val amp = sweep.amplitudeAt(ms)
            if (amp > bestAmp) {
                bestAmp = amp
                best = ms
            }
            ms += 1f
        }
        return best
    }
}
