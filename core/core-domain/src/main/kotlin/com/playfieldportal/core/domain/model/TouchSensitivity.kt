package com.playfieldportal.core.domain.model

/**
 * How much finger travel one XMB swipe step takes. [stepScale] multiplies the gesture layer's
 * per-step distances (vertical item step and horizontal category step together):
 *
 *  - [LOW]    more travel per step — steadier, harder to overshoot.
 *  - [NORMAL] the tuned default.
 *  - [HIGH]   less travel per step — faster scrubbing.
 */
enum class TouchSensitivity(val stepScale: Float) {
    LOW(1.35f),
    NORMAL(1f),
    HIGH(0.72f);

    companion object {
        /** Tolerant parse for the persisted preference; unknown/blank falls back to [NORMAL]. */
        fun fromName(value: String?): TouchSensitivity =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}
