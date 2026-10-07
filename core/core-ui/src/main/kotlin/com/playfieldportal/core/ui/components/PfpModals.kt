package com.playfieldportal.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardBottomReserve
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardTextInput
import com.playfieldportal.core.ui.keyboard.rememberVirtualKeyboardEdit
import com.playfieldportal.core.ui.keyboard.virtualKeyboardField
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText

// ── The app's two shared modals ───────────────────────────────────────────────
//
// [PfpTextEntryModal] names things (collections, categories) and [PfpConfirmModal] asks before an
// action. Both are the card the HSV colour picker already uses (440dp, 0xFF15151F, 16dp corners)
// under the context menu's title-and-rule, so a modal reads as part of the XMB rather than as a
// Material dialog dropped on top of it.
//
// Both are stateless, like [HsvColorPickerDialog]: the host owns the text and the focus and drives
// them from its own `handleGamepadAction`, using [PfpModalNav] for the moves so every host steps
// through a modal the same way. Taps are handled here and reported through the same callbacks.

/** Where the cursor is inside a modal. [FIELD] exists only in the text entry modal. */
enum class PfpModalFocus { FIELD, CANCEL, CONFIRM }

/** The focus rules of the shared modals, for a host's `handleGamepadAction`. */
object PfpModalNav {

    /** A destructive confirm opens on Cancel, so a stray press never deletes anything. */
    fun initialConfirmFocus(destructive: Boolean): PfpModalFocus =
        if (destructive) PfpModalFocus.CANCEL else PfpModalFocus.CONFIRM

    /**
     * Where [action] takes the cursor from [focus]. The field sits above a Cancel / Confirm row;
     * a disabled Confirm is skipped, the row does not wrap, and left/right inside the field belong
     * to the text cursor. Anything that is not a direction leaves the focus where it is.
     */
    fun move(
        focus: PfpModalFocus,
        action: GamepadAction,
        hasField: Boolean,
        confirmEnabled: Boolean,
    ): PfpModalFocus = when (focus) {
        PfpModalFocus.FIELD -> when (action) {
            GamepadAction.NAVIGATE_DOWN -> if (confirmEnabled) PfpModalFocus.CONFIRM else PfpModalFocus.CANCEL
            else -> focus
        }
        PfpModalFocus.CANCEL -> when (action) {
            GamepadAction.NAVIGATE_RIGHT -> if (confirmEnabled) PfpModalFocus.CONFIRM else focus
            GamepadAction.NAVIGATE_UP -> if (hasField) PfpModalFocus.FIELD else focus
            else -> focus
        }
        PfpModalFocus.CONFIRM -> when (action) {
            GamepadAction.NAVIGATE_LEFT -> PfpModalFocus.CANCEL
            GamepadAction.NAVIGATE_UP -> if (hasField) PfpModalFocus.FIELD else focus
            else -> focus
        }
    }

    /**
     * A host's whole interceptor while a modal is open: one call per press. Directions move the
     * cursor ([move]); SELECT activates the focused button; BACK cancels from anywhere. SELECT in
     * the field does nothing — the keyboard is up, and its Done key saves.
     *
     * The cues are played here because a modal swallows the press before the host's own navigation
     * could voice it. A press that changes nothing (the row's edge, a disabled Confirm) is silent.
     */
    fun handle(
        action: GamepadAction,
        focus: PfpModalFocus,
        hasField: Boolean,
        confirmEnabled: Boolean,
        sounds: MenuSoundSink,
        onFocusChange: (PfpModalFocus) -> Unit,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        when (action) {
            GamepadAction.BACK -> { sounds.play(MenuSound.BACK); onCancel() }
            GamepadAction.SELECT -> when (focus) {
                PfpModalFocus.CANCEL -> { sounds.play(MenuSound.BACK); onCancel() }
                PfpModalFocus.CONFIRM -> if (confirmEnabled) { sounds.play(MenuSound.CONFIRM); onConfirm() }
                PfpModalFocus.FIELD -> Unit
            }
            else -> {
                val moved = move(focus, action, hasField, confirmEnabled)
                if (moved != focus) { sounds.play(MenuSound.SCROLL); onFocusChange(moved) }
            }
        }
    }

    /**
     * Which option of a [PfpChoiceModal] [action] selects, from [selected]. Up / down step through
     * the options without wrapping; anything else leaves the selection alone.
     */
    fun moveChoice(selected: Int, action: GamepadAction, optionCount: Int): Int = when (action) {
        GamepadAction.NAVIGATE_UP -> (selected - 1).coerceAtLeast(0)
        GamepadAction.NAVIGATE_DOWN -> (selected + 1).coerceAtMost((optionCount - 1).coerceAtLeast(0))
        else -> selected
    }

    /**
     * A host's interceptor while a [PfpChoiceModal] is open. Up / down pick the option, left /
     * right move between Cancel and Confirm, SELECT activates the focused button and BACK cancels.
     */
    fun handleChoice(
        action: GamepadAction,
        focus: PfpModalFocus,
        selected: Int,
        optionCount: Int,
        sounds: MenuSoundSink,
        onFocusChange: (PfpModalFocus) -> Unit,
        onSelectedChange: (Int) -> Unit,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (action == GamepadAction.NAVIGATE_UP || action == GamepadAction.NAVIGATE_DOWN) {
            val moved = moveChoice(selected, action, optionCount)
            if (moved != selected) { sounds.play(MenuSound.SCROLL); onSelectedChange(moved) }
            return
        }
        handle(
            action = action,
            focus = focus,
            hasField = false,
            confirmEnabled = true,
            sounds = sounds,
            onFocusChange = onFocusChange,
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
    }

    /**
     * A host's interceptor while a [PfpNoticeModal] is open. There is one button and nothing to
     * choose, so SELECT and BACK both dismiss — as a back, since nothing was committed — and every
     * other press is swallowed.
     */
    fun handleNotice(action: GamepadAction, sounds: MenuSoundSink, onDismiss: () -> Unit) {
        if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
            sounds.play(MenuSound.BACK)
            onDismiss()
        }
    }

    /** [focus], moved off Confirm when Confirm has just become unavailable. */
    fun coerce(focus: PfpModalFocus, confirmEnabled: Boolean): PfpModalFocus =
        if (focus == PfpModalFocus.CONFIRM && !confirmEnabled) PfpModalFocus.CANCEL else focus

    /**
     * Whether the text entry modal's Confirm is available for [value]. [allowBlank] is for fields
     * where clearing the text means "back to the default" rather than "no name".
     */
    fun textEntryConfirmEnabled(
        value: String,
        error: String?,
        maxLength: Int?,
        allowBlank: Boolean = false,
    ): Boolean =
        (allowBlank || value.isNotBlank()) && error == null && (maxLength == null || value.length <= maxLength)
}

/** Test tags for the parts of a modal; there is only ever one modal on screen. */
object PfpModalTags {
    const val SCRIM = "pfp_modal_scrim"
    const val CARD = "pfp_modal_card"
    const val FIELD = "pfp_modal_field"
    const val ERROR = "pfp_modal_error"
    const val COUNTER = "pfp_modal_counter"
    const val CANCEL = "pfp_modal_cancel"
    const val CONFIRM = "pfp_modal_confirm"
    const val HINTS = "pfp_modal_hints"
}

internal val ModalScrim = Color(0xCC000000)
internal val ModalSurface = Color(0xFF15151F)
// Text reads ModalSubtext and white through themedText (titles, labels, buttons) or themedSubText
// (messages, details, placeholders, counters), so the user's font colours carry the card's text at
// the same weights.
internal val ModalSubtext = Color.White.copy(alpha = 0.7f)
private val ModalDanger = Color(0xFFFF7070)
private val ModalCardWidth = 440.dp
private val ModalControlShape = RoundedCornerShape(8.dp)
private val ChoiceHintItems = listOf(
    ControllerPromptItem(GamepadAction.SELECT, "Select"),
    ControllerPromptItem(GamepadAction.BACK, "Cancel"),
)

/**
 * A modal that asks before an action: a title, a message and Cancel / Confirm.
 *
 * [focus] is the host's; open it with [PfpModalNav.initialConfirmFocus]. [destructive] draws the
 * confirm label in the destructive red the context menu uses.
 */
@Composable
fun PfpConfirmModal(
    title: String,
    message: String,
    confirmLabel: String,
    focus: PfpModalFocus,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    accent: Color = Color.White,
    showHints: Boolean = true,
    // A message too long for the screen scrolls inside the card; a host may hoist this to scroll
    // it from the controller (up / down have nothing else to do in this modal).
    messageScroll: ScrollState = rememberScrollState(),
) {
    PfpModalScaffold(title = title, onCancel = onCancel, showHints = showHints, modifier = modifier) { cancel ->
        ModalMessage(message, messageScroll)
        ModalButtonRow(
            cancelLabel = cancelLabel,
            confirmLabel = confirmLabel,
            focus = focus,
            confirmEnabled = true,
            destructive = destructive,
            accent = accent,
            onConfirm = onConfirm,
            onCancel = cancel,
        )
    }
}

/** One answer in a [PfpChoiceModal]: its label and an optional detail pinned to the row's end. */
data class PfpChoiceOption(val label: String, val detail: String? = null)

/**
 * A confirm that also asks a question: a title, a message, a short list of [options] of which
 * exactly one is [selected], and Cancel / Confirm. The selected option carries the accent ring.
 *
 * [focus] and [selected] are the host's; drive them with [PfpModalNav.handleChoice].
 */
@Composable
fun PfpChoiceModal(
    title: String,
    message: String,
    options: List<PfpChoiceOption>,
    selected: Int,
    onSelectedChange: (Int) -> Unit,
    confirmLabel: String,
    focus: PfpModalFocus,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    accent: Color = Color.White,
    showHints: Boolean = true,
    messageScroll: ScrollState = rememberScrollState(),
) {
    PfpModalScaffold(title = title, onCancel = onCancel, showHints = showHints, modifier = modifier) { cancel ->
        ModalMessage(message, messageScroll)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                ModalChoiceRow(
                    option = option,
                    selected = index == selected,
                    accent = accent,
                    onClick = { onSelectedChange(index) },
                )
            }
        }
        ModalButtonRow(
            cancelLabel = cancelLabel,
            confirmLabel = confirmLabel,
            focus = focus,
            confirmEnabled = true,
            destructive = destructive,
            accent = accent,
            onConfirm = onConfirm,
            onCancel = cancel,
        )
    }
}

// One option row. The selected row wears the same ring and fill a focused button does; the others
// sit on a faint fill so the list reads as a set of rows rather than loose text.
@Composable
private fun ModalChoiceRow(
    option: PfpChoiceOption,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(ModalControlShape)
            .background(Color.White.copy(alpha = if (selected) 0.10f else 0.05f))
            .then(if (selected) Modifier.border(2.dp, accent, ModalControlShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option.label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = themedText(if (selected) Color.White else ModalSubtext),
            modifier = Modifier.weight(1f),
        )
        option.detail?.let {
            Text(it, fontSize = 12.sp, color = themedSubText(ModalSubtext), maxLines = 1)
        }
    }
}

/**
 * A modal that only tells the user something: a title, a message and one button. The button is
 * always the cursor, and a tap outside the card dismisses too. Drive it with
 * [PfpModalNav.handleNotice].
 */
@Composable
fun PfpNoticeModal(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "OK",
    accent: Color = Color.White,
    showHints: Boolean = true,
    messageScroll: ScrollState = rememberScrollState(),
) {
    PfpModalScaffold(
        title = title,
        onCancel = onDismiss,
        showHints = showHints,
        modifier = modifier,
        hintItems = listOf(ControllerPromptItem(GamepadAction.SELECT, buttonLabel)),
    ) { dismiss ->
        ModalMessage(message, messageScroll)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ModalButton(
                label = buttonLabel,
                focused = true,
                enabled = true,
                destructive = false,
                accent = accent,
                tag = PfpModalTags.CONFIRM,
                onClick = dismiss,
            )
        }
    }
}

/** How far a [PfpTextEntryModal] with `multiline` grows before it scrolls. */
private const val MULTILINE_MAX_LINES = 6

/**
 * A modal that takes one line of text — a collection or category name.
 *
 * The host owns [value] and [focus]. While the focus is [PfpModalFocus.FIELD] the field holds the
 * keyboard; the card rides above it and the controller hints give way to it. [error] is the host's
 * verdict on the current text (a name already taken): it is shown under the field and, like an
 * empty name, keeps Confirm unavailable. With [maxLength] set, further typing is not accepted and
 * a counter is shown.
 */
@Composable
fun PfpTextEntryModal(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    focus: PfpModalFocus,
    onFocusChange: (PfpModalFocus) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Name",
    placeholder: String = "",
    confirmLabel: String = "Save",
    cancelLabel: String = "Cancel",
    error: String? = null,
    maxLength: Int? = null,
    // For a field where an empty value is a valid answer ("use the default name").
    allowBlank: Boolean = false,
    accent: Color = Color.White,
    showHints: Boolean = true,
    // A description rather than a name: wraps onto up to six lines and grows to fit them.
    multiline: Boolean = false,
    // The system keyboard opens on its number page (a year, a rating).
    numeric: Boolean = false,
) {
    val confirmEnabled = PfpModalNav.textEntryConfirmEnabled(value, error, maxLength, allowBlank)
    val fieldFocused = focus == PfpModalFocus.FIELD
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val currentFocus by rememberUpdatedState(focus)
    val currentConfirmEnabled by rememberUpdatedState(confirmEnabled)
    // PFP's keyboard, for a field the controller reached: its Done saves (or, with nothing valid
    // to save, steps to Cancel); its BACK closes only the keyboard and leaves the cursor on the
    // buttons, so a second BACK cancels the modal as it always has.
    val edit = rememberVirtualKeyboardEdit(
        text = value,
        onTextChange = onValueChange,
        placement = KeyboardPlacement.BOTTOM_CENTER,
        maxLength = maxLength,
        onDone = { if (currentConfirmEnabled) onConfirm() else onFocusChange(PfpModalFocus.CANCEL) },
        onClose = { onFocusChange(if (currentConfirmEnabled) PfpModalFocus.CONFIRM else PfpModalFocus.CANCEL) },
    )

    // The host's focus is the truth: taking the field raises a keyboard, leaving it drops it. PFP's
    // is opened first and given a frame, so the field's own keyboard request is already held when
    // focus arrives (VirtualKeyboardTextInput).
    LaunchedEffect(fieldFocused) {
        if (fieldFocused) {
            if (edit.start()) withFrameNanos { }
            focusRequester.requestFocus()
        } else {
            edit.stop()
            focusManager.clearFocus()
        }
    }

    PfpModalScaffold(
        title = title,
        onCancel = onCancel,
        showHints = showHints && !fieldFocused,
        modifier = modifier,
        keyboardReserve = if (edit.isOpen) VirtualKeyboardBottomReserve else 0.dp,
        allowCompact = true,
    ) { cancel ->
        // On a short screen under the system keyboard the buttons move up beside the field. The
        // field keeps one place in the tree either way: re-creating it would drop its focus, close
        // the keyboard and flip the layout straight back.
        val compact = LocalModalCompact.current
        val buttons: @Composable (Modifier) -> Unit = { rowModifier ->
            ModalButtonRow(
                cancelLabel = cancelLabel,
                confirmLabel = confirmLabel,
                focus = focus,
                confirmEnabled = confirmEnabled,
                destructive = false,
                accent = accent,
                onConfirm = onConfirm,
                onCancel = cancel,
                modifier = rowModifier,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!compact) Text(label, color = themedSubText(ModalSubtext), fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.weight(1f)) {
            VirtualKeyboardTextInput(edit) {
            BasicTextField(
                value = edit.fieldValue,
                onValueChange = { typed ->
                    edit.onFieldValueChange(
                        if (maxLength != null && typed.text.length > maxLength) {
                            typed.copy(text = typed.text.take(maxLength))
                        } else {
                            typed
                        },
                    )
                },
                singleLine = !multiline,
                // A description scrolls inside two lines when the strip has no room to grow.
                maxLines = if (!multiline) 1 else if (compact) COMPACT_MULTILINE_MAX_LINES else MULTILINE_MAX_LINES,
                textStyle = TextStyle(color = themedText(Color.White), fontSize = 16.sp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = if (multiline) KeyboardCapitalization.Sentences else KeyboardCapitalization.Words,
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (confirmEnabled) onConfirm() }),
                modifier = Modifier
                    .testTag(PfpModalTags.FIELD)
                    .fillMaxWidth()
                    .virtualKeyboardField(edit)
                    .focusRequester(focusRequester)
                    // A tap lands here before the host knows about it; tell it, so its cursor follows.
                    .onFocusChanged { state ->
                        if (state.isFocused && currentFocus != PfpModalFocus.FIELD) onFocusChange(PfpModalFocus.FIELD)
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (multiline) Modifier.heightIn(min = 48.dp) else Modifier.height(48.dp))
                            .clip(ModalControlShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(
                                width = if (fieldFocused && error == null) 2.dp else 1.dp,
                                color = when {
                                    error != null -> ModalDanger
                                    fieldFocused -> accent
                                    else -> Color.White.copy(alpha = 0.33f)
                                },
                                shape = ModalControlShape,
                            )
                            .padding(horizontal = 14.dp, vertical = if (multiline) 12.dp else 0.dp),
                        contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, color = themedSubText(Color.White.copy(alpha = 0.45f)), fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                },
            )
            }
            }
            if (compact) buttons(Modifier)
            }
            if (error != null || maxLength != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(1f)) {
                        if (error != null) {
                            Text(
                                error,
                                color = ModalDanger,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag(PfpModalTags.ERROR),
                            )
                        }
                    }
                    if (maxLength != null) {
                        Text(
                            "${value.length} / $maxLength",
                            color = themedSubText(ModalSubtext),
                            fontSize = 12.sp,
                            modifier = Modifier.testTag(PfpModalTags.COUNTER),
                        )
                    }
                }
            }
        }
        if (!compact) buttons(Modifier.fillMaxWidth())
    }
}

// The scrim, the card with its title and rule, and the controller hints under it. The scrim and
// the Cancel button are two ways out, so [content] is handed the one cancel lambda they share and
// they cannot end up sounding different.
@Composable
private fun PfpModalScaffold(
    title: String,
    onCancel: () -> Unit,
    showHints: Boolean,
    modifier: Modifier = Modifier,
    hintItems: List<ControllerPromptItem> = ChoiceHintItems,
    // Room kept at the bottom for PFP's keyboard, which — unlike the system one — has no IME inset.
    keyboardReserve: Dp = 0.dp,
    // The text-entry modal: below CompactImeThreshold over the system keyboard, the card becomes one
    // wide strip resting on the keyboard (see LocalModalCompact).
    allowCompact: Boolean = false,
    content: @Composable ColumnScope.(cancel: () -> Unit) -> Unit,
) {
    val menuSounds = LocalMenuSounds.current
    val cancel: () -> Unit = { menuSounds.play(MenuSound.BACK); onCancel() }
    val currentCancel by rememberUpdatedState(cancel)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ModalScrim)
            .testTag(PfpModalTags.SCRIM)
            // Pointer input rather than clickable: a clickable merges its descendants' semantics, which
            // would fold the whole modal into one node for accessibility and for tests.
            .pointerInput(Unit) { detectTapGestures { currentCancel() } },
    ) {
        // PFP draws edge-to-edge, so the system keyboard overlays the window rather than resizing
        // it; the card is placed in what the keyboard leaves, so the field is never under it.
        val systemKeyboard = systemKeyboardHeight()
        val compact = allowCompact &&
            useCompactImeLayout(systemKeyboard > 0.dp, maxHeight - systemKeyboard - keyboardReserve)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = systemKeyboard + keyboardReserve),
            contentAlignment = if (compact) Alignment.BottomCenter else Alignment.Center,
        ) {
        Column(
            modifier = Modifier.padding(vertical = if (compact) 8.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .testTag(PfpModalTags.CARD)
                    // Shrinks to the screen rather than running off it; a long message then scrolls
                    // inside the card (see ModalMessage) while the title and buttons stay put.
                    .weight(1f, fill = false)
                    .then(
                        if (compact) Modifier.fillMaxWidth(COMPACT_CARD_WIDTH_FRACTION).widthIn(max = CompactCardMaxWidth)
                        else Modifier.width(ModalCardWidth),
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(ModalSurface)
                    // Swallows taps so a click inside the card does not reach the scrim's cancel.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(if (compact) Modifier.padding(horizontal = 20.dp, vertical = 12.dp) else Modifier.padding(24.dp)),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        title,
                        color = themedText(Color.White.copy(alpha = 0.92f)),
                        fontSize = if (compact) 15.sp else 19.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = if (compact) 1 else 2,
                    )
                    if (!compact) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.30f)))
                    }
                }
                CompositionLocalProvider(LocalModalCompact provides compact) { content(cancel) }
            }
            if (showHints && !compact) {
                ControllerHintBar(items = hintItems, modifier = Modifier.testTag(PfpModalTags.HINTS))
            }
        }
        }
    }
}

/** True inside a modal laid out as its one-strip system-keyboard form. */
private val LocalModalCompact = compositionLocalOf { false }

private const val COMPACT_CARD_WIDTH_FRACTION = 0.94f
private val CompactCardMaxWidth = 820.dp
private const val COMPACT_MULTILINE_MAX_LINES = 2

// The body text of a confirm or notice. It takes what height the card has left and scrolls within
// it, so a four-paragraph warning fits a 468dp-tall screen without pushing the buttons off it.
@Composable
private fun ColumnScope.ModalMessage(message: String, scroll: ScrollState) {
    Text(
        message,
        color = themedSubText(ModalSubtext),
        fontSize = 15.sp,
        lineHeight = 22.sp,
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(scroll),
    )
}

@Composable
private fun ModalButtonRow(
    cancelLabel: String,
    confirmLabel: String,
    focus: PfpModalFocus,
    confirmEnabled: Boolean,
    destructive: Boolean,
    accent: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    // Full width under the content; wrapped beside the field in the compact strip.
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val menuSounds = LocalMenuSounds.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        ModalButton(
            label = cancelLabel,
            focused = focus == PfpModalFocus.CANCEL,
            enabled = true,
            destructive = false,
            accent = accent,
            tag = PfpModalTags.CANCEL,
            onClick = onCancel,
        )
        ModalButton(
            label = confirmLabel,
            focused = focus == PfpModalFocus.CONFIRM,
            enabled = confirmEnabled,
            destructive = destructive,
            accent = accent,
            tag = PfpModalTags.CONFIRM,
            onClick = { menuSounds.play(MenuSound.CONFIRM); onConfirm() },
        )
    }
}

// The cursor is the accent ring plus a faint fill, and the focused label goes semibold.
@Composable
private fun ModalButton(
    label: String,
    focused: Boolean,
    enabled: Boolean,
    destructive: Boolean,
    accent: Color,
    tag: String,
    onClick: () -> Unit,
) {
    val showCursor = focused && enabled
    Box(
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 40.dp)
            .widthIn(min = 120.dp)
            .clip(ModalControlShape)
            .then(
                if (showCursor) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(2.dp, accent, ModalControlShape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (showCursor) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> themedText(Color.White.copy(alpha = 0.3f))
                destructive -> ModalDanger
                showCursor -> themedText(Color.White)
                else -> themedText(ModalSubtext)
            },
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun PfpTextEntryModalPreview() {
    PfpPreview {
        PfpTextEntryModal(
            title = "New Custom Memory Card",
            value = "Survival Horror",
            onValueChange = {},
            focus = PfpModalFocus.FIELD,
            onFocusChange = {},
            onConfirm = {},
            onCancel = {},
            placeholder = "Card name",
            maxLength = 40,
        )
    }
}

@CombinedPreviews
@Composable
fun PfpTextEntryModalErrorPreview() {
    PfpPreview {
        PfpTextEntryModal(
            title = "Rename Category",
            value = "Favorites",
            onValueChange = {},
            focus = PfpModalFocus.CANCEL,
            onFocusChange = {},
            onConfirm = {},
            onCancel = {},
            error = "A category named \"Favorites\" already exists.",
            maxLength = 40,
        )
    }
}

@CombinedPreviews
@Composable
fun PfpConfirmModalPreview() {
    PfpPreview {
        PfpConfirmModal(
            title = "Remove from Library",
            message = "Parasite Eve II will be removed from your library. The file stays on your " +
                "device, and the next scan adds the game back.",
            confirmLabel = "Remove",
            focus = PfpModalFocus.CONFIRM,
            onConfirm = {},
            onCancel = {},
        )
    }
}

@CombinedPreviews
@Composable
fun PfpNoticeModalPreview() {
    PfpPreview {
        PfpNoticeModal(
            title = "Can't play video",
            message = "No installed app can open this file.",
            onDismiss = {},
        )
    }
}

@CombinedPreviews
@Composable
fun PfpConfirmModalDestructivePreview() {
    PfpPreview {
        PfpConfirmModal(
            title = "Delete Custom Card",
            message = "\"Survival Horror\" will be deleted. The games in it stay in your library.",
            confirmLabel = "Delete",
            focus = PfpModalFocus.CANCEL,
            destructive = true,
            onConfirm = {},
            onCancel = {},
        )
    }
}
