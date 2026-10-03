package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Settings labels that the glossary names, in the one place both screen families read them from. */
class SettingsGlossaryTest {

    @Test
    fun `root folders read Edit Folder and Remove Folder in Settings and the wizard`() {
        assertEquals("Edit Folder", SettingsLabels.EDIT_FOLDER)
        assertEquals("Remove Folder", SettingsLabels.REMOVE_FOLDER)
    }

    @Test
    fun `Triangle's prompt setting is Options Hint`() {
        assertEquals("Options Hint", SettingsLabels.OPTIONS_HINT)
    }

    @Test
    fun `category visibility reads Show on Bar`() {
        assertEquals("Show on Bar", SettingsLabels.SHOW_ON_BAR)
    }

    @Test
    fun `library manager toggles use title casing from the glossary`() {
        assertEquals("Show in Games", SettingsLabels.SHOW_IN_GAMES)
        assertEquals("Pin to Top", SettingsLabels.PIN_TO_TOP)
    }
}
