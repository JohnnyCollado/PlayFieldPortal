package com.playfieldportal.feature.settings.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The glossary labels' string resources read exactly as the English constants the glossary pins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsLabelsResourceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every glossary resource matches its English constant`() {
        assertEquals(SettingsLabels.EDIT_FOLDER, context.getString(SettingsLabels.EDIT_FOLDER_RES))
        assertEquals(SettingsLabels.REMOVE_FOLDER, context.getString(SettingsLabels.REMOVE_FOLDER_RES))
        assertEquals(SettingsLabels.OPTIONS_HINT, context.getString(SettingsLabels.OPTIONS_HINT_RES))
        assertEquals(SettingsLabels.SHOW_ON_BAR, context.getString(SettingsLabels.SHOW_ON_BAR_RES))
        assertEquals(SettingsLabels.SHOW_IN_GAMES, context.getString(SettingsLabels.SHOW_IN_GAMES_RES))
        assertEquals(SettingsLabels.PIN_TO_TOP, context.getString(SettingsLabels.PIN_TO_TOP_RES))
    }
}
