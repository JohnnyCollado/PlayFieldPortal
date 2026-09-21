package com.playfieldportal.feature.xmb.ui.visualizer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The house field, and the one the app is named for: circles orbit a centre and spiral outward,
 * brightening at the core and dissolving as they travel, so the field reads as a mouth opening
 * away from the viewer.
 */

/** Hero budget at its largest. The array is sized once; every smaller budget is a prefix of it. */
const val PORTAL_MAX_PARTICLES = 260

private const val RING_COUNT = 5
private const val GOLDEN_ANGLE = 2.399963f          // radians; what stops the rings reading as stripes
private const val TAU = (2.0 * Math.PI).toFloat()

// Radial travel, in fractions of the rim per second.
private const val RADIAL_BASE = 0.14f
private const val RADIAL_ENERGY = 0.22f
// Angular travel: constant plus an energy term, so a swell makes the whole ring *turn*
// fractionally faster rather than jumping.
private const val SPIN_BASE = 0.32f
private const val SPIN_ENERGY = 0.38f

// Throat to rim, before the tile scale below. Sized so the field still reads as circles at hero
// size rather than as a haze of dots: the throat stays small and bright, and the rim — where the
// alpha falloff has already thinned the particles — gets enough diameter to still be a shape.
private val MIN_PARTICLE = 3.dp
private val MAX_PARTICLE = 12.dp

/** Rim radius a hero-sized field has, used to scale particle sizes down for a picker tile. */
private val REFERENCE_RIM = 170.dp

/**
 * The shared Portal simulation: one array, advanced once per frame by [VisualizerHost] however
 * many tiles happen to be drawing it.
 *
 * Particles are seeded on [RING_COUNT] rings with a golden-angle offset per ring; each carries its
 * own speed jitter so the rings smear into a field within a second or two instead of marching in
 * lockstep forever.
 */
class PortalField {
    private val angle = FloatArray(PORTAL_MAX_PARTICLES)
    private val radius = FloatArray(PORTAL_MAX_PARTICLES)
    private val radialJitter = FloatArray(PORTAL_MAX_PARTICLES)
    private val spinJitter = FloatArray(PORTAL_MAX_PARTICLES)

    init {
        val perRing = PORTAL_MAX_PARTICLES / RING_COUNT
        for (i in 0 until PORTAL_MAX_PARTICLES) {
            val ring = i / perRing
            val withinRing = i % perRing
            angle[i] = (withinRing.toFloat() / perRing) * TAU + ring * GOLDEN_ANGLE
            // Staggered start radii, so the field is already full on the first frame rather than
            // blooming out of the centre every time the player opens.
            radius[i] =
                ((ring.toFloat() / RING_COUNT) + (withinRing.toFloat() / perRing) / RING_COUNT) % 1f
            radialJitter[i] = 0.75f + ((i * 37) % 100) / 200f    // 0.75 .. 1.25
            spinJitter[i] = 0.80f + ((i * 53) % 100) / 250f      // 0.80 .. 1.20
        }
    }

    /**
     * Advances every particle by [dtSec] at the given [energy].
     *
     * Energy scales the spiral rate here and per-particle alpha in [PortalVisualizer.render].
     * Together those are Portal's decay: as the envelope falls to its floor on pause the field
     * slows *and* dims to a faint, almost-still throat rather than stopping dead.
     */
    fun advance(dtSec: Float, energy: Float) {
        val e = energy.coerceIn(0f, 1f)
        val radial = (RADIAL_BASE + RADIAL_ENERGY * e) * dtSec
        val spin = (SPIN_BASE + SPIN_ENERGY * e) * dtSec
        for (i in 0 until PORTAL_MAX_PARTICLES) {
            var r = radius[i] + radial * radialJitter[i]
            if (r >= 1f) {
                r -= 1f
                // Re-seed the angle on the wrap, so a particle does not retrace the same spoke
                // forever and the throat keeps filling from a different place each time.
                angle[i] += GOLDEN_ANGLE
            }
            radius[i] = r
            angle[i] += spin * spinJitter[i]
        }
    }

    internal fun angleAt(i: Int) = angle[i]
    internal fun radiusAt(i: Int) = radius[i]
}

/**
 * Draws [field] into whatever bounds it is given. Stateless — one instance backs the hero and
 * every picker tile at once, each at its own budget.
 *
 * The tile is the case to judge it by: at budget 22 it still has to read as a portal, because the
 * hero flatters everything.
 */
class PortalVisualizer(private val field: PortalField) : PfpVisualizer {
    override val id = VisualizerIds.PORTAL
    override val label = "Portal"

    override fun DrawScope.render(frame: VisualizerFrame, budget: Int, sprite: ImageBitmap) {
        val count = budget.coerceIn(0, PORTAL_MAX_PARTICLES)
        if (count == 0) return

        val centreX = size.width / 2f
        val centreY = size.height / 2f
        val rim = min(size.width, size.height) * 0.46f
        if (rim <= 0f) return

        // Tiles get proportionally larger particles: scaling the dp sizes straight down leaves a
        // 72dp preview drawing 2px dots, which reads as noise rather than as the same field.
        val scale = (rim / REFERENCE_RIM.toPx()).coerceIn(0.5f, 1f)
        val minPx = MIN_PARTICLE.toPx() * scale
        val maxPx = MAX_PARTICLE.toPx() * scale
        // Energy dims the whole field, not just its motion — half of Portal's pause decay.
        val energyAlpha = 0.35f + 0.65f * frame.energy.coerceIn(0f, 1f)

        for (i in 0 until count) {
            val r = field.radiusAt(i)
            // Squared falloff: small and bright at the throat, large and faint at the rim, so
            // particles dissolve into the backdrop rather than hardening against it. The inverse
            // is one line here if it ever needs to flip.
            val alpha = (1f - r) * (1f - r) * energyAlpha
            if (alpha <= 0.01f) continue

            val a = field.angleAt(i)
            val px = centreX + cos(a) * r * rim
            val py = centreY + sin(a) * r * rim
            val diameter = (minPx + (maxPx - minPx) * r).coerceAtLeast(1f)
            val half = diameter / 2f
            val side = diameter.toInt().coerceAtLeast(1)

            drawImage(
                image = sprite,
                dstOffset = IntOffset((px - half).toInt(), (py - half).toInt()),
                dstSize = IntSize(side, side),
                alpha = alpha,
            )
        }
    }
}
