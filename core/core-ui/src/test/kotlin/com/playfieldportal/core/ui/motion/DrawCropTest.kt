package com.playfieldportal.core.ui.motion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cropping at draw time: the whole image is laid out larger than the slot and shifted so the crop
 * rect lands exactly on the slot's bounds (everything else is clipped away). Pinned as numbers,
 * because an off-by-a-fraction here shows up as a tile framed slightly wrong on every game.
 */
class DrawCropTest {

    @Test
    fun `parses the stored rect and rejects anything that is not a real crop`() {
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.9f, 0.8f), DrawCrop.parse("0.1000,0.2000,0.9000,0.8000")!!.asArray(), 0.0001f)
        assertNull(DrawCrop.parse(null))
        assertNull(DrawCrop.parse("0.1,0.2,0.9"))
        assertNull("inverted", DrawCrop.parse("0.9,0.2,0.1,0.8"))
        assertNull("out of range", DrawCrop.parse("-0.1,0,1,1"))
        assertNull("the full image is no crop at all", DrawCrop.parse("0,0,1,1"))
    }

    @Test
    fun `lays the whole image out so the crop fills the bounds`() {
        val crop = DrawCrop(0.25f, 0.5f, 0.75f, 1f)   // middle half across, bottom half down

        val inner = crop.innerBounds(left = 0f, top = 0f, right = 100f, bottom = 50f)

        // The crop is half the image each way, so the image is laid out at twice the bounds,
        // shifted left by a quarter of that width and up by half of that height.
        assertArrayEquals(floatArrayOf(-50f, -50f, 150f, 50f), inner, 0.001f)
    }

    @Test
    fun `respects a bounds origin that is not zero`() {
        val inner = DrawCrop(0f, 0f, 0.5f, 0.5f).innerBounds(left = 10f, top = 20f, right = 60f, bottom = 70f)

        assertArrayEquals(floatArrayOf(10f, 20f, 110f, 120f), inner, 0.001f)
    }

    @Test
    fun `reports the cropped intrinsic size`() {
        val crop = DrawCrop(0.25f, 0.5f, 0.75f, 1f)

        assertEquals(300, crop.croppedWidth(600))
        assertEquals(450, crop.croppedHeight(900))
    }

    @Test
    fun `scales a load size up so the cropped part still arrives at full resolution`() {
        // A 144×80 tile showing the middle half of an image needs the image decoded at 288×160.
        val crop = DrawCrop(0.25f, 0.25f, 0.75f, 0.75f)

        assertEquals(288, crop.sourceWidthFor(144))
        assertEquals(160, crop.sourceHeightFor(80))
    }

    @Test
    fun `keys the memory cache by its exact rect`() {
        assertEquals("0.2500,0.5000,0.7500,1.0000", DrawCrop(0.25f, 0.5f, 0.75f, 1f).key)
    }
}
