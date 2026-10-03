package com.playfieldportal.studio.preview

import com.playfieldportal.themekit.PfpThemeManifest
import kotlin.math.sin

/**
 * How one wave style behaves, mirroring feature-xmb `XmbBackground`: Reduced runs at half speed
 * with 0.65 amplitude and half alpha; the two Static styles never animate (the wave is posed at
 * [WaveMotion.STATIC_TIME]); Reduced + Static keeps the reduced amplitude and alpha while frozen.
 * The same flags govern the motion wallpaper (one setting governs wave and motion).
 */
data class WaveParams(
    val animated: Boolean,
    val speed: Float,
    val ampScale: Float,
    val alphaScale: Float,
)

/** Pure wave maths for the live preview — no Compose, so every mode is unit-testable. */
object WaveMotion {

    /** XmbBackground.STATIC_TIME: the pose a frozen wave is drawn at. */
    const val STATIC_TIME = 2.0f
    const val TAU = 6.2831853f

    /** XmbBackground: `a = 0.05 * ampScale`. */
    const val AMPLITUDE = 0.05f

    /** One sine fold: its crest line sits at `base + a*ampFactor*sin(x*TAU*freq + t*drift + phase)` of the height. */
    data class Fold(
        val base: Float,
        val ampFactor: Float,
        val freq: Float,
        val phase: Float,
        val drift: Float,
        val sheet: Float,
        val edge: Float,
    )

    /** FallbackWave's two folds, back to front. */
    val FOLDS: List<Fold> = listOf(
        Fold(base = 0.63f, ampFactor = 0.9f, freq = 0.80f, phase = 1.7f, drift = -0.38f, sheet = 0.090f, edge = 0.125f),
        Fold(base = 0.75f, ampFactor = 1.2f, freq = 0.42f, phase = 3.1f, drift = 0.30f, sheet = 0.105f, edge = 0.145f),
    )

    fun paramsFor(style: String): WaveParams = when (style) {
        PfpThemeManifest.WAVE_REDUCED -> WaveParams(animated = true, speed = 0.5f, ampScale = 0.65f, alphaScale = 0.5f)
        PfpThemeManifest.WAVE_STATIC -> WaveParams(animated = false, speed = 1f, ampScale = 1f, alphaScale = 1f)
        PfpThemeManifest.WAVE_REDUCED_STATIC -> WaveParams(animated = false, speed = 0.5f, ampScale = 0.65f, alphaScale = 0.5f)
        // Animated, and any value this build does not know (the launcher's own fallback).
        else -> WaveParams(animated = true, speed = 1f, ampScale = 1f, alphaScale = 1f)
    }

    /**
     * The wave's time in seconds: `elapsed * speed` while it animates, [STATIC_TIME] when the style
     * is frozen or the frame is not live ([elapsedMs] null — the exported preview.png).
     */
    fun timeSeconds(params: WaveParams, elapsedMs: Long?): Float =
        if (!params.animated || elapsedMs == null) STATIC_TIME else elapsedMs / 1000f * params.speed

    /** The crest of [fold] at horizontal fraction [xx] (0..1) and time [t], as a fraction of the height. */
    fun crestY01(fold: Fold, params: WaveParams, xx: Float, t: Float): Float =
        fold.base + AMPLITUDE * params.ampScale * fold.ampFactor * sin(xx * TAU * fold.freq + t * fold.drift + fold.phase)

    fun sheetAlpha(fold: Fold, params: WaveParams): Float = fold.sheet * params.alphaScale

    fun edgeAlpha(fold: Fold, params: WaveParams): Float = fold.edge * params.alphaScale
}
