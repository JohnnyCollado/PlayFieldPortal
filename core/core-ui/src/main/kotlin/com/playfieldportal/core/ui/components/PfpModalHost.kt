package com.playfieldportal.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import kotlinx.coroutines.launch

// ── The shared modals, as a screen uses them ──────────────────────────────────
//
// A screen says WHAT it is asking (a spec, or null when nothing is up) and gets back a host: whether
// a modal is open, the one function that takes a controller press, and the modal to compose.
//
//     val modal = rememberPfpModalHost(
//         if (state.confirmClearVisible) PfpModalSpec.Confirm(...) else null
//     )
//     …the screen routes presses to modal.intercept while modal.open…
//     modal.Content()
//
// The cursor inside the modal, the text being typed and the scroll of a long message live in the
// host, keyed on the spec's [PfpModalSpec.key], so each opening starts fresh: on the right button,
// with the right text, scrolled to the top.
//
// Two ways a press reaches the host, matching the two ways screens get input:
//  • a screen that handles actions in composition (the settings scaffold's interceptor) calls
//    [PfpModalHost.intercept] directly;
//  • a screen driven by a view model forwards the action through its UI state and passes it to
//    [PfpModalHost.Content] as `forwardedAction`, the same hand-off the custom icon overlay uses.

/** What a screen wants to ask. One is up at a time. */
sealed interface PfpModalSpec {
    /** Identity of this prompt. A different key is a different opening, and starts fresh. */
    val key: Any

    /**
     * Title, message and Cancel / Confirm. A [destructive] one draws its confirm in red and opens
     * on Cancel; [openOnCancel] alone is for a risky step that is not a deletion.
     */
    class Confirm(
        override val key: Any,
        val title: String,
        val message: String,
        val confirmLabel: String,
        val cancelLabel: String = "Cancel",
        val destructive: Boolean = false,
        val openOnCancel: Boolean = destructive,
        val onConfirm: () -> Unit,
        val onCancel: () -> Unit,
    ) : PfpModalSpec

    /** Title, message and one button. */
    class Notice(
        override val key: Any,
        val title: String,
        val message: String,
        val buttonLabel: String = "OK",
        val onDismiss: () -> Unit,
    ) : PfpModalSpec

    /**
     * One line of text. [onConfirm] receives it trimmed. It is never blank unless [allowBlank],
     * which is for fields where clearing the text means "back to the default".
     */
    class TextEntry(
        override val key: Any,
        val title: String,
        val initial: String = "",
        val placeholder: String = "",
        val confirmLabel: String = "Save",
        val allowBlank: Boolean = false,
        val onConfirm: (String) -> Unit,
        val onCancel: () -> Unit,
    ) : PfpModalSpec
}

/** What [rememberPfpModalHost] hands back for the screen to plug in. */
class PfpModalHost internal constructor(
    /** True while a modal is up: the screen must route every press to [intercept]. */
    val open: Boolean,
    /** Takes one controller press. Returns true when a modal is up, i.e. the press is consumed. */
    val intercept: (GamepadAction) -> Boolean,
    private val content: @Composable () -> Unit,
) {
    /** The modal itself. Compose it after the screen's own content, so it draws over it. */
    @Composable
    fun Content() = content()

    /**
     * [Content] for a screen whose presses arrive through a view model: [forwardedAction] is the
     * press the view model parked in its UI state, and [onActionConsumed] clears it once handled.
     */
    @Composable
    fun Content(forwardedAction: GamepadAction?, onActionConsumed: () -> Unit) {
        val currentIntercept by rememberUpdatedState(intercept)
        val currentConsumed by rememberUpdatedState(onActionConsumed)
        LaunchedEffect(forwardedAction) {
            if (forwardedAction != null) {
                currentIntercept(forwardedAction)
                currentConsumed()
            }
        }
        content()
    }
}

@Composable
fun rememberPfpModalHost(spec: PfpModalSpec?, showHints: Boolean = true): PfpModalHost {
    val menuSounds = LocalMenuSounds.current
    val scope = rememberCoroutineScope()
    val scrollStepPx = with(LocalDensity.current) { 96.dp.toPx() }

    val key = spec?.key
    var focus by remember(key) {
        mutableStateOf(
            when (spec) {
                is PfpModalSpec.TextEntry -> PfpModalFocus.FIELD
                is PfpModalSpec.Confirm -> PfpModalNav.initialConfirmFocus(spec.openOnCancel)
                else -> PfpModalFocus.CONFIRM
            },
        )
    }
    var text by remember(key) { mutableStateOf((spec as? PfpModalSpec.TextEntry)?.initial.orEmpty()) }
    val messageScroll = remember(key) { ScrollState(0) }

    // Up and down have nothing to move to in a confirm or a notice, so they scroll a long message.
    fun scrollMessage(action: GamepadAction): Boolean {
        val delta = when (action) {
            GamepadAction.NAVIGATE_UP -> -scrollStepPx
            GamepadAction.NAVIGATE_DOWN -> scrollStepPx
            else -> return false
        }
        scope.launch { messageScroll.animateScrollBy(delta) }
        return true
    }

    val intercept: (GamepadAction) -> Boolean = { action ->
        when (spec) {
            null -> false
            is PfpModalSpec.Confirm -> {
                if (!scrollMessage(action)) {
                    PfpModalNav.handle(
                        action = action,
                        focus = focus,
                        hasField = false,
                        confirmEnabled = true,
                        sounds = menuSounds,
                        onFocusChange = { focus = it },
                        onConfirm = spec.onConfirm,
                        onCancel = spec.onCancel,
                    )
                }
                true
            }
            is PfpModalSpec.Notice -> {
                if (!scrollMessage(action)) PfpModalNav.handleNotice(action, menuSounds, spec.onDismiss)
                true
            }
            is PfpModalSpec.TextEntry -> {
                PfpModalNav.handle(
                    action = action,
                    focus = focus,
                    hasField = true,
                    confirmEnabled = PfpModalNav.textEntryConfirmEnabled(
                        text, error = null, maxLength = null, allowBlank = spec.allowBlank,
                    ),
                    sounds = menuSounds,
                    onFocusChange = { focus = it },
                    onConfirm = { spec.onConfirm(text.trim()) },
                    onCancel = spec.onCancel,
                )
                true
            }
        }
    }

    return PfpModalHost(open = spec != null, intercept = intercept) {
        when (spec) {
            null -> Unit
            is PfpModalSpec.Confirm -> PfpConfirmModal(
                title = spec.title,
                message = spec.message,
                confirmLabel = spec.confirmLabel,
                cancelLabel = spec.cancelLabel,
                focus = focus,
                destructive = spec.destructive,
                onConfirm = spec.onConfirm,
                onCancel = spec.onCancel,
                showHints = showHints,
                messageScroll = messageScroll,
            )
            is PfpModalSpec.Notice -> PfpNoticeModal(
                title = spec.title,
                message = spec.message,
                buttonLabel = spec.buttonLabel,
                onDismiss = spec.onDismiss,
                showHints = showHints,
                messageScroll = messageScroll,
            )
            is PfpModalSpec.TextEntry -> PfpTextEntryModal(
                title = spec.title,
                value = text,
                onValueChange = { text = it },
                focus = focus,
                onFocusChange = { focus = it },
                onConfirm = { spec.onConfirm(text.trim()) },
                onCancel = spec.onCancel,
                placeholder = spec.placeholder,
                confirmLabel = spec.confirmLabel,
                allowBlank = spec.allowBlank,
                showHints = showHints,
            )
        }
    }
}
