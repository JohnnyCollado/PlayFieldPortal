package com.playfieldportal.feature.launcher

import com.playfieldportal.core.domain.model.Game
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selective sync, Task 3/5: PFP remembers which game it handed off, and reports a RETURN only for a
 * confirmed hand-off — the game's activity covered the launcher (onStop) and PFP came back
 * (onResume). Every other resume (cold start, a rejected launch, an unrelated activity, a second
 * rapid resume) reports nothing, so a local achievement check never runs without a real session.
 * Both the LaunchDispatcher path and the direct Windows-shortcut path feed [GameHandoffTracker].
 */
class GameHandoffTrackerTest {

    private var now = 0L
    private val returned = mutableListOf<Long>()
    private val tracker = GameHandoffTracker(
        clock = { now },
        listeners = setOf(GameSessionReturnListener { game -> returned += game.id }),
    )

    private val game = Game(id = 7, title = "Resonance of Fate", platformId = "windows")

    @Test
    fun `a dispatched game that covered the launcher reports one return`() {
        tracker.onDispatched(game)
        now = 2_000
        tracker.onHostStopped()
        now = 600_000
        tracker.onHostResumed()

        assertEquals(listOf(7L), returned)
    }

    @Test
    fun `a rapid second resume does not report again`() {
        tracker.onDispatched(game)
        tracker.onHostStopped()
        tracker.onHostResumed()
        tracker.onHostResumed()
        tracker.onHostStopped()
        tracker.onHostResumed()

        assertEquals(listOf(7L), returned)
    }

    @Test
    fun `a launch that never took the foreground reports nothing`() {
        tracker.onDispatched(game)
        tracker.onHostResumed()                 // back without ever being covered

        // ...and a later unrelated activity round-trip must not be mistaken for the game.
        tracker.onHostStopped()
        tracker.onHostResumed()

        assertTrue(returned.isEmpty())
    }

    @Test
    fun `a resume with nothing dispatched reports nothing`() {
        tracker.onHostStopped()
        tracker.onHostResumed()

        assertTrue(returned.isEmpty())
    }

    @Test
    fun `a stop long after dispatch is not the game's hand-off`() {
        tracker.onDispatched(game)
        now = GameHandoffTracker.HANDOFF_WINDOW_MS + 1
        tracker.onHostStopped()                 // e.g. the user opened Android settings later
        tracker.onHostResumed()

        assertTrue(returned.isEmpty())
    }

    @Test
    fun `a rejected dispatch is forgotten`() {
        tracker.onDispatched(game)
        tracker.onDispatchRejected()
        tracker.onHostStopped()
        tracker.onHostResumed()

        assertTrue(returned.isEmpty())
    }

    @Test
    fun `the latest dispatch wins`() {
        tracker.onDispatched(game)
        tracker.onDispatched(game.copy(id = 8))
        tracker.onHostStopped()
        tracker.onHostResumed()

        assertEquals(listOf(8L), returned)
    }
}
