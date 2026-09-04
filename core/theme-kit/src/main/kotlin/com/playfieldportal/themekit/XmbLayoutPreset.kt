package com.playfieldportal.themekit

/**
 * Device-independent auto-fit for the XMB cross layout.
 *
 * Given a window's metrics, compute the three [XmbLayoutAdjust] values (scale,
 * barLeftFraction, barTopFraction) that reproduce the hand-tuned PSP-authentic layout
 * captured on the AYN Thor (1920×1080 @ 369 dpi, compact bucket, barLeftFraction = -0.05).
 *
 * This is an OPTIONAL helper — callers opt in explicitly (Setup Wizard checkbox or the
 * "Auto-fit (PSP)" action in the Adjust XMB Layout overlay). It is never applied
 * automatically. The returned values still pass through [XmbLayoutAdjustCodec.sanitize],
 * which re-applies all clamps as the final gate.
 *
 * The derivation is documented in docs/xmb-layout-proportional-formula.md.
 *
 * Pure-JVM by design — theme-kit has no Android/Compose dependencies. Callers convert
 * LocalConfiguration/LocalDensity into pixels + dpi and pass them in.
 */
object XmbLayoutPreset {

    // ── Tuned reference (AYN Thor, 1920×1080 @ 369 dpi, compact bucket) ─────────

    /** Target layout canvas height in dp — the tuned PSP-authentic vertical space. */
    const val PSP_CANVAS_HEIGHT_DP = 354.546f

    /** Desired caticon band centre as a fraction of the layout canvas height. */
    const val PSP_CATICON_CENTER_FRACTION = 0.34436f

    /**
     * Cross icon-centre line, measured in dp from the left edge of the layout canvas,
     * on the AYN Thor reference capture with barLeftFraction = -0.05.
     *
     * Derived from the existing layout constants (not a new magic number):
     *   columnBaseInset = XmbLeftAnchor(130dp) + CategorySlotWidth/2(62dp) - LEADING_ICON_CENTER(55dp) = 137 dp
     *   anchorX = columnBaseInset + hShift = 137 + 630.30 * (-0.05) = 137 - 31.515 = 105.485 dp
     */
    const val PSP_CROSS_ANCHOR_X_DP = 105.49f

    // ── Fixed sub-term constants (derived from existing layout constants) ─────────

    /** contentTopPadding + CAT_BAR_HEIGHT/2 — the sub-term in the barTopFraction formula. */
    private const val BAR_TOP_SUB_DP = 76.0f

    /** columnBaseInset — the sub-term in the barLeftFraction formula. */
    private const val COLUMN_BASE_INSET_DP = 137.0f

    // ── Baseline constants (must match XMBShell.kt) ─────────────────────────────

    private const val XMB_BASELINE_HEIGHT_DP = 468f
    private const val XMB_BASELINE_WIDTH_DP = 832f
    private const val XMB_MAX_SCALE = 2.5f

    // ── Public API ───────────────────────────────────────────────────────────────

    /**
     * Compute the auto-fit [XmbLayoutAdjust] from window dp + density — the shape
     * `LocalConfiguration`/`LocalDensity` hand to an Android caller. Orientation is
     * normalized (PFP is landscape-fixed; the formula always wants the long side as width).
     *
     * @param widthDp  current window width in dp
     * @param heightDp current window height in dp
     * @param density  DisplayMetrics.density (= densityDpi / 160)
     */
    fun computeForWindowDp(widthDp: Float, heightDp: Float, density: Float): XmbLayoutAdjust =
        computeForWindow(
            widthPx = maxOf(widthDp, heightDp) * density,
            heightPx = minOf(widthDp, heightDp) * density,
            densityDpi = density * 160f,
        )

    /**
     * Compute the auto-fit [XmbLayoutAdjust] for an explicit window.
     *
     * @param widthPx  landscape window width in pixels
     * @param heightPx landscape window height in pixels
     * @param densityDpi device density in dpi (dots per inch, e.g. 369)
     */
    fun computeForWindow(widthPx: Float, heightPx: Float, densityDpi: Float): XmbLayoutAdjust {
        val d = densityDpi / 160f
        val W_dp = widthPx / d
        val H_dp = heightPx / d

        // uiScale = automatic, aspect-safe canvas scale; bounded by the smaller axis ratio
        // so a near-square/foldable panel is width-limited instead of over-magnifying off the
        // height ratio.
        val uiScale = minOf(H_dp / XMB_BASELINE_HEIGHT_DP, W_dp / XMB_BASELINE_WIDTH_DP)
            .coerceIn(1f, XMB_MAX_SCALE)

        // Step 1 — SCALE: drive the effective canvas height to the tuned 354.546 dp.
        val rawScale = (H_dp / PSP_CANVAS_HEIGHT_DP) / uiScale
        val scale = rawScale.coerceIn(XmbLayoutAdjust.SCALE_MIN, XmbLayoutAdjust.SCALE_MAX)

        // Recompute the canvas the clamped scale actually produced.
        val totalScale = uiScale * scale
        val canvasH = H_dp / totalScale
        val canvasW = W_dp / totalScale

        // Step 2 — VERTICAL: hold the caticon band at 34.436 % of canvas height.
        val barTopFraction = (PSP_CATICON_CENTER_FRACTION - BAR_TOP_SUB_DP / canvasH)
            .coerceIn(XmbLayoutAdjust.TOP_MIN, XmbLayoutAdjust.TOP_MAX)

        // Step 3 — HORIZONTAL: hold the cross icon-centre line at PSP_CROSS_ANCHOR_X_DP
        // from the left edge of the canvas.
        val barLeftFraction = ((PSP_CROSS_ANCHOR_X_DP - COLUMN_BASE_INSET_DP) / canvasW)
            .coerceIn(XmbLayoutAdjust.LEFT_MIN, XmbLayoutAdjust.LEFT_MAX)

        return XmbLayoutAdjust(
            scale = scale,
            barLeftFraction = barLeftFraction,
            barTopFraction = barTopFraction,
        )
    }

    /**
     * Compute the auto-fit values as explicit floats, without constructing an
     * [XmbLayoutAdjust] (useful for previews / tests that want to assert each axis).
     */
    fun computeRawForWindow(widthPx: Float, heightPx: Float, densityDpi: Float): AutoFitValues {
        val d = densityDpi / 160f
        val W_dp = widthPx / d
        val H_dp = heightPx / d

        val uiScale = minOf(H_dp / XMB_BASELINE_HEIGHT_DP, W_dp / XMB_BASELINE_WIDTH_DP)
            .coerceIn(1f, XMB_MAX_SCALE)

        val rawScale = (H_dp / PSP_CANVAS_HEIGHT_DP) / uiScale
        val scale = rawScale.coerceIn(XmbLayoutAdjust.SCALE_MIN, XmbLayoutAdjust.SCALE_MAX)

        val totalScale = uiScale * scale
        val canvasH = H_dp / totalScale
        val canvasW = W_dp / totalScale

        val barTopFraction = (PSP_CATICON_CENTER_FRACTION - BAR_TOP_SUB_DP / canvasH)
            .coerceIn(XmbLayoutAdjust.TOP_MIN, XmbLayoutAdjust.TOP_MAX)

        val barLeftFraction = ((PSP_CROSS_ANCHOR_X_DP - COLUMN_BASE_INSET_DP) / canvasW)
            .coerceIn(XmbLayoutAdjust.LEFT_MIN, XmbLayoutAdjust.LEFT_MAX)

        return AutoFitValues(
            uiScale = uiScale,
            scale = scale,
            canvasH = canvasH,
            canvasW = canvasW,
            barTopFraction = barTopFraction,
            barLeftFraction = barLeftFraction,
        )
    }

    /** Return type for [computeRawForWindow] — exposes the intermediate canvas so tests can assert it. */
    data class AutoFitValues(
        val uiScale: Float,
        val scale: Float,
        val canvasH: Float,
        val canvasW: Float,
        val barTopFraction: Float,
        val barLeftFraction: Float,
    )
}
