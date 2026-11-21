package com.playfieldportal.core.domain.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * The light round the focused XMB icon (Settings ▸ Interface ▸ Display ▸ Focus glow), drawn in the
 * icon's own shape. Stored as the enum name under [PREFERENCE_KEY], a stored contract (prefs on
 * every device, and backups): never rename the key or the entries.
 */
enum class FocusGlowMode {
    /** No glow. */
    OFF,
    /** A glow that holds still. The default: nobody gets motion they did not choose. */
    STEADY,
    /** A slow swell and fade, like the PSP's. */
    PULSING;

    /**
     * What is actually drawn. The glow is always on the focused icon, and Reduced means "only the
     * focused item moves", so Pulsing plays under Animated and Reduced and holds still under Static.
     * It also holds still whenever motion is not allowed right now (battery saver, a blocking
     * overlay, system animations off). Steady and Off are never changed.
     */
    fun shown(imageMotion: ImageMotion, motionAllowed: Boolean): FocusGlowMode =
        if (this == PULSING && (imageMotion == ImageMotion.STATIC || !motionAllowed)) STEADY else this

    companion object {
        const val PREFERENCE_KEY = "display_focus_glow"
        val DEFAULT = STEADY

        fun fromStored(name: String?): FocusGlowMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The glow's numbers: how big it is for an icon, and the shape of its pulse. */
object FocusGlow {
    /**
     * One whole swell and fade, in milliseconds. A little slower than the 3,000 ms pulse measured in
     * the PSP's firmware (its boot logo glow), because the owner asked for slow and gradual.
     */
    const val PULSE_MS = 3600

    /** The dimmest the pulse gets, as a share of the steady glow. It never goes dark. */
    const val PULSE_FLOOR = 0.4f

    /** Where in the cycle the glow is brightest: it swells for this share and fades for the rest. */
    const val PULSE_PEAK_AT = 0.38f

    /**
     * The glow's strength at [phase] of the cycle (0..1, wrapping), as a share of the steady glow:
     * an eased swell to full, then a longer eased fade back to [PULSE_FLOOR]. No steps anywhere.
     */
    fun pulseLevel(phase: Float): Float {
        val wrapped = phase - kotlin.math.floor(phase)
        val t = if (wrapped <= PULSE_PEAK_AT) wrapped / PULSE_PEAK_AT else (1f - wrapped) / (1f - PULSE_PEAK_AT)
        val eased = (1f - cos(PI.toFloat() * t)) / 2f
        return PULSE_FLOOR + (1f - PULSE_FLOOR) * eased
    }

    /** Blur radius of the wide, soft layer for an icon drawn [width] x [height] pixels. */
    fun softRadiusPx(width: Float, height: Float): Float = min(width, height) * SOFT_SHARE

    /** Blur radius of the tight, brighter rim. */
    fun tightRadiusPx(width: Float, height: Float): Float = min(width, height) * TIGHT_SHARE

    /** Opacity of each layer at full strength (0..255), as approved on 21 Nov 2026. */
    const val SOFT_ALPHA = 215
    const val TIGHT_ALPHA = 150

    /** The glow's colour: a pale blue-white. */
    const val COLOR_ARGB = 0xFFDDEEFF.toInt()

    private const val SOFT_SHARE = 18.6f / 216f
    private const val TIGHT_SHARE = 5f / 216f
}
