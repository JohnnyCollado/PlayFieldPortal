package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A boot or GameBoot clip that fails to play falls back to the built-in presentation — and says so in
 * the tray, once per clip (a keyed row), pointing at the fix, instead of failing silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresentationClipFailureTest {

    private fun text(t: com.playfieldportal.core.domain.text.UiText) = t.resolve(RuntimeEnvironment.getApplication())

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
        val message = text(m.message)
        assertEquals("Game Boot", text(m.label))
        assertTrue(message, "Game Boot video" in message)
        assertTrue(message, "built-in" in message)
        assertTrue(message, "Theme Studio" in message)
        assertTrue("Boot Sequence" == text(PresentationClipFailure.of(gameBoot = false).label))
    }
}
