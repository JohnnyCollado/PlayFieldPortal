package com.playfieldportal.core.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * One text field's wiring to PFP's keyboard: the field's value with its caret, the open session,
 * and the hand-over to the system keyboard. Created by [rememberVirtualKeyboardEdit]; the field
 * edits [fieldValue], wraps itself in [VirtualKeyboardTextInput] and carries
 * [virtualKeyboardField], and the host calls [start] where it used to raise the system keyboard.
 */
@Stable
class VirtualKeyboardEdit internal constructor(
    private val controller: VirtualKeyboardController?,
    private val placement: KeyboardPlacement,
    initialText: String,
) {
    var fieldValue by mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
        private set

    internal var token by mutableStateOf<Long?>(null)
    // After PFP's keyboard closes (Done, BACK, stop) the field can still hold focus for a moment —
    // or for good, when the host moves its cursor elsewhere without blurring it. Lifting the hold
    // then would let the field's own request raise the system keyboard, so the hold outlives the
    // session until the field loses focus or a tap hands over.
    private var holdAfterSession by mutableStateOf(false)
    // A finger came down on the idle field. The host learns of it only through focus and then calls
    // start() with no source, by when the shell may still believe the controller was last — so the
    // tap is remembered here and spent by that start, which must leave the edit to the system one.
    private var tappedIdleField = false
    internal var anchor: KeyboardAnchor? = null
    internal var handOverRequests by mutableStateOf(0)

    // Kept current by rememberVirtualKeyboardEdit so the session's callbacks reach the latest host.
    internal var text: String = initialText
    internal var isPassword: Boolean = false
    internal var maxLength: Int? = null
    internal var onTextChange: (String) -> Unit = {}
    internal var onDone: () -> Unit = {}
    internal var onClose: () -> Unit = {}

    /** True while PFP's keyboard types into this field. */
    val isOpen: Boolean get() = token != null

    /** True while the field's own system-keyboard request must be held back. */
    val holdsSystemKeyboard: Boolean get() = token != null || holdAfterSession

    /**
     * Starts an edit from [source] (default: the last input the shell saw). Returns true when PFP's
     * keyboard took it — the caller then skips `keyboard.show()`; false means the system keyboard.
     * With no [source], a tap on the field since the last start counts as touch.
     */
    fun start(source: InputSource? = null): Boolean {
        val tapped = tappedIdleField
        tappedIdleField = false
        val keyboard = controller ?: return false
        val mode = when {
            source != null -> keyboard.modeFor(source)
            tapped -> keyboard.modeFor(InputSource.TOUCH)
            else -> keyboard.modeFor()
        }
        if (mode != TextInputMode.VIRTUAL) return false
        fieldValue = fieldValue.copy(selection = TextRange(text.length))
        holdAfterSession = false
        token = keyboard.open(
            VirtualKeyboardRequest(
                text = text,
                placement = placement,
                isPassword = isPassword,
                maxLength = maxLength,
                anchor = anchor,
                onTextChange = { value, caret ->
                    fieldValue = TextFieldValue(value, TextRange(caret))
                    onTextChange(value)
                },
                onCaretChange = { caret -> fieldValue = fieldValue.copy(selection = TextRange(caret)) },
                onDone = { endSession(); onDone() },
                onClose = { endSession(); onClose() },
            ),
        )
        return true
    }

    /** The edit ended by other means (focus left, the screen closed it): drop the session quietly. */
    fun stop() {
        val open = token ?: return
        controller?.release(open)
        endSession()
    }

    private fun endSession() {
        token = null
        holdAfterSession = true
    }

    /** The field lost focus: nothing left to hold. */
    internal fun onFieldBlurred() {
        holdAfterSession = false
        tappedIdleField = false
    }

    /** The field's own onValueChange — the system keyboard, a paste, a tap on the caret. */
    fun onFieldValueChange(next: TextFieldValue) {
        fieldValue = next
        if (next.text != text) onTextChange(next.text)
    }

    internal fun syncText(value: String) {
        text = value
        if (fieldValue.text == value) return
        fieldValue = fieldValue.copy(text = value, selection = TextRange(fieldValue.selection.end.coerceAtMost(value.length)))
        token?.let { controller?.updateText(it, value) }
    }

    /** A tap: the system keyboard takes over, whether PFP's is open or has just closed. */
    internal fun handOver() {
        val open = token
        if (open == null && !holdAfterSession) {
            tappedIdleField = true
            return
        }
        open?.let { controller?.handOverToSystemKeyboard(it) }
        token = null
        holdAfterSession = false
        handOverRequests++
    }
}

/**
 * Remembers a field's [VirtualKeyboardEdit]. [text] / [onTextChange] are the host's value, exactly
 * as it would pass them to a String text field; [onDone] runs on the keyboard's Done, [onClose] on
 * its BACK. Leaving composition releases an open session without either.
 */
@Composable
fun rememberVirtualKeyboardEdit(
    text: String,
    onTextChange: (String) -> Unit,
    placement: KeyboardPlacement,
    isPassword: Boolean = false,
    maxLength: Int? = null,
    onDone: () -> Unit = {},
    onClose: () -> Unit = {},
): VirtualKeyboardEdit {
    val controller = LocalVirtualKeyboard.current
    val edit = remember(controller, placement) { VirtualKeyboardEdit(controller, placement, text) }
    val latestTextChange by rememberUpdatedState(onTextChange)
    val latestDone by rememberUpdatedState(onDone)
    val latestClose by rememberUpdatedState(onClose)
    edit.isPassword = isPassword
    edit.maxLength = maxLength
    edit.onTextChange = { latestTextChange(it) }
    edit.onDone = { latestDone() }
    edit.onClose = { latestClose() }
    edit.syncText(text)

    // A tap mid-edit: the held input request goes through, but nothing raises the system keyboard
    // on its own (Virtual Keyboard plan T4), so it is asked for a frame later.
    val systemKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(edit.handOverRequests) {
        if (edit.handOverRequests > 0) {
            withFrameNanos { }
            systemKeyboard?.show()
        }
    }
    DisposableEffect(edit) { onDispose { edit.stop() } }
    return edit
}

/** Holds the system keyboard back while PFP's keyboard types into [edit]'s field, and after. */
@Composable
fun VirtualKeyboardTextInput(edit: VirtualKeyboardEdit, content: @Composable () -> Unit) {
    SuppressPlatformKeyboard(active = edit.holdsSystemKeyboard, content = content)
}

/**
 * For the field (or its decoration): records where it sits, for a [KeyboardPlacement.BELOW_FIELD]
 * panel, and hands the edit to the system keyboard on a tap. The tap is observed on the Initial
 * pass and never consumed, so the field still places its caret where the finger lands.
 */
fun Modifier.virtualKeyboardField(edit: VirtualKeyboardEdit): Modifier = this
    .onFocusChanged { if (!it.hasFocus) edit.onFieldBlurred() }
    .onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        edit.anchor = KeyboardAnchor(right = bounds.right, bottom = bounds.bottom)
    }
    .pointerInput(edit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            edit.handOver()
        }
    }
