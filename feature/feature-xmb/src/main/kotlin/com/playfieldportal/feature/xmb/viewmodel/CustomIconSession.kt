package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import com.playfieldportal.core.ui.icons.catbarSlotKeyFor
import com.playfieldportal.themekit.IconColumnRun
import com.playfieldportal.themekit.IconEditorLayout
import com.playfieldportal.themekit.IconEditorTab
import com.playfieldportal.themekit.IconSlot

// ── Live "Customize XMB Icons" editor ─────────────────────────────────────────
// A translucent editor rendered OVER the real XMB (settings closed), shaped after
// XmbLayoutAdjustSession. There is deliberately NO draft/commit pair: picks apply to the
// CustomIconStore immediately (the XMB behind updates as each lands — the whole point of a
// live editor), and Reset / Reset All are the undo. The tabs and their order come from
// IconEditorLayout, the list the Theme Studio shows too; [tabIndex]/[slotIndex] place the
// gamepad cursor; [message] is the last import outcome.
data class CustomIconSession(
    val tabIndex: Int = 0,
    val slotIndex: Int = 0,
    val message: String? = null,
    /** Bumped when the store's contents change, so the overlay re-reads the icons map. */
    val revision: Int = 0,
    /** The visible bar left to right as the editor opened on it: crossbar slot keys and user-category keys. */
    val barKeys: List<String> = IconEditorLayout.DEFAULT_BAR_ORDER,
    /** The bar's user categories, snapshotted when the editor opens; listed where they sit on the bar. */
    val userCategorySlots: List<UserCategoryIconSlot> = emptyList(),
) {
    val tabs: List<IconEditorTab> get() = IconEditorTab.entries

    /** The tab the cursor is currently on. */
    val tab: IconEditorTab get() = tabs[tabIndex]

    /** The bar's crossbar slot keys, for the shared layout's ordering. */
    private val crossbarOrder: List<String> get() = barKeys.filter { it.startsWith(CROSSBAR_PREFIX) }

    /** The Items tab's column runs in bar order; empty on every other tab. */
    fun runs(): List<IconColumnRun> =
        if (tab == IconEditorTab.ITEMS) IconEditorLayout.itemRuns(crossbarOrder) else emptyList()

    /** The current tab's slots. Crossbar is the bar left to right (user categories in place), then the hidden built-ins. */
    fun slots(): List<IconSlot> {
        if (tab != IconEditorTab.CROSSBAR) return IconEditorLayout.slots(tab, crossbarOrder)
        val builtIns = IconEditorLayout.crossbar(crossbarOrder).associateBy { it.key }
        val users = userCategorySlots.associateBy { it.key }
        val onBar = barKeys.mapNotNull { key -> users[key]?.toIconSlot() ?: builtIns[key] }.distinctBy { it.key }
        val listed = onBar.map { it.key }.toSet()
        return onBar + builtIns.values.filter { it.key !in listed }
    }

    /** The focused slot, or null when the tab has no slot at [slotIndex]. */
    val focusedSlot: IconSlot?
        get() = slots().getOrNull(slotIndex)

    /** Index into [runs] of the focused slot's column; -1 off the Items tab. */
    val focusedRunIndex: Int
        get() = runStarts().indexOfLast { it <= slotIndex }

    /**
     * The slot index D-pad up / down lands on in the Items tab: down goes to the next column's
     * first slot, up to the start of this column (or, from its start, the previous one's). Null at
     * either end and on every other tab, so the cursor stays put.
     */
    fun columnJump(dir: Int): Int? {
        val starts = runStarts()
        val current = focusedRunIndex
        if (current < 0) return null
        return when {
            dir > 0 -> starts.getOrNull(current + 1)
            dir < 0 && slotIndex > starts[current] -> starts[current]
            dir < 0 -> starts.getOrNull(current - 1)
            else -> null
        }
    }

    private fun runStarts(): List<Int> =
        runs().runningFold(0) { start, run -> start + run.slots.size }.dropLast(1)

    private fun UserCategoryIconSlot.toIconSlot() =
        IconSlot(key = key, group = IconSlot.Group.CATEGORY_BAR, displayName = displayName, templateSizePx = USER_SLOT_TEMPLATE_PX)

    private companion object {
        const val CROSSBAR_PREFIX = "catbar_"

        // Unused for user slots (no template export); matches the catbar slots' canvas.
        const val USER_SLOT_TEMPLATE_PX = 256
    }
}

/**
 * The visible bar left to right as editor keys: a user category's `usercat_` key, a built-in's
 * crossbar slot (the one its glyph is themed through). Hidden categories and glyphs no slot
 * themes are left out.
 */
fun crossbarEditorKeys(categories: List<Category>): List<String> =
    categories.filter { it.isVisible }.mapNotNull { category ->
        UserCategoryIconKeys.keyFor(category.id) ?: catbarSlotKeyFor(category.iconKey)
    }.distinct()

/** A fresh editor session on the Crossbar tab for the bar's [categories]. */
fun customIconSessionFor(categories: List<Category>): CustomIconSession =
    CustomIconSession(
        barKeys = crossbarEditorKeys(categories),
        userCategorySlots = userCategoryIconSlots(categories),
    )
