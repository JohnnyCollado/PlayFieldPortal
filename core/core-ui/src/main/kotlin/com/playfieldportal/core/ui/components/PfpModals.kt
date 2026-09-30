package com.playfieldportal.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink

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

private val ModalScrim = Color(0xCC000000)
private val ModalSurface = Color(0xFF15151F)
private val ModalSubtext = Color.White.copy(alpha = 0.7f)
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
) {
    val confirmEnabled = PfpModalNav.textEntryConfirmEnabled(value, error, maxLength, allowBlank)
    val fieldFocused = focus == PfpModalFocus.FIELD
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val currentFocus by rememberUpdatedState(focus)

    // The host's focus is the truth: taking the field raises the keyboard, leaving it drops it.
    LaunchedEffect(fieldFocused) {
        if (fieldFocused) focusRequester.requestFocus() else focusManager.clearFocus()
    }

    PfpModalScaffold(
        title = title,
        onCancel = onCancel,
        showHints = showHints && !fieldFocused,
        modifier = modifier,
    ) { cancel ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = ModalSubtext, fontSize = 13.sp)
            BasicTextField(
                value = value,
                onValueChange = { typed -> onValueChange(if (maxLength != null) typed.take(maxLength) else typed) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (confirmEnabled) onConfirm() }),
                modifier = Modifier
                    .testTag(PfpModalTags.FIELD)
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    // A tap lands here before the host knows about it; tell it, so its cursor follows.
                    .onFocusChanged { state ->
                        if (state.isFocused && currentFocus != PfpModalFocus.FIELD) onFocusChange(PfpModalFocus.FIELD)
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
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
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, color = Color.White.copy(alpha = 0.45f), fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                },
            )
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
                            color = ModalSubtext,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag(PfpModalTags.COUNTER),
                        )
                    }
                }
            }
        }
        ModalButtonRow(
            cancelLabel = cancelLabel,
            confirmLabel = confirmLabel,
            focus = focus,
            confirmEnabled = confirmEnabled,
            destructive = false,
            accent = accent,
            onConfirm = onConfirm,
            onCancel = cancel,
        )
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
    content: @Composable ColumnScope.(cancel: () -> Unit) -> Unit,
) {
    val menuSounds = LocalMenuSounds.current
    val cancel: () -> Unit = { menuSounds.play(MenuSound.BACK); onCancel() }
    val currentCancel by rememberUpdatedState(cancel)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ModalScrim)
            .testTag(PfpModalTags.SCRIM)
            // Pointer input rather than clickable: a clickable merges its descendants' semantics, which
            // would fold the whole modal into one node for accessibility and for tests.
            .pointerInput(Unit) { detectTapGestures { currentCancel() } }
            // Centres the card in what the keyboard leaves, so the field is never under it.
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .testTag(PfpModalTags.CARD)
                    // Shrinks to the screen rather than running off it; a long message then scrolls
                    // inside the card (see ModalMessage) while the title and buttons stay put.
                    .weight(1f, fill = false)
                    .width(ModalCardWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ModalSurface)
                    // Swallows taps so a click inside the card does not reach the scrim's cancel.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        title,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 2,
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.30f)))
                }
                content(cancel)
            }
            if (showHints) {
                ControllerHintBar(items = hintItems, modifier = Modifier.testTag(PfpModalTags.HINTS))
            }
        }
    }
}

// The body text of a confirm or notice. It takes what height the card has left and scrolls within
// it, so a four-paragraph warning fits a 468dp-tall screen without pushing the buttons off it.
@Composable
private fun ColumnScope.ModalMessage(message: String, scroll: ScrollState) {
    Text(
        message,
        color = ModalSubtext,
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
) {
    val menuSounds = LocalMenuSounds.current
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                !enabled -> Color.White.copy(alpha = 0.3f)
                destructive -> ModalDanger
                showCursor -> Color.White
                else -> ModalSubtext
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
            title = "New Collection",
            value = "Survival Horror",
            onValueChange = {},
            focus = PfpModalFocus.FIELD,
            onFocusChange = {},
            onConfirm = {},
            onCancel = {},
            placeholder = "Collection name",
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
            title = "Delete Collection",
            message = "\"Survival Horror\" will be deleted. The games in it stay in your library.",
            confirmLabel = "Delete",
            focus = PfpModalFocus.CANCEL,
            destructive = true,
            onConfirm = {},
            onCancel = {},
        )
    }
}
