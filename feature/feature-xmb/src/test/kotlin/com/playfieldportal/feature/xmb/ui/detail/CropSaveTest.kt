package com.playfieldportal.feature.xmb.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How an applied crop is saved. A still is baked into a PNG as it always was; an animated image
 * is kept whole and framed while drawing, because baking would flatten it to one frame; a video
 * snap is re-encoded, which is its own path entirely.
 */
class CropSaveTest {

    private val gif = "GIF89a".toByteArray() + ByteArray(20)
    private val png = byteArrayOf(0x89.toByte()) + "PNG".toByteArray() + ByteArray(20)
    private val animatedWebp = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() +
        byteArrayOf(10, 0, 0, 0, 0x02) + ByteArray(8)

    @Test
    fun `a still image is baked`() {
        assertEquals(CropSave.BAKE, CropSave.of(isVideo = false, header = png))
    }

    @Test
    fun `an animated image is framed at draw time`() {
        assertEquals(CropSave.AT_DRAW, CropSave.of(isVideo = false, header = gif))
        assertEquals(CropSave.AT_DRAW, CropSave.of(isVideo = false, header = animatedWebp))
    }

    @Test
    fun `a video snap is re-encoded, whatever its bytes look like`() {
        assertEquals(CropSave.REENCODE_VIDEO, CropSave.of(isVideo = true, header = gif))
    }
}
