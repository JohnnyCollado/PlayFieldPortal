package com.playfieldportal.studio

import com.playfieldportal.themekit.XmbLayoutSpec

/**
 * The 11 saved [XmbLayoutSpec] geometry fields, as data for the Layout section's Advanced editor.
 * [min]/[max] mirror the clamps in `XmbLayoutSpecCodec.sanitize`, which remains the real gate: the
 * ViewModel always runs a write through it, and a test pins the two in agreement.
 */
enum class LayoutField(
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val read: (XmbLayoutSpec) -> Float,
    val write: (XmbLayoutSpec, Float) -> XmbLayoutSpec,
) {
    BAR_TOP("Crossbar top", "× height", 0.05f, 0.45f, 0.01f, { it.barTopFraction }, { s, v -> s.copy(barTopFraction = v) }),
    CONTENT_TOP("Content top padding", "dp", 0f, 120f, 1f, { it.contentTopPaddingDp }, { s, v -> s.copy(contentTopPaddingDp = v) }),
    CATEGORY_ICON_SELECTED("Category icon (selected)", "dp", 16f, 160f, 1f, { it.categoryIconSelectedDp }, { s, v -> s.copy(categoryIconSelectedDp = v) }),
    CATEGORY_ICON("Category icon", "dp", 16f, 160f, 1f, { it.categoryIconDp }, { s, v -> s.copy(categoryIconDp = v) }),
    ITEM_ICON("Item icon", "dp", 16f, 160f, 1f, { it.itemIconDp }, { s, v -> s.copy(itemIconDp = v) }),
    ITEM_ICON_SLOT("Item icon slot", "dp", 16f, 160f, 1f, { it.itemIconSlotDp }, { s, v -> s.copy(itemIconSlotDp = v) }),
    ITEM_TEXT_SELECTED("Item text (selected)", "sp", 8f, 40f, 1f, { it.itemTextSelectedSp }, { s, v -> s.copy(itemTextSelectedSp = v) }),
    ITEM_TEXT("Item text", "sp", 8f, 40f, 1f, { it.itemTextSp }, { s, v -> s.copy(itemTextSp = v) }),
    ITEM_TEXT_GAP("Icon-to-text gap", "dp", 0f, 60f, 1f, { it.itemTextStartGapDp }, { s, v -> s.copy(itemTextStartGapDp = v) }),
    LEFT_ANCHOR("Left anchor extra", "dp", -60f, 120f, 1f, { it.leftAnchorExtraDp }, { s, v -> s.copy(leftAnchorExtraDp = v) }),
    PREVIOUS_RISE("Previous item rise", "rows", 0f, 2f, 0.05f, { it.previousItemRiseRows }, { s, v -> s.copy(previousItemRiseRows = v) }),
}
