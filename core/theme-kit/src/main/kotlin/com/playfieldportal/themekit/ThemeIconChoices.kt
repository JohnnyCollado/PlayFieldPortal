package com.playfieldportal.themekit

/**
 * The icons a theme offers as a source for one slot, grouped the way both the launcher grid and
 * the Studio dialog show them. Pure: it names and orders; the caller supplies the pixels.
 */
object ThemeIconChoices {

    const val THEME_ICONS_TITLE = "Theme icons"
    const val PTF_ICONS_TITLE = "More from this PSP theme"

    /** Where a tile's image lives: another slot's icon, or an extra PSP body record. */
    sealed interface Source {
        data class Slot(val key: String) : Source
        data class Ptf(val ref: PtfIcons.SlotRef) : Source
    }

    data class Choice(val source: Source, val label: String)

    data class Section(val title: String, val choices: List<Choice>)

    /**
     * "Theme icons" lists [slotKeys] in registry order, labelled by display name; keys that are
     * not registered are ignored. Slots whose [artKey] is equal and non-null show the same image,
     * so only the first in registry order is kept (a PTF's Memory Stick art fills several slots).
     * "More from this PSP theme" lists [ptfRefs] by (group, index): the PSP meaning where known,
     * else "Icon n" counting 1, 2, 3 across the unnamed tiles. Empty sections are omitted.
     */
    fun sections(
        slotKeys: Collection<String>,
        ptfRefs: Collection<PtfIcons.SlotRef>,
        artKey: (String) -> Any? = { null },
    ): List<Section> {
        val wanted = slotKeys.toSet()
        val seenArt = HashSet<Any>()
        val slots = CustomizableIcons.ALL
            .filter { it.key in wanted }
            .filter { slot -> artKey(slot.key)?.let(seenArt::add) ?: true }
            .map { Choice(Source.Slot(it.key), it.displayName) }

        var unnamed = 0
        val ptf = ptfRefs
            .distinct()
            .sortedWith(compareBy({ it.group }, { it.index }))
            .map { ref -> Choice(Source.Ptf(ref), PtfIcons.labelFor(ref) ?: "Icon ${++unnamed}") }

        return buildList {
            if (slots.isNotEmpty()) add(Section(THEME_ICONS_TITLE, slots))
            if (ptf.isNotEmpty()) add(Section(PTF_ICONS_TITLE, ptf))
        }
    }
}
