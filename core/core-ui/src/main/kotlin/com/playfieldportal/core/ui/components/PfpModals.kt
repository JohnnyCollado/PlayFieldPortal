package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.BackdropBlur
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.R
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
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.core.ui.theme.menuCursorFill
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText

// ── The app's two shared modals ───────────────────────────────────────────────
//
// [PfpTextEntryModal] names things (collections, categories) and [PfpConfirmModal] asks before an
// action. A prompt is not a box: the whole screen takes a blue-grey tint (multiplied over what is
// behind it) and the content sits in a band between two hairlines, 75% of the screen wide. Text is
// white with a soft shadow, the focused button wears the theme's menu cursor, and the button hints
// sit at the bottom of the screen. See [PfpPromptFrame].
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
    /** The band between the two hairlines. Named "card" from before prompts lost their box. */
    const val CARD = "pfp_modal_card"
    const val LINE_TOP = "pfp_modal_line_top"
    const val LINE_BOTTOM = "pfp_modal_line_bottom"
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
// (messages, details, placeholders, counters), so the user's font colours carry the band's text at
// the same weights.
internal val ModalSubtext = Color.White.copy(alpha = 0.7f)
private val ModalDanger = Color(0xFFFF7070)
private val ModalControlShape = RoundedCornerShape(4.dp)

/** The blue-grey a prompt multiplies over the screen when the theme gives nothing better. */
internal val PromptTintFallback = Color(0.6f, 0.62f, 0.7f)

/** How much of the tint a prompt applies: 85% of the way from "no change" to the tint colour. */
internal const val PromptTintStrength = 0.85f

private const val PromptBandWidthFraction = 0.75f
private val PromptHairlineColor = Color.White.copy(alpha = 0.9f)
private val PromptBandMinHeight = 96.dp
// Room kept above and below the band for the bottom hint row, so the band stays centred.
private val PromptHintReserve = 56.dp
private const val PromptFadeMs = 100

// White with a soft shadow, the look of every line of text in a prompt.
private val PromptTextStyle = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 2f), blurRadius = 4f),
)

/**
 * The colour a prompt multiplies over the screen, taken from the theme's background so a red or
 * green theme gets a matching tint. The background's hue is lightened and blended halfway into
 * the blue-grey, so it stays a gentle veil; a theme with no usable hue (grey, near-black or white)
 * gets [PromptTintFallback] itself.
 */
internal fun promptTintFor(top: Color, bottom: Color): Color {
    val mid = lerp(top, bottom, 0.5f)
    val high = maxOf(mid.red, mid.green, mid.blue)
    val low = minOf(mid.red, mid.green, mid.blue)
    if (high - low < 0.12f) return PromptTintFallback
    val veil = lerp(PromptTintFallback, lerp(mid.copy(alpha = 1f), Color.White, 0.6f), 0.5f)
    return Color(
        red = veil.red.coerceAtLeast(0.45f),
        green = veil.green.coerceAtLeast(0.45f),
        blue = veil.blue.coerceAtLeast(0.45f),
    )
}

// ── Blur behind a prompt or the options panel (Android 12+) ───────────────────
//
// A prompt and the options panel are composables drawn over the screen in the same hierarchy, so
// they cannot blur what is behind them: only the host that owns that content can. They raise a flag
// while shown instead, and the host applies one modifier to its content:
//
//     Box(Modifier.pfpBackdropBlur(blurState)) { /* the screen */ }
//     CompositionLocalProvider(LocalPfpBackdropBlur provides blurState) { /* prompts, panels */ }
//
// The blur is one static radius (never animated), and the host switches it off for Performance /
// Thermal Throttle settings by setting [PfpBackdropBlurState.enabled] to false. How strong it is
// comes from Display ▸ Background blur through [LocalPfpBackdropBlurStrength].

/**
 * The user's blur strength, 0 (off) to 1 (the ceiling, [BackdropBlur.MAX_RADIUS_DP]). Provided once
 * at the root of the app from the stored preference.
 */
val LocalPfpBackdropBlurStrength = compositionLocalOf { BackdropBlur.DEFAULT_STRENGTH }

/** The blur radius for [strength]; zero means draw no blur. */
internal fun backdropBlurRadius(strength: Float): Dp = BackdropBlur.radiusDp(strength).dp

/** Whether a prompt or panel should ask for the backdrop blur: Android 12+ and not opted out. */
internal fun backdropBlurWanted(allowBlur: Boolean, sdkInt: Int): Boolean = allowBlur && sdkInt >= 31

/** How many prompts / panels are showing and want the backdrop blurred, and whether the host allows it. */
class PfpBackdropBlurState(initiallyEnabled: Boolean = true) {
    /** The host's switch: false while Performance or Thermal settings ask for no blur. */
    var enabled: Boolean by mutableStateOf(initiallyEnabled)
    private var requests by mutableIntStateOf(0)

    /** Some prompt or panel is showing and asked for the blur. */
    val requested: Boolean get() = requests > 0

    /** The host should blur its content now. */
    val active: Boolean get() = enabled && requests > 0

    internal fun raise() { requests++ }
    internal fun lower() { if (requests > 0) requests-- }
}

/** The host's blur state. Null (the default) means nobody blurs and prompts stay a flat tint. */
val LocalPfpBackdropBlur = staticCompositionLocalOf<PfpBackdropBlurState?> { null }

/** Blurs the content it is applied to while a prompt or panel is showing, on Android 12+. */
@Composable
fun Modifier.pfpBackdropBlur(state: PfpBackdropBlurState?): Modifier {
    val radius = backdropBlurRadius(LocalPfpBackdropBlurStrength.current)
    return if (state != null && Build.VERSION.SDK_INT >= 31 && state.active && radius > 0.dp) blur(radius) else this
}

/** Raises [LocalPfpBackdropBlur] for as long as this is in the composition. */
@Composable
internal fun RaiseBackdropBlur(allowBlur: Boolean) {
    val state = LocalPfpBackdropBlur.current
    val wanted = backdropBlurWanted(allowBlur, Build.VERSION.SDK_INT)
    DisposableEffect(state, wanted) {
        if (state != null && wanted) {
            state.raise()
            onDispose { state.lower() }
        } else {
            onDispose { }
        }
    }
}

@Composable
private fun choiceHintItems() = listOf(
    ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.core_select)),
    ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.core_cancel)),
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
    cancelLabel: String = stringResource(R.string.core_cancel),
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
    cancelLabel: String = stringResource(R.string.core_cancel),
    destructive: Boolean = false,
    accent: Color = Color.White,
    showHints: Boolean = true,
    messageScroll: ScrollState = rememberScrollState(),
) {
    PfpModalScaffold(title = title, onCancel = onCancel, showHints = showHints, modifier = modifier) { cancel ->
        // The message and the options scroll together in what height the card has left. They used
        // to be a scrolling message over a fixed list, so a list as tall as the screen (six options
        // on a 468dp-tall one) squeezed the message to nothing. The selected row is kept in view by
        // its own geometry, so moving the selection scrolls the list.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(messageScroll),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = PromptTextStyle,
                    color = themedSubText(ModalSubtext),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
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

// One option row, centred in the band. The selected row wears the theme's cursor (fill and edge),
// as a focused button does; the others are bare text. Always stacked: up / down pick the option.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModalChoiceRow(
    option: PfpChoiceOption,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val intoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) intoView.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .bringIntoViewRequester(intoView)
            .modalCursor(selected, accent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option.label,
            style = PromptTextStyle,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = themedText(if (selected) Color.White else ModalSubtext),
        )
        option.detail?.let {
            Text(it, style = PromptTextStyle, fontSize = 12.sp, color = themedSubText(ModalSubtext), maxLines = 1)
        }
    }
}

// The theme's menu cursor on a prompt control. A caller's own [accent] still wins for the edge when
// it is not the default white, so a prompt asked to carry a colour keeps carrying it.
@Composable
private fun Modifier.modalCursor(on: Boolean, accent: Color): Modifier {
    val edge = if (accent == Color.White) menuCursorEdge() else accent
    if (!on) return this
    return this
        .clip(ModalControlShape)
        .background(menuCursorFill())
        .border(1.5.dp, edge, ModalControlShape)
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
    buttonLabel: String = stringResource(R.string.core_ok),
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
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
    label: String = stringResource(R.string.core_modal_name_label),
    placeholder: String = "",
    confirmLabel: String = stringResource(R.string.core_save),
    cancelLabel: String = stringResource(R.string.core_cancel),
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
    val fieldEdge = menuCursorEdge()
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
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!compact) Text(label, style = PromptTextStyle, color = themedSubText(ModalSubtext), fontSize = 13.sp)
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
                textStyle = PromptTextStyle.copy(color = themedText(Color.White), fontSize = 16.sp),
                cursorBrush = SolidColor(if (accent == Color.White) fieldEdge else accent),
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
                            .background(if (fieldFocused) menuCursorFill() else Color.White.copy(alpha = 0.06f))
                            .border(
                                width = if (fieldFocused && error == null) 1.5.dp else 1.dp,
                                color = when {
                                    error != null -> ModalDanger
                                    fieldFocused -> if (accent == Color.White) fieldEdge else accent
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
                            stringResource(R.string.core_modal_counter, value.length, maxLength),
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
    hintItems: List<ControllerPromptItem> = choiceHintItems(),
    // Room kept at the bottom for PFP's keyboard, which — unlike the system one — has no IME inset.
    keyboardReserve: Dp = 0.dp,
    // The text-entry modal: below CompactImeThreshold over the system keyboard, the card becomes one
    // wide strip resting on the keyboard (see LocalModalCompact).
    allowCompact: Boolean = false,
    content: @Composable ColumnScope.(cancel: () -> Unit) -> Unit,
) {
    val menuSounds = LocalMenuSounds.current
    val cancel: () -> Unit = { menuSounds.play(MenuSound.BACK); onCancel() }
    PfpPromptFrame(
        onDismiss = cancel,
        hints = hintItems,
        showHints = showHints,
        modifier = modifier,
        keyboardReserve = keyboardReserve,
        allowCompact = allowCompact,
    ) { compact ->
        Text(
            title,
            style = PromptTextStyle,
            color = themedText(Color.White.copy(alpha = 0.92f)),
            fontSize = if (compact) 15.sp else 19.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
        )
        content(cancel)
    }
}

/**
 * The frame every prompt is drawn in: a tint multiplied over the whole screen, and a band between
 * two 1dp hairlines, [PromptBandWidthFraction] of the screen wide and centred. The band's height
 * follows [content] (with a minimum) and what does not fit scrolls inside it, between the lines.
 * [hints] are drawn centred at the bottom of the screen when [showHints] (controller mode); touch
 * hosts pass false and rely on the tappable buttons in the band. A tap on the tint calls
 * [onDismiss]; a tap in the band does not.
 *
 * Reduced motion (the system's animation scale at zero) shows the prompt at once; otherwise it fades
 * in over 100ms. The tint's fade is done by easing its colour toward white, so the multiply
 * blend never has to go through an offscreen layer.
 *
 * Internal so the detail sheets can adopt it ([bandTag] keeps their own test tags).
 */
@Composable
internal fun PfpPromptFrame(
    onDismiss: () -> Unit,
    hints: List<ControllerPromptItem>,
    showHints: Boolean,
    modifier: Modifier = Modifier,
    // Room kept at the bottom for PFP's keyboard, which — unlike the system one — has no IME inset.
    keyboardReserve: Dp = 0.dp,
    // The text-entry modal: below CompactImeThreshold over the system keyboard, the band becomes one
    // wide strip resting on the keyboard (see LocalModalCompact).
    allowCompact: Boolean = false,
    bandTag: String = PfpModalTags.CARD,
    // Ask the host to blur what is behind the tint (Android 12+; see LocalPfpBackdropBlur). False
    // keeps today's flat tint.
    allowBlur: Boolean = true,
    content: @Composable ColumnScope.(compact: Boolean) -> Unit,
) {
    RaiseBackdropBlur(allowBlur)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val colors = LocalPFPColors.current
    val tint = remember(colors.backgroundTop, colors.backgroundBottom) {
        promptTintFor(colors.backgroundTop, colors.backgroundBottom)
    }
    val fade = rememberPromptFade()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(PfpModalTags.SCRIM)
            .drawBehind {
                drawRect(
                    color = lerp(Color.White, tint, PromptTintStrength * fade.value),
                    blendMode = BlendMode.Multiply,
                )
            }
            // Pointer input rather than clickable: a clickable merges its descendants' semantics, which
            // would fold the whole modal into one node for accessibility and for tests.
            .pointerInput(Unit) { detectTapGestures { currentDismiss() } },
    ) {
        // PFP draws edge-to-edge, so the system keyboard overlays the window rather than resizing
        // it; the band is placed in what the keyboard leaves, so the field is never under it.
        val systemKeyboard = systemKeyboardHeight()
        val compact = allowCompact &&
            useCompactImeLayout(systemKeyboard > 0.dp, maxHeight - systemKeyboard - keyboardReserve)
        val hintsShown = showHints && !compact && hints.isNotEmpty()
        val reserve = if (hintsShown) PromptHintReserve else 0.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = reserve, bottom = reserve + systemKeyboard + keyboardReserve),
            contentAlignment = if (compact) Alignment.BottomCenter else Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .testTag(bandTag)
                    .then(
                        if (compact) Modifier.fillMaxWidth(COMPACT_BAND_WIDTH_FRACTION).widthIn(max = CompactBandMaxWidth)
                        else Modifier.fillMaxWidth(PromptBandWidthFraction),
                    )
                    .graphicsLayer { alpha = fade.value }
                    // Swallows taps so a press inside the band does not reach the tint's dismiss.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                PromptHairline(PfpModalTags.LINE_TOP)
                Column(
                    // Shrinks to the screen rather than running off it; a long message then scrolls
                    // inside the band (see ModalMessage) while the title and buttons stay put.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .heightIn(min = if (compact) 0.dp else PromptBandMinHeight)
                        .padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp, Alignment.CenterVertically),
                ) {
                    CompositionLocalProvider(LocalModalCompact provides compact) { content(compact) }
                }
                PromptHairline(PfpModalTags.LINE_BOTTOM)
            }
            if (hintsShown) {
                ControllerPromptBar(
                    items = hints,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .graphicsLayer { alpha = fade.value }
                        .testTag(PfpModalTags.HINTS),
                    labelColor = Color.White,
                    labelStyle = PromptTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    glyphSize = 20.dp,
                    arrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                )
            }
        }
    }
}

// The prompt's fade-in progress, 0 to 1. Instant when the system's animation scale is zero.
@Composable
private fun rememberPromptFade(): State<Float> {
    val context = LocalContext.current
    val reduced = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) { if (!reduced) progress.animateTo(1f, tween(PromptFadeMs)) }
    return progress.asState()
}

@Composable
private fun PromptHairline(tag: String) {
    Box(
        Modifier
            .testTag(tag)
            .fillMaxWidth()
            .height(1.dp)
            .background(PromptHairlineColor),
    )
}

/** True inside a modal laid out as its one-strip system-keyboard form. */
private val LocalModalCompact = compositionLocalOf { false }

private const val COMPACT_BAND_WIDTH_FRACTION = 0.94f
private val CompactBandMaxWidth = 820.dp
private const val COMPACT_MULTILINE_MAX_LINES = 2

// The body text of a confirm or notice, centred. It takes what height the band has left and scrolls
// within it, so a four-paragraph warning fits a 468dp-tall screen without pushing the buttons off it.
@Composable
private fun ColumnScope.ModalMessage(message: String, scroll: ScrollState) {
    Text(
        message,
        style = PromptTextStyle,
        color = themedSubText(ModalSubtext),
        fontSize = 15.sp,
        lineHeight = 22.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
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
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
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

// The cursor is the theme's menu cursor (fill and edge), and the focused label goes semibold.
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
            .modalCursor(showCursor, accent)
            .clip(ModalControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = PromptTextStyle,
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
