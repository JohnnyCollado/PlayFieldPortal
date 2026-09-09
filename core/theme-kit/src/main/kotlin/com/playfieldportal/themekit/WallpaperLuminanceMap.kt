package com.playfieldportal.themekit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A coarse luminance survey of the user's wallpaper, so the XMB can decide per-label how much
 * protection its text needs.
 *
 * The XMB is the one surface in the app that genuinely draws text on raw wallpaper — everything
 * else (Settings, App Drawer, storefront, detail, music, context menus) paints a scrim of *known*
 * colors, so its effective background is a closed-form composite with no sampling needed. See
 * `docs/plans/text-legibility-font-color-plan.md`.
 *
 * Runtime sampling was rejected: `PixelCopy` needs a Window/SurfaceView and the wallpaper is a Coil
 * `AsyncImage`, while `GraphicsLayer.toImageBitmap()` would mean a GPU→CPU readback every frame in
 * a launcher whose background architecture is built on the opposite principle — and it would end up
 * sampling our own text. Surveying once at import time costs single-digit milliseconds behind a
 * spinner that already exists.
 *
 * ### Why two numbers per band
 *
 * [LuminanceBand.mean] decides *polarity* — whether light or dark text is the right starting point
 * for this region. [LuminanceBand.p90] decides *protection strength*, because a plain mean hides a
 * bright cloud sitting behind one word: a band that averages 0.2 but reaches 0.8 at its 90th
 * percentile will eat white text exactly where the label falls.
 */
@Serializable
data class LuminanceBand(
    /** Mean WCAG relative luminance over the band, 0..1. Drives polarity. */
    val mean: Float,
    /** 90th-percentile WCAG relative luminance over the band, 0..1. Drives protection strength. */
    val p90: Float,
)

/**
 * [ROWS] × [ZONES] luminance bands covering the whole wallpaper, plus the path they were computed
 * from.
 *
 * **The stored values are WCAG relative luminance, not Rec.601 luma.** This is the trap in this
 * file: [WallpaperMetrics.luminance] is Rec.601 and does *not* compose with the contrast engine's
 * `contrastRatio`. Mixing the two yields thresholds that look plausible and are wrong — 4.5:1
 * decisions made against a Rec.601 number are simply a different question than the one WCAG asks.
 * `WallpaperLuminanceMapTest` pins the two apart deliberately so nobody "unifies" them later.
 *
 * [source] is the wallpaper path the survey describes. It is embedded rather than tracked by a
 * separate stamp key because wallpaper files are already uniquified per import (so Coil's cache
 * invalidates on its own); a reader that finds a mismatch simply discards the map and recomputes.
 */
@Serializable
data class WallpaperLuminanceMap(
    val source: String,
    val bands: List<LuminanceBand>,
) {

    init {
        require(bands.size == ROWS * ZONES) { "expected ${ROWS * ZONES} bands, got ${bands.size}" }
    }

    /**
     * The band covering the point at [xFraction], [yFraction] (both 0..1, clamped).
     *
     * Fractions rather than pixels: callers work in Compose layout coordinates against a wallpaper
     * whose real pixel size they neither know nor should care about.
     */
    fun bandAt(xFraction: Float, yFraction: Float): LuminanceBand {
        val zone = (xFraction.coerceIn(0f, 1f) * ZONES).toInt().coerceAtMost(ZONES - 1)
        val row = (yFraction.coerceIn(0f, 1f) * ROWS).toInt().coerceAtMost(ROWS - 1)
        return bands[row * ZONES + zone]
    }

    /**
     * [bandAt], plus the wave's contribution.
     *
     * The XMB paints its animated wave over the bottom of the screen, so the pixels a label
     * actually sits on down there are brighter than the wallpaper alone. Modelling the wave
     * per-pixel would mean re-deriving its geometry here and keeping the two in step forever; a
     * single tuned additive constant over the bottom [WAVE_REGION] costs one line and is pinned by
     * a test. Erring bright is the safe direction — it buys a little more protection than strictly
     * needed rather than too little.
     */
    fun effectiveBandAt(xFraction: Float, yFraction: Float): LuminanceBand {
        val band = bandAt(xFraction, yFraction)
        if (yFraction < 1f - WAVE_REGION) return band
        return LuminanceBand(
            mean = (band.mean + WAVE_LUMINANCE_BOOST).coerceAtMost(1f),
            p90 = (band.p90 + WAVE_LUMINANCE_BOOST).coerceAtMost(1f),
        )
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {

        /** Vertical bands. Twelve is fine enough to track a top-to-bottom sweep across one screen. */
        const val ROWS = 12

        /** Horizontal zones: the XMB's icon column, its label run, and the right margin. */
        const val ZONES = 3

        /** Fraction of the screen height, measured from the bottom, that the wave lightens. */
        const val WAVE_REGION = 0.35f

        /** Additive relative-luminance allowance for the wave over [WAVE_REGION]. */
        const val WAVE_LUMINANCE_BOOST = 0.06f

        /** Sample budget for [compute]. The source is already downscaled to ~256px by callers. */
        private const val MAX_SAMPLES = 12_000

        /** Percentile resolution for [LuminanceBand.p90]; finer than the 8-bit source. */
        private const val HISTOGRAM_BINS = 256

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * WCAG relative luminance of a packed ARGB pixel, 0..1.
         *
         * Alpha is ignored: wallpapers are composited onto an opaque backdrop before they are seen,
         * and BMP sources carry `0xFF` regardless.
         */
        fun relativeLuminance(argb: Int): Float {
            fun linearize(channel: Int): Double {
                val v = channel / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            val r = linearize(argb shr 16 and 0xFF)
            val g = linearize(argb shr 8 and 0xFF)
            val b = linearize(argb and 0xFF)
            return (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
        }

        /**
         * Survey [image] into a [WallpaperLuminanceMap] describing the wallpaper at [source].
         *
         * Stride-sampled the same way [WallpaperMetrics.busyness] samples, so the cost is bounded
         * by [MAX_SAMPLES] rather than by the image size. The mean is accumulated exactly; only the
         * percentile goes through a histogram, which needs no sort and no per-band allocation that
         * grows with the pixel count.
         */
        fun compute(image: BmpImage, source: String): WallpaperLuminanceMap {
            val cells = ROWS * ZONES
            val sums = DoubleArray(cells)
            val counts = IntArray(cells)
            val histograms = Array(cells) { IntArray(HISTOGRAM_BINS) }

            val stride = max(
                1,
                sqrt(image.width.toFloat() * image.height / MAX_SAMPLES).toInt(),
            )

            var y = 0
            while (y < image.height) {
                val row = (y.toLong() * ROWS / image.height).toInt().coerceAtMost(ROWS - 1)
                var x = 0
                while (x < image.width) {
                    val zone = (x.toLong() * ZONES / image.width).toInt().coerceAtMost(ZONES - 1)
                    val cell = row * ZONES + zone
                    val luminance = relativeLuminance(image.argb[y * image.width + x])
                    sums[cell] += luminance
                    counts[cell]++
                    val bin = (luminance * (HISTOGRAM_BINS - 1)).toInt().coerceIn(0, HISTOGRAM_BINS - 1)
                    histograms[cell][bin]++
                    x += stride
                }
                y += stride
            }

            val bands = List(cells) { cell ->
                val count = counts[cell]
                // A band can come up empty only for an image shorter or narrower than its own band
                // count. Black is the conservative answer: it asks for no protection, and a 12px
                // wallpaper has bigger problems than legibility.
                if (count == 0) LuminanceBand(0f, 0f)
                else LuminanceBand(
                    mean = (sums[cell] / count).toFloat(),
                    p90 = percentile(histograms[cell], count, 0.90f),
                )
            }
            return WallpaperLuminanceMap(source, bands)
        }

        /**
         * Parse a stored map, returning `null` when it is unreadable **or describes a different
         * wallpaper than [expectedSource]** — both cases mean "recompute", so they are one result.
         */
        fun fromJson(json: String, expectedSource: String): WallpaperLuminanceMap? {
            val map = try {
                JSON.decodeFromString(serializer(), json)
            } catch (_: Exception) {
                // Includes the init require() above: a truncated or hand-edited map is stale data,
                // never a crash.
                return null
            }
            return map.takeIf { it.source == expectedSource }
        }

        /** Smallest bin value at or below which [fraction] of the samples fall. */
        private fun percentile(histogram: IntArray, count: Int, fraction: Float): Float {
            val target = (count * fraction).toInt().coerceAtMost(count - 1)
            var seen = 0
            for (bin in histogram.indices) {
                seen += histogram[bin]
                if (seen > target) return bin.toFloat() / (HISTOGRAM_BINS - 1)
            }
            return 1f
        }
    }
}
