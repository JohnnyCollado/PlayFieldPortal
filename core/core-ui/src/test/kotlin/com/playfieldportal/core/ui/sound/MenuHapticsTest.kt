package com.playfieldportal.core.ui.sound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MenuHapticsTest {

    @Test fun `steps tick and a committed action is the firmer cue`() {
        for (s in listOf(MenuSound.SCROLL, MenuSound.SYSTEM_BROWSE, MenuSound.SELECT, MenuSound.OPTIONS, MenuSound.BACK)) {
            assertEquals(HapticCue.TICK, s.hapticCue, s.name)
        }
        assertEquals(HapticCue.CONFIRM, MenuSound.CONFIRM.hapticCue)
    }

    @Test fun `errors and notifications never buzz`() {
        assertEquals(HapticCue.NONE, MenuSound.ERROR.hapticCue)
        assertEquals(HapticCue.NONE, MenuSound.NOTIFICATION.hapticCue)
    }

    @Test fun `nothing buzzes while the preference is off`() {
        val gate = HapticGate()
        assertNull(gate.cueFor(MenuSound.SCROLL, enabled = false, nowMs = 0))
        assertNull(gate.cueFor(MenuSound.CONFIRM, enabled = false, nowMs = 1_000))
    }

    @Test fun `a cue with no haptic neither buzzes nor starts the interval`() {
        val gate = HapticGate()
        assertNull(gate.cueFor(MenuSound.ERROR, enabled = true, nowMs = 100))
        assertEquals(HapticCue.TICK, gate.cueFor(MenuSound.SCROLL, enabled = true, nowMs = 101))
    }

    @Test fun `a held direction is limited to one pulse per 60 ms`() {
        val gate = HapticGate()
        assertEquals(HapticCue.TICK, gate.cueFor(MenuSound.SCROLL, true, 1_000))
        assertNull(gate.cueFor(MenuSound.SCROLL, true, 1_020))
        assertNull(gate.cueFor(MenuSound.SCROLL, true, 1_059))
        assertEquals(HapticCue.TICK, gate.cueFor(MenuSound.SCROLL, true, 1_060))
    }

    @Test fun `a refused cue does not push the interval back`() {
        val gate = HapticGate()
        assertEquals(HapticCue.TICK, gate.cueFor(MenuSound.SCROLL, true, 0))
        assertNull(gate.cueFor(MenuSound.SCROLL, true, 50))
        // Measured from the last pulse (0), not from the refused attempt (50).
        assertEquals(HapticCue.TICK, gate.cueFor(MenuSound.SCROLL, true, 61))
    }

    @Test fun `the first cue is never limited`() {
        assertEquals(HapticCue.CONFIRM, HapticGate().cueFor(MenuSound.CONFIRM, true, 0))
    }
}
