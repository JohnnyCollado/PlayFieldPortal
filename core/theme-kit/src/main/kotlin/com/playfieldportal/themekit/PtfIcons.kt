package com.playfieldportal.themekit

/**
 * Which official PSP theme icons fill which of our icon slots.
 *
 * An official `.ptf` stores its icons by position (Sony's Custom Theme Converter manifest names
 * them `group/index`): group 2 holds the category icons, group 3 the first-level items and group 4
 * the second-level items, with even indices the normal body and odd indices the focused variant.
 * Only slots whose meaning is the same on both sides are mapped. A PSP category or item with no
 * exact counterpart here (TV, Extras, the default fallbacks, the PSP-only system items) is left
 * alone, so its slot keeps the built-in glyph.
 */
object PtfIcons {

    /** A record's address inside a theme: the slot table group and the record index within it. */
    data class SlotRef(val group: Int, val index: Int)

    private const val CATEGORIES = 2
    private const val FIRST_LEVEL = 3
    private const val SECOND_LEVEL = 4

    /** PTF body record → the icon keys it fills. One source may fill several keys. */
    val DIRECT: Map<SlotRef, List<String>> = mapOf(
        SlotRef(CATEGORIES, 1) to listOf("catbar_settings"),
        SlotRef(CATEGORIES, 2) to listOf("catbar_photos"),
        SlotRef(CATEGORIES, 3) to listOf("catbar_music"),
        SlotRef(CATEGORIES, 4) to listOf("catbar_video"),
        SlotRef(CATEGORIES, 6) to listOf("catbar_games"),
        SlotRef(CATEGORIES, 7) to listOf("catbar_network"),
        SlotRef(FIRST_LEVEL, 2) to listOf(
            "item_memcard_games", "item_memcard_music", "item_memcard_video", "item_memcard_photos",
        ),
        SlotRef(FIRST_LEVEL, 4) to listOf("item_umd"),
        SlotRef(FIRST_LEVEL, 6) to listOf("item_camera"),
        SlotRef(SECOND_LEVEL, 2) to listOf("item_settings"),
    )

    /**
     * The direct-fit icons [dump] carries, keyed by icon slot, each padded to a transparent square
     * (PSP icons are often 64×48 or otherwise non-square; our slots are square). Records that are
     * missing or did not decode are skipped: icons are best-effort and never fail an import.
     * A source that fills several keys is one shared image.
     */
    fun extract(dump: PtfUnpacker.Dump): Map<String, BmpImage> {
        val bySlot = dump.resources
            .filter { it.kind == PtfUnpacker.Resource.Kind.GIM && it.image != null }
            .associateBy { SlotRef(it.slotId, it.sequence) }
        return buildMap {
            for ((slot, keys) in DIRECT) {
                val image = bySlot[slot]?.image?.let(::padToSquare) ?: continue
                keys.forEach { put(it, image) }
            }
        }
    }

    /**
     * The distinct source images behind [icons], so a fanned-out source counts once. [BmpImage]
     * keeps identity equality, so this is distinct by instance, not by pixels.
     */
    fun tintSources(icons: Map<String, BmpImage>): List<BmpImage> = icons.values.distinct()

    /** [image] centred on a transparent square canvas of its longer side; square input is returned as is. */
    fun padToSquare(image: BmpImage): BmpImage {
        val side = maxOf(image.width, image.height)
        if (image.width == side && image.height == side) return image
        val left = (side - image.width) / 2
        val top = (side - image.height) / 2
        val out = IntArray(side * side)
        for (y in 0 until image.height) {
            image.argb.copyInto(out, (top + y) * side + left, y * image.width, (y + 1) * image.width)
        }
        return BmpImage(side, side, out)
    }
}
