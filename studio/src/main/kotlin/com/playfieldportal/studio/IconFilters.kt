package com.playfieldportal.studio

import com.playfieldportal.studio.io.IconPackReport
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconGifSupport
import com.playfieldportal.themekit.IconSlot
import com.playfieldportal.themekit.SYSICON_PLATFORM_IDS

/**
 * The picker's chips. A Studio PRESENTATION grouping (A3): [SHIBA] is the registry's `SHIBA` group
 * plus the three `item_shiba_*` rows, which stay in the registry's `ITEMS` group (keys are the
 * contract, groups are presentation).
 */
enum class PickerGroup(val label: String) {
    CROSSBAR("Crossbar"),
    ITEMS("Items"),
    CONSOLES("Consoles"),
    STATUS("Status strip"),
    SHIBA("Shiba Coins"),
    MEDIA("Media controls"),
    GAME_DETAIL("Game Detail"),
    NOTIFICATIONS("Notifications"),
    MENUS("Menus"),
}

/** What the search box and the chips/toggles currently ask for. All set criteria must hold. */
data class PickerQuery(
    val search: String = "",
    val group: PickerGroup? = null,
    /** Only slots the preview currently shows. */
    val onScreen: Boolean = false,
    val customizedOnly: Boolean = false,
    /** Only slots added in format v4. */
    val newOnly: Boolean = false,
)

/** One real-size rendering on the slot card; [alpha] mirrors the launcher's unselected dimming. */
data class PreviewSize(val label: String, val dp: Int, val alpha: Float = 1f)

/** What the slot card shows for the selected slot. */
data class SlotCardModel(
    val name: String,
    /** "Built-in", "New slot", "Custom" or "Custom (GIF n frames)". */
    val status: String,
    /** "key · group · template px". */
    val detail: String,
    val isCustom: Boolean,
    val isNew: Boolean,
    /** Frames of an animated GIF; 1 for everything else. */
    val frameCount: Int,
)

/** Pure picker logic: no Compose, no state, so every rule here is unit-tested. */
object IconPicker {

    private val V3_STATUS_KEYS = setOf(
        "status_battery_full", "status_battery_high", "status_battery_medium",
        "status_battery_low", "status_battery_charging", "status_bluetooth",
    )
    private val V3_PLATFORM_KEYS: Set<String> = SYSICON_PLATFORM_IDS.map { "sysicon_$it" }.toSet()

    /**
     * Slots added in format v4 (36 - the plan's "+76" counts the 40 consoles as new): everything outside the v3-era 52 icon slots (10 crossbar, 36 items,
     * 6 original status glyphs) and 40 platform consoles. Mirrors the frozen `V3EraReader` key list in
     * theme-kit's tests, which the Studio cannot see.
     */
    val NEW_KEYS: Set<String> = CustomizableIcons.ALL.filter { slot ->
        when (slot.group) {
            IconSlot.Group.CATEGORY_BAR, IconSlot.Group.ITEMS -> false
            IconSlot.Group.STATUS -> slot.key !in V3_STATUS_KEYS
            IconSlot.Group.CONSOLE -> slot.key !in V3_PLATFORM_KEYS
            else -> true
        }
    }.map { it.key }.toSet()

    fun groupOf(slot: IconSlot): PickerGroup = when (slot.group) {
        IconSlot.Group.CATEGORY_BAR -> PickerGroup.CROSSBAR
        IconSlot.Group.ITEMS -> if (slot.key.startsWith("item_shiba_")) PickerGroup.SHIBA else PickerGroup.ITEMS
        IconSlot.Group.STATUS -> PickerGroup.STATUS
        IconSlot.Group.CONSOLE -> PickerGroup.CONSOLES
        IconSlot.Group.SHIBA -> PickerGroup.SHIBA
        IconSlot.Group.MEDIA -> PickerGroup.MEDIA
        IconSlot.Group.GAME_DETAIL -> PickerGroup.GAME_DETAIL
        IconSlot.Group.NOTIFICATIONS -> PickerGroup.NOTIFICATIONS
        IconSlot.Group.MENUS -> PickerGroup.MENUS
    }

    /** Slots per chip, in chip order (always totals, independent of any other filter). */
    fun counts(): Map<PickerGroup, Int> {
        val byGroup = CustomizableIcons.ALL.groupingBy(::groupOf).eachCount()
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
        CustomizableIcons.ALL.filter { slot ->
            (query.group == null || groupOf(slot) == query.group) &&
                matchesSearch(slot, query.search) &&
                (!query.onScreen || slot.key in onScreen) &&
                (!query.customizedOnly || slot.key in customized) &&
                (!query.newOnly || slot.key in NEW_KEYS)
        }

    // ── On screen ────────────────────────────────────────────────────────────

    /** The item rows (and medallions) each crossbar category lists; the crossbar and status strip are always up. */
    private fun categoryKeys(categoryKey: String): Set<String> {
        val matches: (String) -> Boolean = when (categoryKey) {
            "catbar_video" -> { k -> k.startsWith("item_video_") || k == "item_memcard_video" }
            "catbar_music" -> { k -> k.startsWith("item_music_") || k == "item_playlist" || k == "item_memcard_music" }
            "catbar_photos" -> { k -> k.startsWith("item_photo_") || k == "item_camera" || k == "item_memcard_photos" }
            "catbar_games" -> { k -> k == "item_memcard_games" || k == "item_add" || k == "item_missing" }
            "catbar_social" -> { k -> k.startsWith("item_social_") }
            "catbar_achievements" -> { k -> k.startsWith("item_shiba_") || k.startsWith("shiba_coin_") }
            "catbar_settings" -> { k -> k == "item_settings" }
            else -> { _ -> false }
        }
        return CustomizableIcons.ALL.map { it.key }.filter(matches).toSet()
    }

    /**
     * The slots the preview is showing, fed by its navigation state: the selected crossbar
     * [categoryKey] (a `catbar_*` key) and [shown], the slot keys of the rows actually on screen
     * (drilled lists, sibling column, and `menu_check` while a menu is open — see
     * `PreviewNav.shownSlotKeys`). The crossbar and status strip are always up.
     */
    fun onScreenKeys(categoryKey: String = "catbar_video", shown: Set<String> = emptySet()): Set<String> {
        val ofGroup = { g: IconSlot.Group -> CustomizableIcons.group(g).map { it.key }.toSet() }
        return ofGroup(IconSlot.Group.CATEGORY_BAR) + ofGroup(IconSlot.Group.STATUS) + categoryKeys(categoryKey) + shown
    }

    // ── Slot card ────────────────────────────────────────────────────────────

    /** [extension] / [bytes] are the slot's override ("png"/"gif" and its encoded bytes), or null when built-in. */
    fun cardModel(slot: IconSlot, extension: String?, bytes: ByteArray?): SlotCardModel {
        val custom = extension != null
        val isNew = slot.key in NEW_KEYS
        val gif = custom && extension == "gif" && bytes != null
        val frames = if (gif) IconGifSupport.countFrames(bytes).coerceAtLeast(1) else 1
        val status = when {
            gif && frames > 1 -> "Custom (GIF $frames frames)"
            custom -> "Custom"
            isNew -> "New slot"
            else -> "Built-in"
        }
        return SlotCardModel(
            name = slot.displayName,
            status = status,
            detail = "${slot.key} · ${groupOf(slot).label} · ${slot.templateSizePx} px",
            isCustom = custom,
            isNew = isNew,
            frameCount = frames,
        )
    }

    /** Real-size renderings, from the launcher's render sites (crossbar 72/56 at .58, rows, strip). */
    fun previewSizes(slot: IconSlot): List<PreviewSize> = when (groupOf(slot)) {
        PickerGroup.CROSSBAR, PickerGroup.CONSOLES ->
            listOf(PreviewSize("Selected", 72), PreviewSize("Unselected", 56, 0.58f), PreviewSize("In list", 40))
        PickerGroup.ITEMS -> listOf(PreviewSize("Selected", 52), PreviewSize("In list", 40))
        PickerGroup.STATUS -> listOf(PreviewSize("Status strip", 20))
        PickerGroup.SHIBA -> listOf(PreviewSize("Medallion", 48))
        PickerGroup.MEDIA -> listOf(PreviewSize("Transport", 32))
        PickerGroup.GAME_DETAIL -> listOf(PreviewSize("Action row", 28))
        PickerGroup.NOTIFICATIONS -> listOf(PreviewSize("Notification", 24))
        PickerGroup.MENUS -> listOf(PreviewSize("Menu", 24))
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
