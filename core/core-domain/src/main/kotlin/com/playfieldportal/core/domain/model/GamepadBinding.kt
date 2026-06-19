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
}

val DEFAULT_BINDINGS = listOf(
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_A,      GamepadAction.SELECT),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_B,      GamepadAction.BACK),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_X,      GamepadAction.OPEN_TASK_TRAY),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_Y,      GamepadAction.LONG_PRESS),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_UP,       GamepadAction.NAVIGATE_UP),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_DOWN,     GamepadAction.NAVIGATE_DOWN),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_LEFT,     GamepadAction.NAVIGATE_LEFT),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_RIGHT,    GamepadAction.NAVIGATE_RIGHT),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_L1,     GamepadAction.PREV_CATEGORY),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_R1,     GamepadAction.NEXT_CATEGORY),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_START,  GamepadAction.HOME),
    GamepadBinding(KeyEvent.KEYCODE_BUTTON_SELECT, GamepadAction.OPEN_TASK_TRAY),
    GamepadBinding(KeyEvent.KEYCODE_ENTER,         GamepadAction.SELECT),
    GamepadBinding(KeyEvent.KEYCODE_BACK,          GamepadAction.BACK),
    GamepadBinding(KeyEvent.KEYCODE_DPAD_CENTER,   GamepadAction.SELECT),
)

fun GamepadAction.displayLabel(): String = when (this) {
    GamepadAction.NAVIGATE_UP     -> "Navigate Up"
    GamepadAction.NAVIGATE_DOWN   -> "Navigate Down"
    GamepadAction.NAVIGATE_LEFT   -> "Navigate Left (Previous Category)"
    GamepadAction.NAVIGATE_RIGHT  -> "Navigate Right (Next Category)"
    GamepadAction.SELECT          -> "Select / Launch"
    GamepadAction.BACK            -> "Back / Close"
    GamepadAction.LONG_PRESS      -> "Options / Long Press"
    GamepadAction.PREV_CATEGORY   -> "Previous Category (L1)"
    GamepadAction.NEXT_CATEGORY   -> "Next Category (R1)"
    GamepadAction.HOME            -> "Home / Boot Screen"
    GamepadAction.OPEN_TASK_TRAY  -> "Open Task Tray"
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
