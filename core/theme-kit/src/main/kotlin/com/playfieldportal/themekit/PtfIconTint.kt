package com.playfieldportal.themekit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One flat tint for the built-in icons a PSP theme does not replace.
 *
 * Built-in icons are drawn as silhouettes in the theme's icon colour (SrcIn), so after a `.ptf`
 * import they sit beside the theme's own art. This picks the single colour that best stands in for
 * that art and scores how well it does: multicolour icons (illustrated cards, photos) score low,
 * and then the import keeps the automatic colour instead of forcing a poor match.
 *
 * Colour distances are CIEDE2000 in CIELAB (D65), so "close" means close to the eye, not in RGB.
 */
object PtfIconTint {

    /**
     * [argb] is the tint (opaque); [score] is the share, 0–100, of the icons' opaque pixels within
     * [MATCH_DELTA_E] of it; [medianDeltaE] is how far a typical pixel sits from it.
     */
    data class Result(val argb: Int, val score: Int, val medianDeltaE: Float)

    /** From this score up, the tint replaces the automatic icon colour. */
    const val APPLY_SCORE = 35

    /** From this score up, a vivid tint also becomes the theme accent (menus, cursor, wave). */
    const val STRONG_SCORE = 60

    /** A pixel this close to the tint counts as explained by it. */
    const val MATCH_DELTA_E = 20.0

    // Pixels at least this opaque are art; the soft edges below it are anti-aliasing.
    private const val MIN_ALPHA = 200
    private const val OPAQUE = 0xFF shl 24

    // Seed bin size in L / a / b units, then the radius around the seed that refines it.
    private const val BIN_L = 8.0
    private const val BIN_AB = 10.0
    private const val REFINE_DELTA_E = 12.0

    /**
     * The tint for [icons] (pass each source once — see [PtfIcons.tintSources]), or null when they
     * hold no opaque pixels. Large sets are stride-sampled down to about [maxSamples] pixels.
     */
    fun derive(icons: Collection<BmpImage>, maxSamples: Int = 20_000): Result? {
        val total = icons.sumOf { img -> img.argb.count { it ushr 24 >= MIN_ALPHA } }
        if (total == 0) return null
        val stride = ((total + maxSamples - 1) / maxSamples).coerceAtLeast(1)

        val capacity = minOf(total, maxSamples + 1)
        val labs = ArrayList<DoubleArray>(capacity)
        val colors = ArrayList<Int>(capacity)
        var seen = 0
        for (img in icons) for (px in img.argb) {
            if (px ushr 24 < MIN_ALPHA) continue
            if (seen++ % stride == 0) {
                labs += toLab(px)
                colors += px or OPAQUE
            }
        }

        // Seed: the most populated coarse Lab bin (ties go to the bin met first, so the result is
        // deterministic for a given input order).
        val bins = LinkedHashMap<Long, MutableList<DoubleArray>>()
        for (lab in labs) bins.getOrPut(binKey(lab)) { mutableListOf() } += lab
        val seed = mean(bins.values.maxBy { it.size })

        // Refine to the mean of everything near the seed, then snap to the sampled pixel closest to
        // that mean: the tint is always a colour the art really uses, never a blend of shades.
        val near = labs.filter { deltaE2000(it, seed) <= REFINE_DELTA_E }
        val centre = if (near.isEmpty()) seed else mean(near)
        val snapped = labs.indices.minBy { deltaE2000(labs[it], centre) }
        val tint = labs[snapped]

        val distances = labs.map { deltaE2000(it, tint) }.sorted()
        val matched = distances.count { it <= MATCH_DELTA_E }
        return Result(
            argb = colors[snapped],
            score = (matched * 100.0 / distances.size).roundToInt(),
            medianDeltaE = distances[distances.size / 2].toFloat(),
        )
    }

    /** The icon colour to store for [result], or null to keep the automatic colour. */
    fun iconColorFor(result: Result?): Int? = result?.takeIf { it.score >= APPLY_SCORE }?.argb

    /**
     * The theme accent after an import: a strong, vivid icon tint, so menus, the cursor and the
     * icons share one hue; otherwise [wallpaperAccent], the colour derived from the wallpaper.
     */
    fun chooseAccent(result: Result?, wallpaperAccent: Int?): Int? =
        result?.takeIf { it.score >= STRONG_SCORE && isVivid(it.argb) }?.argb ?: wallpaperAccent

    /**
     * The same test the menus use to decide whether an accent carries a hue (`resolveHueSource`):
     * channels spread by at least 0.10 and not effectively black.
     */
    private fun isVivid(argb: Int): Boolean {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        return max - minOf(r, g, b) >= 0.10f && max >= 0.30f
    }

    // ── colour science ────────────────────────────────────────────────────────

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private const val EPSILON = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0

    /** Opaque sRGB → CIELAB (D65) as `[L, a, b]`. Alpha is ignored. */
    fun toLab(argb: Int): DoubleArray {
        val r = linear((argb shr 16 and 0xFF) / 255.0)
        val g = linear((argb shr 8 and 0xFF) / 255.0)
        val b = linear((argb and 0xFF) / 255.0)
        val fx = labF((0.4124 * r + 0.3576 * g + 0.1805 * b) / XN)
        val fy = labF((0.2126 * r + 0.7152 * g + 0.0722 * b) / YN)
        val fz = labF((0.0193 * r + 0.1192 * g + 0.9505 * b) / ZN)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** CIELAB (D65) → opaque packed sRGB, clamped into gamut. */
    fun fromLab(l: Double, a: Double, b: Double): Int {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        val x = labFInverse(fx) * XN
        val y = labFInverse(fy) * YN
        val z = labFInverse(fz) * ZN
        val r = encode(3.2406 * x - 1.5372 * y - 0.4986 * z)
        val g = encode(-0.9689 * x + 1.8758 * y + 0.0415 * z)
        val bl = encode(0.0557 * x - 0.2040 * y + 1.0570 * z)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** CIEDE2000 colour difference between two CIELAB colours. */
    fun deltaE2000(l1: Double, a1: Double, b1: Double, l2: Double, a2: Double, b2: Double): Double {
        val c1 = hypot(a1, b1)
        val c2 = hypot(a2, b2)
        val cBar7 = ((c1 + c2) / 2).pow(7)
        val g = 0.5 * (1 - sqrt(cBar7 / (cBar7 + POW25_7)))
        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = hypot(a1p, b1)
        val c2p = hypot(a2p, b2)
        val h1p = hueDegrees(b1, a1p)
        val h2p = hueDegrees(b2, a2p)

        val dLp = l2 - l1
        val dCp = c2p - c1p
        val chromaProduct = c1p * c2p
        val dhp = when {
            chromaProduct == 0.0 -> 0.0
            abs(h2p - h1p) <= 180 -> h2p - h1p
            h2p - h1p > 180 -> h2p - h1p - 360
            else -> h2p - h1p + 360
        }
        val dHp = 2 * sqrt(chromaProduct) * sin(Math.toRadians(dhp / 2))

        val lBarP = (l1 + l2) / 2
        val cBarP = (c1p + c2p) / 2
        val hBarP = when {
            chromaProduct == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hBarP - 30)) + 0.24 * cos(Math.toRadians(2 * hBarP)) +
            0.32 * cos(Math.toRadians(3 * hBarP + 6)) - 0.20 * cos(Math.toRadians(4 * hBarP - 63))
        val lOffset = (lBarP - 50).pow(2)
        val sl = 1 + 0.015 * lOffset / sqrt(20 + lOffset)
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val cBarP7 = cBarP.pow(7)
        val rt = -2 * sqrt(cBarP7 / (cBarP7 + POW25_7)) *
            sin(Math.toRadians(60 * exp(-((hBarP - 275) / 25).pow(2))))

        val dl = dLp / sl
        val dc = dCp / sc
        val dh = dHp / sh
        return sqrt(dl * dl + dc * dc + dh * dh + rt * dc * dh)
    }

    private val POW25_7 = 25.0.pow(7)

    private fun deltaE2000(x: DoubleArray, y: DoubleArray): Double =
        deltaE2000(x[0], x[1], x[2], y[0], y[1], y[2])

    private fun hueDegrees(b: Double, aPrime: Double): Double =
        if (b == 0.0 && aPrime == 0.0) 0.0 else (Math.toDegrees(atan2(b, aPrime)) + 360) % 360

    private fun binKey(lab: DoubleArray): Long {
        val l = (lab[0] / BIN_L).roundToInt().toLong()
        val a = (lab[1] / BIN_AB).roundToInt().toLong()
        val b = (lab[2] / BIN_AB).roundToInt().toLong()
        // L spans 0..100 and a/b roughly -128..128: offsets keep every part non-negative.
        return ((l + 64) shl 32) or ((a + 512) shl 16) or (b + 512)
    }

    private fun mean(labs: List<DoubleArray>): DoubleArray {
        val out = DoubleArray(3)
        for (lab in labs) for (i in 0..2) out[i] += lab[i]
        for (i in 0..2) out[i] /= labs.size
        return out
    }

    private fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun encode(linear: Double): Int {
        val c = linear.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }

    private fun labF(t: Double): Double = if (t > EPSILON) cbrt(t) else (KAPPA * t + 16) / 116

    private fun labFInverse(f: Double): Double {
        val cube = f * f * f
        return if (cube > EPSILON) cube else (116 * f - 16) / KAPPA
    }
}
