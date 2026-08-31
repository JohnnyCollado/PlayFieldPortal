package com.playfieldportal.feature.settings.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.menuCursor
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.feature.settings.ui.ControllerNavItem
import com.playfieldportal.feature.settings.ui.LocalSettingsCursorVisible
import com.playfieldportal.feature.settings.ui.LocalSettingsFocusTracker
import com.playfieldportal.feature.settings.ui.LocalSettingsReportFocused
import com.playfieldportal.feature.settings.ui.LocalSettingsTouchInput
import com.playfieldportal.feature.settings.ui.SettingsAccent
import com.playfieldportal.feature.settings.ui.SettingsDivider
import com.playfieldportal.feature.settings.ui.SettingsRowAction
import com.playfieldportal.feature.settings.ui.SettingsRowActionButton
import com.playfieldportal.feature.settings.ui.SettingsSubtext
import com.playfieldportal.feature.settings.ui.SettingsText
import com.playfieldportal.feature.settings.ui.rememberControllerRowRegistration

// ── Wizard row family ───────────────────────────────────────────────────────────
//
// The first-run wizard's PSP-styled rows. Every row registers through the SAME controller-row
// helpers the settings rows use (rememberControllerRowRegistration), so UP/DOWN traversal,
// SELECT activation, LEFT/RIGHT inline actions, keep-in-view scrolling and focus restoration
// behave identically to Settings — only the skin differs: inset rounded cursor, no dividers,
// centered page chrome around them.

/**
 * The wizard's generic row. Rows with a real [onClick] claim the page's initial focus; rows
 * with only inline [actions] (root rows) do not, so a page always opens on its first ACTION.
 */
@Composable
fun WizardRow(
    label: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    focusKey: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    actions: List<SettingsRowAction> = emptyList(),
    // Root rows: while an inline action holds focus, the row-level cursor fill is suppressed so
    // the action's own background is the sole highlight (same rule as the settings DirectoryRow).
    hideRowHighlightOnActionFocus: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val focusTracker = LocalSettingsFocusTracker.current
    val touchInput = LocalSettingsTouchInput.current
    val cursorVisible = LocalSettingsCursorVisible.current
    val reportFocused = LocalSettingsReportFocused.current
    var isFocused by remember { mutableStateOf(false) }
    val actionFocusCount = remember { mutableIntStateOf(0) }
    val anyActionFocused = actionFocusCount.intValue > 0

    val row = rememberControllerRowRegistration(
        prefix = "wizard",
        focusKey = focusKey,
        claimInitialFocus = onClick != null,
        selectable = onClick != null,
        onSelect = onClick,
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
    val highlighted = isFocused && cursorVisible && !(hideRowHighlightOnActionFocus && anyActionFocused)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(row.focusRequester)
            .then(row.positionReporting)
            .pointerInput(row.rowKey, onClick) {
                detectTapGestures(onTap = { touchInput(); onClick?.invoke() })
            }
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    focusTracker(onClick)
                    reportFocused(row.focusRequester)
                }
            }
            .menuCursor(highlighted)
            .focusable()
            .padding(horizontal = 48.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (highlighted) Color.White else SettingsText,
                fontSize = 15.sp,
            )
            if (!sublabel.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = sublabel, color = SettingsSubtext, fontSize = 12.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
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
}

/** Label + value row (summary entries, connected-service status). */
@Composable
fun WizardValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    focusKey: String? = null,
    onClick: (() -> Unit)? = null,
) {
    WizardRow(
        label = label,
        modifier = modifier,
        sublabel = sublabel,
        focusKey = focusKey,
        onClick = onClick,
        trailing = {
            Text(
                text = value,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            )
        },
    )
}

/**
 * One managed root folder, mirroring Library Manager's directory row: non-selectable body with
 * Edit (re-link/re-point) and Remove as inline controller actions reached via LEFT/RIGHT.
 */
@Composable
fun WizardRootRow(
    name: String,
    sublabel: String?,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    WizardRow(
        label = name,
        sublabel = sublabel,
        modifier = modifier,
        hideRowHighlightOnActionFocus = true,
        actions = listOf(
            SettingsRowAction(
                "Edit folder", onEdit,
                actionFocusBackgroundColor = lerp(SettingsAccent, Color.Black, 0.50f),
            ) {
                Icon(
                    Icons.Default.Create,
                    contentDescription = "Edit folder",
                    tint = SettingsAccent,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                        .padding(4.dp),
                )
            },
            SettingsRowAction(
                "Remove folder", onRemove,
                actionFocusBackgroundColor = lerp(Color(0xFFE55353), Color.Black, 0.50f),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove folder",
                    tint = Color(0xFFE55353),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                        .padding(4.dp),
                )
            },
        ),
    )
}

/** Checkbox row (terms-style toggles): SELECT/tap flips, ☑ drawn when checked. */
@Composable
fun WizardCheckboxRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusKey: String? = null,
) {
    WizardRow(
        label = label,
        modifier = modifier,
        focusKey = focusKey,
        onClick = { onToggle(!checked) },
        trailing = { WizardCheckboxGlyph(checked) },
    )
}

@Composable
private fun WizardCheckboxGlyph(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) Color.White else Color.Transparent)
            .border(2.dp, Color.White.copy(alpha = if (checked) 1f else 0.6f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                color = Color(0xFF06224B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The PSP rounded input field: near-white pill with dark text, confirm-to-edit like the
 * settings field (SELECT/tap enters edit and opens the keyboard; IME Done or focus leaving
 * exits). Optional [onAdvance] renders the reference's circular ▶ button at the field's right
 * edge — a real controller node too (DOWN from the field, SELECT advances) as well as a
 * touch target.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WizardTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusKey: String? = null,
    isPassword: Boolean = false,
    onAdvance: (() -> Unit)? = null,
) {
    val focusTracker = LocalSettingsFocusTracker.current
    val touchInput = LocalSettingsTouchInput.current
    val reportFocused = LocalSettingsReportFocused.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }

    val row = rememberControllerRowRegistration(
        prefix = "wizardfield",
        focusKey = focusKey,
        claimInitialFocus = true,
        selectable = true,
        onSelect = { editing = true },
    )
    val fr = row.focusRequester

    // The keyboard follows edit mode only — navigating onto the field never opens it (the
    // readOnly→editable flip restarts the input session, so settle a frame, re-assert focus,
    // settle again, then show — same sequence as SettingsTextFieldRow).
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = SettingsSubtext,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = !editing,
                    singleLine = true,
                    placeholder = { Text(placeholder, color = SettingsSubtext) },
                    visualTransformation =
                        if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { editing = false }),
                    // Same dark outlined field as SettingsTextFieldRow — white text on a
                    // transparent container, accent border when focused / divider when not.
                    // No separate border shape: the Material outline follows the host theme.
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
                                // SELECT over the field starts editing (opens the keyboard).
                                focusTracker { editing = true }
                                reportFocused(fr)
                            } else {
                                editing = false
                            }
                        },
                )
                // While not editing, a non-focusable tap layer lets touch users enter edit
                // mode; pointerInput adds no focus target, so D-pad traversal is untouched.
                if (!editing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) { detectTapGestures { editing = true } },
                    )
                }
            }
            if (onAdvance != null) {
                Spacer(Modifier.width(10.dp))
                WizardAdvanceButton(onAdvance, focusKey?.let { "${it}advance" })
            }
        }
    }
}

/** The reference's glowing ▶ circle — controller node (DOWN from the field) + touch target. */
@Composable
private fun WizardAdvanceButton(onAdvance: () -> Unit, focusKey: String?) {
    val focusTracker = LocalSettingsFocusTracker.current
    val touchInput = LocalSettingsTouchInput.current
    val reportFocused = LocalSettingsReportFocused.current
    var focused by remember { mutableStateOf(false) }
    val advance = rememberControllerRowRegistration(
        prefix = "wizardadvance",
        focusKey = focusKey,
        claimInitialFocus = false,
        selectable = true,
        onSelect = onAdvance,
    )
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(38.dp)
            .clip(CircleShape)
            .background(if (focused) menuCursorFill() else Color.White.copy(alpha = 0.16f))
            .border(2.dp, if (focused) menuCursorEdge() else Color.White.copy(alpha = 0.55f), CircleShape)
            .focusRequester(advance.focusRequester)
            .then(advance.positionReporting)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) {
                    focusTracker(onAdvance)
                    reportFocused(advance.focusRequester)
                }
            }
            .pointerInput(onAdvance) { detectTapGestures(onTap = { touchInput(); onAdvance() }) }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "▶", color = Color.White, fontSize = 14.sp)
    }
}

/** Amber transient message (validation / status). SELECT or tap dismisses; never claims focus. */
@Composable
fun WizardMessageRow(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusTracker = LocalSettingsFocusTracker.current
    val touchInput = LocalSettingsTouchInput.current
    val reportFocused = LocalSettingsReportFocused.current
    var isFocused by remember { mutableStateOf(false) }
    val row = rememberControllerRowRegistration(
        prefix = "wizardmsg",
        focusKey = null,
        claimInitialFocus = false,   // a transient status never steals the page's opening focus
        selectable = true,
        onSelect = onDismiss,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(row.focusRequester)
            .then(row.positionReporting)
            .pointerInput(row.rowKey) { detectTapGestures(onTap = { touchInput(); onDismiss() }) }
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    focusTracker(onDismiss)
                    reportFocused(row.focusRequester)
                }
            }
            .menuCursor(isFocused)
            .focusable()
            .padding(horizontal = 48.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = message, color = WizardAmber, fontSize = 13.sp)
            Text(
                text = "Tap to dismiss — or press ✕ on this row",
                color = SettingsSubtext,
                fontSize = 11.sp,
            )
        }
    }
}

/** Small uppercase group label inside a page (services sections, summary groups). */
@Composable
fun WizardSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        color = Color.White.copy(alpha = 0.72f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 10.dp),
    )
}

/** Body paragraph (welcome intro, explanations). */
@Composable
fun WizardInfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.88f),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 10.dp),
    )
}
