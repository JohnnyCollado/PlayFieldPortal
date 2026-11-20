package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the words a colour is described with. They are what a user who cannot judge a hue by eye
 * steers by, so the names, the "leaning" hint at a band's edges and the stepper labels are fixed.
 */
class ColorNamingTest {

    @Test
    fun `each hue band has one plain name`() {
        val expected = mapOf(
            0f to "red", 30f to "orange", 55f to "yellow", 120f to "green",
            180f to "teal", 225f to "blue", 270f to "purple", 310f to "pink", 350f to "red",
        )
        for ((hue, name) in expected) assertEquals(name, ColorNaming.hueName(hue), "hue $hue")
    }

    @Test
    fun `a hue near the edge of its band leans toward the neighbour`() {
        assertEquals("Purple, leaning blue", ColorNaming.hueDescription(252f))
        assertEquals("Purple", ColorNaming.hueDescription(270f))
        assertEquals("Purple, leaning pink", ColorNaming.hueDescription(288f))
    }

    @Test
    fun `red wraps around the top of the hue circle`() {
        assertEquals("Red, leaning pink", ColorNaming.hueDescription(338f))
        assertEquals("Red", ColorNaming.hueDescription(5f))
        assertEquals("Red, leaning orange", ColorNaming.hueDescription(12f))
        assertEquals("Red", ColorNaming.hueDescription(360f))
    }

    @Test
    fun `saturation and brightness read as words`() {
        assertEquals(listOf("Grey", "Muted", "Vivid", "Intense"), listOf(0.1f, 0.3f, 0.6f, 0.9f).map(ColorNaming::saturationWord))
        assertEquals(
            listOf("Very dark", "Dark", "Medium", "Bright"),
            listOf(0.2f, 0.5f, 0.7f, 0.9f).map(ColorNaming::brightnessWord),
        )
    }

    @Test
    fun `a whole colour is described in one phrase`() {
        assertEquals("Purple", ColorNaming.describe(0xFF7A4DD6L))
        assertEquals("Dark grey", ColorNaming.describe(0xFF4A505AL))
        assertEquals("Bright grey", ColorNaming.describe(0xFFB8C4D0L))
        assertEquals("Dark blue", ColorNaming.describe(0xFF23477EL))
        assertEquals("Dark muted green", ColorNaming.describe(ColorMath.hsvToArgb(120f, 0.3f, 0.4f)))
    }

    @Test
    fun `tint steps are labelled by the neighbouring hue`() {
        val royalPurple = 0xFF7A4DD6L
        assertEquals("Much bluer", ColorNaming.tintLabel(royalPurple, -2))
        assertEquals("Bluer", ColorNaming.tintLabel(royalPurple, -1))
        assertEquals("Standard", ColorNaming.tintLabel(royalPurple, 0))
        assertEquals("Pinker", ColorNaming.tintLabel(royalPurple, 1))
        assertEquals("Much pinker", ColorNaming.tintLabel(royalPurple, 2))
        assertEquals("More teal", ColorNaming.tintLabel(0xFF0055AAL, -1))
        assertEquals("More orange", ColorNaming.tintLabel(0xFFE03B4FL, 1))
    }

    @Test
    fun `a grey's tint steps are cooler and warmer`() {
        val charcoal = 0xFF4A505AL
        assertEquals("Much cooler", ColorNaming.tintLabel(charcoal, -2))
        assertEquals("Cooler", ColorNaming.tintLabel(charcoal, -1))
        assertEquals("Warmer", ColorNaming.tintLabel(charcoal, 1))
    }

    @Test
    fun `depth steps are labelled lighter to deeper`() {
        assertEquals(
            listOf("Much lighter", "Lighter", "Standard", "Deeper", "Much deeper"),
            (-2..2).map(ColorNaming::depthLabel),
        )
    }
}
