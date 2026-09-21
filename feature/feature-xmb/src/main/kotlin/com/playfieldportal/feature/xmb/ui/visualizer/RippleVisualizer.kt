package com.playfieldportal.feature.xmb.ui.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The water surface: concentric rings expand from one or two drift points, thinning and fading as
 * they grow.
 *
 * Timing here is specified, not tuned by eye. An earlier build ran a 2.6s ring life and read as
 * agitation rather than as water; this one expands about 40% slower and stretches the emission
 * interval to match. Both numbers move together on purpose — longer-lived rings at the old
 * emission rate would just crowd the surface and undo the calm.
 */

/** Hero ring budget. Concurrency is life ÷ interval, so this is ~4.2s / ~0.9s with headroom. */
const val RIPPLE_MAX_RINGS = 14

private const val RING_LIFE_SEC = 4.2f
private const val EMIT_INTERVAL_REST_SEC = 0.90f
private const val EMIT_INTERVAL_SWELL_SEC = 0.42f

/** Peak radius as a fraction of the larger screen dimension. */
private const val PEAK_RADIUS_FRACTION = 0.75f

/** Two drop points, drifting slowly so it never looks like a fixed sprinkler. */
private const val ORIGIN_COUNT = 2
private const val ORIGIN_DRIFT_SEC = 19f
private const val ORIGIN_SPREAD = 0.22f

private val RING_STROKE_NEAR = 2.6.dp
private val RING_STROKE_FAR = 0.7.dp

/**
 * The shared Ripple simulation. A fixed ring buffer of [RIPPLE_MAX_RINGS] slots; a ring is just
 * `(originX, originY, birthTime, birthEnergy)` and its radius is a function of age, so advancing
 * the field costs nothing but the emission check.
 */
class RippleField {
    private val originX = FloatArray(RIPPLE_MAX_RINGS)
    private val originY = FloatArray(RIPPLE_MAX_RINGS)
    private val birthSec = FloatArray(RIPPLE_MAX_RINGS) { -RING_LIFE_SEC }
    private val birthEnergy = FloatArray(RIPPLE_MAX_RINGS)

    private var nextSlot = 0
    private var lastEmitSec = -RING_LIFE_SEC
    private var nowSec = 0f

    /**
     * Emits at most one ring, on an energy threshold crossing with a refractory period — so a
     * swell produces *a drop* rather than a continuous smear.
     *
     * Emission is gated on `energy > EMIT_FLOOR`, which is Ripple's decay: as the envelope falls on
     * pause the drops simply stop. Rings already in flight finish expanding and fade out on their
     * own, and the surface settles to nothing over roughly one ring lifetime. A paused visualizer
     * that keeps emitting is the single clearest tell that nothing is listening.
     */
    fun advance(timeSec: Float, energy: Float) {
        nowSec = timeSec
        if (energy <= EMIT_FLOOR) return

        // Above the floor, a stronger envelope shortens the wait between drops.
        val swell = ((energy - EMIT_FLOOR) / (1f - EMIT_FLOOR)).coerceIn(0f, 1f)
        val interval = EMIT_INTERVAL_REST_SEC +
            (EMIT_INTERVAL_SWELL_SEC - EMIT_INTERVAL_REST_SEC) * swell
        if (timeSec - lastEmitSec < interval) return

        val origin = nextSlot % ORIGIN_COUNT
        // Slow Lissajous drift, in unit coordinates around the centre. Resolved to pixels at draw
        // time, so one simulation serves the hero and every tile whatever their aspect ratios.
        val phase = timeSec / ORIGIN_DRIFT_SEC + origin * 0.5f
        originX[nextSlot] = 0.5f + ORIGIN_SPREAD * cos(phase * 2f + origin)
        originY[nextSlot] = 0.5f + ORIGIN_SPREAD * sin(phase * 3f)
        birthSec[nextSlot] = timeSec
        birthEnergy[nextSlot] = energy
        lastEmitSec = timeSec
        nextSlot = (nextSlot + 1) % RIPPLE_MAX_RINGS
    }

    /** Age of the ring in [slot] as a 0..1 fraction of its life; >1 means it is gone. */
    internal fun ageAt(slot: Int): Float = (nowSec - birthSec[slot]) / RING_LIFE_SEC
    internal fun originXAt(slot: Int) = originX[slot]
    internal fun originYAt(slot: Int) = originY[slot]
    internal fun birthEnergyAt(slot: Int) = birthEnergy[slot]
    /** Newest first, so a tile drawing only `budget` rings drops the faintest ones. */
    internal fun slotsNewestFirst(): IntProgression = (nextSlot - 1 + RIPPLE_MAX_RINGS) downTo (nextSlot)
}

/**
 * Draws [field]'s live rings. Like Portal, one instance serves the hero and every tile at once.
 *
 * [tint] is read per draw rather than captured, so a theme change reaches the rings without
 * rebuilding the simulation underneath them. Portal needs no equivalent: its colour lives in the
 * sprite.
 */
class RippleVisualizer(
    private val field: RippleField,
    private val tint: () -> Color,
) : PfpVisualizer {
    override val id = VisualizerIds.RIPPLE
    override val label = "Ripple"

    override fun DrawScope.render(frame: VisualizerFrame, budget: Int, sprite: ImageBitmap) {
        val count = budget.coerceIn(0, RIPPLE_MAX_RINGS)
        if (count == 0) return

        val peak = max(size.width, size.height) * PEAK_RADIUS_FRACTION
        if (peak <= 0f) return
        val nearPx = RING_STROKE_NEAR.toPx()
        val farPx = RING_STROKE_FAR.toPx()
        // The surface as a whole brightens on a swell, so the field still answers the envelope in
        // the frames between drops.
        val energyAlpha = 0.55f + 0.45f * frame.energy.coerceIn(0f, 1f)

        var drawn = 0
        for (raw in field.slotsNewestFirst()) {
            if (drawn >= count) break
            val slot = ((raw % RIPPLE_MAX_RINGS) + RIPPLE_MAX_RINGS) % RIPPLE_MAX_RINGS
            val t = field.ageAt(slot)
            if (t < 0f || t >= 1f) continue

            // Alpha and width both decay with radius: the ring thins out as it spreads, which is
            // what separates water from a set of expanding hoops.
            val alpha = (1f - t) * (1f - t) *
                (0.25f + 0.75f * field.birthEnergyAt(slot)) * energyAlpha
            if (alpha <= 0.01f) continue

            drawCircle(
                color = tint(),
                radius = t * peak,
                center = Offset(field.originXAt(slot) * size.width, field.originYAt(slot) * size.height),
                alpha = alpha,
                style = Stroke(width = nearPx + (farPx - nearPx) * t),
            )
            drawn++
        }
    }
}
