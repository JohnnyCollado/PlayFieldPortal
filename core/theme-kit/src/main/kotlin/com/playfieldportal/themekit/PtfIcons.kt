package com.playfieldportal.themekit

/**
 * Which official PSP theme icons fill which of our icon slots.
 *
 * An official `.ptf` stores its icons by position (Sony's Custom Theme Converter manifest names
 * them `group/index`): group 2 holds the category icons, group 3 the first-level items and group 4
 * the second-level items. Groups 3 and 4 alternate body (even index) and focused variant (odd);
 * group 2 has no focus variants, so every record in it is a body, odd indices included.
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
        // The Game category and the Memory Stick fill every slot that draws the same art.
        SlotRef(CATEGORIES, 6) to SharedIconArt.GAMES,
        SlotRef(CATEGORIES, 7) to listOf("catbar_network"),
        SlotRef(FIRST_LEVEL, 2) to SharedIconArt.MEMORY_CARD,
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

    /** Most extra bodies kept per theme; see [extractExtras]. */
    const val MAX_EXTRAS = 64

    /**
     * Whether [ref] is a body image rather than a focus variant or a non-icon group: any group-2
     * record, or an even record of groups 3 and 4.
     */
    fun isBody(ref: SlotRef): Boolean = when (ref.group) {
        CATEGORIES -> true
        FIRST_LEVEL, SECOND_LEVEL -> ref.index % 2 == 0
        else -> false
    }

    /**
     * The body images [dump] carries that [DIRECT] does not map (TV, Extras, the PSP-only items,
     * the default fallbacks), padded square like [extract]. Undecodable records are skipped. At
     * most [MAX_EXTRAS] are kept, the lowest (group, index) first, and the map iterates in that order.
     */
    fun extractExtras(dump: PtfUnpacker.Dump): Map<SlotRef, BmpImage> {
        val bodies = dump.resources
            .filter { it.kind == PtfUnpacker.Resource.Kind.GIM && it.image != null }
            .associateBy { SlotRef(it.slotId, it.sequence) }
        val refs = bodies.keys
            .filter { isBody(it) && it !in DIRECT }
            .sortedWith(compareBy({ it.group }, { it.index }))
            .take(MAX_EXTRAS)
        return buildMap {
            for (ref in refs) bodies.getValue(ref).image?.let { put(ref, padToSquare(it)) }
        }
    }

    private val STEM_INDEXES = 0..255

    /**
     * [ref]'s file name without extension, `<group>_<index>`: the one spelling of an extra's name,
     * shared by the bundle entry (`ptficons/<stem>.png`) and the on-device file. An underscore, not
     * a hyphen, so the entry stays a safe passthrough name for older readers.
     */
    fun fileStem(ref: SlotRef): String = "${ref.group}_${ref.index}"

    /**
     * The record [stem] names, or null unless it is exactly the canonical [fileStem] of a body
     * record ([isBody]) with an index in 0..255.
     */
    fun refForStem(stem: String): SlotRef? {
        val group = stem.substringBefore('_', "").toIntOrNull() ?: return null
        val index = stem.substringAfter('_', "").toIntOrNull() ?: return null
        val ref = SlotRef(group, index)
        return ref.takeIf { index in STEM_INDEXES && isBody(it) && fileStem(it) == stem }
    }

    /** What a non-direct body record is on the PSP, where the repo documents it; null otherwise. */
    fun labelFor(ref: SlotRef): String? = LABELS[ref]

    private val LABELS: Map<SlotRef, String> = mapOf(
        SlotRef(CATEGORIES, 0) to "Default category",
        SlotRef(CATEGORIES, 5) to "TV",
        SlotRef(CATEGORIES, 8) to "Extras",
        SlotRef(FIRST_LEVEL, 0) to "Default item",
        SlotRef(FIRST_LEVEL, 8) to "Game sharing",
        SlotRef(FIRST_LEVEL, 28) to "Power save",
        SlotRef(FIRST_LEVEL, 40) to "Online manual",
        SlotRef(FIRST_LEVEL, 44) to "Internet radio",
        SlotRef(FIRST_LEVEL, 50) to "Internet search",
        SlotRef(SECOND_LEVEL, 0) to "Default sub-item",
    )

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
