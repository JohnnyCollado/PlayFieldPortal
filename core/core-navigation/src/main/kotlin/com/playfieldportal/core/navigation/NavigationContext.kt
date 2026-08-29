package com.playfieldportal.core.navigation

/**
 * One frame of the navigation context stack (spec §16). The active context — the top of the
 * stack — is the only one that receives navigation commands; paused contexts preserve their
 * state (focused node, edit mode) for restoration.
 *
 * A context is UI-agnostic: it holds generic [NavigationNode]s in registration order plus
 * optional geometry (visual Y positions). Visual order wins when geometry is present (spec §8);
 * registration order is the fallback when geometry is unavailable (spec §7.2).
 */
class NavigationContext(
    /** Stable identifier for logging/debugging (e.g. "settings:display", "modal:deleteConfirm"). */
    val id: String,
    /** Modal contexts sit above the graph they interrupted and receive all input (spec §15). */
    val modal: Boolean = false,
) {
    private var nodes: List<NavigationNode> = emptyList()
    private var geometry: Map<String, Float> = emptyMap()
    private var ready: Boolean = false

    var focusedKey: String? = null
        private set

    /** Set while a component-owned edit mode is active on the focused node (spec §5). */
    var editHandler: EditModeHandler? = null
        private set

    var isReady: Boolean
        get() = ready
        private set(value) { ready = value }

    fun markReady() {
        ready = true
    }

    /** Current ordered node list (registration order — geometry sorting happens at query time). */
    fun nodesInRegistrationOrder(): List<NavigationNode> = nodes

    fun geometryFor(key: String): Float? = geometry[key]

    fun hasGeometry(): Boolean = geometry.isNotEmpty()

    fun focusedNode(): NavigationNode? = focusedKey?.let { findNode(it) }

    fun editHandlerFor(key: String): EditModeHandler? {
        if (editHandler != null) return null
        val node = findNode(key) ?: return null
        return node.onEditStart?.invoke()?.also { editHandler = it }
    }

    fun clearEditHandler() {
        editHandler?.onExit()
        editHandler = null
    }

    // ── Node registry ────────────────────────────────────────────────────────────

    /**
     * Replace the node list, preserving focus on the same logical key when it still exists
     * (spec §7 "node remains present"). When the focused node disappeared, recover to the
     * nearest surviving focusable node — first by visual geometry around the old position,
     * then by registration-order distance as the fallback (spec §7.2).
     *
     * Returns the new focused key (null when nothing is focusable).
     */
    fun updateNodes(
        newNodes: List<NavigationNode>,
        newGeometry: Map<String, Float> = emptyMap(),
        previousGeometry: Map<String, Float> = geometry,
    ): String? {
        val previousNodes = nodes
        val previousKey = focusedKey
        nodes = newNodes
        if (newGeometry.isNotEmpty()) geometry = newGeometry

        val focusable = focusableNodes()
        val focusableKeys = focusable.flatMap { node -> listOf(node.key) + node.children.filter { it.focusable && it.enabled }.map { it.key } }

        focusedKey = when {
            previousKey != null && previousKey in focusableKeys -> previousKey
            previousKey != null -> recoverAfterRemoval(previousNodes, previousKey, previousGeometry, focusable)
            else -> focusable.firstOrNull()?.key
        }
        return focusedKey
    }

    private fun recoverAfterRemoval(
        previousNodes: List<NavigationNode>,
        previousKey: String,
        previousGeometry: Map<String, Float>,
        focusable: List<NavigationNode>,
    ): String? {
        if (focusable.isEmpty()) return null
        // Inline-action recovery: fall back to its owning parent row first.
        previousNodes.forEach { node ->
            if (node.children.any { it.key == previousKey } && focusable.any { it.key == node.key }) {
                return node.key
            }
        }
        // Geometry recovery: nearest focusable node to the removed node's last visual position.
        val previousY = previousGeometry[previousKey]
        if (previousY != null && geometry.isNotEmpty()) {
            focusable.minByOrNull { key -> kotlin.math.abs((geometry[key.key] ?: Float.MAX_VALUE) - previousY) }
                ?.let { return it.key }
        }
        // Order fallback (spec §7.2): nearest surviving node by registration distance.
        val previousIndex = previousNodes.indexOfFirst { it.key == previousKey }
        return if (previousIndex >= 0) {
            focusable.minByOrNull { kotlin.math.abs(nodes.indexOf(it) - previousIndex) }?.key
        } else {
            focusable.firstOrNull()?.key
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────────

    /**
     * Focusable nodes ordered for vertical traversal: visual (geometry) order when positions
     * are known, registration order otherwise (spec §8). Unpositioned nodes sort last but keep
     * their relative registration order (stable sort), matching the legacy scaffold behaviour.
     */
    fun focusableNodes(): List<NavigationNode> {
        val focusable = nodes.filter { it.focusable && it.enabled }
        if (!hasGeometry()) return focusable
        return focusable.sortedBy { geometry[it.key] ?: Float.MAX_VALUE }
    }

    fun findNode(key: String): NavigationNode? =
        nodes.firstOrNull { it.key == key } ?: nodes.firstNotNullOfOrNull { node ->
            node.children.firstOrNull { it.key == key }
        }

    /** The owner row of a child/inline node, or null when [key] is a top-level node. */
    fun ownerOf(key: String): NavigationNode? =
        nodes.firstOrNull { node -> node.children.any { it.key == key } }

    fun isKnownKey(key: String): Boolean = findNode(key) != null

    // ── Movement ─────────────────────────────────────────────────────────────────

    /**
     * Vertical movement (UP/DOWN). Focusing a child first exits to its owning row (spec §4:
     * vertical traversal happens between rows). Clamps at both boundaries — no wrapping (§2.5).
     * Returns the new focused key, or null when there is nothing focusable.
     */
    fun moveVertical(delta: Int): String? {
        val focusable = focusableNodes()
        if (focusable.isEmpty()) {
            focusedKey = null
            return null
        }
        // Focus on an inline action: exit back to its owning row before moving vertically.
        val owner = ownerOf(focusedKey ?: "")
        val baseKey = owner?.key ?: focusedKey
        val current = focusable.indexOfFirst { it.key == baseKey }
        val target = if (current < 0) 0 else (current + delta).coerceIn(0, focusable.lastIndex)
        val targetNode = focusable[target]
        // Landing on a row with inline actions focuses the row itself; horizontal movement
        // enters the actions explicitly (spec §4: cursor may land directly on children when
        // appropriate, but vertical traversal walks rows).
        focusedKey = targetNode.key
        return focusedKey
    }

    /**
     * Horizontal movement between a row and its inline children (spec §4). RIGHT enters the
     * first child; from a child, LEFT/RIGHT step between children (clamped at the ends, no
     * wrapping); LEFT past the first child returns to the owning row. Returns null when the
     * current row has no children (no-op — boundary stop).
     */
    fun moveHorizontal(delta: Int): String? {
        val owner = ownerOf(focusedKey ?: "")
        val children = (owner ?: findNode(focusedKey ?: ""))?.children
            ?.filter { it.focusable && it.enabled }
            ?: return null
        if (children.isEmpty()) return null
        if (owner != null && focusedKey != owner.key) {
            val index = children.indexOfFirst { it.key == focusedKey }
            val targetIndex = index + delta
            focusedKey = when {
                targetIndex < 0 -> owner.key
                targetIndex >= children.size -> focusedKey
                else -> children[targetIndex].key
            }
        } else {
            // On the row itself: RIGHT enters the first child; LEFT stays put.
            focusedKey = if (delta > 0) children.first().key else focusedKey
        }
        return focusedKey
    }

    fun focusFirst(): String? {
        focusedKey = focusableNodes().firstOrNull()?.key
        return focusedKey
    }

    /** Realign the focused key with actual UI focus; ignores unknown keys. Exiting edit mode on focus loss. */
    fun setFocused(key: String?) {
        if (key == null) {
            focusedKey = null
            return
        }
        if (isKnownKey(key)) {
            if (key != focusedKey) clearEditHandler()
            focusedKey = key
        }
    }

    // ── Selection ────────────────────────────────────────────────────────────────

    /**
     * Confirm on the focused node. Inside edit mode the handler gets the event first (spec §5);
     * otherwise the node's [NavigationNode.onSelect] fires. Returns whether anything consumed it.
     */
    fun confirm(): Boolean {
        val handler = editHandler
        if (handler != null) return handler.onConfirm()
        val key = focusedKey ?: return false
        val owner = ownerOf(key)
        val node = if (owner != null) {
            owner.children.firstOrNull { it.key == key } ?: return false
        } else {
            findNode(key) ?: return false
        }
        if (!node.focusable || !node.selectable || !node.enabled) return false
        return node.onSelect?.invoke() != null
    }

    internal fun setFocusedKeyForTest(key: String?) {
        focusedKey = key
    }
}
