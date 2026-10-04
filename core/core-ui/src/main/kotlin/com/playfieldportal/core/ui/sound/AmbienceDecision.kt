package com.playfieldportal.core.ui.sound

/**
 * What the ambience loop should be doing. Pure, so the gate rules are testable without a player.
 *  - [PLAY]: the launcher is in front and nothing outranks the loop.
 *  - [HOLD]: the launcher is still visible but paused (a dialog-style app on top) — the player keeps
 *    its place and its decoder, so a brief interruption resumes mid-loop instead of restarting.
 *  - [RELEASE]: stopped (a game in front), or nothing to play — the decoder is let go.
 */
enum class AmbienceDecision {
    PLAY, HOLD, RELEASE;

    companion object {
        fun decide(
            assigned: Boolean,
            gain: Float,
            foreground: Boolean,
            hostPaused: Boolean,
            bootFinished: Boolean,
            held: Boolean,
        ): AmbienceDecision = when {
            !assigned || gain <= 0f || !foreground || !bootFinished || held -> RELEASE
            hostPaused -> HOLD
            else -> PLAY
        }
    }
}
