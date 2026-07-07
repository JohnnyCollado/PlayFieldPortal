package com.playfieldportal.studio.io

import com.playfieldportal.themekit.PtfUnpacker
import java.io.File

/**
 * Writes a [PtfUnpacker.Dump] to disk as reference assets for theme authors: every
 * decoded image as a PNG (`slot{N}_res{M}.png`, the wallpaper also as `wallpaper.png`),
 * non-image payloads as `.bin`, plus a `report.txt` inventory.
 */
object PtfUnpackWriter {

    data class Summary(val images: Int, val other: Int, val failed: Int)

    fun write(dump: PtfUnpacker.Dump, outDir: File): Summary {
        outDir.mkdirs()
        var images = 0
        var other = 0
        var failed = 0
        val report = StringBuilder("theme: ${dump.name}\nfirmware: ${dump.firmware}\n\n")

        for (res in dump.resources) {
            val base = "slot${res.slotId}_res${res.sequence}"
            val image = res.image
            val payload = res.payload
            when {
                image != null -> {
                    val img = java.awt.image.BufferedImage(
                        image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB,
                    )
                    img.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
                    val png = ImageCodecs.toPngBytes(img)
                    File(outDir, "$base.png").writeBytes(png)
                    // The wallpaper slot's BMP is the asset authors want first — alias it.
                    if (res.slotId == 1 && res.kind == PtfUnpacker.Resource.Kind.BMP) {
                        File(outDir, "wallpaper.png").writeBytes(png)
                    }
                    images++
                    report.appendLine("$base.png  ${image.width}x${image.height}  (${res.kind})")
                }
                payload != null -> {
                    File(outDir, "$base.bin").writeBytes(payload)
                    other++
                    report.appendLine("$base.bin  ${payload.size} bytes")
                }
                else -> {
                    failed++
                    report.appendLine("$base  FAILED to decompress")
                }
            }
        }
        File(outDir, "report.txt").writeText(report.toString())
        return Summary(images = images, other = other, failed = failed)
    }
}
