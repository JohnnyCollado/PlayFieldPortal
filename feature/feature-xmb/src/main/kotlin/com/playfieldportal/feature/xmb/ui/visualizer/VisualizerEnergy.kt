package com.playfieldportal.feature.xmb.ui.visualizer

import kotlin.math.abs
import kotlin.math.sin

/**
 * The synthetic envelope that stands in for a real signal, and the pause behaviour that carries
 * the whole illusion.
 *
 * Pure Kotlin on purpose — no Compose, no Android — so the parts a reviewer would actually doubt
 * (range, determinism, the decay curve) are unit-testable without a device.
 */

/** Resting level the envelope falls to when playback stops. Never zero: a dead field looks broken. */
const val ENERGY_FLOOR = 0.12f

/** How long the fall to [ENERGY_FLOOR] takes. Short enough to feel like a response, not a fade-out. */
const val ENERGY_DECAY_MS = 600f

/** Emission cutoff: below this the Ripple surface stops being disturbed (§2.2). */
const val EMIT_FLOOR = 0.20f

// Incommensurate periods, so the sum never repeats on a human timescale and the field never
// develops a visible beat. Whole-number ratios here are exactly what makes a fake look fake.
private const val PERIOD_A = 1.7f
private const val PERIOD_B = 2.9f
private const val PERIOD_C = 4.3f

private const val TAU = (2.0 * Math.PI).toFloat()

/**
 * Phase offset for [syntheticEnergy], derived from a track id.
 *
 * Deterministic by design: the same song gets the same character on every play, which is what
 * makes the field feel attached to the music rather than sprayed over it. Null (nothing loaded)
 * collapses to 0.
 */
fun visualizerSeed(trackId: String?): Float {
    if (trackId.isNullOrEmpty()) return 0f
    // A small stable hash folded into 0..1. abs() before the modulo, because Int.MIN_VALUE's
    // negation is itself and a negative seed would shift the phase the wrong way.
    var h = 0
    for (c in trackId) h = h * 31 + c.code
    return (abs(h.toLong()) % 10_000L).toFloat() / 10_000f
}

/**
 * Three sines at [PERIOD_A]/[PERIOD_B]/[PERIOD_C] seconds, summed and normalised into 0..1, with
 * the phase offset by [seed].
 */
fun syntheticEnergy(elapsedSec: Float, seed: Float): Float {
    val a = sin(TAU * (elapsedSec / PERIOD_A + seed))
    val b = sin(TAU * (elapsedSec / PERIOD_B + seed * 1.7f))
    val c = sin(TAU * (elapsedSec / PERIOD_C + seed * 2.3f))
    return (((a + b + c) / 3f) * 0.5f + 0.5f).coerceIn(0f, 1f)
}

/**
 * Linear fall from [from] to [ENERGY_FLOOR] over [ENERGY_DECAY_MS], clamped at both ends.
 *
 * Monotonic non-increasing for a non-decreasing [msSince], which is the property the test pins:
 * a curve that overshoots or oscillates on the way down reads as the field arguing with the pause.
 */
fun decayedEnergy(from: Float, msSince: Long): Float {
    val start = from.coerceIn(ENERGY_FLOOR, 1f)
    val t = (msSince / ENERGY_DECAY_MS).coerceIn(0f, 1f)
    return start + (ENERGY_FLOOR - start) * t
}

/**
 * Stateful envelope for one playing session: free-running while playing, decaying to the floor
 * from wherever it stood the moment playback stopped.
 *
 * Decay is a contract on the *source*, not on any one renderer. That is what makes it uniform for
 * free — every field reads the same falling number and settles in its own idiom (Portal slows and
 * dims, Ripple stops being disturbed and flattens) without any of them knowing what pause means.
 */
class VisualizerEnergySource(private val seed: Float) {
    private var originNanos: Long? = null
    private var pausedAtNanos: Long? = null
    private var energyAtPause: Float = ENERGY_FLOOR

    /** Current level at [timeNanos]. Call once per frame; [playing] drives the pause transition. */
    fun energyAt(timeNanos: Long, playing: Boolean): Float {
        val origin = originNanos ?: timeNanos.also { originNanos = it }
        val elapsedSec = (timeNanos - origin) / 1_000_000_000f

        if (playing) {
            pausedAtNanos = null
            return syntheticEnergy(elapsedSec, seed)
        }

        val pausedAt = pausedAtNanos ?: timeNanos.also {
            pausedAtNanos = it
            energyAtPause = syntheticEnergy(elapsedSec, seed)
        }
        return decayedEnergy(energyAtPause, (timeNanos - pausedAt) / 1_000_000L)
    }
}
