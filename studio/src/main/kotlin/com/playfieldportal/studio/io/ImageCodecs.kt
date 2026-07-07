package com.playfieldportal.studio.io

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.playfieldportal.themekit.BmpImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * JVM image codecs for the Studio: theme-kit deliberately traffics in raw pixel arrays and
 * encoded bytes; this is where the desktop frontend does the actual encoding/decoding
 * (ImageIO for files/PNG, Skia for Compose bitmaps).
 */
object ImageCodecs {

    /** theme-kit's decoded BMP → a drawable/encodable BufferedImage. */
    fun bmpToBufferedImage(bmp: BmpImage): BufferedImage =
        BufferedImage(bmp.width, bmp.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, bmp.width, bmp.height, bmp.argb, 0, bmp.width)
        }

    fun toPngBytes(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    /** Decodes png/jpg/bmp/gif; null when the file isn't a readable image. */
    fun loadImage(file: File): BufferedImage? =
        runCatching { ImageIO.read(file) }.getOrNull()

    fun decodeImage(bytes: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()

    /**
     * Downscaled ARGB pixel grab for [com.playfieldportal.themekit.AccentDeriver] — the
     * deriver stride-samples anyway, so a bounded copy keeps huge wallpapers cheap.
     */
    fun toBmpImage(image: BufferedImage, maxDim: Int = 480): BmpImage {
        val scale = minOf(1f, maxDim.toFloat() / maxOf(image.width, image.height))
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (w == image.width && h == image.height) image else {
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics()
                g.drawImage(image, 0, 0, w, h, null)
                g.dispose()
            }
        }
        return BmpImage(width = w, height = h, argb = scaled.getRGB(0, 0, w, h, null, 0, w))
    }

    /** Encoded PNG/JPG bytes → Compose bitmap for the preview canvas. Null on bad bytes. */
    fun toImageBitmap(bytes: ByteArray): ImageBitmap? =
        runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()

    /**
     * Validates and normalizes a user-imported icon: decodes, downscales anything larger
     * than [sizePx] on its longest edge (preserving aspect and alpha), re-encodes as PNG.
     * Returns null when the file isn't an image.
     */
    fun normalizeIconPng(file: File, sizePx: Int): ByteArray? {
        val src = loadImage(file) ?: return null
        val longest = maxOf(src.width, src.height)
        val image = if (longest <= sizePx) {
            // Re-draw into ARGB so palette/JPEG sources still export with an alpha channel.
            BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics(); g.drawImage(src, 0, 0, null); g.dispose()
            }
        } else {
            val scale = sizePx.toFloat() / longest
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics()
                g.setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g.drawImage(src, 0, 0, w, h, null)
                g.dispose()
            }
        }
        return toPngBytes(image)
    }
}
