package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [BackgroundTaskInfo.fraction] is the whole reason the counts stopped being discarded, so the
 * cases it has to survive are the ones the producers actually emit: a total of zero from "nothing
 * to do", and no counts at all from a file scan that cannot know its total.
 */
class BackgroundTaskInfoTest {

    private fun task(current: Int? = null, total: Int? = null) =
        BackgroundTaskInfo(id = "t", label = "Scanning", current = current, total = total)

    @Test
    fun `a fraction comes from current over total`() {
        assertEquals(0.25f, task(current = 14, total = 56).fraction)
    }

    @Test
    fun `an indeterminate task keeps a null fraction`() {
        assertNull(task().fraction, "a file scan reports no counts at all")
        assertNull(task(current = 5).fraction, "a current with no total is not a fraction")
        assertNull(task(total = 5).fraction, "a total with no current is not a fraction")
    }

    @Test
    fun `a total of zero does not divide by zero`() {
        assertNull(task(current = 0, total = 0).fraction)
    }

    @Test
    fun `a fraction is clamped, so a producer overshooting its own total cannot overflow the bar`() {
        assertEquals(1f, task(current = 60, total = 56).fraction)
    }

    @Test
    fun `the count label is shown only when there are real counts to show`() {
        assertEquals("14 / 56", task(current = 14, total = 56).countLabel)
        assertNull(task().countLabel)
        assertNull(task(current = 0, total = 0).countLabel)
    }

    @Test
    fun `a task kind maps to the history kind its settled row will use`() {
        // One icon table has to serve both panel sections, so these must line up by name.
        for (kind in TaskKind.entries) {
            assertEquals(kind.name, kind.notificationKind.name)
        }
    }
}
