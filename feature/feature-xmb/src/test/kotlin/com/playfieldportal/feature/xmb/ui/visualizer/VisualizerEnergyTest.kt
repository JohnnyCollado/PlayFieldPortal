package com.playfieldportal.feature.xmb.ui.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope is the only part of the visualizer stack a unit test can hold: everything above it
 * is pixels. These pin the three properties the renderers are entitled to assume.
 */
class VisualizerEnergyTest {

    private fun ms(n: Long) = n * 1_000_000L

    // ── Range ─────────────────────────────────────────────────────────────────

    @Test
    fun `synthetic energy stays inside 0 to 1 across a long sweep`() {
        for (seed in listOf(0f, 0.13f, 0.5f, 0.99f)) {
            var t = 0f
            while (t < 120f) {
                val e = syntheticEnergy(t, seed)
                assertTrue("energy $e out of range at t=$t seed=$seed", e in 0f..1f)
                t += 0.017f
            }
        }
    }

    @Test
    fun `synthetic energy actually moves rather than sitting at the midpoint`() {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var t = 0f
        while (t < 30f) {
            val e = syntheticEnergy(t, 0.4f)
            min = minOf(min, e); max = maxOf(max, e)
            t += 0.05f
        }
        assertTrue("envelope range too narrow: $min..$max", max - min > 0.5f)
    }

    // ── Seed determinism ──────────────────────────────────────────────────────

    @Test
    fun `seed is stable for the same track id`() {
        assertEquals(visualizerSeed("track-42"), visualizerSeed("track-42"), 0f)
    }

    @Test
    fun `different track ids get different seeds`() {
        assertNotEquals(visualizerSeed("track-42"), visualizerSeed("track-43"))
    }

    @Test
    fun `seed is inside 0 to 1 and null collapses to zero`() {
        assertEquals(0f, visualizerSeed(null), 0f)
        assertEquals(0f, visualizerSeed(""), 0f)
        for (id in listOf("a", "content://x/y", "🎵", "-2147483648")) {
            assertTrue(visualizerSeed(id) in 0f..1f)
        }
    }

    @Test
    fun `a seeded envelope differs from an unseeded one`() {
        val plain = syntheticEnergy(3.3f, 0f)
        val seeded = syntheticEnergy(3.3f, visualizerSeed("Aerith's Theme"))
        assertNotEquals(plain, seeded)
    }

    // ── Decay ─────────────────────────────────────────────────────────────────

    @Test
    fun `decay is monotonic non-increasing and lands on the floor`() {
        var previous = decayedEnergy(from = 0.9f, msSince = 0L)
        assertEquals(0.9f, previous, 1e-4f)
        for (t in 0L..900L step 25L) {
            val now = decayedEnergy(from = 0.9f, msSince = t)
            assertTrue("energy rose at t=$t ($previous -> $now)", now <= previous + 1e-5f)
            previous = now
        }
        assertEquals(ENERGY_FLOOR, decayedEnergy(0.9f, ENERGY_DECAY_MS.toLong()), 1e-4f)
        assertEquals(ENERGY_FLOOR, decayedEnergy(0.9f, 10_000L), 1e-4f)
    }

    @Test
    fun `decay never drops below the floor even from below it`() {
        assertEquals(ENERGY_FLOOR, decayedEnergy(0f, 0L), 1e-4f)
        assertEquals(ENERGY_FLOOR, decayedEnergy(0f, 5_000L), 1e-4f)
    }

    @Test
    fun `source free-runs while playing and decays to the floor once paused`() {
        val source = VisualizerEnergySource(visualizerSeed("track-42"))
        // Playing: the level moves.
        val first = source.energyAt(0L, playing = true)
        val later = source.energyAt(ms(800), playing = true)
        assertNotEquals(first, later)

        // Paused: falls from wherever it stood, monotonically, to the floor.
        var previous = source.energyAt(ms(800), playing = false)
        for (t in 800L..1_600L step 50L) {
            val now = source.energyAt(ms(t), playing = false)
            assertTrue("energy rose while paused at t=$t", now <= previous + 1e-5f)
            previous = now
        }
        assertEquals(ENERGY_FLOOR, source.energyAt(ms(1_500), playing = false), 1e-4f)
    }

    @Test
    fun `resuming leaves the pause behind rather than continuing to decay`() {
        val source = VisualizerEnergySource(0.25f)
        source.energyAt(0L, playing = true)
        source.energyAt(ms(500), playing = false)
        source.energyAt(ms(900), playing = false)
        // Back on the free-running curve, which is not the floor at this phase.
        assertEquals(syntheticEnergy(1.4f, 0.25f), source.energyAt(ms(1_400), playing = true), 1e-4f)
    }

    @Test
    fun `emit floor sits above the resting floor so a paused ripple stops emitting`() {
        assertTrue(EMIT_FLOOR > ENERGY_FLOOR)
    }
}
