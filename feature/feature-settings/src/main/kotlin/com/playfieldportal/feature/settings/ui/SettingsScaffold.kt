package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.isDirectional
import com.playfieldportal.core.ui.theme.LocalPFPColors
import kotlinx.coroutines.launch
import timber.log.Timber

// ── CompositionLocals — provided by SettingsNavHost, consumed by SettingsScaffold ──

val LocalSettingsPendingAction = compositionLocalOf<GamepadAction?> { null }
val LocalSettingsActionConsumed = compositionLocalOf<() -> Unit> { {} }

/** Reports pointer input so the host can hide controller-only cursor decoration. */
val LocalSettingsTouchInput = compositionLocalOf<() -> Unit> { {} }
val LocalSettingsCursorVisible = compositionLocalOf { true }

// Internal tracker: rows register their onClick when they gain focus so the scaffold
// can invoke the right action on a controller SELECT press.
internal val LocalSettingsFocusTracker =
    compositionLocalOf<((() -> Unit)?) -> Unit> { {} }

// Internal registry: rows that declare a focusKey register a FocusRequester here so the
// scaffold can restore focus to a specific row (the one that opened a child screen) when
// returning, instead of always snapping back to the first row.
internal val LocalSettingsFocusRegistry =
    compositionLocalOf<SnapshotStateMap<String, FocusRequester>> { mutableStateMapOf() }

// Internal registrar: the first interactive row to compose reports its FocusRequester here so
// the scaffold can place initial focus on a real, laid-out row. (The old 0dp bootstrap box
// never reliably gained focus, leaving menus opening with nothing selected.)
internal val LocalSettingsRegisterFirstFocusable =
    compositionLocalOf<(FocusRequester) -> Unit> { {} }

// Explicit vertical navigation. Rows register (FocusRequester, ControllerNavItem) pairs in
// composition order; ControllerNavigationState owns movement and selection, and the scaffold
// requests focus for the model's focused key. Coordinates are a presentation concern only
// (scroll-into-view, reseed fallback) — never directional moveFocus, which escapes into the
// XMB's focusable items behind the overlay (proven by logs: canFocus inheritance does not
// reach the XMB's LazyColumn across subcompositions).
internal val LocalSettingsRowPositions =
    compositionLocalOf<SnapshotStateMap<FocusRequester, Float>?> { null }
// On-screen HEIGHT of each row, used for keep-in-view clamping by the row's bottom edge. Kept
// separate from [LocalSettingsRowPositions] (row tops) because the engine's geometry and the
// reseed fallback both consume the top-Y map as-is.
internal val LocalSettingsRowSizes =
    compositionLocalOf<SnapshotStateMap<FocusRequester, Float>?> { null }
internal val LocalSettingsNavigationOrder =
    compositionLocalOf<SnapshotStateList<Pair<FocusRequester, ControllerNavItem>>?> { null }

// Controller-reachable inline actions (e.g. a root row's Replace/Remove buttons). Keyed by the
// owning row's navigation key; each entry is (actionKey, FocusRequester) in visual order. They
// are reached via LEFT/RIGHT and never participate in vertical traversal.
internal val LocalSettingsRowActions =
    compositionLocalOf<SnapshotStateMap<String, SnapshotStateList<Pair<String, FocusRequester>>>?> { null }

// The scrollable Column inside each screen is the real scroll owner. SettingsScaffold registers
// it here so controller boundary navigation resets that exact state rather than an unrelated
// scaffold-local ScrollState.
internal val LocalSettingsScrollStateRegistrar =
    compositionLocalOf<(ScrollState) -> Unit> { {} }

internal val LocalSettingsScrollToTop =
    compositionLocalOf<() -> Unit> { {} }
internal val LocalSettingsReportFocused =
    compositionLocalOf<(FocusRequester) -> Unit> { {} }

// Rows report leaving composition. If the FOCUSED row is removed (a list item deleted, a
// section re-rendered), Compose silently clears focus and the menu goes dead until the user
// presses a direction — the scaffold uses this signal to refocus the nearest surviving row.
internal val LocalSettingsReportRemoved =
    compositionLocalOf<(FocusRequester) -> Unit> { {} }

// Focus was lost (its row left composition): land on the row nearest the last focused Y so the
// cursor reappears where the user was, not at the top of the screen.
private fun reseedFocus(
    rowPositions: Map<FocusRequester, Float>,
    lastFocusedY: Float?,
    firstRow: FocusRequester?,
) {
    val target = lastFocusedY?.let { anchor ->
        rowPositions.entries.minByOrNull { kotlin.math.abs(it.value - anchor) }?.key
    } ?: firstRow
    target?.let { runCatching { it.requestFocus() } }
}

// ── Colors ────────────────────────────────────────────────────────────────────

// Sourced from the shared palette so the settings rows and the Material dialogs (PFPTheme's
// dark scheme) can never drift apart on a rebrand.
val SettingsBg = Color(0xE6000000)
val SettingsAccent = com.playfieldportal.core.ui.theme.PfpPalette.Accent
val SettingsText = Color.White
val SettingsSubtext = com.playfieldportal.core.ui.theme.PfpPalette.Subtext
val SettingsDivider = com.playfieldportal.core.ui.theme.PfpPalette.Divider
val SettingsSelectedBg = com.playfieldportal.core.ui.theme.PfpPalette.Accent.copy(alpha = 0.14f)

// ── Scaffold ──────────────────────────────────────────────────────────────────

@Composable
fun SettingsScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // When set, focus is restored to the row whose focusKey matches (used when returning
    // from a child screen). When null, focus starts at the first interactive row.
    restoreFocusKey: String? = null,
    // When set, called with every incoming action BEFORE normal navigation handling.
    // Return true to consume the action (suppresses back/select/focus movement).
    // Used by ControllerSettingsScreen to capture button presses during remap mode.
    onInterceptAction: ((GamepadAction) -> Boolean)? = null,
    onTouchInput: () -> Unit = {},
    // ── Chrome overrides — the first-run wizard's PSP skin (see WizardScaffold) ──
    // Replaces the ◀ breadcrumb header (the wizard draws a step badge + title instead).
    header: (@Composable () -> Unit)? = null,
    // Hides the divider under the header (the wizard header has its own bottom rule).
    showDivider: Boolean = true,
    // Light scrim: the XMB wave reads through instead of sitting behind a dark overlay.
    lightScrim: Boolean = false,
    // Pinned footer under the content (the wizard's ✕ Enter / ○ Back chrome). When set, the
    // content area becomes a weighted column so the viewport excludes the footer band.
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // The screen content owns the actual verticalScroll state. All focus visibility and boundary
    // operations use this registered state so touch scrolling and controller navigation share one
    // scroll owner.
    val contentScrollState = remember { mutableStateOf<ScrollState?>(null) }
    val bootstrapFR = remember { FocusRequester() }
    val pendingAction = LocalSettingsPendingAction.current
    val onConsumed = LocalSettingsActionConsumed.current
    // Menu backdrop is tinted by the user's chosen color scheme (the same background anchors
    // the XMB wave uses), so settings screens match the theme instead of a flat black panel.
    val pfpColors = LocalPFPColors.current

    // Tracks the onclick of whichever row currently has controller focus
    val focusedRowClick = remember { mutableStateOf<(() -> Unit)?>(null) }
    // Declarative navigation model: owns the focused key, ordered movement and selection.
    // Rows feed it items via the ordered registration list below.
    val navigationState = remember { ControllerNavigationState() }
    val cursorVisible = remember { mutableStateOf(true) }
    // Root-space centre of the visible content viewport. Touch scrolling hides the cursor;
    // the next controller action reanchors focus to the closest visible node instead of resuming
    // the previously focused (possibly off-screen) row.
    val touchScrolled = remember { mutableStateOf(false) }

    // Per-row FocusRequesters keyed by focusKey, for focus-restoration on child return.
    val focusRegistry = remember { mutableStateMapOf<String, FocusRequester>() }

    // FocusRequester of the first interactive row — the reliable initial-focus target.
    val firstRowFocus = remember { mutableStateOf<FocusRequester?>(null) }

    // On-screen Y of every interactive row — presentation only (scroll-into-view, reseed
    // fallback). Movement and selection live in ControllerNavigationState.
    val rowPositions = remember { mutableStateMapOf<FocusRequester, Float>() }
    // On-screen HEIGHT of every interactive row — presentation only, for the keep-in-view
    // clamp to align a row's bottom edge (rather than its top) at the lower viewport edge.
    val rowSizes = remember { mutableStateMapOf<FocusRequester, Float>() }
    // Ordered navigation: rows register (FocusRequester, ControllerNavItem) pairs in composition
    // order; the model consumes them and the scaffold requests focus for the focused key.
    val navigationOrder =
        remember { androidx.compose.runtime.mutableStateListOf<Pair<FocusRequester, ControllerNavItem>>() }
    // Row key -> its inline action (key, FocusRequester) pairs, for LEFT/RIGHT navigation.
    val rowActionFrs = remember {
        mutableStateMapOf<String, SnapshotStateList<Pair<String, FocusRequester>>>()
    }

    // Keep the model's item list in lockstep with what rows register. Rows add/remove/refresh
    // their pairs; snapshotFlow observes any change and the model preserves the focused key
    // (recovering to the nearest survivor if the focused item disappears).
    //
    // The list is ordered by on-screen Y — NOT registration order. Rows register on first
    // composition, so when data loads asynchronously and rows insert mid-list (a fresh Library
    // Manager open: placeholder rows first, then root paths and console cards), the registration
    // list ends up scrambled and the cursor would jump past the inserted rows. Every row in the
    // composed Column has a known Y, so sorting by it makes traversal follow what the user sees.
    // Unpositioned rows (not yet laid out) keep registration order via the stable sort.
    LaunchedEffect(navigationOrder, rowPositions) {
        snapshotFlow {
            // Reading rowPositions subscribes the flow to layout changes, so the order re-sorts
            // the moment a late row lands (or everything scrolls by the same delta — a no-op).
            // The geometry map is emitted alongside so position changes re-trigger the flow even
            // when the visual order itself is unchanged (rows already in order) — the engine's
            // touch re-anchor needs the Y positions, not just the ordering.
            val sorted = navigationOrder.sortedBy { (fr, _) -> rowPositions[fr] ?: Float.MAX_VALUE }
            val geometry = sorted.mapNotNull { (fr, item) ->
                rowPositions[fr]?.let { item.key to it }
            }.toMap()
            sorted to geometry
        }
            .collect { (entries, geometry) ->
                navigationState.updateItems(entries.map { it.second }, geometry)
            }
    }
    // Absolute content position at the top of the scroll viewport. Used as the stable anchor
    // when clamping at the first item; unlike the focused row's moving Y it remains valid while
    // the column is scrolled.
    val firstVisibleContentY = remember { mutableStateOf<Float?>(null) }
    // Height of the visible content viewport (px), measured from the content Box. Keep-in-view
    // and re-anchoring use this REAL on-screen area — not the full configured screen height,
    // which includes system bars and would let the cursor walk past the visible edge.
    val contentViewportHeight = remember { mutableStateOf<Float?>(null) }
    var focusedRow by remember { mutableStateOf<FocusRequester?>(null) }
    // Last known Y of the focused row — the anchor for re-focusing when that row is removed
    // from composition (imported list items, sections that re-render away).
    var lastFocusedY by remember { mutableStateOf<Float?>(null) }
    var refocusTick by remember { mutableStateOf(0) }

    // Set true once any row has actually received focus (the menu is no longer "dead").
    var focusRedirected by remember { mutableStateOf(false) }

    // Assign controller focus the moment the menu mounts. Rather than a single delayed
    // attempt (which silently fails if the focusable subtree isn't laid out yet, leaving the
    // menu visible with nothing focused), we re-issue requestFocus() every frame until a row
    // actually gains focus. This guarantees focus + visible highlight appear immediately with
    // no directional input. When restoreFocusKey is set we target that specific row (returning
    // from a child); otherwise we redirect Down from the top so a fresh open always starts at
    // the first item with no stale focus restored.
    LaunchedEffect(Unit) {
        Timber.d("Settings focus: screen opened ($title / $subtitle) restoreKey=$restoreFocusKey")
        var attempts = 0
        // Keep trying until a row actually takes focus. The target is the row we're restoring
        // to (returning from a child) or the first interactive row; both are real, laid-out
        // FocusRequesters. They may not be registered on the very first frame, so we retry —
        // falling back to the 0dp bootstrap only until the real target appears.
        while (!focusRedirected && attempts < 30) {
            withFrameNanos { /* wait for this frame's layout pass */ }
            val target =
                if (restoreFocusKey != null) focusRegistry[restoreFocusKey] else firstRowFocus.value
            if (target != null) {
                runCatching { target.requestFocus() }
            } else {
                runCatching { bootstrapFR.requestFocus() }
            }
            attempts++
        }
        // Last resort if nothing took focus: first row, then bootstrap.
        if (!focusRedirected) {
            (firstRowFocus.value ?: bootstrapFR).let { runCatching { it.requestFocus() } }
        }
        Timber.d("Settings focus: default focus assigned=$focusRedirected after $attempts frame(s) ($subtitle)")
    }

    // The focused row left composition (e.g. a "Found Games" item just imported away, or a
    // section re-rendered): the model recovers the focused key to the nearest surviving item
    // by list order, and we request focus there so the cursor never silently disappears.
    // Runs a frame later so the new layout has settled and the row's onDispose has run.
    LaunchedEffect(refocusTick) {
        if (refocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        val sortedEntries = navigationOrder.sortedBy { (fr, _) -> rowPositions[fr] ?: Float.MAX_VALUE }
        navigationState.updateItems(
            sortedEntries.map { it.second },
            sortedEntries.mapNotNull { (fr, item) ->
                rowPositions[fr]?.let { item.key to it }
            }.toMap(),
        )
        val target = navigationState.focusedKey
            ?.let { key -> navigationOrder.firstOrNull { it.second.key == key }?.first }
            ?: firstRowFocus.value
        target?.let { runCatching { it.requestFocus() } }
        Timber.d("Settings focus: refocused after row removal (key=${navigationState.focusedKey})")
    }

    // Handle UP / DOWN / SELECT forwarded from XMBViewModel via pendingSettingsAction
    // Keep the focused row inside the REAL visible content viewport and scroll it into view.
    // The bounds come from the measured content Box (root-space Y), so the cursor can never
    // walk past the visible edge — even on devices with system bars. A small margin keeps the
    // row below the header (breadcrumb stays visible) and above the bottom edge.
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(focusedRow) {
        val focused = focusedRow ?: return@LaunchedEffect
        withFrameNanos { }
        val y = rowPositions[focused] ?: return@LaunchedEffect
        val viewportTop = firstVisibleContentY.value ?: return@LaunchedEffect
        val viewportHeight = contentViewportHeight.value ?: return@LaunchedEffect
        val activeScrollState = contentScrollState.value ?: return@LaunchedEffect
        val rowHeight = rowSizes[focused] ?: return@LaunchedEffect
        val margin = 16f * density.density
        val viewportBottom = viewportTop + viewportHeight
        // Bring the WHOLE focused row inside the visible area, symmetric for both edges: the
        // row's TOP clears the top margin and its BOTTOM clears the bottom margin. Aligning by
        // the row's own relevant edge is what keeps it fully on-screen — scrolling so the TOP
        // just clears the bottom edge would leave the lower part of the cursor clipped below
        // the fold (the bug fixed here; going UP was already correct because a row extends
        // downward from its top).
        val target = when {
            y < viewportTop + margin ->
                (activeScrollState.value.toFloat() - (viewportTop + margin - y)).coerceAtLeast(0f)
            y + rowHeight > viewportBottom - margin ->
                activeScrollState.value.toFloat() + ((y + rowHeight) - (viewportBottom - margin))
            else -> null
        }
        target?.let {
            activeScrollState.animateScrollTo(it.toInt().coerceIn(0, activeScrollState.maxValue))
        }
    }

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Request Compose focus for the row or inline action registered under [key]; fall back to
    // reseeding by last-known Y when nothing is registered (e.g. an empty or loading screen).
    fun requestFocusFor(key: String?) {
        val fr = navigationOrder.firstOrNull { it.second.key == key }?.first
            ?: rowActionFrs.entries.firstOrNull { (_, actions) -> actions.any { it.first == key } }
                ?.value?.firstOrNull { it.first == key }?.second
        if (fr != null) {
            runCatching { fr.requestFocus() }
        } else {
            reseedFocus(rowPositions, lastFocusedY, firstRowFocus.value)
        }
    }

    LaunchedEffect(pendingAction) {
        if (pendingAction == null) return@LaunchedEffect
        // Every controller action is a source transition, including actions intercepted by a
        // screen-specific modal/editor. Reconcile the existing stable-key focus before showing it.
        cursorVisible.value = true
        navigationState.markControllerInput()
        // Revival press: the first controller press after a touch drag. It re-anchors the
        // cursor to the row nearest the viewport centre (the content the user was looking at)
        // and shows it there — it must NOT also move. Only the NEXT directional press moves,
        // so the cursor can never step past the visible area while it is re-appearing.
        val revivalPress = touchScrolled.value
        if (revivalPress) {
            val viewportTop = firstVisibleContentY.value
            val viewportHeight = contentViewportHeight.value
            val viewportCenter = if (viewportTop != null && viewportHeight != null) {
                viewportTop + viewportHeight / 2f
            } else {
                null
            }
            if (viewportCenter != null) {
                navigationState.focusNearestTo(viewportCenter)
            }
            touchScrolled.value = false
        }
        val focusedKey = navigationState.focusedKey
        if (focusedKey != null) {
            requestFocusFor(focusedKey)
        }
        Timber.d("Settings focus: action=$pendingAction focusedClick=${focusedRowClick.value != null}")
        // Give the screen a chance to consume the action first (e.g. remap capture mode).
        // If the interceptor returns true the action is fully consumed — no navigation fires.
        if (onInterceptAction?.invoke(pendingAction) == true) {
            onConsumed()
            return@LaunchedEffect
        }
        // Revival presses are consumed by the re-anchor itself: no directional movement.
        if (revivalPress && pendingAction.isDirectional) {
            onConsumed()
            return@LaunchedEffect
        }
        when (pendingAction) {
            // Explicit, clamped vertical navigation: focus the nearest registered row above/below
            // the current one by screen-Y. At the first/last row there is no neighbour, so focus
            // simply stays — it can NEVER wander into the XMB because we only ever requestFocus()
            // a registered settings row, never call directional moveFocus. If the current row's
            // geometry isn't known yet, re-seed on the first row rather than risk an escape.
            GamepadAction.NAVIGATE_UP -> {
                val previous = navigationState.focusedKey
                val target = navigationState.move(-1)
                // Clamped at the first navigable item: stay put but scroll back to the top.
                if (target != null && target == previous) {
                    // Up at the first logical item is a deliberate top-boundary action. Always
                    // return the whole scrollable settings column to offset zero, including
                    // screens whose first visible content is a section header.
                    coroutineScope.launch { contentScrollState.value?.animateScrollTo(0) }
                }
                requestFocusFor(target)
            }

            GamepadAction.NAVIGATE_DOWN -> {
                requestFocusFor(navigationState.move(1))
            }
            // Inline trailing actions (e.g. a root row's Replace/Remove buttons) are reached
            // horizontally; LEFT/RIGHT is a no-op on rows without them.
            GamepadAction.NAVIGATE_LEFT -> {
                navigationState.moveHorizontal(-1)?.let { requestFocusFor(it) }
            }

            GamepadAction.NAVIGATE_RIGHT -> {
                navigationState.moveHorizontal(1)?.let { requestFocusFor(it) }
            }

            GamepadAction.SELECT -> {
                // The model dispatches to the focused item; the registered-click fallback only
                // fires when the model has nothing to dispatch (e.g. no rows composed yet) and
                // stays fresh through the focus tracker.
                if (!navigationState.select()) focusedRowClick.value?.invoke()
            }
            // One-level-up navigation: invoke this screen's back handler. For multi-step
            // screens that's "collapse a sub-step (else close)"; for leaf screens it closes
            // the overlay back to the XMB. Mirrors the on-screen Back button exactly.
            GamepadAction.BACK -> {
                onBack()
            }

            else -> Unit
        }
        onConsumed()
    }

    CompositionLocalProvider(
        LocalSettingsCursorVisible provides cursorVisible.value,
        // Marking focusRedirected here means ANY row gaining focus stops the bootstrap loop,
        // covering both the first-row redirect and the restore-to-key path.
        LocalSettingsFocusTracker provides { click ->
            focusedRowClick.value = click; focusRedirected = true
        },
        LocalSettingsTouchInput provides {
            cursorVisible.value = false
            touchScrolled.value = true
            navigationState.markTouchInput()
            onTouchInput()
        },
        LocalSettingsFocusRegistry provides focusRegistry,
        // First clickable row to compose wins the initial-focus slot.
        LocalSettingsRegisterFirstFocusable provides { fr ->
            if (firstRowFocus.value == null) firstRowFocus.value = fr
        },
        LocalSettingsRowPositions provides rowPositions,
        LocalSettingsRowSizes provides rowSizes,
        LocalSettingsNavigationOrder provides navigationOrder,
        LocalSettingsReportFocused provides { fr ->
            focusedRow = fr
            rowPositions[fr]?.let { lastFocusedY = it }
            // Keep the model's focused key aligned with real Compose focus (initial focus,
            // restore-to-key, touch): movement and selection both read from the model.
            val key = navigationOrder.firstOrNull { it.first === fr }?.second?.key
                ?: rowActionFrs.entries.firstOrNull { (_, actions) -> actions.any { it.second === fr } }
                    ?.let { (_, actions) -> actions.firstOrNull { it.second === fr }?.first }
            if (key != null) navigationState.setFocused(key)
        },
        LocalSettingsReportRemoved provides { fr ->
            if (focusedRow == fr) {
                focusedRow = null
                refocusTick++
            }
        },
        LocalSettingsRowActions provides rowActionFrs,
        LocalSettingsScrollStateRegistrar provides { state -> contentScrollState.value = state },
    ) {
        Box(
            modifier = modifier
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.pressed || it.position != it.previousPosition }) {
                                cursorVisible.value = false
                                touchScrolled.value = true
                                navigationState.markTouchInput()
                                onTouchInput()
                            }
                            event.changes.forEach { change ->
                                if (change.position != change.previousPosition) {
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                .fillMaxSize()
                // Semi-transparent scrim so the XMB wave/wallpaper background stays visible behind
                // Settings (the XMB foreground is hidden by XMBShell while a Settings screen is up).
                // The wizard skin uses a much lighter scrim so the wave reads through like the
                // PSP original's rich blue background while white text stays readable.
                .background(
                    if (lightScrim) {
                        Brush.verticalGradient(
                            0f to pfpColors.backgroundTop.copy(alpha = 0.45f),
                            1f to pfpColors.backgroundBottom.copy(alpha = 0.55f),
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to pfpColors.backgroundTop.copy(alpha = 0.72f),
                            1f to pfpColors.backgroundBottom.copy(alpha = 0.90f),
                        )
                    }
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 720.dp),
            ) {
                if (header != null) {
                    header()
                } else {
                // ── Header — breadcrumb form, matching the detail menus: the ◀ back arrow
                // leads, followed by the title stack. Excluded from focus traversal: the arrow
                // is clickable (touch only — the controller uses the B button), so without this,
                // pressing UP on the first row would jump focus up into the header.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = false }
                        .padding(horizontal = 48.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Arrow AND title stack both trigger back — one tap target, no press highlight.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                            indication = null,
                        ) { onBack() },
                    ) {
                        Text(
                            text = "◀",
                            color = SettingsSubtext,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(end = 20.dp),
                        )
                        Column {
                            Text(
                                text = title.uppercase(),
                                color = SettingsAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp,
                            )
                            Text(
                                text = subtitle,
                                color = SettingsText,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                }
                }

                if (showDivider) HorizontalDivider(color = SettingsDivider)

                // Invisible 0dp focus bootstrap element. requestFocus() lands here first;
                // onFocusChanged immediately redirects to the first real interactive row via
                // moveFocus(Down). This avoids needing focusGroup() which is not reliably
                // available across all Compose versions.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.dp)
                        .focusRequester(bootstrapFR)
                        .focusable()
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                // Hand off to the first interactive row (the default focus target).
                                focusManager.moveFocus(FocusDirection.Down)
                                focusRedirected = true
                                Timber.d("Settings focus: default focus → first item ($subtitle)")
                            }
                        }
                )

                // Content owns the remaining height; with a wizard footer the content Box is
                // weighted so the measured viewport (below) excludes the footer band and
                // keep-in-view clamping never walks a row underneath it.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (footer != null) Modifier.weight(1f) else Modifier)
                        .onGloballyPositioned {
                            firstVisibleContentY.value = it.localToRoot(Offset.Zero).y
                            contentViewportHeight.value = it.size.height.toFloat()
                        },
                ) {
                    content()
                }

                if (footer != null) {
                    // Footer chrome (✕ Enter / ○ Back) is display-only — never a focus target,
                    // so UP on the first content row cannot land inside it.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties { canFocus = false },
                    ) {
                        footer()
                    }
                }
            }
        }
    }
}

// ── Reusable row components ───────────────────────────────────────────────────
// (Controller-row registration helpers live in ControllerRowRegistration.kt — shared with the
// first-run wizard's row family so focus behavior can never drift between the two families.)

@Composable
fun SettingsGroup(title: String) {
    // Headers are visual landmarks. The scaffold's first-item clamp scrolls the complete
    // settings column to offset zero, ensuring a long screen that starts with this header can
    // always be returned to its true top with UP at the first row.
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.1f))
            .padding(start = 48.dp, top = 10.dp, bottom = 10.dp),
        text = title.uppercase(),
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
    )
}

/**
 * A controller-reachable inline action rendered in a [SettingsRow]'s trailing slot. Reached by
 * pressing RIGHT onto the row; LEFT/RIGHT steps between a row's actions and back to the row.
 * SELECT activates the focused action.
 */
class SettingsRowAction(
    val label: String,
    val onClick: () -> Unit,
    val onLongPress: (() -> Unit)? = null,
    // Background color drawn behind the icon when this action holds controller focus.
    val actionFocusBackgroundColor: Color = Color.White.copy(alpha = 0.25f),
    val icon: @Composable () -> Unit,
)

@Composable
fun SettingsRow(
    label: String,
    sublabel: String? = null,
    focusKey: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    // Inline controller-reachable actions (e.g. Replace/Remove buttons), navigated via LEFT/RIGHT.
    actions: List<SettingsRowAction> = emptyList(),
    // Reports controller-focus changes so a screen can track which row is hovered (e.g. to
    // open a per-row context menu on the options button).
    onFocusChangedExternal: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    // When true the row-level cursor fill is suppressed while an inline action has focus,
    // letting the action's own background be the sole highlight indicator.
    hideRowHighlightOnActionFocus: Boolean = false,
) {
    val actionFocusCount = remember { mutableIntStateOf(0) }
    val anyActionFocused = actionFocusCount.intValue > 0
    val focusTracker = LocalSettingsFocusTracker.current
    val touchInput = LocalSettingsTouchInput.current
    val cursorVisible = LocalSettingsCursorVisible.current
    val reportFocused = LocalSettingsReportFocused.current
    var isFocused by remember { mutableStateOf(false) }

    // EVERY row is controller-focusable — a non-focusable row is a dead zone the cursor can't
    // reach or scroll to (read-only value rows, info footers). Only rows with a real [onClick]
    // claim the initial-focus slot, so a screen still opens on its first ACTION, and only they
    // draw the strong cursor fill; read-only rows get a softer frame and SELECT is a no-op.
    // Registration + geometry reporting are shared with the wizard's row family via
    // rememberControllerRowRegistration so both families navigate identically.
    val row = rememberControllerRowRegistration(
        prefix = "row",
        focusKey = focusKey,
        claimInitialFocus = onClick != null,
        selectable = onClick != null,
        onSelect = onClick,
        onLongPress = onLongPress,
        trailingActionsFor = { rowKey ->
            actions.mapIndexed { index, action ->
                ControllerNavItem(
                    key = "$rowKey:action:$index",
                    focusable = true,
                    selectable = true,
                    enabled = true,
                    onSelect = action.onClick,
                    onLongPress = action.onLongPress,
                )
            }
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(row.focusRequester)
            // Report on-screen Y and height so the scaffold can navigate and keep the row in view.
            .then(row.positionReporting)
            // Observe focus to register onclick with scaffold (for SELECT) and show highlight
            .pointerInput(row.rowKey, onClick, onLongPress) {
                detectTapGestures(
                    onTap = { touchInput(); onClick?.invoke() },
                    onLongPress = { touchInput(); onLongPress?.invoke() },
                )
            }
            .onFocusChanged { state ->
                isFocused = state.isFocused
                onFocusChangedExternal?.invoke(state.isFocused)
                if (state.isFocused) {
                    focusTracker(onClick)
                    reportFocused(row.focusRequester)
                    Timber.d("Settings focus: row=\"$label\" clickable=${onClick != null}")
                }
            }
            // One consistent cursor fill for every focused row — read-only rows get the same
            // highlight as actions. A dimmer tint read as "not navigable" and broke the visual
            // rhythm, so the cursor now treats every row identically.
            .background(
                if (isFocused && cursorVisible && !(hideRowHighlightOnActionFocus && anyActionFocused))
                    com.playfieldportal.core.ui.theme.menuCursorFill()
                else Color.Transparent
            )
            .focusable()
            .padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isFocused && cursorVisible && !(hideRowHighlightOnActionFocus && anyActionFocused)) Color.White else SettingsText,
                fontSize = 15.sp,
            )
            if (!sublabel.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(sublabel, color = SettingsSubtext, fontSize = 12.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.width(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    key(index) {
                        SettingsRowActionButton(
                            rowKey = row.rowKey,
                            index = index,
                            action = action,
                            onFocusedChanged = { focused ->
                                if (focused) actionFocusCount.intValue++
                                else actionFocusCount.intValue--
                            },
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 48.dp))
}

/**
 * Wraps arbitrary [content] as a controller-focusable, confirm/tap-activatable settings element,
 * registering with the scaffold's focus system exactly like [SettingsRow]. Use for custom rows that
 * don't fit the label/sublabel layout (e.g. the Shiba player card). [content] receives whether the
 * element currently holds controller focus, so the caller can draw its own highlight.
 */
@Composable
fun SettingsFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusKey: String? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focusTracker = LocalSettingsFocusTracker.current
    val touchInput = LocalSettingsTouchInput.current
    val reportFocused = LocalSettingsReportFocused.current
    var isFocused by remember { mutableStateOf(false) }

    val row = rememberControllerRowRegistration(
        prefix = "custom",
        focusKey = focusKey,
        claimInitialFocus = true,
        selectable = true,
        onSelect = onClick,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(row.focusRequester)
            .then(row.positionReporting)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    focusTracker(onClick)
                    reportFocused(row.focusRequester)
                }
            }
            .pointerInput(onClick) {
                detectTapGestures(onTap = { touchInput(); onClick() })
            }
            .focusable(),
    ) {
        content(isFocused)
    }
}

@Composable
fun SettingsToggleRow(
    label: String,
    sublabel: String? = null,
    focusKey: String? = null,
    leading: @Composable (() -> Unit)? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingsRow(
        label = label,
        sublabel = sublabel,
        focusKey = focusKey,
        leading = leading,
        // Row-level click so controller SELECT can toggle it
        onClick = { onToggle(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SettingsAccent,
                    uncheckedThumbColor = SettingsSubtext,
                    uncheckedTrackColor = SettingsDivider,
                ),
            )
        },
    )
}

@Composable
fun SettingsValueRow(
    label: String,
    value: String,
    sublabel: String? = null,
    focusKey: String? = null,
    onFocusChangedExternal: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(
        label = label,
        sublabel = sublabel,
        focusKey = focusKey,
        onFocusChangedExternal = onFocusChangedExternal,
        onClick = onClick,
        trailing = {
            Text(
                text = value,
                color = SettingsAccent,
                fontSize = 13.sp,
            )
        },
    )
}

// Confirm-to-edit text field for controller navigation. Navigating onto the field only
// highlights it (read-only, no keyboard); pressing SELECT (A) — or tapping, for touch —
// enters edit mode and opens the keyboard. IME "Done", or focus leaving the field, exits
// edit mode. This keeps the keyboard from popping up just by scrolling past the field.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    focusKey: String? = null,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    helper: String? = null,
    enabled: Boolean = true,
) {
    val focusTracker = LocalSettingsFocusTracker.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reportFocused = LocalSettingsReportFocused.current
    var editing by remember { mutableStateOf(false) }

    // Always focusable so this field can be the screen's initial-focus target (a screen that
    // starts with a text field still opens with it highlighted, read-only). Registration is
    // shared with every other row family via rememberControllerRowRegistration; SELECT enters
    // edit mode, and disabled fields are excluded from navigation entirely.
    val row = rememberControllerRowRegistration(
        prefix = "field",
        focusKey = focusKey,
        claimInitialFocus = true,
        selectable = enabled,
        enabled = enabled,
        onSelect = { editing = true },
    )
    val fr = row.focusRequester

    // The keyboard follows edit mode only — focus alone (navigating onto the field) never
    // opens it, because the field stays read-only until SELECT/tap flips `editing`.
    // The readOnly -> editable flip restarts the field's text-input session asynchronously, so
    // show() in the same frame silently no-ops (the field looks dead on a controller). Settle a
    // frame, re-assert focus on the now-editable field, settle again, then show the keyboard.
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            withFrameNanos { }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 48.dp, vertical = 8.dp)) {
        Text(
            text = label,
            color = SettingsSubtext,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                readOnly = !editing,
                singleLine = singleLine,
                placeholder = { Text(placeholder, color = SettingsSubtext) },
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = { editing = false }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SettingsText,
                    unfocusedTextColor = SettingsText,
                    focusedBorderColor = SettingsAccent,
                    unfocusedBorderColor = SettingsDivider,
                    cursorColor = SettingsAccent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fr)
                    .then(row.positionReporting)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            // Controller SELECT over the field starts editing (opens the keyboard).
                            focusTracker { editing = true }
                            reportFocused(fr)
                        } else {
                            editing = false
                        }
                    },
            )
            // While not editing, a non-focusable tap layer lets touch users enter edit mode
            // (a read-only field ignores taps). pointerInput adds no focus target, so it never
            // interferes with controller D-pad traversal.
            if (!editing && enabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { editing = true } },
                )
            }
        }
        if (!helper.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = helper, color = SettingsSubtext.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}
