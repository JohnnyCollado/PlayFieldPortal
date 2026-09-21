package com.playfieldportal.feature.xmb.ui.visualizer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * One tick of whatever is driving the field.
 *
 * This is the seam between the *source* of motion and the renderers that draw it, and it is
 * deliberately wider than what exists today: [energy] is synthetic (see [VisualizerEnergySource])
 * and [bands] is always null, because PFP's music path is a `MediaPlayer` with no audio tap. If an
 * ExoPlayer migration ever lands, [energy] becomes RMS off a `TeeAudioProcessor` and [bands] fills
 * in from an FFT — and no renderer below changes, which is the whole reason the shape is fixed now.
 *
 * Bar-spectrum and waveform styles are excluded on purpose: the eye reads bars as a *measurement*,
 * so a spectrum uncorrelated with the audio reads as a lie within seconds. Portal and Ripple are
 * ambient fields, which a synthetic envelope can drive honestly.
 */
data class VisualizerFrame(
    val timeNanos: Long,
    /** 0..1. Synthetic today, RMS later. Every renderer must consume it in at least one term. */
    val energy: Float,
    /** Per-band magnitudes; null until there is a real FFT. */
    val bands: FloatArray?,
) {
    // Hand-written because the generated pair would compare [bands] by identity, and a frame is
    // held in snapshot state where an equality check decides whether draw re-runs.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizerFrame) return false
        return timeNanos == other.timeNanos &&
            energy == other.energy &&
            (bands?.contentEquals(other.bands) ?: (other.bands == null))
    }

    override fun hashCode(): Int {
        var result = timeNanos.hashCode()
        result = 31 * result + energy.hashCode()
        result = 31 * result + (bands?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * A field renderer: pure draw, no state of its own and no clock.
 *
 * Simulation state (particles, rings) lives in [VisualizerHost] and is advanced **once per frame**
 * regardless of how many tiles are on screen — see §3.3 of the plan. A renderer therefore holds a
 * reference to that shared field and draws the first `budget` entries of it scaled into whatever
 * bounds the `DrawScope` gives it, which is what makes nine live picker previews cost one
 * simulation and nine draws rather than nine of everything.
 */
interface PfpVisualizer {
    val id: String
    val label: String

    /** Draws [budget] elements of the shared field into the current bounds. */
    fun DrawScope.render(frame: VisualizerFrame, budget: Int, sprite: ImageBitmap)
}

/** The stable ids, which are also the persisted preference values. Do not rename. */
object VisualizerIds {
    const val OFF = "off"
    const val PORTAL = "portal"
    const val RIPPLE = "ripple"

    /** Picker order: `Off` first, so the cheapest option is the one under the cursor by default. */
    val ALL = listOf(OFF, PORTAL, RIPPLE)

    fun labelFor(id: String): String = when (id) {
        PORTAL -> "Portal"
        RIPPLE -> "Ripple"
        else -> "Off"
    }
}
