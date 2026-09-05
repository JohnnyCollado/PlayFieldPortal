package com.playfieldportal.core.navigation

/**
 * Shared grid-cursor math for the tile grids (App Drawer, installed-app picker).
 *
 * One pure helper instead of a third copy of the same modulo rules. Returns the destination
 * index, or `null` when the move is illegal — edge of a row (no wrap), past the first/last
 * row, or an empty grid — so the caller can no-op rather than strand or wrap the cursor.
 *
 * [columns] must be > 0; [size] is the item count (not the row count), so a short last row
 * never produces an out-of-range index. Uses [NavigationDirection] — the engine's own
 * direction vocabulary — to keep this module free of UI/domain dependencies.
 */
fun gridMove(
    current: Int,
    direction: NavigationDirection,
    columns: Int,
    size: Int,
): Int? {
    if (size <= 0 || columns <= 0) return null
    if (current !in 0 until size) return null
    return when (direction) {
        NavigationDirection.LEFT -> if (current % columns > 0) current - 1 else null
        NavigationDirection.RIGHT ->
            if (current % columns < columns - 1 && current + 1 < size) current + 1 else null
        NavigationDirection.UP -> if (current - columns >= 0) current - columns else null
        NavigationDirection.DOWN -> if (current + columns < size) current + columns else null
    }
}
