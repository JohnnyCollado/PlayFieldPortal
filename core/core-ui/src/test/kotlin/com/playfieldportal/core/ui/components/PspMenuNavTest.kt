package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules every PSP-panel menu follows: the cursor clamps instead of wrapping, SCROLL sounds only
 * when it actually moves, Back climbs one level, Triangle closes from any depth, and an activation
 * cue follows the row's kind.
 */
class PspMenuNavTest {

    private class Recorder : MenuSoundSink {
        val played = mutableListOf<MenuSound>()
        override fun play(sound: MenuSound) { played += sound }
    }

    private fun handle(
        action: GamepadAction,
        index: Int = 0,
        rowCount: Int = 3,
        depth: Int = 0,
        cue: PspMenuCue = PspMenuCue.CONFIRM,
        rec: Recorder = Recorder(),
    ): Pair<PspMenuOutcome, List<MenuSound>> =
        PspMenuNav.handle(action, index, rowCount, depth, cue, rec) to rec.played

    // ── P1-1: clamp, SCROLL only on a real move ──

    @Test
    fun `up at the first row and down at the last stay put and make no sound`() {
        val (up, upSounds) = handle(GamepadAction.NAVIGATE_UP, index = 0)
        val (down, downSounds) = handle(GamepadAction.NAVIGATE_DOWN, index = 2)
        assertEquals(PspMenuOutcome.Ignored, up)
        assertEquals(PspMenuOutcome.Ignored, down)
        assertTrue(upSounds.isEmpty())
        assertTrue(downSounds.isEmpty())
    }

    @Test
    fun `a real move reports the new index and plays SCROLL`() {
        val (down, downSounds) = handle(GamepadAction.NAVIGATE_DOWN, index = 0)
        val (up, upSounds) = handle(GamepadAction.NAVIGATE_UP, index = 2)
        assertEquals(PspMenuOutcome.Moved(1), down)
        assertEquals(PspMenuOutcome.Moved(1), up)
        assertEquals(listOf(MenuSound.SCROLL), downSounds)
        assertEquals(listOf(MenuSound.SCROLL), upSounds)
    }

    @Test
    fun `an empty menu ignores movement and activation`() {
        assertEquals(PspMenuOutcome.Ignored, handle(GamepadAction.NAVIGATE_DOWN, rowCount = 0).first)
        val (select, sounds) = handle(GamepadAction.SELECT, rowCount = 0)
        assertEquals(PspMenuOutcome.Ignored, select)
        assertTrue(sounds.isEmpty())
    }

    // ── P1-2: Back climbs, Triangle closes ──

    @Test
    fun `back below the root climbs one level with BACK`() {
        val (outcome, sounds) = handle(GamepadAction.BACK, depth = 1)
        assertEquals(PspMenuOutcome.Up, outcome)
        assertEquals(listOf(MenuSound.BACK), sounds)
    }

    @Test
    fun `back at the root closes with BACK`() {
        val (outcome, sounds) = handle(GamepadAction.BACK, depth = 0)
        assertEquals(PspMenuOutcome.Close, outcome)
        assertEquals(listOf(MenuSound.BACK), sounds)
    }

    @Test
    fun `triangle closes from any depth with BACK`() {
        for (depth in 0..2) {
            val (outcome, sounds) = handle(GamepadAction.OPEN_CONTEXT_MENU, depth = depth)
            assertEquals(PspMenuOutcome.Close, outcome)
            assertEquals(listOf(MenuSound.BACK), sounds)
        }
    }

    @Test
    fun `other actions are ignored silently`() {
        val (outcome, sounds) = handle(GamepadAction.NAVIGATE_LEFT)
        assertEquals(PspMenuOutcome.Ignored, outcome)
        assertTrue(sounds.isEmpty())
    }

    // ── P1-3: the activation cue follows the row ──

    @Test
    fun `activating a row that opens a menu plays SELECT`() {
        val (outcome, sounds) = handle(GamepadAction.SELECT, cue = PspMenuCue.SELECT)
        assertEquals(PspMenuOutcome.Activate, outcome)
        assertEquals(listOf(MenuSound.SELECT), sounds)
    }

    @Test
    fun `activating a commit row plays CONFIRM`() {
        val (outcome, sounds) = handle(GamepadAction.SELECT, cue = PspMenuCue.CONFIRM)
        assertEquals(PspMenuOutcome.Activate, outcome)
        assertEquals(listOf(MenuSound.CONFIRM), sounds)
    }

    @Test
    fun `activating a silent row activates without a sound`() {
        val (outcome, sounds) = handle(GamepadAction.SELECT, cue = PspMenuCue.NONE)
        assertEquals(PspMenuOutcome.Activate, outcome)
        assertTrue(sounds.isEmpty())
    }

    @Test
    fun `a row's cue comes from its flags`() {
        assertEquals(PspMenuCue.SELECT, PspMenuRow("Add to Card", opensMenu = true).cue)
        assertEquals(PspMenuCue.CONFIRM, PspMenuRow("Hide").cue)
        assertEquals(PspMenuCue.NONE, PspMenuRow("Favorite", silent = true).cue)
        // Silent wins: a row that opens nothing audible stays quiet even if it also shows a ›.
        assertEquals(PspMenuCue.NONE, PspMenuRow("Favorite", opensMenu = true, silent = true).cue)
    }
}
