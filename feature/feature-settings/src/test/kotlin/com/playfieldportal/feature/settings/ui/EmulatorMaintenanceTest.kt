package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reset Emulator Configuration can be reached from the settings tree.
 *
 * Survey U33 (docs/audits/codebase-survey-2026-10-22.md): Maintenance was drawn only with no
 * section, but every Emulators row in the tree opens a section, so the reset was unreachable.
 */
class EmulatorMaintenanceTest {

    @Test
    fun `Maintenance shows on the Installed section and on the unsectioned screen`() {
        assertTrue(showsEmulatorMaintenance(null))
        assertTrue(showsEmulatorMaintenance(EmulatorSettingsSection.INSTALLED))
    }

    @Test
    fun `the Custom and RetroArch sections stay focused on their own rows`() {
        assertFalse(showsEmulatorMaintenance(EmulatorSettingsSection.CUSTOM))
        assertFalse(showsEmulatorMaintenance(EmulatorSettingsSection.RETROARCH))
    }
}
