package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Customize XMB Icons on a pad: every touch button has a button press. Reset All and Save as Theme
 * used to be touch-only, and touch mode now hides the pad's prompts, so neither family may lack one.
 */
class CustomIconsPadTest {

    @Test
    fun `the pad reaches every touch button`() {
        assertEquals(CustomIconsCommand.PICK, customIconsPadCommand(GamepadAction.SELECT))
        assertEquals(CustomIconsCommand.RESET_SLOT, customIconsPadCommand(GamepadAction.OPEN_CONTEXT_MENU))
        assertEquals(CustomIconsCommand.RESET_ALL, customIconsPadCommand(GamepadAction.CHANGE_SORT))
        assertEquals(CustomIconsCommand.SAVE_AS_THEME, customIconsPadCommand(GamepadAction.HOME))
        assertEquals(CustomIconsCommand.DONE, customIconsPadCommand(GamepadAction.BACK))
    }

    @Test
    fun `cursor and tab buttons are the view model's, not commands`() {
        assertNull(customIconsPadCommand(GamepadAction.NAVIGATE_LEFT))
        assertNull(customIconsPadCommand(GamepadAction.NEXT_CATEGORY))
    }
}
