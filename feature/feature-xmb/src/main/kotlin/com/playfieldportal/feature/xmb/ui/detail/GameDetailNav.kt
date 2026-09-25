package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.NavigationCommand
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.NavigationEngine
import com.playfieldportal.core.navigation.NavigationLogger
import com.playfieldportal.core.navigation.NavigationNode
import com.playfieldportal.core.navigation.NavigationTouchAction

/**
 * Stable identity for a media tile: the asset itself, never its position in the strip. Lives here
 * (not in the ViewModel) because both the graph and the screen have to derive the same key.
 */
internal fun mediaStableId(media: DetailMedia): String =
    if (media.isVideo) "v:${media.uri}" else "i:${media.uri}"

// ── Game Detail navigation (unified engine adapter) ───────────────────────────
//
// Game Detail used to be navigated by four ad-hoc index fields (`mainFocus`, `discFocusIndex`,
// `mediaFocus`, `pageScrollSteps`) scattered between the ViewModel and the screen. This adapter
// replaces them with the shared core-navigation engine:
//
//   * every controller-actionable element is a stable semantic node (never a visible list index);
//   * UP/DOWN moves between visual rows using reported geometry, LEFT/RIGHT between a row's
//     siblings, and neither wraps;
//   * the page graph is one context, every blocking overlay is a modal context pushed on top — the
//     paused page keeps its focused node and gets it back exactly when the modal closes;
//   * navigation input is ignored (never buffered) until the screen reports readiness, and dropped
//     while an alignment animation holds the recovery lock.
//
// The adapter stays pure: it never touches Compose or the repository layer. It builds generic
// nodes, reports focus changes, and hands activations back through [onActivate] with the node's
// stable key. That keeps the whole navigation contract unit-testable on the JVM.

/** Stable semantic keys for the Game Detail page. Never derive these from list positions. */
object GameDetailKeys {
    const val LAUNCH = "game-detail:launch"
    const val ACTIONS = "game-detail:actions"
    const val FAVORITE = "game-detail:favorite"
    const val ARTWORK = "game-detail:artwork"
    const val MANUAL = "game-detail:manual"
    const val OPTIONS_ACTION = "game-detail:options-action"
    const val DISCS = "game-detail:discs"
    const val COINS = "game-detail:shiba-coins"
    const val OVERVIEW = "game-detail:overview"
    const val INFO = "game-detail:info"
    const val MEDIA = "game-detail:media"

    fun disc(gameId: Long): String = "game-detail:disc:$gameId"

    fun media(stableId: String): String = "game-detail:media:$stableId"

    fun option(actionName: String): String = "game-detail:options:$actionName"

    fun emulatorPick(profileId: String): String = "game-detail:emulator-pick:$profileId"

    fun collectionRow(collectionId: Long): String = "game-detail:collection:$collectionId"

    const val COLLECTION_CREATE_ROW = "game-detail:collection:create"

    const val METADATA_APPLY = "game-detail:metadata:apply"

    fun metadataField(fieldName: String): String = "game-detail:metadata:field:$fieldName"

    // ── Storefront match picker and Rematch (C23 T6) ──────────────────────────
    fun storefrontCandidate(index: Int): String = "game-detail:storefront:candidate:$index"

    /** Always the last stop in the picker — "No correct match" is never absent. */
    const val STOREFRONT_NO_MATCH = "game-detail:storefront:no-match"

    fun storefrontStore(storeKey: String): String = "game-detail:storefront:store:$storeKey"

    const val STOREFRONT_SEARCH_ALL = "game-detail:storefront:search-all"

    const val CONFIRM_REMOVE = "game-detail:confirm-remove"
    const val CONFIRM_CANCEL = "game-detail:confirm-cancel"

    // ── Modal context ids ─────────────────────────────────────────────────────
    const val MODAL_OPTIONS = "game-detail:modal:options"
    const val MODAL_EMULATOR_PICKER = "game-detail:modal:emulator-picker"
    const val MODAL_COLLECTION_PICKER = "game-detail:modal:collection-picker"
    const val MODAL_METADATA = "game-detail:modal:metadata"
    const val MODAL_CONFIRM_REMOVE = "game-detail:modal:confirm-remove"
    const val MODAL_NOTE_EDITOR = "game-detail:modal:note-editor"
    const val MODAL_TITLE_EDITOR = "game-detail:modal:title-editor"
    const val MODAL_MANUAL_VIEWER = "game-detail:modal:manual"
    const val MODAL_IMAGE_VIEWER = "game-detail:modal:image-viewer"
    const val MODAL_VIDEO_PLAYER = "game-detail:modal:video-player"
    const val MODAL_ARTWORK_STUDIO = "game-detail:modal:artwork-studio"
    const val MODAL_STOREFRONT_MATCH = "game-detail:modal:storefront-match"
    const val MODAL_STOREFRONT_REMATCH = "game-detail:modal:storefront-rematch"
}

/**
 * What the page's node graph is built from. A plain snapshot of the loaded game's visible
 * affordances — deliberately not the whole UI state, so the graph can be unit-tested without a
 * ViewModel, a database or a Compose runtime.
 */
data class GameDetailNavContent(
    /** 0 until the game row is loaded and the page can render. */
    val gameId: Long = 0L,
    val loaded: Boolean = false,
    /** The manual action exists and can open something. */
    val hasManual: Boolean = false,
    /** Emulator controls apply (false for package-backed Android/Windows entries). */
    val showEmulatorControls: Boolean = false,
    /** Disc members in visual order; empty unless this is a multi-disc set. */
    val discIds: List<Long> = emptyList(),
    /** Shiba Coins row is shown (never for Android entries). */
    val showCoins: Boolean = false,
    /** The overview row is shown. */
    val showOverview: Boolean = false,
    /** The structured information band has anything to show. */
    val showInfo: Boolean = false,
    /** Media strip stable ids in visual order (videos first, then screenshots). */
    val mediaIds: List<String> = emptyList(),
)

/**
 * Game Detail's navigation state, backed by the shared [NavigationEngine].
 *
 * Not thread-safe by design: every caller runs on the main thread (ViewModel input handling and the
 * UI's geometry reporting), exactly like the Compose state it replaced.
 */
class GameDetailNav(
    private val logger: NavigationLogger = NavigationLogger.NONE,
) {
    private val engine = NavigationEngine(contextId = "game-detail", logger = logger)

    /** Called with a node's stable key when it is activated (Confirm or tap). */
    var onActivate: (String) -> Unit = {}

    private var content = GameDetailNavContent()

    /** Last known node geometry (root-space Y per key), reported by the screen. */
    private var geometry: Map<String, Float> = emptyMap()

    /** The node list currently registered in the active context. */
    private var registeredNodes: List<NavigationNode> = emptyList()

    /**
     * Rows that hand the cursor straight to a child on vertical arrival. Their container is a visual
     * band (the quick actions, the disc row, the media strip) rather than something a user acts on,
     * so parking the cursor on it would cost an extra RIGHT press for no information.
     */
    private val autoEnterRows = setOf(GameDetailKeys.ACTIONS, GameDetailKeys.DISCS, GameDetailKeys.MEDIA)

    val focusedKey: String? get() = engine.focusedKey
    val cursorVisible: Boolean get() = engine.cursorVisible
    val acceptsInput: Boolean get() = engine.acceptsInput
    val isModalActive: Boolean get() = engine.isModalActive
    val activeContextId: String get() = engine.activeContextId

    /**
     * Every key the cursor can actually reach, inline children included.
     *
     * Not the engine's [NavigationEngine.focusableKeys], which reports only top-level nodes: the
     * quick actions, discs and media tiles are children (LEFT/RIGHT siblings), so a caller asking
     * "can the user reach this?" needs the whole graph.
     */
    fun reachableKeys(): Set<String> = registeredNodes
        .flatMap { node ->
            buildList {
                if (node.focusable && node.enabled) add(node.key)
                node.children.filter { it.focusable && it.enabled }.forEach { add(it.key) }
            }
        }
        .toSet()

    // ── Graph ─────────────────────────────────────────────────────────────────

    /**
     * Rebuild the page graph from [newContent]. The engine preserves the focused key when it still
     * exists and recovers to the nearest surviving node — by former visual position, then by
     * registration order — when it does not, so unrelated asynchronous content changes never reset
     * the cursor to Launch.
     *
     * While a modal context is up the page graph is frozen: the modal owns the active context, and
     * re-registering here would overwrite the modal's own nodes. The pending page graph is applied
     * when the modal closes.
     */
    fun updateContent(newContent: GameDetailNavContent) {
        content = newContent
        if (engine.isModalActive) return
        registerPageGraph()
    }

    /**
     * Report the on-screen Y of the nodes the page actually composed. Geometry is what makes
     * UP/DOWN follow the visual layout (and what lets a removed node's focus recover to whatever
     * took its place on screen).
     */
    fun reportGeometry(map: Map<String, Float>) {
        if (map.isEmpty()) return
        geometry = geometry + map
        if (engine.isModalActive) return
        registerPageGraph()
    }

    private fun registerPageGraph() {
        registeredNodes = pageNodes()
        // The same map is the previous geometry: it still holds the position of any node this
        // update removed, which is exactly what focus recovery needs to look up.
        engine.replaceNodesWithGeometry(registeredNodes, geometry, geometry)
        normalizeFocus()
    }

    /**
     * Keep the cursor off the visual bands that hold no action of their own.
     *
     * A removed node recovers to its owning row first (the engine's rule), and a row is a legitimate
     * answer for a row that is *itself* actionable — but the quick-action, disc and media bands are
     * not: parking there would hide the cursor on something with nothing to confirm, so it steps
     * into the band's first child instead.
     */
    private fun normalizeFocus() {
        val key = engine.focusedKey ?: return
        if (key !in autoEnterRows) return
        childKeys(key).firstOrNull()?.let { engine.setFocused(it) }
    }

    private fun pageNodes(): List<NavigationNode> {
        if (!content.loaded) return emptyList()
        val nodes = mutableListOf<NavigationNode>()
        nodes += NavigationNode(GameDetailKeys.LAUNCH, onSelect = { activate(GameDetailKeys.LAUNCH) })

        val quickActions = buildList {
            add(NavigationNode(GameDetailKeys.FAVORITE, onSelect = { activate(GameDetailKeys.FAVORITE) }))
            add(NavigationNode(GameDetailKeys.ARTWORK, onSelect = { activate(GameDetailKeys.ARTWORK) }))
            // Manual stays visible but disabled without one, and a disabled action is never
            // focusable — the controller must not be able to land on something that does nothing.
            if (content.hasManual) {
                add(NavigationNode(GameDetailKeys.MANUAL, onSelect = { activate(GameDetailKeys.MANUAL) }))
            }
            // Options opens the context menu, which every entry has. The emulator itself is changed
            // by confirming the information band.
            add(NavigationNode(GameDetailKeys.OPTIONS_ACTION, onSelect = { activate(GameDetailKeys.OPTIONS_ACTION) }))
        }
        nodes += container(GameDetailKeys.ACTIONS, quickActions)

        if (content.discIds.size > 1) {
            nodes += container(
                GameDetailKeys.DISCS,
                content.discIds.map { discId ->
                    NavigationNode(GameDetailKeys.disc(discId), onSelect = { activate(GameDetailKeys.disc(discId)) })
                },
            )
        }
        if (content.showCoins) {
            nodes += NavigationNode(GameDetailKeys.COINS, onSelect = { activate(GameDetailKeys.COINS) })
        }
        if (content.showOverview) {
            nodes += NavigationNode(GameDetailKeys.OVERVIEW, onSelect = { activate(GameDetailKeys.OVERVIEW) })
        }
        if (content.showInfo) {
            // One stop, like every other row: confirming the highlighted band opens the emulator
            // picker directly, with no RIGHT into an inner field first. Package-backed entries have
            // no emulator, so for them the band is a reading stop whose Confirm does nothing.
            nodes += NavigationNode(
                key = GameDetailKeys.INFO,
                selectable = content.showEmulatorControls,
                onSelect = { activate(GameDetailKeys.INFO) },
            )
        }
        if (content.mediaIds.isNotEmpty()) {
            nodes += container(
                GameDetailKeys.MEDIA,
                content.mediaIds.map { id ->
                    NavigationNode(GameDetailKeys.media(id), onSelect = { activate(GameDetailKeys.media(id)) })
                },
            )
        }
        return nodes
    }

    private fun container(
        key: String,
        children: List<NavigationNode>,
        selectable: Boolean = false,
    ): NavigationNode = NavigationNode(
        key = key,
        // Focusable so vertical traversal can land on it (and then step into a child); never
        // selectable, because a band itself does nothing when confirmed.
        selectable = selectable,
        children = children,
    )

    private fun activate(key: String) {
        onActivate(key)
    }

    // ── Readiness ─────────────────────────────────────────────────────────────

    /**
     * The page's first usable graph is on screen, so controller input may start. Before this point
     * the engine ignores input entirely rather than buffering it — nothing fires late when the page
     * finishes loading.
     */
    fun markReady() = engine.markReady()

    // ── Input ─────────────────────────────────────────────────────────────────

    /**
     * Dispatch one controller action. Returns the key the cursor is on afterwards (null when nothing
     * is focusable, or when the input was gated and dropped).
     *
     * Back and the Options shortcut are deliberately NOT handled here: whether Back closes a modal,
     * a picker or the page is domain knowledge the ViewModel owns.
     */
    fun handleAction(action: GamepadAction): String? = when (action) {
        GamepadAction.NAVIGATE_UP -> move(NavigationDirection.UP)
        GamepadAction.NAVIGATE_DOWN -> move(NavigationDirection.DOWN)
        GamepadAction.NAVIGATE_LEFT -> move(NavigationDirection.LEFT)
        GamepadAction.NAVIGATE_RIGHT -> move(NavigationDirection.RIGHT)
        GamepadAction.SELECT -> {
            engine.dispatch(NavigationCommand.Confirm)
            engine.focusedKey
        }
        else -> engine.focusedKey
    }

    private fun move(direction: NavigationDirection): String? {
        val previousChildIndex = childIndex(engine.focusedKey)
        val landed = engine.dispatch(NavigationCommand.Direction(direction))
            // A boundary stop (or a gated input) returns null: the cursor simply stays put.
            ?: return engine.focusedKey
        // Bands are never a resting place (see normalizeFocus): a vertical arrival steps into the
        // child nearest the horizontal position the cursor came from — the "nearest reasonable
        // horizontal neighbour" rule, without which every row change would cost an extra RIGHT
        // press — and a horizontal one ran off the row's edge, so it stops at the first child.
        if (landed !in autoEnterRows) return landed
        val children = childKeys(landed)
        if (children.isEmpty()) return landed
        val target = when (direction) {
            NavigationDirection.UP, NavigationDirection.DOWN ->
                children[if (previousChildIndex >= 0) previousChildIndex.coerceAtMost(children.lastIndex) else 0]
            NavigationDirection.LEFT, NavigationDirection.RIGHT -> children.first()
        }
        engine.setFocused(target)
        return engine.focusedKey
    }

    /** Index of [key] among its row's children, or -1 when it is a top-level node. */
    private fun childIndex(key: String?): Int {
        if (key == null) return -1
        registeredNodes.forEach { node ->
            val index = node.children.indexOfFirst { it.key == key }
            if (index >= 0) return index
        }
        return -1
    }

    private fun childKeys(key: String): List<String> =
        registeredNodes.firstOrNull { it.key == key }
            ?.children
            ?.filter { it.focusable && it.enabled }
            ?.map { it.key }
            .orEmpty()

    /** A touch on a known node: mark touch input, move logical focus there, then activate it. */
    fun touch(key: String): Boolean = engine.dispatchTouch(key, NavigationTouchAction.TAP)

    /** Any touch anywhere else — hides the controller cursor without moving logical focus. */
    fun markTouchInput() = engine.markTouchInput()

    /** Realign nav focus with the UI (used when the page restores a saved node). */
    fun setFocused(key: String?) = engine.setFocused(key)

    // ── Modal contexts ────────────────────────────────────────────────────────

    /**
     * Push a blocking overlay's own navigation context. The page graph keeps its focused node and
     * receives no input until [popModal].
     *
     * [preferredFocus] is where the overlay's cursor starts ("Apply", the currently selected
     * emulator, the row the picker was left on); without one the first node takes it.
     */
    fun pushModal(
        contextId: String,
        nodes: List<NavigationNode> = emptyList(),
        preferredFocus: String? = null,
    ) {
        engine.pushModal(contextId)
        registeredNodes = nodes
        // Modal graphs are short vertical lists; they get registration order, never the page's Y.
        engine.replaceNodes(nodes)
        if (preferredFocus != null) engine.setFocused(preferredFocus)
        if (engine.focusedKey == null) engine.focusFirst()
    }

    /**
     * Re-register the active modal's nodes — its rows can arrive after the overlay opened (metadata
     * retrieval, the collection list, the installed-emulator catalog).
     *
     * [preferredFocus] is applied only when the overlay had no cursor yet, so a row arriving late
     * can never steal focus from the user. The engine keeps the focused key otherwise, or recovers
     * to the nearest survivor when the node it was on disappeared.
     */
    fun updateModalNodes(nodes: List<NavigationNode>, preferredFocus: String? = null) {
        val hadFocus = engine.focusedKey != null
        registeredNodes = nodes
        engine.replaceNodes(nodes)
        if (!hadFocus && preferredFocus != null) engine.setFocused(preferredFocus)
    }

    /** Pop the top modal context. Returns the page node to restore the cursor to. */
    fun popModal(): String? {
        val restored = engine.popContext()
        // The page graph may have changed while the modal was up; apply it now that its context is
        // active again (and keep the restored key when it still exists).
        engine.setFocused(restored)
        registerPageGraph()
        return engine.focusedKey
    }

    // ── Recovery lock ─────────────────────────────────────────────────────────

    /**
     * Block navigation while the page aligns the focused node (focus-driven scrolling). Repeated
     * input during the lock is dropped, never queued.
     */
    fun beginRecoveryLock() = engine.beginRecoveryLock()

    fun endRecoveryLock() = engine.endRecoveryLock()
}
