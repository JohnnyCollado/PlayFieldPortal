package com.playfieldportal.core.ui.wave

/**
 * Visual treatment for the XMB wave. Only applies when no custom wallpaper is set — a
 * wallpaper automatically replaces the wave.
 *
 *  - [ANIMATED]        full opacity, animated
 *  - [REDUCED]         lower opacity + amplitude, animated (slower)
 *  - [STATIC]          full opacity, frozen wave — no animation updates
 *  - [REDUCED_STATIC]  lower opacity + amplitude, frozen wave
 */
enum class WaveStyle {
    ANIMATED,
    REDUCED,
    STATIC,
    REDUCED_STATIC;

    /** Whether the wave should animate at all. */
    val animated: Boolean get() = this == ANIMATED || this == REDUCED

    /** Whether the wave should be drawn at reduced opacity / amplitude. */
    val reduced: Boolean get() = this == REDUCED || this == REDUCED_STATIC
}
