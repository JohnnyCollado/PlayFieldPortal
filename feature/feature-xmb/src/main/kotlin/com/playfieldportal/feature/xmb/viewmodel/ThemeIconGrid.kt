package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.themekit.PtfIcons
import com.playfieldportal.themekit.ThemeIconChoices
import java.io.File

/** Columns of the theme icon grid. One number, shared by [ThemeIconGridNav] and the overlay's grid. */
const val THEME_ICON_GRID_COLUMNS = 6

/**
 * The grid of the applied theme's icons that "Pick" can copy onto [slotKey]. [icons] and [files]
 * hold one entry per tile, in the flattened order of [sections]; [cursor] is the focused tile.
 */
data class ThemeIconGridState(
    val slotKey: String,
    val slotName: String,
    val themeName: String,
    val sections: List<ThemeIconChoices.Section>,
    val icons: List<CustomIcon>,
    val files: List<File>,
    val cursor: Int = 0,
) {
    /** Tiles per section, the shape [ThemeIconGridNav] walks. */
    val sectionSizes: List<Int> get() = sections.map { it.choices.size }

    val title: String get() = "$themeName · icon for $slotName"
}

/**
 * Builds the grid from the two decoded tiers: [slotIcons] (the theme's slot icons by key) and
 * [ptfIcons] (the extra PSP bodies), each with the file it came from. [artKey] identifies a file's
 * image, so identical slot images collapse into one tile. Null when the theme offers no tile.
 */
fun themeIconGridFor(
    slotKey: String,
    slotName: String,
    themeName: String,
    slotIcons: Map<String, Pair<File, CustomIcon>>,
    ptfIcons: Map<PtfIcons.SlotRef, Pair<File, CustomIcon>>,
    artKey: (File) -> Any?,
): ThemeIconGridState? {
    val sections = ThemeIconChoices.sections(slotIcons.keys, ptfIcons.keys) { key -> slotIcons[key]?.first?.let(artKey) }
    if (sections.isEmpty()) return null
    val tiles = sections.flatMap { it.choices }.map { choice ->
        when (val source = choice.source) {
            is ThemeIconChoices.Source.Slot -> slotIcons.getValue(source.key)
            is ThemeIconChoices.Source.Ptf -> ptfIcons.getValue(source.ref)
        }
    }
    return ThemeIconGridState(
        slotKey = slotKey,
        slotName = slotName,
        themeName = themeName,
        sections = sections,
        icons = tiles.map { it.second },
        files = tiles.map { it.first },
    )
}

/** The MIME `CustomIconStore.import` expects for a stored icon file, by its extension. */
fun themeIconMime(file: File): String? = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heif" -> "image/heif"
    else -> null
}

/**
 * Cursor movement over the grid. The tiles are one flattened list laid out in rows of `columns`;
 * each section starts on a new row. LEFT / RIGHT step one tile; UP / DOWN keep the column, clamped
 * to the target row's length. Nothing wraps; any other action leaves the cursor alone.
 */
object ThemeIconGridNav {

    fun move(sectionSizes: List<Int>, columns: Int, index: Int, action: GamepadAction): Int {
        val total = sectionSizes.sum()
        if (total == 0 || columns < 1) return index
        val at = index.coerceIn(0, total - 1)
        return when (action) {
            GamepadAction.NAVIGATE_LEFT -> (at - 1).coerceAtLeast(0)
            GamepadAction.NAVIGATE_RIGHT -> (at + 1).coerceAtMost(total - 1)
            GamepadAction.NAVIGATE_UP -> vertical(sectionSizes, columns, at, -1)
            GamepadAction.NAVIGATE_DOWN -> vertical(sectionSizes, columns, at, +1)
            else -> index
        }
    }

    private fun vertical(sectionSizes: List<Int>, columns: Int, at: Int, dir: Int): Int {
        // Each visual row as (first flat index, length).
        val rows = buildList {
            var sectionStart = 0
            for (size in sectionSizes) {
                for (offset in 0 until size step columns) add((sectionStart + offset) to minOf(columns, size - offset))
                sectionStart += size
            }
        }
        val row = rows.indexOfLast { it.first <= at }
        val target = rows.getOrNull(row + dir) ?: return at
        return target.first + minOf(at - rows[row].first, target.second - 1)
    }
}
