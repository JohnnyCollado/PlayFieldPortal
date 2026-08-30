package com.playfieldportal.core.navigation

/**
 * The unified navigation engine (spec: PlayFieldPortal_Unified_Navigation_Architecture.md).
 *
 * Owns navigation state only — never rendering, business logic, or component behaviour
 * (spec §1). The active navigation context is the top of a stack of [NavigationContext]s
 * (spec §16): screens push themselves, modals push on top and pause the graph below, and
 * only the top context ever receives commands.
 *
 * Readiness gating (spec §13): until a screen declares readiness via [markReady], navigation
 * input is ignored and NOT buffered — nothing fires when readiness arrives.
 *
 * Recovery locking (spec §9): while a cursor-recovery animation runs, repeated directional
 * inputs are dropped, not queued.
 */
class NavigationEngine(
    contextId: String = "root",
    private val logger: NavigationLogger = NavigationLogger.NONE,
) {
    /** Stack of contexts; the last entry is the active one. */
    private val contexts = ArrayDeque<NavigationContext>().apply {
        add(NavigationContext(contextId))
    }

    private var ready = false
    private var inputLocked = false

    /** The key of the node currently focused in the active context. */
    val focusedKey: String?
        get() = active.focusedKey

    val activeContextId: String
        get() = active.id

    /** True while a modal context is on top of the stack (spec §15). */
    val isModalActive: Boolean
        get() = contexts.size > 1

    /** True while edit mode is active in the active context (spec §5). */
    val isEditing: Boolean
        get() = active.editHandler != null

    /** True when the engine would currently accept navigation input. */
    val acceptsInput: Boolean
        get() = ready && !inputLocked

    /** Whether the visual cursor should be rendered. Touch hides it until controller input resumes. */
    var cursorVisible: Boolean = true
        private set

    /** Mark a pointer interaction without changing logical focus. */
    fun markTouchInput() {
        cursorVisible = false
    }

    /** Mark controller input and restore the visual cursor. */
    fun markControllerInput() {
        cursorVisible = true
    }

    /** Dispatch a tap or long press to a known node. Long press never falls through to tap. */
    fun dispatchTouch(key: String, action: NavigationTouchAction): Boolean {
        if (!ready || inputLocked) return false
        val node = active.findNode(key) ?: return false
        markTouchInput()
        active.setFocused(key)
        return when (action) {
            NavigationTouchAction.TAP -> active.confirm()
            NavigationTouchAction.LONG_PRESS -> node.onLongPress?.let { it(); true } ?: false
        }
    }

    private val active: NavigationContext
        get() = contexts.last()

    // ── Readiness (spec §13) ─────────────────────────────────────────────────────

    /** The screen signals its navigable content is ready. Idempotent. */
    fun markReady() {
        ready = true
    }

    /**
     * Recovery for screens that never signal readiness (spec §13): force-ready with a warning.
     * The user is not punished for a missed integration signal.
     */
    fun recoverReadiness() {
        if (ready) return
        logger.warn(
            "NavigationEngine: screen '${active.id}' never reported readiness — " +
                "forcing recovery so navigation is not permanently broken."
        )
        markReady()
    }

    // ── Node registry ────────────────────────────────────────────────────────────

    fun replaceNodes(nodes: List<NavigationNode>, geometry: Map<String, Float> = emptyMap()) {
        active.updateNodes(nodes, geometry)
    }

    fun replaceNodesWithGeometry(
        nodes: List<NavigationNode>,
        geometry: Map<String, Float>,
        previousGeometry: Map<String, Float> = emptyMap(),
    ) {
        active.updateNodes(nodes, geometry, previousGeometry)
    }

    fun focusFirst() {
        active.focusFirst()
    }

    /** Realign the focused key with actual UI focus (touch taps, initial focus). */
    fun setFocused(key: String?) {
        active.setFocused(key)
    }

    fun reportNodeGeometry(key: String, y: Float) {
        // Geometry bookkeeping is context-level; updates flow through replaceNodes* calls.
        // Kept as an explicit seam so adapters can report single-node moves cheaply.
        active.reportGeometry(key, y)
    }

    fun focusableKeys(): Set<String> = active.nodes.filter { it.focusable && it.enabled }.mapTo(mutableSetOf()) { it.key }

    /**
     * The active context's key → Y geometry. Geometry lands here via [replaceNodes]/[replaceNodesWithGeometry]
     * (bulk) or [reportNodeGeometry] (single node), and is what touch→D-pad re-anchoring reads.
     */
    fun currentGeometry(): Map<String, Float> = active.allGeometry()

    // ── Context stack / modals (spec §15, §16) ───────────────────────────────────

    /**
     * Push a modal context. The underlying graph is paused by construction — only the top
     * context receives commands — and its focused node is preserved for restoration.
     */
    fun pushModal(contextId: String) {
        contexts.addLast(NavigationContext(contextId, modal = true))
    }

    /**
     * Pop the top context (modal close). The previous context resumes with the exact logical
     * node it had before the modal opened (spec §15). Returns the key to restore focus to.
     */
    fun popContext(): String? {
        if (contexts.size <= 1) return active.focusedKey
        val closing = contexts.removeLast()
        closing.clearEditHandler()
        return active.focusedKey
    }

    // ── Input dispatch (spec §18) ────────────────────────────────────────────────

    /**
     * Dispatch a navigation command. Returns the key the UI should now present as focused
     * (null = boundary stop / nothing focusable / input dropped).
     *
     * Gating order: readiness → input lock → modal/edit routing (all inside the active context).
     */
    fun dispatch(command: NavigationCommand): String? {
        markControllerInput()
        // Readiness gate (spec §13): input ignored, never buffered.
        if (!ready) return null
        // Recovery-animation gate (spec §9): drop, don't queue.
        if (inputLocked) return null

        val context = active
        val handler = context.editHandler

        // Edit mode owns directional input and Confirm (spec §5).
        if (handler != null) {
            when (command) {
                is NavigationCommand.Direction -> {
                    // Handler consumes the direction; if it declines, fall through to
                    // normal traversal so navigation never dead-ends inside edit mode.
                    if (handler.onDirection(command.direction)) return null
                    return dispatchDirection(context, command.direction)
                }
                NavigationCommand.Confirm -> {
                    return if (handler.onConfirm()) null else context.focusedKey
                }
                is NavigationCommand.Back -> {
                    // Back ALWAYS exits edit mode first (spec §5); a subsequent Back navigates.
                    context.clearEditHandler()
                    return null
                }
            }
        }

        return when (command) {
            is NavigationCommand.Direction -> dispatchDirection(context, command.direction)
            NavigationCommand.Confirm -> {
                // Confirm may start a component edit mode (spec §5) before falling through
                // to the node's plain select action.
                val key = context.focusedKey
                if (key != null) {
                    val editHandler = context.editHandlerFor(key)
                    if (editHandler != null) return null
                }
                if (context.confirm()) key else null
            }
            is NavigationCommand.Back -> {
                context.clearEditHandler()
                backHandler?.invoke()
                null
            }
        }
    }

    private fun dispatchDirection(context: NavigationContext, direction: NavigationDirection): String? {
        return when (direction) {
            NavigationDirection.UP -> context.moveVertical(-1)
            NavigationDirection.DOWN -> context.moveVertical(1)
            NavigationDirection.LEFT -> context.moveHorizontal(-1)
            NavigationDirection.RIGHT -> context.moveHorizontal(1)
        }
    }

    /** Screen-level back handler (leaf screens close; nested screens collapse a level). */
    var backHandler: (() -> Unit)? = null

    // ── Recovery locking (spec §9) ───────────────────────────────────────────────

    /**
     * Begin a recovery animation: navigation input is temporarily blocked and repeated
     * directional inputs are dropped (not queued).
     */
    fun beginRecoveryLock() {
        inputLocked = true
    }

    /** Recovery animation finished; navigation resumes. Queued inputs never replay. */
    fun endRecoveryLock() {
        inputLocked = false
    }

    // ── Test seams ───────────────────────────────────────────────────────────────

    /** Direct access for tests: the active context's focused key. */
    internal fun activeContextForTest(): NavigationContext = active

    /**
     * Test/adapter convenience: Confirm on the focused node without the dispatch gating
     * (readiness/locks). Returns whether anything consumed it.
     */
    fun confirmDirect(): Boolean = active.confirm()

    /**
     * Raw, un-gated directional movement on the active context (adapter entry point).
     * Does NOT apply readiness or recovery-lock gating — unlike [dispatch], which is the
     * input-pipeline entry. Used by UI adapters that compose their own gating/top-boundary
     * behavior on top of the engine.
     */
    fun moveActive(direction: NavigationDirection): String? =
        dispatchDirection(active, direction)

    /** Un-gated vertical movement by an explicit step delta (adapter entry point). */
    fun moveVerticalActive(delta: Int): String? = active.moveVertical(delta)

    /** Un-gated horizontal movement by an explicit step delta (adapter entry point). */
    fun moveHorizontalActive(delta: Int): String? = active.moveHorizontal(delta)
}
