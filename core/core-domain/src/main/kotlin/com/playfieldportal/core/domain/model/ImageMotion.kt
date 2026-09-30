package com.playfieldportal.core.domain.model

/**
 * How animated artwork (GIF / animated WebP) plays: Settings ▸ Artwork ▸ Animated Images.
 * Covers game artwork in every slot, custom icons and animated wallpapers; ICON1 video snaps keep
 * their own Animated Icons switch.
 *
 * An image that may not play holds its first frame. Stored as the enum name in DataStore.
 */
enum class ImageMotion(val label: String) {
    /** Every on-screen animated image plays. */
    ANIMATED("Animated"),
    /** Only the focused item plays (its tile, and the hero/background/logo it drives). */
    REDUCED("Reduced"),
    /** Nothing plays. */
    STATIC("Static");

    /**
     * Whether an image may play right now. [onScreen] and [allowed] come first and apply to every
     * mode: an image nobody can see, or one PFP may not animate at all (in the background behind a
     * game, battery saver, a blocking overlay), always holds its first frame.
     */
    fun shouldAnimate(onScreen: Boolean, focused: Boolean, allowed: Boolean): Boolean =
        onScreen && allowed && when (this) {
            ANIMATED -> true
            REDUCED  -> focused
            STATIC   -> false
        }

    companion object {
        /** Focused-only: the battery-friendly choice, and how custom icons already behaved. */
        val DEFAULT = REDUCED

        fun fromName(name: String?): ImageMotion? = entries.firstOrNull { it.name == name }
    }
}
