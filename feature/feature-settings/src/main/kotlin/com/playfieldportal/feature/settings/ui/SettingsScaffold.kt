package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
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
    content: @Composable () -> Unit,
) {
    val focusManager    = LocalFocusManager.current
    val bootstrapFR     = remember { FocusRequester() }
    val pendingAction   = LocalSettingsPendingAction.current
    val onConsumed      = LocalSettingsActionConsumed.current

    // Tracks the onclick of whichever row currently has controller focus
    val focusedRowClick = remember { mutableStateOf<(() -> Unit)?>(null) }

    // Per-row FocusRequesters keyed by focusKey, for focus-restoration on child return.
    val focusRegistry   = remember { mutableStateMapOf<String, FocusRequester>() }

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
        while (!focusRedirected && attempts < 12) {
            withFrameNanos { /* wait for this frame's layout pass */ }
            if (restoreFocusKey != null) {
                focusRegistry[restoreFocusKey]?.let { runCatching { it.requestFocus() } }
            } else {
                runCatching { bootstrapFR.requestFocus() }
            }
            attempts++
        }
        // Restore target never materialised (e.g. the row was removed) — fall back to the
        // first row so the menu is never left without a focused item.
        if (!focusRedirected && restoreFocusKey != null) {
            runCatching { bootstrapFR.requestFocus() }
        }
        Timber.d("Settings focus: default focus assigned=$focusRedirected after $attempts frame(s) ($subtitle)")
    }

    // Handle UP / DOWN / SELECT forwarded from XMBViewModel via pendingSettingsAction
    LaunchedEffect(pendingAction) {
        if (pendingAction == null) return@LaunchedEffect
        Timber.d("Settings focus: action=$pendingAction focusedClick=${focusedRowClick.value != null}")
        when (pendingAction) {
            GamepadAction.NAVIGATE_UP   -> focusManager.moveFocus(FocusDirection.Up)
            GamepadAction.NAVIGATE_DOWN -> focusManager.moveFocus(FocusDirection.Down)
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
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SettingsBg),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header ────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val focusTracker  = LocalSettingsFocusTracker.current
    val focusRegistry = LocalSettingsFocusRegistry.current
    var isFocused by remember { mutableStateOf(false) }

    // Clickable rows with a focusKey publish a FocusRequester so the scaffold can restore
    // focus to them. (A non-clickable row has no focus target, so a requester would be unusable.)
    val focusRequester = if (focusKey != null && onClick != null) remember(focusKey) { FocusRequester() } else null
    if (focusKey != null && focusRequester != null) {
        DisposableEffect(focusKey) {
            focusRegistry[focusKey] = focusRequester
            onDispose { if (focusRegistry[focusKey] === focusRequester) focusRegistry.remove(focusKey) }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // Observe focus to register onclick with scaffold (for SELECT) and show highlight
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    focusTracker(onClick)
                    Timber.d("Settings focus: row=\"$label\" clickable=${onClick != null}")
                }
            }
            .background(if (isFocused && onClick != null) SettingsSelectedBg else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
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
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingsRow(
        label    = label,
        sublabel = sublabel,
        focusKey = focusKey,
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
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(
        label    = label,
        sublabel = sublabel,
        focusKey = focusKey,
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
