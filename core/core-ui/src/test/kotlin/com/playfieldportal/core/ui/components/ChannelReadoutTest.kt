package com.playfieldportal.core.ui.components

import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the words each of the picker's bars is labelled with: they are how a bar's position is read
 * without judging the swatch by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelReadoutTest {

    private fun channelReadout(field: HsvPickerField, state: HsvPickerState): String =
        com.playfieldportal.core.ui.components.channelReadout(field, state).resolve(ApplicationProvider.getApplicationContext<android.content.Context>())


    private val state = HsvPickerState(hue = 252f, saturation = 0.64f, brightness = 0.84f)

    @Test
    fun `the hue bar names its hue and the neighbour it leans toward`() {
        assertEquals("Purple, leaning blue", channelReadout(HsvPickerField.HUE, state))
        assertEquals("Purple", channelReadout(HsvPickerField.HUE, state.copy(hue = 270f)))
    }

    @Test
    fun `saturation and brightness read as a word and a rounded percentage`() {
        assertEquals("Vivid 64%", channelReadout(HsvPickerField.SATURATION, state))
        assertEquals("Bright 84%", channelReadout(HsvPickerField.BRIGHTNESS, state))
        assertEquals("Grey 0%", channelReadout(HsvPickerField.SATURATION, state.copy(saturation = 0f)))
        assertEquals("Very dark 29%", channelReadout(HsvPickerField.BRIGHTNESS, state.copy(brightness = 0.29f)))
    }
}
