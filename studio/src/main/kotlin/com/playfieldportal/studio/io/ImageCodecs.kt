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

    // Matches theme-kit Bmp.kt's cap: a crafted header claiming huge dimensions is
    // rejected BEFORE ImageIO allocates pixel buffers for it.
    private const val MAX_IMAGE_DIMENSION = 8192

    /**
     * Header-only dimension sniff via the format's ImageReader — no pixel allocation.
     * Null when no reader claims the input (not an image).
     */
    private fun dimensionsWithinBounds(input: javax.imageio.stream.ImageInputStream): Boolean {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return false
        val reader = readers.next()
        return try {
            reader.input = input
            reader.getWidth(0) <= MAX_IMAGE_DIMENSION && reader.getHeight(0) <= MAX_IMAGE_DIMENSION
        } catch (_: Exception) {
            false
        } finally {
            reader.dispose()
        }
    }

    /** theme-kit's decoded BMP → a drawable/encodable BufferedImage. */
    fun bmpToBufferedImage(bmp: BmpImage): BufferedImage =
        BufferedImage(bmp.width, bmp.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, bmp.width, bmp.height, bmp.argb, 0, bmp.width)
        }

    fun toPngBytes(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    /**
     * Decodes png/jpg/bmp/gif; null when the file isn't a readable image, is oversized
     * (> [SafeIo.MAX_THEME_FILE_BYTES]), or claims dimensions past the 8192 cap.
     */
    fun loadImage(file: File): BufferedImage? {
        if (!file.isFile || file.length() > SafeIo.MAX_THEME_FILE_BYTES) return null
        return runCatching {
            val boundsOk = ImageIO.createImageInputStream(file)?.use(::dimensionsWithinBounds) ?: false
            if (boundsOk) ImageIO.read(file) else null
        }.getOrNull()
    }

    fun decodeImage(bytes: ByteArray): BufferedImage? =
        runCatching {
            val boundsOk = ImageIO.createImageInputStream(bytes.inputStream())
                ?.use(::dimensionsWithinBounds) ?: false
            if (boundsOk) ImageIO.read(bytes.inputStream()) else null
        }.getOrNull()

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
     * Center-crops [src] to the target aspect ratio, then bilinear-scales to exactly
     * [targetW]×[targetH] — the wallpaper preset pipeline.
     */
    fun centerCropScale(src: BufferedImage, targetW: Int, targetH: Int): BufferedImage {
        val targetAspect = targetW.toFloat() / targetH
        val srcAspect = src.width.toFloat() / src.height
        val (cropW, cropH) = if (srcAspect > targetAspect) {
            // Source is wider: crop the sides.
            (src.height * targetAspect).toInt().coerceAtLeast(1) to src.height
        } else {
            src.width to (src.width / targetAspect).toInt().coerceAtLeast(1)
        }
        val x = (src.width - cropW) / 2
        val y = (src.height - cropH) / 2
        val cropped = src.getSubimage(x, y, cropW, cropH)
        return BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB).also {
            val g = it.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(cropped, 0, 0, targetW, targetH, null)
            g.dispose()
        }
    }

    /** Aspect-preserving thumbnail with the longest edge at [maxDim]. */
    fun thumbnail(src: BufferedImage, maxDim: Int): BufferedImage {
        val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
            val g = it.createGraphics()
            g.drawImage(src, 0, 0, w, h, null)
            g.dispose()
        }
    }

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
