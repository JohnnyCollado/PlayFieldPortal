package com.playfieldportal.studio

import com.playfieldportal.studio.io.ImageCodecs
import com.playfieldportal.themekit.BmpImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageCodecsTest {

    @Test
    fun `bmp image pixels survive the png round trip`() {
        val bmp = BmpImage(4, 2, intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF000000.toInt(), 0xFF808080.toInt(), 0xFF123456.toInt(), 0xFFFEDCBA.toInt(),
        ))
        val png = ImageCodecs.toPngBytes(ImageCodecs.bmpToBufferedImage(bmp))
        val decoded = assertNotNull(ImageIO.read(png.inputStream()))
        assertEquals(4, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(0xFFFF0000.toInt(), decoded.getRGB(0, 0))
        assertEquals(0xFF123456.toInt(), decoded.getRGB(2, 1))
    }

    @Test
    fun `toBmpImage bounds huge images for accent sampling`() {
        val big = BufferedImage(2000, 1000, BufferedImage.TYPE_INT_ARGB)
        val bmp = ImageCodecs.toBmpImage(big, maxDim = 480)
        assertEquals(480, bmp.width)
        assertEquals(240, bmp.height)
        assertEquals(480 * 240, bmp.argb.size)
    }

    @Test
    fun `normalizeIconPng downscales oversized icons and keeps small ones`(): Unit {
        val dir = File(System.getProperty("java.io.tmpdir"), "studio-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val bigFile = File(dir, "big.png")
            ImageIO.write(BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB), "png", bigFile)
            val normalized = assertNotNull(ImageCodecs.normalizeIconPng(bigFile, sizePx = 256))
            val image = assertNotNull(ImageIO.read(normalized.inputStream()))
            assertEquals(256, image.width)
            assertEquals(128, image.height)

            val smallFile = File(dir, "small.png")
            ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", smallFile)
            val kept = assertNotNull(ImageCodecs.normalizeIconPng(smallFile, sizePx = 256))
            val keptImage = assertNotNull(ImageIO.read(kept.inputStream()))
            assertEquals(64, keptImage.width)
            // JPEG/RGB sources re-encode with an alpha channel so silhouettes stay tintable.
            assertTrue(keptImage.colorModel.hasAlpha())

            val junk = File(dir, "junk.png").apply { writeBytes(ByteArray(16) { 9 }) }
            assertNull(ImageCodecs.normalizeIconPng(junk, sizePx = 256))
        } finally {
            dir.deleteRecursively()
        }
    }
}
