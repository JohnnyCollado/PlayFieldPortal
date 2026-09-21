package com.playfieldportal.feature.xmb.ui.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * One clock, one array, N draws — the rule that makes a live picker affordable.
 *
 * The strip's previews are not separate visualizers. They are **one** simulation drawn once each —
 * three draws (the hero and the two live tiles) against one advance per field per frame:
 *
 *  - **One `withFrameNanos` loop**, owned by the player screen. Not `rememberInfiniteTransition`,
 *    and never one per tile — that would be one animation subscription and one recomposition
 *    scope per preview, for a field that is already being computed.
 *  - **One particle array per field**, advanced once per frame in this holder. Each tile draws the
 *    *first N* entries scaled into its own bounds, so simulation cost is O(1) in tile count and
 *    only draw scales.
 *  - **State is read in the draw phase.** [frame] is read *inside* a `Canvas` lambda, so a new
 *    frame invalidates draw only. Read it from a composable body instead and the whole player
 *    recomposes at 60Hz, which is precisely the bug this class exists to prevent.
 *
 * If you are adding motion to the music player, add it here. A second animation loop is the one
 * thing most likely to undo all of the above.
 */
@Stable
class VisualizerHost internal constructor(tint: () -> Color) {

    private val portalField = PortalField()
    private val rippleField = RippleField()

    private val renderers: Map<String, PfpVisualizer> = listOf(
        PortalVisualizer(portalField),
        RippleVisualizer(rippleField, tint),
    ).associateBy { it.id }

    /** Seconds since this host's first frame — see [advance] for why it is not derived per frame. */
    private var elapsedSec = 0f

    private var frameState by mutableStateOf(VisualizerFrame(0L, ENERGY_FLOOR, null))

    /**
     * The latest frame. **Read this only from inside a draw lambda** — see the class doc.
     */
    val frame: VisualizerFrame get() = frameState

    fun rendererFor(id: String): PfpVisualizer? = renderers[id]

    /**
     * Advances every field once, then publishes the frame.
     *
     * Both fields advance even though only one is on the hero, because the picker strip previews
     * all of them live and a field that only starts simulating when selected would pop. Portal's
     * advance is 260 float operations; the cost of the branch would be comparable to the work.
     */
    internal fun advance(timeNanos: Long, dtSec: Float, energy: Float) {
        // Ripple works in seconds *since this host started*, accumulated from the deltas, never in
        // `timeNanos / 1e9`: the frame clock's origin is device uptime, so that quotient is in the
        // hundreds of thousands and a Float there resolves to ~30ms steps — coarser than the ring
        // ages and emission intervals it would be compared against.
        elapsedSec += dtSec
        portalField.advance(dtSec, energy)
        rippleField.advance(elapsedSec, energy)
        frameState = VisualizerFrame(timeNanos, energy, null)
    }
}

// ── Budgets ───────────────────────────────────────────────────────────────────
// The hero drops when the strip opens so total draw count stays roughly flat across the one
// transition where jank would be most visible.

fun heroBudget(visualizerId: String, stripOpen: Boolean): Int = when (visualizerId) {
    VisualizerIds.PORTAL -> if (stripOpen) 140 else PORTAL_MAX_PARTICLES
    VisualizerIds.RIPPLE -> if (stripOpen) 8 else RIPPLE_MAX_RINGS
    else -> 0
}

fun tileBudget(visualizerId: String): Int = when (visualizerId) {
    VisualizerIds.PORTAL -> 22
    VisualizerIds.RIPPLE -> 5
    else -> 0
}

/**
 * Creates the holder and drives its single clock.
 *
 * The clock stops entirely when [active] is false — the caller passes false when the player is
 * closed or `Off` is selected with the strip shut — and whenever the app is not resumed. Nothing
 * here runs in the background.
 *
 * [trackId] seeds the envelope, so each song has its own character, consistently, every play.
 */
@Composable
fun rememberVisualizerHost(
    isPlaying: Boolean,
    trackId: String?,
    tint: Color,
    active: Boolean,
): VisualizerHost {
    val tintState = rememberUpdatedState(tint)
    val host = remember { VisualizerHost { tintState.value } }

    // Read inside the frame callback rather than keyed on, so a play/pause does not tear down and
    // restart the loop (which would lose the frame delta and stutter on every toggle).
    val playing by rememberUpdatedState(isPlaying)

    var resumed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }

    val energy = remember(trackId) { VisualizerEnergySource(visualizerSeed(trackId)) }
    val running = active && resumed

    // The single clock. It restarts only when the gate flips or the track (and so the envelope's
    // seed) changes — never on a play/pause, which is read inside the callback instead.
    LaunchedEffect(running, energy) {
        if (!running) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                // Clamped: coming back from a dropped frame or a long GC pause, an unclamped delta
                // would teleport every particle across the field in one step.
                val dt = if (lastNanos == 0L) 0f
                else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastNanos = now
                host.advance(now, dt, energy.energyAt(now, playing))
            }
        }
    }
    return host
}

/**
 * Draws one field into [modifier]'s bounds.
 *
 * `Off` is the absence of a renderer, not a renderer: it costs no clock, no particles and no draw,
 * and this composable degrades to an empty `Spacer`.
 */
@Composable
fun VisualizerField(
    host: VisualizerHost,
    visualizerId: String,
    budget: Int,
    sprite: ImageBitmap,
    modifier: Modifier = Modifier,
) {
    val renderer = host.rendererFor(visualizerId)
    if (renderer == null || budget <= 0) {
        Spacer(modifier)
        return
    }
    Canvas(modifier) {
        // The one state read that matters, and it is inside the draw lambda on purpose.
        with(renderer) { render(host.frame, budget, sprite) }
    }
}
