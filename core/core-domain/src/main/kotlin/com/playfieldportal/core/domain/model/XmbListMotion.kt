package com.playfieldportal.core.domain.model

/**
 * How the XMB item list steps between rows (Settings ▸ Interface ▸ Display ▸ Item List Motion).
 *
 *  - [REWIND] a single ▼ hands the focused row up behind the category icon fast while the column
 *    follows a beat later; ▲ plays that backwards — the column moves first and the row above the
 *    bar drops in. A held direction glides as one column. The default.
 *  - [GLIDE]  the whole column moves together on the category bar's spring — the first step
 *    animation PFP shipped.
 */
enum class XmbListMotion(val label: String) {
    REWIND("Rewind"),
    GLIDE("Glide");

    companion object {
        val DEFAULT = REWIND

        /** Tolerant parse for the persisted preference; unknown/blank falls back to [DEFAULT]. */
        fun fromName(value: String?): XmbListMotion =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
