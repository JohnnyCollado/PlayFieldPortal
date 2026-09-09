package com.playfieldportal.core.domain.model

/**
 * How text separates itself from whatever is behind it — the text-side twin of
 * [IconLegibilityStyle], and deliberately the same shape so the two settings rows read alike.
 *
 * The problem is the same one the icon setting solves, one layer up: the launcher paints its own
 * translucent gradients over a user wallpaper, so the backdrop luminance sweeps across the
 * crossover where neither white nor black text passes everywhere. A fill color alone cannot fix
 * that, and neither can a drop shadow — a shadow adds a dark *edge* without changing the *fill*
 * relationship.
 *
 *  - [NONE]     the fill on its own.
 *  - [SHADOW]   today's rendering: the standard directional drop shadow. The always-on floor.
 *  - [OUTLINE]  a stroked copy behind the fill. Costs a second `Text` node, so it is for the few
 *               large non-list labels, never inside a `LazyColumn`.
 *  - [PLATE]    a text-shaped rounded plate of the opposite polarity, drawn in the label's own
 *               `drawBehind` at a solved alpha — no extra layout node, and alpha solves to 0
 *               wherever the backdrop is already dark enough.
 *  - [AUTO]     the shipped default: measure, then use the weakest instrument that works. It reads
 *               the existing `display_text_shadow` preference as "may I use a shadow?", so no
 *               existing user choice is retired or overridden.
 */
enum class TextLegibilityStyle(val label: String) {
    NONE("None"),
    SHADOW("Drop Shadow"),
    OUTLINE("Outline"),
    PLATE("Contrast Plate"),
    AUTO("Automatic");

    companion object {
        val DEFAULT = AUTO

        /** Tolerant parse for the persisted preference; unknown/blank falls back to [DEFAULT]. */
        fun fromName(value: String?): TextLegibilityStyle =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
