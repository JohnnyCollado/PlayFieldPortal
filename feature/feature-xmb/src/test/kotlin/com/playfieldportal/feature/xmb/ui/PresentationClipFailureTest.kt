package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A boot or GameBoot clip that fails to play falls back to the built-in presentation — and says so in
 * the tray, once per clip (a keyed row), pointing at the fix, instead of failing silently.
 */
class PresentationClipFailureTest {

    @Test
    fun `each presentation reports under its own key, so a repeat replaces its row`() {
        val boot = PresentationClipFailure.of(gameBoot = false)
        val gameBoot = PresentationClipFailure.of(gameBoot = true)
        assertNotEquals(boot.id, gameBoot.id)
        assertEquals(gameBoot.id, PresentationClipFailure.of(gameBoot = true).id)
    }

    @Test
    fun `the message names the clip, says what played instead, and how to fix it`() {
        val m = PresentationClipFailure.of(gameBoot = true)
        assertEquals("GameBoot", m.label)
        assertTrue(m.message, "GameBoot video" in m.message)
        assertTrue(m.message, "built-in" in m.message)
        assertTrue(m.message, "Theme Studio" in m.message)
        assertTrue("Boot Sequence" == PresentationClipFailure.of(gameBoot = false).label)
    }
}
