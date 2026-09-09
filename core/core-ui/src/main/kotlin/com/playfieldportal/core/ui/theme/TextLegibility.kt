package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The launcher's WCAG contrast engine.
 *
 * Deliberately pure channel arithmetic on the sRGB values Compose stores — no android dependency —
 * so every rule here is pinned by plain JVM unit tests (`TextLegibilityTest`) rather than by
 * eyeballing a screenshot. It grew out of [deriveStorefrontColors]'s contrast floor and was
 * promoted here once the same math turned out to be needed by Settings, the XMB and the
 * font-color picker. See `docs/plans/text-legibility-font-color-plan.md`.
 *
 * Two facts drive everything below:
 *
 *  - White text needs a background whose relative luminance is **<= 0.183** to reach 4.5:1; black
 *    text needs **>= 0.175**. A backdrop that sweeps across that crossover down one screen cannot
 *    be served by any single text color.
 *  - A drop shadow adds a dark *edge* without changing the *fill* relationship, so it cannot
 *    rescue a fill whose ratio is structurally too low.
 */

// -- Roles --------------------------------------------------------------------

/**
 * The contrast bar a text run has to clear, which is a property of its *size*, not of the screen.
 *
 * WCAG "large text" is 24sp regular or 18.66sp bold. Almost nothing in this app qualifies: the
 * settings rows are 15sp labels, 13sp values and 12sp sublabels, so they are [BODY]. Only the
 * XMB's 22sp SemiBold selected label is [LARGE]. A global 3:1 render bar would ratify exactly the
 * failure this engine exists to fix — 3:1 remains the *picker's* warning trigger, which is a
 * different question from what we are willing to render.
 */
enum class TextContrastRole(val threshold: Float) {
    BODY(4.5f),
    LARGE(3.0f),
}

// -- Luminance & ratio --------------------------------------------------------

/** WCAG relative luminance of [c]: 0 (black) .. 1 (white). */
fun relativeLuminance(c: Color): Double {
    fun linearize(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.04045) v / 12.92
        else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linearize(c.red) + 0.7152 * linearize(c.green) + 0.0722 * linearize(c.blue)
}

/** WCAG contrast ratio between [a] and [b]: 1 .. 21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Contrast floor: return [fg] unchanged when it clears [minContrast] against [bg]; otherwise pick
 * whichever pole (black/white) actually reads on [bg]. Defaults to WCAG AA (4.5); the App Drawer
 * passes a lower 3.0 floor because its preset gradient is mid-tone by design (white on the classic
 * PSP blue is ~3.9:1) and only genuinely pale washes should flip.
 */
fun ensureReadable(fg: Color, bg: Color, minContrast: Float = 4.5f): Color {
    if (contrastRatio(fg, bg) >= minContrast) return fg
    return bestPolarity(bg)
}

/** Whichever of black/white reads better on [bg]. The last-resort step of AUTO resolution. */
fun bestPolarity(bg: Color): Color =
    if (contrastRatio(Color.Black, bg) >= contrastRatio(Color.White, bg)) Color.Black else Color.White

// -- Compositing --------------------------------------------------------------

/**
 * Source-over composite of [top] (honouring its alpha) onto opaque [bottom].
 *
 * Blended in sRGB channel space because that is what Compose's own `Modifier.background` does —
 * blending in linear space here would produce numbers that do not match the pixels on screen,
 * which is the whole point of computing them.
 */
fun composite(top: Color, bottom: Color): Color {
    val a = top.alpha
    return Color(
        red = top.red * a + bottom.red * (1f - a),
        green = top.green * a + bottom.green * (1f - a),
        blue = top.blue * a + bottom.blue * (1f - a),
    )
}

/**
 * The lowest alpha at which a scrim of color [scrim], painted over [background], lets [text] clear
 * [target]. Returns `0f` when the background already passes and `1f` when even a fully opaque
 * scrim of this color cannot get there.
 *
 * This is the function that makes the design rationale checkable: on the measured bright band it
 * answers ~0.66 for a *full-screen* scrim, which is what would bury the wallpaper across the other
 * 80% of the screen — and is why a text-shaped plate (the same local alpha over ~12% of the frame)
 * is the right instrument instead.
 */
fun scrimAlphaFor(
    scrim: Color,
    background: Color,
    text: Color,
    target: Float = TextContrastRole.BODY.threshold,
): Float {
    if (contrastRatio(text, background) >= target) return 0f
    // 1/256 steps: finer than the 8-bit buffer the result is painted into.
    for (step in 1..256) {
        val a = step / 256f
        if (contrastRatio(text, composite(scrim.copy(alpha = a), background)) >= target) return a
    }
    return 1f
}

/**
 * Darken [base] toward black just far enough that, painted at [alpha] over the worst-case
 * wallpaper, [text] on top of it clears [target]. Returns [base] unchanged when it already passes.
 *
 * Hue is kept: the result is `lerp(base, Black, t)` for the smallest workable `t`, so a themed
 * scrim stays recognisably the theme's colour instead of collapsing to a neutral wash.
 *
 * [worstCase] defaults to white because a user wallpaper can be anything and the scrim is the only
 * thing between it and the text. When the requested [alpha] is too low for *any* darkening to work
 * (below ~0.55 nothing reaches white's 0.183 luminance ceiling) this returns black, and the caller
 * is responsible for per-text protection instead.
 */
fun solveScrimColor(
    base: Color,
    alpha: Float,
    target: Float = TextContrastRole.BODY.threshold,
    text: Color = Color.White,
    worstCase: Color = Color.White,
): Color {
    fun passes(t: Float): Boolean =
        contrastRatio(
            text,
            composite(lerp(base, Color.Black, t).copy(alpha = alpha), worstCase),
        ) >= target

    if (passes(0f)) return base
    if (!passes(1f)) return Color.Black
    var lo = 0f
    var hi = 1f
    repeat(12) {
        val mid = (lo + hi) / 2f
        if (passes(mid)) hi = mid else lo = mid
    }
    return lerp(base, Color.Black, hi)
}

// -- HSL lightness clamp ------------------------------------------------------

/**
 * The outcome of resolving a requested text color against a known background.
 *
 * [adjusted] is what raises the user-facing notice, and it is deliberately distinct from
 * [meetsTarget]: a color can be adjusted and still short of the bar (the caller then escalates to
 * a contrast plate), or unadjusted and passing (the common case, no notice).
 */
@Immutable
data class ResolvedTextColor(
    /** What actually gets painted. */
    val color: Color,
    /** What the user picked. */
    val requested: Color,
    /** `color != requested`. */
    val adjusted: Boolean,
    /** Contrast of [color] against the background it was resolved for. */
    val achievedRatio: Float,
    /** Whether [achievedRatio] cleared the role's threshold. */
    val meetsTarget: Boolean,
)

/**
 * Move [fg]'s **HSL lightness** — preserving hue and saturation — until it clears [target] on [bg].
 *
 * HSL, not HSV: HSV's `V` at `S = 1.0` never reaches white, so clamping `V` would report
 * "unreachable" for colors that are trivially reachable. HSL's `L` reaches both poles, so a
 * solution exists in at least one direction for any hue.
 *
 * Both directions are bisected and the winner is whichever lands *nearer* the original lightness —
 * not whichever maximises contrast, which would drive every choice to black and throw the hue away.
 *
 * **[maxShift] is what makes this a clamp rather than an override.** A solution always exists at
 * the poles — white passes on any background below L 0.183 and black on any above 0.175, and those
 * two ranges overlap — so an unbounded search would "succeed" by handing back near-black for a
 * bright yellow and call it the user's colour. Past [maxShift] the hue is no longer recognisable,
 * so the result comes back with `meetsTarget = false` and the caller escalates to a contrast
 * plate, which keeps the requested colour and moves the *background* instead.
 */
fun clampLightnessForContrast(
    fg: Color,
    bg: Color,
    target: Float = TextContrastRole.BODY.threshold,
    maxShift: Float = 0.35f,
): ResolvedTextColor {
    val plain = contrastRatio(fg, bg).toFloat()
    if (plain >= target) {
        return ResolvedTextColor(fg, fg, adjusted = false, achievedRatio = plain, meetsTarget = true)
    }

    val (h, s, l0) = fg.toHsl()
    fun at(l: Float) = hslToColor(h, s, l)
    fun passes(l: Float) = contrastRatio(at(l), bg) >= target

    /** Bisect between the (failing) original lightness and a (passing) pole. */
    fun bisect(pole: Float): Float? {
        if (!passes(pole)) return null
        var near = l0
        var far = pole
        repeat(8) {
            val mid = (near + far) / 2f
            if (passes(mid)) far = mid else near = mid
        }
        return far
    }

    val down = bisect(0f)
    val up = bisect(1f)
    val best = when {
        down != null && up != null -> if (l0 - down <= up - l0) down else up
        else -> down ?: up
    }

    // `best` is never null in practice — the poles always bracket any background — but the search
    // is written so the caller does not have to trust that.
    if (best == null || kotlin.math.abs(best - l0) > maxShift) {
        // Too far to still be the colour the user picked. Render the requested colour unchanged
        // and report the miss; the caller escalates to a plate rather than overriding the choice.
        return ResolvedTextColor(
            color = fg,
            requested = fg,
            adjusted = false,
            achievedRatio = plain,
            meetsTarget = false,
        )
    }

    val out = at(best)
    return ResolvedTextColor(
        color = out,
        requested = fg,
        adjusted = out != fg,
        achievedRatio = contrastRatio(out, bg).toFloat(),
        meetsTarget = true,
    )
}

/** Hue (0..360), saturation (0..1), lightness (0..1). Hand-rolled so this file stays pure JVM. */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        red -> (green - blue) / d + (if (green < blue) 6f else 0f)
        green -> (blue - red) / d + 2f
        else -> (red - green) / d + 4f
    } * 60f
    return Triple(h, s, l)
}

/** Inverse of [toHsl]. */
internal fun hslToColor(h: Float, s: Float, l: Float): Color {
    if (s == 0f) return Color(l, l, l)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    val hk = h / 360f
    return Color(
        channel(hk + 1f / 3f).coerceIn(0f, 1f),
        channel(hk).coerceIn(0f, 1f),
        channel(hk - 1f / 3f).coerceIn(0f, 1f),
    )
}
