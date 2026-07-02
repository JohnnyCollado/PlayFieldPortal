package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.TouchNavButtonMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers how the on-screen touch button visibility resolves from mode + last input source. */
class TouchNavButtonResolutionTest {

    @Test fun `auto follows the last input source`() {
        val base = XMBUiState(touchNavButtonMode = TouchNavButtonMode.AUTO)
        assertFalse("controller → hidden", base.copy(lastInputWasTouch = false).resolvedShowTouchButton)
        assertTrue("touch → shown", base.copy(lastInputWasTouch = true).resolvedShowTouchButton)
    }

    @Test fun `always show ignores input source`() {
        val s = XMBUiState(touchNavButtonMode = TouchNavButtonMode.ALWAYS_SHOW)
        assertTrue(s.copy(lastInputWasTouch = false).resolvedShowTouchButton)
        assertTrue(s.copy(lastInputWasTouch = true).resolvedShowTouchButton)
    }

    @Test fun `always hide ignores input source`() {
        val s = XMBUiState(touchNavButtonMode = TouchNavButtonMode.ALWAYS_HIDE)
        assertFalse(s.copy(lastInputWasTouch = false).resolvedShowTouchButton)
        assertFalse(s.copy(lastInputWasTouch = true).resolvedShowTouchButton)
    }

    @Test fun `mode parses tolerantly`() {
        assertEquals(TouchNavButtonMode.ALWAYS_SHOW, TouchNavButtonMode.fromName("ALWAYS_SHOW"))
        assertEquals(TouchNavButtonMode.AUTO, TouchNavButtonMode.fromName(null))
        assertEquals(TouchNavButtonMode.AUTO, TouchNavButtonMode.fromName("garbage"))
    }
}
