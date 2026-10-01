package com.playfieldportal.core.navigation

/**
 * A cursor on a grid whose cells may span several columns. [anchorColumn] remembers the column
 * the cursor last travelled vertically through, so leaving a wide cell vertically returns to the
 * column it was entered from rather than the cell's first column. Null means "use the cell's
 * first column".
 */
data class SpanGridCursor(val row: Int, val cell: Int, val anchorColumn: Int? = null)

/**
 * One step of [direction] on a grid described by [rows]: each row lists its cells' column spans,
 * and every row spans the same number of columns. LEFT/RIGHT step a cell within the row; UP/DOWN
 * land on the cell in the next row covering the anchor column. Returns null at an edge (no wrap),
 * for an empty grid, or for a cursor outside it. [gridMove] covers the uniform, unspanned case.
 */
fun spanGridMove(rows: List<List<Int>>, from: SpanGridCursor, direction: NavigationDirection): SpanGridCursor? {
    val row = rows.getOrNull(from.row) ?: return null
    if (from.cell !in row.indices) return null
    return when (direction) {
        NavigationDirection.LEFT ->
            if (from.cell > 0) SpanGridCursor(from.row, from.cell - 1) else null
        NavigationDirection.RIGHT ->
            if (from.cell < row.lastIndex) SpanGridCursor(from.row, from.cell + 1) else null
        NavigationDirection.UP -> vertical(rows, from, row, from.row - 1)
        NavigationDirection.DOWN -> vertical(rows, from, row, from.row + 1)
    }
}

private fun vertical(rows: List<List<Int>>, from: SpanGridCursor, row: List<Int>, targetRow: Int): SpanGridCursor? {
    val target = rows.getOrNull(targetRow) ?: return null
    val column = from.anchorColumn ?: firstColumnOf(row, from.cell)
    val cell = cellCovering(target, column) ?: return null
    return SpanGridCursor(targetRow, cell, column)
}

private fun firstColumnOf(row: List<Int>, cell: Int): Int = row.take(cell).sum()

private fun cellCovering(row: List<Int>, column: Int): Int? {
    var start = 0
    row.forEachIndexed { index, span ->
        if (column < start + span) return index
        start += span
    }
    return null
}
