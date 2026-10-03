package com.playfieldportal.core.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who draws the open keyboard (Virtual Keyboard plan section 3.2). */
enum class KeyboardPlacement {
    /** Inside the settings scaffold's footer band, which grows to fit it. */
    SETTINGS_FOOTER,
    /** Under the field, right-aligned to it ([VirtualKeyboardRequest.anchor]). */
    BELOW_FIELD,
    /** Centred at the bottom of the screen — every other host. */
    BOTTOM_CENTER,
}

/** Where a [KeyboardPlacement.BELOW_FIELD] panel hangs: the field's bottom-right, in root px. */
data class KeyboardAnchor(val right: Float, val bottom: Float)

/** What a field asks for when it opens the keyboard. */
class VirtualKeyboardRequest(
    val text: String,
    val placement: KeyboardPlacement,
    val isPassword: Boolean = false,
    val maxLength: Int? = null,
    val anchor: KeyboardAnchor? = null,
    /** The text changed; [caret] is where the field's caret now belongs. */
    val onTextChange: (text: String, caret: Int) -> Unit,
    val onCaretChange: (caret: Int) -> Unit = {},
    val onDone: () -> Unit,
    /** BACK, a newer session, or the setting turned off — the host decides keep vs cancel. */
    val onClose: () -> Unit,
)

/** The open keyboard as hosts draw it. [token] identifies the field that opened it. */
data class VirtualKeyboardSession(
    val token: Long,
    val keyboard: VirtualKeyboardState,
    val placement: KeyboardPlacement,
    val isPassword: Boolean,
    val anchor: KeyboardAnchor?,
)

/**
 * The single virtual-keyboard session (Virtual Keyboard plan section 3). A field opens it; the
 * shell routes every gamepad action here first while it is open; the reducer decides what each
 * press does and this hands the effects to the field. One session at a time: opening a second
 * closes the first.
 */
@Singleton
class VirtualKeyboardController @Inject constructor() {

    private val _session = MutableStateFlow<VirtualKeyboardSession?>(null)
    val session: StateFlow<VirtualKeyboardSession?> = _session.asStateFlow()

    private var request: VirtualKeyboardRequest? = null
    private var nextToken = 1L

    /** The last input the shell saw — decides the mode for fields opened by an action. */
    @Volatile
    var inputSource: InputSource = InputSource.CONTROLLER

    /** Plays each press's cue; the shell points this at its menu sounds. */
    var soundSink: (MenuSound) -> Unit = {}

    @Volatile
    var enabled: Boolean = true
        private set

    val isOpen: Boolean get() = _session.value != null

    /** Settings ▸ Controller ▸ Virtual Keyboard, mirrored by the shell. Off closes an open session. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) closeCurrent()
    }

    fun modeFor(source: InputSource = inputSource): TextInputMode = resolveTextInputMode(source, enabled)

    /** Opens the keyboard for a field and returns the token it closes or releases it with. */
    fun open(request: VirtualKeyboardRequest): Long {
        closeCurrent()
        val token = nextToken++
        this.request = request
        _session.value = VirtualKeyboardSession(
            token = token,
            keyboard = VirtualKeyboardReducer.open(request.text, request.maxLength),
            placement = request.placement,
            isPassword = request.isPassword,
            anchor = request.anchor,
        )
        return token
    }

    /** True when the keyboard owned the press — every press while open, so nothing leaks behind. */
    fun onGamepadAction(action: GamepadAction): Boolean {
        val current = _session.value ?: return false
        val host = request ?: return false
        val reduction = VirtualKeyboardReducer.reduce(current.keyboard, action)
        _session.value = current.copy(keyboard = reduction.state)
        reduction.effects.forEach { effect ->
            when (effect) {
                is KeyboardEffect.Edit -> host.onTextChange(effect.text, effect.caret)
                is KeyboardEffect.CaretMoved -> host.onCaretChange(effect.caret)
                is KeyboardEffect.Sound -> soundSink(effect.sound)
                KeyboardEffect.Done -> { clear(); host.onDone() }
                KeyboardEffect.Close -> { clear(); host.onClose() }
            }
        }
        return true
    }

    /** The field changed its text itself (e.g. a filter normalised it): follow it. */
    fun updateText(token: Long, text: String) {
        val current = _session.value?.takeIf { it.token == token } ?: return
        if (current.keyboard.buffer.text == text) return
        _session.value = current.copy(keyboard = VirtualKeyboardReducer.replaceText(current.keyboard, text))
    }

    /** A tap mid-edit: the system keyboard takes over. The field stays in its edit. */
    fun handOverToSystemKeyboard(token: Long) {
        if (_session.value?.token == token) clear()
    }

    /** The field left composition or ended its edit itself: drop its session quietly. */
    fun release(token: Long) {
        if (_session.value?.token == token) clear()
    }

    private fun closeCurrent() {
        val host = request ?: return
        if (_session.value == null) return
        clear()
        host.onClose()
    }

    private fun clear() {
        _session.value = null
        request = null
    }
}

/** The app's keyboard controller; null where none is provided (previews, isolated tests). */
val LocalVirtualKeyboard = staticCompositionLocalOf<VirtualKeyboardController?> { null }

/**
 * True while the shell's overlay draws PFP's keyboard (every placement but the settings footer).
 * A screen hides its own bottom prompt bar then: the keyboard owns every button and brings its own
 * prompts, and two bars stacked read as one garbled one.
 */
@Composable
fun isVirtualKeyboardOverlayOpen(): Boolean {
    val session = LocalVirtualKeyboard.current?.session?.collectAsState()?.value ?: return false
    return session.placement != KeyboardPlacement.SETTINGS_FOOTER
}
