package com.playfieldportal.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the words each of the picker's bars is labelled with: they are how a bar's position is read
 * without judging the swatch by eye.
 */
class ChannelReadoutTest {

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
