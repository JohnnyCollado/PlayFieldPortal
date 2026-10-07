package com.playfieldportal.core.domain.model

/**
 * Display ▸ Screen Orientation.
 *
 *  - [LANDSCAPE]     PFP stays landscape whichever way the device is held (the default).
 *  - [FOLLOW_DEVICE] the window rotates with the device, so automations that watch the screen's
 *                    rotation (Tasker launcher switching, issue #21) see portrait; PFP covers
 *                    itself with a rotate prompt while it is portrait.
 */
enum class ScreenOrientationMode {
    LANDSCAPE,
    FOLLOW_DEVICE;

    /** The next mode in the settings row's cycle. */
    fun next(): ScreenOrientationMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Tolerant parse for the persisted preference; unknown/blank falls back to [LANDSCAPE]. */
        fun fromName(value: String?): ScreenOrientationMode =
            entries.firstOrNull { it.name == value } ?: LANDSCAPE
    }
}
