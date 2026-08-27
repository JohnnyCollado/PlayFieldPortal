package com.playfieldportal.feature.settings.ui

/** A logical item exposed to controller navigation. */
data class ControllerNavItem(
    val key: String,
    val focusable: Boolean = true,
    val selectable: Boolean = true,
    val enabled: Boolean = true,
    val onSelect: (() -> Unit)? = null,
)

/**
 * Ordered controller navigation for vertical screens. UI code owns focus request/scrolling; this
 * class owns the logical selection and never allows navigation to escape the screen.
 */
class ControllerNavigationState {
    private var items: List<ControllerNavItem> = emptyList()
    var focusedKey: String? = null
        private set

    fun updateItems(newItems: List<ControllerNavItem>) {
        items = newItems
        val navigable = navigableItems()
        focusedKey = focusedKey
            ?.takeIf { key -> navigable.any { it.key == key } }
            ?: navigable.firstOrNull()?.key
    }

    fun move(delta: Int): String? {
        val navigable = navigableItems()
        if (navigable.isEmpty()) {
            focusedKey = null
            return null
        }
        val current = navigable.indexOfFirst { it.key == focusedKey }
        val target = if (current < 0) 0 else (current + delta).coerceIn(0, navigable.lastIndex)
        focusedKey = navigable[target].key
        return focusedKey
    }

    fun focusFirst(): String? {
        focusedKey = navigableItems().firstOrNull()?.key
        return focusedKey
    }

    fun select(): Boolean {
        val item = items.firstOrNull { it.key == focusedKey } ?: return false
        if (!item.focusable || !item.selectable || !item.enabled) return false
        item.onSelect?.invoke() ?: return false
        return true
    }

    private fun navigableItems(): List<ControllerNavItem> =
        items.filter { it.focusable && it.enabled }
}
