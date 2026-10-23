package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library Manager ▸ Import PC Games ▸ Add by ID, as a settings step instead of a Material dialog
 * whose fields and Source chips a pad could not reach (survey P6,
 * docs/audits/codebase-survey-2026-10-22.md).
 */
class AddPcGameStepTest {

    @Test
    fun `Source steps through the launcher's sources and wraps`() {
        val sources = listOf("steam", "epic", "gog")
        assertEquals("epic", nextPcSource(sources, "steam"))
        assertEquals("steam", nextPcSource(sources, "gog"))
        assertEquals("steam", nextPcSource(sources, null))
        assertEquals(null, nextPcSource(emptyList(), null))
    }

    @Test
    fun `Add needs both an id and a name, Test Launch only the id`() {
        assertFalse(canAddPcGame(id = "", title = "Hades"))
        assertFalse(canAddPcGame(id = "1145360", title = "  "))
        assertTrue(canAddPcGame(id = "1145360", title = "Hades"))
        assertFalse(canTestPcGame(id = " "))
        assertTrue(canTestPcGame(id = "1145360"))
    }
}
