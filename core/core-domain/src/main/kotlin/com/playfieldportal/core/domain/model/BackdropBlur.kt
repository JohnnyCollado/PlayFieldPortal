package com.playfieldportal.core.domain.model

import kotlin.math.roundToInt

/**
 * How strongly the screen is blurred behind a prompt or the options panel
 * (Settings ▸ Interface ▸ Display ▸ Background blur).
 *
 * One strength from 0 (off) to 1. 1 is the ceiling: the blur the feature first shipped to the
 * device with, which the owner asked to be the most it can ever be. The setting only weakens it.
 *
 * [PREFERENCE_KEY] is a stored contract (prefs on every device, and backups): never rename it.
 */
object BackdropBlur {
    const val PREFERENCE_KEY = "display_backdrop_blur_strength"

    /** The blur radius at full strength, in dp. */
    const val MAX_RADIUS_DP = 14f

    /** What a fresh install uses: a little over half the ceiling. */
    const val DEFAULT_STRENGTH = 0.6f

    /** How many positions the slider has between its ends (10% each). */
    const val SLIDER_STEPS = 9

    /** A stored strength as a usable one: absent is the default, anything else is kept in 0..1. */
    fun coerce(stored: Float?): Float = (stored ?: DEFAULT_STRENGTH).coerceIn(0f, 1f)

    /** The strength rounded to the nearest slider position. */
    fun snap(strength: Float): Float = (coerce(strength) * 10f).roundToInt() / 10f

    /** The blur radius, in dp, for [strength]. 0 means draw no blur at all. */
    fun radiusDp(strength: Float): Float = MAX_RADIUS_DP * coerce(strength)
}
