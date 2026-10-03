package com.playfieldportal.core.navigation

/** One cell of a [FlowGrid]: a node's key and its width along the row, in any consistent unit. */
data class FlowCell(val key: String, val width: Float)

/**
 * A free-flowing grid: rows of cells of their own widths, laid left to right from each row's
 * start, [spacing] apart. The game picker packs box art of different shapes this way; the app
 * picker's equal tiles make it a plain fixed-column grid.
 *
 * LEFT/RIGHT step within a row and stop at its ends. UP/DOWN land on the cell in the next row
 * whose centre is nearest the cursor's (ties to the left), so a short row below a long one is still
 * reached, and a column of mixed widths still reads as a column. Nothing wraps.
 */
class FlowGrid(val rows: List<List<FlowCell>>, val spacing: Float = 0f) {

    /** Every key, row by row: the registration order a context falls back on. */
    val keys: List<String> = rows.flatMap { row -> row.map { it.key } }

    /** The key one step [direction] from [fromKey], or null at an edge or for a key not in the grid. */
    fun move(fromKey: String, direction: NavigationDirection): String? {
        val r = rows.indexOfFirst { row -> row.any { it.key == fromKey } }
        if (r < 0) return null
        val row = rows[r]
        val c = row.indexOfFirst { it.key == fromKey }
        return when (direction) {
            NavigationDirection.LEFT -> row.getOrNull(c - 1)?.key
            NavigationDirection.RIGHT -> row.getOrNull(c + 1)?.key
            NavigationDirection.UP, NavigationDirection.DOWN -> {
                val target = rows.getOrNull(if (direction == NavigationDirection.UP) r - 1 else r + 1) ?: return null
                val x = centreX(row, c)
                target.indices.minByOrNull { kotlin.math.abs(centreX(target, it) - x) }?.let { target[it].key }
            }
        }
    }

    // Cell [index]'s horizontal centre within its left-aligned row.
    private fun centreX(row: List<FlowCell>, index: Int): Float {
        var x = 0f
        for (i in 0 until index) x += row[i].width + spacing
        return x + row[index].width / 2f
    }

    companion object {
        /** [cells] packed into rows no wider than [available]; see [packFlowRows]. */
        fun pack(cells: List<FlowCell>, available: Float, spacing: Float): FlowGrid =
            FlowGrid(packFlowRows(cells.map { it.width }, available, spacing).map { range -> cells.slice(range) }, spacing)
    }
}

/**
 * Packs items of [widths] left to right into rows no wider than [available], [spacing] apart,
 * as index ranges. Every row holds at least one item, so an item wider than the space — or any
 * item before the space is measured ([available] 0) — stands in a row of its own.
 */
fun packFlowRows(widths: List<Float>, available: Float, spacing: Float): List<IntRange> {
    val rows = mutableListOf<IntRange>()
    var start = 0
    var used = 0f
    widths.forEachIndexed { i, w ->
        if (i > start && used + spacing + w > available) {
            rows += start until i
            start = i
            used = w
        } else {
            used = if (i == start) w else used + spacing + w
        }
    }
    if (widths.isNotEmpty()) rows += start..widths.lastIndex
    return rows
}
