package com.playfieldportal.feature.xmb.ui.detail

import kotlin.math.floor

/**
 * The shape a tab's result tiles are judged at (C16 "Studio layout rework", grid capacity rules).
 * [aspect] is width ÷ height; [minTileWidthDp] is the narrowest tile still worth judging artwork in.
 */
enum class StudioTileClass(val aspect: Double, val minTileWidthDp: Double) {
    LANDSCAPE(aspect = 1.5, minTileWidthDp = 112.0),
    PORTRAIT(aspect = 0.7, minTileWidthDp = 80.0),
    SQUARE(aspect = 1.0, minTileWidthDp = 96.0),
    WIDE(aspect = 2.0, minTileWidthDp = 140.0),
}

/**
 * How many result tiles one page of the grid holds (AD-17: a page is one measured gridful).
 *
 * Pure: the screen measures the grid slot, the ViewModel owns the result. A larger screen gets more
 * columns and rows, never bigger tiles (AD-16).
 */
data class StudioGridCapacity(val columns: Int, val rows: Int) {

    val pageSize: Int get() = columns * rows

    companion object {
        /** Until the screen reports a size: the old fixed 4 × 5, so nothing changes before it does. */
        val UNMEASURED = StudioGridCapacity(columns = 4, rows = 5)

        private const val GAP_DP = 8.0
        private const val MIN_COLUMNS = 3
        private const val MAX_COLUMNS = 8
        private const val MIN_ROWS = 1
        private const val MAX_ROWS = 6

        // An exact fit must not floor one short through floating-point error. Kept tiny: a real
        // near-miss (a 570 × 223 slot of square tiles fits 1.998 rows) must still round down.
        private const val FIT_EPSILON = 1e-6

        fun of(widthDp: Float, heightDp: Float, tileClass: StudioTileClass): StudioGridCapacity {
            val width = widthDp.toDouble()
            val height = heightDp.toDouble()
            val columns = fits(width, tileClass.minTileWidthDp).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
            // A slot narrower than three minimum tiles would give a negative tile; zero keeps the
            // row division finite and lets the clamp decide.
            val tileWidth = ((width - GAP_DP * (columns - 1)) / columns).coerceAtLeast(0.0)
            val tileHeight = tileWidth / tileClass.aspect
            val rows = fits(height, tileHeight).coerceIn(MIN_ROWS, MAX_ROWS)
            return StudioGridCapacity(columns, rows)
        }

        /** How many [tile]s, with a gap between each pair, fit along [span]. */
        private fun fits(span: Double, tile: Double): Int =
            floor((span + GAP_DP) / (tile + GAP_DP) + FIT_EPSILON).toInt()
    }
}
