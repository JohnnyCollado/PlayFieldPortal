package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.Game
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Told when PFP comes back from a game session it launched itself. Implementations must be quick
 * and non-blocking — they run on the main thread from MainActivity's onResume.
 */
fun interface GameSessionReturnListener {
    fun onGameSessionReturned(game: Game)
}

/**
 * Remembers which game PFP handed off and reports a RETURN only for a confirmed hand-off: the
 * game's activity covered the launcher (onStop, within [HANDOFF_WINDOW_MS] of dispatch) and PFP
 * came back (onResume). A cold-start resume, a launch that never took the foreground, an
 * unrelated activity round-trip and a second rapid resume all report nothing.
 *
 * Fed from both launch paths: [LaunchDispatcher] (emulator/package launches) and the direct
 * Windows-shortcut path that bypasses it (`LauncherShortcutRepository.launch`). Unlike the
 * dispatcher it records no outcome and raises no recovery UI — it only answers "did the user just
 * come back from this game?", which is what the Local Steam achievement check needs.
 *
 * Main-thread confined like the dispatcher's own state: every caller is a ViewModel launch site or
 * a MainActivity lifecycle callback.
 */
@Singleton
class GameHandoffTracker @Inject constructor(
    @LaunchRealtimeClock private val clock: LaunchClock,
    private val listeners: Set<@JvmSuppressWildcards GameSessionReturnListener>,
) {
    private var pending: Game? = null
    private var dispatchedAt = 0L
    private var covered = false

    /** A launch intent for [game] reached the system. The latest dispatch wins. */
    fun onDispatched(game: Game) {
        pending = game
        dispatchedAt = clock.now()
        covered = false
    }

    /** The launch never happened (startActivity or the shortcut launch failed). */
    fun onDispatchRejected() {
        pending = null
        covered = false
    }

    /** MainActivity stopped: the dispatched game covered the launcher, if it did so promptly. */
    fun onHostStopped() {
        if (pending == null) return
        if (clock.now() - dispatchedAt > HANDOFF_WINDOW_MS) {
            // Too late to be this launch's hand-off (e.g. Android settings opened later).
            pending = null
            return
        }
        covered = true
    }

    /** MainActivity resumed: report a confirmed session's return once, then forget it. */
    fun onHostResumed() {
        val game = pending ?: return
        val returned = covered
        pending = null
        covered = false
        if (!returned) return
        listeners.forEach { listener ->
            runCatching { listener.onGameSessionReturned(game) }
                .onFailure { Timber.w(it, "Game return listener failed for %s", game.id) }
        }
    }

    companion object {
        /** How soon after dispatch the game's activity must cover the launcher to count. */
        const val HANDOFF_WINDOW_MS = 15_000L
    }
}
