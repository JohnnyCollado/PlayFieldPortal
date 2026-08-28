package com.playfieldportal.feature.settings.ui

/** A logical item exposed to controller navigation. */
data class ControllerNavItem(
    val key: String,
    val focusable: Boolean = true,
    val selectable: Boolean = true,
    val enabled: Boolean = true,
    val onSelect: (() -> Unit)? = null,
    // Inline actions rendered in the row's trailing slot, reached via LEFT/RIGHT (e.g. a
    // root row's Replace/Remove buttons). They never participate in vertical movement.
    val trailingActions: List<ControllerNavItem> = emptyList(),
)

/**
 * Ordered controller navigation for vertical screens. UI code owns focus request/scrolling; this
 * class owns the logical selection and never allows navigation to escape the screen.
 *
 * The scaffold feeds the current item list via [updateItems]; [move] traverses the navigable items
 * in list order, [moveHorizontal] steps into a row's inline [trailingActions] (LEFT/RIGHT),
 * [select] dispatches to the focused item, and [focusFirst]/[setFocused] establish or realign
 * focus. Items disappear as rows leave composition — the focused key then recovers to the nearest
 * surviving navigable item by list order.
 */
class ControllerNavigationState {
    private var items: List<ControllerNavItem> = emptyList()
    var focusedKey: String? = null
        private set

    fun updateItems(newItems: List<ControllerNavItem>) {
        val previousItems = items
        val previousKey = focusedKey
        items = newItems
        val navigable = navigableItems()
        val navigableKeys = navigable.flatMap { row ->
            listOf(row.key) + row.trailingActions
                .filter { it.focusable && it.enabled }
                .map { it.key }
        }
        focusedKey = when {
            previousKey != null && previousKey in navigableKeys -> previousKey
            previousKey != null -> {
                // The focused item disappeared. If it was an inline action, fall back to its
                // owning row; otherwise recover to the nearest survivor by list order.
                val ownerRow = previousItems.firstOrNull { it.trailingActions.any { a -> a.key == previousKey } }
                when {
                    ownerRow != null && navigable.any { it.key == ownerRow.key } -> ownerRow.key
                    else -> {
                        val previousIndex = previousItems.indexOfFirst { it.key == previousKey }
                        if (previousIndex >= 0) {
                            navigable.minByOrNull { kotlin.math.abs(newItems.indexOf(it) - previousIndex) }?.key
                        } else {
                            navigable.firstOrNull()?.key
                        }
                    }
                }
            }
            else -> navigable.firstOrNull()?.key
        }
    }

    fun move(delta: Int): String? {
        val navigable = navigableItems()
        if (navigable.isEmpty()) {
            focusedKey = null
            return null
        }
        // Focus on an inline action: exit back to its owning row before moving vertically.
        val owner = items.firstOrNull { it.trailingActions.any { a -> a.key == focusedKey } }
        val baseKey = owner?.key ?: focusedKey
        val current = navigable.indexOfFirst { it.key == baseKey }
        val target = if (current < 0) 0 else (current + delta).coerceIn(0, navigable.lastIndex)
        focusedKey = navigable[target].key
        return focusedKey
    }

    /**
     * Horizontal movement between a row and its inline trailing actions. RIGHT enters the first
     * action; from an action, LEFT/RIGHT step between them (clamped), and LEFT past the first
     * action returns to the row. Returns null when the current row has no actions (no-op).
     */
    fun moveHorizontal(delta: Int): String? {
        val owner = items.firstOrNull { it.trailingActions.any { a -> a.key == focusedKey } }
        val navigableActions = (owner ?: items.firstOrNull { it.key == focusedKey })
            ?.trailingActions
            ?.filter { it.focusable && it.enabled }
            ?: return null
        if (navigableActions.isEmpty()) return null
        if (owner != null) {
            val index = navigableActions.indexOfFirst { it.key == focusedKey }
            val targetIndex = index + delta
            focusedKey = when {
                targetIndex < 0 -> owner.key
                targetIndex >= navigableActions.size -> focusedKey
                else -> navigableActions[targetIndex].key
            }
        } else {
            // On the row: RIGHT enters the first action; LEFT stays put.
            focusedKey = if (delta > 0) navigableActions.first().key else focusedKey
        }
        return focusedKey
    }

    fun focusFirst(): String? {
        focusedKey = navigableItems().firstOrNull()?.key
        return focusedKey
    }

    /** Realign the focused key with actual (e.g. Compose-driven) focus; ignores unknown keys. */
    fun setFocused(key: String?) {
        if (key == null) {
            focusedKey = null
            return
        }
        val known = items.any { it.key == key } ||
            items.any { it.trailingActions.any { a -> a.key == key } }
        if (known) focusedKey = key
    }

    fun select(): Boolean {
        val item = items.firstOrNull { it.key == focusedKey }
            ?: items.flatMap { it.trailingActions }.firstOrNull { it.key == focusedKey }
            ?: return false
        if (!item.focusable || !item.selectable || !item.enabled) return false
        item.onSelect?.invoke() ?: return false
        return true
    }

    private fun navigableItems(): List<ControllerNavItem> =
        items.filter { it.focusable && it.enabled }
}
