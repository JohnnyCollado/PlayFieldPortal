package com.playfieldportal.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #21: the persisted Screen Orientation choice. Landscape stays the default. */
class ScreenOrientationModeTest {

    @Test
    fun `parses the persisted name`() {
        assertEquals(ScreenOrientationMode.FOLLOW_DEVICE, ScreenOrientationMode.fromName("FOLLOW_DEVICE"))
        assertEquals(ScreenOrientationMode.LANDSCAPE, ScreenOrientationMode.fromName("LANDSCAPE"))
    }

    @Test
    fun `missing or unknown values fall back to landscape`() {
        assertEquals(ScreenOrientationMode.LANDSCAPE, ScreenOrientationMode.fromName(null))
        assertEquals(ScreenOrientationMode.LANDSCAPE, ScreenOrientationMode.fromName(""))
        assertEquals(ScreenOrientationMode.LANDSCAPE, ScreenOrientationMode.fromName("PORTRAIT"))
    }

    @Test
    fun `next cycles through every mode and wraps`() {
        assertEquals(ScreenOrientationMode.FOLLOW_DEVICE, ScreenOrientationMode.LANDSCAPE.next())
        assertEquals(ScreenOrientationMode.LANDSCAPE, ScreenOrientationMode.FOLLOW_DEVICE.next())
    }
}
