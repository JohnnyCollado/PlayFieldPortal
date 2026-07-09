package com.playfieldportal.feature.settings.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import com.playfieldportal.core.ui.theme.LocalPFPColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import timber.log.Timber

// ── CompositionLocals — provided by SettingsNavHost, consumed by SettingsScaffold ──

val LocalSettingsPendingAction  = compositionLocalOf<GamepadAction?> { null }
val LocalSettingsActionConsumed = compositionLocalOf<() -> Unit> { {} }

// Internal tracker: rows register their onClick when they gain focus so the scaffold
// can invoke the right action on a controller SELECT press.
internal val LocalSettingsFocusTracker =
    compositionLocalOf<(((() -> Unit)?) -> Unit)> { {} }

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

// Explicit vertical navigation. Rows report their on-screen Y here, and which row currently
// holds focus. Up/Down then focus the nearest registered row above/below — never directional
// moveFocus, which escapes into the XMB's focusable items behind the overlay (proven by logs:
// canFocus inheritance does not reach the XMB's LazyColumn across subcompositions).
internal val LocalSettingsRowPositions =
    compositionLocalOf<SnapshotStateMap<FocusRequester, Float>?> { null }
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

val SettingsBg         = Color(0xE6000000)
val SettingsAccent     = Color(0xFF4A90D9)
val SettingsText       = Color.White
val SettingsSubtext    = Color(0xFFAAAAAA)
val SettingsDivider    = Color(0xFF2A2A2A)
val SettingsSelectedBg = Color(0xFF4A90D9).copy(alpha = 0.14f)

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
    content: @Composable () -> Unit,
) {
    val focusManager    = LocalFocusManager.current
    val bootstrapFR     = remember { FocusRequester() }
    val pendingAction   = LocalSettingsPendingAction.current
    val onConsumed      = LocalSettingsActionConsumed.current
    // Menu backdrop is tinted by the user's chosen color scheme (the same background anchors
    // the XMB wave uses), so settings screens match the theme instead of a flat black panel.
    val pfpColors       = LocalPFPColors.current

    // Tracks the onclick of whichever row currently has controller focus
    val focusedRowClick = remember { mutableStateOf<(() -> Unit)?>(null) }

    // Per-row FocusRequesters keyed by focusKey, for focus-restoration on child return.
    val focusRegistry   = remember { mutableStateMapOf<String, FocusRequester>() }

    // FocusRequester of the first interactive row — the reliable initial-focus target.
    val firstRowFocus   = remember { mutableStateOf<FocusRequester?>(null) }

    // On-screen Y of every interactive row, and which row currently holds focus — drives
    // explicit, escape-proof Up/Down navigation.
    val rowPositions    = remember { mutableStateMapOf<FocusRequester, Float>() }
    var focusedRow      by remember { mutableStateOf<FocusRequester?>(null) }
    // Last known Y of the focused row — the anchor for re-focusing when that row is removed
    // from composition (imported list items, sections that re-render away).
    var lastFocusedY    by remember { mutableStateOf<Float?>(null) }
    var refocusTick     by remember { mutableStateOf(0) }

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
            val target = if (restoreFocusKey != null) focusRegistry[restoreFocusKey] else firstRowFocus.value
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
    // section re-rendered): refocus the nearest surviving row by last-known Y so the cursor
    // never silently disappears. Runs a frame later so the new layout has settled.
    LaunchedEffect(refocusTick) {
        if (refocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        val anchor = lastFocusedY
        val target = if (anchor != null) {
            rowPositions.entries.minByOrNull { kotlin.math.abs(it.value - anchor) }?.key
        } else null
        (target ?: firstRowFocus.value)?.let { runCatching { it.requestFocus() } }
        Timber.d("Settings focus: refocused after row removal (anchorY=$anchor, found=${target != null})")
    }

    // Handle UP / DOWN / SELECT forwarded from XMBViewModel via pendingSettingsAction
    LaunchedEffect(pendingAction) {
        if (pendingAction == null) return@LaunchedEffect
        Timber.d("Settings focus: action=$pendingAction focusedClick=${focusedRowClick.value != null}")
        // Give the screen a chance to consume the action first (e.g. remap capture mode).
        // If the interceptor returns true the action is fully consumed — no navigation fires.
        if (onInterceptAction?.invoke(pendingAction) == true) {
            onConsumed()
            return@LaunchedEffect
        }
        when (pendingAction) {
            // Explicit, clamped vertical navigation: focus the nearest registered row above/below
            // the current one by screen-Y. At the first/last row there is no neighbour, so focus
            // simply stays — it can NEVER wander into the XMB because we only ever requestFocus()
            // a registered settings row, never call directional moveFocus. If the current row's
            // geometry isn't known yet, re-seed on the first row rather than risk an escape.
            GamepadAction.NAVIGATE_UP   -> {
                val curY = focusedRow?.let { rowPositions[it] }
                if (curY != null) {
                    rowPositions.entries.filter { it.value < curY - 0.5f }
                        .maxByOrNull { it.value }?.key
                        ?.let { runCatching { it.requestFocus() } }
                } else reseedFocus(rowPositions, lastFocusedY, firstRowFocus.value)
            }
            GamepadAction.NAVIGATE_DOWN -> {
                val curY = focusedRow?.let { rowPositions[it] }
                if (curY != null) {
                    rowPositions.entries.filter { it.value > curY + 0.5f }
                        .minByOrNull { it.value }?.key
                        ?.let { runCatching { it.requestFocus() } }
                } else reseedFocus(rowPositions, lastFocusedY, firstRowFocus.value)
            }
            GamepadAction.SELECT        -> focusedRowClick.value?.invoke()
            // One-level-up navigation: invoke this screen's back handler. For multi-step
            // screens that's "collapse a sub-step (else close)"; for leaf screens it closes
            // the overlay back to the XMB. Mirrors the on-screen Back button exactly.
            GamepadAction.BACK          -> onBack()
            else                        -> Unit
        }
        onConsumed()
    }

    CompositionLocalProvider(
        // Marking focusRedirected here means ANY row gaining focus stops the bootstrap loop,
        // covering both the first-row redirect and the restore-to-key path.
        LocalSettingsFocusTracker provides { click -> focusedRowClick.value = click; focusRedirected = true },
        LocalSettingsFocusRegistry provides focusRegistry,
        // First clickable row to compose wins the initial-focus slot.
        LocalSettingsRegisterFirstFocusable provides { fr -> if (firstRowFocus.value == null) firstRowFocus.value = fr },
        LocalSettingsRowPositions provides rowPositions,
        LocalSettingsReportFocused provides { fr ->
            focusedRow = fr
            rowPositions[fr]?.let { lastFocusedY = it }
        },
        LocalSettingsReportRemoved provides { fr ->
            if (focusedRow == fr) {
                focusedRow = null
                refocusTick++
            }
        },
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                // Semi-transparent scrim so the XMB wave/wallpaper background stays visible behind
                // Settings (the XMB foreground is hidden by XMBShell while a Settings screen is up).
                .background(
                    Brush.verticalGradient(
                        0f to pfpColors.backgroundTop.copy(alpha = 0.72f),
                        1f to pfpColors.backgroundBottom.copy(alpha = 0.90f),
                    )
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────────────────────
                // The header is excluded from focus traversal: its "◀ Back" text is clickable
                // (touch only — the controller uses the B button), so without this, pressing UP
                // on the first row would jump focus up into the header instead of clamping.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = false }
                        .padding(horizontal = 48.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text          = title.uppercase(),
                            color         = SettingsAccent,
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text       = subtitle,
                            color      = SettingsText,
                            fontSize   = 22.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }

                    Text(
                        text     = "◀  Back",
                        color    = SettingsSubtext,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onBack() },
                    )
                }

                HorizontalDivider(color = SettingsDivider)

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

                content()
            }
        }
    }
}

// ── Reusable row components ───────────────────────────────────────────────────

@Composable
fun SettingsGroup(title: String) {
    Text(
        text          = title.uppercase(),
        color         = SettingsAccent.copy(alpha = 0.7f),
        fontSize      = 10.sp,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier      = Modifier.padding(start = 48.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsRow(
    label: String,
    sublabel: String? = null,
    focusKey: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    // Reports controller-focus changes so a screen can track which row is hovered (e.g. to
    // open a per-row context menu on the options button).
    onFocusChangedExternal: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val focusTracker  = LocalSettingsFocusTracker.current
    val focusRegistry = LocalSettingsFocusRegistry.current
    val registerFirst = LocalSettingsRegisterFirstFocusable.current
    val rowPositions  = LocalSettingsRowPositions.current
    val reportFocused = LocalSettingsReportFocused.current
    val reportRemoved = LocalSettingsReportRemoved.current
    var isFocused by remember { mutableStateOf(false) }

    // Every clickable row owns a FocusRequester. Rows with a focusKey publish it so the scaffold
    // can restore focus to them; and the first clickable row to compose claims the initial-focus
    // slot so the menu always opens with a real, selectable row highlighted.
    val focusRequester = if (onClick != null) remember { FocusRequester() } else null
    if (focusKey != null && focusRequester != null) {
        DisposableEffect(focusKey) {
            focusRegistry[focusKey] = focusRequester
            onDispose { if (focusRegistry[focusKey] === focusRequester) focusRegistry.remove(focusKey) }
        }
    }
    if (focusRequester != null) {
        DisposableEffect(Unit) {
            registerFirst(focusRequester)
            onDispose {
                rowPositions?.remove(focusRequester)
                // If this row held focus, the scaffold refocuses the nearest surviving row.
                reportRemoved(focusRequester)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // Report on-screen Y so the scaffold can navigate to this row explicitly.
            .then(
                if (focusRequester != null && rowPositions != null) {
                    val positions = rowPositions
                    val req = focusRequester
                    Modifier.onGloballyPositioned { positions[req] = it.localToRoot(Offset.Zero).y }
                } else Modifier
            )
            // Observe focus to register onclick with scaffold (for SELECT) and show highlight
            .onFocusChanged { state ->
                isFocused = state.isFocused
                onFocusChangedExternal?.invoke(state.isFocused)
                if (state.isFocused) {
                    focusTracker(onClick)
                    focusRequester?.let { reportFocused(it) }
                    Timber.d("Settings focus: row=\"$label\" clickable=${onClick != null}")
                }
            }
            // Theme-adaptive cursor fill (noticeably stronger than the old fixed 14% blue).
            .background(
                if (isFocused && onClick != null) com.playfieldportal.core.ui.theme.menuCursorFill()
                else Color.Transparent
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text      = label,
                color     = if (isFocused && onClick != null) Color.White else SettingsText,
                fontSize  = 15.sp,
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
    }
    HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(start = 48.dp))
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
        label    = label,
        sublabel = sublabel,
        focusKey = focusKey,
        leading  = leading,
        // Row-level click so controller SELECT can toggle it
        onClick  = { onToggle(!checked) },
        trailing = {
            Switch(
                checked         = checked,
                onCheckedChange = onToggle,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor   = Color.White,
                    checkedTrackColor   = SettingsAccent,
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
        label    = label,
        sublabel = sublabel,
        focusKey = focusKey,
        onFocusChangedExternal = onFocusChangedExternal,
        onClick  = onClick,
        trailing = {
            Text(
                text     = value,
                color    = SettingsAccent,
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
) {
    val focusTracker  = LocalSettingsFocusTracker.current
    val focusRegistry = LocalSettingsFocusRegistry.current
    val registerFirst = LocalSettingsRegisterFirstFocusable.current
    val rowPositions  = LocalSettingsRowPositions.current
    val reportFocused = LocalSettingsReportFocused.current
    val keyboard      = LocalSoftwareKeyboardController.current
    val reportRemoved = LocalSettingsReportRemoved.current
    var editing by remember { mutableStateOf(false) }

    // Always own a FocusRequester so this field can be the screen's initial-focus target
    // (a screen that starts with a text field still opens with it highlighted, read-only).
    val fr = remember { FocusRequester() }
    if (focusKey != null) {
        DisposableEffect(focusKey) {
            focusRegistry[focusKey] = fr
            onDispose { if (focusRegistry[focusKey] === fr) focusRegistry.remove(focusKey) }
        }
    }
    DisposableEffect(Unit) {
        registerFirst(fr)
        onDispose {
            rowPositions?.remove(fr)
            reportRemoved(fr)
        }
    }

    // The keyboard follows edit mode only — focus alone (navigating onto the field) never
    // opens it, because the field stays read-only until SELECT/tap flips `editing`.
    LaunchedEffect(editing) {
        if (editing) keyboard?.show() else keyboard?.hide()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp)) {
        Text(text = label, color = SettingsSubtext, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            OutlinedTextField(
                value         = value,
                onValueChange = onValueChange,
                readOnly      = !editing,
                singleLine    = singleLine,
                placeholder   = { Text(placeholder, color = SettingsSubtext) },
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                    imeAction    = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = { editing = false }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor        = SettingsText,
                    unfocusedTextColor      = SettingsText,
                    focusedBorderColor      = SettingsAccent,
                    unfocusedBorderColor    = SettingsDivider,
                    cursorColor             = SettingsAccent,
                    focusedContainerColor   = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fr)
                    .then(
                        if (rowPositions != null) {
                            val positions = rowPositions
                            Modifier.onGloballyPositioned { positions[fr] = it.localToRoot(Offset.Zero).y }
                        } else Modifier
                    )
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
            if (!editing) {
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
