package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.navigation.NavigationEngine
import com.playfieldportal.core.navigation.NavigationNode

/**
 * A logical item exposed to controller navigation.
 *
 * This is the Settings-side of the shared navigation contract (spec §17): the adapter maps these
 * onto the generic [NavigationNode]s the core understands. The core never knows what a row can do —
 * it only navigates generic nodes.
 */
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

/** Maps a Settings row onto the generic navigation-core node contract (spec §3, §4). */
internal fun ControllerNavItem.toNavigationNode(): NavigationNode = NavigationNode(
    key = key,
    focusable = focusable,
    selectable = selectable,
    enabled = enabled,
    onSelect = onSelect,
    // Inline trailing actions become child nodes (spec §4): reached via LEFT/RIGHT,
    // never vertically, and clamps at the ends.
    children = trailingActions.map { it.toNavigationNode() },
)

/**
 * Settings adapter for the unified navigation core (Phase 2—3 of the migration).
 *
 * This class drives a core [NavigationEngine] — cycling one [com.playfieldportal.core.navigation.NavigationContext]
 * per screen — while preserving Settings' legacy public surface ([updateItems], [move],
 * [moveHorizontal], [focusFirst], [setFocused], [select], [focusedKey]) so the scaffold and its
 * tests keep working unchanged.
 *
 * Ownership stays with the core: stable-key focus preservation, nearest-survivor recovery
 * (visual geometry → order fallback), no-wrap clamping and inline-action traversal all come
 * from the engine. This class only translates between Settings rows and generic nodes.
 */
class ControllerNavigationState(
    private val engine: NavigationEngine = NavigationEngine("settings"),
) {
    /** The key of the node currently focused, mirrored from the engine. */
    var focusedKey: String? = null
        private set

    val acceptsInput: Boolean get() = engine.acceptsInput

    /**
     * Feed the current row list (registration order or, ideally, Y-sorted visual order) plus
     * optional geometry. The engine preserves focus on the same logical key and recovers to the
     * nearest survivor when the focused row disappears (spec §7, §8).
     */
    fun updateItems(
        newItems: List<ControllerNavItem>,
        geometry: Map<String, Float> = emptyMap(),
    ) {
        engine.replaceNodes(newItems.map { it.toNavigationNode() }, geometry)
        focusedKey = engine.focusedKey
    }

    /** Vertical traversal (UP/DOWN) by [delta] steps, clamped with no wrapping. */
    fun move(delta: Int): String? {
        focusedKey = engine.moveVerticalActive(delta)
        return focusedKey
    }

    /**
     * Horizontal movement between a row and its inline [trailingActions] (spec §4). RIGHT enters
     * the first action; from an action, LEFT/RIGHT step between them (clamped at the ends);
     * LEFT past the first action returns to the row. Returns null when the row has no actions.
     */
    fun moveHorizontal(delta: Int): String? {
        focusedKey = engine.moveHorizontalActive(delta)
        return focusedKey
    }

    /** Realign focus to the first navigable node (screen entry when nothing to restore). */
    fun focusFirst(): String? {
        engine.focusFirst()
        focusedKey = engine.focusedKey
        return focusedKey
    }

    /** Realign the focused key with actual (e.g. Compose-driven) focus; ignores unknown keys. */
    fun setFocused(key: String?) {
        engine.setFocused(key)
        focusedKey = engine.focusedKey
    }

    /** Confirm on the focused node; dispatches to its action. Returns whether anything consumed it. */
    fun select(): Boolean = engine.confirmDirect()
}