package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.domain.model.ScreenOrientationMode
import com.playfieldportal.core.domain.model.TextLegibilityStyle
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.model.TouchSensitivity
import com.playfieldportal.core.domain.model.UmdSlotMode
import com.playfieldportal.core.domain.model.XmbListMotion
import com.playfieldportal.core.ui.wave.WaveStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display's option rows open a choice dialog instead of cycling. The dialog is where every value is
 * listed, so each option must offer exactly the values its setting has, in the stored order.
 */
class DisplayOptionChoicesTest {

    @Test
    fun `every option lists one choice per value of its setting`() {
        val expected = mapOf(
            DisplayOption.WAVE_STYLE to WaveStyle.entries.size,
            DisplayOption.BACKGROUND_MOTION to WaveStyle.entries.size,
            DisplayOption.ICON_LEGIBILITY to IconLegibilityStyle.entries.size,
            DisplayOption.ITEM_LIST_MOTION to XmbListMotion.entries.size,
            DisplayOption.UMD_SLOT to UmdSlotMode.entries.size,
            DisplayOption.TEXT_LEGIBILITY to TextLegibilityStyle.entries.size,
            DisplayOption.SCREEN_ORIENTATION to ScreenOrientationMode.entries.size,
            DisplayOption.TOUCH_NAV_BUTTON to TouchNavButtonMode.entries.size,
            DisplayOption.TOUCH_SENSITIVITY to TouchSensitivity.entries.size,
        )
        // A new option added without a row here fails, rather than shipping unchecked.
        assertEquals(DisplayOption.entries.toSet(), expected.keys)
        expected.forEach { (option, count) ->
            assertEquals("$option", count, option.choices.size)
        }
    }

    @Test
    fun `choices keep the setting's own labels in its stored order`() {
        assertEquals(IconLegibilityStyle.entries.map { it.label }, DisplayOption.ICON_LEGIBILITY.choices.map { it.label })
        assertEquals(UmdSlotMode.entries.map { it.label }, DisplayOption.UMD_SLOT.choices.map { it.label })
        assertEquals(
            listOf("Animated", "Reduced", "Static", "Reduced + Static"),
            DisplayOption.WAVE_STYLE.choices.map { it.label },
        )
    }

    @Test
    fun `no text is a pipe-separated legend any more`() {
        DisplayOption.entries.forEach { option ->
            assertFalse("$option summary", '|' in option.summary)
            assertTrue("$option summary is blank", option.summary.isNotBlank())
            option.choices.forEach { choice ->
                assertTrue("$option ${choice.label}", choice.label.isNotBlank())
                assertFalse("$option ${choice.label}", '|' in choice.detail.orEmpty())
            }
        }
    }
}
