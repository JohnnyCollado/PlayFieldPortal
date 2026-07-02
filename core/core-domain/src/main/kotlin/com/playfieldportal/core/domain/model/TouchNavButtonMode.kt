package com.playfieldportal.core.domain.model

/**
 * Controls the bottom-right on-screen contextual button (App Drawer / Back).
 *
 *  - [AUTO]        show it only when touch was the last input; hide it while a controller is in use.
 *  - [ALWAYS_SHOW] always visible (useful on touch-only devices).
 *  - [ALWAYS_HIDE] never visible (controller-only setups).
 */
enum class TouchNavButtonMode {
    AUTO,
    ALWAYS_SHOW,
    ALWAYS_HIDE;

    companion object {
        /** Tolerant parse for the persisted preference; unknown/blank falls back to [AUTO]. */
        fun fromName(value: String?): TouchNavButtonMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
