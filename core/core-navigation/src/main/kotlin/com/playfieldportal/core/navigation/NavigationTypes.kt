package com.playfieldportal.core.navigation

/**
 * Directional navigation intents (spec §2.3). Diagonal navigation is not supported, and the
 * engine never reduces these to abstract next/previous commands — spatial intent matters.
 */
enum class NavigationDirection { UP, DOWN, LEFT, RIGHT }

/**
 * A logical location the controller cursor may interact with (spec §3).
 *
 * The engine never knows what concrete UI component a node represents — a Settings row, an XMB
 * category, a slider — only that it has a stable identity and interaction flags.
 *
 * Stable identity rule: [key] must survive recomposition, reordering and scrolling so the
 * engine can preserve focus on the same logical node whenever it still exists.
 */
data class NavigationNode(
    val key: String,
    val focusable: Boolean = true,
    val selectable: Boolean = true,
    val enabled: Boolean = true,
    /** Activated by Confirm. Null means the node is read-only (Confirm is a no-op). */
    val onSelect: (() -> Unit)? = null,
    /**
     * Child nodes (inline actions, composite controls) reached via LEFT/RIGHT (spec §4).
     * Children never participate in vertical traversal; horizontal movement clamps at the ends.
     */
    val children: List<NavigationNode> = emptyList(),
    /**
     * When Confirm on this node should enter a component-owned edit mode (spec §5), the node
     * supplies a handler factory. Returning null means Confirm falls through to [onSelect].
     */
    val onEditStart: (() -> EditModeHandler?)? = null,
)

/**
 * Minimal logging seam so the pure-JVM core stays dependency-free. Adapters wire their own
 * logger (e.g. Timber); tests capture warnings to assert recovery behaviour (spec §13).
 */
fun interface NavigationLogger {
    fun warn(message: String)

    companion object {
        val NONE: NavigationLogger = NavigationLogger { }
    }
}

/**
 * Component-owned edit mode (spec §5). While active the component interprets directional input
 * according to its own semantics, and Back ALWAYS exits edit mode before any screen navigation.
 */
interface EditModeHandler {
    /** Handle a directional input with the component's own semantics. Return whether consumed. */
    fun onDirection(direction: NavigationDirection): Boolean

    /** Handle Confirm inside edit mode. Return whether consumed. */
    fun onConfirm(): Boolean

    /** Edit mode is ending (Back, focus loss, or component completion). */
    fun onExit()
}
