package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Display's option rows open a choice dialog instead of cycling. The dialog is where every value is
 * listed, so each option must offer exactly the values its setting has, in the stored order.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayOptionChoicesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `every option lists one choice per value of its setting`() {
        val expected = mapOf(
            DisplayOption.MOTION to com.playfieldportal.core.domain.model.ImageMotion.entries.size,
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
        assertEquals(IconLegibilityStyle.entries.map { it.label }, DisplayOption.ICON_LEGIBILITY.choices.map { it.label.resolve(context) })
        assertEquals(UmdSlotMode.entries.map { it.label }, DisplayOption.UMD_SLOT.choices.map { it.label.resolve(context) })
        assertEquals(
            // Named apart from Motion's values (21 Nov 2026): the wave's old "Reduced" meant dimmer.
            listOf("Moving", "Moving, dim", "Still", "Still, dim"),
            DisplayOption.WAVE_STYLE.choices.map { it.label.resolve(context) },
        )
        assertEquals(
            listOf("Animated", "Reduced", "Static"),
            DisplayOption.MOTION.choices.map { it.label.resolve(context) },
        )
    }

    @Test
    fun `no text is a pipe-separated legend any more`() {
        DisplayOption.entries.forEach { option ->
            val summary = option.summary.resolve(context)
            assertFalse("$option summary", '|' in summary)
            assertTrue("$option summary is blank", summary.isNotBlank())
            option.choices.forEach { choice ->
                val label = choice.label.resolve(context)
                assertTrue("$option $label", label.isNotBlank())
                assertFalse("$option $label", '|' in choice.detail?.resolve(context).orEmpty())
            }
        }
    }
}
