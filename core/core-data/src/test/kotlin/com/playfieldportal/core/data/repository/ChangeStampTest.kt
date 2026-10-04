package com.playfieldportal.core.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/** A change stamp observed for a change must move on every bump, even inside one clock tick. */
class ChangeStampTest {
    @Test
    fun `follows the clock when the clock has moved on`() {
        assertEquals(2_000L, nextStamp(previous = 1_000L, now = 2_000L))
    }

    @Test
    fun `steps past the previous stamp when the clock has not moved`() {
        assertEquals(1_001L, nextStamp(previous = 1_000L, now = 1_000L))
        assertEquals(1_001L, nextStamp(previous = 1_000L, now = 900L))
    }

    @Test
    fun `a first stamp is just the clock`() {
        assertEquals(1_000L, nextStamp(previous = null, now = 1_000L))
    }
}
