package com.playfieldportal.core.ui.motion

import com.playfieldportal.core.ui.wave.WaveStyle

/**
 * Pure decision for whether a user-supplied motion wallpaper (looping MP4/WebM/GIF behind the
 * XMB) plays, plays slowed, or renders its poster still. The governing rule it encodes:
 *
 * > When the wave would be frozen, the motion wallpaper is not merely paused — no decoder exists.
 *
 * `WaveStyle.STATIC` never starts the wave's frame loop; the motion equivalent is that POSTER
 * never constructs an ExoPlayer (a paused player still holds a codec instance, a surface, and
 * buffers — precisely the cost a launcher must not pay while a game is about to launch). The
 * composable that consumes this policy releases its player whenever the decision is POSTER.
 *
 * Tested by MotionWallpaperPolicyTest, which pins the whole matrix.
 */
object MotionWallpaperPolicy {

    /** What the motion branch should render right now. */
    enum class Decision {
        /** No decoder — render the poster still only. */
        POSTER,

        /** Loop the motion wallpaper at full playback speed. */
        PLAY,

        /** Loop it, but slowed (and dimmed by the caller's scrim) — the REDUCED analogue. */
        PLAY_REDUCED,
    }

    /** All inputs the decision reads. Pure data so the matrix is unit-testable off-device. */
    data class Inputs(
        /** A motion file is configured (and its file exists). */
        val hasMotion: Boolean,
        /** The poster sidecar exists — the fallback for freeze AND decode failure. */
        val hasPoster: Boolean,
        /** The user's "how lively is my background" choice (one setting governs wave and motion). */
        val style: WaveStyle,
        /** An opaque fullscreen layer (boot, video, game detail, music player…) covers the background. */
        val covered: Boolean,
        /** Battery saver / thermal throttling is active and its setting is respected. */
        val throttled: Boolean,
        /** The app is at least started (ON_START..ON_STOP); false while backgrounded behind a game. */
        val appVisible: Boolean,
    )

    fun decide(inputs: Inputs): Decision {
        // An unrenderable state (motion without poster, or no motion at all) degrades to the
        // poster rather than a black screen — the caller shouldn't have composed this branch.
        if (!inputs.hasMotion || !inputs.hasPoster) return Decision.POSTER
        // STATIC/REDUCED_STATIC asked for a still background — same choice the wave honors.
        if (!inputs.style.animated) return Decision.POSTER
        // Any freeze input kills the decoder outright, exactly as the wave's frame loop is
        // never started. These are independent: any one alone forces the poster.
        if (inputs.covered || inputs.throttled || !inputs.appVisible) return Decision.POSTER
        return if (inputs.style.reduced) Decision.PLAY_REDUCED else Decision.PLAY
    }
}
