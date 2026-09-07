package com.playfieldportal.studio

import java.awt.image.BufferedImage
import java.io.File
import org.jcodec.api.SequenceEncoder
import org.jcodec.scale.AWTUtil

/**
 * Builds a tiny real MP4 with JCodec's own encoder — the same library the import gate reads
 * with, so the fixture exercises the exact H.264/MP4 path [VideoCodecs] accepts (a synthetic
 * box structure can't: the ladder's last check is a genuine header parse).
 */
object MotionTestMedia {

    /** Writes a trivial H.264 MP4 whose frame `i` is a flat, per-frame-different colour. */
    fun writeTestMp4(file: File, width: Int = 320, height: Int = 240, frames: Int = 6) {
        val encoder = SequenceEncoder.createSequenceEncoder(file, 10)
        for (i in 0 until frames) {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val shade = ((i + 1) * 40) and 0xFF
            for (x in 0 until width) for (y in 0 until height) {
                img.setRGB(x, y, (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade)
            }
            encoder.encodeNativeFrame(AWTUtil.fromBufferedImageRGB(img))
        }
        encoder.finish()
    }
}
