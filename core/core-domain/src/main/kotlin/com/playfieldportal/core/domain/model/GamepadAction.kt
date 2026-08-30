package com.playfieldportal.core.domain.model

enum class GamepadAction {
    NAVIGATE_UP,
    NAVIGATE_DOWN,
    NAVIGATE_LEFT,
    NAVIGATE_RIGHT,
    SELECT,
    BACK,
    BUTTON_Y,
    LONG_PRESS,
    PREV_CATEGORY,
    NEXT_CATEGORY,
    HOME,
    OPEN_TASK_TRAY,
    CHANGE_SORT,
}

/** True for the four directional navigation actions (D-pad / stick movement). */
val GamepadAction.isDirectional: Boolean
    get() = this == GamepadAction.NAVIGATE_UP ||
        this == GamepadAction.NAVIGATE_DOWN ||
        this == GamepadAction.NAVIGATE_LEFT ||
        this == GamepadAction.NAVIGATE_RIGHT
