package com.playfieldportal.feature.xmb.gamepad

// Abstract XMB actions — decoupled from physical keycodes.
// The mapping from keycode → GamepadAction lives in ControllerMappingRepository.
enum class GamepadAction {
    NAVIGATE_UP,        // Move selection up in item list
    NAVIGATE_DOWN,      // Move selection down in item list
    NAVIGATE_LEFT,      // Move to previous category
    NAVIGATE_RIGHT,     // Move to next category
    SELECT,             // Confirm / open / launch
    BACK,               // Go back / close overlay
    LONG_PRESS,         // Context menu / options (Triangle / Y)
    PREV_CATEGORY,      // L1 — jump left along category bar
    NEXT_CATEGORY,      // R1 — jump right along category bar
    HOME,               // Start — show boot screen / return to root
    OPEN_TASK_TRAY,     // Select button — open background task tray
}
