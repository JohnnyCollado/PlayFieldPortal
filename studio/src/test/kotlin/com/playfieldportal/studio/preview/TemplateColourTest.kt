package com.playfieldportal.studio.preview

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Export templates are what authors paint over: a glyph slot hands out a white silhouette (it is
 * tinted on device), but a full-colour slot must hand out its real art, or the author starts from
 * a shape with every colour thrown away.
 */
class TemplateColourTest {

    private fun opaquePixels(png: ByteArray): List<Int> {
        val img = ImageIO.read(ByteArrayInputStream(png))
        return (0 until img.height).flatMap { y -> (0 until img.width).map { x -> img.getRGB(x, y) } }
            .filter { (it ushr 24) > 0x80 }
    }

    private fun isWhite(argb: Int): Boolean =
        (argb shr 16 and 0xFF) > 0xF0 && (argb shr 8 and 0xFF) > 0xF0 && (argb and 0xFF) > 0xF0

    @Test
    fun `a full-colour template keeps its colours`() {
        val pixels = opaquePixels(PreviewRenderer.rasterizeDefaultIcon("shiba_coin_gold", 64))
        assertTrue(pixels.isNotEmpty(), "the template has art")
        assertFalse(pixels.all(::isWhite), "coin art is not flattened to a white silhouette")
    }

    @Test
    fun `a glyph template stays a white silhouette`() {
        val pixels = opaquePixels(PreviewRenderer.rasterizeDefaultIcon("catbar_music", 64))
        assertTrue(pixels.isNotEmpty(), "the template has art")
        assertTrue(pixels.all(::isWhite), "glyphs are tinted on device, so the template is white")
    }
}
