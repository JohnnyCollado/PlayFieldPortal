package com.playfieldportal.core.domain.model

import android.view.KeyEvent
import kotlinx.serialization.Serializable

@Serializable
data class GamepadBinding(
    val keyCode: Int,
    val action: GamepadAction,
)

@Serializable
data class GamepadMappings(
    val bindings: List<GamepadBinding> = DEFAULT_BINDINGS,
) {
    fun actionFor(keyCode: Int): GamepadAction? =
        bindings.firstOrNull { it.keyCode == keyCode }?.action

    /**
     * The physical button that currently performs [action], for UI prompts.
     *
     * The inverse of [actionFor], and the reason footers cannot drift from the
     * pad: chrome asks for the action it names, not for a fixed position.
     *
     * Keycodes with no position on a gamepad — Enter, hardware Back, D-pad
     * centre — are skipped. They are legitimate ways to drive the UI but not
     * buttons a prompt can point at, and several share an action with a real
     * face button (SELECT is bound three times), so taking the first binding
     * blindly would show a D-pad glyph for "Launch".
     */
    fun iconFor(action: GamepadAction): ControllerIcon? =
        bindings.asSequence()
            .filter { it.action == action }
            .mapNotNull { it.keyCode.toControllerIcon() }
            .firstOrNull()
}

val DEFAULT_BINDINGS = listOf(
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_A,      GamepadAction.SELECT),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_B,      GamepadAction.BACK),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_X,      GamepadAction.CHANGE_SORT),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_Y,      GamepadAction.OPEN_CONTEXT_MENU),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_UP,       GamepadAction.NAVIGATE_UP),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_DOWN,     GamepadAction.NAVIGATE_DOWN),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_LEFT,     GamepadAction.NAVIGATE_LEFT),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_RIGHT,    GamepadAction.NAVIGATE_RIGHT),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_L1,     GamepadAction.PREV_CATEGORY),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_R1,     GamepadAction.NEXT_CATEGORY),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_START,  GamepadAction.HOME),
    // The task tray was removed; Select keeps its repurposed sort role.
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_SELECT, GamepadAction.CHANGE_SORT),
    GamepadBinding(KeyEvent.KEYCODE_ENTER,         GamepadAction.SELECT),
    GamepadBinding(KeyEvent.KEYCODE_BACK,          GamepadAction.BACK),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_CENTER,   GamepadAction.SELECT),
)

/**
 * The full binding table for a pair of layout preferences.
 *
 * Rebuilt from [DEFAULT_BINDINGS] rather than mutated in place, so toggling a
 * setting back and forth always lands on a clean state and every non-face
 * binding survives — including the Enter / D-pad-centre / hardware-Back aliases
 * an earlier per-action remap path used to strip.
 *
 * `gamepadMappingsFor(STANDARD, STANDARD)` is by construction equal to the
 * defaults; a divergence there once meant a button changed meaning the first
 * time the user opened controller settings.
 */
fun gamepadMappingsFor(
    confirmBack: ConfirmBackLayout,
    xy: XYLayout,
): GamepadMappings {
    val confirmKey = when (confirmBack) {
        ConfirmBackLayout.STANDARD -> KeyEvent.KEYCODE_BUTTON_A
        ConfirmBackLayout.REVERSED -> KeyEvent.KEYCODE_BUTTON_B
    }
    val contextMenuKey = when (xy) {
        XYLayout.STANDARD -> KeyEvent.KEYCODE_BUTTON_Y
        XYLayout.SWAPPED -> KeyEvent.KEYCODE_BUTTON_X
    }
    return GamepadMappings(
        DEFAULT_BINDINGS.map { binding ->
            when (binding.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B -> GamepadBinding(
                    binding.keyCode,
                    if (binding.keyCode == confirmKey) GamepadAction.SELECT else GamepadAction.BACK,
                )
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> GamepadBinding(
                    binding.keyCode,
                    if (binding.keyCode == contextMenuKey) {
                        GamepadAction.OPEN_CONTEXT_MENU
                    } else {
                        GamepadAction.CHANGE_SORT
                    },
                )
                else -> binding
            }
        },
    )
}

fun GamepadAction.displayLabel(): String = when (this) {
    GamepadAction.NAVIGATE_UP       -> "Navigate Up"
    GamepadAction.NAVIGATE_DOWN     -> "Navigate Down"
    GamepadAction.NAVIGATE_LEFT     -> "Navigate Left (Previous Category)"
    GamepadAction.NAVIGATE_RIGHT    -> "Navigate Right (Next Category)"
    GamepadAction.SELECT            -> "Select / Launch"
    GamepadAction.BACK              -> "Back / Close"
    GamepadAction.OPEN_CONTEXT_MENU -> "Options / Context Menu"
    GamepadAction.CHANGE_SORT       -> "Change Sort Order"
    GamepadAction.PREV_CATEGORY     -> "Previous Tab (App Drawer)"
    GamepadAction.NEXT_CATEGORY     -> "Next Tab (App Drawer)"
    GamepadAction.HOME              -> "Start (Confirm in pickers)"
}

fun Int.keycodeDisplayName(): String = when (this) {
    KeyEvent.KEYCODE_BUTTON_A      -> "A / Cross"
    KeyEvent.KEYCODE_BUTTON_B      -> "B / Circle"
    KeyEvent.KEYCODE_BUTTON_X      -> "X / Square"
    KeyEvent.KEYCODE_BUTTON_Y      -> "Y / Triangle"
    KeyEvent.KEYCODE_DPAD_UP       -> "D-Pad Up"
    KeyEvent.KEYCODE_DPAD_DOWN     -> "D-Pad Down"
    KeyEvent.KEYCODE_DPAD_LEFT     -> "D-Pad Left"
    KeyEvent.KEYCODE_DPAD_RIGHT    -> "D-Pad Right"
    KeyEvent.KEYCODE_DPAD_CENTER   -> "D-Pad Center"
    KeyEvent.KEYCODE_BUTTON_L1     -> "L1"
    KeyEvent.KEYCODE_BUTTON_R1     -> "R1"
    KeyEvent.KEYCODE_BUTTON_L2     -> "L2"
    KeyEvent.KEYCODE_BUTTON_R2     -> "R2"
    KeyEvent.KEYCODE_BUTTON_START  -> "Start"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
    KeyEvent.KEYCODE_ENTER         -> "Enter"
    KeyEvent.KEYCODE_BACK          -> "Back"
    else                           -> "Key $this"
}
