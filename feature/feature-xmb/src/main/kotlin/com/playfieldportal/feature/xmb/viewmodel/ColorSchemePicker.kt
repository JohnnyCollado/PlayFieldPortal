package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.XmbSchemeVariant
import com.playfieldportal.core.domain.model.supportsVariants

// ── The colour-scheme picker's tint and depth steppers ────────────────────────
//
// Pure moves on [ColorSchemePickerState]; XMBViewModel voices them and writes the preview.

/** The highlighted row. */
val ColorSchemePickerState.selected: ColorSchemeOption? get() = options.getOrNull(selectedIndex)

/** True on a fixed preset: the monthly scheme and Custom have no steps to offer. */
val ColorSchemePickerState.variantsAvailable: Boolean
    get() = selected?.scheme?.supportsVariants == true

/** The colour the highlighted row previews: its swatch, with the steps applied where it has them. */
val ColorSchemePickerState.previewArgb: Long
    get() {
        val swatch = selected?.swatch ?: 0xFF888888L
        return if (variantsAvailable) variant.applyTo(swatch) else swatch
    }

/** The cursor on [index]; a different row starts from its own standard colour. */
fun ColorSchemePickerState.movedTo(index: Int): ColorSchemePickerState =
    if (index == selectedIndex) this else copy(selectedIndex = index, variant = XmbSchemeVariant.STANDARD)

/**
 * [tint] and [depth] more steps on the highlighted preset, or null when that changes nothing: the
 * row has no steps, or the range has ended.
 */
fun ColorSchemePickerState.stepped(tint: Int = 0, depth: Int = 0): ColorSchemePickerState? {
    if (!variantsAvailable) return null
    val next = variant.withTint(tint).withDepth(depth)
    return if (next == variant) null else copy(variant = next)
}
