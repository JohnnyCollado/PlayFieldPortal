package com.playfieldportal.studio

import com.playfieldportal.studio.io.IconPackReport
import com.playfieldportal.themekit.IconGifSupport
import com.playfieldportal.themekit.IconSlot

/** The picker's chips: the groups of [EditableSlots], the only slots a Studio theme replaces. */
enum class PickerGroup(val label: String) {
    CROSSBAR("Crossbar"),
    ITEMS("Items"),
    CONSOLES("Consoles"),
}

/** What the search box and the chips/toggles currently ask for. All set criteria must hold. */
data class PickerQuery(
    val search: String = "",
    val group: PickerGroup? = null,
    /** Only slots the preview currently shows. */
    val onScreen: Boolean = false,
    val customizedOnly: Boolean = false,
)

/** One real-size rendering on the slot card; [alpha] mirrors the launcher's unselected dimming. */
data class PreviewSize(val label: String, val dp: Int, val alpha: Float = 1f)

/** What the slot card shows for the selected slot. */
data class SlotCardModel(
    val name: String,
    /** "Built-in", "Custom" or "Custom (GIF n frames)". */
    val status: String,
    /** "key · group · template px". */
    val detail: String,
    val isCustom: Boolean,
    /** Frames of an animated GIF; 1 for everything else. */
    val frameCount: Int,
)

/** Pure picker logic: no Compose, no state, so every rule here is unit-tested. */
object IconPicker {

    /** A crossbar slot for a category the launcher never seeds (custom categories can pick its icon). */
    private const val FAVORITES_CATEGORY_ICON = "catbar_favorites"

    fun groupOf(slot: IconSlot): PickerGroup = when (slot.group) {
        // The XMB seeds no Favorites category; its icon is one a custom category can pick, so it lists with the items.
        IconSlot.Group.CATEGORY_BAR -> if (slot.key == FAVORITES_CATEGORY_ICON) PickerGroup.ITEMS else PickerGroup.CROSSBAR
        IconSlot.Group.CONSOLE -> PickerGroup.CONSOLES
        else -> PickerGroup.ITEMS
    }

    /** Slots per chip, in chip order (always totals, independent of any other filter). */
    fun counts(): Map<PickerGroup, Int> {
        val byGroup = EditableSlots.ALL.groupingBy(::groupOf).eachCount()
        return PickerGroup.entries.associateWith { byGroup[it] ?: 0 }
    }

    private fun compact(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Matches the display name, the key, or the chip name, ignoring case, spacing and punctuation
     * (`PS 3` finds `PlayStation 3` and `sysicon_ps3`). Console display names are the platform names.
     */
    fun matchesSearch(slot: IconSlot, search: String): Boolean {
        val q = compact(search)
        if (q.isEmpty()) return true
        return q in compact(slot.displayName) || q in compact(slot.key) || q in compact(groupOf(slot).label)
    }

    /** [customized] = keys with an override; [onScreen] = keys the preview shows (see [onScreenKeys]). */
    fun filter(query: PickerQuery, customized: Set<String>, onScreen: Set<String>): List<IconSlot> =
        EditableSlots.ALL.filter { slot ->
            (query.group == null || groupOf(slot) == query.group) &&
                matchesSearch(slot, query.search) &&
                (!query.onScreen || slot.key in onScreen) &&
                (!query.customizedOnly || slot.key in customized)
        }

    // ── On screen ────────────────────────────────────────────────────────────

    /** The item rows (and medallions) each crossbar category lists; the crossbar and status strip are always up. */
    private fun categoryKeys(categoryKey: String): Set<String> {
        val matches: (String) -> Boolean = when (categoryKey) {
            "catbar_video" -> { k -> k.startsWith("item_video_") || k == "item_playlist" || k == "item_memcard_video" || k == "item_add" }
            "catbar_music" -> { k -> k.startsWith("item_music_") || k == "item_playlist" || k == "item_memcard_music" || k == "item_add" }
            "catbar_photos" -> { k -> k.startsWith("item_photo_") || k == "item_camera" || k == "item_memcard_photos" || k == "item_add" }
            "catbar_games" -> { k -> k == "item_memcard_games" || k == "item_add" || k == "item_missing" }
            // Installed apps draw their own icons; Add Apps is the one slot these lists show.
            "catbar_network", "catbar_appstore" -> { k -> k == "item_add" }
            "catbar_social" -> { k -> k.startsWith("item_social_") }
            "catbar_achievements" -> { k -> k.startsWith("item_shiba_") || k.startsWith("shiba_coin_") }
            "catbar_settings" -> { k -> k == "item_settings" }
            else -> { _ -> false }
        }
        return EditableSlots.ALL.map { it.key }.filter(matches).toSet()
    }

    /**
     * The slots the preview is showing, fed by its navigation state: the selected crossbar
     * [categoryKey] (a `catbar_*` key) and [shown], the slot keys of the rows actually on screen
     * (drilled lists, sibling column, and `menu_check` while a menu is open — see
     * `PreviewNav.shownSlotKeys`). The crossbar and status strip are always up.
     */
    fun onScreenKeys(categoryKey: String = "catbar_games", shown: Set<String> = emptySet()): Set<String> {
        val crossbar = EditableSlots.group(IconSlot.Group.CATEGORY_BAR).map { it.key }.toSet() - FAVORITES_CATEGORY_ICON
        // Only editable slots can be "on screen" in the picker; the strip and menus keep the launcher's art.
        return crossbar + categoryKeys(categoryKey) + shown.filter(EditableSlots::isEditable)
    }

    // ── Slot card ────────────────────────────────────────────────────────────

    /** [extension] / [bytes] are the slot's override ("png"/"gif" and its encoded bytes), or null when built-in. */
    fun cardModel(slot: IconSlot, extension: String?, bytes: ByteArray?): SlotCardModel {
        val custom = extension != null
        val gif = custom && extension == "gif" && bytes != null
        val frames = if (gif) IconGifSupport.countFrames(bytes).coerceAtLeast(1) else 1
        val status = when {
            gif && frames > 1 -> "Custom (GIF $frames frames)"
            custom -> "Custom"
            else -> "Built-in"
        }
        return SlotCardModel(
            name = slot.displayName,
            status = status,
            detail = "${slot.key} · ${groupOf(slot).label} · ${slot.templateSizePx} px",
            isCustom = custom,
            frameCount = frames,
        )
    }

    /** Real-size renderings, from the launcher's render sites (crossbar 72/56 at .58, rows, strip). */
    fun previewSizes(slot: IconSlot): List<PreviewSize> = when (groupOf(slot)) {
        PickerGroup.CROSSBAR, PickerGroup.CONSOLES ->
            listOf(PreviewSize("Selected", 72), PreviewSize("Unselected", 56, 0.58f), PreviewSize("In list", 40))
        PickerGroup.ITEMS -> listOf(PreviewSize("Selected", 52), PreviewSize("In list", 40))
    }

    // ── Pack report ──────────────────────────────────────────────────────────

    /** Plain-text lines for the import report dialog; a refused pack reports only its reason. */
    fun packSummary(report: IconPackReport): List<String> {
        report.error?.let { return listOf(it) }
        return buildList {
            add(
                "${report.source}: ${report.added.size} added, ${report.replaced.size} replaced, " +
                    "${report.unmatched.size} unmatched, ${report.rejected.size} rejected",
            )
            report.unmatched.forEach { u ->
                add(if (u.suggestion != null) "${u.name} - did you mean ${u.suggestion}?" else "${u.name} - no matching slot")
            }
            report.rejected.forEach { add("${it.name} - ${it.reason}") }
        }
    }
}
